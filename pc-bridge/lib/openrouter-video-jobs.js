'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const API = 'https://openrouter.ai/api/v1/videos';
const ID = /^(?!\.{1,2}$)[a-zA-Z0-9_.:-]{1,160}$/;
const REQUEST_ID = /^[a-zA-Z0-9_-]{1,128}$/;
const TERMINAL = new Set(['completed', 'failed', 'cancelled', 'expired']);
const digest = value => createHash('sha256').update(value).digest('hex');
function failure(status, message) { return Object.assign(new Error(message), { status }); }
function safeError(value, key) {
    return String(value || 'Ошибка OpenRouter').split(key).join('[скрыто]')
        .replace(/https?:\/\/\S+/g, '[URL]').slice(0, 1000);
}
function videoFormat(bytes) {
    if (bytes.length >= 12 && bytes.toString('ascii', 4, 8) === 'ftyp' &&
        /^(isom|iso[2-9]|mp41|mp42|avc1|dash|M4V |MSNV)$/.test(bytes.toString('ascii', 8, 12)))
        return { extension: 'mp4', mimeType: 'video/mp4' };
    if (bytes.length >= 4 && bytes.readUInt32BE(0) === 0x1a45dfa3 && bytes.includes(Buffer.from('webm')))
        return { extension: 'webm', mimeType: 'video/webm' };
    return null;
}

class OpenRouterVideoJobs {
    constructor({ file, mediaDirectory, request, download, capabilities, now = Date.now }) {
        this.file = file; this.mediaDirectory = mediaDirectory; this.request = request;
        this.download = download; this.capabilities = capabilities; this.now = now;
        this.jobs = new Map(); this.starts = new Map(); this.downloads = new Map();
        if (fs.existsSync(file)) {
            const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
            if (saved.version !== 1 || !Array.isArray(saved.jobs) || saved.jobs.length > 1000) throw Error('Unsupported video journal');
            for (const job of saved.jobs) {
                if (!REQUEST_ID.test(job.requestId) || this.jobs.has(job.requestId) ||
                    !/^[a-f0-9]{64}$/.test(job.owner) || !/^[a-f0-9]{64}$/.test(job.fingerprint) ||
                    (job.id && !ID.test(job.id))) throw Error('Invalid video journal');
                if (job.status === 'submitting') {
                    job.status = 'unknown'; job.error = 'Ответ запуска потерян. Повторная отправка не выполнялась.';
                }
                this.jobs.set(job.requestId, job);
            }
        }
    }
    persist() {
        const temporary = this.file + '.tmp';
        const output = fs.openSync(temporary, 'w');
        try { fs.writeFileSync(output, JSON.stringify({ version: 1, jobs: [...this.jobs.values()] })); fs.fsyncSync(output); }
        finally { fs.closeSync(output); }
        fs.renameSync(temporary, this.file);
    }
    publicJob(job) {
        return { requestId: job.requestId, id: job.id || '', model: job.model, status: job.status,
            createdAt: job.createdAt, error: job.error || '', httpStatus: job.httpStatus || 0,
            duration: job.duration || 0, cost: job.cost ?? null, downloadable: job.status === 'completed' };
    }
    owned(requestId, key) {
        if (!key) throw failure(400, 'Укажите API-ключ OpenRouter');
        const job = this.jobs.get(requestId);
        if (!job) throw failure(404, 'Видеозадача не найдена');
        if (job.owner !== digest(key)) throw failure(403, 'Для этой задачи нужен исходный API-ключ OpenRouter');
        return job;
    }
    async start(payload, key) {
        if (!key) throw failure(400, 'Укажите API-ключ OpenRouter');
        const requestId = String(payload.requestId || '');
        const model = String(payload.model || '').trim();
        const prompt = String(payload.prompt || '').trim();
        if (!REQUEST_ID.test(requestId) || !model || !prompt || prompt.length > 16000) throw failure(400, 'Нужны ID запроса, модель и описание видео');
        if (payload.frame_images || payload.input_references || payload.provider || payload.callback_url)
            throw failure(400, 'Этот маршрут пока поддерживает генерацию видео из текста без референсов и provider options');
        const options = {};
        for (const name of ['duration', 'resolution', 'aspect_ratio']) if (payload[name] != null) options[name] = payload[name];
        const fingerprint = digest(JSON.stringify({ model, prompt, options }));
        const existing = this.jobs.get(requestId);
        if (existing) {
            this.owned(requestId, key);
            if (existing.fingerprint !== fingerprint) throw failure(409, 'ID уже относится к другому запросу');
            const active = this.starts.get(requestId);
            return active ? active.promise : this.publicJob(existing);
        }
        const active = this.starts.get(requestId);
        if (active) {
            if (active.fingerprint !== fingerprint || active.owner !== digest(key)) throw failure(409, 'ID уже занят другим запросом');
            return active.promise;
        }
        const promise = this.create(requestId, model, prompt, options, fingerprint, key);
        this.starts.set(requestId, { fingerprint, owner: digest(key), promise });
        try { return await promise; } finally { this.starts.delete(requestId); }
    }
    async create(requestId, model, prompt, options, fingerprint, key) {
        const caps = await this.capabilities(model);
        if (!caps) throw failure(400, 'Модель не найдена в видео-каталоге OpenRouter');
        for (const [name, values] of [['duration', caps.supported_durations], ['resolution', caps.supported_resolutions], ['aspect_ratio', caps.supported_aspect_ratios]]) {
            if (options[name] != null && (!Array.isArray(values) || !values.includes(options[name])))
                throw failure(400, `Модель не поддерживает выбранный параметр ${name}`);
        }
        if (options.duration == null && Array.isArray(caps.supported_durations)) {
            const durations = caps.supported_durations.filter(n => Number.isInteger(n) && n > 0);
            if (durations.length) options.duration = Math.min(...durations);
        }
        if (this.jobs.size >= 1000) throw failure(409, 'Журнал видеозадач заполнен; новая платная задача не отправлена');
        const job = { requestId, model, fingerprint, owner: digest(key), status: 'submitting', createdAt: this.now(),
            duration: 0, requestedDuration: options.duration || 0 };
        this.jobs.set(requestId, job);
        try { this.persist(); } catch (error) { this.jobs.delete(requestId); throw error; }
        try {
            const response = await this.request(API, { method: 'POST', headers: { Authorization: `Bearer ${key}` },
                body: { model, prompt, ...options }, timeoutMs: 60000 });
            if (response.status >= 400 && response.status < 500) {
                job.status = 'failed'; job.httpStatus = response.status;
                job.error = safeError(response.json?.error?.message || `OpenRouter HTTP ${response.status}`, key);
            } else if (response.status >= 200 && response.status < 300 && typeof response.json?.id === 'string' && ID.test(response.json.id)) {
                job.id = response.json.id;
                job.status = ['pending','in_progress','completed'].includes(response.json.status) ? response.json.status : 'pending';
            } else throw Error('Не получен однозначный ответ запуска');
        } catch (_) {
            job.status = 'unknown'; job.error = 'Ответ запуска потерян. Повторная отправка не выполнялась.';
        }
        this.persist();
        return this.publicJob(job);
    }
    async poll(requestId, key) {
        const job = this.owned(requestId, key);
        if (!job.id || TERMINAL.has(job.status)) return this.publicJob(job);
        const response = await this.request(`${API}/${encodeURIComponent(job.id)}`, {
            headers: { Authorization: `Bearer ${key}` }, timeoutMs: 30000 });
        if (response.status !== 200) throw failure(response.status >= 400 && response.status <= 599 ? response.status : 502,
            safeError(response.json?.error?.message || 'Не удалось обновить видеозадачу', key));
        const state = response.json;
        if (state?.id !== job.id || !['pending','in_progress',...TERMINAL].includes(state.status))
            throw failure(502, 'OpenRouter вернул другой ID или неизвестный статус');
        job.status = state.status;
        if (state.error) job.error = safeError(typeof state.error === 'string' ? state.error : state.error.message, key);
        if (Number.isFinite(state.usage?.cost) && state.usage.cost >= 0) job.cost = state.usage.cost;
        if (Number.isFinite(state.duration) && state.duration > 0) job.duration = state.duration;
        // Signed/unsigned URLs from the provider are neither persisted nor returned.
        this.persist();
        return this.publicJob(job);
    }
    async content(requestId, key) {
        const job = this.owned(requestId, key);
        if (job.status !== 'completed') throw failure(409, 'Видео ещё не готово');
        if (this.downloads.has(requestId)) return this.downloads.get(requestId);
        const promise = this.saveContent(job, key);
        this.downloads.set(requestId, promise);
        try { return await promise; } finally { this.downloads.delete(requestId); }
    }
    async saveContent(job, key) {
        if (job.media && ['mp4','webm'].some(extension => job.media.fileName === `openrouter_video_${job.requestId}.${extension}`) &&
            fs.existsSync(path.join(this.mediaDirectory, job.media.fileName)) && job.media.sizeBytes > 0 &&
            fs.statSync(path.join(this.mediaDirectory, job.media.fileName)).isFile() &&
            fs.statSync(path.join(this.mediaDirectory, job.media.fileName)).size === job.media.sizeBytes)
            return job.media;
        const temporary = path.join(this.mediaDirectory, `openrouter_video_${job.requestId}.part`);
        try {
            await this.download(`${API}/${encodeURIComponent(job.id)}/content?index=0`, {
                Authorization: `Bearer ${key}` }, temporary);
            const head = Buffer.alloc(1024);
            const input = fs.openSync(temporary, 'r');
            let length;
            try { length = fs.readSync(input, head, 0, head.length, 0); } finally { fs.closeSync(input); }
            const format = videoFormat(head.subarray(0, length));
            if (!format) throw failure(502, 'OpenRouter вернул неизвестный формат видео');
            const fileName = `openrouter_video_${job.requestId}.${format.extension}`;
            fs.renameSync(temporary, path.join(this.mediaDirectory, fileName));
            job.media = { fileName, mimeType: format.mimeType, model: job.model, durationSec: job.duration || 0,
                width: 0, height: 0, resolution: '', sizeBytes: fs.statSync(path.join(this.mediaDirectory, fileName)).size,
                mediaUrl: `/download/media/${encodeURIComponent(fileName)}` };
            this.persist();
            return job.media;
        } finally { try { fs.unlinkSync(temporary); } catch (_) {} }
    }
}
module.exports = { OpenRouterVideoJobs, videoFormat };
