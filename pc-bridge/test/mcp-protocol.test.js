const { spawnSync } = require('node:child_process');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

test('MCP reports malformed requests and unsupported methods without crashing', () => {
    const input = ['{', 'null', JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'unknown' }),
        JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'ping' }),
        JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })].join('\n') + '\n';
    const result = spawnSync(process.execPath, [path.join(__dirname, '..', 'mcp_sysinfo_server.js')], {
        input, encoding: 'utf8', timeout: 3000
    });
    assert.equal(result.status, 0, result.stderr);
    const messages = result.stdout.trim().split('\n').map(JSON.parse);
    assert.deepEqual(messages.map(m => m.error?.code || 'ok'), [-32700, -32600, -32601, 'ok']);
    assert.equal(messages.at(-1).id, 2);
});
