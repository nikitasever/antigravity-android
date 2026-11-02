'use strict';
// Этап 7 (EXTENSIONS_INTEGRATION_PLAN.md): сквозные контрольные пакеты и полный
// проход инвентаря. Пакеты прогоняются через РЕАЛЬНУЮ инфраструктуру:
// реестр расширений (установка/включение), загрузка навыков, script-runner
// (зависимости + изоляция), MCP-клиент (настоящее рукопожатие HTTP и stdio),
// hook-engine (контракт stdin JSON → stdout JSON). Проход инвентаря проверяется
// по сгенерированному extensions-migration-coverage.json: каждая позиция имеет
// адаптацию и сценарий проверки, нерешённые зависимости — в backlog с владельцем
// и следующим шагом, общий перенос не объявляется завершённым при блокировках.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const test = require('node:test');
const assert = require('node:assert/strict');

const registry = require('../lib/extension-registry');
const runtime = require('../lib/extension-runtime');
const scriptRunner = require('../lib/script-runner');
const formats = require('../lib/extension-formats');
const { McpClient, buildMcpDescriptor, McpClientError } = require('../lib/mcp-client');
const hookEngine = require('../lib/hook-engine');
const adapters = require('../lib/component-adapters');
const coverageLib = require('../scripts/build-migration-coverage');

const PROJECT_ROOT = path.join(__dirname, '..', '..');
const FIXTURES = path.join(__dirname, 'fixtures', 'stage7');
const NODE_EXE = process.execPath;

function makeTempDir(prefix = 'ag-stage7-') {
    return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function q(p) {
    return `"${String(p).replace(/"/g, '""')}"`;
}

// ---------------------------------------------------------------------------
// Контрольный пакет 1: инструкционный навык — установка → выбор → инструкции
// ТОЛЬКО выбранного навыка; выключенный навык — честный отказ.
// ---------------------------------------------------------------------------

test('контрольный пакет 1: инструкционный навык — установка, выбор, загрузка инструкций', () => {
    const source = path.join(FIXTURES, 'control-instruction-skill');
    const targetRoot = makeTempDir();
    const registryIndexPath = path.join(targetRoot, 'registry-index.json');

    const imported = registry.importPackage({ sourcePath: source, targetRoot, registryIndexPath });
    assert.equal(imported.ok, true, `импорт должен пройти: ${imported.error || ''}`);

    const skillRoots = [{ dir: targetRoot, source: 'stage7-control' }];
    const catalog = runtime.buildSkillCatalog(skillRoots);
    const hit = catalog.find(s => s.name === 'control-instruction-skill');
    assert.ok(hit, 'навык найден в каталоге после установки');
    assert.equal(hit.enabled, true, 'после установки навык включён');

    const detected = runtime.detectSkillInvocation('/control-instruction-skill проверка сценария', catalog);
    assert.equal(detected.skillId, 'control-instruction-skill');

    const loaded = runtime.loadSelectedSkill(detected.skillId, { skillRoots });
    assert.equal(loaded.status, 'ok', `загрузка успешна: ${JSON.stringify(loaded.refusal || null)}`);
    assert.ok(loaded.skill, 'инструкции загружены');
    assert.ok(String(loaded.skill.instructions).includes('CONTROL-INSTRUCTION-SKILL-LOADED'),
        'инструкция выбранного навыка содержит маркер сценария');

    const block = runtime.composeSkillInstructionBlock(loaded.skill);
    assert.ok(block.includes('<SKILL_INSTRUCTION name="control-instruction-skill"'), 'блок инструкции именован');
    assert.ok(block.includes('CONTROL-INSTRUCTION-SKILL-LOADED'), 'маркер в блоке инструкции');

    // Статусы различаются: выключенный навык не выдаёт инструкции.
    const importedDir = path.join(targetRoot, 'control-instruction-skill');
    fs.renameSync(path.join(importedDir, 'SKILL.md'), path.join(importedDir, 'SKILL.md.disabled'));
    const catalogDisabled = runtime.buildSkillCatalog(skillRoots);
    const loadedDisabled = runtime.loadSelectedSkill('control-instruction-skill', { skillRoots, catalog: catalogDisabled });
    assert.equal(loadedDisabled.status, 'disabled', 'выключенный навык — статус disabled, не ok');
    assert.ok(loadedDisabled.refusal, 'отказ видим');
});

// ---------------------------------------------------------------------------
// Контрольный пакет 2: навык со скриптом — зависимости ДО запуска + изоляция
// ---------------------------------------------------------------------------

test('контрольный пакет 2: навык со скриптом — зависимости до запуска и изолированный запуск', async () => {
    const source = path.join(FIXTURES, 'control-script-skill');
    const targetRoot = makeTempDir();
    const registryIndexPath = path.join(targetRoot, 'registry-index.json');
    const imported = registry.importPackage({ sourcePath: source, targetRoot, registryIndexPath });
    assert.equal(imported.ok, true, `импорт навыка со скриптом: ${imported.error || ''}`);

    const scriptPath = path.join(targetRoot, 'control-script-skill', 'scripts', 'make-report.js');
    assert.ok(fs.existsSync(scriptPath), 'скрипт установлен вместе с навыком');

    // Зависимости проверяются ДО запуска: интерпретатор и внешние require.
    const deps = scriptRunner.checkScriptDependencies({ scriptPath });
    assert.equal(deps.interpreter.kind, 'node', 'интерпретатор определён');
    assert.equal(deps.ready, true, `скрипт готов к запуску: ${JSON.stringify(deps.missing)}`);
    assert.ok(deps.dependencies.some(d => d.name === 'control-dep' && d.satisfied === true),
        'зависимость пакета (node_modules/control-dep) видна и удовлетворена');
    assert.equal(deps.missing.length, 0, 'недостающих зависимостей нет');

    // Реальный запуск в изолированном рабочем каталоге.
    const run = await scriptRunner.runScriptIsolated({ scriptPath, args: ['этап7'], timeoutMs: 30000 });
    assert.equal(run.ok, true, `скрипт выполнился: ${run.blocker || run.stderr || ''}`);
    assert.ok(run.stdout.includes('CONTROL-SCRIPT-SKILL-OK'), 'маркер сценария в результате');
    assert.notEqual(run.cwd, path.dirname(scriptPath), 'рабочий каталог изолирован от каталога скрипта');

    // Отсутствующая зависимость — видимый блокер ДО запуска, не молчаливый прогон.
    const missingScript = path.join(FIXTURES, 'control-composite-plugin', 'scripts', 'needs-missing.js');
    const badDeps = scriptRunner.checkScriptDependencies({ scriptPath: missingScript });
    assert.equal(badDeps.ready, false, 'скрипт с отсутствующей зависимостью не готов');
    assert.ok(badDeps.missing.includes('stage7-not-installed'), 'недостающая зависимость названа');
    const badRun = await scriptRunner.runScriptIsolated({ scriptPath: missingScript, timeoutMs: 30000 });
    assert.equal(badRun.ok, false, 'запуск с недостающей зависимостью не притворяется успешным');
    assert.ok(badRun.blocker, 'блокер видим');
    assert.ok(!badRun.stdout.includes('недостижимо'), 'скрипт не выполнялся молча');
});

// ---------------------------------------------------------------------------
// Контрольный пакет 3: HTTP MCP — настоящее рукопожатие Streamable HTTP
// ---------------------------------------------------------------------------

test('контрольный пакет 3: HTTP MCP — рукопожатие, tools/list, tools/call', async () => {
    const pkgDir = path.join(FIXTURES, 'control-http-mcp');
    const rawConfig = JSON.parse(fs.readFileSync(path.join(pkgDir, 'mcp.json'), 'utf8'));
    const parsed = formats.parseMcpConfig(rawConfig);
    assert.equal(parsed.servers.length, 1);
    assert.equal(parsed.servers[0].transport, 'streamable-http', 'транспорт из конфига пакета');
    assert.ok(parsed.servers[0].url.includes('18791'), 'URL сервера зафиксирован в пакете');

    const serverProc = spawn(NODE_EXE, [path.join(pkgDir, 'server.js')], { stdio: ['ignore', 'pipe', 'pipe'] });
    try {
        await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('HTTP MCP-сервер контрольного пакета не поднялся')), 8000);
            serverProc.stdout.on('data', (d) => {
                if (String(d).includes('ready')) { clearTimeout(timer); resolve(); }
            });
            serverProc.on('exit', (code) => { clearTimeout(timer); reject(new Error(`сервер умер: ${code}`)); });
        });

        const descriptor = buildMcpDescriptor('stage7-http-echo', rawConfig.mcpServers['stage7-http-echo']);
        const client = new McpClient(descriptor, { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 });
        assert.equal(client.summary().state, 'idle', 'до рукопожатия статус не «подключён»');

        const summary = await client.connect();
        assert.equal(summary.state, 'connected', 'статус connected только после рукопожатия');

        const tools = await client.listTools();
        assert.ok(JSON.stringify(tools).includes('stage7_http_echo'), 'инструмент из живого tools/list');

        const result = await client.callTool('stage7_http_echo', { text: 'этап7' });
        assert.ok(JSON.stringify(result).includes('CONTROL-HTTP-MCP-OK:этап7'), 'tools/call вернул результат сервера');

        await client.close();
    } finally {
        serverProc.kill();
    }
});

// ---------------------------------------------------------------------------
// Контрольный пакет 4: stdio MCP — запуск процесса, рукопожатие, вызов,
// видимая ошибка при падении процесса
// ---------------------------------------------------------------------------

test('контрольный пакет 4: stdio MCP — рукопожатие, tools/call, видимое падение', async () => {
    const pkgDir = path.join(FIXTURES, 'control-stdio-mcp');
    const rawConfig = JSON.parse(fs.readFileSync(path.join(pkgDir, 'mcp.json'), 'utf8'));
    const parsed = formats.parseMcpConfig(rawConfig);
    assert.equal(parsed.servers[0].transport, 'stdio', 'транспорт stdio из конфига пакета');
    assert.equal(parsed.servers[0].command, 'node');

    // Команда «node» из конфига разрешается на интерпретатор запуска (node не в
    // PATH этого окружения), args — на скрипт внутри пакета; cwd — каталог пакета.
    const descriptor = buildMcpDescriptor('stage7-stdio-echo', rawConfig.mcpServers['stage7-stdio-echo']);
    descriptor.command = NODE_EXE;
    descriptor.args = [path.join(pkgDir, 'server.js')];
    descriptor.cwd = pkgDir;

    const client = new McpClient(descriptor, { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 });
    assert.equal(client.summary().state, 'idle', 'до рукопожатия статус не «подключён»');
    const summary = await client.connect();
    assert.equal(summary.state, 'connected', 'статус connected только после рукопожатия');

    const tools = await client.listTools();
    assert.ok(JSON.stringify(tools).includes('stage7_stdio_echo'), 'инструмент из живого tools/list');

    const result = await client.callTool('stage7_stdio_echo', { text: 'процесс' });
    assert.ok(JSON.stringify(result).includes('CONTROL-STDIO-MCP-OK:процесс'), 'tools/call вернул результат процесса');
    await client.close();

    // Падение процесса — видимая ошибка, не тихий успех.
    const broken = buildMcpDescriptor('stage7-stdio-broken', { command: NODE_EXE, args: ['-e', 'process.exit(3)'] });
    const brokenClient = new McpClient(broken, { handshakeTimeoutMs: 4000, requestTimeoutMs: 4000 });
    await assert.rejects(() => brokenClient.connect(), (err) => {
        assert.ok(err instanceof McpClientError, 'ошибка типа McpClientError');
        assert.ok(['connect_failed', 'process_exit', 'handshake_timeout'].includes(err.code),
            `код ошибки честный: ${err.code}`);
        return true;
    });
});

// ---------------------------------------------------------------------------
// Контрольный пакет 5: составной плагин с зависимостями — состав, адаптеры,
// hook по официальному контракту, скрипты с проверкой зависимостей
// ---------------------------------------------------------------------------

test('контрольный пакет 5: составной плагин — адаптеры, hook-контракт, зависимости', async () => {
    const pluginDir = path.join(FIXTURES, 'control-composite-plugin');

    // Состав и адаптеры компонентов (Этап 5) по фактическим файлам пакета.
    const analysis = adapters.analyzePluginAdapters(pluginDir);
    assert.equal(analysis.exists, true, 'каталог плагина найден');
    const kinds = new Set(analysis.records.map(r => r.componentType));
    assert.ok(kinds.has('skill'), 'навык в составе');
    assert.ok(kinds.has('hook'), 'hook в составе');
    assert.ok(kinds.has('executable'), 'скрипты в составе');
    for (const r of analysis.records) {
        assert.ok(r.strategy, `стратегия у ${r.componentType}`);
        assert.ok(r.status, `статус у ${r.componentType}`);
    }
    assert.ok(analysis.licenseCheck, 'право распространения проверено отдельно');
    assert.equal(analysis.licenseCheck.rightToRedistribute, 'granted',
        'MIT в manifest — право распространения подтверждено условиями лицензии');
    assert.ok(analysis.support, 'сводная оценка поддержки посчитана');

    // Hook по официальному контракту: JSON на stdin → JSON на stdout.
    const hooksRaw = JSON.parse(fs.readFileSync(path.join(pluginDir, 'hooks', 'hooks.json'), 'utf8'));
    const parsedHooks = hookEngine.parseHooksConfig(hooksRaw, { sourcePath: path.join(pluginDir, 'hooks', 'hooks.json') });
    assert.equal(parsedHooks.namedHooks.length, 1);
    const plan = hookEngine.collectEventPlan(parsedHooks.namedHooks, 'PreToolUse', { toolName: 'run_command' });
    assert.equal(plan.runnable.length, 1, 'hook исполняется по событию');

    const handler = plan.runnable[0].handler;
    // Команда «node» из hooks.json разрешается на интерпретатор запуска;
    // контракт cwd — каталог hooks.json.
    handler.command = `${q(NODE_EXE)} ${q(path.join(pluginDir, 'hooks', 'hook-guard.js'))}`;
    const hookRes = await hookEngine.runHookHandler(handler, { event: 'PreToolUse', toolCall: { name: 'run_command' } },
        { cwd: path.join(pluginDir, 'hooks') });
    assert.notEqual(hookRes.status, 'error', `hook выполнился: ${hookRes.stderr || ''}`);
    assert.equal(hookRes.exitCode, 0);
    const hookOut = hookRes.output || JSON.parse(hookRes.rawStdout || '{}');
    assert.equal(hookOut.marker, 'CONTROL-HOOK-OK', 'маркер контракта в stdout hook’а');
    assert.equal(hookOut.ok, true);
    assert.equal(hookOut.event, 'PreToolUse', 'событие передано в stdin и отражено в stdout');

    // Скрипты плагина: зависимости ДО запуска, изолированный запуск.
    const collect = path.join(pluginDir, 'scripts', 'collect.js');
    const deps = scriptRunner.checkScriptDependencies({ scriptPath: collect });
    assert.equal(deps.ready, true, `скрипт готов: ${JSON.stringify(deps.missing)}`);
    const run = await scriptRunner.runScriptIsolated({ scriptPath: collect, timeoutMs: 30000 });
    assert.equal(run.ok, true, `скрипт выполнился: ${run.blocker || run.stderr || ''}`);
    assert.ok(run.stdout.includes('CONTROL-COMPOSITE-SCRIPT-OK'), 'маркер сценария в результате');
});

// ---------------------------------------------------------------------------
// Полный проход инвентаря: адаптация + сценарий проверки у каждой позиции,
// backlog с владельцем и следующим шагом, общий перенос честно «не завершено»
// ---------------------------------------------------------------------------

test('проход инвентаря: 79/79 позиций имеют адаптацию и сценарий проверки', () => {
    const coverage = coverageLib.buildCoverage();
    const inventory = JSON.parse(fs.readFileSync(path.join(PROJECT_ROOT, 'extensions-inventory.json'), 'utf8'));

    assert.equal(coverage.summary.positionsTotal, inventory.positions.length,
        'проход покрывает полный срез без пропусков');
    assert.equal(coverage.summary.positionsTotal, 79, 'зафиксированный срез: 79 позиций');

    const batchIds = new Set(coverageLib.BATCHES.map(b => b.id));
    const seen = new Set();
    for (const pos of coverage.positions) {
        assert.ok(batchIds.has(pos.batch), `позиция ${pos.id} отнесена к пакету прохода`);
        assert.ok(!seen.has(pos.id), `позиция ${pos.id} не задвоена`);
        seen.add(pos.id);
        assert.ok(pos.adaptation && pos.adaptation.supportStatus,
            `адаптация у ${pos.id}: supportStatus зафиксирован`);
        assert.ok(pos.verificationScenario && pos.verificationScenario !== 'не записано',
            `сценарий проверки у ${pos.id} записан`);
        assert.ok(typeof pos.path.basis === 'string' && pos.path.basis.length > 0,
            `подтверждение пути у ${pos.id} обосновано`);
    }

    // Сумма по пакетам равна срезу — каждый позиция прошла ровно один пакет.
    const sum = coverage.batches.reduce((acc, b) => acc + b.positionCount, 0);
    assert.equal(sum, coverage.summary.positionsTotal, 'батчи разбивают срез без потерь и дублей');

    // Путь подтверждён только с доказательством: у подтверждённых есть проверка на диске.
    for (const pos of coverage.positions) {
        if (pos.path.confirmed) {
            assert.ok(pos.path.checked.some(c => c.exists === true),
                `${pos.id}: подтверждённый путь опирается на реальную проверку файлов`);
        }
    }
});

test('проход инвентаря: backlog с владельцем и следующим шагом, статусы честные', () => {
    const coverage = coverageLib.buildCoverage();

    assert.ok(coverage.backlog.length > 0, 'нерешённые зависимости собраны в backlog');
    for (const item of coverage.backlog) {
        assert.ok(item.owner && item.owner.length > 0, `владелец у ${item.id}`);
        assert.ok(item.nextStep && item.nextStep.length > 0, `следующий шаг у ${item.id}`);
        assert.ok(item.rule, `правило решения у ${item.id} зафиксировано`);
    }

    // Общий перенос нельзя объявить завершённым, пока обязательные позиции
    // заблокированы (план, Этап 7).
    if (coverage.summary.mandatoryBlocked.length > 0) {
        assert.equal(coverage.summary.overallMigrationStatus, 'не завершено',
            'при заблокированных обязательных позициях статус «не завершено»');
    }
    assert.equal(coverage.summary.mandatoryBlocked.length, 0,
        'пустой состав четырёх локальных записей исправлен; staging ссылается на канонический пакет');
    assert.notEqual(coverage.summary.overallMigrationStatus, 'завершено',
        'дескриптор не доказывает живую работу всего внешнего каталога');

    // Контрольные пакеты присутствуют как реальные файлы (5 штук).
    assert.equal(coverage.controlPackages.length, 5);
    for (const pkg of coverage.controlPackages) {
        assert.equal(pkg.fixturePresent, true, `фикстура ${pkg.id} существует`);
        assert.ok(pkg.scenario, `сценарий у ${pkg.id} записан`);
    }

    // Артефакт прохода записан на диск и согласован с генератором.
    const written = JSON.parse(fs.readFileSync(path.join(PROJECT_ROOT, 'extensions-migration-coverage.json'), 'utf8'));
    assert.equal(written.summary.positionsTotal, coverage.summary.positionsTotal,
        'записанный артефакт совпадает с генератором по объёму среза');
});
