'use strict';
const fs = require('node:fs');
const SOURCES = { chat: '/models', images: '/images/models', videos: '/videos/models' };

function mergeOpenRouterCatalogs(sources) {
    const merged = new Map();
    for (const kind of Object.keys(SOURCES)) {
        const rows = sources[kind];
        if (!Object.hasOwn(SOURCES, kind) || !Array.isArray(rows)) continue;
        for (const raw of rows) {
            if (!raw || typeof raw.id !== 'string' || !raw.id.trim()) continue;
            const id = raw.id.trim();
            const previous = merged.get(id);
            const row = previous || { ...raw, id, catalogEndpoints: [], generationCapabilities: {} };
            const arch = { ...(row.architecture || {}) };
            const rawArch = raw.architecture || {};
            const output = arch.output_modalities || String(arch.modality || 'text->text').split('->')[1]?.split('+') || [];
            arch.input_modalities = arch.input_modalities || rawArch.input_modalities || ['text'];
            arch.output_modalities = [...new Set([
                ...(previous ? output : rawArch.output_modalities || (kind === 'chat' ? output : [])),
                ...(kind === 'images' ? ['image'] : kind === 'videos' ? ['video'] : [])
            ])];
            arch.modality = `${arch.input_modalities.join('+')}->${arch.output_modalities.join('+')}`;
            row.architecture = arch;
            if (!row.catalogEndpoints.includes(kind)) row.catalogEndpoints.push(kind);
            if (kind !== 'chat') row.generationCapabilities[kind] = { ...raw };
            if (!previous && kind !== 'chat' && raw.context_length == null) row.context_length = 0;
            merged.set(id, row);
        }
    }
    return [...merged.values()];
}

/** Each endpoint has its own last good response; a failed source cannot erase its models. */
class OpenRouterCatalog {
    constructor({ request, file, classify, now = Date.now }) {
        this.request = request; this.file = file; this.classify = classify; this.now = now;
        this.sources = {}; this.inFlight = null;
        try {
            const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
            if (saved.version === 2) for (const kind of Object.keys(SOURCES)) {
                const source = saved.sources?.[kind];
                if (Array.isArray(source?.data) && source.data.length && Number.isFinite(source.at)) this.sources[kind] = source;
            }
        } catch (_) {}
    }
    result(fromCache, failed = []) {
        const timestamps = Object.values(this.sources).map(s => Number(s.at) || 0).filter(Boolean);
        const at = timestamps.length ? Math.min(...timestamps) : 0;
        const models = mergeOpenRouterCatalogs(Object.fromEntries(
            Object.entries(this.sources).map(([key, source]) => [key, source.data])
        )).map(this.classify).sort((a, b) => (b.created || 0) - (a.created || 0));
        const missing = Object.keys(SOURCES).filter(key => !this.sources[key]);
        return { ok: models.length > 0, fromCache, partial: !!(failed.length || missing.length),
            failedSources: [...new Set([...failed, ...missing])], catalogVersion: 2,
            source: 'openrouter.ai: models + images/models + videos/models' + (fromCache ? ' (cache)' : ''),
            syncedAt: at ? new Date(at).toISOString() : '', cachedAt: at ? new Date(at).toISOString() : '',
            totalCount: models.length, totalCached: models.length, models };
    }
    async get(force = false) {
        if (this.inFlight) return this.inFlight;
        const cached = this.result(true);
        if (!force && cached.ok && !cached.partial && this.now() - Date.parse(cached.syncedAt) < 600000)
            return cached;
        this.inFlight = this.refresh();
        try { return await this.inFlight; } finally { this.inFlight = null; }
    }
    async refresh() {
        const failures = [];
        let updated = 0;
        await Promise.all(Object.entries(SOURCES).map(async ([kind, endpoint]) => {
            try {
                const response = await this.request('https://openrouter.ai/api/v1' + endpoint, { timeoutMs: 12000 });
                if (response.status !== 200 || !Array.isArray(response.json?.data) || !response.json.data.length)
                    throw Error('Catalog unavailable');
                this.sources[kind] = { at: this.now(), data: response.json.data };
                updated++;
            } catch (_) { failures.push(kind); }
        }));
        if (updated && this.file) {
            try {
                const temporary = this.file + '.tmp';
                fs.writeFileSync(temporary, JSON.stringify({ version: 2, sources: this.sources }));
                fs.renameSync(temporary, this.file);
            } catch (_) {} // In-memory results remain usable when cache storage is unavailable.
        }
        return this.result(!updated || failures.length > 0, failures);
    }
}
module.exports = { mergeOpenRouterCatalogs, OpenRouterCatalog };
