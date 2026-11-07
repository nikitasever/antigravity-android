'use strict';
function createOpenRouterVideoApi({ jobs, keyFor }) {
    return async function handle(action, payload) {
        const key = keyFor(payload);
        try {
            const runtime = jobs();
            if (action === 'download') {
                const media = await runtime.content(String(payload.requestId || ''), key);
                return { status: 200, body: { ok: true, media } };
            }
            const job = action === 'start' ? await runtime.start(payload, key) :
                action === 'status' ? await runtime.poll(String(payload.requestId || ''), key) : null;
            if (!job) return { status: 400, body: { ok: false, error: 'Неизвестное действие видеозадачи' } };
            const rejected = action === 'start' && ['failed','unknown'].includes(job.status);
            return { status: rejected ? job.httpStatus || 409 : action === 'start' ? 202 : 200,
                body: { ok: !rejected, job, ...(rejected ? { error: job.error } : {}) } };
        } catch (error) {
            const detail = String(error.message || 'Ошибка видеозадачи').slice(0, 1000);
            return { status: error.status >= 400 && error.status <= 599 ? error.status : 502,
                body: { ok: false, error: key ? detail.split(key).join('[скрыто]') : detail } };
        }
    };
}
module.exports = { createOpenRouterVideoApi };
