const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

function extractFunction(name, context = {}) {
    const start = source.indexOf(`function ${name}(`);
    assert.ok(start > 0, `${name} найден в server.js`);
    const end = source.indexOf('\n}', start) + 2;
    return vm.runInNewContext(`(${source.slice(start, end).trim()})`, context);
}

test('parsePngDimensions читает реальные размеры из IHDR и отвергает не-PNG', () => {
    const parse = extractFunction('parsePngDimensions', { Buffer });
    const png = Buffer.alloc(24);
    png[0] = 0x89; png[1] = 0x50; png[2] = 0x4E; png[3] = 0x47;
    png.writeUInt32BE(1024, 16);
    png.writeUInt32BE(576, 20);
    const dims = parse(png);
    assert.equal(dims.width, 1024);
    assert.equal(dims.height, 576);
    assert.equal(parse(Buffer.from('not a png at all, really!!')), null);
    assert.equal(parse(Buffer.alloc(4)), null);
});

test('extractOpenRouterImageBase64 находит изображение в разных форматах ответа', () => {
    const extract = extractFunction('extractOpenRouterImageBase64');
    const viaImages = { choices: [{ message: { images: [{ image_url: { url: 'data:image/png;base64,QUJDREVG' } }] } }] };
    assert.equal(extract(viaImages), 'QUJDREVG');
    const viaB64 = { choices: [{ message: { images: [{ b64_json: 'R0hJTAc=' }] } }] };
    assert.equal(extract(viaB64), 'R0hJTAc=');
    const viaContent = { choices: [{ message: { content: [{ image_url: 'data:image/png;base64,SEVMTE8=' }] } }] };
    assert.equal(extract(viaContent), 'SEVMTE8=');
    assert.equal(extract({ choices: [{ message: { content: 'просто текст' } }] }), null);
    assert.equal(extract(null), null);
});

test('buildCompactionPrompt включает всю переписку и требует только сводку', () => {
    const build = extractFunction('buildCompactionPrompt');
    const prompt = build([
        { role: 'USER', content: 'Сделай экран входа' },
        { role: 'AGENT', content: 'Готово, файл LoginScreen.kt' },
        { role: 'USER', content: '  ' },
        null
    ]);
    assert.ok(prompt.includes('Пользователь: Сделай экран входа'));
    assert.ok(prompt.includes('Ассистент: Готово, файл LoginScreen.kt'));
    assert.ok(prompt.includes('сводк'));
    assert.ok(!prompt.includes('null:'), 'повреждённые записи не попадают в промпт');
});
