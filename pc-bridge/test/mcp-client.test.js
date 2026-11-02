'use strict';
// Этап 3 (EXTENSIONS_INTEGRATION_PLAN.md): интеграционные тесты реального
// MCP-рукопожатия. Каждый транспорт проверяется полным циклом
// initialize → notifications/initialized → tools/list и реальным tools/call.
// Отдельно: отказ соединения, таймауты, падение процесса, истёкший вход (401),
// отмена (Stop), reconnect и отсутствие утечки секретов.
//
// stdio-тесты идут против настоящего mcp_sysinfo_server.js; HTTP/SSE-тесты —
// против фикстур, реализующих настоящий протокол (JSON-RPC 2.0 поверх
// Streamable HTTP и HTTP+SSE).

const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const {
    McpClient,
    McpClientManager,
    buildMcpDescriptor,
    describeDescriptor
} = require('../lib/mcp-client');

const NODE = process.execPath;
const SYSINFO_SERVER = path.join(__dirname, '..', 'mcp_sysinfo_server.js');
const SECRET_TOKEN = 'topsecret-token-123456';

function listen(server) {
    return new Promise((resolve) => {
        server.listen(0, '127.0.0.1', () => resolve(server.address().port));
    });
}

function closeServer(server) {
    return new Promise((resolve) => {
        if (server.closeAllConnections) server.closeAllConnections();
        server.close(() => resolve());
    });
}

// ---------------------------------------------------------------------------
// Фикстура Streamable HTTP MCP-сервера (настоящий протокол: initialize,
// Mcp-Session-Id, notifications, tools/list с pagination, tools/call).
// ---------------------------------------------------------------------------

async function startStreamableFixture(opts = {}) {
    const state = {
        requests: [],
        initializedSeen: false,
        cancelledNotifications: [],
        hangResponses: []
    };
    const tools = opts.tools || [
        {
            name: 'echo',
            description: 'возвращает аргументы',
            inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: [] }
        }
    ];
    const server = http.createServer((req, res) => {
        if (req.method !== 'POST') {
            res.writeHead(405);
            res.end();
            return;
        }
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
            let msg = {};
            try { msg = JSON.parse(body); } catch (_) {}
            const method = typeof msg.method === 'string' ? msg.method : '';
            state.requests.push({
                method,
                authorization: String(req.headers.authorization || ''),
                sessionSeen: String(req.headers['mcp-session-id'] || ''),
                cursor: msg.params && msg.params.cursor ? String(msg.params.cursor) : ''
            });
            if (opts.requireToken && req.headers.authorization !== `Bearer ${opts.requireToken}`) {
                res.writeHead(401, { 'content-type': 'application/json' });
                res.end(JSON.stringify({ error: 'unauthorized' }));
                return;
            }
            const send = (obj, extraHeaders = {}) => {
                res.writeHead(200, { 'content-type': 'application/json', ...extraHeaders });
                res.end(JSON.stringify(obj));
            };
            if (method === 'initialize') {
                if (opts.errorEcho) {
                    send({
                        jsonrpc: '2.0',
                        id: msg.id,
                        error: { code: -32000, message: `invalid token ${opts.errorEcho}` }
                    });
                    return;
                }
                const version = opts.versionEcho ||
                    (msg.params && msg.params.protocolVersion) || '2025-06-18';
                send({
                    jsonrpc: '2.0',
                    id: msg.id,
                    result: {
                        protocolVersion: version,
                        capabilities: { tools: {} },
                        serverInfo: { name: 'fixture-streamable', version: '0.1.0' }
                    }
                }, { 'mcp-session-id': 'sess-fixture-1' });
                return;
            }
            if (method.startsWith('notifications/')) {
                if (method === 'notifications/initialized') state.initializedSeen = true;
                if (method === 'notifications/cancelled') state.cancelledNotifications.push(msg.params || {});
                res.writeHead(202);
                res.end();
                return;
            }
            if (method === 'ping') {
                send({ jsonrpc: '2.0', id: msg.id, result: {} });
                return;
            }
            if (method === 'tools/list') {
                if (opts.toolsListHang) {
                    state.hangResponses.push(res);
                    return;
                }
                if (opts.paginate) {
                    if (!msg.params || !msg.params.cursor) {
                        send({ jsonrpc: '2.0', id: msg.id, result: { tools: tools.slice(0, 1), nextCursor: 'page-2' } });
                    } else {
                        send({ jsonrpc: '2.0', id: msg.id, result: { tools: tools.slice(1) } });
                    }
                    return;
                }
                send({ jsonrpc: '2.0', id: msg.id, result: { tools } });
                return;
            }
            if (method === 'tools/call') {
                const name = msg.params && msg.params.name;                if (name === 'hang') {
                    state.hangResponses.push(res);
                    return;
                }
                if (name === 'schema-broken') {
                    // Нарушение схемы MCP: у CallToolResult обязан быть content.
                    send({ jsonrpc: '2.0', id: msg.id, result: {} });
                    return;
                }
                if (name === 'refuse') {
                    send({ jsonrpc: '2.0', id: msg.id, error: { code: -32602, message: 'Некорректные аргументы инструмента' } });
                    return;
                }
                if (name === 'env-dump') {
                    send({
                        jsonrpc: '2.0',
                        id: msg.id,
                        result: {
                            content: [{ type: 'text', text: `env dump ${opts.secretEcho || ''}` }],
                            isError: false
                        }
                    });
                    return;
                }
                send({
                    jsonrpc: '2.0',
                    id: msg.id,
                    result: {
                        content: [{ type: 'text', text: 'ECHO:' + JSON.stringify((msg.params && msg.params.arguments) || {}) }],
                        isError: false
                    }
                });
                return;
            }
            if (msg.id !== undefined) {
                send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `Unknown method: ${method}` } });
            }
        });
    });
    const port = await listen(server);
    return {
        state,
        url: `http://127.0.0.1:${port}/mcp`,
        close: () => closeServer(server)
    };
}

// ---------------------------------------------------------------------------
// Фикстура устаревшего HTTP+SSE транспорта: GET /sse → событие endpoint,
// POST /message → ответы приходят событиями message по открытому потоку.
// ---------------------------------------------------------------------------

async function startSseFixture() {
    const state = { sseRes: null, requests: [] };
    const server = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://127.0.0.1');
        if (req.method === 'GET' && url.pathname === '/sse') {
            res.writeHead(200, {
                'content-type': 'text/event-stream',
                'cache-control': 'no-cache'
            });
            res.write('event: endpoint\ndata: /message?sessionId=s1\n\n');
            state.sseRes = res;
            return;
        }
        if (req.method === 'POST' && url.pathname === '/message') {
            let body = '';
            req.on('data', (c) => { body += c; });
            req.on('end', () => {
                let msg = {};
                try { msg = JSON.parse(body); } catch (_) {}
                state.requests.push({ method: typeof msg.method === 'string' ? msg.method : '' });
                res.writeHead(202);
                res.end();
                const send = (obj) => {
                    if (state.sseRes) {
                        state.sseRes.write(`event: message\ndata: ${JSON.stringify(obj)}\n\n`);
                    }
                };
                const method = typeof msg.method === 'string' ? msg.method : '';
                if (method === 'initialize') {
                    send({
                        jsonrpc: '2.0',
                        id: msg.id,
                        result: {
                            protocolVersion: msg.params.protocolVersion,
                            capabilities: { tools: {} },
                            serverInfo: { name: 'fixture-sse', version: '0.1.0' }
                        }
                    });
                } else if (method === 'tools/list') {
                    send({
                        jsonrpc: '2.0',
                        id: msg.id,
                        result: {
                            tools: [{ name: 'sse-echo', description: 'проверка SSE', inputSchema: { type: 'object' } }]
                        }
                    });
                } else if (method === 'tools/call') {
                    send({
                        jsonrpc: '2.0',
                        id: msg.id,
                        result: { content: [{ type: 'text', text: 'SSE-OK' }], isError: false }
                    });
                } else if (msg.id !== undefined) {
                    send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'unknown' } });
                }
            });
            return;
        }
        res.writeHead(404);
        res.end();
    });
    const port = await listen(server);
    return {
        state,
        url: `http://127.0.0.1:${port}/sse`,
        close: () => closeServer(server)
    };
}

// stdio-сервер, корректно завершающий рукопожатие, но падающий при tools/call.
function writeCrashyServer(dir) {
    const file = path.join(dir, 'crashy-mcp.js');
    fs.writeFileSync(file, [
        "const readline = require('readline');",
        "const rl = readline.createInterface({ input: process.stdin, terminal: false });",
        "function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }",
        "rl.on('line', (line) => {",
        "    let req; try { req = JSON.parse(line); } catch (_) { return; }",
        "    const { id, method, params } = req;",
        "    if (method === 'initialize') {",
        "        return send({ jsonrpc: '2.0', id, result: { protocolVersion: params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'crashy', version: '1.0.0' } } });",
        "    }",
        "    if (method === 'notifications/initialized' || method === 'initialized') return;",
        "    if (method === 'tools/list') {",
        "        return send({ jsonrpc: '2.0', id, result: { tools: [{ name: 'go', inputSchema: { type: 'object' } }] } });",
        "    }",
        "    if (method === 'tools/call') process.exit(7);",
        "    if (id !== undefined) send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'unknown' } });",
        "});"
    ].join('\n'), 'utf8');
    return file;
}

function sysinfoDescriptor() {
    return { id: 'sysinfo', transport: 'stdio', command: NODE, args: [SYSINFO_SERVER] };
}

// ---------------------------------------------------------------------------
// stdio: реальный сервер mcp_sysinfo_server.js
// ---------------------------------------------------------------------------

test('stdio: полное реальное рукопожатие initialize → initialized → tools/list', async (t) => {
    const client = new McpClient(sysinfoDescriptor(), { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 });
    t.after(() => client.close());
    const summary = await client.connect();
    assert.equal(client.state, 'connected');
    assert.equal(summary.serverInfo.name, 'antigravity-sysinfo-mcp');
    assert.ok(summary.protocolVersion, 'версия протокола согласована');
    assert.deepEqual(summary.tools.map(x => x.name), ['get_workstation_pulse'],
        'имена инструментов — только из живого ответа tools/list');
    assert.equal(summary.tools[0].inputSchema.type, 'object');
    assert.equal(summary.facets.tools, 'ok');
});

test('stdio: реальный tools/call и честный отказ неизвестного инструмента', async (t) => {
    const client = new McpClient(sysinfoDescriptor(), { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 });
    t.after(() => client.close());
    await client.connect();
    const result = await client.callTool('get_workstation_pulse', {});
    assert.equal(result.isError, false);
    const info = JSON.parse(result.content[0].text);
    assert.equal(info.mcpVerificationBadge, 'ANTIGRAVITY-MCP-VERIFIED-2026');
    assert.ok(info.hostname, 'телеметрия получена из живого вызова');
    await assert.rejects(() => client.callTool('no_such_tool', {}), (err) => {
        assert.equal(err.code, 'tool_error');
        return true;
    });
});

test('stdio: падение процесса при старте — отказ соединения без статуса connected', async () => {
    const client = new McpClient(
        { id: 'crashy', transport: 'stdio', command: NODE, args: ['-e', 'process.exit(3)'] },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    await assert.rejects(() => client.connect(), (err) => {
        assert.ok(['process_exit', 'connect_failed'].includes(err.code), `код ошибки: ${err.code}`);
        return true;
    });
    assert.equal(client.state, 'failed');
});

test('stdio: таймаут рукопожатия (сервер молчит) — handshake_timeout', async () => {
    const client = new McpClient(
        {
            id: 'silent',
            transport: 'stdio',
            command: NODE,
            args: ['-e', 'process.stdin.resume(); setInterval(() => {}, 1000);']
        },
        { handshakeTimeoutMs: 400 }
    );
    await assert.rejects(() => client.connect(), (err) => {
        assert.equal(err.code, 'handshake_timeout');
        return true;
    });
    assert.equal(client.state, 'failed');
    await client.close(); // убить зависший процесс
});

test('stdio: падение процесса после подключения — process_exit при вызове', async (t) => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-crashy-'));
    t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
    const script = writeCrashyServer(tmp);
    const client = new McpClient(
        { id: 'crashy2', transport: 'stdio', command: NODE, args: [script] },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    const summary = await client.connect();
    assert.deepEqual(summary.tools.map(x => x.name), ['go']);
    await assert.rejects(() => client.callTool('go', {}), (err) => {
        assert.equal(err.code, 'process_exit');
        assert.match(err.message, /код 7/, 'код выхода процесса сохранён в диагностику');
        return true;
    });
    assert.equal(client.state, 'failed');
});

test('stdio: reconnect после close() снова выполняет полное рукопожатие', async (t) => {
    const client = new McpClient(sysinfoDescriptor(), { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 });
    t.after(() => client.close());
    await client.connect();
    await client.close();
    assert.equal(client.state, 'closed');
    const summary = await client.reconnect();
    assert.equal(summary.state, 'connected');
    const result = await client.callTool('get_workstation_pulse', {});
    assert.equal(result.isError, false);
});

// ---------------------------------------------------------------------------
// Streamable HTTP
// ---------------------------------------------------------------------------

test('streamable-http: рукопожатие, Mcp-Session-Id, авторизация и tools/call', async (t) => {
    const fx = await startStreamableFixture({ requireToken: SECRET_TOKEN });
    t.after(fx.close);
    const client = new McpClient(
        {
            id: 'http-srv',
            transport: 'streamable-http',
            url: fx.url,
            headers: { Authorization: `Bearer ${SECRET_TOKEN}` }
        },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    const summary = await client.connect();
    assert.equal(summary.serverInfo.name, 'fixture-streamable');
    assert.deepEqual(summary.tools.map(x => x.name), ['echo'], 'tools — из живого tools/list');
    assert.equal(fx.state.initializedSeen, true, 'notifications/initialized доставлен');
    const listReqs = fx.state.requests.filter(r => r.method === 'tools/list');
    assert.ok(listReqs.length > 0 && listReqs.every(r => r.sessionSeen === 'sess-fixture-1'),
        'клиент повторно шлёт Mcp-Session-Id после initialize');
    const result = await client.callTool('echo', { text: 'привет' });
    assert.match(result.content[0].text, /привет/);
});

test('streamable-http: постраничный tools/list собирает все страницы по nextCursor', async (t) => {
    const fx = await startStreamableFixture({
        paginate: true,
        tools: [
            { name: 'tool-a', inputSchema: { type: 'object' } },
            { name: 'tool-b', inputSchema: { type: 'object' } }
        ]
    });
    t.after(fx.close);
    const client = new McpClient(
        { id: 'paged', transport: 'streamable-http', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    const summary = await client.connect();
    assert.deepEqual(summary.tools.map(x => x.name), ['tool-a', 'tool-b']);
    const cursors = fx.state.requests.filter(r => r.method === 'tools/list').map(r => r.cursor);
    assert.deepEqual(cursors, ['', 'page-2'], 'вторая страница запрошена с cursor');
});

test('streamable-http: отказ соединения (сервер отключён) — connect_failed', async () => {
    // Занимаем и освобождаем порт — адрес гарантированно без слушателя.
    const tmpServer = http.createServer();
    const port = await listen(tmpServer);
    await closeServer(tmpServer);
    const client = new McpClient(
        { id: 'down', transport: 'streamable-http', url: `http://127.0.0.1:${port}/mcp` },
        { handshakeTimeoutMs: 2000, requestTimeoutMs: 2000 }
    );
    await assert.rejects(() => client.connect(), (err) => {
        assert.equal(err.code, 'connect_failed');
        return true;
    });
    assert.equal(client.state, 'failed');
});

test('streamable-http: HTTP 401 (истёкший вход) — auth_failed без утечки токена', async (t) => {
    const fx = await startStreamableFixture({ requireToken: SECRET_TOKEN });
    t.after(fx.close);
    const client = new McpClient(
        {
            id: 'expired',
            transport: 'streamable-http',
            url: fx.url,
            headers: { Authorization: 'Bearer WRONG-TOKEN-VALUE' }
        },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    await assert.rejects(() => client.connect(), (err) => {
        assert.equal(err.code, 'auth_failed');
        assert.ok(!err.message.includes(SECRET_TOKEN) && !err.message.includes('WRONG-TOKEN-VALUE'),
            'значения токенов не попадают в текст ошибки');
        return true;
    });
});

test('секреты не утекают: describeDescriptor и ошибки редактируются', async (t) => {
    // Сервер намеренно эхоирует токен в сообщении об ошибке JSON-RPC.
    const fx = await startStreamableFixture({ errorEcho: SECRET_TOKEN });
    t.after(fx.close);
    const descriptor = {
        id: 'echo-err',
        transport: 'streamable-http',
        url: `${fx.url}?token=zzz-secret`,
        headers: { Authorization: `Bearer ${SECRET_TOKEN}` }
    };
    const described = JSON.stringify(describeDescriptor(descriptor));
    assert.ok(!described.includes(SECRET_TOKEN), 'describeDescriptor без значений');
    assert.ok(!described.includes('zzz-secret'), 'query URL без значений');
    assert.ok(describeDescriptor(descriptor).authPresent, 'наличие авторизации — с признаком, без значений');
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 4000, requestTimeoutMs: 4000 } });
    t.after(() => manager.closeAll());
    const probe = await manager.probe(descriptor);
    assert.equal(probe.status, 'failed');
    assert.ok(!JSON.stringify(probe).includes(SECRET_TOKEN),
        'ответ probe не содержит секрета, даже если сервер его эхоирует');
    assert.ok(JSON.stringify(probe).includes('[скрыто]'), 'значение заменено маркером');
});

test('streamable-http: результат tools/call редактируется от секретов', async (t) => {
    const fx = await startStreamableFixture({ requireToken: SECRET_TOKEN, secretEcho: SECRET_TOKEN });
    t.after(fx.close);
    const client = new McpClient(
        {
            id: 'envdump',
            transport: 'streamable-http',
            url: fx.url,
            headers: { Authorization: `Bearer ${SECRET_TOKEN}` }
        },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    await client.connect();
    const manager = new McpClientManager();
    t.after(() => manager.closeAll());
    manager.descriptors.set('envdump', client.descriptor);
    manager.sessions.set('envdump', client);
    const call = await manager.callTool('envdump', 'env-dump', {});
    assert.equal(call.ok, true);
    assert.ok(!JSON.stringify(call.result).includes(SECRET_TOKEN), 'секрет вырезан из результата');
    assert.match(call.result.content[0].text, /\[скрыто\]/);
});

test('streamable-http: таймаут tools/call — request_timeout', async (t) => {
    const fx = await startStreamableFixture();
    t.after(fx.close);
    const client = new McpClient(
        { id: 'hangy', transport: 'streamable-http', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 400 }
    );
    t.after(() => client.close());
    await client.connect();
    await assert.rejects(() => client.callTool('hang', {}), (err) => {
        assert.equal(err.code, 'request_timeout');
        return true;
    });
});

test('streamable-http: нарушение схемы ответа tools/call — protocol_error', async (t) => {
    const fx = await startStreamableFixture();
    t.after(fx.close);
    const client = new McpClient(
        { id: 'broken', transport: 'streamable-http', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    await client.connect();
    await assert.rejects(() => client.callTool('schema-broken', {}), (err) => {
        assert.equal(err.code, 'protocol_error');
        return true;
    });
    await assert.rejects(() => client.callTool('refuse', {}), (err) => {
        assert.equal(err.code, 'tool_error');
        assert.match(err.message, /Некорректные аргументы/);
        return true;
    });
});

test('согласование версии: незнакомая версия — отказ, старая известная — принимается', async (t) => {
    const fx1 = await startStreamableFixture({ versionEcho: '1999-01-01' });
    t.after(fx1.close);
    const client1 = new McpClient(
        { id: 'v-unknown', transport: 'streamable-http', url: fx1.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client1.close());
    await assert.rejects(() => client1.connect(), (err) => {
        assert.equal(err.code, 'version_unsupported');
        assert.match(err.message, /1999-01-01/);
        return true;
    });

    const fx2 = await startStreamableFixture({ versionEcho: '2024-11-05' });
    t.after(fx2.close);
    const client2 = new McpClient(
        { id: 'v-old', transport: 'streamable-http', url: fx2.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client2.close());
    const summary = await client2.connect();
    assert.equal(summary.protocolVersion, '2024-11-05', 'поддержанная серверная версия принята');
});

test('connected требует всего цикла: tools/list без ответа — нет статуса connected', async (t) => {
    const fx = await startStreamableFixture({ toolsListHang: true });
    t.after(fx.close);
    const client = new McpClient(
        { id: 'no-tools', transport: 'streamable-http', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 400 }
    );
    t.after(() => client.close());
    await assert.rejects(() => client.connect(), (err) => {
        assert.equal(err.code, 'request_timeout', 'без tools/list рукопожатие не завершено');
        return true;
    });
    assert.notEqual(client.state, 'connected');
});

// ---------------------------------------------------------------------------
// Устаревший HTTP+SSE
// ---------------------------------------------------------------------------

test('sse (HTTP+SSE): рукопожатие через endpoint и реальный вызов инструмента', async (t) => {
    const fx = await startSseFixture();
    t.after(fx.close);
    const client = new McpClient(
        { id: 'sse-srv', transport: 'sse', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    const summary = await client.connect();
    assert.equal(summary.serverInfo.name, 'fixture-sse');
    assert.deepEqual(summary.tools.map(x => x.name), ['sse-echo']);
    assert.equal(fx.state.requests.some(r => r.method === 'notifications/initialized'), true);
    const result = await client.callTool('sse-echo', {});
    assert.equal(result.content[0].text, 'SSE-OK');
});

// ---------------------------------------------------------------------------
// Отмена (Stop) и менеджер сессий
// ---------------------------------------------------------------------------

test('отмена вызова (Stop): cancelled, уведомление отправлено, сессия жива', async (t) => {
    const fx = await startStreamableFixture();
    t.after(fx.close);
    const client = new McpClient(
        { id: 'stop', transport: 'streamable-http', url: fx.url },
        { handshakeTimeoutMs: 5000, requestTimeoutMs: 5000 }
    );
    t.after(() => client.close());
    await client.connect();
    const ac = new AbortController();
    const pending = client.callTool('hang', {}, { signal: ac.signal, timeoutMs: 5000 });
    setTimeout(() => ac.abort(), 150);
    await assert.rejects(() => pending, (err) => {
        assert.equal(err.code, 'cancelled');
        return true;
    });
    await new Promise((r) => setTimeout(r, 200));
    assert.ok(fx.state.cancelledNotifications.length >= 1,
        'сервер получил notifications/cancelled');
    assert.equal(client.state, 'connected', 'Stop не разрушает соединение');
    await client.ping({ timeoutMs: 2000 });
});

test('McpClientManager: connected только после полного рукопожатия; отказ — без угаданных tools', async (t) => {
    const manager = new McpClientManager({ clientOptions: { handshakeTimeoutMs: 8000, requestTimeoutMs: 8000 } });
    t.after(() => manager.closeAll());

    const good = await manager.probe(sysinfoDescriptor());
    assert.equal(good.ok, true);
    assert.equal(good.status, 'connected');
    assert.deepEqual(good.tools, ['get_workstation_pulse'], 'tools — из живого tools/list');
    assert.equal(good.serverInfo.name, 'antigravity-sysinfo-mcp');
    assert.ok(good.probedAt);

    const call = await manager.callTool('sysinfo', 'get_workstation_pulse', {});
    assert.equal(call.ok, true);
    assert.match(call.result.content[0].text, /ANTIGRAVITY-MCP-VERIFIED-2026/);

    // Сервер молчит: статус failed, tools пусты (ничего не угадано).
    const bad = await manager.probe(
        {
            id: 'silent2',
            transport: 'stdio',
            command: NODE,
            args: ['-e', 'setInterval(() => {}, 1000);']
        },
        { handshakeTimeoutMs: 350 }
    );
    assert.equal(bad.ok, false);
    assert.equal(bad.status, 'failed');
    assert.deepEqual(bad.tools, [], 'при отказе список инструментов не выдумывается');
    assert.ok(bad.error, 'причина отказа зафиксирована');
    assert.ok(bad.errorCode);

    await manager.disconnect('sysinfo');
    assert.equal(manager.getLive('sysinfo'), null, 'disconnect снимает живой статус');
});

test('buildMcpDescriptor: транспорт из записи конфига, секреты только в памяти', () => {
    const remote = buildMcpDescriptor('remote', {
        type: 'streamable-http',
        url: 'https://example.com/mcp?token=zzz-secret',
        headers: { Authorization: `Bearer ${SECRET_TOKEN}` }
    });
    assert.equal(remote.transport, 'streamable-http');
    assert.deepEqual(remote.headers, { Authorization: `Bearer ${SECRET_TOKEN}` },
        'заголовки пробрасываются на транспорт');
    const described = JSON.stringify(describeDescriptor(remote));
    assert.ok(!described.includes(SECRET_TOKEN));
    assert.ok(!described.includes('zzz-secret'));

    const local = buildMcpDescriptor('local', {
        command: 'node',
        args: ['srv.js'],
        env: { API_KEY: SECRET_TOKEN }
    });
    assert.equal(local.transport, 'stdio');
    assert.equal(local.env.API_KEY, SECRET_TOKEN, 'env пробрасывается в процесс');
    assert.ok(!JSON.stringify(describeDescriptor(local)).includes(SECRET_TOKEN));
});
