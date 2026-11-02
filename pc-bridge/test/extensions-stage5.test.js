'use strict';
// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): адаптеры компонентов плагинов и
// Android-runtime. Тесты гоняют НАСТОЯЩИЕ процессы (hook-engine и script-runner
// реально запускают скрипты) — работоспособность доказывается запуском.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');

const hookEngine = require('../lib/hook-engine');
const scriptRunner = require('../lib/script-runner');
const adapters = require('../lib/component-adapters');
const runtime = require('../lib/extension-runtime');

const PROJECT_ROOT = path.join(__dirname, '..', '..');
const NODE_EXE = process.execPath;

function makeTempDir(prefix = 'ag-stage5-') {
    return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function write(dir, rel, content) {
    const p = path.join(dir, ...rel.split('/'));
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, content, 'utf8');
    return p;
}

function q(p) {
    return `"${String(p).replace(/"/g, '""')}"`;
}

// ---------------------------------------------------------------------------
// hook-engine: парсинг обеих официальных форм и видимые отказы
// ---------------------------------------------------------------------------

test('hook-engine: парсинг форм и ВИДИМЫЕ отказы неподдерживаемого', () => {
    const parsed = hookEngine.parseHooksConfig({
        demo: {
            PreToolUse: [
                { matcher: '*', hooks: [{ type: 'command', command: 'echo ok' }] },
                { type: 'prompt', command: 'echo no' },
                { type: 'command' }
            ],
            WeirdEvent: [{ type: 'command', command: 'echo x' }]
        },
        off: { enabled: false, Stop: [{ type: 'command', command: 'echo q' }] }
    }, { sourcePath: path.join('C:', 'tmp', 'hooks.json') });

    assert.equal(parsed.namedHooks.length, 2);
    const codes = parsed.refusals.map(r => r.code);
    assert.ok(codes.includes('unsupported_handler_type'), 'тип prompt отклонён видимо');
    assert.ok(codes.includes('missing_command'), 'handler без command отклонён видимо');
    assert.ok(codes.includes('unsupported_event'), 'неизвестное событие отклонено видимо');
    // Ни один неподдерживаемый handler не попал в план.
    const plan = hookEngine.collectEventPlan(parsed.namedHooks, 'PreToolUse', { toolName: 'run_command' });
    assert.equal(plan.runnable.length, 1, 'исполняется только type:"command" с командой');
    assert.equal(plan.runnable[0].handler.command, 'echo ok');

    // enabled:false — видимый отказ, не молчаливый пропуск.
    const stopPlan = hookEngine.collectEventPlan(parsed.namedHooks, 'Stop', {});
    assert.equal(stopPlan.runnable.length, 0);
    assert.ok(stopPlan.refusals.some(r => r.code === 'disabled'));
});

test('hook-engine: форма Claude-совместимого settings-блока и matcher', () => {
    const parsed = hookEngine.parseHooksConfig({
        PreToolUse: [{ matcher: 'run_command|view_file', hooks: [{ command: 'echo a' }] }],
        PostToolUse: [{ matcher: 'browser_.*', hooks: [{ command: 'echo b' }] }],
        PreInvocation: [{ command: 'echo flat' }]
    }, { sourcePath: 'hooks.json' });
    assert.equal(parsed.namedHooks.length, 1);

    const m1 = hookEngine.collectEventPlan(parsed.namedHooks, 'PreToolUse', { toolName: 'run_command' });
    assert.equal(m1.runnable.length, 1);
    const m2 = hookEngine.collectEventPlan(parsed.namedHooks, 'PreToolUse', { toolName: 'send_email' });
    assert.equal(m2.runnable.length, 0, 'matcher не совпал — фильтр, не отказ');
    const m3 = hookEngine.collectEventPlan(parsed.namedHooks, 'PostToolUse', { toolName: 'browser_click' });
    assert.equal(m3.runnable.length, 1);
    const flat = hookEngine.collectEventPlan(parsed.namedHooks, 'PreInvocation', {});
    assert.equal(flat.runnable.length, 1, 'плоская форма события');

    // Некорректный matcher — видимый отказ.
    const bad = hookEngine.parseHooksConfig({
        h: { PreToolUse: [{ matcher: 'a(b', hooks: [{ command: 'x' }] }] }
    }, {});
    const plan = hookEngine.collectEventPlan(bad.namedHooks, 'PreToolUse', { toolName: 'a' });
    assert.equal(plan.runnable.length, 0);
    assert.ok(plan.refusals.some(r => r.code === 'invalid_matcher'));
});

// ---------------------------------------------------------------------------
// hook-engine: реальный запуск по контракту (stdin JSON → stdout JSON)
// ---------------------------------------------------------------------------

test('hook-engine: реальный запуск type:"command" — контракт stdin/stdout и cwd', async () => {
    const dir = makeTempDir();
    const hookJs = write(dir, 'hook.js', `
const fs = require('node:fs');
let input = '';
process.stdin.on('data', d => { input += d.toString('utf8'); });
process.stdin.resume();process.stdin.on('end', () => {
    const parsed = JSON.parse(input);
    fs.writeFileSync('received-input.json', JSON.stringify(parsed));
    process.stdout.write(JSON.stringify({ decision: 'allow', reason: 'всё ок' }));
});
`);
    const hooks = hookEngine.loadHooksFromSources([{ path: write(dir, 'hooks.json', JSON.stringify({
        demo: { PreToolUse: [{ matcher: '*', hooks: [{ type: 'command', command: `${q(NODE_EXE)} ${q(hookJs)}`, timeout: 20 }] }] }
    })), origin: 'test' }]);

    const r = await hookEngine.runEventHooks(hooks.namedHooks, 'PreToolUse', {
        toolCall: { name: 'run_command', args: { CommandLine: 'npm test' } },
        stepIdx: 19,
        conversationId: 'conv-test',
        workspacePaths: ['/path/to/workspace'],
        modelName: 'auto',
        lastUserInput: 'Fix the failing unit test'
    }, { homeDir: os.homedir() });

    assert.equal(r.refusals.length, 0, 'без отказов: ' + JSON.stringify(r.refusals));
    assert.equal(r.ran.length, 1);
    assert.equal(r.ran[0].status, 'ok', JSON.stringify(r.ran[0]));
    assert.equal(r.decision.decision, 'allow');

    // cwd = каталог hooks.json (контракт docs/hooks.md) + camelCase-поля stdin.
    const received = JSON.parse(fs.readFileSync(path.join(dir, 'received-input.json'), 'utf8'));
    assert.equal(received.toolCall.name, 'run_command');
    assert.equal(received.toolCall.args.CommandLine, 'npm test');
    assert.equal(received.conversationId, 'conv-test');
    assert.equal(received.stepIdx, 19);
    assert.equal(received.lastUserInput, 'Fix the failing unit test');
});

test('hook-engine: решения deny/ask/overwrite и не-JSON выход — видимо', async () => {
    const dir = makeTempDir();
    const mk = (name, body) => write(dir, name, body);
    const denyJs = mk('deny.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'deny', reason: 'запрещено политикой' })));`);
    const askJs = mk('ask.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'ask', reason: 'нужно подтверждение' })));`);
    const overJs = mk('over.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'allow', overwrite: { CommandLine: 'ls -la' } })));`);
    const weirdJs = mk('weird.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'maybe' })));`);
    const badJs = mk('bad.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write('это не JSON'));`);

    const runOne = (cmd) => hookEngine.runEventHooks([{
        name: 't', enabled: true, sourcePath: path.join(dir, 'hooks.json'), origin: 'test',
        events: { PreToolUse: [{ matcher: '*', handlers: [{ type: 'command', command: cmd, timeoutSec: 20 }] }] }
    }], 'PreToolUse', { toolCall: { name: 'run_command', args: { CommandLine: 'x' } } }, { homeDir: os.homedir() });

    const deny = await runOne(`${q(NODE_EXE)} ${q(denyJs)}`);
    assert.equal(deny.decision.decision, 'deny');
    assert.match(deny.decision.reason, /запрещено политикой/);

    const ask = await runOne(`${q(NODE_EXE)} ${q(askJs)}`);
    assert.equal(ask.decision.decision, 'deny', 'ask без интерактивного подтверждения — честный отказ');
    assert.match(ask.decision.reason, /подтверждени/);

    const over = await runOne(`${q(NODE_EXE)} ${q(overJs)}`);
    assert.deepEqual(over.decision.overwrite, { CommandLine: 'ls -la' });
    assert.match(over.decision.notice, /CommandLine/);

    const weird = await runOne(`${q(NODE_EXE)} ${q(weirdJs)}`);
    assert.equal(weird.decision.decision, 'deny', 'неизвестное решение блокирует');

    const bad = await runOne(`${q(NODE_EXE)} ${q(badJs)}`);
    assert.equal(bad.ran[0].status, 'bad-json');
    assert.ok(bad.refusals.some(r => r.code === 'bad-json'), 'не-JSON выход отражён видимо');
});

test('hook-engine: тайм-аут останавливает hook (реальный процесс)', async () => {
    const dir = makeTempDir();
    const slowJs = write(dir, 'slow.js', `setTimeout(() => {}, 10000);`);
    const hooks = hookEngine.loadHooksFromSources([{ path: write(dir, 'hooks.json', JSON.stringify({
        slow: { Stop: [{ type: 'command', command: `${q(NODE_EXE)} ${q(slowJs)}`, timeout: 1 }] }
    })), origin: 'test' }]);
    const r = await hookEngine.runEventHooks(hooks.namedHooks, 'Stop', { terminationReason: 'NO_TOOL_CALL' }, { homeDir: os.homedir() });
    assert.equal(r.ran[0].status, 'timeout');
    assert.ok(r.refusals.some(x => x.code === 'timeout'), 'тайм-аут видим');
});

test('hook-engine: Stop continue / PostInvocation terminate / injectSteps', async () => {
    const dir = makeTempDir();
    const stopJs = write(dir, 'stop.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'continue', reason: 'тесты ещё идут' })));`);
    const fcJs = write(dir, 'fc.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ terminationBehavior: 'force_continue' })));`);
    const termJs = write(dir, 'term.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ terminationBehavior: 'terminate' })));`);
    const injectJs = write(dir, 'inject.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ injectSteps: [
        { systemMessage: { systemMessage: 'приветствие' } },
        { userMessage: 'подсказка' },
        { ephemeralMessage: 'разовая заметка' },
        { mysteryStep: true }
    ] })));`);

    const mkHooks = (event, cmds) => cmds.map((cmd, i) => ({
        name: `h${i}`, enabled: true, sourcePath: path.join(dir, 'hooks.json'), origin: 'test',
        events: { [event]: [{ matcher: null, handlers: [{ type: 'command', command: cmd, timeoutSec: 20 }] }] }
    }));

    const stop = await hookEngine.runEventHooks(mkHooks('Stop', [`${q(NODE_EXE)} ${q(stopJs)}`]), 'Stop', { terminationReason: 'NO_TOOL_CALL' }, { homeDir: os.homedir() });
    assert.equal(stop.stopDecision.shouldContinue, true);
    assert.match(stop.stopDecision.reason, /тесты ещё идут/);

    const post = await hookEngine.runEventHooks(
        mkHooks('PostInvocation', [`${q(NODE_EXE)} ${q(fcJs)}`, `${q(NODE_EXE)} ${q(termJs)}`]),
        'PostInvocation', { invocationNum: 1, initialNumSteps: 3 }, { homeDir: os.homedir() });
    assert.equal(post.ran.length, 2, 'оба hook\'а выполнились последовательно');
    assert.equal(post.terminationBehavior, 'terminate', 'terminate жёстче force_continue');

    const sess = await hookEngine.runEventHooks(mkHooks('SessionStart', [`${q(NODE_EXE)} ${q(injectJs)}`]), 'SessionStart', {}, { homeDir: os.homedir() });
    assert.deepEqual(sess.injectSteps.map(s => s.kind), ['systemMessage', 'userMessage', 'ephemeralMessage']);
    assert.ok(sess.refusals.some(r => r.code === 'invalid_inject_step'), 'неизвестный шаг отклонён видимо');
});

test('hook-engine: запрет прав и гигиена окружения', async () => {
    const dir = makeTempDir();
    const marker = path.join(dir, 'should-not-exist.txt');
    const touchJs = write(dir, 'touch.js', `require('node:fs').writeFileSync(process.env.MARKER_PATH, 'x'); process.stdin.resume();process.stdin.on('end', () => process.stdout.write('{}'));`);
    const hooks = [() => ({
        name: 't', enabled: true, sourcePath: path.join(dir, 'hooks.json'), origin: 'test',
        events: { PreToolUse: [{ matcher: null, handlers: [{ type: 'command', command: `${q(NODE_EXE)} ${q(touchJs)}`, timeoutSec: 20 }] }] }
    })].map(f => f());

    // Права Этапа 4: запрет pcMcpToolsExec → ничего не запускается.
    const denied = await hookEngine.runEventHooks(hooks, 'PreToolUse', { toolCall: { name: 'x', args: {} } }, {
        homeDir: os.homedir(),
        permissions: { pcMcpToolsExec: false, pcTerminalExec: true },
        env: hookEngine.buildHookEnv(process.env, { MARKER_PATH: marker })
    });
    assert.equal(denied.ran.length, 0);
    assert.ok(denied.refusals.some(r => r.code === 'permission_denied'));
    assert.ok(!fs.existsSync(marker), 'процесс не запускался');

    // Гигиена окружения: секреты не передаются, базовые переменные — передаются.
    const env = hookEngine.buildHookEnv(
        { PATH: 'p', DSH_SESSION_TOKEN: 't1', OPENROUTER_API_KEY: 'k', SAFE_VAR: 's' },
        { EXTRA: 'e', MY_SECRET: 'no' });
    assert.equal(env.PATH, 'p');
    assert.equal(env.SAFE_VAR, 's');
    assert.equal(env.EXTRA, 'e');
    assert.ok(!('DSH_SESSION_TOKEN' in env) && !('OPENROUTER_API_KEY' in env) && !('MY_SECRET' in env));
});

// ---------------------------------------------------------------------------
// script-runner: зависимости, изоляция рабочего каталога, реальный запуск
// ---------------------------------------------------------------------------

test('script-runner: проверка зависимостей ДО запуска', () => {
    const dir = makeTempDir();
    const bad = write(dir, 'needs-dep.js', `const x = require('nonexistent-pkg-xyz-123'); console.log(x);`);
    const deps = scriptRunner.checkScriptDependencies({ scriptPath: bad });
    assert.equal(deps.interpreter.kind, 'node');
    assert.equal(deps.interpreter.available, true, 'node доступен (реальный probe)');
    assert.ok(deps.missing.includes('nonexistent-pkg-xyz-123'));
    assert.equal(deps.ready, false);

    const py = write(dir, 'tool.py', `import requests\nprint(requests.get)\n`);
    const pyDeps = scriptRunner.checkScriptDependencies({
        scriptPath: py,
        probeInterpreter: () => ({ available: true, version: '3.x' })
    });
    assert.ok(pyDeps.dependencies.some(d => d.name === 'requests'));
    assert.ok(pyDeps.issues.some(i => /требует проверки/.test(i)));

    const mystery = write(dir, 'x.xyz', 'nothing');
    const mDeps = scriptRunner.checkScriptDependencies({
        scriptPath: mystery,
        probeInterpreter: () => ({ available: false, error: 'нет' })
    });
    assert.equal(mDeps.ready, false);
    assert.ok(mDeps.issues.length > 0, 'неизвестный вид — явные issues');
});

test('script-runner: реальный запуск в ИЗОЛИРОВАННОМ рабочем каталоге', async () => {
    const dir = makeTempDir();
    const script = write(dir, 'cwd-echo.js', `console.log(process.cwd());`);
    const r = await scriptRunner.runScriptIsolated({ scriptPath: script, timeoutMs: 20000 });
    assert.equal(r.ok, true, JSON.stringify({ blocker: r.blocker, stderr: r.stderr }));
    const shownCwd = r.stdout.trim();
    assert.ok(shownCwd.includes('ag-script-'), `cwd служебный временный: ${shownCwd}`);
    assert.ok(!shownCwd.startsWith(PROJECT_ROOT), 'корень проекта НЕ выдан как cwd');

    // Явно заданный рабочий каталог тоже работает.
    const workDir = path.join(dir, 'work');
    const r2 = await scriptRunner.runScriptIsolated({ scriptPath: script, workDir, timeoutMs: 20000 });
    assert.equal(r2.stdout.trim(), workDir);

    // Блокер вместо молчаливого прогона: нехватка зависимости.
    const bad = write(dir, 'needs-dep.js', `require('nonexistent-pkg-xyz-123');`);
    const r3 = await scriptRunner.runScriptIsolated({ scriptPath: bad, timeoutMs: 20000 });
    assert.equal(r3.ok, false);
    assert.match(r3.blocker, /не хватает зависимостей/);

    // Тайм-аут реального процесса.
    const slow = write(dir, 'slow.js', `setTimeout(() => {}, 10000);`);
    const r4 = await scriptRunner.runScriptIsolated({ scriptPath: slow, timeoutMs: 800 });
    assert.equal(r4.ok, false);
    assert.equal(r4.timedOut, true);
});

// ---------------------------------------------------------------------------
// component-adapters: отдельные проверки видов (план Этапа 5)
// ---------------------------------------------------------------------------

test('адаптеры: commands, agents, hooks, LSP, локальные программы, config vars, UI', () => {
    const command = adapters.adaptComponentType('command');
    assert.equal(command.strategy, 'convert');
    assert.equal(command.status, 'verified');

    const agent = adapters.adaptComponentType('agent');
    assert.equal(agent.strategy, 'convert');
    assert.equal(agent.status, 'verified');

    const hook = adapters.adaptComponentType('hook');
    assert.equal(hook.strategy, 'own-executor');
    assert.equal(hook.status, 'verified');
    assert.match(hook.note, /молча|видим/);

    const lsp = adapters.adaptComponentType('lsp-server');
    assert.equal(lsp.status, 'blocked');
    assert.match(lsp.blocker, /не переносится/);

    const exec = adapters.adaptComponentType('executable');
    assert.equal(exec.strategy, 'own-executor');
    assert.equal(exec.status, 'verified');

    const cfg = adapters.adaptComponentType('config-var');
    assert.equal(cfg.strategy, 'convert');
    assert.equal(cfg.status, 'verified');

    const ui = adapters.adaptComponentType('catalog-card');
    assert.equal(ui.strategy, 'preserve');
    assert.equal(ui.status, 'not-applicable');
    assert.match(ui.note, /UI-компонент/);

    const conn = adapters.adaptComponentType('connector');
    assert.equal(conn.status, 'blocked');
    assert.match(conn.blocker, /MCP-подключение|OAuth/);

    // Псевдонимы диалектов не теряются.
    assert.equal(adapters.normalizeKind('commands'), 'command');
    assert.equal(adapters.normalizeKind('mcpServers'), 'mcp-server');
    assert.equal(adapters.adaptComponentType('mcpServers').strategy, 'own-executor');

    const unknown = adapters.adaptComponentType('что-то-новое');
    assert.equal(unknown.status, 'requires-check');
    assert.match(unknown.blocker, /не отнесён/);
});

test('адаптеры: право распространения и статус «полностью»', () => {
    assert.equal(adapters.checkRedistributionRight('MIT').rightToRedistribute, 'granted');
    assert.equal(adapters.checkRedistributionRight('собственный код проекта; лицензия сторонних пакетов по их условиям').rightToRedistribute, 'granted');
    assert.equal(adapters.checkRedistributionRight('не указана в файле').rightToRedistribute, 'unknown');
    assert.equal(adapters.checkRedistributionRight('n/a').rightToRedistribute, 'not-applicable');

    const okRecords = [
        adapters.adaptComponentType('skill'),
        adapters.adaptComponentType('asset')
    ];
    const license = adapters.checkRedistributionRight('Apache-2.0');
    const full = adapters.evaluatePluginSupport({ records: okRecords, licenseCheck: license, compositionVerified: true });
    assert.equal(full.supportStatus, 'fully-supported');

    // «Полностью» запрещено без проверенного состава.
    const partial = adapters.evaluatePluginSupport({ records: okRecords, licenseCheck: license, compositionVerified: false });
    assert.notEqual(partial.supportStatus, 'fully-supported');

    // «Полностью» запрещено без подтверждённого права распространения.
    const noLicense = adapters.evaluatePluginSupport({
        records: okRecords,
        licenseCheck: adapters.checkRedistributionRight('не указано'),
        compositionVerified: true
    });
    assert.notEqual(noLicense.supportStatus, 'fully-supported');
    assert.equal(noLicense.licenseOk, false);

    // Блокер компонента снимает «полностью».
    const withBlocker = adapters.evaluatePluginSupport({
        records: [...okRecords, adapters.adaptComponentType('lsp-server')],
        licenseCheck: license,
        compositionVerified: true
    });
    assert.notEqual(withBlocker.supportStatus, 'fully-supported');
    assert.ok(withBlocker.blockers.length > 0);
});

test('адаптеры: преобразования команды/агента/output-style и правила', () => {
    const skill = adapters.convertCommandToSkill(
        '---\ndescription: Собрать отчёт\n---\nСобери отчёт по $ARGUMENTS и приложи ссылки.',
        'Report Builder');
    assert.equal(skill.convertedFrom, 'command');
    assert.match(skill.name, /^[a-z0-9-]+$/);
    assert.match(skill.instruction, /\[аргументы запроса\]/);
    assert.ok(!skill.instruction.includes('$ARGUMENTS'));
    assert.match(skill.skillMarkdown, /^---\nname: /);

    const agent = adapters.convertAgentToSkill(
        '---\nname: reviewer\ndescription: Ревьюер\ntools: read_file, run_command\n---\nПроверяй изменения.',
        'reviewer');
    assert.equal(agent.convertedFrom, 'agent');
    assert.match(agent.instruction, /read_file, run_command/);

    const style = adapters.convertOutputStyleToSkill('---\nname: concise\n---\nОтвечай кратко.', 'concise');
    assert.equal(style.convertedFrom, 'output-style');
    assert.match(style.instruction, /кратко/);

    const rules = adapters.composeRulesBlock('Всегда пиши тесты.', { pluginName: 'guard' });
    assert.match(rules, /Всегда пиши тесты/);
    assert.match(rules, /guard/);
    assert.equal(adapters.composeRulesBlock('   '), null);
});

// ---------------------------------------------------------------------------
// component-adapters: живой состав плагинов и матрица по реальному инвентарю
// ---------------------------------------------------------------------------

test('адаптеры: живой состав плагина — manifest-only это блокер, не «пустая поддержка»', () => {
    const dir = makeTempDir();
    write(dir, 'plugin.json', JSON.stringify({ name: 'empty-plugin', version: '1.0.0' }));
    const a = adapters.analyzePluginAdapters(dir, { name: 'empty-plugin' });
    assert.equal(a.support.supportStatus, 'blocked');
    assert.ok(a.records.some(r => /состав пуст/.test(r.blocker || '')));

    // Составной плагин: hooks + skills — каждый со своей стратегией.
    const dir2 = makeTempDir();
    write(dir2, 'plugin.json', JSON.stringify({ name: 'full-plugin' }));
    write(dir2, 'hooks.json', JSON.stringify({ lint: { PostToolUse: [{ matcher: '*', hooks: [{ command: 'echo x' }] }] } }));
    write(dir2, 'skills/demo/SKILL.md', '---\nname: demo\ndescription: Демо\n---\nТело.');
    write(dir2, 'rules/AGENTS.md', 'Правила.');
    write(dir2, 'mcp_config.json', JSON.stringify({ mcpServers: {} }));
    const b = adapters.analyzePluginAdapters(dir2, { name: 'full-plugin' });
    const kinds = b.records.map(r => r.normalizedKind);
    assert.ok(kinds.includes('hook') && kinds.includes('skill') && kinds.includes('rule') && kinds.includes('mcp-server'));
    assert.ok(b.records.find(r => r.normalizedKind === 'hook').strategy === 'own-executor');
    // Лицензия не указана → «полностью» не выдаётся.
    assert.equal(b.licenseCheck.rightToRedistribute, 'unknown');
    assert.notEqual(b.support.supportStatus, 'fully-supported');
});

test('адаптеры: матрица по РЕАЛЬНОМУ инвентарю — у каждого компонента путь или блокер', () => {
    const inventory = JSON.parse(fs.readFileSync(path.join(PROJECT_ROOT, 'extensions-inventory.json'), 'utf8'));
    const matrix = adapters.buildAdapterMatrix(inventory);

    assert.equal(matrix.positions.length, inventory.positions.length);
    assert.equal(matrix.summary.positionsTotal, inventory.positions.length);
    for (const p of matrix.positions) {
        assert.ok(p.records.length > 0, `позиция ${p.id} без адаптеров`);
        for (const r of p.records) {
            assert.ok(['verified', 'blocked', 'requires-check', 'not-applicable'].includes(r.status), `${p.id}/${r.componentType}: ${r.status}`);
            if (r.status === 'verified') assert.ok(r.verification, `${p.id}/${r.componentType}: verified без verification`);
            if (r.status === 'blocked') assert.ok(r.blocker, `${p.id}/${r.componentType}: blocked без blocker`);
        }
        assert.ok(p.supportStatus, `позиция ${p.id} без supportStatus`);
    }

    // «Отдельно проверить»: сводная таблица видов присутствует.
    const kindNames = matrix.kindTable.map(k => k.kind);
    for (const need of ['command', 'agent', 'hook', 'lsp-server', 'executable', 'config-var', 'catalog-card', 'connector']) {
        assert.ok(kindNames.includes(need), `kindTable без ${need}`);
    }

    // Проверенные факты из инвентаря.
    const byId = new Map(matrix.positions.map(p => [p.id, p]));
    const ag = byId.get('local-mcp-ag-workstation-info');
    assert.equal(ag.fullSupportAllowed, true, 'собственный код + живой MCP → fully-supported');
    const guard = byId.get('local-plugin-antigravity-workspace-guard');
    assert.equal(guard.supportStatus, 'fully-supported');
    assert.equal(guard.blockers.length, 0, 'defect №1 исправлен реальным составом, проверенным extensions-blockers.test.js');
    const viz = byId.get('codex-visualize-plugin-assets');
    const vizRec = viz.records.find(r => r.componentType === 'skill-assets');
    assert.equal(vizRec.status, 'blocked');
    assert.match(vizRec.blocker, /unpkg|не подключено/i);

    // «Полностью» — только при подтверждённом праве распространения.
    for (const id of matrix.summary.fullySupported) {
        assert.equal(byId.get(id).licenseCheck.rightToRedistribute !== 'unknown', true, `${id}: лицензия не подтверждена`);
    }
    assert.ok(matrix.summary.licenseUnknown.length > 0, 'неподтверждённые лицензии видимы');
});

// ---------------------------------------------------------------------------
// Интеграция: runToolCallLoop + реальные hooks (собственный исполнитель)
// ---------------------------------------------------------------------------

function makeHookRunner(hooksFile, { permissions = { pcMcpToolsExec: true, pcTerminalExec: true } } = {}) {
    const loaded = hookEngine.loadHooksFromSources([{ path: hooksFile, origin: 'test' }]);
    const run = (event, ctx) => hookEngine.runEventHooks(loaded.namedHooks, event, ctx, {
        permissions,
        homeDir: os.homedir()
    });
    return {
        initialRefusals: loaded.refusals,
        async beforeToolCall({ ref, args, callId, stepIdx }) {
            const r = await run('PreToolUse', { toolCall: { name: ref, args }, stepIdx, callId });
            const d = r.decision || { decision: 'allow', reason: '', notice: null, overwrite: {} };
            const overwrite = d.overwrite || {};
            return {
                allow: d.decision === 'allow',
                args: Object.keys(overwrite).length ? Object.assign({}, args, overwrite) : args,
                notice: d.notice,
                reason: d.reason,
                refusals: r.refusals
            };
        },
        async afterToolCall({ ref, args, callId, result, stepIdx, error }) {
            return { refusals: (await run('PostToolUse', { toolCall: { name: ref, args }, stepIdx, callId, error })).refusals };
        },
        async onInvocation({ event, invocationNum, initialNumSteps }) {
            const r = await run(event === 'PostInvocation' ? 'PostInvocation' : 'PreInvocation', { invocationNum, initialNumSteps });
            return { injectSteps: r.injectSteps, terminationBehavior: r.terminationBehavior, refusals: r.refusals };
        },
        async onStop({ terminationReason }) {
            const r = await run('Stop', { executionNum: 1, terminationReason });
            return {
                shouldContinue: Boolean(r.stopDecision && r.stopDecision.shouldContinue),
                reason: (r.stopDecision && r.stopDecision.reason) || '',
                refusals: r.refusals
            };
        }
    };
}

test('runToolCallLoop + hook-engine: deny блокирует вызов, overwrite помечает результат', async () => {
    const dir = makeTempDir();
    const denyJs = write(dir, 'deny.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'deny', reason: 'инструмент запрещён' })));`);
    const overJs = write(dir, 'over.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'allow', overwrite: { q: 2 } })));`);
    write(dir, 'hooks.json', JSON.stringify({
        gate: { PreToolUse: [{ matcher: 'srvX:danger', hooks: [{ command: `${q(NODE_EXE)} ${q(denyJs)}` }] }] },
        rewrite: { PreToolUse: [{ matcher: 'srvX:whoami', hooks: [{ command: `${q(NODE_EXE)} ${q(overJs)}` }] }] }
    }));
    const hookRunner = makeHookRunner(path.join(dir, 'hooks.json'));

    const executed = [];
    const workingAtRound2 = [];
    let round = 0;
    const result = await runtime.runToolCallLoop({
        messages: [{ role: 'user', content: 'проверка hooks' }],
        tools: [],
        nameToRef: new Map([['danger', 'srvX:danger'], ['whoami', 'srvX:whoami']]),
        hookRunner,
        chat: async (working) => {
            round += 1;
            if (round === 1) {
                return { message: { role: 'assistant', content: '', tool_calls: [
                    { id: 'c1', type: 'function', function: { name: 'danger', arguments: '{}' } },
                    { id: 'c2', type: 'function', function: { name: 'whoami', arguments: '{"q":1}' } }
                ] } };
            }
            workingAtRound2.push(...working);
            return { message: { role: 'assistant', content: 'готово' } };
        },
        execute: async (ref, args) => {
            executed.push({ ref, args });
            return { ok: true, result: { content: [{ type: 'text', text: 'ответ-' + ref }] } };
        }
    });

    // deny: инструмент НЕ исполнялся, отказ виден в tool-сообщении следующего хода.
    assert.ok(!executed.some(e => e.ref === 'srvX:danger'), 'заблокированный вызов не исполнялся');
    const toolTexts = workingAtRound2.filter(m => m.role === 'tool').map(m => String(m.content));
    assert.ok(toolTexts.some(t => /заблокирован hook/.test(t)), 'отказ виден модели');
    assert.equal(result.toolCalls.find(t => t.ref === 'srvX:danger').code, 'hook_denied');

    // overwrite: аргументы перезаписаны (q:2), результат с уведомлением по контракту.
    const who = executed.find(e => e.ref === 'srvX:whoami');
    assert.deepEqual(who.args, { q: 2 }, 'overwrite применился');
    assert.ok(toolTexts.some(t => /переписал аргументы/.test(t) && /q/.test(t)), 'уведомление о перезаписи перед результатом');
});

test('runToolCallLoop + hook-engine: PostToolUse после вызова и Stop continue продлевает цикл', async () => {
    const dir = makeTempDir();
    const postJs = write(dir, 'post.js', `require('node:fs').writeFileSync(process.env.MARKER, 'done'); process.stdin.resume();process.stdin.on('end', () => process.stdout.write('{}'));`);
    // Stop-продление срабатывает один раз (по файлу-состоянию), затем — обычный стоп.
    const stopJs = write(dir, 'stop.js', `
const fs = require('node:fs');
const state = 'stop-state.txt';
process.stdin.resume();process.stdin.on('end', () => {
    if (!fs.existsSync(state)) { fs.writeFileSync(state, '1'); process.stdout.write(JSON.stringify({ decision: 'continue', reason: 'нужен ещё один проход' })); }
    else process.stdout.write('{}');
});`);
    const marker = path.join(dir, 'marker.txt');
    write(dir, 'hooks.json', JSON.stringify({
        post: { PostToolUse: [{ matcher: '*', hooks: [{ command: `${q(NODE_EXE)} ${q(postJs)}` }] }] },
        cont: { Stop: [{ command: `${q(NODE_EXE)} ${q(stopJs)}` }] }
    }));
    const loaded = hookEngine.loadHooksFromSources([{ path: path.join(dir, 'hooks.json') }]);
    const env = hookEngine.buildHookEnv(process.env, { MARKER: marker });
    const perms = { pcMcpToolsExec: true, pcTerminalExec: true };

    let chatRounds = 0;
    let postCalls = 0;
    const result = await runtime.runToolCallLoop({
        messages: [{ role: 'user', content: 'продолжение' }],
        tools: [],
        nameToRef: new Map(),
        hookRunner: {
            async afterToolCall(ctx) {
                postCalls += 1;
                const r = await hookEngine.runEventHooks(loaded.namedHooks, 'PostToolUse',
                    { toolCall: { name: ctx.ref, args: ctx.args }, stepIdx: ctx.stepIdx },
                    { permissions: perms, homeDir: os.homedir(), env });
                return { refusals: r.refusals };
            },
            async onStop({ terminationReason }) {
                const r = await hookEngine.runEventHooks(loaded.namedHooks, 'Stop', { executionNum: 1, terminationReason },
                    { permissions: perms, homeDir: os.homedir(), env });
                return {
                    shouldContinue: Boolean(r.stopDecision && r.stopDecision.shouldContinue),
                    reason: (r.stopDecision && r.stopDecision.reason) || '',
                    refusals: r.refusals
                };
            }
        },
        chat: async () => {
            chatRounds += 1;
            if (chatRounds === 1) {
                return { message: { role: 'assistant', content: '', tool_calls: [
                    { id: 'c1', type: 'function', function: { name: 'whoami', arguments: '{}' } }
                ] } };
            }
            return { message: { role: 'assistant', content: chatRounds >= 3 ? 'финал' : 'ещё раз' } };
        },
        execute: async () => ({ ok: true, result: { content: [{ type: 'text', text: 'ок' }] } })
    });

    assert.equal(postCalls, 1, 'PostToolUse выполнился после инструмента');
    assert.equal(fs.readFileSync(marker, 'utf8'), 'done', 'PostToolUse-hook реально отработал');
    assert.equal(result.finalMessage.content, 'финал', 'Stop continue продлил цикл до финального ответа');
    assert.equal(chatRounds, 3, 'ровно один дополнительный проход');
    assert.equal(result.toolCalls.length, 1);
});

// ---------------------------------------------------------------------------
// Склейка server.js: реальные loadExtensionHooks/buildHookRunner (vm-извлечение
// функций server.js, как в server-functions.test.js)
// ---------------------------------------------------------------------------

const serverSource = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

function extractServerFunction(name, context = {}) {
    const start = serverSource.indexOf(`function ${name}(`);
    assert.ok(start > 0, `${name} найден в server.js`);
    const end = serverSource.indexOf('\n}', start) + 2;
    return vm.runInNewContext(`(${serverSource.slice(start, end).trim()})`, context);
}

test('server.js: loadExtensionHooks собирает пользовательские и workspace hooks', () => {
    const ws = makeTempDir();
    write(ws, '.agents/hooks.json', JSON.stringify({
        wsHook: { PreInvocation: [{ command: 'echo ws' }] }
    }));
    const load = extractServerFunction('loadExtensionHooks', {
        fs,
        path,
        GEMINI_ROOT_DIR: path.join(makeTempDir(), 'no-user-config'),
        DEFAULT_WORKSPACE: ws,
        hookEngine,
        collectPluginHookSources: () => []
    });
    const loaded = load({ workspace: ws });
    assert.equal(loaded.namedHooks.length, 1);
    assert.equal(loaded.namedHooks[0].name, 'wsHook');
    assert.equal(loaded.namedHooks[0].origin, 'workspace');
});

test('server.js: buildHookRunner — реальный deny-hook блокирует через серверную склейку', async () => {
    const dir = makeTempDir();
    const denyJs = write(dir, 'deny.js', `process.stdin.resume();process.stdin.on('end', () => process.stdout.write(JSON.stringify({ decision: 'deny', reason: 'серверная склейка' })));`);
    const hooksFile = write(dir, 'hooks.json', JSON.stringify({
        gate: { PreToolUse: [{ matcher: 'srvX:danger', hooks: [{ command: `${q(NODE_EXE)} ${q(denyJs)}` }] }] }
    }));
    const build = extractServerFunction('buildHookRunner', {
        path,
        os,
        hookEngine,
        loadExtensionHooks: () => hookEngine.loadHooksFromSources([{ path: hooksFile, origin: 'test' }]),
        loadAiPermissionsConfig: () => ({}),
        buildHookPayloadContext: (ctx) => ({ conversationId: ctx.conversationId || 'c1' })
    });
    const runner = build({ conversationId: 'conv-1', modelName: 'auto', lastUserInput: 'проверка' });
    const pre = await runner.beforeToolCall({ ref: 'srvX:danger', args: { x: 1 }, callId: 'c1', stepIdx: 0 });
    assert.equal(pre.allow, false);
    assert.match(pre.reason, /серверная склейка/);

    // Права: запрет прав даёт видимый отказ и никакого запуска.
    const buildDenied = extractServerFunction('buildHookRunner', {
        path,
        os,
        hookEngine,
        loadExtensionHooks: () => hookEngine.loadHooksFromSources([{ path: hooksFile, origin: 'test' }]),
        loadAiPermissionsConfig: () => ({ pcMcpToolsExec: false }),
        buildHookPayloadContext: (ctx) => ({ conversationId: ctx.conversationId || 'c1' })
    });
    const preDenied = await buildDenied({}).beforeToolCall({ ref: 'srvX:danger', args: {}, callId: 'c2', stepIdx: 0 });
    assert.equal(preDenied.allow, true, 'hook не выполнился — решения нет');
    assert.ok(preDenied.refusals.some(r => r.code === 'permission_denied'));
});
