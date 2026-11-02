'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const adapters = require('../lib/component-adapters');
const hooks = require('../lib/hook-engine');
const { loadOfficialMcp } = require('../lib/official-mcp-catalog');
const { resolveStdioLaunch } = require('../lib/mcp-client');
const { syncLocalPlugins } = require('../scripts/sync-local-plugins');
const ROOT = path.resolve(__dirname, '../..');
const plugin = name => path.join(ROOT, 'extensions/plugins', name);
const q = value => '"' + value + '"';

test('workspace guard: real Git status/check and real PreToolUse denial', async t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-guard-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    assert.equal(spawnSync('git', ['init', dir], { windowsHide: true }).status, 0);
    fs.writeFileSync(path.join(dir, 'user-work.txt'), 'preserve this');
    const root = plugin('antigravity-workspace-guard');
    const script = path.join(root, 'skills/workspace-check/scripts/check.js');
    const result = spawnSync(process.execPath, [script, '--workspace', dir], { encoding: 'utf8', windowsHide: true });
    assert.equal(result.status, 0);
    assert.equal(JSON.parse(result.stdout).dirty, true);
    const parsed = hooks.parseHooksConfig(JSON.parse(fs.readFileSync(path.join(root, 'hooks.json'))), { sourcePath: path.join(root, 'hooks.json') });
    const plan = hooks.collectEventPlan(parsed.namedHooks, 'PreToolUse', { toolName: 'run_command' });
    assert.equal(plan.runnable.length, 1);
    const handler = { ...plan.runnable[0].handler, command: `${q(process.execPath)} ${q(script)} --hook` };
    const denied = await hooks.runHookHandler(handler, hooks.buildHookInput('PreToolUse', { toolCall: { name: 'run_command', args: { command: 'git reset --hard' } }, workspacePaths: [dir] }), { cwd: root });
    assert.equal(denied.output.decision, 'deny');
    const { guard } = require(script);
    for (const command of ['git clean -fd', 'git push origin main --force', 'git checkout -- file']) assert.equal(guard({ toolCall: { args: { command } } }).decision, 'deny', command);
    assert.deepEqual(guard({ toolCall: { args: { command: 'git status --short' } } }), {});
    assert.equal(fs.readFileSync(path.join(dir, 'user-work.txt'), 'utf8'), 'preserve this');
});

test('Kotlin inspector: actual script and PostToolUse contract expose conflicts, preserve source', async t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-compose-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const source = '<<<<<<< HEAD\nfun old() {}\n=======\nfun newer() {}\n>>>>>>> branch\n';
    fs.writeFileSync(path.join(dir, 'Demo.kt'), source);
    const root = plugin('kotlin-compose-inspector');
    const script = path.join(root, 'skills/compose-check/scripts/check.js');
    const result = spawnSync(process.execPath, [script, '--workspace', dir], { encoding: 'utf8', windowsHide: true });
    assert.equal(result.status, 1);
    assert.equal(JSON.parse(result.stdout).diagnostics.filter(x => x.severity === 'error').length, 3);
    const parsed = hooks.parseHooksConfig(JSON.parse(fs.readFileSync(path.join(root, 'hooks.json'))), { sourcePath: path.join(root, 'hooks.json') });
    const plan = hooks.collectEventPlan(parsed.namedHooks, 'PostToolUse', { toolName: 'write_file' });
    const handler = { ...plan.runnable[0].handler, command: `${q(process.execPath)} ${q(script)} --hook` };
    const output = await hooks.runHookHandler(handler, hooks.buildHookInput('PostToolUse', { workspacePaths: [dir], toolCall: { name: 'write_file', args: { path: 'Demo.kt' } } }), { cwd: root });
    assert.ok(output.output.injectSteps[0].ephemeralMessage.includes('Маркер конфликта'));
    assert.equal(fs.readFileSync(path.join(dir, 'Demo.kt'), 'utf8'), source);
});

test('canonical MIT packages have verified composition; repair preserves enabled config', t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-sync-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const configDir = path.join(dir, '.gemini/config');
    fs.mkdirSync(configDir, { recursive: true });
    const config = JSON.stringify({ plugins: { 'antigravity-workspace-guard': { enabled: false } } });
    fs.writeFileSync(path.join(configDir, 'config.json'), config);
    syncLocalPlugins({ home: dir, staging: path.join(dir, 'staging') });
    assert.equal(fs.readFileSync(path.join(configDir, 'config.json'), 'utf8'), config);
    for (const name of ['antigravity-workspace-guard', 'kotlin-compose-inspector']) {
        const analysis = adapters.analyzePluginAdapters(plugin(name));
        assert.equal(analysis.support.supportStatus, 'fully-supported', JSON.stringify(analysis.support));
        assert.equal(analysis.licenseCheck.rightToRedistribute, 'granted');
        for (const target of [path.join(configDir, 'plugins', name), path.join(dir, 'staging', name)]) {
            assert.equal(fs.readFileSync(path.join(target, 'plugin.json'), 'utf8'), fs.readFileSync(path.join(plugin(name), 'plugin.json'), 'utf8'));
        }
    }
});

test('official descriptors have evidence, explicit runtime limits and zero embedded credentials', () => {
    const catalog = loadOfficialMcp();
    assert.equal(catalog.servers.length, 3);
    const matrix = adapters.buildAdapterMatrix(require('../../extensions-inventory.json'));
    for (const s of catalog.servers) {
        for (const id of s.positionIds) {
            const p = matrix.positions.find(p => p.id === id);
            assert.equal(p.portableMcp.id, s.id);
            assert.notEqual(p.supportStatus, 'fully-supported');
            assert.equal(p.portableMcp.runtimeVerification, 'not-run');
        }
    }
    const repaired = matrix.positions.filter(p => p.id.startsWith('local-plugin-'));
    assert.equal(repaired.length, 4);
    assert.ok(repaired.every(p => p.supportStatus === 'fully-supported'));
    assert.equal(new Set(repaired.map(p => p.canonicalId)).size, 2);
});

test('Windows npm descriptors launch through bundled JS without a shell', () => {
    if (process.platform !== 'win32') return;
    const launch = resolveStdioLaunch('npx', ['--version']);
    assert.equal(launch.command, process.execPath);
    const result = spawnSync(launch.command, launch.args, { encoding: 'utf8', windowsHide: true, timeout: 15000 });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout.trim(), /^\d+\.\d+\.\d+/);
    const literal = resolveStdioLaunch('npx', ['package; echo unsafe']);
    assert.equal(literal.args[1], 'package; echo unsafe');
});
