'use strict';
// Canonical API protocol fixture. No production config, API key or commercial provider.
const http = require('node:http');
let complete = false;
const state = { posts: 0, polls: 0, downloads: 0, bridgePosts: 0, bridgePaths: [], forbiddenBridgePosts: 0,
    bridgeReceivedCredential: false, cdnReceivedAuthorization: false };
const jobs = new Set();
// Owned-fixture cleanup only: restore a previously observed fake provider ID after a fixture restart.
const restoreIndex = process.argv.indexOf('--restore-qa-job');
if (restoreIndex >= 0) {
    const id = process.argv[restoreIndex + 1];
    if (!/^qa_provider_[1-9]\d{0,3}$/.test(id || '')) throw Error('Invalid controlled provider ID');
    jobs.add(id); state.posts = Number(id.substring('qa_provider_'.length));
}
const send = (res, data, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
http.createServer(async (req, res) => {
    try {
        const url = new URL(req.url, 'http://127.0.0.1:8816');
        if (url.pathname === '/qa/state') return send(res, state);
        if (url.pathname === '/qa/complete') { complete = true; return send(res, { ok: true }); }
        if (url.pathname === '/cdn/video.mp4') {
            if (req.headers.authorization) state.cdnReceivedAuthorization = true;
            const bytes = Buffer.alloc(24); bytes.writeUInt32BE(24); bytes.write('ftypisom', 4);
            res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': bytes.length }); return res.end(bytes);
        }
        if (req.headers.authorization !== 'Bearer qa-direct-video-key') return send(res, { error: { message: 'Wrong controlled credential' } }, 401);
        if (req.method === 'GET' && url.pathname === '/api/v1/videos/models') return send(res, { data: [{ id: 'qa/direct-video', supported_durations: [8, 4] }] });
        if (req.method === 'POST' && url.pathname === '/api/v1/videos') {
            let raw = ''; for await (const part of req) { raw += part; if (raw.length > 20000) throw Error('Body too large'); }
            const body = JSON.parse(raw);
            if (body.model !== 'qa/direct-video' || body.duration !== 4 || !body.prompt) return send(res, { error: { message: 'Wrong model or duration' } }, 400);
            const id = `qa_provider_${++state.posts}`; jobs.add(id); return send(res, { id, status: complete ? 'completed' : 'pending' }, 202);
        }
        const match = url.pathname.match(/^\/api\/v1\/videos\/(qa_provider_\d+)(\/content)?$/);
        if (match && jobs.has(match[1])) {
            if (match[2]) {
                state.downloads++; res.writeHead(302, { Location: 'https://cdn.qa.example/cdn/video.mp4?token=ephemeral' }); return res.end();
            }
            state.polls++; return send(res, { id: match[1], status: complete ? 'completed' : 'in_progress' });
        }
        send(res, { error: { message: 'Unknown controlled job' } }, 404);
    } catch (_) { send(res, { error: { message: 'Controlled API fixture failed' } }, 500); }
}).listen(8816, '127.0.0.1');
http.createServer(async (req, res) => {
    if (req.method !== 'GET') {
        state.bridgePosts++; state.bridgePaths.push(req.url.split('?')[0]);
        if (!req.url.startsWith('/api/sync/state')) state.forbiddenBridgePosts++;
        let body = ''; for await (const part of req) body += part;
        if (body.includes('qa-direct-video-key') || String(req.headers.authorization || '').includes('qa-direct-video-key')) state.bridgeReceivedCredential = true;
        return send(res, { ok: false, error: 'PC write/sync deliberately unavailable' }, 503);
    }
    send(res, { ok: true, models: [], sessions: [], agents: [], servers: [], plugins: [], skills: [], connections: [] });
}).listen(8817, '127.0.0.1');
