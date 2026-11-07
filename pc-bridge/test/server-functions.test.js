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
    const functionStart = source.slice(start - 6, start) === 'async ' ? start - 6 : start;
    return vm.runInNewContext(`(${source.slice(functionStart, end).trim()})`, context);
}

test('OpenRouter capabilities use output metadata rather than a model name or video input', () => {
    const classify = extractFunction('classifyOpenRouterModel');
    const videoReader = classify({ id: 'example/video-reader', architecture: { input_modalities: ['text', 'video'], output_modalities: ['text'] } });
    assert.equal(videoReader.isVideoGen, false);
    assert.equal(videoReader.isVision, true);
    assert.equal(videoReader.isFree, false);
    assert.notEqual(videoReader.priceLabel, 'Бесплатно (Free)');
    const namedFlux = classify({ id: 'example/flux-assistant', architecture: { output_modalities: ['text'] } });
    assert.equal(namedFlux.isImageGen, false);
    const image = classify({ id: 'example/image', architecture: { output_modalities: ['image'] }, pricing: { prompt: '0', completion: '0', image: '0.02' } });
    assert.equal(image.isImageGen, true);
    assert.equal(image.isFree, false);
    assert.equal(image.categories.includes('TEXT'), false);
});

test('OpenRouter image extraction supports the dedicated Images API', () => {
    const extract = extractFunction('extractOpenRouterImageBase64');
    assert.equal(extract({ data: [{ b64_json: 'QUJD' }] }), 'QUJD');
});

test('image generation preserves the selected model and uses the dedicated Images API', async () => {
    let sent;
    const handler = extractFunction('handleOpenRouterImageGeneration', {
        loadOpenRouterApiKey: () => 'qa-test-key',
        httpsJsonRequest: async (url, options) => { sent = { url, options }; return { status: 400, json: { error: { message: 'Unsupported test input' } } }; }
    });
    await handler({ model: 'black-forest-labs/flux.2-pro', prompt: 'QA' });
    assert.equal(sent.url, 'https://openrouter.ai/api/v1/images');
    assert.equal(sent.options.body.model, 'black-forest-labs/flux.2-pro');
    assert.equal(sent.options.body.prompt, 'QA');
    assert.equal(sent.options.body.n, 1);
});

test('image response keeps actual JPEG format and never labels it PNG', async () => {
    const bytes = Buffer.from([255,216,255,224,0,16,74,70,73,70,0,1,255,217]);
    let written;
    const identify = extractFunction('identifyOpenRouterImage', { Buffer });
    const handler = extractFunction('handleOpenRouterImageGeneration', {
        Buffer, path, GENERATED_MEDIA_DIR: '/qa',
        fs: { writeFileSync: (file, data) => { written = { file, data }; } },
        loadOpenRouterApiKey: () => 'qa-test-key',
        parsePngDimensions: extractFunction('parsePngDimensions', { Buffer }),
        identifyOpenRouterImage: identify,
        extractOpenRouterImageBase64: extractFunction('extractOpenRouterImageBase64'),
        httpsJsonRequest: async () => ({ status: 200, json: { data: [{ b64_json: bytes.toString('base64'), media_type: 'image/jpeg' }] } })
    });
    const result = await handler({ model: 'example/model', prompt: 'QA' });
    assert.equal(result.status, 200);
    assert.equal(result.body.media.mimeType, 'image/jpeg');
    assert.equal(result.body.media.model, 'example/model');
    assert.ok(written.file.endsWith('.jpg'));
    assert.deepEqual(written.data, bytes);
    assert.equal(identify(Buffer.from('not an image response')), null);
});

test('blank image model is rejected without dispatching a replacement', async () => {
    let calls = 0;
    const handler = extractFunction('handleOpenRouterImageGeneration', {
        loadOpenRouterApiKey: () => 'qa-test-key',
        httpsJsonRequest: async () => { calls++; throw Error('must not call'); }
    });
    assert.equal((await handler({ model: '', prompt: 'QA' })).status, 400);
    assert.equal(calls, 0);
});

test('OpenRouter credit errors keep HTTP 402 and redact a reflected key', async () => {
    const handler = extractFunction('handleOpenRouterImageGeneration', {
        loadOpenRouterApiKey: () => 'qa-test-key',
        httpsJsonRequest: async () => ({ status: 402, json: { error: { message: 'Insufficient credits qa-test-key' } } })
    });
    const result = await handler({ model: 'example/image', prompt: 'QA' });
    assert.equal(result.status, 402);
    assert.ok(result.body.error.includes('Insufficient credits'));
    assert.ok(!result.body.error.includes('qa-test-key'));
});

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


test('web console session identifiers cannot close the inline script element', () => {
    const start = source.indexOf('function renderLiveWebConsoleHtml(');
    const end = source.indexOf('\n}', source.indexOf('</html>', start)) + 2;
    const render = vm.runInNewContext(`(${source.slice(start, end)})`);
    const attack = '</script><script>alert(1)</script>';
    const html = render(attack);
    assert.equal(html.includes(attack), false);
    assert.equal((html.match(/<script>/g) || []).length, 1);
});
