'use strict';
// ---------------------------------------------------------------------------
// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): собственный исполнитель lifecycle
// hooks по ПРОВЕРЕННОМУ официальному контракту — builtin/skills/
// agy-customizations/docs/hooks.md (hooks.json, type: "command", stdin JSON /
// stdout JSON, события SessionStart/PreToolUse/PostToolUse/PreInvocation/
// PostInvocation/Stop).
//
// Политика «недопустимые/неподдерживаемые hooks не запускать молча»:
//  - неподдерживаемый тип handler'а (не "command"), неизвестное событие,
//    отсутствующая команда, некорректный matcher, выключенный hook
//    (enabled: false), запрет права — ВСЕГДА попадают в явный список refusals
//    и никогда не исполняются;
//  - мост не имеет интерактивного подтверждения: решение "ask"/"force_ask"
//    честно превращается в отказ с причиной, а не в молчаливый прогон;
//  - решение пользователя (Stop / USER_CANCELED) приоритетно: hook'и не могут
//    принудительно продолжить отменённый ход.
//
// Рабочий каталог: по контракту hooks.md команда выполняется в каталоге,
// содержащем hooks.json (cwd = каталог конфига). Изоляция рабочего каталога
// применяется к скриптам навыков/локальным программам (lib/script-runner.js).
// Окружение: передаётся из process.env за вычетом DSH_* и имён, похожих на
// секреты (TOKEN/SECRET/PASSWORD/…); значения секретов в результаты не попадают.
// ---------------------------------------------------------------------------
const path = require('node:path');
const { spawn } = require('node:child_process');

const HOOK_EVENTS = ['SessionStart', 'PreToolUse', 'PostToolUse', 'PreInvocation', 'PostInvocation', 'Stop'];
const TOOL_EVENTS = new Set(['PreToolUse', 'PostToolUse']);
const FLAT_EVENTS = new Set(['SessionStart', 'PreInvocation', 'PostInvocation', 'Stop']);
const DEFAULT_TIMEOUT_SEC = 30;
const MAX_TIMEOUT_SEC = 600;

const SECRETISH_ENV_RE = /(TOKEN|SECRET|PASSWORD|CREDENTIAL|APIKEY|API_KEY|PRIVATE)/i;

function isSecretishEnvName(name) {
    return name.startsWith('DSH_') || SECRETISH_ENV_RE.test(name);
}

function buildHookEnv(baseEnv, extraEnv = {}) {
    const out = {};
    for (const [k, v] of Object.entries(baseEnv || {})) {
        if (!isSecretishEnvName(k)) out[k] = v;
    }
    for (const [k, v] of Object.entries(extraEnv)) {
        // Даже явные extras не проносят имена, похожие на секреты.
        if (!isSecretishEnvName(k) && v != null) out[k] = String(v);
    }
    return out;
}

// «~ раскрывается в домашний каталог» (hooks.md, Hook Handler Fields).
function expandHome(command, homeDir) {
    if (!homeDir) return command;
    const home = String(homeDir).replace(/[\\/]+$/, '');
    return String(command).replace(/(^|[\s"'=(])~(?=[\\/]|$)/g, (m, pre) => pre + home);
}

function normalizeHandler(rawHandler, ctx) {
    const { hookName, event, sourcePath } = ctx;
    const refusalBase = { hookName, event, sourcePath };
    if (!rawHandler || typeof rawHandler !== 'object') {
        return { handler: null, refusal: { ...refusalBase, code: 'invalid_handler', reason: 'handler не является объектом — не запускается' } };
    }
    const type = rawHandler.type == null ? 'command' : String(rawHandler.type);
    if (type !== 'command') {
        return {
            handler: null,
            refusal: {
                ...refusalBase,
                code: 'unsupported_handler_type',
                reason: `тип hook'а "${type}" не поддерживается (поддерживается только type: "command") — не запускается`
            }
        };
    }
    const command = rawHandler.command == null ? '' : String(rawHandler.command).trim();
    if (!command) {
        return { handler: null, refusal: { ...refusalBase, code: 'missing_command', reason: 'hook без обязательного поля command — не запускается' } };
    }
    let timeoutSec = DEFAULT_TIMEOUT_SEC;
    if (rawHandler.timeout != null) {
        const t = Number(rawHandler.timeout);
        if (Number.isFinite(t) && t > 0) timeoutSec = Math.min(t, MAX_TIMEOUT_SEC);
    }
    return {
        handler: { type: 'command', command, timeoutSec, rawType: type },
        refusal: null
    };
}

function normalizeGroup(rawGroup, ctx, flatExpected) {
    const refusals = [];
    const handlers = [];
    if (!rawGroup || typeof rawGroup !== 'object') {
        refusals.push({ ...ctx, code: 'invalid_group', reason: 'запись события не является объектом — не запускается' });
        return { groups: [], refusals };
    }
    if (flatExpected) {
        // Плоская форма: список handler-объектов напрямую (hooks.md, таблица).
        const { handler, refusal } = normalizeHandler(rawGroup, ctx);
        if (handler) handlers.push(handler);
        if (refusal) refusals.push(refusal);
        return { groups: [{ matcher: null, handlers }], refusals };
    }
    // Группированная форма для PreToolUse/PostToolUse: {matcher, hooks: [...]}.
    const matcherRaw = rawGroup.matcher == null ? '*' : String(rawGroup.matcher);
    const inner = Array.isArray(rawGroup.hooks) ? rawGroup.hooks : null;
    if (!inner) {
        // Объект-handler в позиции группы — разбираем как handler, чтобы отказ
        // был точным (unsupported_handler_type / missing_command).
        if ('command' in rawGroup || 'type' in rawGroup) {
            const { handler, refusal } = normalizeHandler(rawGroup, ctx);
            return { groups: [{ matcher: matcherRaw, handlers: handler ? [handler] : [] }], refusals: refusal ? [refusal] : [] };
        }
        refusals.push({ ...ctx, code: 'invalid_group', reason: 'для события нужна группа {matcher, hooks: [...]} — не запускается' });
        return { groups: [], refusals };
    }
    for (const h of inner) {
        const { handler, refusal } = normalizeHandler(h, ctx);
        if (handler) handlers.push(handler);
        if (refusal) refusals.push(refusal);
    }
    return { groups: [{ matcher: matcherRaw, handlers }], refusals };
}

// Две официальные формы конфига:
//  1) Antigravity (docs/hooks.md): {"<hookName>": {"enabled"?, "<Event>": [...]}}
//  2) Claude-совместимый settings-блок hooks: {"<Event>": [{matcher, hooks}]}
// Форма 2 распознаётся, когда ключ верхнего уровня — имя поддерживаемого
// события. Неизвестные ключи формы 1 — именованные hooks; неизвестные события
// внутри — явный отказ (не молчаливый пропуск).
function parseHooksConfig(raw, { sourcePath = null, origin = 'config' } = {}) {
    const namedHooks = [];
    const refusals = [];
    const issues = [];
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        issues.push(`${sourcePath || origin}: hooks.json должен быть JSON-объектом`);
        return { namedHooks, refusals, issues };
    }
    const keys = Object.keys(raw);
    const looksLikeEventMap = keys.length > 0 && keys.every(k => HOOK_EVENTS.includes(k));

    const pushNamed = (name, body) => {
        const ctxBase = { hookName: name, sourcePath, origin };
        if (!body || typeof body !== 'object' || Array.isArray(body)) {
            refusals.push({ ...ctxBase, event: null, code: 'invalid_hook', reason: 'конфигурация hook\'а не является объектом — не запускается' });
            return;
        }
        const enabled = body.enabled == null ? true : Boolean(body.enabled);
        const events = {};
        for (const [event, value] of Object.entries(body)) {
            if (event === 'enabled') continue;
            if (!HOOK_EVENTS.includes(event)) {
                refusals.push({
                    ...ctxBase,
                    event,
                    code: 'unsupported_event',
                    reason: `событие "${event}" не входит в поддерживаемые (${HOOK_EVENTS.join(', ')}) — не запускается`
                });
                continue;
            }
            const flatExpected = FLAT_EVENTS.has(event);
            const list = Array.isArray(value) ? value : [value];
            const groups = [];
            for (const item of list) {
                const { groups: g, refusals: r } = normalizeGroup(item, { ...ctxBase, event }, flatExpected);
                groups.push(...g);
                refusals.push(...r);
            }
            events[event] = groups;
        }
        namedHooks.push({ name, enabled, sourcePath, origin, events });
    };

    if (looksLikeEventMap) {
        pushNamed(`hooks@${sourcePath || origin}`, raw);
    } else {
        for (const key of keys) {
            pushNamed(key, raw[key]);
        }
    }
    return { namedHooks, refusals, issues };
}

function loadHooksFromSources(sources = [], { readFileSync } = {}) {
    const readFile = readFileSync || ((p) => require('node:fs').readFileSync(p, 'utf8'));
    const merged = { namedHooks: [], refusals: [], issues: [] };
    for (const src of sources) {
        if (!src || !src.path) continue;
        let raw;
        try {
            raw = JSON.parse(readFile(src.path));
        } catch (err) {
            merged.issues.push(`${src.path}: не удалось разобрать hooks.json: ${err.message}`);
            continue;
        }
        const parsed = parseHooksConfig(raw, { sourcePath: src.path, origin: src.origin || 'config' });
        // Слияние: несколько именованных hooks исполняются последовательно
        // (hooks.md, «Merging»).
        merged.namedHooks.push(...parsed.namedHooks);
        merged.refusals.push(...parsed.refusals);
        merged.issues.push(...parsed.issues);
    }
    return merged;
}

function matcherMatches(matcher, toolName) {
    if (matcher == null || matcher === '' || matcher === '*') return true;
    let re;
    try {
        re = new RegExp(matcher);
    } catch (_) {
        return { error: `некорректный matcher "${matcher}" — не запускается` };
    }
    return re.test(String(toolName || ''));
}

// План исполнения события: что запустить и что отказано (всё видимо).
function collectEventPlan(hooks, event, { toolName = null } = {}) {
    const runnable = [];
    const refusals = [];
    for (const named of hooks || []) {
        const base = { hookName: named.name, sourcePath: named.sourcePath, origin: named.origin, event };
        if (!named.enabled) {
            refusals.push({ ...base, code: 'disabled', reason: `hook "${named.name}" выключен (enabled: false) — не запускается` });
            continue;
        }
        const groups = (named.events && named.events[event]) || [];
        for (const group of groups) {
            if (TOOL_EVENTS.has(event)) {
                const m = matcherMatches(group.matcher, toolName);
                if (m && typeof m === 'object' && m.error) {
                    refusals.push({ ...base, code: 'invalid_matcher', reason: m.error });
                    continue;
                }
                if (!m) continue; // matcher не совпал — это не отказ, а фильтр
            }
            for (const handler of group.handlers) {
                runnable.push({
                    ...base,
                    matcher: group.matcher,
                    handler,
                    cwd: named.sourcePath ? path.dirname(named.sourcePath) : process.cwd()
                });
            }
        }
    }
    return { runnable, refusals };
}

// ---------------------------------------------------------------------------
// Запуск handler'а (реальный дочерний процесс): stdin JSON → stdout JSON.
// ---------------------------------------------------------------------------

function defaultRunCommand({ command, cwd, env, timeoutMs, stdinData = null }) {
    return new Promise((resolve) => {
        const isWin = process.platform === 'win32';
        // Контракт hooks.md: команда выполняется через «sh -c» на Unix и
        // «cmd /c» на Windows — shell: true даёт ровно эту семантику.
        let child;
        try {
            child = spawn(command, [], { shell: true, cwd, env, windowsHide: true });
        } catch (err) {
            resolve({ exitCode: null, stdout: '', stderr: String(err && err.message || err), timedOut: false, spawnError: String(err && err.message || err) });
            return;
        }
        // Контракт: hook получает JSON на stdin.
        if (stdinData != null && child.stdin) {
            try {
                child.stdin.on('error', () => {});
                child.stdin.write(String(stdinData));
                child.stdin.end();
            } catch (_) {}
        } else if (child.stdin) {
            try { child.stdin.end(); } catch (_) {}
        }
        let stdout = '';
        let stderr = '';
        let timedOut = false;
        let settled = false;
        const timer = setTimeout(() => {
            timedOut = true;
            try {
                if (isWin) {
                    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
                } else {
                    child.kill('SIGKILL');
                }
            } catch (_) {}
        }, Math.max(1, timeoutMs));
        child.stdout.on('data', (d) => { stdout += d.toString('utf8'); });
        child.stderr.on('data', (d) => { stderr += d.toString('utf8'); });
        const finish = (result) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(result);
        };
        child.on('error', (err) => finish({ exitCode: null, stdout, stderr, timedOut, spawnError: String(err && err.message || err) }));
        child.on('close', (code) => finish({ exitCode: code, stdout, stderr, timedOut, spawnError: null }));
    });
}

async function runHookHandler(handler, input, { cwd, homeDir, env, runProcess } = {}) {
    const started = Date.now();
    const command = expandHome(handler.command, homeDir || process.env.USERPROFILE || process.env.HOME);
    const timeoutMs = Math.round((handler.timeoutSec || DEFAULT_TIMEOUT_SEC) * 1000);
    const run = runProcess || defaultRunCommand;
    let proc;
    try {
        proc = await run({
            command,
            cwd: cwd || process.cwd(),
            env: env || buildHookEnv(process.env),
            timeoutMs,
            stdinData: JSON.stringify(input)
        });
    } catch (err) {
        return {
            status: 'error',
            hookName: null,
            command,
            cwd: cwd || null,
            output: null,
            rawStdout: '',
            stderr: String(err && err.message || err),
            exitCode: null,
            timedOut: false,
            durationMs: Date.now() - started
        };
    }
    const rawStdout = String(proc.stdout || '');
    let output = null;
    let status = 'ok';
    if (proc.timedOut) {
        status = 'timeout';
    } else if (proc.spawnError) {
        status = 'error';
    } else if (proc.exitCode !== 0 && proc.exitCode != null) {
        status = 'error';
    }
    const trimmed = rawStdout.trim();
    if (trimmed) {
        try {
            const parsedOut = JSON.parse(trimmed);
            if (parsedOut && typeof parsedOut === 'object') output = parsedOut;
            else status = status === 'ok' ? 'bad-json' : status;
        } catch (_) {
            status = status === 'ok' ? 'bad-json' : status;
        }
    } else if (status === 'ok') {
        status = 'bad-json'; // контракт требует JSON на stdout
    }
    return {
        status,
        command,
        cwd: cwd || null,
        output,
        rawStdout,
        stderr: String(proc.stderr || ''),
        exitCode: proc.exitCode == null ? null : proc.exitCode,
        timedOut: Boolean(proc.timedOut),
        spawnError: proc.spawnError || null,
        durationMs: Date.now() - started
    };
}

// ---------------------------------------------------------------------------
// Контракты входа (hooks.md, Input/Output Contract): camelCase, protojson.
// ---------------------------------------------------------------------------

function buildCommonFields(ctx = {}) {
    const common = {
        conversationId: String(ctx.conversationId || ''),
        workspacePaths: Array.isArray(ctx.workspacePaths) ? ctx.workspacePaths.map(String) : [],
        modelName: String(ctx.modelName || 'auto'),
        lastUserInput: String(ctx.lastUserInput || '')
    };
    if (ctx.transcriptPath) common.transcriptPath = String(ctx.transcriptPath);
    if (ctx.artifactDirectoryPath) common.artifactDirectoryPath = String(ctx.artifactDirectoryPath);
    return common;
}

function buildHookInput(event, ctx = {}) {
    const common = buildCommonFields(ctx);
    switch (event) {
        case 'PreToolUse':
            return {
                toolCall: { name: String((ctx.toolCall && ctx.toolCall.name) || ''), args: (ctx.toolCall && ctx.toolCall.args) || {} },
                stepIdx: Number(ctx.stepIdx) || 0,
                ...common
            };
        case 'PostToolUse': {
            const out = {
                toolCall: { name: String((ctx.toolCall && ctx.toolCall.name) || ''), args: (ctx.toolCall && ctx.toolCall.args) || {} },
                stepIdx: Number(ctx.stepIdx) || 0,
                ...common
            };
            if (ctx.error != null) out.error = String(ctx.error);
            return out;
        }
        case 'PreInvocation':
        case 'PostInvocation':
            return {
                invocationNum: Number(ctx.invocationNum) || 0,
                initialNumSteps: Number(ctx.initialNumSteps) || 0,
                ...common
            };
        case 'Stop': {
            const out = {
                executionNum: Number(ctx.executionNum) || 0,
                terminationReason: String(ctx.terminationReason || ''),
                fullyIdle: ctx.fullyIdle == null ? true : Boolean(ctx.fullyIdle),
                ...common
            };
            if (ctx.error != null) out.error = String(ctx.error);
            return out;
        }
        case 'SessionStart':
        default:
            return { ...common };
    }
}

// ---------------------------------------------------------------------------
// Агрегация выхода по контрактам событий.
// ---------------------------------------------------------------------------

function collectInjectSteps(output, acc, refusals, base) {
    const steps = Array.isArray(output && output.injectSteps) ? output.injectSteps : null;
    if (!steps) return;
    for (const step of steps) {
        if (!step || typeof step !== 'object') {
            refusals.push({ ...base, code: 'invalid_inject_step', reason: 'injectSteps: шаг не является объектом — пропущен видимо' });
            continue;
        }
        if (step.systemMessage && typeof step.systemMessage === 'object' && typeof step.systemMessage.systemMessage === 'string') {
            acc.push({ kind: 'systemMessage', text: step.systemMessage.systemMessage });
        } else if (typeof step.userMessage === 'string') {
            acc.push({ kind: 'userMessage', text: step.userMessage });
        } else if (typeof step.ephemeralMessage === 'string') {
            acc.push({ kind: 'ephemeralMessage', text: step.ephemeralMessage });
        } else {
            refusals.push({ ...base, code: 'invalid_inject_step', reason: 'injectSteps: неизвестный вид шага — пропущен видимо' });
        }
    }
}

function combinePreToolUseDecision(results) {
    let decision = null;
    let reason = '';
    let notice = null;
    const overwrite = {};
    const permissionOverrides = [];
    for (const r of results) {
        const out = r.output || {};
        const d = out.decision == null ? null : String(out.decision);
        if (out.reason) reason = String(out.reason);
        if (Array.isArray(out.permissionOverrides)) {
            for (const p of out.permissionOverrides) permissionOverrides.push(String(p));
        }
        if (out.overwrite && typeof out.overwrite === 'object' && !Array.isArray(out.overwrite)) {
            for (const [k, v] of Object.entries(out.overwrite)) overwrite[k] = v;
            const keys = Object.keys(out.overwrite);
            if (keys.length > 0) {
                notice = `hook "${r.hookName}" переписал аргументы инструмента (ключи: ${keys.join(', ')})`;
            }
        }
        if (d === 'deny') {
            decision = 'deny';
        } else if (d === 'ask' || d === 'force_ask') {
            // Мост не имеет интерактивного подтверждения — честный отказ, не прогон.
            decision = 'deny';
            reason = reason || `hook "${r.hookName}" требует подтверждения (${d}) — мост не имеет интерактивного подтверждения`;
        } else if (d === 'allow') {
            if (decision == null) decision = 'allow';
        } else if (d != null) {
            decision = 'deny';
            reason = reason || `hook "${r.hookName}" вернул неизвестное решение "${d}" — исполнение блокируется`;
        }
    }
    if (decision == null) decision = 'allow'; // без решения — по умолчанию разрешено
    return { decision, reason, notice, overwrite, permissionOverrides };
}

// Полный прогон события: план → последовательный запуск → агрегация выхода.
async function runEventHooks(hooks, event, inputCtx, opts = {}) {
    const { toolName = null, homeDir, env, runProcess, permissions } = opts;
    // Matcher сверяется с именем инструмента: берём явный opts.toolName, иначе
    // имя из контракта входа (toolCall.name).
    const effectiveToolName = toolName != null
        ? toolName
        : ((inputCtx && inputCtx.toolCall && inputCtx.toolCall.name) || null);
    const plan = collectEventPlan(hooks, event, { toolName: effectiveToolName });
    const refusals = plan.refusals.slice();
    const results = [];
    // Право запуска shell-кода плагинов: оба реальных ключа прав (Этап 4/5).
    const hooksAllowed = permissions
        ? (permissions.pcMcpToolsExec !== false && permissions.pcTerminalExec !== false)
        : true;
    if (!hooksAllowed) {
        for (const item of plan.runnable) {
            refusals.push({
                hookName: item.hookName,
                event,
                sourcePath: item.sourcePath,
                code: 'permission_denied',
                reason: 'запуск hook\'ов запрещён правами (pcPluginsMcpSkillsAccess/pcPowerShellExecution) — не запускается'
            });
        }
        return { event, ran: results, refusals, decision: null, injectSteps: [], terminationBehavior: '', stopDecision: null };
    }
    for (const item of plan.runnable) {
        const input = buildHookInput(event, inputCtx);
        const r = await runHookHandler(item.handler, input, {
            cwd: item.cwd,
            homeDir,
            env,
            runProcess
        });
        results.push({
            hookName: item.hookName,
            event,
            sourcePath: item.sourcePath,
            matcher: item.matcher,
            ...r
        });
        if (r.status === 'timeout') {
            refusals.push({
                hookName: item.hookName,
                event,
                sourcePath: item.sourcePath,
                code: 'timeout',
                reason: `hook "${item.hookName}" превысил тайм-аут (${item.handler.timeoutSec} с) и остановлен`
            });
        } else if (r.status === 'bad-json') {
            refusals.push({
                hookName: item.hookName,
                event,
                sourcePath: item.sourcePath,
                code: 'bad-json',
                reason: `hook "${item.hookName}" не вернул корректный JSON на stdout — результат проигнорирован видимо`
            });
        } else if (r.status === 'error') {
            refusals.push({
                hookName: item.hookName,
                event,
                sourcePath: item.sourcePath,
                code: 'error',
                reason: `hook "${item.hookName}" завершился ошибкой (exit=${r.exitCode})`
            });
        }
    }

    const injectSteps = [];
    if (event === 'PreToolUse') {
        const combined = combinePreToolUseDecision(results);
        return { event, ran: results, refusals, decision: combined, injectSteps, terminationBehavior: '', stopDecision: null };
    }
    if (event === 'Stop') {
        let shouldContinue = false;
        let reason = '';
        for (const r of results) {
            const out = r.output || {};
            if (String(out.decision || '') === 'continue') {
                shouldContinue = true;
                if (out.reason) reason = String(out.reason);
            }
        }
        return { event, ran: results, refusals, decision: null, injectSteps, terminationBehavior: '', stopDecision: { shouldContinue, reason } };
    }
    if (event === 'PostInvocation') {
        // terminate жёстче force_continue: при конфликте побеждает terminate.
        let terminationBehavior = '';
        for (const r of results) {
            const tb = String((r.output || {}).terminationBehavior || '');
            if (tb === 'terminate') terminationBehavior = 'terminate';
            else if (tb === 'force_continue' && terminationBehavior !== 'terminate') terminationBehavior = 'force_continue';
        }
        for (const r of results) collectInjectSteps(r.output, injectSteps, refusals, { hookName: r.hookName, event, sourcePath: r.sourcePath, code: 'inject' });
        return { event, ran: results, refusals, decision: null, injectSteps, terminationBehavior, stopDecision: null };
    }
    // SessionStart / PreInvocation / PostToolUse
    for (const r of results) collectInjectSteps(r.output, injectSteps, refusals, { hookName: r.hookName, event, sourcePath: r.sourcePath, code: 'inject' });
    return { event, ran: results, refusals, decision: null, injectSteps, terminationBehavior: '', stopDecision: null };
}

module.exports = {
    HOOK_EVENTS,
    TOOL_EVENTS,
    FLAT_EVENTS,
    DEFAULT_TIMEOUT_SEC,
    isSecretishEnvName,
    buildHookEnv,
    expandHome,
    parseHooksConfig,
    loadHooksFromSources,
    collectEventPlan,
    matcherMatches,
    defaultRunCommand,
    runHookHandler,
    buildCommonFields,
    buildHookInput,
    combinePreToolUseDecision,
    runEventHooks
};
