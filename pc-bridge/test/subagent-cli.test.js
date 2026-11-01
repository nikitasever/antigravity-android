const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { killProcessTree } = require('../lib/subagent-cli');

for (const failure of ['error', 'exit', 'throw']) {
    test('taskkill ' + failure + ' is handled and falls back without an unhandled error', async () => {
        const kills = [];
        const child = { pid: 1234, kill: signal => kills.push(signal) };
        const stop = killProcessTree(child, (cmd, args) => {
            assert.equal(cmd, 'taskkill');
            assert.deepEqual(args, ['/pid', '1234', '/T', '/F']);
            if (failure === 'throw') throw new Error('spawn failed');
            const killer = new EventEmitter();
            setImmediate(() => {
                if (failure === 'error') killer.emit('error', new Error('ENOENT'));
                killer.emit('close', failure === 'exit' ? 5 : -1);
            });
            return killer;
        }, 'win32');
        assert.equal(await stop, false);
        assert.deepEqual(kills, ['SIGKILL']);
    });
}

test('successful taskkill does not fall back; POSIX reports only direct-child termination', async () => {
    const kills = [];
    const child = { pid: 1234, kill: signal => kills.push(signal) };
    assert.equal(await killProcessTree(child, () => {
        const killer = new EventEmitter();
        setImmediate(() => killer.emit('close', 0));
        return killer;
    }, 'win32'), true);
    assert.deepEqual(kills, []);
    assert.equal(await killProcessTree(child, () => { throw new Error('must not spawn'); }, 'linux'), false);
    assert.deepEqual(kills, ['SIGKILL']);
    assert.equal(await killProcessTree(null, () => {}), false);
});
