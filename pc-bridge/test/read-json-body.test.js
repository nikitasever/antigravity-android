const { PassThrough } = require('node:stream');
const test = require('node:test');
const assert = require('node:assert/strict');
const { readJsonBody } = require('../lib/read-json-body');

test('a multibyte message split between network chunks is preserved', async () => {
    const request = new PassThrough();
    const result = readJsonBody(request);
    const bytes = Buffer.from(JSON.stringify({ prompt: 'Привет 🌍' }));
    for (const byte of bytes) request.write(Buffer.from([byte]));
    request.end();
    assert.deepEqual(await result, { prompt: 'Привет 🌍' });
});

for (const input of ['{', 'null', '[]', '"text"']) {
    test(`invalid body ${input} fails instead of running an empty command`, async () => {
        const request = new PassThrough();
        const result = readJsonBody(request);
        request.end(input);
        await assert.rejects(result, { statusCode: 400 });
    });
}

test('oversized requests fail with 413 and release buffered chunks', async () => {
    const request = new PassThrough();
    const result = readJsonBody(request, 10);
    request.end(JSON.stringify({ prompt: 'too long' }));
    await assert.rejects(result, { statusCode: 413 });
    assert.equal(request.listenerCount('data'), 0);
});

test('an aborted request rejects instead of waiting forever', async () => {
    const request = new PassThrough();
    const result = readJsonBody(request);
    request.emit('aborted');
    request.end();
    await assert.rejects(result, { statusCode: 400 });
});
