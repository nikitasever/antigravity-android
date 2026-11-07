const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { OpenRouterVideoJobs, videoFormat } = require('../lib/openrouter-video-jobs');
const key = 'qa-video-secret-not-a-real-key';
const payload = { requestId: 'request_A', model: 'qa/video-model', prompt: 'private QA prompt' };

test('dot provider IDs cannot escape the videos path or authorize another paid submit', async () => {
    for (const id of ['.', '..']) await fixture(async ({ options }) => {
        let posts = 0;
        options.request = async () => { posts++; return { status: 202, json: { id, status: 'completed' } }; };
        const jobs = new OpenRouterVideoJobs(options);
        assert.equal((await jobs.start(payload, key)).status, 'unknown');
        const restored = new OpenRouterVideoJobs(options);
        assert.equal((await restored.start(payload, key)).status, 'unknown');
        assert.equal((await restored.poll(payload.requestId, key)).status, 'unknown');
        await assert.rejects(restored.content(payload.requestId, key), error => error.status === 409);
        assert.equal(posts, 1);
    });
});
async function fixture(block) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'or-video-'));
    try {
        let posts = 0, polls = 0, downloads = 0;
        const options = { file: path.join(directory, 'jobs.json'), mediaDirectory: directory,
            capabilities: async () => ({ supported_durations: [8,4], supported_resolutions: ['720p'], supported_aspect_ratios: ['16:9'] }),
            request: async (url, request) => {
                assert.equal(request.headers.Authorization, 'Bearer ' + key);
                if (request.method === 'POST') { posts++; assert.equal(request.body.duration, 4);
                    await new Promise(r => setTimeout(r, 3));
                    return { status: 202, json: { id: 'provider_A', status: 'pending', polling_url: 'https://untrusted.invalid/token' } }; }
                polls++; assert.equal(url, 'https://openrouter.ai/api/v1/videos/provider_A');
                return { status: 200, json: { id: 'provider_A', status: 'completed', unsigned_urls: ['https://untrusted.invalid/key'], urls: ['signed-secret'], usage: { cost: 0.1 } } };
            },
            download: async (url, headers, target) => {
                downloads++; assert.equal(url, 'https://openrouter.ai/api/v1/videos/provider_A/content?index=0');
                assert.equal(headers.Authorization, 'Bearer ' + key);
                const bytes = Buffer.alloc(24); bytes.writeUInt32BE(24,0); bytes.write('ftypisom',4);
                fs.writeFileSync(target, bytes); // Container header fixture, not a generated playable video.
            } };
        await block({ options, directory, counts: () => ({ posts, polls, downloads }) });
    } finally {
        assert.ok(path.resolve(directory).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.ok(path.basename(directory).startsWith('or-video-'));
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

test('video start, concurrent duplicate, restart, poll and cached content never resubmit the paid POST', () => fixture(async ({ options, counts }) => {
    const jobs = new OpenRouterVideoJobs(options);
    const started = await Promise.all([jobs.start(payload, key), jobs.start(payload, key)]);
    assert.equal(started[0].id, 'provider_A');
    const restored = new OpenRouterVideoJobs(options);
    assert.equal((await restored.start(payload, key)).status, 'pending');
    assert.equal((await restored.poll(payload.requestId, key)).status, 'completed');
    const media = await restored.content(payload.requestId, key);
    assert.equal(media.mimeType, 'video/mp4');
    assert.equal(media.durationSec, 0);
    assert.equal((await restored.content(payload.requestId, key)).fileName, media.fileName);
    assert.deepEqual(counts(), { posts: 1, polls: 1, downloads: 1 });
    const stored = fs.readFileSync(options.file, 'utf8');
    for (const secret of [key, payload.prompt, 'signed-secret', 'untrusted.invalid']) assert.ok(!stored.includes(secret));
}));

test('same ID cannot change prompt or key and invalid duration does not dispatch', () => fixture(async ({ options, counts }) => {
    const jobs = new OpenRouterVideoJobs(options);
    await jobs.start(payload, key);
    await assert.rejects(jobs.start({ ...payload, prompt: 'different' }, key), error => error.status === 409);
    await assert.rejects(jobs.poll(payload.requestId, 'different-key'), error => error.status === 403);
    await assert.rejects(jobs.start({ ...payload, requestId: 'other', duration: 3 }, key), error => error.status === 400);
    await assert.rejects(jobs.start({ ...payload, requestId: '../escape' }, key), error => error.status === 400);
    assert.equal(counts().posts, 1);
}));

test('timeout after submission stays unknown across restart instead of repeating a paid job', () => fixture(async ({ options }) => {
    let posts = 0;
    options.request = async () => { posts++; throw Error('response lost'); };
    const first = new OpenRouterVideoJobs(options);
    assert.equal((await first.start(payload, key)).status, 'unknown');
    const restored = new OpenRouterVideoJobs(options);
    assert.equal((await restored.start(payload, key)).status, 'unknown');
    assert.equal(posts, 1);
}));

test('process death in submitting state restores unknown without another POST', () => fixture(async ({ options }) => {
    const jobs = new OpenRouterVideoJobs(options);
    await jobs.start(payload, key);
    const saved = JSON.parse(fs.readFileSync(options.file));
    saved.jobs[0].status = 'submitting'; delete saved.jobs[0].id;
    fs.writeFileSync(options.file, JSON.stringify(saved));
    options.request = async () => { throw Error('must not request'); };
    assert.equal((await new OpenRouterVideoJobs(options).start(payload, key)).status, 'unknown');
}));

test('402 remains a terminal failed job and secret-bearing provider errors are redacted', () => fixture(async ({ options }) => {
    let posts = 0;
    options.request = async () => { posts++; return { status: 402, json: { error: { message: `No credits ${key} https://secret.invalid/signed` } } }; };
    const jobs = new OpenRouterVideoJobs(options);
    const result = await jobs.start(payload, key);
    assert.equal(result.status, 'failed'); assert.equal(result.httpStatus, 402);
    assert.ok(!result.error.includes(key)); assert.ok(!result.error.includes('secret.invalid'));
    await jobs.start(payload, key);
    assert.equal(posts, 1);
}));

test('polling a different provider ID never overwrites the owned job', () => fixture(async ({ options }) => {
    const jobs = new OpenRouterVideoJobs(options);
    await jobs.start(payload, key);
    jobs.request = async () => ({ status: 200, json: { id: 'other', status: 'completed' } });
    await assert.rejects(jobs.poll(payload.requestId, key), error => error.status === 502);
    assert.equal((await jobs.start(payload, key)).status, 'pending');
}));

test('journal storage failure prevents paid submission and unknown video bytes are rejected', () => fixture(async ({ options, counts }) => {
    options.file = path.join(options.mediaDirectory, 'missing', 'jobs.json');
    await assert.rejects(new OpenRouterVideoJobs(options).start(payload, key));
    assert.equal(counts().posts, 0);
    assert.equal(videoFormat(Buffer.from('<html>not a video</html>')), null);
    const avif = Buffer.alloc(24); avif.write('ftypavif',4);
    assert.equal(videoFormat(avif), null);
}));

test('truncated cached video is downloaded again without generating a new job', () => fixture(async ({ options, counts }) => {
    const jobs = new OpenRouterVideoJobs(options);
    await jobs.start(payload, key); await jobs.poll(payload.requestId, key);
    const media = await jobs.content(payload.requestId, key);
    fs.writeFileSync(path.join(options.mediaDirectory, media.fileName), 'broken');
    await jobs.content(payload.requestId, key);
    assert.deepEqual(counts(), { posts: 1, polls: 1, downloads: 2 });
}));
