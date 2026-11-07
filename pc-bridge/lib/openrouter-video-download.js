'use strict';
const https = require('node:https');
const fs = require('node:fs');
const { Transform } = require('node:stream');
const { pipeline } = require('node:stream/promises');

/** Canonical API content may redirect to storage; the key is sent only to OpenRouter. */
function createVideoDownloader(get = https.get) { return async function downloadOpenRouterVideo(url, headers, file) {
    const signal = AbortSignal.timeout(120000);
    const maximum = 256 * 1024 * 1024;
    async function follow(address, remaining) {
        const parsed = new URL(address);
        if (parsed.protocol !== 'https:') throw Error('Небезопасный адрес скачивания видео');
        const response = await new Promise((resolve, reject) => {
            const request = get(parsed, { signal, headers: parsed.origin === 'https://openrouter.ai' ? headers : {} }, resolve);
            request.on('error', reject);
        });
        if ([301,302,303,307,308].includes(response.statusCode)) {
            response.resume();
            if (!remaining || !response.headers.location) throw Error('Слишком много перенаправлений при скачивании видео');
            return follow(new URL(response.headers.location, parsed).href, remaining - 1);
        }
        if (response.statusCode !== 200) {
            response.resume();
            throw Object.assign(new Error(`Не удалось скачать видео: HTTP ${response.statusCode}`), { status: response.statusCode });
        }
        if (Number(response.headers['content-length']) > maximum) {
            response.destroy(); throw Object.assign(new Error('Видео превышает лимит скачивания 256 МБ'), { status: 413 });
        }
        let received = 0;
        const limit = new Transform({ transform(chunk, encoding, callback) {
            received += chunk.length;
            callback(received > maximum ? new Error('Видео превышает лимит скачивания 256 МБ') : null, chunk);
        } });
        await pipeline(response, limit, fs.createWriteStream(file, { flags: 'w' }));
    }
    await follow(url, 3);
}; }
const downloadOpenRouterVideo = createVideoDownloader();
module.exports = { downloadOpenRouterVideo, createVideoDownloader };
