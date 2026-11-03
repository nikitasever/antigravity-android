'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { McpOAuth, CredentialVault, REDIRECT_URI, challenge } = require('../lib/mcp-oauth');
const { McpClientManager, buildMcpDescriptor } = require('../lib/mcp-client');
const { createMcpOAuthApi, normalizeOAuthAction } = require('../lib/mcp-oauth-api');

async function fixture(t, { redirectUri = REDIRECT_URI } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-oauth-'));
    let origin, authChallenge, refreshes = 0, revokes = 0, now = Date.now();
    let rejectRefresh = false, rejectAccess = false, echoToken = false, metadataOverride = {}, prmOverride = {};
    const server = http.createServer(async (req, res) => {
        const url = new URL(req.url, origin);
        const json = (status, body) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); };
        if (url.pathname === '/.well-known/oauth-protected-resource/mcp') return json(200, { resource: origin + '/mcp', authorization_servers: [origin], scopes_supported: ['read'], ...prmOverride });
        if (url.pathname === '/.well-known/oauth-authorization-server') return json(200, { issuer: origin, authorization_endpoint: origin + '/authorize', token_endpoint: origin + '/token', registration_endpoint: origin + '/register', revocation_endpoint: origin + '/revoke', code_challenge_methods_supported: ['S256'], ...metadataOverride });
        let raw = ''; for await (const chunk of req) raw += chunk;
        if (url.pathname === '/register') {
            const registration = JSON.parse(raw);
            assert.deepEqual(registration.redirect_uris, [redirectUri]);
            assert.equal(registration.token_endpoint_auth_method, 'none');
            return json(201, { client_id: 'public-test', token_endpoint_auth_method: 'none' });
        }
        if (url.pathname === '/authorize') {
            assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
            assert.equal(url.searchParams.get('resource'), origin + '/mcp');
            assert.equal(url.searchParams.get('redirect_uri'), redirectUri);
            authChallenge = url.searchParams.get('code_challenge');
            return json(200, { code: 'test-code' });
        }
        if (url.pathname === '/token') {
            const form = new URLSearchParams(raw);
            assert.equal(form.get('resource'), origin + '/mcp');
            assert.equal(form.get('client_id'), 'public-test');
            if (form.get('grant_type') === 'authorization_code') {
                assert.equal(challenge(form.get('code_verifier')), authChallenge);
                assert.equal(form.get('redirect_uri'), redirectUri);
                assert.equal(form.get('code'), 'test-code');
            } else {
                refreshes++;
                if (rejectRefresh) return json(400, { error: 'invalid_grant' });
                assert.equal(form.get('refresh_token'), 'refresh-' + (refreshes - 1));
            }
            return json(200, { token_type: 'Bearer', access_token: 'access-' + refreshes, refresh_token: 'refresh-' + refreshes, expires_in: 120 });
        }
        if (url.pathname === '/revoke') { revokes++; res.writeHead(200); return res.end(); }
        if (url.pathname === '/mcp') {
            if (!req.headers.authorization || (rejectAccess && req.headers.authorization === 'Bearer access-0')) {
                res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${origin}/.well-known/oauth-protected-resource/mcp", scope="read"`);
                return json(401, { error: 'unauthorized' });
            }
            assert.match(req.headers.authorization, /^Bearer access-\d$/);
            const msg = JSON.parse(raw);
            if (!('id' in msg)) { res.writeHead(202); return res.end(); }
            const result = msg.method === 'initialize' ? { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: echoToken ? req.headers.authorization + ' refresh-0' : 'oauth-live-fixture', version: '1' } } : msg.method === 'tools/list' ? { tools: [{ name: 'echo', inputSchema: { type: 'object' } }] } : { content: [{ type: 'text', text: 'AUTHENTICATED' }] };
            return json(200, { jsonrpc: '2.0', id: msg.id, result });
        }
        json(404, {});
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    origin = `http://127.0.0.1:${server.address().port}`;
    t.after(() => { server.closeAllConnections(); server.close(); fs.rmSync(dir, { recursive: true, force: true }); });
    const vault = new CredentialVault(dir);
    const oauth = new McpOAuth({ vault, allowLoopback: true, now: () => now });
    const cfg = { url: origin + '/mcp' };
    const login = async (id = 'demo') => {
        const start = await oauth.start(id, cfg);
        await fetch(start.authorizationUrl);
        await oauth.complete(id, cfg, { state: start.state, completionToken: start.completionToken, code: 'test-code' });
        return start;
    };
    return { oauth, vault, cfg, login, dir, origin, advance: ms => { now += ms; }, counts: () => ({ refreshes, revokes }), rejectRefresh: () => { rejectRefresh = true; }, rejectAccess: () => { rejectAccess = true; }, echoToken: () => { echoToken = true; }, metadata: v => { metadataOverride = v; }, prm: v => { prmOverride = v; } };
}

test('OAuth: actual discovery → registration → S256 exchange → live MCP → refresh once → revoke', async t => {
    const f = await fixture(t);
    const start = await f.login();
    assert.ok(!JSON.stringify(start).includes('verifier'));
    assert.ok(!JSON.stringify(start).includes('access-'));
    const manager = new McpClientManager({ clientOptions: { authorizeDescriptor: d => f.oauth.authorize(d) } });
    t.after(() => manager.closeAll());
    const descriptor = buildMcpDescriptor('demo', f.cfg);
    assert.equal((await manager.probe(descriptor)).ok, true);
    assert.equal((await manager.callTool('demo', 'echo', {})).ok, true);
    f.advance(100000);
    await Promise.all([1, 2, 3].map(() => manager.callTool('demo', 'echo', {}, { descriptor })));
    assert.equal(f.counts().refreshes, 1);
    assert.equal(f.oauth.isAuthorized('demo', f.cfg.url), true);
    const reloaded = new McpOAuth({ vault: new CredentialVault(f.dir), allowLoopback: true });
    assert.equal((await reloaded.authorize(descriptor)).authToken, 'access-1', 'encrypted credential survives process restart');
    const encrypted = fs.readdirSync(f.dir).filter(x => x.endsWith('.enc')).map(x => fs.readFileSync(path.join(f.dir, x))).join('');
    assert.ok(!encrypted.includes('access-1'));
    assert.equal((await f.oauth.authorize({ ...descriptor, id: 'other' })).authToken, null, 'tokens do not cross server identities');
    await f.oauth.revoke('demo', f.cfg);
    await manager.disconnect('demo');
    assert.equal(f.counts().revokes, 2);
    assert.equal(f.oauth.isAuthorized('demo', f.cfg.url), false);
    assert.notEqual((await manager.probe(descriptor)).ok, true);
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: start.state, code: 'test-code' }), /state/);
});

test('OAuth rejects invalid state, wrong resource, expired flow and user cancellation', async t => {
    const f = await fixture(t);
    const start = await f.oauth.start('demo', f.cfg);
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: 'wrong', code: 'x' }), /state/);
    await assert.rejects(f.oauth.complete('other', f.cfg, { state: start.state, code: 'x' }), /state/);
    await assert.rejects(f.oauth.complete('demo', { url: f.origin + '/other' }, { state: start.state, code: 'x' }), /state/);
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: start.state, code: 'x' }), /приложению/);
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: start.state, completionToken: start.completionToken, error: 'access_denied' }), /отменён/);
    assert.equal(f.oauth.isAuthorized('demo', f.cfg.url), false);
    const next = await f.oauth.start('demo', f.cfg);
    f.advance(600001);
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: next.state, code: 'x' }), /state/);
});

test('OAuth discovery validates resource, issuer, S256 and preregistration', async t => {
    const f = await fixture(t);
    f.prm({ resource: 'https://wrong.example/mcp' });
    await assert.rejects(f.oauth.start('demo', f.cfg), /ресурса/);
    f.prm({}); f.metadata({ issuer: 'https://wrong.example' });
    await assert.rejects(f.oauth.start('demo', f.cfg), /issuer/);
    f.metadata({ code_challenge_methods_supported: ['plain'] });
    await assert.rejects(f.oauth.start('demo', f.cfg), /S256/);
    f.metadata({ registration_endpoint: null });
    await assert.rejects(f.oauth.start('demo', f.cfg), /предварительную регистрацию/);
    const start = await f.oauth.start('demo', { ...f.cfg, oauth: { clientId: 'public-test' } });
    assert.equal(new URL(start.authorizationUrl).searchParams.get('client_id'), 'public-test');
});

test('OAuth invalid_grant clears credentials and requests a new login', async t => {
    const f = await fixture(t);
    await f.login(); f.advance(100000); f.rejectRefresh();
    await assert.rejects(f.oauth.authorize(buildMcpDescriptor('demo', f.cfg)), /HTTP 400/);
    assert.equal(f.oauth.isAuthorized('demo', f.cfg.url), false);
});

test('OAuth refreshes a rejected token once during handshake', async t => {
    const f = await fixture(t);
    await f.login(); f.rejectAccess();
    const manager = new McpClientManager({ clientOptions: {
        authorizeDescriptor: d => f.oauth.authorize(d), onAuthFailure: d => f.oauth.handleAuthFailure(d)
    } });
    t.after(() => manager.closeAll());
    assert.equal((await manager.probe(buildMcpDescriptor('demo', f.cfg))).ok, true);
    assert.equal(f.counts().refreshes, 1);
});

test('OAuth production rejects insecure/private metadata targets and embedded credentials', async () => {
    const oauth = new McpOAuth();
    for (const url of ['http://example.com', 'https://127.0.0.1', 'https://[::1]', 'https://169.254.169.254', 'https://10.0.0.1', 'https://user:pass@example.com', 'https://example.com/#fragment']) {
        await assert.rejects(oauth.safeUrl(url));
    }
    for (const redirectUri of ['com.google.antigravity:/mcp-oauth/callback', 'http://192.168.1.2:8080/mcp-oauth/callback', 'http://127.0.0.1:8080/evil', 'http://127.0.0.1:8080/mcp-oauth/callback?next=evil']) {
        await assert.rejects(oauth.start('demo', { url: 'https://example.com/mcp' }, { redirectUri }), /loopback/);
    }
    assert.equal(challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
});

test('SSE never forwards a Bearer token to a different endpoint origin', async t => {
    let leaked = false;
    const victim = http.createServer((_req, res) => { leaked = true; res.end(); });
    await new Promise(resolve => victim.listen(0, '127.0.0.1', resolve));
    const malicious = http.createServer((_req, res) => {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write(`event: endpoint\ndata: http://127.0.0.1:${victim.address().port}/steal\n\n`);
    });
    await new Promise(resolve => malicious.listen(0, '127.0.0.1', resolve));
    const manager = new McpClientManager();
    t.after(async () => {
        await manager.closeAll();
        for (const server of [malicious, victim]) { server.closeAllConnections(); server.close(); }
    });
    const result = await manager.probe(buildMcpDescriptor('sse', { type: 'sse', url: `http://127.0.0.1:${malicious.address().port}/sse`, authToken: 'must-not-leak' }), { handshakeTimeoutMs: 2000 });
    assert.equal(result.ok, false);
    assert.equal(result.errorCode, 'protocol_error');
    assert.equal(leaked, false);
});

test('bridge HTTP API: oauthStart → callback → oauthStatus → real MCP → oauthRevoke', async t => {
    let api;
    const bridge = http.createServer(async (req, res) => {
        const url = new URL(req.url, 'http://localhost');
        if (await api.callback(req, res, url)) return;
        let body = ''; for await (const part of req) body += part;
        const result = await api.action(JSON.parse(body));
        res.writeHead(result?.ok ? 200 : 400, { 'content-type': 'application/json' });
        res.end(JSON.stringify(result));
    });
    await new Promise(resolve => bridge.listen(0, '127.0.0.1', resolve));
    t.after(() => { bridge.closeAllConnections(); bridge.close(); });
    const origin = `http://127.0.0.1:${bridge.address().port}`;
    const redirectUri = origin + '/api/mcp/oauth/callback';
    const f = await fixture(t, { redirectUri });
    const invalidated = [];
    api = createMcpOAuthApi({ oauth: f.oauth, findConfig: id => id === 'demo' ? f.cfg : null,
        invalidate: async id => invalidated.push(id), bridgeRedirectUri: redirectUri });
    const post = async payload => (await fetch(origin + '/api/mcp', { method: 'POST', body: JSON.stringify({ name: 'demo', ...payload }) })).json();
    const start = await post({ action: 'oauthStart' });
    assert.equal(start.ok, true);
    assert.equal(start.authUrl, start.authorizationUrl);
    assert.equal(start.redirectUri, redirectUri);
    assert.ok(!JSON.stringify(start).includes('access-'));
    assert.equal((await post({ action: 'oauthStatus', sessionId: start.sessionId })).status, 'pending');
    assert.equal((await post({ action: 'oauthStatus', sessionId: 'unknown' })).status, 'expired');
    assert.equal((await fetch(redirectUri + '?state=wrong&code=must-not-echo')).status, 400);
    await fetch(start.authUrl);
    const duplicate = await fetch(redirectUri + `?state=${start.state}&state=${start.state}&code=test-code`);
    assert.equal(duplicate.status, 400);
    const result = await fetch(redirectUri + `?state=${start.state}&code=test-code`);
    assert.equal(result.status, 200);
    assert.equal(result.headers.get('cache-control'), 'no-store');
    const html = await result.text();
    for (const secret of ['test-code', start.state, start.sessionId, 'access-0', 'refresh-0']) assert.ok(!html.includes(secret));
    const status = await post({ action: 'oauthStatus', sessionId: start.sessionId });
    assert.equal(status.status, 'authorized'); assert.equal(status.authorized, true);
    assert.ok(!JSON.stringify(status).includes('access-'));
    assert.deepEqual(f.counts(), { refreshes: 0, revokes: 0 }, 'polling does not invoke refresh or tools');
    const manager = new McpClientManager({ clientOptions: { authorizeDescriptor: d => f.oauth.authorize(d) } });
    t.after(() => manager.closeAll());
    assert.equal((await manager.probe(buildMcpDescriptor('demo', f.cfg))).ok, true);
    assert.equal((await manager.callTool('demo', 'echo')).ok, true);
    const logout = await post({ action: 'oauthRevoke' });
    assert.equal(logout.ok, true); assert.equal(logout.authorized, false);
    assert.equal((await post({ action: 'oauthStatus', sessionId: start.sessionId })).status, 'revoked');
    assert.equal((await fetch(redirectUri + `?state=${start.state}&code=test-code`)).status, 400);
    assert.deepEqual(invalidated, ['demo', 'demo']);
});

test('OAuth API aliases, denial and error redaction preserve native compatibility', async t => {
    const f = await fixture(t);
    const api = createMcpOAuthApi({ oauth: f.oauth, findConfig: () => f.cfg,
        bridgeRedirectUri: 'http://127.0.0.1:8787/api/mcp/oauth/callback' });
    for (const action of ['oauthStart', 'oauthStatus', 'oauthRevoke', 'oauth_start', 'oauth_status', 'oauth_revoke']) assert.ok(normalizeOAuthAction(action));
    assert.equal(normalizeOAuthAction('add'), null);
    assert.equal(normalizeOAuthAction('constructor'), null);
    const start = await api.action({ action: 'oauth_start', name: 'demo' });
    const denied = await api.action({ action: 'oauth_complete', name: 'demo', state: start.state, completionToken: start.completionToken, error: 'access_denied secret-provider-error' });
    assert.equal(denied.ok, false);
    assert.ok(!JSON.stringify(denied).includes('secret-provider-error'));
    assert.equal((await api.action({ action: 'oauthStatus', name: 'demo', sessionId: start.sessionId })).status, 'denied');
    await f.login();
    assert.deepEqual(f.oauth.secretsFor('demo', f.cfg.url), ['access-0', 'refresh-0']);
    assert.ok(!f.oauth.publicError('demo', f.cfg.url).includes('access-0'));
});

test('replaced OAuth flow is superseded and old callback cannot complete', async t => {
    const f = await fixture(t);
    const first = await f.oauth.start('demo', f.cfg);
    const second = await f.oauth.start('demo', f.cfg);
    assert.equal(f.oauth.status('demo', f.cfg, first.sessionId).status, 'superseded');
    assert.equal(f.oauth.status('demo', f.cfg, second.sessionId).status, 'pending');
    await assert.rejects(f.oauth.complete('demo', f.cfg, { state: first.state, completionToken: first.completionToken, code: 'test-code' }), /state/);
});

test('MCP handshake metadata cannot echo an OAuth token into API responses', async t => {
    const f = await fixture(t);
    await f.login(); f.echoToken();
    const manager = new McpClientManager({ clientOptions: { authorizeDescriptor: d => f.oauth.authorize(d) } });
    t.after(() => manager.closeAll());
    const probe = await manager.probe(buildMcpDescriptor('demo', f.cfg));
    assert.equal(probe.ok, true);
    assert.ok(!JSON.stringify(probe).includes('access-0'));
    assert.ok(!JSON.stringify(probe).includes('refresh-0'));
    assert.ok(!JSON.stringify(manager.getLive('demo')).includes('access-0'));
});
