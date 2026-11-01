const test = require('node:test');
const assert = require('node:assert/strict');
const { SubagentRuntime } = require('../lib/subagent-runtime');
const tick = () => new Promise(resolve => setImmediate(resolve));

test('timeout remains pending for consumers until the late error review has been collected', async () => {
    let rejectWorker;
    const runtime = new SubagentRuntime({ limit: 1, timeoutMs: 10, run: () =>
        new Promise((_, reject) => { rejectWorker = reject; }) });
    runtime.dispatch({ task: 'Review', model: 'test' });
    await new Promise(resolve => setTimeout(resolve, 30));
    runtime.cancel(); // Cancel the second queued worker; the timed-out worker is still settling.
    const pending = runtime.snapshot();
    assert.equal(pending.agents[0].status, 'failed');
    assert.equal(pending.agents[0].settling, true);
    assert.equal(pending.swarmActive, true);
    assert.throws(() => runtime.dispatch({ task: 'new', model: 'test' }), /ещё выполняется/);
    const failure = new Error('late CLI failure');
    failure.review = { diff: 'partial changes' };
    rejectWorker(failure); await tick();
    const finished = runtime.snapshot();
    assert.equal(finished.swarmActive, false);
    assert.equal(finished.agents[0].settling, false);
    assert.equal(finished.agents[0].review.diff, 'partial changes');
});

test('timeout does not free an executor slot before the process settles', async () => {
    const releases = [];
    const runtime = new SubagentRuntime({ limit: 1, timeoutMs: 10, run: () =>
        new Promise(resolve => releases.push(resolve)) });
    runtime.dispatch({ task: 'slow process', model: 'test' });
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.deepEqual(runtime.snapshot().agents.map(a => a.status), ['failed', 'queued']);
    assert.equal(runtime.active, 1);
    assert.equal(releases.length, 1);
    releases.shift()('late'); await tick();
    assert.equal(releases.length, 1);
    releases.shift()('second'); await tick();
    assert.equal(runtime.active, 0);
    assert.equal(runtime.snapshot().agents[0].result, '');
});

test('independent workers obey concurrency and duplicate dispatch never starts extra calls', async () => {
    let calls = 0;
    const releases = [];
    const runtime = new SubagentRuntime({ limit: 1, run: () => {
        calls++; return new Promise(resolve => releases.push(resolve));
    } });
    runtime.dispatch({ task: 'Review', model: 'test', requestId: 'same' });
    assert.deepEqual(runtime.snapshot().agents.map(a => a.status), ['running', 'queued']);
    runtime.dispatch({ task: 'Review', model: 'test', requestId: 'same' });
    assert.equal(calls, 1);
    releases.shift()('First result'); await tick();
    assert.equal(calls, 2);
    releases.shift()('Second result'); await tick();
    assert.equal(runtime.snapshot().swarmActive, false);
    assert.deepEqual(runtime.snapshot().agents.map(a => a.result), ['First result', 'Second result']);
});

test('cancel prevents queued work and late result cannot turn cancellation into success', async () => {
    let release;
    let calls = 0;
    const runtime = new SubagentRuntime({ limit: 1, run: () => {
        calls++; return new Promise(resolve => { release = resolve; });
    } });
    runtime.dispatch({ task: 'Review', model: 'test' });
    runtime.cancel();
    release('Late result'); await tick();
    assert.equal(calls, 1);
    assert.deepEqual(runtime.snapshot().agents.map(a => a.status), ['cancelled', 'cancelled']);
});

test('a late review is never attached to a cancelled job', async () => {
    let release;
    const runtime = new SubagentRuntime({ limit: 1, run: () =>
        new Promise(resolve => { release = resolve; }) });
    runtime.dispatch({ task: 'Review', model: 'test' });
    runtime.cancel();
    release({ text: 'late', review: 'fabricated-review' }); await tick();
    const cancelled = runtime.snapshot().agents;
    assert.ok(cancelled.every(a => a.status === 'cancelled'));
    assert.ok(cancelled.every(a => a.review === undefined && a.result === ''));
});

test('a review produced by a real error is still kept for the terminal failed job', async () => {
    const runtime = new SubagentRuntime({ run: () => { const error = new Error('CLI failure'); error.review = 'partial-review'; throw error; } });
    runtime.dispatch({ task: 'Review', model: 'test' });
    await tick();
    const agents = runtime.snapshot().agents;
    assert.ok(agents.every(a => a.status === 'failed' && a.review === 'partial-review' && a.result === ''));
});

test('worker failure and timeout are terminal failures, never fabricated findings', async () => {
    const runtime = new SubagentRuntime({ timeoutMs: 10, run: ({ name }) => {
        if (name === 'Исследователь') throw new Error('CLI unavailable');
        return new Promise(() => {});
    } });
    runtime.dispatch({ task: 'Review', model: 'test' });
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.deepEqual(runtime.snapshot().agents.map(a => a.status), ['failed', 'failed']);
    assert.ok(runtime.snapshot().agents.every(a => a.result === '' && a.error));
});

test('an old request reads its own results and cannot cancel a newer batch', async () => {
    const runtime = new SubagentRuntime({ run: async ({ task }) => task });
    runtime.dispatch({ task: 'first', model: 'test', requestId: 'a' });
    await tick();
    runtime.dispatch({ task: 'second', model: 'test', requestId: 'b' });
    const first = runtime.dispatch({ task: 'first', model: 'test', requestId: 'a' });
    assert.equal(first.batchId, 'a');
    assert.ok(first.agents.every(a => a.result === 'first'));
    runtime.cancel('a');
    await tick();
    assert.ok(runtime.snapshotFor('b').agents.every(a => a.result === 'second' && a.status === 'completed'));
    assert.equal(runtime.snapshotFor('missing').agents.length, 0);
});
