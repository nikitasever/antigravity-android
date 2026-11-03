const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

function extractFunction(name, context = {}) {
    // Сначала ищем async-форму: срез начнётся с 'async function', и await внутри
    // останется валидным. Обычная 'function name(' найдётся и внутри async.
    let start = source.indexOf(`async function ${name}(`);
    if (start < 0) start = source.indexOf(`function ${name}(`);
    assert.ok(start > 0, `${name} найден в server.js`);
    const end = source.indexOf('\n}', start) + 2;
    return vm.runInNewContext(`(${source.slice(start, end).trim()})`, context);
}

// Этап 0 плана расширений: пустой каталог остаётся пустым — автозасев
// фиктивных плагинов при старте удалён полностью.
test('автозасев фиктивных плагинов отсутствует в server.js', () => {
    assert.ok(!source.includes('seedDefaultPluginsIfEmpty'),
        'seedDefaultPluginsIfEmpty больше не существует');
    assert.ok(!source.includes('antigravity-workspace-guard'),
        'фиктивный пакет по описанию не создаётся на старте');
});

// Просмотр списка навыков ничего не включает: отключённое остаётся отключённым.
// (После выноса управления навыками в lib/skill-store.js поведение проверяется
// через модульный API — прежний vm-срез функций из server.js больше не нужен.)
test('listSkills не реанимирует отключённый навык при просмотре', async t => {
    const { createSkillStore } = require('../lib/skill-store');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stage0-skills-'));
    t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
    const skillsDir = path.join(tmp, 'skills');
    const aseDir = path.join(skillsDir, 'autonomous-software-engineer');
    fs.mkdirSync(aseDir, { recursive: true });
    fs.writeFileSync(path.join(aseDir, 'SKILL.md.disabled'), '---\nname: autonomous-software-engineer\ndescription: "Отключён"\n---\n\nТекст', 'utf8');

    const store = createSkillStore({
        skillRoots: [
            { dir: path.join(tmp, 'workspace-no-skills'), source: 'workspace' },
            { dir: skillsDir, source: 'global' },
            { dir: path.join(tmp, 'builtin-no-skills'), source: 'builtin' }
        ],
        writeDir: skillsDir
    });

    const before = fs.existsSync(path.join(aseDir, 'SKILL.md'));
    assert.equal(before, false, 'до просмотра активного файла нет');
    const skills = store.listSkills();
    const ase = skills.find(s => s.name === 'autonomous-software-engineer');
    assert.ok(ase, 'отключённый навык виден в списке');
    assert.equal(ase.enabled, false, 'но остаётся отключённым');
    assert.equal(fs.existsSync(path.join(aseDir, 'SKILL.md')), false,
        'просмотр не создал активный SKILL.md');

    // Второй просмотр — тот же результат, отключение переживает любые вызовы.
    const second = store.listSkills().find(s => s.name === 'autonomous-software-engineer');
    assert.equal(second.enabled, false);
    assert.equal(fs.existsSync(path.join(aseDir, 'SKILL.md')), false);
});

// «Установка готового» отличается от «создания нового»: install без реального
// пути/URL — честная ошибка, а не manifest, выданный за функциональность.
test('managePlugin install отвергает имя без реального пути или URL', async t => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stage0-install-'));
    t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
    let agyCalls = [];
    const managePlugin = extractFunction('managePlugin', {
        fs, path,
        UPLOADS_DIR: tmp,
        runAgySubcommand: async args => { agyCalls.push(args); return { ok: true, stdout: '', stderr: '' }; },
        invalidateAllIdleWorkers: () => {},
        listPlugins: async () => []
    });

    const res = await managePlugin({ action: 'install', name: 'super-tool', description: 'Обещает много' });
    assert.equal(res.ok, false, 'установка по одному имени отклонена');
    assert.ok(res.error.includes('путь') || res.error.includes('URL'),
        'ошибка объясняет, что нужен реальный путь или URL');
    assert.deepEqual(agyCalls, [], 'CLI не вызывался — фиктивный manifest не создан');
    const staging = path.join(tmp, 'plugin-staging', 'super-tool');
    assert.equal(fs.existsSync(path.join(staging, 'plugin.json')), false,
        'manifest не записан для отклонённой установки');
});

// Явный редактор нового пакета: отдельное действие create, manifest честно
// отмечает непроверенные инструменты.
test('managePlugin create явно создаёт локальный пакет с непроверенными инструментами', async t => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stage0-create-'));
    t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
    const managePlugin = extractFunction('managePlugin', {
        fs, path,
        UPLOADS_DIR: tmp,
        runAgySubcommand: async () => ({ ok: true, stdout: 'installed', stderr: '' }),
        invalidateAllIdleWorkers: () => {},
        listPlugins: async () => []
    });

    const res = await managePlugin({ action: 'create', name: 'My Toolkit', description: 'Локальный набор' });
    assert.equal(res.ok, true);
    assert.ok(res.message.includes('Инструменты пакета не проверены'),
        'успех не выдаёт проверенную функциональность');
    const manifestPath = path.join(tmp, 'plugin-staging', 'my-toolkit', 'plugin.json');
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    assert.equal(manifest.name, 'my-toolkit');
    assert.deepEqual(manifest.verifiedTools, [], 'инструменты честно непроверены');
    assert.ok(manifest.created, 'зафиксировано время создания');
});

// Статус MCP: настроен ≠ подключён; имена инструментов не выдумываются ни по
// имени сервера, ни по статической декларации. Этап 3: tools — только из
// живого tools/list после реального рукопожатия.
test('listMcpServers не называет сконфигурированный сервер подключённым и не выдумывает tools', async () => {
    const stdout = [
        'NAME        MODE    STATUS   COMMAND',
        'sysinfo     stdio   enabled  node pc-bridge/mcp_sysinfo_server.js',
        'rea         stdio   enabled  npx -y rea-server',
        'oldsrv      stdio   disabled python old_server.py',
        ''
    ].join('\n');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stage0-mcp-'));
    try {
        const listMcpServers = extractFunction('listMcpServers', {
            fs, path,
            runAgySubcommand: async () => ({ ok: true, stdout, stderr: '' }),
            GEMINI_SETTINGS_FILE: path.join(tmp, 'no-settings.json'),
            splitCommandAndArgs: s => {
                const parts = s.split(/\s+/);
                return { command: parts[0], args: parts.slice(1) };
            }
        });
        const servers = await listMcpServers();
        const sysinfo = servers.find(s => s.id === 'sysinfo');
        const rea = servers.find(s => s.id === 'rea');
        const oldsrv = servers.find(s => s.id === 'oldsrv');
        // Массивы создаются в vm-контексте с чужим prototype — сравниваем по значению.
        assert.equal(sysinfo.status, 'configured', 'включённый сервер — «настроен», не «подключён»');
        assert.equal(sysinfo.tools.join(','), '',
            'без живого tools/list инструменты не выдумываются даже для sysinfo');
        assert.equal(rea.status, 'configured');
        assert.equal(rea.tools.join(','), '', 'имена инструментов rea не выдумываются без handshake');
        assert.equal(oldsrv.status, 'disabled', 'отключённый остаётся отключённым');
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

// Этап 3: connected выставляется только после успешного полного рукопожатия;
// при отказе — failed с причиной и без выдуманных инструментов.
test('mergeMcpLiveState вносит живой статус рукопожатия, а не конфигурацию', async () => {
    const live = new Map();
    const mergeMcpLiveState = extractFunction('mergeMcpLiveState', { mcpLiveState: live });
    const stdout = 'sysinfo     stdio   enabled  node pc-bridge/mcp_sysinfo_server.js\n';
    const listMcpServers = extractFunction('listMcpServers', {
        fs, path,
        runAgySubcommand: async () => ({ ok: true, stdout, stderr: '' }),
        GEMINI_SETTINGS_FILE: path.join(os.tmpdir(), 'stage0-no-settings.json'),
        splitCommandAndArgs: s => {
            const parts = s.split(/\s+/);
            return { command: parts[0], args: parts.slice(1) };
        },
        mergeMcpLiveState
    });

    // Успешное рукопожатие: initialize → initialized → tools/list прошли.
    live.set('sysinfo', {
        id: 'sysinfo',
        status: 'connected',
        tools: ['get_workstation_pulse'],
        serverInfo: { name: 'antigravity-sysinfo-mcp', version: '1.0.0' },
        protocolVersion: '2025-06-18',
        capabilities: { tools: {} },
        error: '',
        errorCode: null,
        probedAt: '2026-11-02T00:00:00.000Z'
    });
    let servers = await listMcpServers();
    assert.equal(servers[0].status, 'connected');
    assert.equal(servers[0].tools.join(','), 'get_workstation_pulse', 'tools — из живого tools/list');
    assert.equal(servers[0].serverInfo.name, 'antigravity-sysinfo-mcp');

    // Отказ рукопожатия: failed, tools пусты, причина видна.
    live.set('sysinfo', {
        id: 'sysinfo',
        status: 'failed',
        tools: [],
        error: 'Тайм-аут 350 мс: initialize',
        errorCode: 'handshake_timeout',
        probedAt: '2026-11-02T00:00:01.000Z'
    });
    servers = await listMcpServers();
    assert.equal(servers[0].status, 'failed');
    assert.equal(servers[0].tools.join(','), '', 'при отказе инструменты не выдумываются');
    assert.match(servers[0].error, /Тайм-аут/);

    // Проверки не было — честно «настроен», без инструментов.
    live.delete('sysinfo');
    servers = await listMcpServers();
    assert.equal(servers[0].status, 'configured');
    assert.equal(servers[0].tools.join(','), '');
});