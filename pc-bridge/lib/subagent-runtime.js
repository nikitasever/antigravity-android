'use strict';
const { randomUUID } = require('node:crypto');

// Bounded independent analysis workers. The injected runner owns CLI permissions.
class SubagentRuntime {
    constructor({ run, limit = 2, timeoutMs = 180000, onChange = () => {} }) {
        this.run = run;
        this.limit = limit;
        this.timeoutMs = timeoutMs;
        this.onChange = onChange;
        this.jobs = [];
        this.active = 0;
        this.requestIds = new Map();
    }
    dispatch({ task, model, requestId = randomUUID(), mode = 'analysis' }) {
        if (!['analysis', 'code'].includes(mode)) throw new Error('Unknown task mode');
        if (this.requestIds.has(requestId)) return this.snapshot(this.requestIds.get(requestId));
        if (String(task || '').length > 8000) throw new Error('Задача превышает 8000 символов');
        if (!String(task || '').trim()) throw new Error('Задача не задана');
        if (this.jobs.some(j => j.settling || ['queued', 'running'].includes(j.status))) throw new Error('Предыдущая задача ещё выполняется');
        this.jobs = [
            { name: mode === 'code' ? 'Разработчик' : 'Исследователь', role: mode === 'code'
                ? 'Предложи минимальную реализацию задачи и проверь её.' : 'Найди факты, связанные файлы и ограничения задачи.' },
            { name: 'Рецензент', role: 'Независимо проверь риски, крайние случаи и способы проверки. Если готовишь изменения, предложи свой независимый вариант.' }
        ].map(profile => ({ id: randomUUID(), ...profile, model, mode, task: String(task).trim(),
            batchId: requestId, status: 'queued', result: '', error: '', controller: null }));
        this.requestIds.set(requestId, this.jobs);
        if (this.requestIds.size > 8) this.requestIds.delete(this.requestIds.keys().next().value);
        this.changed();
        this.pump();
        return this.snapshot();
    }
    changed() { this.onChange(this.snapshot()); }
    snapshot(jobs = this.jobs) {
        return { batchId: jobs[0]?.batchId || '', swarmActive: jobs.some(j => j.settling || ['queued', 'running'].includes(j.status)),
            strategy: 'PARALLEL_ANALYSIS', agents: jobs.map(j => ({
                id: j.id, name: j.name, mention: '@' + j.id, role: j.role, model: j.model,
                isLeader: false, status: j.status, currentStep: j.task,
                result: j.result, error: j.error, mode: j.mode, review: j.review, settling: Boolean(j.settling),
                logs: [j.error || j.result || (j.status === 'queued' ? 'В очереди' : j.status === 'cancelled' ? 'Отменено' : 'Анализ задачи')]
            })) };
    }
    snapshotFor(requestId) { return this.snapshot(this.requestIds.get(requestId) || []); }
    cancel(requestId = '') {
        const jobs = requestId ? (this.requestIds.get(requestId) || []) : this.jobs;
        for (const job of jobs) {
            if (!['queued', 'running'].includes(job.status)) continue;
            job.status = 'cancelled';
            job.controller?.abort();
        }
        this.changed();
        return this.snapshot(jobs);
    }
    pump() {
        while (this.active < this.limit) {
            const job = this.jobs.find(j => j.status === 'queued');
            if (!job) break;
            this.start(job);
        }
    }
    async start(job) {
        this.active++;
        job.settling = true;
        job.status = 'running';
        job.controller = new AbortController();
        this.changed();
        let timer;
        // Keep the slot until the executor really settles, even after a timeout.
        const execution = (async () => this.run({ ...job, signal: job.controller.signal }))();
        // A cancelled job must never pick up a late review or result: only
        // terminal completed/failed jobs may keep the review the executor
        // produced (a partial review after a real error is legitimate detail).
        const settled = execution.then(result => { if (job.status !== 'cancelled' && result?.review) job.review = result.review; },
            error => { if (job.status !== 'cancelled' && error?.review) job.review = error.review; });
        const timeout = new Promise((_, reject) => {
            timer = setTimeout(() => {
                job.controller.abort();
                reject(new Error('Превышено время анализа'));
            }, this.timeoutMs);
        });
        try {
            const result = await Promise.race([execution, timeout]);
            if (job.status !== 'cancelled') {
                const text = typeof result === 'string' ? result : result?.text;
                if (!String(text || '').trim()) throw new Error('Исполнитель вернул пустой результат');
                job.result = String(text).slice(0, 64000);
                job.status = 'completed';
            }
        } catch (error) {
            if (job.status !== 'cancelled') { job.error = String(error.message || error); job.status = 'failed'; }
        } finally {
            clearTimeout(timer);
            this.changed();
            await settled;
            job.settling = false;
            job.controller = null;
            this.active--;
            this.changed();
            this.pump();
        }
    }
}
module.exports = { SubagentRuntime };
