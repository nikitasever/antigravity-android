// Семантика панели субагентов «как у них» (референс Claude: «%1$d running
// task(s)», «No background tasks», «Your computer is busy with other
// sessions…», «Running in the shared directory… consider worktree mode»):
// счётчики, статус занятости и режим рабочего каталога в snapshot.
const test = require('node:test');
const assert = require('node:assert');

const { SubagentRuntime } = require('../lib/subagent-runtime');

function makeRuntime(t, runImpl) {
    const runtime = new SubagentRuntime({ run: runImpl, limit: 2, timeoutMs: 5000 });
    return runtime;
}

test('snapshot: пустое состояние — «Нет фоновых задач», счётчики нулевые', t => {
    const runtime = makeRuntime(t, async () => 'ok');
    const snap = runtime.snapshot();
    assert.equal(snap.swarmActive, false);
    assert.equal(snap.runningTasks, 0, 'нет выполняющихся задач');
    assert.deepEqual(snap.counts, { queued: 0, running: 0, completed: 0, failed: 0, cancelled: 0 });
    assert.equal(snap.busy, false);
    assert.equal(snap.busyMessage, '');
});

test('snapshot: запущенная пачка — busy с сообщением и счётчиками', async t => {
    let release;
    const gate = new Promise(r => { release = r; });
    const runtime = makeRuntime(t, async () => { await gate; return 'готово'; });
    const snap = runtime.dispatch({ task: 'задача', model: 'm', requestId: 'req-1', mode: 'analysis' });
    assert.equal(snap.busy, true);
    assert.match(snap.busyMessage, /занят другими сессиями/, 'текст занятости «как у них»');
    assert.equal(snap.runningTasks, 2, 'исполнитель + рецензент');
    assert.equal(snap.counts.running, 2);
    // Повторный запуск при занятости — честный отказ, не выдуманный успех.
    assert.throws(() => runtime.dispatch({ task: 'ещё', model: 'm', requestId: 'req-2' }), /Предыдущая задача ещё выполняется/);
    release();
    await new Promise(r => setTimeout(r, 30));
    const done = runtime.snapshot();
    assert.equal(done.busy, false);
    assert.equal(done.counts.completed, 2);
    assert.equal(done.runningTasks, 0);
});

test('snapshot: режим рабочего каталога — код изолирован, анализ в общем каталоге', async t => {
    const runtime = makeRuntime(t, async () => 'отчёт');
    runtime.dispatch({ task: 'задача', model: 'm', requestId: 'req-c', mode: 'code' });
    await new Promise(r => setTimeout(r, 30));
    const snap = runtime.snapshot();
    // Вся пачка кода наследует режим диспатча: обе задачи — в изолированных
    // рабочих копиях (subagent-cli: workspaces.create + review).
    for (const agent of snap.agents) {
        assert.equal(agent.workspaceMode, 'isolated', `${agent.name}: изолированная рабочая копия`);
        assert.equal(agent.sharedDirectoryWarning, '');
    }
    const runtime2 = makeRuntime(t, async () => 'отчёт');
    runtime2.dispatch({ task: 'задача', model: 'm', requestId: 'req-a', mode: 'analysis' });
    await new Promise(r => setTimeout(r, 30));
    const snap2 = runtime2.snapshot();
    for (const agent of snap2.agents) {
        assert.equal(agent.workspaceMode, 'shared', `${agent.name}: общий каталог`);
        assert.match(agent.sharedDirectoryWarning, /общем каталоге/, 'подсказка об общем каталоге «как у них»');
    }
});
