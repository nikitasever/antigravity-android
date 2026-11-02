'use strict';
// QA/Security-аудит Этапов 2–7 (EXTENSIONS_INTEGRATION_PLAN.md): точечные
// проверки 9 подтверждённых дефектов после исправлений:
//   Critical #1 — валидация имени пакета + whitelist путей в server.js;
//   Critical #2 — inflight-гонка McpClientManager (probe/callTool/disconnect);
//   High #1   — атомарный saveRegistryIndex + сериализация мутаций реестра;
//   High #2   — таймаут убивает дерево процессов, промис завершается гарантированно;
//   High #3   — рекурсивный redactDeep + секреты из args в collectSecrets;
//   Medium #3 — statSync до readFileSync (лимит 64 МБ) в readSourceEntries;
//   Medium #4 — блок drive-relative "C:foo" + staging/откат в importPackage.
// Тесты используют РЕАЛЬНЫЕ процессы (stdio MCP-сервер, скрипты с внучатыми
// процессами) — работоспособность доказывается запуском.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { spawn } = require('node:child_process');
const test = require('node:test');
const assert = require('node:assert/strict');

const registry = require('../lib/extension-registry');
const archive = require('../lib/extension-archive');
const { McpClientManager, buildMcpDescriptor, collectSecrets, describeDescriptor } = require('../lib/mcp-client');
const scriptRunner = require('../lib/script-runner');
const hookEngine = require('../lib/hook-engine');
const runtime = require('../lib/extension-runtime');

const NODE = process.execPath;
const FIXTURES = path.join(__dirname, 'fixtures', 'security-qa');
const SERVER_JS = path.join(FIXTURES, 'count-stdio-server.js');
const HOLDER_JS = path.join(FIXTURES, 'grandchild-holder.js');

function makeTempDir(prefix = 'ag-secqa-') {
    return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function q(p) {
    return `"${String(p).replace(/"/g, '""')}"`;
}

// ---------------------------------------------------------------------------
// Critical #1: валидация имени пакета
// ---------------------------------------------------------------------------

test('Critical #1: validatePackageName отклоняет опасные имена', () => {
    const bad = ['', '   ', 'a/b', 'a\\b', 'a:b', '..', 'x/../y', 'x..y', '-lead', '.lead',
        'has space', 'кириллица', 'a'.repeat(65), 'name?'];
    for (const name of bad) {
        const r = registry.validatePackageName(name);
        assert.equal(r.valid, false, `имя должно быть отклонено: ${JSON.stringify(name)}`);
        assert.ok(r.issues.length > 0, `для ${JSON.stringify(name)} указана причина`);
    }
    const good = ['alpha', 'my-plugin_1.0', 'a', 'control-instruction-skill', 'Z9'];
    for (const name of good) {
        const r = registry.validatePackageName(name);
        assert.equal(r.valid, true, `имя должно пройти: ${name} (${r.issues.join('; ')})`);
    }
    // raw === path.basename(raw): сегмент пути не должен быть многосоставным.
    assert.equal(registry.validatePackageName('pkg/evil').valid, false);
    assert.equal(registry.validatePackageName('pkg\\evil').valid, false);
});

test('Critical #1: importPackage отклоняет manifest.name с обходом путей', () => {
    const targetRoot = makeTempDir();
    const registryIndexPath = path.join(targetRoot, 'index.json');
    const names = ['../evil', 'a/b', 'a:b', '..x', 'x'.repeat(65), ''];
    for (const evil of names) {
        const buf = archive.writeZipEntries([
            { name: 'pkg/plugin.json', data: JSON.stringify({ name: evil, version: '1.0.0' }) },
            { name: 'pkg/skills/one/SKILL.md', data: '---\nname: one\ndescription: d\n---\nBody' }
        ]);
        const res = registry.importPackage({ buffer: buf, kind: 'zip', targetRoot, registryIndexPath });
        assert.equal(res.ok, false, `имя ${JSON.stringify(evil)} должно отклоняться`);
        assert.match(String(res.error), /валидаци|Импорт отклонён/);
    }
    // Ничего из отклонённого не записано на диск.
    const leftovers = fs.readdirSync(targetRoot).filter(n => n !== 'index.json');
    assert.deepEqual(leftovers, [], 'отклонённые пакеты не оставляют файлов');
});

// ---------------------------------------------------------------------------
// Critical #1: whitelist путей в server.js (реальный код извлекается из server.js)
// ---------------------------------------------------------------------------

function extractServerPathGuard() {
    const src = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
    // Извлекаются ТОЛЬКО нужные константы и функция (без соседнего кода).
    const pick = (re, label) => {
        const m = src.match(re);
        assert.ok(m, `${label} найден в server.js`);
        return m[0];
    };
    const consts = [
        pick(/^const GEMINI_ROOT_DIR = .*$/m, 'GEMINI_ROOT_DIR'),
        pick(/^const PLUGINS_DIR = .*$/m, 'PLUGINS_DIR'),
        pick(/^const DEFAULT_WORKSPACE = .*$/m, 'DEFAULT_WORKSPACE'),
        pick(/^const UPLOADS_DIR = .*$/m, 'UPLOADS_DIR'),
        pick(/^const ALLOWED_EXTENSION_ROOTS = .*$/m, 'ALLOWED_EXTENSION_ROOTS')
    ].join('\n');
    const fnStart = src.indexOf('function resolveInsideAllowedRoots');
    assert.ok(fnStart >= 0, 'resolveInsideAllowedRoots найден в server.js');
    // Находим закрывающую скобку функции по балансу.
    let depth = 0;
    let i = src.indexOf('{', fnStart);
    for (; i < src.length; i++) {
        if (src[i] === '{') depth++;
        else if (src[i] === '}') { depth--; if (depth === 0) break; }
    }
    const chunk = consts + '\n' + src.slice(fnStart, i + 1);
    const sandbox = { path, module: { exports: {} }, exports: {} };
    vm.runInNewContext(chunk + '\n;__out = { resolveInsideAllowedRoots, ALLOWED_EXTENSION_ROOTS };', sandbox);
    return sandbox.__out;
}

test('Critical #1: пути вне разрешённых корней server.js отклоняются', () => {
    const { resolveInsideAllowedRoots, ALLOWED_EXTENSION_ROOTS } = extractServerPathGuard();
    assert.ok(ALLOWED_EXTENSION_ROOTS.length >= 3, 'три разрешённых корня');

    const okPath = path.join(ALLOWED_EXTENSION_ROOTS[1], 'exports', 'x.zip');
    assert.equal(resolveInsideAllowedRoots(okPath, 'outPath').ok, true, 'внутри корня — разрешено');
    assert.equal(resolveInsideAllowedRoots(ALLOWED_EXTENSION_ROOTS[0], 'targetRoot').ok, true, 'сам корень — разрешено');

    const evil = [
        path.join(os.tmpdir(), 'evil.zip'),
        'C:\\Windows\\Temp\\evil.zip',
        path.join(ALLOWED_EXTENSION_ROOTS[0], '..', '..', 'escape.zip'),
        '/etc/passwd'
    ];
    for (const p of evil) {
        const r = resolveInsideAllowedRoots(p, 'sourcePath');
        assert.equal(r.ok, false, `путь должен отклоняться: ${p}`);
        assert.match(String(r.error), /вне разрешённых корней/);
    }
    assert.equal(resolveInsideAllowedRoots('', 'sourcePath').ok, false);
    assert.equal(resolveInsideAllowedRoots('   ', 'outPath').ok, false);
});

// ---------------------------------------------------------------------------
// Critical #2: McpClientManager — inflight-гонка probe/callTool/disconnect
// ---------------------------------------------------------------------------

function countStarts(markerFile) {
    try { return fs.readFileSync(markerFile, 'utf8').split('\n').filter(l => l === 'start').length; }
    catch (_) { return 0; }
}

function stdioDescriptor(markerFile, delayMs = 0) {
    return buildMcpDescriptor('qa-secret-echo', {
        command: NODE,
        args: [SERVER_JS, '--token', 'SECRET_ARG_VALUE_98765'],
        env: { QA_MARKER_FILE: markerFile, QA_DELAY_MS: String(delayMs), QA_TOKEN: 'SECRET_ENV_VALUE_12345' }
    });
}

test('Critical #2: параллельные probe одного сервера разделяют один процесс', async () => {
    const tmp = makeTempDir();
    const markerFile = path.join(tmp, 'markers.txt');
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 } });
    try {
        const desc = stdioDescriptor(markerFile, 300);
        const [a, b, c] = await Promise.all([
            manager.probe(desc),
            manager.probe(desc),
            manager.probe(desc)
        ]);
        assert.equal(a.ok, true, `probe успешен: ${a.error || ''}`);
        assert.equal(b.ok, true);
        assert.equal(c.ok, true);
        assert.equal(countStarts(markerFile), 1, 'три параллельных probe — один фактический запуск процесса');
        assert.equal(a.probedAt, b.probedAt, 'результаты разделяют один общий probe');
        assert.equal(manager.inflight.size, 0, 'очередь inflight очищена');
    } finally {
        await manager.closeAll();
    }
});

test('Critical #2: callTool во время подключения дожидается probe, не запуская второй', async () => {
    const tmp = makeTempDir();
    const markerFile = path.join(tmp, 'markers.txt');
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 } });
    try {
        const desc = stdioDescriptor(markerFile, 400);
        // Probe стартует, callTool приходит в окно connecting.
        const probePromise = manager.probe(desc);
        const callPromise = manager.callTool('qa-secret-echo', 'echo', { text: 'x' }, { descriptor: desc });
        const [probe, call] = await Promise.all([probePromise, callPromise]);
        assert.equal(probe.ok, true);
        assert.equal(call.ok, true, `вызов успешен: ${call.error || ''}`);
        assert.equal(countStarts(markerFile), 1, 'callTool не создал второе подключение');
    } finally {
        await manager.closeAll();
    }
});

test('Critical #2: disconnect во время probe дожидается его и очищает сессию', async () => {
    const tmp = makeTempDir();
    const markerFile = path.join(tmp, 'markers.txt');
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 } });
    const desc = stdioDescriptor(markerFile, 250);
    const probePromise = manager.probe(desc);
    await manager.disconnect('qa-secret-echo');
    await probePromise;
    assert.equal(manager.sessions.size, 0, 'сессия очищена');
    assert.equal(manager.inflight.size, 0, 'inflight очищен');
    await manager.closeAll();
});

// ---------------------------------------------------------------------------
// High #1: атомарная запись индекса + сериализация мутаций
// ---------------------------------------------------------------------------

test('High #1: saveRegistryIndex атомарен (tmp + rename, без остатков)', () => {
    const tmp = makeTempDir();
    const indexPath = path.join(tmp, 'reg', 'index.json');
    const seen = [];
    const origWrite = fs.writeFileSync;
    const origRename = fs.renameSync;
    fs.writeFileSync = function (p, ...rest) {
        if (String(p).endsWith('.tmp')) seen.push(['write-tmp', path.basename(String(p))]);
        return origWrite.call(fs, p, ...rest);
    };
    fs.renameSync = function (from, to) {
        seen.push(['rename', path.basename(String(from)), path.basename(String(to))]);
        return origRename.call(fs, from, to);
    };
    try {
        registry.saveRegistryIndex(indexPath, { version: 1, packages: { a: { id: 'skill:a' } } });
    } finally {
        fs.writeFileSync = origWrite;
        fs.renameSync = origRename;
    }
    assert.ok(seen.some(s => s[0] === 'write-tmp'), 'пишется временный .tmp-файл');
    assert.ok(seen.some(s => s[0] === 'rename'), 'финализируется rename-ом');
    const loaded = registry.loadRegistryIndex(indexPath);
    assert.equal(loaded.packages.a.id, 'skill:a');
    const leftovers = fs.readdirSync(path.dirname(indexPath)).filter(n => n.endsWith('.tmp'));
    assert.deepEqual(leftovers, [], 'временные файлы не остаются');
});

test('High #1: withRegistryLock сериализует параллельные мутации', async () => {
    const order = [];
    let releaseFirst;
    const firstGate = new Promise(r => { releaseFirst = r; });
    const first = registry.withRegistryLock(async () => {
        order.push('first-start');
        await firstGate;
        order.push('first-end');
    });
    const second = registry.withRegistryLock(async () => {
        order.push('second-start');
        order.push('second-end');
    });
    // Пока первая мутация не завершена, вторая не стартует.
    await new Promise(r => setTimeout(r, 50));
    assert.deepEqual(order, ['first-start'], 'вторая мутация ждёт первую');
    releaseFirst();
    await Promise.all([first, second]);
    assert.deepEqual(order, ['first-start', 'first-end', 'second-start', 'second-end']);
});

// ---------------------------------------------------------------------------
// High #2: таймаут убивает дерево процессов, промис завершается гарантированно
// ---------------------------------------------------------------------------

test('High #2: таймаут script-runner убивает дерево и завершает промис', async () => {
    const started = Date.now();
    const run = await scriptRunner.runScriptIsolated({ scriptPath: HOLDER_JS, timeoutMs: 500, keepWorkDir: true });
    const elapsed = Date.now() - started;
    assert.equal(run.timedOut, true, 'зафиксирован таймаут');
    assert.equal(run.ok, false);
    assert.ok(elapsed < 10000, `промис завершился гарантированно (${elapsed} мс), не висит из-за внучатого процесса`);
});

test('High #2: таймаут hook-engine убивает дерево и завершает промис', async () => {
    const started = Date.now();
    const res = await hookEngine.defaultRunCommand({
        command: `${q(NODE)} ${q(HOLDER_JS)}`,
        cwd: FIXTURES,
        env: process.env,
        timeoutMs: 500
    });
    const elapsed = Date.now() - started;
    assert.equal(res.timedOut, true, 'зафиксирован таймаут');
    assert.ok(elapsed < 10000, `промис завершился гарантированно (${elapsed} мс)`);
});

// ---------------------------------------------------------------------------
// High #3: рекурсивный redactDeep + секреты из args
// ---------------------------------------------------------------------------

test('High #3: collectSecrets и describeDescriptor учитывают секреты в args', () => {
    const desc = buildMcpDescriptor('qa', {
        command: NODE,
        args: ['srv.js', '--token', 'SECRET_ARG_VALUE_98765', '--access-token=SECRET_EQ_11111', '--api-key', 'SECRET_KEY_22222'],
        headers: { Authorization: 'Bearer SECRET_HEADER_33333' },
        env: { T: 'SECRET_ENV_44444' }
    });
    const secrets = collectSecrets(desc);
    for (const s of ['SECRET_ARG_VALUE_98765', 'SECRET_EQ_11111', 'SECRET_KEY_22222', 'SECRET_HEADER_33333', 'SECRET_ENV_44444']) {
        assert.ok(secrets.includes(s), `секрет из args/headers/env собран: ${s}`);
    }
    const described = JSON.stringify(describeDescriptor(desc));
    assert.ok(!described.includes('SECRET_ARG_VALUE_98765'), 'describeDescriptor маскирует секреты args');
    assert.ok(!described.includes('SECRET_EQ_11111'), 'маскируется --flag=value');
});

test('High #3: redactDeep маскирует секреты во всех полях результата', async () => {
    const tmp = makeTempDir();
    const markerFile = path.join(tmp, 'markers.txt');
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 } });
    try {
        const desc = stdioDescriptor(markerFile);
        await manager.probe(desc);
        const payload = 'a SECRET_ARG_VALUE_98765 b SECRET_ENV_VALUE_12345 c';
        const call = await manager.callTool('qa-secret-echo', 'echo', { text: payload });
        assert.equal(call.ok, true, `вызов успешен: ${call.error || ''}`);
        const raw = JSON.stringify(call.result);
        assert.ok(!raw.includes('SECRET_ARG_VALUE_98765'), 'секрет из args замаскирован в content');
        assert.ok(!raw.includes('SECRET_ENV_VALUE_12345'), 'секрет из env замаскирован в content');
        assert.ok(raw.includes('скрыто'), 'замена видима');
        // structuredContent и вложенные поля замаскированы (redactDeep, не плоский обход).
        assert.ok(!JSON.stringify(call.result.structuredContent || {}).includes('SECRET_'),
            'structuredContent замаскирован');
        assert.ok(!JSON.stringify(call.result.extra || {}).includes('SECRET_'),
            'произвольное вложенное поле замаскировано');
        // toolResultToText работает с отредактированным объектом и видит structuredContent.
        const asText = runtime.toolResultToText({ ok: true, result: call.result });
        assert.ok(!asText.includes('SECRET_'), 'текст результата без секретов');
        assert.ok(asText.length > 0);
    } finally {
        await manager.closeAll();
    }
});

// ---------------------------------------------------------------------------
// Medium #3: размер архива проверяется до чтения файла
// ---------------------------------------------------------------------------

test('Medium #3: readSourceEntries отклоняет архив > 64 МБ до чтения в память', () => {
    const tmp = makeTempDir();
    const big = path.join(tmp, 'big.zip');
    fs.writeFileSync(big, '');
    fs.truncateSync(big, 65 * 1024 * 1024); // разреженный файл 65 МБ
    assert.throws(
        () => registry.readSourceEntries({ sourcePath: big, kind: 'zip' }),
        /больше лимита/,
        'гигантский файл отклоняется ДО readFileSync'
    );
});

// ---------------------------------------------------------------------------
// Medium #4: drive-relative пути и staging/откат importPackage
// ---------------------------------------------------------------------------

test('Medium #4: inspectEntryName блокирует Windows drive-relative "C:foo"', () => {
    const r = archive.inspectEntryName('C:foo');
    assert.ok(r.issues.length > 0, 'drive-relative путь отклонён');
    assert.ok(r.issues.some(i => /drive-relative/.test(i)), `причина указана: ${r.issues.join('; ')}`);
    // Абсолютные и обходные пути по-прежнему блокируются.
    assert.ok(archive.inspectEntryName('C:\\abs\\x').issues.length > 0);
    assert.ok(archive.inspectEntryName('pkg/../x').issues.some(i => /обход/.test(i)));
});

test('Medium #4: importPackage откатывается через staging при прерывании записи', () => {
    const tmp = makeTempDir();
    const src = path.join(tmp, 'src-pkg');
    fs.mkdirSync(path.join(src, 'skills', 'one'), { recursive: true });
    fs.writeFileSync(path.join(src, 'plugin.json'), JSON.stringify({ name: 'rollback-demo', version: '1.0.0' }));
    fs.writeFileSync(path.join(src, 'skills', 'one', 'SKILL.md'), '---\nname: one\ndescription: d\n---\nBody');
    fs.writeFileSync(path.join(src, 'skills', 'one', 'extra.txt'), 'data');

    const targetRoot = path.join(tmp, 'target');
    const registryIndexPath = path.join(tmp, 'index.json');

    // Вторая запись файла падает — импорт обязан откатиться полностью.
    let writes = 0;
    const origWrite = fs.writeFileSync;
    fs.writeFileSync = function (p, ...rest) {
        if (String(p).includes(`${path.sep}skills${path.sep}`) || String(p).endsWith('extra.txt')) {
            writes++;
            if (writes >= 2) throw new Error('диск недоступен (тест)');
        }
        return origWrite.call(fs, p, ...rest);
    };
    let res;
    try {
        res = registry.importPackage({ sourcePath: src, kind: 'dir', targetRoot, registryIndexPath });
    } finally {
        fs.writeFileSync = origWrite;
    }
    assert.equal(res.ok, false, 'импорт с ошибкой записи не успешен');
    assert.match(String(res.error), /прервана/);
    // Полный откат: ни staging-каталогов, ни полузаписанного пакета.
    const leftovers = fs.existsSync(targetRoot)
        ? fs.readdirSync(targetRoot).filter(n => n !== 'index.json')
        : [];
    assert.deepEqual(leftovers, [], 'staging удалён, полузаписанный пакет не остаётся');
    const idx = registry.loadRegistryIndex(registryIndexPath);
    assert.deepEqual(Object.keys(idx.packages), [], 'индекс не получил запись о прерванном импорте');

    // Успешный импорт после сбоя — staging не мешает, пакет на месте.
    const ok = registry.importPackage({ sourcePath: src, kind: 'dir', targetRoot, registryIndexPath });
    assert.equal(ok.ok, true, `повторный импорт успешен: ${ok.error || ''}`);
    assert.ok(fs.existsSync(path.join(targetRoot, 'rollback-demo', 'plugin.json')));
    const stagingLeft = fs.readdirSync(targetRoot).filter(n => n.startsWith('.staging-'));
    assert.deepEqual(stagingLeft, [], 'staging-каталоги не остаются после успешного импорта');
});
