const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { OpenRouterVideoJobs } = require('../lib/openrouter-video-jobs');
const { createOpenRouterVideoApi } = require('../lib/openrouter-video-api');

test('real HTTP adapter start/status/content and retry reuse the persisted provider job', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'or-video-api-'));
    let server;
    try {
        let posts = 0;
        const options = { file: path.join(dir, 'jobs.json'), mediaDirectory: dir,
            capabilities: async () => ({ supported_durations: [4] }),
            request: async (url, request) => request.method === 'POST' ?
                (posts++, { status: 202, json: { id: 'provider_A', status: 'pending' } }) :
                { status: 200, json: { id: 'provider_A', status: 'completed' } },
            download: async (url, headers, file) => { const bytes = Buffer.alloc(24); bytes.writeUInt32BE(24,0); bytes.write('ftypisom',4); fs.writeFileSync(file,bytes); }
        };
        let jobs = new OpenRouterVideoJobs(options);
        const adapter = createOpenRouterVideoApi({ jobs: () => jobs, keyFor: payload => payload.apiKey });
        server = http.createServer(async (request, response) => {
            let body = ''; for await (const chunk of request) body += chunk;
            const action = { '/start': 'start', '/status': 'status', '/content': 'download' }[request.url];
            const result = await adapter(action, JSON.parse(body));
            response.writeHead(result.status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(result.body));
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const base = `http://127.0.0.1:${server.address().port}`;
        const payload = { requestId: 'request_A', model: 'qa/video', prompt: 'QA', apiKey: 'qa-key' };
        const call = async route => { const response = await fetch(base+route, { method: 'POST', body: JSON.stringify(payload) }); return { status: response.status, body: await response.json() }; };
        assert.equal((await call('/start')).status, 202);
        jobs = new OpenRouterVideoJobs(options);
        assert.equal((await call('/start')).body.job.id, 'provider_A');
        assert.equal((await call('/status')).body.job.status, 'completed');
        assert.equal((await call('/content')).body.media.mimeType, 'video/mp4');
        assert.equal(posts, 1);
    } finally {
        if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
        assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.ok(path.basename(dir).startsWith('or-video-api-'));
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
test('API adapter preserves credit errors and never returns a configured key', async () => {
    const handle = createOpenRouterVideoApi({ keyFor: () => 'qa-secret', jobs: () => ({
        start: async () => ({ status: 'failed', httpStatus: 402, requestId: 'r', error: 'No credits' })
    }) });
    const result = await handle('start', {});
    assert.equal(result.status, 402); assert.equal(result.body.ok, false); assert.equal(result.body.job.requestId, 'r');
    const broken = createOpenRouterVideoApi({ keyFor: () => 'qa-secret', jobs: () => { throw Error('qa-secret'); } });
    assert.ok(!JSON.stringify(await broken('start', {})).includes('qa-secret'));
});
