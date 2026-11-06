const test = require('node:test');
const assert = require('node:assert/strict');
const { ToolApprovalBroker, callWithApproval, approvalPreview } = require('../lib/tool-approval');
const tick = () => new Promise(resolve => setImmediate(resolve));
const decision = (request, value = 'APPROVED') => ({ ...request, decision: value });

test('approval waits, binds its scope, and accepts the same decision idempotently', async () => {
    const broker = new ToolApprovalBroker(); let request, ended = false;
    const waiting = broker.ask({ turnId: 'turn', conversationId: 'chat', onRequest: r => request = r }).then(x => { ended = true; return x; });
    await tick(); assert.equal(ended, false);
    for (const changed of [{ token: 'wrong' }, { turnId: 'other' }, { conversationId: 'other' }, { requestId: 'other' }])
        assert.equal(broker.decide({ ...decision(request), ...changed }).ok, false);
    assert.equal(broker.decide(decision(request), { origin: 'https://attacker.example' }).status, 403);
    assert.equal(broker.decide(decision(request)).ok, true);
    assert.equal(await waiting, 'APPROVED');
    assert.equal(broker.decide(decision(request)).ok, true);
    assert.equal(broker.decide(decision(request, 'DENIED')).status, 409);
});

test('one-shot approval executes the immutable original action exactly once', async () => {
    const broker = new ToolApprovalBroker(); let request, actual = 0;
    const args = { path: 'original.txt', content: 'original' };
    const waiting = callWithApproval({ broker, serverId: 'files', toolName: 'write', args,
        options: {}, turnId: 't', conversationId: 'c', onRequest: r => request = r,
        call: async (server, name, received, options) => {
            if (!options.userInitiated) return { ok: false, errorCode: 'tool_needs_approval' };
            actual++; assert.deepEqual(received, { path: 'original.txt', content: 'original' });
            return { ok: true, result: 'written' };
        } });
    await tick(); args.path = 'different.txt'; assert.equal(actual, 0);
    assert.equal(broker.decide(decision(request)).ok, true);
    assert.equal(broker.decide(decision(request)).ok, true);
    const completed = await waiting;
    assert.equal(completed.ok, true); assert.equal(completed.approvalDecision, 'APPROVED'); assert.equal(actual, 1);
});

for (const scenario of ['deny', 'abort', 'expire']) test(`${scenario} never executes the action`, async () => {
    const broker = new ToolApprovalBroker({ timeoutMs: scenario === 'expire' ? 20 : 1000 });
    const controller = new AbortController(); let request, actual = 0;
    const waiting = callWithApproval({ broker, serverId: 's', toolName: 'write', args: {},
        options: { signal: controller.signal }, turnId: 't', conversationId: 'c', onRequest: r => request = r,
        call: async (_, __, ___, options) => {
            if (options.userInitiated) actual++;
            return { ok: false, errorCode: 'tool_needs_approval' };
        } });
    await tick();
    if (scenario === 'deny') broker.decide(decision(request, 'DENIED'));
    if (scenario === 'abort') controller.abort();
    if (scenario === 'expire') await new Promise(resolve => setTimeout(resolve, 35));
    const result = await waiting;
    assert.equal(result.ok, false); assert.equal(actual, 0);
    assert.equal(result.approvalDecision, scenario === 'deny' ? 'DENIED' : undefined);
    assert.equal(result.errorCode, scenario === 'deny' ? 'tool_denied' : scenario === 'expire' ? 'approval_expired' : 'approval_cancelled');
    assert.equal(broker.decide(decision(request)).ok, false);
});

test('explicit deny is not offered for approval, and current policy is checked after approval', async () => {
    const broker = new ToolApprovalBroker(); let request;
    const base = { broker, serverId: 's', toolName: 'x', args: {}, options: {}, turnId: 't', conversationId: 'c', onRequest: r => request = r };
    const denied = await callWithApproval({ ...base, call: async () => ({ ok: false, errorCode: 'tool_denied' }) });
    assert.equal(denied.errorCode, 'tool_denied'); assert.equal(request, undefined);
    const pending = callWithApproval({ ...base, call: async (_, __, ___, o) => ({ ok: false,
        errorCode: o.userInitiated ? 'tool_denied' : 'tool_needs_approval' }) });
    await tick(); broker.decide(decision(request));
    assert.equal((await pending).errorCode, 'tool_denied');
});

test('limits, already-aborted tasks and oversized previews fail closed', async () => {
    const broker = new ToolApprovalBroker({ limit: 0 });
    assert.equal(await broker.ask({ turnId: 't', conversationId: 'c' }), 'UNAVAILABLE');
    const controller = new AbortController(); controller.abort();
    assert.equal(await new ToolApprovalBroker().ask({ turnId: 't', conversationId: 'c', signal: controller.signal }), 'CANCELLED');
    const result = await callWithApproval({ broker: new ToolApprovalBroker(), serverId: 's', toolName: 'x',
        args: { content: 'x'.repeat(16001) }, options: {}, turnId: 't', conversationId: 'c',
        onRequest: () => assert.fail('must not issue a capability'),
        call: async () => ({ ok: false, errorCode: 'tool_needs_approval' }) });
    assert.equal(result.errorCode, 'approval_preview_too_large');
});

test('argument preview redacts credentials without changing the actual action', () => {
    const args = { path: 'file.txt', apiKey: 'key-value', nested: { password: 'password-value' }, command: 'echo known-secret' };
    const preview = approvalPreview(args, ['known-secret']);
    for (const secret of ['key-value', 'password-value', 'known-secret']) assert.equal(preview.includes(secret), false);
    assert.equal(preview.includes('file.txt'), true);
    assert.equal(args.nested.password, 'password-value');
});
