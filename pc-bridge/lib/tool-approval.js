'use strict';
const { randomUUID, randomBytes, timingSafeEqual } = require('node:crypto');
const { redactSecrets } = require('./mcp-client');

function approvalPreview(args, secrets = []) {
    return redactSecrets(JSON.stringify(args, (key, value) =>
        /token|secret|password|authorization|api[_-]?key/i.test(key) ? '[скрыто]' : value, 2), secrets);
}

// Memory-only capabilities. No persistent grants, argument replay or execution in this API.
class ToolApprovalBroker {
    constructor({ timeoutMs = 120000, limit = 64 } = {}) {
        this.timeoutMs = timeoutMs; this.limit = limit; this.requests = new Map();
    }
    ask({ turnId, conversationId, preview, signal, onRequest }) {
        if (!turnId || !conversationId || this.requests.size >= this.limit) return Promise.resolve('UNAVAILABLE');
        if (signal?.aborted) return Promise.resolve('CANCELLED');
        return new Promise(resolve => {
            const id = randomUUID(), token = randomBytes(32).toString('hex');
            const item = { id, token, turnId, conversationId, decision: null, done: false };
            this.requests.set(id, item);
            const finish = outcome => {
                if (item.done) return;
                item.done = true;
                clearTimeout(item.timer); signal?.removeEventListener('abort', item.abort);
                resolve(outcome);
                // Keep a short tombstone for idempotent acknowledgements; never rerun a callback.
                item.cleanup = setTimeout(() => this.requests.delete(id), this.timeoutMs);
                item.cleanup.unref?.();
            };
            item.finish = finish;
            item.abort = () => finish('CANCELLED');
            signal?.addEventListener('abort', item.abort, { once: true });
            item.timer = setTimeout(() => finish('EXPIRED'), this.timeoutMs);
            item.timer.unref?.();
            try { onRequest({ requestId: id, token, turnId, conversationId, preview }); }
            catch (_) { finish('UNAVAILABLE'); }
        });
    }
    decide(body, { origin } = {}) {
        if (origin) return { ok: false, status: 403, error: 'Подтверждение доступно из приложения' };
        const item = this.requests.get(body.requestId);
        const supplied = typeof body.token === 'string' ? Buffer.from(body.token) : Buffer.alloc(0);
        const expected = item ? Buffer.from(item.token) : Buffer.alloc(0);
        if (!item || supplied.length !== expected.length || !timingSafeEqual(supplied, expected) ||
            item.turnId !== body.turnId || item.conversationId !== body.conversationId) {
            return { ok: false, status: 404, error: 'Запрос разрешения не найден' };
        }
        if (!['APPROVED', 'DENIED'].includes(body.decision)) return { ok: false, status: 400, error: 'Неверное решение' };
        if (item.done) return item.decision === body.decision ? { ok: true, decision: item.decision }
            : { ok: false, status: 409, error: 'Запрос уже завершён или устарел' };
        item.decision = body.decision;
        item.finish(body.decision);
        return { ok: true, decision: item.decision };
    }
}

async function callWithApproval({ broker, call, serverId, toolName, args, options, turnId, conversationId, onRequest }) {
    // Snapshot once: a mutable object cannot change the approved action while awaiting a decision.
    const serialized = JSON.stringify(args || {});
    const original = JSON.parse(serialized);
    const first = await call(serverId, toolName, JSON.parse(serialized), options);
    if (first?.ok || first?.errorCode !== 'tool_needs_approval') return first;
    if (serialized.length > 16000) return { ok: false, errorCode: 'approval_preview_too_large', error: 'Аргументы слишком велики для подтверждения' };
    const outcome = await broker.ask({ turnId, conversationId, signal: options?.signal,
        preview: '', onRequest: request => onRequest(request, original) });
    if (outcome !== 'APPROVED' || options?.signal?.aborted) return { ok: false,
        ...(outcome === 'DENIED' ? { approvalDecision: 'DENIED' } : {}),
        errorCode: outcome === 'DENIED' ? 'tool_denied' : outcome === 'EXPIRED' ? 'approval_expired' : 'approval_cancelled',
        error: 'Разрешение не получено: отказ, отмена или истечение времени' };
    // The registry rechecks explicit deny and current configuration. No permission is written.
    const result = await call(serverId, toolName, JSON.parse(serialized), { ...options, userInitiated: true });
    return { ...result, approvalDecision: 'APPROVED' };
}
module.exports = { ToolApprovalBroker, callWithApproval, approvalPreview };
