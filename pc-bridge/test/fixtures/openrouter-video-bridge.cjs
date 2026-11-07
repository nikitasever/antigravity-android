'use strict';
// Protocol fixture: real journal/API adapter, injected provider and a container-header byte fixture.
// No commercial API calls; these bytes are not a generated playable video.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { randomUUID } = require('node:crypto');
const { OpenRouterVideoJobs } = require('../../lib/openrouter-video-jobs');
const { createOpenRouterVideoApi } = require('../../lib/openrouter-video-api');
const directory = path.resolve('uploads/video-protocol-fixture');
fs.mkdirSync(directory, { recursive: true });
const counts = { posts: 0, polls: 0, downloads: 0, wrongBridgeCalls: 0, textRequests: 0 };
const recoveryMode = process.argv.includes('--recovery');
let forceComplete = false;
const seen = new Map();
const jobs = new OpenRouterVideoJobs({ file: path.join(directory, 'jobs.json'), mediaDirectory: directory,
    capabilities: async model => model === 'qa/video-model' ? { supported_durations: [4] } : null,
    request: async (url, options) => {
        if (options.method === 'POST') { counts.posts++; return { status: 202, json: { id: randomUUID(), status: forceComplete ? 'completed' : 'pending' } }; }
        counts.polls++;
        const id = decodeURIComponent(url.split('/').pop());
        const polls = (seen.get(id) || 0) + 1; seen.set(id, polls);
        return { status: 200, json: { id, status: recoveryMode ? (forceComplete ? 'completed' : 'in_progress') : polls === 1 ? 'in_progress' : 'completed' } };
    },
    download: async (url, headers, file) => {
        counts.downloads++;
        const bytes = Buffer.alloc(24); bytes.writeUInt32BE(24, 0); bytes.write('ftypisom', 4); fs.writeFileSync(file, bytes);
    }
});
const adapter = createOpenRouterVideoApi({ jobs: () => jobs, keyFor: payload => payload.apiKey || 'qa-video-key' });
http.createServer(async (request, response) => {
    try {
        const url = new URL(request.url, 'http://127.0.0.1:8814');
        if (request.method === 'GET' && url.pathname === '/qa/state') {
            response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(counts)); return;
        }
        if (request.method === 'POST' && url.pathname === '/qa/complete') {
            forceComplete = true; response.writeHead(200); response.end('{"ok":true}'); return;
        }
        if (request.method === 'POST' && url.pathname === '/qa/pending') {
            forceComplete = false; response.writeHead(200); response.end('{"ok":true}'); return;
        }
        if (request.method === 'POST' && url.pathname === '/api/chat/stream') counts.textRequests++;
        if (request.method === 'GET' && ['/qa/media-good', '/qa/media-truncated'].includes(url.pathname)) {
            const bytes = Buffer.alloc(24); bytes.writeUInt32BE(24, 0); bytes.write('ftypisom', 4);
            response.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': url.pathname.endsWith('truncated') ? 100 : bytes.length,
                Connection: 'close' }); response.end(bytes); return;
        }
        if (request.method === 'GET' && url.pathname.startsWith('/download/media/')) {
            const fileName = decodeURIComponent(url.pathname.slice('/download/media/'.length));
            if (path.basename(fileName) !== fileName || !/^openrouter_video_(qa_android_|media_gen_)[a-zA-Z0-9_-]+\.mp4$/.test(fileName)) throw Error('Unexpected fixture file');
            const bytes = fs.readFileSync(path.join(directory, fileName));
            response.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': bytes.length }); response.end(bytes); return;
        }
        if (request.method === 'GET') {
            response.writeHead(200, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ ok: true, hostname: 'QA video', models: [], sessions: [], agents: [], plugins: [], skills: [], servers: [], connections: [] })); return;
        }
        let body = ''; for await (const chunk of request) { body += chunk; if (body.length > 10000) throw Error('Fixture body too large'); }
        const action = { '/api/openrouter/generate-video': 'start', '/api/openrouter/video-status': 'status', '/api/openrouter/video-download': 'download' }[url.pathname];
        if (!action || request.method !== 'POST') { response.writeHead(404); response.end(); return; }
        const result = await adapter(action, JSON.parse(body));
        response.writeHead(result.status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(result.body));
    } catch (_) { response.writeHead(500); response.end('{"ok":false,"error":"QA fixture failed"}'); }
}).listen(8814, '127.0.0.1');
if (recoveryMode) http.createServer((request, response) => {
    if (request.method === 'POST' && /\/api\/openrouter\/(generate-video|video-status|video-download)/.test(request.url)) {
        counts.wrongBridgeCalls++; response.writeHead(400); response.end('{"ok":false,"error":"Wrong QA bridge"}'); return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ ok: true, hostname: 'QA other bridge', models: [], sessions: [], agents: [], plugins: [], skills: [], servers: [], connections: [] }));
}).listen(8815, '127.0.0.1');
