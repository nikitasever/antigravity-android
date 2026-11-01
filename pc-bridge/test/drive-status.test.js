const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');

// Exercise the real route bodies without starting workers, tunnels, or a server.
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
const start = source.indexOf("    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/status')");
const end = source.indexOf("    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/read')", start);
assert.ok(start >= 0 && end > start);
const route = vm.runInNewContext(`(async function(req, urlObj, res, listGoogleDriveFiles, sendJson, GDRIVE_WORKSPACE_DIR, loadGDriveOAuthConfig, googleDriveStatus, listGoogleDriveRealFiles) { ${source.slice(start, end)} })`);

for (const endpoint of ['status', 'files']) {
    test(`local drive ${endpoint} does not claim an authenticated Google account`, async () => {
        const files = [{ name: 'real.md', sizeBytes: 7 }];
        let response;
        await route({ method: 'GET' }, new URL(`http://localhost/api/gdrive/${endpoint}`), {},
            () => files, (_res, status, body) => { response = { status, body }; }, 'local-workspace',
            () => ({ accessToken: '', refreshToken: '' }),
            () => { throw new Error('real status must not run without OAuth'); },
            () => { throw new Error('real files must not run without OAuth'); });
        assert.equal(response.status, 200);
        assert.equal(response.body.connected, false);
        if (endpoint === 'status') {
            assert.equal(response.body.accountEmail, '');
            assert.equal(response.body.usedBytes, 7);
            assert.equal(response.body.filesCount, 1);
        } else {
            assert.equal(response.body.files, files);
            assert.equal(response.body.total, 1);
        }
    });
}

test('drive routes switch to the real API only when OAuth tokens exist', async () => {
    let response;
    await route({ method: 'GET' }, new URL('http://localhost/api/gdrive/files?q=docs'), {},
        () => { throw new Error('local mirror must not run when OAuth is configured'); },
        (_res, status, body) => { response = { status, body }; }, 'local-workspace',
        () => ({ accessToken: 'real-token', refreshToken: '' }),
        () => { throw new Error('status stub not needed'); },
        async (q) => {
            assert.equal(q, 'docs');
            return { ok: true, files: [{ id: 'g1', name: 'doc.md', downloadUrl: '/download/gdrive_id/g1' }] };
        });
    assert.equal(response.status, 200);
    assert.equal(response.body.connected, true);
    assert.equal(response.body.total, 1);
    assert.equal(response.body.files[0].id, 'g1');
});
