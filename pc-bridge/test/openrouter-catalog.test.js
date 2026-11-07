const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { mergeOpenRouterCatalogs, OpenRouterCatalog } = require('../lib/openrouter-catalog');

test('catalog merges IDs while preserving chat and dedicated generation capabilities', () => {
    const sources = { chat: [{ id: 'shared', context_length: 5000, architecture: { input_modalities: ['text','image'], output_modalities: ['text'] } }],
        images: [{ id: 'shared', supported_parameters: { n: { min: 1, max: 1 } } }],
        videos: [{ id: 'video-only', supported_durations: [4,8] }] };
    const before = JSON.stringify(sources);
    const merged = mergeOpenRouterCatalogs(sources);
    assert.equal(merged.length, 2);
    assert.deepEqual(merged[0].architecture.output_modalities, ['text','image']);
    assert.equal(merged[0].context_length, 5000);
    assert.deepEqual(merged[0].catalogEndpoints, ['chat','images']);
    assert.equal(merged[0].generationCapabilities.images.supported_parameters.n.max, 1);
    assert.deepEqual(merged[1].architecture.output_modalities, ['video']);
    assert.equal(merged[1].context_length, 0);
    assert.deepEqual(merged[1].generationCapabilities.videos.supported_durations, [4,8]);
    assert.equal(JSON.stringify(sources), before);
    assert.deepEqual(mergeOpenRouterCatalogs({ videos: sources.videos, images: sources.images, chat: sources.chat }), merged);
});

test('old classified cache is not treated as current raw-source discovery', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'or-catalog-'));
    try {
        const file = path.join(dir, 'cache.json');
        fs.writeFileSync(file, JSON.stringify({ models: [{ id: 'old', isVideoGen: true }] }));
        const catalog = new OpenRouterCatalog({ file, classify: row => row,
            request: async () => { throw Error('offline'); } });
        const result = await catalog.get();
        assert.equal(result.ok, false);
        assert.equal(result.models.length, 0);
        assert.equal(result.partial, true);
    } finally {
        assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.ok(path.basename(dir).startsWith('or-catalog-'));
        fs.rmSync(dir, { recursive: true, force: true });
    }
});

test('partial failures preserve dedicated models across refresh and process restart', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'or-catalog-'));
    try {
        let offline = false;
        let calls = 0;
        const options = { file: path.join(dir, 'cache.json'), classify: row => row, now: () => 2000000,
            request: async url => { calls++; if (offline) throw Error('offline');
                return { status: 200, json: { data: [{ id: url.split('/').slice(-2).join('-') }] } }; } };
        const catalog = new OpenRouterCatalog(options);
        const initial = await catalog.get();
        assert.equal(initial.partial, false);
        assert.equal(calls, 3);
        await catalog.get();
        assert.equal(calls, 3);
        offline = true;
        const restarted = new OpenRouterCatalog(options);
        const stale = await restarted.get(true);
        assert.equal(stale.models.length, initial.models.length);
        assert.equal(stale.fromCache, true);
        assert.equal(stale.partial, true);
        assert.deepEqual(new Set(stale.failedSources), new Set(['chat','images','videos']));
    } finally {
        assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.ok(path.basename(dir).startsWith('or-catalog-'));
        fs.rmSync(dir, { recursive: true, force: true });
    }
});

test('concurrent refreshes share the same three requests and reject malformed successful responses', async () => {
    let calls = 0;
    const catalog = new OpenRouterCatalog({ file: '', classify: row => row,
        request: async () => { calls++; await new Promise(r => setTimeout(r, 5)); return { status: 200, json: { error: 'not a catalog' } }; } });
    const results = await Promise.all([catalog.get(true), catalog.get(true)]);
    assert.equal(calls, 3);
    assert.equal(results[0].ok, false);
    assert.equal(results[1].partial, true);
});

test('one failed source retains its own models while successful sources update', async () => {
    let iteration = 1;
    const catalog = new OpenRouterCatalog({ file: '', classify: row => row,
        request: async url => {
            const kind = url.includes('/images/') ? 'image' : url.includes('/videos/') ? 'video' : 'chat';
            if (kind === 'image' && iteration === 2) throw Error('image endpoint offline');
            return { status: 200, json: { data: [{ id: `${kind}-${iteration}` }] } };
        } });
    await catalog.get(true);
    iteration = 2;
    const partial = await catalog.get(true);
    assert.equal(partial.partial, true);
    assert.deepEqual(partial.failedSources, ['images']);
    assert.deepEqual(new Set(partial.models.map(m => m.id)), new Set(['chat-2', 'image-1', 'video-2']));
});
