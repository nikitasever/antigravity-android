const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { AgentWorkspace } = require('../lib/agent-workspace');
const { createSubagentRunner } = require('../lib/subagent-cli');
const { SubagentRuntime } = require('../lib/subagent-runtime');
const { spawn } = require('node:child_process');

test('code tasks run in distinct copies and return review through runtime without editing source', async t => {
    const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-code-test-'));
    const repository = path.join(temporary, 'project');
    await fs.mkdir(repository);
    const manager = new AgentWorkspace(repository, path.join(temporary, 'workers'));
    const copies = [];
    t.after(async () => {
        for (const copy of copies) await manager.git(['worktree', 'remove', '--force', copy]);
        await fs.rm(temporary, { recursive: true, force: true });
    });
    await manager.git(['init']);
    await fs.writeFile(path.join(repository, 'code.txt'), 'original\n');
    await manager.git(['add', 'code.txt']);
    await manager.git(['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture']);
    const runner = createSubagentRunner({ executable: 'test-cli', repository, workspaces: manager,
        spawnProcess: (_executable, args, options) => {
            assert.equal(args[1], 'accept-edits');
            assert.ok(args.includes('--sandbox'));
            assert.ok(!args.includes('--dangerously-skip-permissions'));
            copies.push(options.cwd);
            return spawn(process.execPath, ['-e', "require('fs').writeFileSync('code.txt','proposal\\n'); require('fs').writeFileSync('new.txt','new content'); console.log('Prepared')"], options);
        } });
    const runtime = new SubagentRuntime({ run: runner });
    runtime.dispatch({ task: 'prepare a change', model: 'test', mode: 'code' });
    // Two real Git worktrees and process startups can exceed 10 s on Windows
    // while Gradle/AV scan the workspace. Keep assertions, allow a bounded wait.
    let deadline = Date.now() + 60000;
    while (runtime.active && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    const agents = runtime.snapshot().agents;
    assert.deepEqual(agents.map(a => a.status), ['completed', 'completed']);
    assert.equal(new Set(copies).size, 2);
    assert.ok(agents.every(a => a.review.diff.includes('+proposal') && a.review.newFiles[0].content === 'new content'));
    assert.equal(await fs.readFile(path.join(repository, 'code.txt'), 'utf8'), 'original\n');
    await fs.writeFile(path.join(repository, 'code.txt'), 'user changes\n');
    runtime.dispatch({ task: 'must reject dirty source', model: 'test', mode: 'code' });
    deadline = Date.now() + 60000;
    while (runtime.active && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(runtime.snapshot().agents.every(a => a.status === 'failed' && a.error.includes('preserve project changes')));
    assert.equal(copies.length, 2);
});

test('isolated Git worktree preserves source and exposes edits, new files and stale source', async t => {
    const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-workspace-test-'));
    const repository = path.join(temporary, 'project');
    await fs.mkdir(repository);
    const manager = new AgentWorkspace(repository, path.join(temporary, 'workers'));
    t.after(() => fs.rm(temporary, { recursive: true, force: true }));
    await manager.git(['init']);
    await fs.writeFile(path.join(repository, 'code.txt'), 'original\n');
    await manager.git(['add', 'code.txt']);
    await manager.git(['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture']);
    const worker = await manager.create();
    await fs.writeFile(path.join(worker.directory, 'code.txt'), 'proposed\n');
    await fs.writeFile(path.join(worker.directory, 'new.txt'), 'new file\n');
    let review = await manager.review(worker);
    assert.match(review.diff, /\+proposed/);
    assert.deepEqual(review.untracked, ['new.txt']);
    assert.equal(review.sourceChanged, false);
    assert.equal(await fs.readFile(path.join(repository, 'code.txt'), 'utf8'), 'original\n');
    await fs.writeFile(path.join(repository, 'code.txt'), 'user edit\n');
    review = await manager.review(worker);
    assert.equal(review.sourceChanged, true);
    await assert.rejects(manager.create(), /preserve project changes/);
    await assert.rejects(manager.review({ ...worker, id: '../project' }), /Invalid workspace/);
    await manager.git(['worktree', 'remove', '--force', worker.directory]);
});

test('worktree storage inside the source project is rejected', async t => {
    const repository = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-storage-test-'));
    t.after(() => fs.rm(repository, { recursive: true, force: true }));
    const manager = new AgentWorkspace(repository, path.join(repository, 'workers'));
    await manager.git(['init']);
    await assert.rejects(manager.create(), /outside the project/);
});

test('automatic pruning preserves dirty, locked and unreviewed proposals beyond the old limit', async t => {
    const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-prune-test-'));
    const repository = path.join(temporary, 'project');
    const storage = path.join(temporary, 'workers');
    await fs.mkdir(repository);
    const manager = new AgentWorkspace(repository, storage, { keep: 1 });
    t.after(() => fs.rm(temporary, { recursive: true, force: true }));
    await manager.git(['init']);
    await fs.writeFile(path.join(repository, 'code.txt'), 'original\n');
    await manager.git(['add', 'code.txt']);
    await manager.git(['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture']);
    const first = await manager.create();
    await fs.writeFile(path.join(first.directory, 'code.txt'), 'unapplied proposal\n');
    await manager.git(['worktree', 'lock', first.directory]);
    const second = await manager.create();
    assert.deepEqual(await manager.prune(), []);
    assert.equal(await fs.readFile(path.join(first.directory, 'code.txt'), 'utf8'), 'unapplied proposal\n');
    assert.ok((await manager.git(['worktree', 'list', '--porcelain'])).includes('locked'));
    await assert.doesNotReject(fs.access(second.directory));
});
