// Поштучные права инструментов MCP — «как у них» (референс Claude:
// McpToolPermission / McpSetAllToolsPermission; «Ask before sending» /
// «Always allow» / «Deny»). Проверяется семантика трёх уровней (tool →
// «все инструменты сервера» → глобальная политика), честные отказы deny/ask и
// ранняя проверка ДО соединения в McpClientManager.callTool.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createToolPermissions, VALID_PERMISSIONS } = require('../lib/tool-permissions');
const { McpClientManager } = require('../lib/mcp-client');

function tmpConfig(t, initial = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-toolperm-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const file = path.join(dir, 'permissions-config.json');
    if (Object.keys(initial).length) fs.writeFileSync(file, JSON.stringify(initial, null, 2), 'utf8');
    return { file, tp: createToolPermissions({ configPath: file }) };
}

test('права: допустимые значения и дефолт от глобальной политики', t => {
    assert.deepEqual(VALID_PERMISSIONS, ['allow', 'ask', 'deny']);
    const { tp } = tmpConfig(t);
    assert.equal(tp.globalDefault(), 'allow', 'нет файла — политика always-proceed → allow');
    const { tp: tp2 } = tmpConfig(t, { toolExecutionPolicy: 'request-review' });
    assert.equal(tp2.globalDefault(), 'ask', 'иная политика → ask');
});

test('права: приоритет tool > сервер «все инструменты» > политика', t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    assert.deepEqual(tp.getToolPermission('srv', 'read'), { permission: 'allow', source: 'policy' });
    tp.setServerDefault('srv', 'ask');
    assert.deepEqual(tp.getToolPermission('srv', 'read'), { permission: 'ask', source: 'server' });
    tp.setToolPermission('srv', 'read', 'deny');
    assert.deepEqual(tp.getToolPermission('srv', 'read'), { permission: 'deny', source: 'tool' });
    assert.deepEqual(tp.getToolPermission('srv', 'write'), { permission: 'ask', source: 'server' }, 'остальные инструменты — по серверу');
    assert.equal(tp.getServerDefault('srv'), 'ask');
});

test('права: запись сохраняет остальные ключи permissions-config.json', t => {
    const { file, tp } = tmpConfig(t, { preset: 'AUTOPILOT', toolExecutionPolicy: 'always-proceed', phoneFileSystemAccess: true });
    const saved = tp.setToolPermission('srv', 'read', 'deny');
    assert.equal(saved.ok, true);
    const back = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(back.preset, 'AUTOPILOT', 'прочие ключи не перетёрты');
    assert.equal(back.phoneFileSystemAccess, true);
    assert.equal(back.toolPermissions.srv.tools.read, 'deny');
});

test('права: deny блокирует всех; ask — только вызовы модели', t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setToolPermission('srv', 'danger', 'deny');
    const denied = tp.checkToolCall('srv', 'danger', { userInitiated: true });
    assert.equal(denied.allow, false);
    assert.equal(denied.errorCode, 'tool_denied');
    assert.match(denied.error, /Запретить/);
    tp.setToolPermission('srv', 'maybe', 'ask');
    const modelCall = tp.checkToolCall('srv', 'maybe', { userInitiated: false });
    assert.equal(modelCall.allow, false);
    assert.equal(modelCall.errorCode, 'tool_needs_approval');
    assert.match(modelCall.error, /Спрашивать/);
    const userCall = tp.checkToolCall('srv', 'maybe', { userInitiated: true });
    assert.equal(userCall.allow, true, 'явное действие пользователя проходит ask');
});

test('права: невалидные значения отклоняются без записи', t => {
    const { file, tp } = tmpConfig(t);
    assert.equal(tp.setToolPermission('srv', 'x', 'maybe').ok, false);
    assert.equal(tp.setServerDefault('srv', '').ok, false);
    assert.equal(fs.existsSync(file), false, 'файл не создан при отказе');
});

test('McpClientManager: проверка прав выполняется ДО соединения', async t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setToolPermission('srvX', 'danger', 'deny');
    const manager = new McpClientManager({ clientOptions: { toolPermissions: tp } });
    // Сервер не зарегистрирован: без права вернулся бы not_connected,
    // но deny должен сработать раньше — вызов не начинается вовсе.
    const denied = await manager.callTool('srvX', 'danger', {});
    assert.equal(denied.ok, false);
    assert.equal(denied.errorCode, 'tool_denied');
    const notConnected = await manager.callTool('srvX', 'safe', {});
    assert.equal(notConnected.ok, false);
    assert.equal(notConnected.errorCode, 'not_connected', 'разрешённый инструмент доходит до штатной проверки соединения');
    const asked = await manager.callTool('srvX', 'danger', {}, { userInitiated: true });
    assert.equal(asked.ok, false);
    assert.notEqual(asked.errorCode, 'tool_needs_approval', 'deny действует и для явных вызовов');
});

// --- Скоупы «как у них» («Always allow for this project / session / task / website») ---

test('скоупы: session и task побеждают project-право; task — самый специфичный', t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setToolPermission('srv', 'tool1', 'deny', { scope: 'project' });
    assert.equal(tp.getToolPermission('srv', 'tool1').permission, 'deny');
    tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'session', scopeId: 'conv-1' });
    assert.deepEqual(
        tp.getToolPermission('srv', 'tool1', { sessionId: 'conv-1' }),
        { permission: 'allow', source: 'session' },
        'session-скоуп побеждает project-запрет'
    );
    assert.equal(tp.getToolPermission('srv', 'tool1', { sessionId: 'other' }).permission, 'deny',
        'для другой сессии project-право остаётся');
    tp.setToolPermission('srv', 'tool1', 'ask', { scope: 'task', scopeId: 'batch-1' });
    assert.deepEqual(
        tp.getToolPermission('srv', 'tool1', { sessionId: 'conv-1', taskId: 'batch-1' }),
        { permission: 'ask', source: 'task' },
        'task-скоуп побеждает session'
    );
});

test('скоупы: session/task не пишутся в permissions-config.json и не переживают новый экземпляр', t => {
    const { file, tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'session', scopeId: 'conv-1' });
    tp.setToolPermission('srv', 'tool2', 'allow', { scope: 'task', scopeId: 'batch-1' });
    const back = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(back.toolPermissions?.srv?.tools?.tool1, undefined, 'session-право не в файле');
    assert.equal(back.toolPermissions?.srv?.tools?.tool2, undefined, 'task-право не в файле');
    const { createToolPermissions } = require('../lib/tool-permissions');
    const fresh = createToolPermissions({ configPath: file });
    assert.equal(fresh.getToolPermission('srv', 'tool1', { sessionId: 'conv-1' }).source, 'policy',
        'новый экземпляр (после перезапуска моста) не помнит session-скоуп');
});

test('скоупы: website действует по origin и честно недоступен без него', t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setServerDefault('srv', 'ask');
    const noOrigin = tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'website' });
    assert.equal(noOrigin.ok, false);
    assert.equal(noOrigin.code, 'website_scope_requires_origin', 'stdio-сервер без origin — честный отказ');
    const saved = tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'website', origin: 'https://mcp.example.com' });
    assert.equal(saved.ok, true);
    assert.deepEqual(
        tp.getToolPermission('srv', 'tool1', { origin: 'https://mcp.example.com' }),
        { permission: 'allow', source: 'website' },
        'website-скоуп побеждает server-право'
    );
    assert.equal(tp.getToolPermission('srv', 'tool1', { origin: 'https://other.example.com' }).permission, 'ask',
        'другой origin получает server-право');
});

test('скоупы: неверный scopeId — честный отказ без записи', t => {
    const { tp } = tmpConfig(t);
    const r1 = tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'session' });
    assert.equal(r1.ok, false);
    assert.match(r1.error, /sessionId/);
    const r2 = tp.setToolPermission('srv', 'tool1', 'allow', { scope: 'task' });
    assert.equal(r2.ok, false);
    assert.match(r2.error, /taskId/);
});

test('скоупы: checkToolCall сообщает источник скоупа в отказе', t => {
    const { tp } = tmpConfig(t, { toolExecutionPolicy: 'always-proceed' });
    tp.setToolPermission('srv', 'tool1', 'deny', { scope: 'session', scopeId: 'conv-1' });
    const denied = tp.checkToolCall('srv', 'tool1', { sessionId: 'conv-1' });
    assert.equal(denied.allow, false);
    assert.match(denied.error, /скоуп: session/);
});
