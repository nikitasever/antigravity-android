// Режимы разрешений «как у них» (референс Claude: PermissionMode =
// default/plan/auto/acceptEdits/dontAsk/bypassPermissions). Проверяются:
// сохранение режима, честная эвристика риска (classifyToolRisk), семантика
// каждого режима, правило «deny прав никогда не обходится» и граница модели
// (эвристика вместо модельной проверки — «требует проверки»).
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createPermissionModes, VALID_MODES, classifyToolRisk } = require('../lib/permission-modes');
const { createToolPermissions } = require('../lib/tool-permissions');

function tmpBoth(t, initial = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-permmodes-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const file = path.join(dir, 'permissions-config.json');
    if (Object.keys(initial).length) fs.writeFileSync(file, JSON.stringify(initial, null, 2), 'utf8');
    return {
        file,
        modes: createPermissionModes({ configPath: file }),
        tp: createToolPermissions({ configPath: file })
    };
}

test('режимы: состав значений совпадает с референсом; сохранение и валидация', t => {
    assert.deepEqual(VALID_MODES, ['default', 'plan', 'auto', 'acceptEdits', 'dontAsk', 'bypassPermissions']);
    const { file, modes } = tmpBoth(t);
    assert.equal(modes.getMode(), 'default', 'без ключа — default');
    assert.equal(modes.setMode('auto').ok, true);
    assert.equal(modes.getMode(), 'auto');
    const back = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(back.permissionMode, 'auto');
    assert.equal(modes.setMode('yolo').ok, false, 'неизвестный режим отклоняется');
    assert.equal(modes.getMode(), 'auto', 'отказ не меняет режим');
});

test('эвристика: имена выполнения/разрушения и опасные аргументы дают high-risk', t => {
    assert.equal(classifyToolRisk('read_file', {}).risk, 'low');
    assert.equal(classifyToolRisk('write_file', {}).category, 'write');
    assert.equal(classifyToolRisk('delete_file', {}).risk, 'high');
    assert.equal(classifyToolRisk('run_shell', { command: 'ls' }).risk, 'high');
    const sneaky = classifyToolRisk('harmless', { cmd: 'sudo rm -rf /' });
    assert.equal(sneaky.risk, 'high', 'опасные аргументы видны и у «безобидного» имени');
    assert.ok(sneaky.reasons.length >= 1);
});

test('режим plan: запись и выполнение запрещены, чтение проходит', t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'always-proceed' });
    const readBase = tp.checkToolCall('srv', 'read_file', {});
    const planRead = modes.applyModeToCheck(readBase, 'read_file', {}, { mode: 'plan' });
    assert.equal(planRead.allow, true);
    const writeBase = tp.checkToolCall('srv', 'write_file', {});
    const planWrite = modes.applyModeToCheck(writeBase, 'write_file', {}, { mode: 'plan' });
    assert.equal(planWrite.allow, false);
    assert.equal(planWrite.errorCode, 'tool_denied');
    assert.match(planWrite.error, /режиме «План»/);
});

test('режим auto: high-risk требует подтверждения, low-risk проходит; граница модели заявлена', t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'always-proceed' });
    const lowBase = tp.checkToolCall('srv', 'read_file', {});
    assert.equal(modes.applyModeToCheck(lowBase, 'read_file', {}, { mode: 'auto' }).allow, true);
    const highBase = tp.checkToolCall('srv', 'delete_file', {});
    const autoHigh = modes.applyModeToCheck(highBase, 'delete_file', {}, { mode: 'auto', userInitiated: false });
    assert.equal(autoHigh.allow, false);
    assert.equal(autoHigh.errorCode, 'tool_needs_approval');
    assert.match(autoHigh.error, /НАША эвристическая оценка/);
    assert.match(autoHigh.error, /«требует проверки»/);
    const userCall = modes.applyModeToCheck(highBase, 'delete_file', {}, { mode: 'auto', userInitiated: true });
    assert.equal(userCall.allow, true, 'явное действие пользователя проходит auto-подтверждение');
});

test('режим acceptEdits: правки принимаются, разрушительное выполнение — с подтверждения', t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'request-review' });
    const writeBase = tp.checkToolCall('srv', 'write_file', {});
    assert.equal(writeBase.allow, false, 'база: ask (политика request-review)');
    const edit = modes.applyModeToCheck(writeBase, 'write_file', {}, { mode: 'acceptEdits', userInitiated: false });
    assert.equal(edit.allow, true, 'правки в режиме acceptEdits проходят автоматически');
    const dangerous = tp.checkToolCall('srv', 'run_shell', {});
    const exec = modes.applyModeToCheck(dangerous, 'run_shell', { command: 'echo hi' }, { mode: 'acceptEdits', userInitiated: false });
    assert.equal(exec.allow, false);
    assert.equal(exec.errorCode, 'tool_needs_approval');
});

test('dontAsk/bypassPermissions: снимают «Спрашивать», но НЕ обходят deny прав', t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'request-review' });
    const asked = tp.checkToolCall('srv', 'any_tool', {});
    assert.equal(asked.allow, false);
    for (const mode of ['dontAsk', 'bypassPermissions']) {
        const relaxed = modes.applyModeToCheck(asked, 'any_tool', {}, { mode, userInitiated: false });
        assert.equal(relaxed.allow, true, `${mode} снимает ask`);
    }
    tp.setToolPermission('srv', 'forbidden', 'deny');
    const denied = tp.checkToolCall('srv', 'forbidden', {});
    for (const mode of ['dontAsk', 'bypassPermissions', 'auto', 'acceptEdits']) {
        const still = modes.applyModeToCheck(denied, 'forbidden', {}, { mode, userInitiated: true });
        assert.equal(still.allow, false, `${mode} не обходит явный deny`);
    }
});

test('режим default: вердикт прав проходит без изменений', t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'always-proceed' });
    const base = tp.checkToolCall('srv', 'delete_file', {});
    const same = modes.applyModeToCheck(base, 'delete_file', {}, { mode: 'default' });
    assert.deepEqual(same, base);
});

test('McpClientManager: режим plan блокирует запись ДО соединения', async t => {
    const { modes, tp } = tmpBoth(t, { toolExecutionPolicy: 'always-proceed' });
    modes.setMode('plan');
    const { McpClientManager } = require('../lib/mcp-client');
    const manager = new McpClientManager({ clientOptions: { toolPermissions: tp, permissionModes: modes } });
    const write = await manager.callTool('srvX', 'write_file', {});
    assert.equal(write.ok, false);
    assert.equal(write.errorCode, 'tool_denied');
    const read = await manager.callTool('srvX', 'read_file', {});
    assert.equal(read.ok, false);
    assert.equal(read.errorCode, 'not_connected', 'чтение доходит до штатной проверки соединения');
});
