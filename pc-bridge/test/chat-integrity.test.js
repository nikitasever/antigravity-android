const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

test('new turns do not inherit the previous reply in the same conversation', () => {
    const start = source.indexOf('function recordActiveTurnSnapshot(');
    const end = source.indexOf('async function executeAgyTurnStreaming(', start);
    const byId = new Map();
    const byConv = new Map();
    const context = { activeTurnsById: byId, activeTurnsByConv: byConv, Date };
    const record = vm.runInNewContext(`(${source.slice(start, end).trim()})`, context);
    record('old', 'chat', { status: 'completed', reply: 'previous answer' });
    record('new', 'chat', { status: 'running' });
    assert.notEqual(byId.get('new').reply, 'previous answer');
    assert.equal(byId.get('old').reply, 'previous answer');
});

test('OpenRouter without credentials reports failure and never substitutes Gemini', async () => {
    const start = source.indexOf('async function executeOpenRouterStreamingTurn(');
    const end = source.indexOf('function loadAiPermissionsConfig(', start);
    const events = [];
    const context = {
        require, Date, INSTANCE_NAME: 'test', turnCancellations: new Map(),
        liveOpenRouterModelsMemoryCache: [],
        loadOpenRouterConfig: () => ({ apiKey: '' }),
        classifyOpenRouterModel: () => ({}), recordActiveTurnSnapshot: () => {},
        executeAgyTurnStreaming: () => { throw new Error('Unexpected provider switch'); }
    };
    const execute = vm.runInNewContext(`(${source.slice(start, source.lastIndexOf('\n}', end) + 2).trim()})`, context);
    const result = await execute({ model: 'anthropic/test-model', cleanUserPrompt: 'hello' }, e => events.push(e));
    assert.equal(result.ok, false);
    assert.equal(result.model, 'anthropic/test-model');
    assert.equal(events.at(-1).type, 'done');
});

test('plan and verify prompts reach the actual agent instead of fabricated release notes', async () => {
    const start = source.indexOf('async function executeAgyTurnStreaming(');
    const end = source.indexOf('// Управление плагинами', start);
    const execute = vm.runInNewContext(`(${source.slice(start, source.lastIndexOf('\n}', end) + 2).trim()})`, {
        require, Date, DEFAULT_WORKSPACE: 'test', CHAT_FAST_WORKSPACE: 'test',
        inFlightTurns: new Map(), cachedAgyModels: [{ id: 'test' }], resetConversationsSet: new Set(),
        isExplicitPcWorkspaceTask: () => false,
        loadAgentSystemPrompt: () => { throw new Error('REAL_AGENT_PIPELINE'); },
        fs: { writeFileSync: () => { throw new Error('Unexpected filesystem write'); } }
    });
    for (const cleanUserPrompt of ['/plan change', '/verify change', 'проверь структуру проекта']) {
        await assert.rejects(execute({ model: 'test', cleanUserPrompt }), /REAL_AGENT_PIPELINE/);
    }
});
