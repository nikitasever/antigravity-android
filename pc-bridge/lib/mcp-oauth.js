'use strict';
// MCP HTTP authorization: RFC 9728 discovery, RFC 8414/OIDC metadata,
// authorization code + S256, rotating refresh tokens and RFC 7009 revocation.
// No access/refresh tokens or PKCE verifiers are returned to Android/manifests.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const dns = require('node:dns').promises;
const net = require('node:net');
const http = require('node:http');
const https = require('node:https');
const { Readable } = require('node:stream');
const { redactSecrets } = require('./mcp-client');
const REDIRECT_URI = 'http://127.0.0.1:49152/mcp-oauth/callback';
const BRIDGE_CALLBACK_PATH = '/api/mcp/oauth/callback';
const random = () => crypto.randomBytes(32).toString('base64url');
const challenge = verifier => crypto.createHash('sha256').update(verifier).digest('base64url');

function publicAddress(ip) {
    if (net.isIP(ip) === 4) {
        const [a, b] = ip.split('.').map(Number);
        return !(a === 0 || a === 10 || a === 127 || a >= 224 ||
            (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
            (a === 192 && (b === 168 || b === 0)) || (a === 100 && b >= 64 && b <= 127) || (a === 198 && (b === 18 || b === 19)));
    }
    // Only global unicast IPv6; mapped IPv4, local and link-local are rejected.
    return net.isIP(ip) === 6 && /^[23][0-9a-f]{3}:/i.test(ip);
}

class CredentialVault {
    constructor(dir) { this.dir = dir; }
    key() {
        fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
        const file = path.join(this.dir, 'key');
        try { fs.writeFileSync(file, crypto.randomBytes(32), { flag: 'wx', mode: 0o600 }); }
        catch (e) { if (e.code !== 'EEXIST') throw e; }
        return fs.readFileSync(file);
    }
    file(id) { return path.join(this.dir, crypto.createHash('sha256').update(id).digest('hex') + '.enc'); }
    get(id) {
        const file = this.file(id);
        if (!fs.existsSync(file)) return null;
        const bytes = fs.readFileSync(file);
        const decipher = crypto.createDecipheriv('aes-256-gcm', this.key(), bytes.subarray(0, 12));
        decipher.setAAD(Buffer.from(id));
        decipher.setAuthTag(bytes.subarray(12, 28));
        return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString());
    }
    set(id, value) {
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', this.key(), iv);
        cipher.setAAD(Buffer.from(id));
        const bytes = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final()]);
        const file = this.file(id), tmp = file + '.' + random() + '.tmp';
        try {
            fs.writeFileSync(tmp, Buffer.concat([iv, cipher.getAuthTag(), bytes]), { mode: 0o600 });
            fs.renameSync(tmp, file);
        } finally { fs.rmSync(tmp, { force: true }); }
    }
    delete(id) { fs.rmSync(this.file(id), { force: true }); }
}

class McpOAuth {
    constructor({ vault, fetchImpl, allowLoopback = false, now = Date.now } = {}) {
        this.vault = vault; this.fetch = fetchImpl || ((url, options) => this.pinnedFetch(url, options)); this.allowLoopback = allowLoopback; this.now = now;
        this.pending = new Map(); this.sessions = new Map(); this.refreshes = new Map(); this.epochs = new Map();
    }
    async safeUrl(raw) {
        const u = new URL(raw);
        const host = u.hostname.replace(/^\[|\]$/g, '');
        const local = ['127.0.0.1', '::1', 'localhost'].includes(host);
        if (u.username || u.password || u.hash || (u.protocol !== 'https:' && !(this.allowLoopback && local && u.protocol === 'http:'))) throw new Error('OAuth требует HTTPS URL без userinfo/fragment');
        const addresses = net.isIP(host) ? [{ address: host }] : await dns.lookup(host, { all: true });
        if (!addresses.length || addresses.some(a => !publicAddress(a.address) && !(this.allowLoopback && local))) throw new Error('OAuth: адрес внутренней сети запрещён');
        return u;
    }
    async pinnedFetch(raw, options) {
        const url = await this.safeUrl(raw);
        const host = url.hostname.replace(/^\[|\]$/g, '');
        const addresses = net.isIP(host) ? [{ address: host, family: net.isIP(host) }] : await dns.lookup(host, { all: true });
        const local = ['127.0.0.1', '::1', 'localhost'].includes(host);
        if (!addresses.length || addresses.some(a => !publicAddress(a.address) && !(this.allowLoopback && local))) throw new Error('OAuth: адрес внутренней сети запрещён');
        // Pin the checked address in the TLS request to prevent DNS rebinding
        // between metadata validation and the actual token exchange.
        const selected = addresses[0];
        return new Promise((resolve, reject) => {
            const req = (url.protocol === 'https:' ? https : http).request(url, {
                method: options.method || 'GET', headers: options.headers, signal: options.signal,
                lookup: (_host, opts, callback) => opts.all
                    ? callback(null, [selected]) : callback(null, selected.address, selected.family),
                maxHeaderSize: 16384
            }, res => {
                const headers = new Headers();
                for (const [key, value] of Object.entries(res.headers)) if (value != null) headers.set(key, Array.isArray(value) ? value.join(', ') : value);
                if (res.statusCode >= 300 && res.statusCode < 400) {
                    res.destroy(); reject(new Error('OAuth: перенаправление HTTP запрещено')); return;
                }
                resolve(new Response([204, 205, 304].includes(res.statusCode) ? null : Readable.toWeb(res), { status: res.statusCode, headers }));
            });
            req.on('error', reject);
            req.end(options.body);
        });
    }
    async request(raw, options = {}) {
        const url = await this.safeUrl(raw);
        const response = await this.fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(15000) });
        // Bound the streamed body before JSON parsing, including error responses.
        let text = '';
        if (response.body) for await (const chunk of response.body) {
            text += Buffer.from(chunk).toString();
            if (Buffer.byteLength(text) > 256 * 1024) throw new Error('OAuth: слишком большой ответ');
        }
        let json; try { json = text ? JSON.parse(text) : {}; } catch { throw new Error('OAuth: некорректный JSON'); }
        if (!response.ok) {
            const e = new Error(`OAuth: HTTP ${response.status}`);
            e.invalidGrant = json.error === 'invalid_grant'; throw e;
        }
        return json;
    }
    form(url, fields) {
        return this.request(url, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(fields).toString() });
    }
    async discover(resource) {
        const u = await this.safeUrl(resource);
        const probe = await this.fetch(u, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'antigravity', version: '1' } } }), redirect: 'error', signal: AbortSignal.timeout(15000) });
        const auth = probe.headers.get('www-authenticate') || '';
        await probe.body?.cancel();
        const advertised = /resource_metadata="([^"]+)"/i.exec(auth)?.[1];
        const candidates = advertised ? [advertised] : [u.origin + '/.well-known/oauth-protected-resource' + u.pathname.replace(/\/$/, ''), u.origin + '/.well-known/oauth-protected-resource'];
        let prm;
        for (const candidate of [...new Set(candidates)]) {
            try { prm = await this.request(candidate); break; } catch (e) { if (advertised) throw e; }
        }
        if (!prm || prm.resource !== resource || !Array.isArray(prm.authorization_servers) || !prm.authorization_servers.length) throw new Error('OAuth: нет корректных метаданных защищённого ресурса (RFC 9728)');
        const issuer = (await this.safeUrl(prm.authorization_servers[0])).href.replace(/\/$/, '');
        const as = new URL(issuer), suffix = as.pathname.replace(/\/$/, '');
        const locations = [as.origin + '/.well-known/oauth-authorization-server' + suffix, as.origin + '/.well-known/openid-configuration' + suffix, issuer + '/.well-known/openid-configuration'];
        let metadata;
        for (const location of [...new Set(locations)]) {
            try { metadata = await this.request(location); break; } catch { /* try next RFC discovery location */ }
        }
        if (!metadata || String(metadata.issuer).replace(/\/$/, '') !== issuer) throw new Error('OAuth: issuer метаданных не совпадает');
        if (!metadata.code_challenge_methods_supported?.includes('S256')) throw new Error('OAuth: сервер не объявил PKCE S256');
        for (const key of ['authorization_endpoint', 'token_endpoint']) await this.safeUrl(metadata[key]);
        return { metadata, scope: /scope="([^"]*)"/i.exec(auth)?.[1] || (prm.scopes_supported || []).join(' ') };
    }
    binding(id, resource) { return JSON.stringify([id, resource]); }
    isAuthorized(id, url) {
        try { return Boolean(this.vault.get(this.binding(id, new URL(url).href))); }
        catch { return false; }
    }
    secretsFor(id, url) {
        try {
            const tokens = this.vault.get(this.binding(id, new URL(url).href))?.tokens || {};
            return [tokens.access_token, tokens.refresh_token].filter(v => typeof v === 'string');
        } catch { return []; }
    }
    publicError(id, url) {
        // Never expose exception messages from fetch, metadata or the provider.
        return redactSecrets('OAuth: авторизация не выполнена. Проверьте настройки подключения и повторите вход.', this.secretsFor(id, url));
    }
    status(id, cfg, sessionId) {
        const resource = new URL(cfg.url).href;
        const session = sessionId ? this.sessions.get(sessionId) : null;
        const saved = this.vault.get(this.binding(id, resource));
        const authorized = Boolean(saved?.tokens && (saved.tokens.refresh_token || saved.tokens.expiresAt > this.now()));
        if (sessionId && (!session || session.id !== id || session.resource !== resource)) return { status: 'expired', authorized, errorCode: 'unknown_session' };
        const status = session && session.expires <= this.now() ? 'expired' : session?.status || (authorized ? 'authorized' : 'not_authorized');
        return { status, authorized, ...(session ? { expiresAt: session.expires } : {}) };
    }
    handleAuthFailure(descriptor) {
        if (!descriptor?.oauthManaged || !descriptor.url) return false;
        const key = this.binding(descriptor.id, new URL(descriptor.url).href), saved = this.vault.get(key);
        if (!saved) return false;
        if (!saved.tokens.refresh_token) { this.vault.delete(key); return false; }
        saved.tokens.expiresAt = 0;
        this.vault.set(key, saved);
        return true;
    }
    async start(id, cfg, { redirectUri = REDIRECT_URI, callbackMode = 'native' } = {}) {
        const epoch = this.epochs.get(id) || 0;
        const redirect = new URL(redirectUri);
        const bridge = callbackMode === 'bridge';
        const local = redirect.protocol === 'http:' && redirect.hostname === '127.0.0.1' && Boolean(redirect.port);
        if ((!local && !(bridge && redirect.protocol === 'https:')) ||
            redirect.username || redirect.password || redirect.pathname !== (bridge ? BRIDGE_CALLBACK_PATH : '/mcp-oauth/callback') || redirect.search || redirect.hash) throw new Error('OAuth: требуется корректный loopback/HTTPS callback URI');
        for (const [key, session] of this.sessions) if (session.expires <= this.now()) this.sessions.delete(key);
        if (this.sessions.size >= 64) throw new Error('OAuth: слишком много сессий входа');
        if (!cfg.url || cfg.disabled) throw new Error('Для входа требуется включённое HTTP MCP-подключение');
        const resource = new URL(cfg.url).href;
        const { metadata, scope } = await this.discover(resource);
        let clientId = cfg.oauth?.clientId;
        if (!clientId) {
            if (!metadata.registration_endpoint) throw new Error('Поставщик требует предварительную регистрацию public client с loopback redirect URI; укажите oauth.clientId в конфигурации MCP.');
            const registration = await this.request(metadata.registration_endpoint, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ client_name: 'Antigravity Android', redirect_uris: [redirectUri], grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'], token_endpoint_auth_method: 'none' }) });
            clientId = registration.client_id;
            if (typeof clientId !== 'string' || !clientId || (registration.token_endpoint_auth_method && registration.token_endpoint_auth_method !== 'none')) throw new Error('OAuth: сервер не зарегистрировал public client');
        }
        for (const [s, p] of this.pending) if (p.expires <= this.now() || p.id === id) {
            const previous = this.sessions.get(p.completionToken);
            if (previous) previous.status = p.expires <= this.now() ? 'expired' : 'superseded';
            this.pending.delete(s);
        }
        if (this.pending.size >= 32) throw new Error('OAuth: слишком много ожидающих входов');
        if ((this.epochs.get(id) || 0) !== epoch) throw new Error('OAuth: вход отменён');
        const state = random(), verifier = random(), completionToken = random();
        const expires = this.now() + 10 * 60 * 1000;
        this.pending.set(state, { id, resource, metadata, clientId, verifier, completionToken, redirectUri, callbackMode, expires });
        this.sessions.set(completionToken, { id, resource, status: 'pending', expires });
        const url = new URL(metadata.authorization_endpoint);
        for (const [key, value] of Object.entries({ response_type: 'code', client_id: clientId, redirect_uri: redirectUri, state, code_challenge: challenge(verifier), code_challenge_method: 'S256', resource, ...(scope ? { scope } : {}) })) url.searchParams.set(key, value);
        return { authUrl: url.href, authorizationUrl: url.href, state, completionToken, sessionId: completionToken, redirectUri };
    }
    async complete(id, cfg, { state, code, error, completionToken }) {
        const p = this.pending.get(state);
        if (!p || p.id !== id || p.resource !== new URL(cfg.url).href || p.expires <= this.now()) throw new Error('OAuth: неверный или истёкший state');
        if (typeof completionToken !== 'string' || completionToken.length !== p.completionToken.length ||
            !crypto.timingSafeEqual(Buffer.from(completionToken), Buffer.from(p.completionToken))) throw new Error('OAuth: ответ не принадлежит этому приложению');
        this.pending.delete(state); // single use, including denial and failed exchange
        const session = this.sessions.get(p.completionToken);
        if (error || !code || String(code).length > 4096) {
            if (session) session.status = error ? 'denied' : 'failed';
            throw new Error('OAuth: вход отменён или код отсутствует');
        }
        const epoch = this.epochs.get(id) || 0;
        try {
            const tokens = await this.form(p.metadata.token_endpoint, { grant_type: 'authorization_code', code, client_id: p.clientId, redirect_uri: p.redirectUri, code_verifier: p.verifier, resource: p.resource });
            if ((this.epochs.get(id) || 0) !== epoch) throw new Error('OAuth: вход отменён');
            this.save(p, tokens);
            if (session) session.status = 'authorized';
        } catch (e) { if (session && session.status !== 'revoked') session.status = 'failed'; throw e; }
    }
    async completeBridgeCallback(params, findConfig) {
        for (const key of ['state', 'code', 'error']) if (params.getAll(key).length > 1) throw new Error('OAuth: повторяющийся параметр callback');
        const state = params.get('state'), p = this.pending.get(state);
        if (!p || p.callbackMode !== 'bridge' || p.expires <= this.now()) throw new Error('OAuth: неверный или истёкший callback');
        const cfg = findConfig(p.id);
        if (!cfg || cfg.disabled || !cfg.url) throw new Error('OAuth: подключение удалено или выключено');
        await this.complete(p.id, cfg, { state, code: params.get('code'), error: params.get('error'), completionToken: p.completionToken });
        return { id: p.id, status: 'authorized' };
    }
    save(p, tokens, old = {}) {
        if (typeof tokens.access_token !== 'string' || !tokens.access_token || tokens.access_token.length > 16384 ||
            (tokens.refresh_token != null && typeof tokens.refresh_token !== 'string') ||
            String(tokens.token_type).toLowerCase() !== 'bearer' || /[\r\n]/.test(tokens.access_token)) throw new Error('OAuth: сервер вернул некорректный Bearer-токен');
        const ttl = Number(tokens.expires_in);
        this.vault.set(this.binding(p.id, p.resource), { ...p, verifier: undefined, completionToken: undefined, expires: undefined, tokens: { access_token: tokens.access_token, refresh_token: tokens.refresh_token || old.refresh_token, expiresAt: this.now() + (Number.isFinite(ttl) && ttl > 0 ? ttl : 3600) * 1000 } });
    }
    async authorize(descriptor) {
        if (!descriptor.url) return descriptor;
        const resource = new URL(descriptor.url).href, key = this.binding(descriptor.id, resource);
        let saved = this.vault.get(key);
        if (!saved) {
            if (!descriptor.oauthManaged) return descriptor;
            const { authToken, oauthManaged, oauthSecrets, ...rest } = descriptor;
            return rest;
        }
        if (saved.tokens.expiresAt <= this.now() + 30000) {
            if (!saved.tokens.refresh_token) { this.vault.delete(key); throw new Error('OAuth: срок входа истёк, войдите повторно'); }
            if (!this.refreshes.has(key)) {
                const epoch = this.epochs.get(descriptor.id) || 0;
                const run = (async () => {
                    try {
                        const tokens = await this.form(saved.metadata.token_endpoint, { grant_type: 'refresh_token', refresh_token: saved.tokens.refresh_token, client_id: saved.clientId, resource });
                        if ((this.epochs.get(descriptor.id) || 0) !== epoch) throw new Error('OAuth: вход отозван');
                        this.save(saved, tokens, saved.tokens);
                    } catch (e) { if (e.invalidGrant) this.vault.delete(key); throw e; }
                })();
                this.refreshes.set(key, run);
            }
            const run = this.refreshes.get(key);
            try { await run; } finally { if (this.refreshes.get(key) === run) this.refreshes.delete(key); }
            saved = this.vault.get(key);
        }
        const headers = Object.fromEntries(Object.entries(descriptor.headers || {}).filter(([k]) => k.toLowerCase() !== 'authorization'));
        return { ...descriptor, headers, authToken: saved.tokens.access_token, oauthManaged: true,
            // Redaction context only, never sent as an MCP transport field.
            oauthSecrets: [saved.tokens.access_token, saved.tokens.refresh_token].filter(Boolean) };
    }
    async revoke(id, cfg) {
        this.epochs.set(id, (this.epochs.get(id) || 0) + 1);
        for (const [s, p] of this.pending) if (p.id === id) this.pending.delete(s);
        for (const session of this.sessions.values()) if (session.id === id) session.status = 'revoked';
        const key = this.binding(id, new URL(cfg.url).href), saved = this.vault.get(key);
        this.vault.delete(key); // local logout always succeeds even if provider is offline
        if (saved?.metadata.revocation_endpoint) {
            for (const token of [saved.tokens.refresh_token, saved.tokens.access_token].filter(Boolean)) await this.form(saved.metadata.revocation_endpoint, { token, client_id: saved.clientId });
        }
    }
}
module.exports = { McpOAuth, CredentialVault, REDIRECT_URI, BRIDGE_CALLBACK_PATH, challenge, publicAddress };
