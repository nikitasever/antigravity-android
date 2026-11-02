'use strict';
// Этап 3 (EXTENSIONS_INTEGRATION_PLAN.md): реальный MCP-клиент (JSON-RPC 2.0).
//
// Транспорты: stdio (дочерний процесс), Streamable HTTP (POST + Mcp-Session-Id)
// и устаревший HTTP+SSE (событие endpoint + POST сообщений) — совместимость со
// старым форматом по потребностям реестра. Жизненный цикл: initialize →
// notifications/initialized → tools/list. Статус «connected» выставляется
// ТОЛЬКО после успешного полного рукопожатия; список инструментов берётся
// исключительно из живого ответа tools/list (возможно — постранично), ничего
// не угадывается по имени сервера.
//
// Авторизация: параметры (env, headers, Bearer-токен) пробрасываются на транспорт
// и не попадают ни в журналы, ни в публичные ответы API — все тексты ошибок
// проходят redactSecrets, describeDescriptor возвращает только имена ключей.
//
// Ошибки: McpClientError с кодами connect_failed (отказ соединения/запуска),
// handshake_timeout, request_timeout, process_exit (падение процесса),
// http_error, auth_failed (401/403 — например, истёкший вход), protocol_error
// (нарушение схемы JSON-RPC/MCP), version_unsupported, tool_error (отказ
// инструмента), cancelled (Stop), not_connected, closed, invalid_descriptor.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { killProcessTree } = require('./subagent-cli');
const { parseMcpServerEntry } = require('./extension-formats');

// Версии протокола, которые клиент реально поддерживает. Клиент запрашивает
// предпочтительную, сервер отвечает своей; незнакомая версия — честный отказ.
const SUPPORTED_PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const PREFERRED_PROTOCOL_VERSION = '2025-06-18';
const CLIENT_INFO = { name: 'antigravity-pc-bridge', version: '1.0.0' };
const DEFAULT_HANDSHAKE_TIMEOUT_MS = 10000;
const DEFAULT_REQUEST_TIMEOUT_MS = 30000;
const MAX_LIST_PAGES = 50;
const KNOWN_TRANSPORTS = ['stdio', 'streamable-http', 'sse'];

function resolveStdioLaunch(command, args, { platform = process.platform, nodeExecutable = process.execPath } = {}) {
    if (platform === 'win32' && /^(npx|npm)(\.cmd)?$/i.test(command)) {
        const cli = path.join(path.dirname(nodeExecutable), 'node_modules', 'npm', 'bin', command.toLowerCase().startsWith('npx') ? 'npx-cli.js' : 'npm-cli.js');
        if (!fs.existsSync(cli)) throw new McpClientError('connect_failed', 'Для MCP требуется npm рядом с Node.js; пакет не запускался');
        // .cmd cannot be spawned directly on Windows. Use the bundled JS CLI,
        // preserving arguments without a shell or command string interpolation.
        return { command: nodeExecutable, args: [cli, ...args] };
    }
    return { command, args };
}

class McpClientError extends Error {
    constructor(code, message, details = {}) {
        super(message);
        this.name = 'McpClientError';
        this.code = code;
        this.details = details;
    }
}

// ---------------------------------------------------------------------------
// Редактирование секретов: значения env/headers/токена не должны утекать ни в
// журналы, ни в ответы API. Заменяются все вхождения значения.
// ---------------------------------------------------------------------------

function redactSecrets(text, secrets) {
    let out = text == null ? '' : String(text);
    for (const raw of secrets || []) {
        const s = String(raw == null ? '' : raw);
        if (s.length < 4) continue; // слишком короткие значения не трогаем
        out = out.split(s).join('[скрыто]');
    }
    return out;
}

// Редактирование результата tools/call: текстовые блоки — данные сервера,
// в них может попасть известный секрет (например, инструмент печатает env).
function redactToolResult(result, secrets) {
    // QA/Security, High #3: рекурсивная маскировка ВСЕХ полей результата
    // (content, structuredContent, image/resource, вложенные объекты/массивы).
    return redactDeep(result, secrets);
}

// Рекурсивный обход произвольного JSON-подобного значения: строки маскируются
// redactSecrets, объекты/массивы обходятся до листьев. Буферы/TypedArray не
// трогаем (бинарные данные — не текст секретов).
function redactDeep(value, secrets) {
    if (value == null) return value;
    if (typeof value === 'string') return redactSecrets(value, secrets);
    if (typeof value === 'object') {
        if (Buffer.isBuffer(value) || ArrayBuffer.isView(value)) return value;
        if (Array.isArray(value)) return value.map(v => redactDeep(v, secrets));
        const out = {};
        for (const [k, v] of Object.entries(value)) out[k] = redactDeep(v, secrets);
        return out;
    }
    return value;
}

function collectSecrets(descriptor) {
    const secrets = [];
    if (!descriptor || typeof descriptor !== 'object') return secrets;
    const push = (v) => {
        if (typeof v === 'string' && v) secrets.push(v);
    };
    for (const [k, v] of Object.entries(descriptor.env || {})) {
        push(v);
    }
    for (const [k, v] of Object.entries(descriptor.headers || {})) {
        push(v);
        // Bearer-токен эхоируется сервером без префикса — вырезаем и его.
        if (typeof v === 'string') {
            const m = v.match(/^Bearer\s+(.+)$/i);
            if (m) push(m[1].trim());
        }
    }
    push(descriptor.authToken);
    // QA/Security, High #3: секреты/токены в аргументах команды (после флагов
    // --token / --access-token / --api-key / --password, в --header и в формате
    // --flag=value) маскируются так же, как env/headers.
    const args = Array.isArray(descriptor.args) ? descriptor.args.map(String) : [];
    const SECRET_FLAG_RE = /^--?(?:token|access[-_]?token|api[-_]?key|apikey|auth[-_]?token|password|secret|client[-_]?secret)$/i;
    const HEADER_ARG_RE = /^--?(?:header|headers|h)$/i;
    for (let i = 0; i < args.length; i++) {
        const arg = args[i];
        const eq = arg.match(/^(--?[^=]+)=(.*)$/s);
        if (eq) {
            if (SECRET_FLAG_RE.test(eq[1].trim())) push(eq[2]);
            continue;
        }
        if (SECRET_FLAG_RE.test(arg) && i + 1 < args.length) {
            push(args[i + 1]);
            i++;
            continue;
        }
        if (HEADER_ARG_RE.test(arg) && i + 1 < args.length) {
            const hv = args[i + 1];
            push(hv);
            const m = String(hv).match(/^\s*[^:]+:\s*(.+)$/s);
            if (m) push(m[1].trim());
            const bm = String(hv).match(/Bearer\s+(.+)$/i);
            if (bm) push(bm[1].trim());
            i++;
            continue;
        }
        // Bearer-токен отдельным аргументом.
        const bm = arg.match(/^Bearer\s+(.+)$/i);
        if (bm) push(bm[1].trim());
    }
    // Токены в URL (query/userinfo) тоже не должны утекать в тексты ошибок.
    if (descriptor.url) {
        try {
            const u = new URL(String(descriptor.url));
            push(u.username);
            push(u.password);
            for (const [, qv] of u.searchParams) push(qv);
        } catch (_) {}
    }
    return secrets;
}

// Безопасное описание дескриптора для API/журналов: без значений env/headers,
// URL — без userinfo и query (там могут быть токены). QA/Security, High #3:
// значения секретных аргументов команды (--token и т.п.) маскируются.
function describeDescriptor(descriptor) {
    const d = descriptor && typeof descriptor === 'object' ? descriptor : {};
    let urlInfo = null;
    if (d.url) {
        try {
            const u = new URL(String(d.url));
            urlInfo = {
                origin: u.origin,
                pathname: u.pathname,
                hasQuery: Boolean(u.search),
                hasUserinfo: Boolean(u.username || u.password)
            };
        } catch (_) {
            urlInfo = { invalid: true };
        }
    }
    const secrets = collectSecrets(d);
    const maskArgs = (arr) => arr.map(a => {
        let out = String(a);
        for (const s of secrets) {
            if (s.length >= 4 && out.includes(s)) out = out.split(s).join('[скрыто]');
        }
        return out;
    });
    return {
        id: d.id ? String(d.id) : '',
        transport: d.transport || 'stdio',
        command: d.command ? String(d.command) : '',
        args: Array.isArray(d.args) ? maskArgs(d.args.map(String)) : [],
        url: urlInfo,
        envKeys: Object.keys(d.env || {}),
        headerKeys: Object.keys(d.headers || {}),
        authPresent: Boolean(
            (d.headers && Object.keys(d.headers).length > 0) ||
            (d.env && Object.keys(d.env).length > 0) ||
            d.authToken
        )
    };
}

function safeError(err, secrets) {
    const code = err && err.code ? String(err.code) : 'connect_failed';
    return {
        code,
        message: redactSecrets(err && err.message ? err.message : String(err), secrets)
    };
}

// ---------------------------------------------------------------------------
// Минимальный разбор SSE-потока (события `event:`/`data:` через пустую строку).
// ---------------------------------------------------------------------------

function createSseAccumulator(onEvent) {
    let buffer = '';
    return {
        push(chunk) {
            buffer += chunk;
            let idx;
            while ((idx = buffer.indexOf('\n\n')) !== -1) {
                const rawEvent = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 2);
                const lines = rawEvent.split(/\r?\n/).filter(l => l !== '');
                let event = 'message';
                const dataLines = [];
                for (const line of lines) {
                    if (line.startsWith('event:')) event = line.slice(6).trim();
                    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
                }
                if (dataLines.length > 0) onEvent({ event, data: dataLines.join('\n') });
            }
        }
    };
}

// ---------------------------------------------------------------------------
// Транспорты. Единый контракт:
//   start(opts) → Promise (готов принимать сообщения)
//   send(msg, { signal }) → Promise (для HTTP — до разбора ответа)
//   close()
// Входящие сообщения: hooks.onMessage(msg); сбой: hooks.onError(err);
// завершение: hooks.onClose(info).
// ---------------------------------------------------------------------------

class StdioTransport {
    constructor(descriptor, hooks) {
        this.command = descriptor.command;
        this.args = Array.isArray(descriptor.args) ? descriptor.args.map(String) : [];
        this.env = descriptor.env && typeof descriptor.env === 'object' ? descriptor.env : {};
        this.cwd = descriptor.cwd ? String(descriptor.cwd) : undefined;
        this.hooks = hooks;
        this.child = null;
        this.stdoutBuffer = '';
        this.stderrTail = '';
        this.exited = false;
    }

    start() {
        return new Promise((resolve, reject) => {
            let settled = false;
            try {
                const launch = resolveStdioLaunch(this.command, this.args);
                this.child = spawn(launch.command, launch.args, {
                    env: { ...process.env, ...this.env },
                    cwd: this.cwd,
                    windowsHide: true,
                    stdio: ['pipe', 'pipe', 'pipe']
                });
            } catch (err) {
                reject(new McpClientError('connect_failed', `Не удалось запустить MCP-сервер: ${err.message}`));
                return;
            }
            this.child.once('spawn', () => {
                if (settled) return;
                settled = true;
                resolve();
            });
            this.child.once('error', (err) => {
                if (!settled) {
                    settled = true;
                    reject(new McpClientError('connect_failed', `Процесс MCP-сервера не запущен: ${err.message}`));
                    return;
                }
                this.hooks.onError(new McpClientError('connect_failed', `Ошибка процесса MCP-сервера: ${err.message}`));
            });
            this.child.stdout.setEncoding('utf8');
            this.child.stdout.on('data', (chunk) => this.#onStdout(chunk));
            this.child.stderr.setEncoding('utf8');
            this.child.stderr.on('data', (chunk) => {
                this.stderrTail = (this.stderrTail + chunk).slice(-2000);
            });
            this.child.once('exit', (code, signal) => {
                this.exited = true;
                this.hooks.onClose({ code, signal, stderr: this.stderrTail });
            });
        });
    }

    #onStdout(chunk) {
        this.stdoutBuffer += chunk;
        let idx;
        while ((idx = this.stdoutBuffer.indexOf('\n')) !== -1) {
            const line = this.stdoutBuffer.slice(0, idx).replace(/\r$/, '');
            this.stdoutBuffer = this.stdoutBuffer.slice(idx + 1);
            if (!line.trim()) continue;
            let msg;
            try {
                msg = JSON.parse(line);
            } catch (_) {
                // stdio-транспорт MCP: каждая строка — ровно одно JSON-сообщение.
                this.hooks.onError(new McpClientError('protocol_error', 'Строка от MCP-сервера не является JSON-RPC сообщением'));
                return;
            }
            this.hooks.onMessage(msg);
        }
    }

    send(msg) {
        if (!this.child || this.exited) {
            throw new McpClientError('process_exit', 'Процесс MCP-сервера завершён');
        }
        try {
            this.child.stdin.write(JSON.stringify(msg) + '\n');
        } catch (err) {
            throw new McpClientError('process_exit', `Не удалось записать в stdin MCP-сервера: ${err.message}`);
        }
        return Promise.resolve();
    }

    close() {
        if (!this.child) return Promise.resolve();
        const child = this.child;
        this.child = null;
        return killProcessTree(child, spawn);
    }
}

class StreamableHttpTransport {
    constructor(descriptor, hooks) {
        this.url = String(descriptor.url || '');
        this.hooks = hooks;
        this.sessionId = null;
        this.authHeaders = buildAuthHeaders(descriptor);
    }

    start() {
        let u;
        try {
            u = new URL(this.url);
        } catch (_) {
            return Promise.reject(new McpClientError('invalid_descriptor', 'Некорректный URL MCP-сервера'));
        }
        if (u.protocol !== 'http:' && u.protocol !== 'https:') {
            return Promise.reject(new McpClientError('invalid_descriptor', 'Streamable HTTP требует http(s) URL'));
        }
        return Promise.resolve();
    }

    async send(msg, { signal } = {}) {
        const isNotification = msg.id === undefined;
        const headers = {
            'content-type': 'application/json',
            accept: 'application/json, text/event-stream',
            ...this.authHeaders
        };
        if (this.sessionId) headers['mcp-session-id'] = this.sessionId;
        let resp;
        try {
            resp = await fetch(this.url, {
                method: 'POST',
                headers,
                body: JSON.stringify(msg),
                signal,
                redirect: 'error'
            });
        } catch (err) {
            if (err && (err.name === 'AbortError' || err.code === 'ABORT_ERR')) {
                throw new McpClientError('cancelled', 'Запрос отменён');
            }
            throw new McpClientError('connect_failed', `Соединение с MCP-сервером не установлено: ${err.message}`);
        }
        const sid = resp.headers.get('mcp-session-id');
        if (sid) this.sessionId = sid;
        if (resp.status === 401 || resp.status === 403) {
            await resp.text().catch(() => '');
            throw new McpClientError('auth_failed', `Отказ в авторизации (HTTP ${resp.status}) — проверьте токен доступа`, { status: resp.status });
        }
        if (!resp.ok) {
            const bodyText = await resp.text().catch(() => '');
            throw new McpClientError('http_error', `HTTP ${resp.status}: ${String(bodyText).slice(0, 300)}`, { status: resp.status });
        }
        const contentType = String(resp.headers.get('content-type') || '').toLowerCase();
        if (resp.status === 202 || resp.status === 204 || (isNotification && !contentType.includes('json') && !contentType.includes('event-stream'))) {
            await resp.text().catch(() => '');
            return;
        }
        if (contentType.includes('text/event-stream')) {
            const reader = resp.body && typeof resp.body.getReader === 'function' ? resp.body.getReader() : null;
            if (!reader) {
                const text = await resp.text().catch(() => '');
                this.#deliverJsonText(text);
                return;
            }
            const acc = createSseAccumulator(({ data }) => this.#deliverJsonText(data));
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                acc.push(Buffer.from(value).toString('utf8'));
            }
            return;
        }
        const text = await resp.text();
        this.#deliverJsonText(text);
    }

    #deliverJsonText(text) {
        const trimmed = String(text || '').trim();
        if (!trimmed) return;
        let msg;
        try {
            msg = JSON.parse(trimmed);
        } catch (_) {
            throw new McpClientError('protocol_error', 'Ответ MCP-сервера не является JSON');
        }
        if (Array.isArray(msg)) {
            for (const m of msg) this.hooks.onMessage(m);
        } else {
            this.hooks.onMessage(msg);
        }
    }

    close() {
        this.sessionId = null;
        return Promise.resolve();
    }
}

class SseTransport {
    constructor(descriptor, hooks) {
        this.url = String(descriptor.url || '');
        this.hooks = hooks;
        this.authHeaders = buildAuthHeaders(descriptor);
        this.abort = new AbortController();
        this.endpointUrl = null;
        this.closed = false;
        this.streamError = null;
    }

    async start({ timeoutMs = DEFAULT_HANDSHAKE_TIMEOUT_MS } = {}) {
        let base;
        try {
            base = new URL(this.url);
        } catch (_) {
            throw new McpClientError('invalid_descriptor', 'Некорректный URL MCP-сервера');
        }
        if (base.protocol !== 'http:' && base.protocol !== 'https:') {
            throw new McpClientError('invalid_descriptor', 'HTTP+SSE требует http(s) URL');
        }
        let resp;
        try {
            resp = await fetch(this.url, {
                method: 'GET',
                headers: { accept: 'text/event-stream', ...this.authHeaders },
                redirect: 'error',
                signal: this.abort.signal
            });
        } catch (err) {
            throw new McpClientError('connect_failed', `SSE-поток не открыт: ${err.message}`);
        }
        if (resp.status === 401 || resp.status === 403) {
            await resp.text().catch(() => '');
            throw new McpClientError('auth_failed', `Отказ в авторизации (HTTP ${resp.status}) — проверьте токен доступа`, { status: resp.status });
        }
        if (!resp.ok) {
            const bodyText = await resp.text().catch(() => '');
            throw new McpClientError('http_error', `SSE: HTTP ${resp.status}: ${String(bodyText).slice(0, 300)}`, { status: resp.status });
        }
        this.streamDone = this.#consume(resp);
        try {
            await new Promise((resolve, reject) => {
                const timer = setTimeout(() => {
                    reject(new McpClientError('handshake_timeout', `SSE: событие endpoint не получено за ${timeoutMs} мс`));
                }, timeoutMs);
                this.onEndpointReady = () => {
                    clearTimeout(timer);
                    resolve();
                };
                const fail = (err) => {
                    clearTimeout(timer);
                    reject(err);
                };
                this.onStreamErrorBeforeEndpoint = fail;
                if (this.endpointUrl) {
                    clearTimeout(timer);
                    resolve();
                }
            });
        } finally {
            // После рукопожатия ошибки потока идут в общий onError.
            this.onEndpointReady = null;
            this.onStreamErrorBeforeEndpoint = null;
        }
    }

    async #consume(resp) {
        const reader = resp.body && typeof resp.body.getReader === 'function' ? resp.body.getReader() : null;
        if (!reader) {
            const err = new McpClientError('protocol_error', 'SSE: тело потока недоступно');
            if (this.onStreamErrorBeforeEndpoint) this.onStreamErrorBeforeEndpoint(err);
            else this.hooks.onError(err);
            return;
        }
        const acc = createSseAccumulator(({ event, data }) => {
            if (event === 'endpoint') {
                try {
                    const endpoint = new URL(data.trim(), this.url);
                    if (endpoint.origin !== new URL(this.url).origin || endpoint.username || endpoint.password || endpoint.hash) throw new Error('cross-origin endpoint');
                    this.endpointUrl = endpoint.toString();
                } catch (_) {
                    const err = new McpClientError('protocol_error', 'SSE: endpoint должен принадлежать исходному серверу');
                    if (this.onStreamErrorBeforeEndpoint) this.onStreamErrorBeforeEndpoint(err);
                    else this.hooks.onError(err);
                    return;
                }
                if (this.onEndpointReady) this.onEndpointReady();
                return;
            }
            if (event === 'message' || event === '') {
                let msg;
                try {
                    msg = JSON.parse(data);
                } catch (_) {
                    const err = new McpClientError('protocol_error', 'SSE: событие message не является JSON');
                    if (this.onStreamErrorBeforeEndpoint) this.onStreamErrorBeforeEndpoint(err);
                    else this.hooks.onError(err);
                    return;
                }
                this.hooks.onMessage(msg);
            }
        });
        try {
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                acc.push(Buffer.from(value).toString('utf8'));
            }
        } catch (err) {
            if (!this.closed) {
                const wrapped = new McpClientError('connect_failed', `SSE-поток прерван: ${err.message}`);
                if (this.onStreamErrorBeforeEndpoint) this.onStreamErrorBeforeEndpoint(wrapped);
                else this.hooks.onError(wrapped);
                return;
            }
        }
        if (!this.closed) {
            this.hooks.onClose({ reason: 'sse-stream-ended' });
        }
    }

    async send(msg, { signal } = {}) {
        if (!this.endpointUrl) {
            throw new McpClientError('not_connected', 'SSE: endpoint для отправки ещё не получен');
        }
        const combined = combineSignals(signal, this.abort.signal);
        let resp;
        try {
            resp = await fetch(this.endpointUrl, {
                method: 'POST',
                headers: { 'content-type': 'application/json', ...this.authHeaders },
                body: JSON.stringify(msg),
                signal: combined.signal,
                redirect: 'error'
            });
        } catch (err) {
            if (err && (err.name === 'AbortError' || err.code === 'ABORT_ERR')) {
                throw new McpClientError('cancelled', 'Запрос отменён');
            }
            throw new McpClientError('connect_failed', `SSE: не удалось отправить сообщение: ${err.message}`);
        } finally {
            combined.cleanup();
        }
        if (resp.status === 401 || resp.status === 403) {
            await resp.text().catch(() => '');
            throw new McpClientError('auth_failed', `Отказ в авторизации (HTTP ${resp.status}) — проверьте токен доступа`, { status: resp.status });
        }
        // Ответ придёт событием message по SSE; тело POST — служебное (202).
        await resp.text().catch(() => '');
        if (!resp.ok && resp.status !== 202) {
            throw new McpClientError('http_error', `SSE: HTTP ${resp.status}`, { status: resp.status });
        }
    }

    close() {
        this.closed = true;
        try {
            this.abort.abort();
        } catch (_) {}
        return Promise.resolve();
    }
}

function buildAuthHeaders(descriptor) {
    const headers = {};
    for (const [k, v] of Object.entries(descriptor.headers || {})) {
        headers[String(k).toLowerCase()] = String(v);
    }
    if (descriptor.authToken && !headers.authorization) {
        headers.authorization = `Bearer ${descriptor.authToken}`;
    }
    return headers;
}

function combineSignals(a, b) {
    if (!a) return { signal: b, cleanup: () => {} };
    if (!b) return { signal: a, cleanup: () => {} };
    const controller = new AbortController();
    const onA = () => controller.abort();
    const onB = () => controller.abort();
    if (a.aborted || b.aborted) controller.abort();
    a.addEventListener('abort', onA, { once: true });
    b.addEventListener('abort', onB, { once: true });
    return {
        signal: controller.signal,
        cleanup: () => {
            a.removeEventListener('abort', onA);
            b.removeEventListener('abort', onB);
        }
    };
}

function createTransport(descriptor, hooks) {
    switch (descriptor.transport) {
        case 'stdio':
            return new StdioTransport(descriptor, hooks);
        case 'streamable-http':
        case 'http':
            return new StreamableHttpTransport(descriptor, hooks);
        case 'sse':
            return new SseTransport(descriptor, hooks);
        default:
            throw new McpClientError('unsupported_transport', `Неподдерживаемый транспорт MCP: ${descriptor.transport}`);
    }
}

// ---------------------------------------------------------------------------
// MCP-клиент: RPC-ядро и жизненный цикл.
// ---------------------------------------------------------------------------

class McpClient {
    constructor(descriptor, options = {}) {
        if (!descriptor || typeof descriptor !== 'object') {
            throw new McpClientError('invalid_descriptor', 'Не задан дескриптор MCP-сервера');
        }
        const transport = String(descriptor.transport || 'stdio');
        if (!KNOWN_TRANSPORTS.includes(transport)) {
            throw new McpClientError('unsupported_transport', `Неподдерживаемый транспорт MCP: ${transport}`);
        }
        if (transport === 'stdio' && !descriptor.command) {
            throw new McpClientError('invalid_descriptor', 'Для stdio-транспорта нужна команда запуска');
        }
        if ((transport === 'streamable-http' || transport === 'sse') && !descriptor.url) {
            throw new McpClientError('invalid_descriptor', `Для транспорта ${transport} нужен URL`);
        }
        this.descriptor = { ...descriptor, transport };
        this.secrets = collectSecrets(this.descriptor);
        this.options = {
            protocolVersion: options.protocolVersion || PREFERRED_PROTOCOL_VERSION,
            handshakeTimeoutMs: clampTimeout(options.handshakeTimeoutMs, DEFAULT_HANDSHAKE_TIMEOUT_MS),
            requestTimeoutMs: clampTimeout(options.requestTimeoutMs, DEFAULT_REQUEST_TIMEOUT_MS)
        };
        this.state = 'idle';
        this.serverInfo = null;
        this.protocolVersion = '';
        this.capabilities = null;
        this.tools = [];
        this.resources = [];
        this.prompts = [];
        this.facets = {};
        this.lastError = null;
        this.toolsStale = false;
        this.#nextId = 1;
        this.#pending = new Map();
        this.#transport = null;
    }

    #nextId;
    #pending;
    #transport;

    // --- Низкоуровневый RPC-обмен -------------------------------------------

    #onMessage = (msg) => {
        if (!msg || typeof msg !== 'object') return;
        const hasId = msg.id !== undefined && msg.id !== null;
        if (hasId && (msg.result !== undefined || msg.error !== undefined)) {
            const pending = this.#pending.get(msg.id);
            if (!pending) return;
            this.#pending.delete(msg.id);
            clearTimeout(pending.timer);
            if (pending.signal && pending.onAbort) pending.signal.removeEventListener('abort', pending.onAbort);
            if (msg.error) {
                const errObj = msg.error && typeof msg.error === 'object' ? msg.error : {};
                pending.reject(new McpClientError('rpc_error', redactSecrets(String(errObj.message || 'Ошибка JSON-RPC'), this.secrets), {
                    code: typeof errObj.code === 'number' ? errObj.code : null
                }));
            } else {
                pending.resolve(msg.result);
            }
            return;
        }
        if (typeof msg.method === 'string') {
            if (hasId) {
                // Запрос от сервера: поддерживаем только ping.
                if (msg.method === 'ping') {
                    this.#sendRaw({ jsonrpc: '2.0', id: msg.id, result: {} }).catch(() => {});
                } else {
                    this.#sendRaw({
                        jsonrpc: '2.0',
                        id: msg.id,
                        error: { code: -32601, message: `Метод не поддерживается клиентом: ${msg.method}` }
                    }).catch(() => {});
                }
            } else if (msg.method === 'notifications/tools/list_changed') {
                this.toolsStale = true;
            }
        }
    };

    #onError = (err) => {
        const wrapped = err instanceof McpClientError ? err : new McpClientError('connect_failed', redactSecrets(err.message || String(err), this.secrets));
        this.lastError = safeError(wrapped, this.secrets);
        this.#failAllPending(wrapped);
    };

    #onClose = (info) => {
        if (this.state === 'closed') return;
        const exitDetails = info && (info.code !== undefined || info.signal) ? ` (код ${info.code === null || info.code === undefined ? 'нет' : info.code}${info.signal ? `, сигнал ${info.signal}` : ''})` : '';
        const err = new McpClientError('process_exit', `Соединение с MCP-сервером закрыто${exitDetails}`);
        this.#failAllPending(err);
        if (this.state === 'connected' || this.state === 'connecting') {
            this.state = 'failed';
            this.lastError = safeError(err, this.secrets);
        }
    };

    #failAllPending(err) {
        for (const [, pending] of this.#pending) {
            clearTimeout(pending.timer);
            if (pending.signal && pending.onAbort) pending.signal.removeEventListener('abort', pending.onAbort);
            pending.reject(err);
        }
        this.#pending.clear();
    }

    #sendRaw(msg) {
        if (!this.#transport) {
            return Promise.reject(new McpClientError('closed', 'Транспорт закрыт'));
        }
        try {
            return Promise.resolve(this.#transport.send(msg, {}));
        } catch (err) {
            return Promise.reject(err instanceof McpClientError ? err : new McpClientError('connect_failed', redactSecrets(err.message, this.secrets)));
        }
    }

    request(method, params, { timeoutMs, signal, timeoutCode = 'request_timeout' } = {}) {
        const ms = clampTimeout(timeoutMs, this.options.requestTimeoutMs);
        return new Promise((resolve, reject) => {
            const id = this.#nextId++;
            const controller = new AbortController();
            const timer = setTimeout(() => {
                if (!this.#pending.has(id)) return;
                this.#pending.delete(id);
                try {
                    controller.abort();
                } catch (_) {}
                this.#sendRaw({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: id, reason: 'тайм-аут клиента' } }).catch(() => {});
                reject(new McpClientError(timeoutCode, `Тайм-аут ${ms} мс: ${method}`));
            }, ms);
            const onAbort = () => {
                if (!this.#pending.has(id)) return;
                this.#pending.delete(id);
                clearTimeout(timer);
                try {
                    controller.abort();
                } catch (_) {}
                this.#sendRaw({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: id, reason: 'отменено клиентом' } }).catch(() => {});
                reject(new McpClientError('cancelled', 'Вызов отменён (Stop)'));
            };
            if (signal) {
                if (signal.aborted) {
                    clearTimeout(timer);
                    queueMicrotask(() => reject(new McpClientError('cancelled', 'Вызов отменён (Stop)')));
                    return;
                }
                signal.addEventListener('abort', onAbort, { once: true });
            }
            this.#pending.set(id, { resolve, reject, timer, signal, onAbort, method });
            const failSend = (err) => {
                if (!this.#pending.has(id)) return;
                this.#pending.delete(id);
                clearTimeout(timer);
                if (signal && onAbort) signal.removeEventListener('abort', onAbort);
                reject(err instanceof McpClientError ? err : new McpClientError('connect_failed', redactSecrets(err.message || String(err), this.secrets)));
            };
            try {
                const sendPromise = this.#transport && this.#transport.send
                    ? this.#transport.send({ jsonrpc: '2.0', id, method, params }, { signal: controller.signal })
                    : Promise.reject(new McpClientError('closed', 'Транспорт закрыт'));
                Promise.resolve(sendPromise).catch(failSend);
            } catch (err) {
                failSend(err);
            }
        });
    }

    // --- Жизненный цикл -----------------------------------------------------

    async connect() {
        if (this.state === 'connected') return this.summary();
        if (this.state === 'connecting') {
            throw new McpClientError('not_connected', 'Подключение уже выполняется');
        }
        this.state = 'connecting';
        this.lastError = null;
        this.tools = [];
        this.resources = [];
        this.prompts = [];
        this.facets = {};
        this.toolsStale = false;
        // События устаревшего транспорта (например, поздний exit старого
        // процесса после reconnect) не должны трогать текущую сессию.
        const hooks = {
            onMessage: (msg) => {
                if (this.#transport === transport) this.#onMessage(msg);
            },
            onError: (err) => {
                if (this.#transport === transport) this.#onError(err);
            },
            onClose: (info) => {
                if (this.#transport === transport) this.#onClose(info);
            }
        };
        const transport = createTransport(this.descriptor, hooks);
        this.#transport = transport;
        try {
            await this.#transport.start({ timeoutMs: this.options.handshakeTimeoutMs });
            // 1) initialize: согласование версии протокола.
            const initResult = await this.request('initialize', {
                protocolVersion: this.options.protocolVersion,
                capabilities: {
                    tools: { listChanged: true },
                    resources: { listChanged: true },
                    prompts: { listChanged: true }
                },
                clientInfo: CLIENT_INFO
            }, { timeoutMs: this.options.handshakeTimeoutMs, timeoutCode: 'handshake_timeout' });
            if (!initResult || typeof initResult !== 'object' ||
                typeof initResult.protocolVersion !== 'string' ||
                !initResult.serverInfo || typeof initResult.serverInfo.name !== 'string') {
                throw new McpClientError('protocol_error', 'initialize: сервер вернул ответ без protocolVersion/serverInfo');
            }
            const serverVersion = initResult.protocolVersion;
            if (serverVersion !== this.options.protocolVersion && !SUPPORTED_PROTOCOL_VERSIONS.includes(serverVersion)) {
                throw new McpClientError('version_unsupported',
                    `Сервер выбрал неподдерживаемую версию протокола MCP: ${serverVersion}`,
                    { serverVersion, supported: SUPPORTED_PROTOCOL_VERSIONS });
            }
            this.serverInfo = { name: String(initResult.serverInfo.name), version: String(initResult.serverInfo.version || '') };
            this.protocolVersion = serverVersion;
            this.capabilities = initResult.capabilities && typeof initResult.capabilities === 'object' ? initResult.capabilities : {};
            // 2) initialized: подтверждение завершения согласования.
            await this.#sendRaw({ jsonrpc: '2.0', method: 'notifications/initialized' });
            // 3) tools/list — обязательно; список только из живого ответа.
            this.tools = await this.#listAll('tools/list', 'tools');
            this.facets.tools = 'ok';
            // resources/prompts — только при объявленной поддержке.
            if (this.capabilities.resources && typeof this.capabilities.resources === 'object') {
                try {
                    this.resources = await this.#listAll('resources/list', 'resources');
                    this.facets.resources = 'ok';
                } catch (err) {
                    this.facets.resources = `error: ${safeError(err, this.secrets).message}`;
                }
            } else {
                this.facets.resources = 'not_declared';
            }
            if (this.capabilities.prompts && typeof this.capabilities.prompts === 'object') {
                try {
                    this.prompts = await this.#listAll('prompts/list', 'prompts');
                    this.facets.prompts = 'ok';
                } catch (err) {
                    this.facets.prompts = `error: ${safeError(err, this.secrets).message}`;
                }
            } else {
                this.facets.prompts = 'not_declared';
            }
            this.state = 'connected';
            return this.summary();
        } catch (err) {
            this.state = 'failed';
            const wrapped = err instanceof McpClientError ? err : new McpClientError('connect_failed', redactSecrets(err.message || String(err), this.secrets));
            this.lastError = safeError(wrapped, this.secrets);
            await this.#closeTransport();
            throw wrapped;
        }
    }

    async #listAll(method, key) {
        const items = [];
        let cursor;
        for (let page = 0; page < MAX_LIST_PAGES; page++) {
            const result = await this.request(method, cursor ? { cursor } : {}, {});
            if (!result || typeof result !== 'object' || !Array.isArray(result[key])) {
                throw new McpClientError('protocol_error', `${method}: ответ сервера без массива ${key}`);
            }
            for (const item of result[key]) {
                if (!item || typeof item !== 'object' || typeof item.name !== 'string') {
                    throw new McpClientError('protocol_error', `${method}: элемент списка без name`);
                }
                items.push(item);
            }
            cursor = result.nextCursor;
            if (!cursor) return items;
        }
        throw new McpClientError('protocol_error', `${method}: слишком много страниц (${MAX_LIST_PAGES})`);
    }

    summary() {
        return {
            state: this.state,
            serverInfo: this.serverInfo,
            protocolVersion: this.protocolVersion,
            capabilities: this.capabilities,
            tools: this.tools.map(t => ({
                name: t.name,
                description: typeof t.description === 'string' ? t.description : '',
                inputSchema: t.inputSchema && typeof t.inputSchema === 'object' ? t.inputSchema : null
            })),
            resources: this.resources.map(r => ({
                uri: typeof r.uri === 'string' ? r.uri : '',
                name: r.name,
                description: typeof r.description === 'string' ? r.description : ''
            })),
            prompts: this.prompts.map(p => ({
                name: p.name,
                description: typeof p.description === 'string' ? p.description : ''
            })),
            facets: { ...this.facets },
            toolsStale: this.toolsStale
        };
    }

    async callTool(name, args = {}, { timeoutMs, signal } = {}) {
        if (this.state !== 'connected') {
            throw new McpClientError('not_connected', 'Вызов инструмента возможен только после реального MCP-рукопожатия');
        }
        if (!name || typeof name !== 'string') {
            throw new McpClientError('tool_error', 'Не указано имя инструмента');
        }
        let result;
        try {
            result = await this.request('tools/call', {
                name,
                arguments: args && typeof args === 'object' && !Array.isArray(args) ? args : {}
            }, { timeoutMs, signal });
        } catch (err) {
            if (err instanceof McpClientError && err.code === 'rpc_error') {
                // Отказ инструмента/сервера — честный tool_error с кодом JSON-RPC.
                throw new McpClientError('tool_error', err.message, err.details);
            }
            throw err;
        }
        if (!result || typeof result !== 'object' || !Array.isArray(result.content)) {
            throw new McpClientError('protocol_error', 'tools/call: ответ сервера без content');
        }
        return result;
    }

    async ping({ timeoutMs } = {}) {
        return this.request('ping', {}, { timeoutMs });
    }

    async listTools({ cursor } = {}) {
        return this.request('tools/list', cursor ? { cursor } : {}, {});
    }

    async listResources({ cursor } = {}) {
        return this.request('resources/list', cursor ? { cursor } : {}, {});
    }

    async listPrompts({ cursor } = {}) {
        return this.request('prompts/list', cursor ? { cursor } : {}, {});
    }

    // Переподключение: полный новый цикл рукопожатия.
    async reconnect() {
        await this.close();
        this.state = 'idle';
        return this.connect();
    }

    async #closeTransport() {
        if (this.#transport) {
            const t = this.#transport;
            this.#transport = null;
            try {
                await t.close();
            } catch (_) {}
        }
    }

    async close() {
        this.#failAllPending(new McpClientError('closed', 'Соединение закрыто клиентом'));
        await this.#closeTransport();
        if (this.state !== 'failed') this.state = 'closed';
    }
}

function clampTimeout(value, fallback) {
    const n = Number(value);
    if (!Number.isFinite(n) || n <= 0) return fallback;
    return Math.min(Math.max(Math.round(n), 100), 600000);
}

// ---------------------------------------------------------------------------
// Менеджер сессий: одна живая сессия на сервер, авто-переподключение перед
// вызовом, снимки состояния для API (без секретов).
// ---------------------------------------------------------------------------

class McpClientManager {
    constructor({ clientOptions } = {}) {
        this.clientOptions = clientOptions || {};
        this.sessions = new Map();
        this.descriptors = new Map();
        this.live = new Map();
        // QA/Security, Critical #2: очередь выполняющихся probe-операций по
        // ключу сервера. Параллельные probe()/callTool()/disconnect() одного
        // сервера ждут один общий промис и не оставляют осиротевших
        // дочерних stdio-процессов.
        this.inflight = new Map();
    }

    #key(idOrDescriptor) {
        if (typeof idOrDescriptor === 'string') return idOrDescriptor;
        return String(idOrDescriptor.id || idOrDescriptor.url || idOrDescriptor.command || '');
    }

    // Свежее рукопожатие при каждой проверке: initialize → initialized →
    // tools/list. connected — только при успехе всего цикла. Параллельные
    // вызовы для одного сервера разделяют один и тот же промис (inflight).
    async probe(descriptor, options = {}) {
        const id = this.#key(descriptor);
        if (!id) {
            return {
                ok: false,
                status: 'failed',
                error: 'Не задан идентификатор MCP-сервера',
                errorCode: 'invalid_descriptor',
                tools: []
            };
        }
        const existing = this.inflight.get(id);
        if (existing) return existing;

        const run = this.#runProbe(id, descriptor, options);
        this.inflight.set(id, run);
        try {
            return await run;
        } finally {
            if (this.inflight.get(id) === run) this.inflight.delete(id);
        }
    }

    async #runProbe(id, descriptor, options) {
        // Уборка предыдущей сессии БЕЗ ожидания inflight (мы и есть inflight).
        const prev = this.sessions.get(id);
        this.sessions.delete(id);
        this.live.delete(id);
        if (prev) await prev.close().catch(() => {});
        if (this.clientOptions.authorizeDescriptor) {
            try { descriptor = await this.clientOptions.authorizeDescriptor(descriptor); }
            catch { return { ok: false, id, status: 'failed', tools: [], errorCode: 'auth_failed', error: 'OAuth: обновление входа не удалось. Войдите повторно.' }; }
        }
        this.descriptors.set(id, descriptor);
        const client = new McpClient(descriptor, { ...this.clientOptions, ...options });
        const probedAt = new Date().toISOString();
        try {
            const summary = await client.connect();
            this.sessions.set(id, client);
            const record = {
                id,
                status: 'connected',
                transport: descriptor.transport,
                serverInfo: summary.serverInfo,
                protocolVersion: summary.protocolVersion,
                capabilities: summary.capabilities,
                tools: summary.tools.map(t => t.name),
                toolsDetailed: summary.tools,
                resources: summary.resources,
                prompts: summary.prompts,
                facets: summary.facets,
                error: '',
                errorCode: null,
                probedAt
            };
            this.live.set(id, record);
            return { ok: true, ...record };
        } catch (err) {
            const safe = safeError(err, client.secrets);
            if (safe.code === 'auth_failed' && err.details?.status === 401 &&
                !options.oauthRetried && this.clientOptions.onAuthFailure) {
                try {
                    if (await this.clientOptions.onAuthFailure(descriptor)) {
                        await client.close().catch(() => {});
                        return this.#runProbe(id, descriptor, { ...options, oauthRetried: true });
                    }
                } catch { /* fall through to the visible auth failure */ }
            }
            const record = {
                id,
                status: 'failed',
                transport: descriptor.transport,
                serverInfo: null,
                protocolVersion: '',
                capabilities: null,
                tools: [],
                toolsDetailed: [],
                resources: [],
                prompts: [],
                facets: {},
                error: safe.message,
                errorCode: safe.code,
                probedAt
            };
            this.live.set(id, record);
            return { ok: false, ...record };
        }
    }

    getLive(id) {
        return this.live.get(this.#key(id)) || null;
    }

    // Вызов инструмента. Если живой сессии нет — сначала переподключение
    // (рукопожатие), затем ровно один вызов (без автоматического повтора —
    // чтобы не выполнить побочный эффект дважды). При живой сессии в стадии
    // подключения (connecting) дожидается текущего подключения, а не стартует
    // повторный probe (Critical #2).
    async callTool(id, name, args = {}, options = {}) {
        const key = this.#key(id);
        if (this.clientOptions.authorizeDescriptor) {
            try {
                const raw = options.descriptor || this.descriptors.get(key);
                if (raw) {
                    const fresh = await this.clientOptions.authorizeDescriptor(raw);
                    options = { ...options, descriptor: fresh };
                    if (this.sessions.has(key) && this.descriptors.get(key)?.authToken !== fresh.authToken) {
                        const probe = await this.probe(fresh, options);
                        if (!probe.ok) return probe;
                    }
                }
            } catch {
                await this.disconnect(key);
                return { ok: false, errorCode: 'auth_failed', error: 'OAuth: обновление входа не удалось. Войдите повторно.' };
            }
        }
        let client = this.sessions.get(key);
        if (!client || client.state === 'connecting') {
            // Идёт (или вот-вот начнётся) probe — дожидаемся его результата.
            const inflight = this.inflight.get(key);
            if (inflight) {
                const probe = await inflight;
                if (!probe.ok) {
                    return { ok: false, error: probe.error || 'Соединение не установлено', errorCode: probe.errorCode || 'connect_failed' };
                }
                client = this.sessions.get(key);
            }
        }
        if (!client || client.state !== 'connected') {
            const descriptor = options.descriptor || this.descriptors.get(key);
            if (!descriptor) {
                return { ok: false, error: `MCP-сервер не зарегистрирован: ${key}`, errorCode: 'not_connected' };
            }
            const probe = await this.probe(descriptor, options);
            if (!probe.ok) {
                return { ok: false, error: probe.error || 'Соединение не установлено', errorCode: probe.errorCode || 'connect_failed' };
            }
            client = this.sessions.get(key);
        }
        try {
            const result = await client.callTool(name, args, options);
            // Тексты результата приходят от сервера — рекурсивно вырезаем
            // известные секреты (env/headers/токены из args) перед выдачей в API.
            return {
                ok: true,
                result: redactToolResult(result, client.secrets)
            };
        } catch (err) {
            const safe = safeError(err, client.secrets);
            if (safe.code === 'auth_failed' && err.details?.status === 401 && this.clientOptions.onAuthFailure) {
                // Mark credentials for refresh, but never replay tools/call:
                // the tool could have performed a side effect before failing.
                try { await this.clientOptions.onAuthFailure(this.descriptors.get(key)); } catch {}
            }
            return { ok: false, error: safe.message, errorCode: safe.code, details: err.details || null };
        }
    }

    async disconnect(id) {
        const key = this.#key(id);
        // Дожидаемся завершения выполняющегося probe, чтобы не осиротить
        // дочерний stdio-процесс (Critical #2).
        const inflight = this.inflight.get(key);
        if (inflight) {
            await inflight.catch(() => {});
            if (this.inflight.get(key) === inflight) this.inflight.delete(key);
        }
        const client = this.sessions.get(key);
        this.sessions.delete(key);
        this.live.delete(key);
        if (client) {
            await client.close().catch(() => {});
        }
    }

    async closeAll() {
        const ids = [...new Set([...this.sessions.keys(), ...this.inflight.keys()])];
        for (const id of ids) {
            await this.disconnect(id).catch(() => {});
        }
        this.sessions.clear();
        this.descriptors.clear();
        this.live.clear();
        this.inflight.clear();
    }
}

// ---------------------------------------------------------------------------
// Дескриптор из записи конфигурации (mcp_config.json / settings.json).
// Значения env/headers остаются в памяти процесса и не экспортируются.
// ---------------------------------------------------------------------------

function buildMcpDescriptor(name, cfg) {
    const parsed = parseMcpServerEntry(name, cfg);
    const transport = parsed.transport === 'http' ? 'streamable-http' : parsed.transport;
    const c = cfg && typeof cfg === 'object' ? cfg : {};
    return {
        id: String(name),
        transport,
        command: parsed.command,
        args: parsed.args,
        env: c.env && typeof c.env === 'object' ? { ...c.env } : {},
        cwd: c.cwd ? String(c.cwd) : undefined,
        url: parsed.url,
        headers: c.headers && typeof c.headers === 'object' ? { ...c.headers } : {},
        authToken: typeof c.authToken === 'string' && c.authToken ? c.authToken : null,
        disabled: parsed.disabled === true
    };
}

module.exports = {
    McpClientError,
    resolveStdioLaunch,
    McpClient,
    McpClientManager,
    buildMcpDescriptor,
    describeDescriptor,
    redactSecrets,
    collectSecrets,
    SUPPORTED_PROTOCOL_VERSIONS,
    PREFERRED_PROTOCOL_VERSION,
    DEFAULT_HANDSHAKE_TIMEOUT_MS,
    DEFAULT_REQUEST_TIMEOUT_MS
};
