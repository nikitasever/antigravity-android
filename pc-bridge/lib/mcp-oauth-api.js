'use strict';
const { BRIDGE_CALLBACK_PATH } = require('./mcp-oauth');
const ACTIONS = {
    __proto__: null,
    oauthstart: 'oauth_start', oauthstatus: 'oauth_status', oauthrevoke: 'oauth_revoke',
    oauth_start: 'oauth_start', oauth_status: 'oauth_status', oauth_revoke: 'oauth_revoke', oauth_complete: 'oauth_complete'
};
function normalizeOAuthAction(action) { return ACTIONS[String(action || '').toLowerCase()] || null; }

function createMcpOAuthApi({ oauth, findConfig, invalidate = async () => {}, probe = async () => ({ ok: true }), bridgeRedirectUri }) {
    async function action(payload) {
        const operation = normalizeOAuthAction(payload.action);
        if (!operation) return null;
        const id = String(payload.name || payload.id || '').trim();
        const cfg = findConfig(id);
        if (!id || !cfg?.url) return { ok: false, errorCode: 'invalid_descriptor', error: 'OAuth доступен только для настроенного HTTP MCP-сервера' };
        try {
            if (operation === 'oauth_status') return { ok: true, id, ...oauth.status(id, cfg, payload.sessionId) };
            if (operation === 'oauth_start') {
                const native = String(payload.action).toLowerCase() === 'oauth_start' && payload.callbackMode !== 'bridge';
                const callbackMode = native ? 'native' : 'bridge';
                // Never construct a redirect from an untrusted Host header or
                // arbitrary body URL. HTTPS forwarding is explicit private config.
                const redirectUri = native ? payload.redirectUri : (cfg.oauth?.bridgeRedirectUri || bridgeRedirectUri);
                return { ok: true, id, ...await oauth.start(id, cfg, { redirectUri, callbackMode }) };
            }
            if (operation === 'oauth_revoke') {
                try { await oauth.revoke(id, cfg); }
                finally { await invalidate(id); }
                return { ok: true, id, ...oauth.status(id, cfg) };
            }
            await oauth.complete(id, cfg, payload);
            return probe(id);
        } catch { return { ok: false, errorCode: 'auth_failed', error: oauth.publicError(id, cfg.url) }; }
    }
    async function callback(req, res, url) {
        if (url.pathname !== BRIDGE_CALLBACK_PATH) return false;
        let success = false;
        if (req.method === 'GET') {
            try {
                const result = await oauth.completeBridgeCallback(url.searchParams, findConfig);
                await invalidate(result.id);
                success = true;
            } catch { /* no provider text, authorization code or token in response/log */ }
        }
        res.writeHead(success ? 200 : 400, {
            'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store',
            'referrer-policy': 'no-referrer', 'x-content-type-options': 'nosniff',
            'content-security-policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        });
        res.end(success
            ? '<!doctype html><html lang="ru"><meta charset="utf-8"><title>Antigravity</title><p>Вход выполнен. Можно вернуться в Antigravity.</p></html>'
            : '<!doctype html><html lang="ru"><meta charset="utf-8"><title>Antigravity</title><p>Не удалось завершить вход. Начните авторизацию заново в Antigravity.</p></html>');
        return true;
    }
    return { action, callback };
}
module.exports = { createMcpOAuthApi, normalizeOAuthAction };
