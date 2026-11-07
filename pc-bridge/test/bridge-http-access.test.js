'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { BridgeAccess } = require('../lib/bridge-access');
const { createBridgeHttpAccess } = require('../lib/bridge-http-access');

async function fixture(work) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-http-qa-'));
    const access = new BridgeAccess({ file: path.join(directory, 'owner.json') });
    const guard = createBridgeHttpAccess(access);
    let privateCalls = 0;
    const server = http.createServer(async (req, res) => {
        if (await guard(req, res, new URL(req.url, 'http://localhost'))) return;
        privateCalls++; res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true}');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const owner = { 'X-Antigravity-Bridge-Token': access.token };
    try { await work({ access, base, owner, calls: () => privateCalls }); }
    finally {
        server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
        assert.equal(path.dirname(fs.realpathSync(directory)), fs.realpathSync(os.tmpdir()));
        assert.ok(path.basename(directory).startsWith('bridge-http-qa-')); fs.rmSync(directory, { recursive: true });
    }
}

test('real HTTP unauthenticated private APIs never reach paid, permission, configuration or static handlers', () => fixture(async ({ base, owner, calls }) => {
    for (const route of ['/api/openrouter/config', '/api/permissions', '/api/chat', '/api/openrouter/video', '/generated_media/a.mp4', '/api/status', '/web']) {
        const response = await fetch(base + route, { method: route.endsWith('mp4') || route.endsWith('status') || route.endsWith('web') ? 'GET' : 'POST' });
        assert.equal(response.status, 401, route);
    }
    assert.equal(calls(), 0);
    const health = await (await fetch(base + '/api/health')).json();
    assert.deepEqual(Object.keys(health).sort(), ['ok', 'requiresPairing', 'status']);
    assert.equal((await fetch(base + '/api/status', { headers: owner })).status, 200);
    assert.equal(calls(), 1);
}));

test('real HTTP pairing is single-use, bounded and cannot be initiated by cross-origin browser forms', () => fixture(async ({ access, base, calls }) => {
    const pin = access.openPairing().code;
    const post = (code, headers = {}) => fetch(base + '/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify({ code }) });
    assert.equal((await post(pin, { Origin: 'https://evil.example' })).status, 403);
    assert.equal((await post('wrong')).status, 403);
    const result = await (await post(pin)).json();
    assert.equal(result.bridgeToken, access.token);
    assert.equal((await post(pin)).status, 403);
    assert.equal(calls(), 0);
}));

test('browser uses one-use ticket and HttpOnly cookie; cross-origin mutations fail', () => fixture(async ({ base, owner }) => {
    const ticket = (await (await fetch(base + '/api/web-ticket', { method: 'POST', headers: owner })).json()).ticket;
    assert.equal(ticket.length, 64);
    const opened = await fetch(base + '/web?ticket=' + ticket + '&session=qa', { redirect: 'manual' });
    assert.equal(opened.status, 303); assert.equal(opened.headers.get('location'), '/web?session=qa');
    const cookie = opened.headers.get('set-cookie'); assert.match(cookie, /HttpOnly/); assert.match(cookie, /SameSite=Strict/);
    assert.equal(opened.headers.get('referrer-policy'), 'no-referrer');
    assert.equal((await fetch(base + '/web?ticket=' + ticket, { redirect: 'manual' })).status, 403);
    const headers = { Cookie: cookie.split(';')[0] };
    assert.equal((await fetch(base + '/api/sessions', { headers })).status, 200);
    assert.equal((await fetch(base + '/api/chat', { method: 'POST', headers })).status, 403);
    assert.equal((await fetch(base + '/api/chat', { method: 'POST', headers: { ...headers, Origin: 'https://evil.example' } })).status, 403);
    assert.equal((await fetch(base + '/api/chat', { method: 'POST', headers: { ...headers, Origin: base } })).status, 200);
}));

test('server installs guard before private and OAuth handlers and retains state checks', () => {
    const source = fs.readFileSync(path.join(__dirname, '../server.js'), 'utf8');
    const server = source.slice(source.indexOf('const server = http.createServer'));
    assert.ok(server.indexOf('await guardBridgeAccess') < server.indexOf('mcpOAuthApi.callback'));
    assert.ok(server.indexOf('await guardBridgeAccess') < server.indexOf('renderLiveWebConsoleHtml'));
    const callback = server.slice(server.indexOf("urlObj.pathname === '/api/gdrive/oauth/callback'"));
    assert.ok(callback.indexOf('driveOAuthStates.delete(state)') < callback.indexOf('httpsJsonRequest'));
    assert.ok(callback.indexOf('pending.expiresAt <= Date.now()') < callback.indexOf('httpsJsonRequest'));
});
