'use strict';
const { readJsonBody } = require('./read-json-body');

/** Runs before every private handler, including static files, TTS, SSE and the web console. */
function createBridgeHttpAccess(access) {
    const reply = (res, code, value) => {
        res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify(value)); return true;
    };
    const originFor = req => {
        const scheme = req.socket.encrypted || req.headers['x-forwarded-proto'] === 'https' ? 'https' : 'http';
        try { return new URL(`${scheme}://${req.headers.host}`).origin; } catch (_) { return ''; }
    };
    return async function guard(req, res, url) {
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('Referrer-Policy', 'no-referrer');
        res.setHeader('X-Frame-Options', 'DENY');
        res.setHeader('X-Content-Type-Options', 'nosniff');
        if (req.method === 'OPTIONS') {
            res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type, X-Antigravity-Bridge-Token' }); res.end(); return true;
        }
        if (req.method === 'GET' && url.pathname === '/api/health')
            return reply(res, 200, { ok: true, status: 'online', requiresPairing: true });
        if (req.method === 'POST' && url.pathname === '/api/pair') {
            if (req.headers.origin || !/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || ''))
                return reply(res, 403, { ok: false, error: 'Pair from the Android application' });
            try {
                const data = await readJsonBody(req, 1024);
                const paired = access.pair(data.code);
                return paired ? reply(res, 200, { ok: true, ...paired }) : reply(res, 403, { ok: false, error: 'Pairing code invalid or expired' });
            } catch (error) { return reply(res, error.statusCode || 400, { ok: false, error: 'Invalid pairing request' }); }
        }
        // These callbacks validate and consume their own unpredictable OAuth state.
        if (req.method === 'GET' && ['/api/mcp/oauth/callback', '/api/gdrive/oauth/callback'].includes(url.pathname)) return false;
        if (req.method === 'GET' && (url.pathname === '/web' || url.pathname === '/') && url.searchParams.has('ticket')) {
            const session = access.consumeWebTicket(url.searchParams.get('ticket'));
            if (!session) return reply(res, 403, { ok: false, error: 'Browser ticket expired or already used' });
            url.searchParams.delete('ticket');
            res.writeHead(303, { Location: url.pathname + url.search,
                'Set-Cookie': `agy_owner=${session}; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600${originFor(req).startsWith('https:') ? '; Secure' : ''}` });
            res.end(); return true;
        }
        const owner = access.authorize(req.headers['x-antigravity-bridge-token']);
        const cookie = (req.headers.cookie || '').split(';').map(x => x.trim()).find(x => x.startsWith('agy_owner='))?.slice(10);
        const web = access.authorizeWebSession(cookie);
        if (!owner && !web) return reply(res, 401, { ok: false, error: 'Pair this device with PC-Bridge', code: 'bridge_pairing_required' });
        if (!owner && web) {
            const origin = originFor(req);
            const site = req.headers['sec-fetch-site'];
            if ((req.headers.origin && req.headers.origin !== origin) || (site && !['same-origin', 'none'].includes(site)) ||
                (!['GET', 'HEAD'].includes(req.method) && req.headers.origin !== origin))
                return reply(res, 403, { ok: false, error: 'Cross-origin browser request rejected' });
        }
        if (req.method === 'POST' && url.pathname === '/api/pairing/open') {
            if (!owner) return reply(res, 403, { ok: false, error: 'Owner token required' });
            return reply(res, 200, { ok: true, ...access.openPairing() });
        }
        if (req.method === 'POST' && url.pathname === '/api/web-ticket') {
            if (!owner) return reply(res, 403, { ok: false, error: 'Owner token required' });
            try { return reply(res, 200, { ok: true, ticket: access.createWebTicket() }); }
            catch (_) { return reply(res, 429, { ok: false, error: 'Too many browser sessions' }); }
        }
        return false;
    };
}
module.exports = { createBridgeHttpAccess };
