'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const workflow = fs.readFileSync(path.join(root, '.github/workflows/build.yml'), 'utf8');
const block = workflow.match(/          node <<'NODE'\r?\n([\s\S]*?)          NODE/);
assert.ok(block, 'release validation step must exist');
const source = block[1].replace(/^          /gm, '');
const request = JSON.parse(fs.readFileSync(path.join(root, '.github/release.json'), 'utf8'));
const gradle = fs.readFileSync(path.join(root, 'app/build.gradle.kts'), 'utf8');

async function validate({ changed = true, tagStatus = 404, tagSha = 'older', manifest = request } = {}) {
    let output = '', calls = 0, error = '';
    const proc = { env: { GITHUB_SHA: 'current', GITHUB_REPOSITORY: 'nikitasever/antigravity-android', GH_TOKEN: 'test-only', GITHUB_OUTPUT: 'output' }, exitCode: 0, exit: () => { throw new Error('EXIT'); } };
    const files = { readFileSync: p => p.endsWith('release.json') ? JSON.stringify(manifest) : gradle,
        appendFileSync: (p, text) => { assert.equal(p, 'output'); output += text; } };
    try {
        vm.runInNewContext(source, {
            require: id => id === 'node:fs' ? files : { execFileSync: () => changed ? '.github/release.json\n' : '' },
            process: proc, console: { error: text => { error = text; } },
            fetch: async url => {
                calls++;
                assert.equal(url, `https://api.github.com/repos/nikitasever/antigravity-android/git/ref/tags/${manifest.tag}`);
                return { status: tagStatus, ok: tagStatus === 200, json: async () => ({ object: { sha: tagSha } }) };
            }
        });
    } catch (failure) {
        if (failure.message !== 'EXIT') { proc.exitCode = 1; error = failure.message; }
    }
    await new Promise(setImmediate);
    return { output, calls, error, code: proc.exitCode };
}

test('release: unchanged request never republishes or contacts GitHub', async () => {
    assert.deepEqual(await validate({ changed: false }), { output: '', calls: 0, error: '', code: 0 });
});
test('release: matching app version can publish a new tag', async () => {
    const r = await validate();
    assert.equal(r.code, 0); assert.match(r.output, /publish=true/); assert.ok(r.output.includes(`tag=${request.tag}\n`));
});
test('release: wrong app version is rejected before any API request', async () => {
    const r = await validate({ manifest: { ...request, tag: 'v0.0.0' } });
    assert.equal(r.code, 1); assert.equal(r.output, ''); assert.equal(r.calls, 0);
});
test('release: multiline names cannot inject workflow outputs', async () => {
    const r = await validate({ manifest: { ...request, name: 'Release\npublish=false' } });
    assert.equal(r.code, 1); assert.equal(r.calls, 0);
});
test('release: an existing tag on a different commit cannot be overwritten', async () => {
    const r = await validate({ tagStatus: 200 });
    assert.equal(r.code, 1); assert.equal(r.output, ''); assert.match(r.error, /different commit/);
});
test('release: failed tag authorization is not treated as an absent tag', async () => {
    const r = await validate({ tagStatus: 403 });
    assert.equal(r.code, 1); assert.equal(r.output, ''); assert.match(r.error, /HTTP 403/);
});
test('release: rerunning the same commit keeps the same release target', async () => {
    const r = await validate({ tagStatus: 200, tagSha: 'current' });
    assert.equal(r.code, 0); assert.match(r.output, /publish=true/);
});
