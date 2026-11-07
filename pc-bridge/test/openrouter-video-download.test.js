const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { EventEmitter } = require('node:events');
const { createVideoDownloader } = require('../lib/openrouter-video-download');

async function temporary(block) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'or-download-'));
    try { await block(path.join(dir, 'output.part')); }
    finally {
        assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.ok(path.basename(dir).startsWith('or-download-'));
        fs.rmSync(dir, { recursive: true, force: true });
    }
}
function transport(respond) {
    return (url, options, callback) => {
        const response = respond(url, options);
        const stream = Readable.from(response.bytes ? [response.bytes] : []);
        stream.statusCode = response.status; stream.headers = response.headers || {};
        queueMicrotask(() => callback(stream));
        return new EventEmitter();
    };
}

test('content redirect drops Authorization at external storage and saves only bytes', () => temporary(async file => {
    const calls = [];
    const download = createVideoDownloader(transport((url, options) => {
        calls.push({ url: url.href, headers: options.headers });
        return calls.length === 1 ? { status: 302, headers: { location: 'https://storage.example/video?signature=qa' } } :
            { status: 200, bytes: Buffer.from('QA content') };
    }));
    await download('https://openrouter.ai/api/v1/videos/id/content', { Authorization: 'Bearer qa-key' }, file);
    assert.equal(calls[0].headers.Authorization, 'Bearer qa-key');
    assert.deepEqual(calls[1].headers, {});
    assert.equal(fs.readFileSync(file, 'utf8'), 'QA content');
}));
test('oversized or non-HTTPS content is rejected before file creation', () => temporary(async file => {
    const download = createVideoDownloader(transport(() => ({ status: 200, headers: { 'content-length': String(256 * 1024 * 1024 + 1) } })));
    await assert.rejects(download('https://openrouter.ai/api/v1/videos/id/content', {}, file), error => error.status === 413);
    await assert.rejects(download('http://openrouter.ai/api/v1/videos/id/content', {}, file));
    assert.equal(fs.existsSync(file), false);
}));
test('redirect loops are bounded and an OpenRouter lookalike never gets a key', () => temporary(async file => {
    let calls = 0;
    const download = createVideoDownloader(transport((url, options) => {
        calls++;
        if (calls > 1) assert.deepEqual(options.headers, {});
        return { status: 302, headers: { location: 'https://openrouter.ai.evil.example/loop' } };
    }));
    await assert.rejects(download('https://openrouter.ai/api/v1/videos/id/content', { Authorization: 'Bearer qa' }, file));
    assert.equal(calls, 4);
}));
