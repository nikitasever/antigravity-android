'use strict';
// Контрольный пакет 3 (Этап 7): настоящий Streamable HTTP MCP-сервер
// (JSON-RPC 2.0 поверх POST) для сквозного сценария: initialize →
// notifications/initialized → tools/list → tools/call. Ничего не угадывает:
// список инструментов отдаётся только из этого файла.
const http = require('node:http');

const PORT = 18791;

const TOOLS = [
    {
        name: 'stage7_http_echo',
        description: 'эхо контрольного HTTP MCP: возвращает переданный текст',
        inputSchema: {
            type: 'object',
            properties: { text: { type: 'string' } },
            required: []
        }
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
        const send = (obj) => {
            res.writeHead(200, { 'content-type': 'application/json' });
            res.end(JSON.stringify(obj));
        };
        const method = typeof msg.method === 'string' ? msg.method : '';
        if (method === 'initialize') {
            send({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    protocolVersion: (msg.params && msg.params.protocolVersion) || '2025-06-18',
                    capabilities: { tools: {} },
                    serverInfo: { name: 'stage7-http-echo', version: '1.0.0' }
                }
            });
            return;
        }
        if (method === 'notifications/initialized') {
            res.writeHead(202);
            res.end();
            return;
        }
        if (method === 'tools/list') {
            send({ jsonrpc: '2.0', id: msg.id, result: { tools: TOOLS } });
            return;
        }
        if (method === 'tools/call') {
            const text = (msg.params && msg.params.arguments && msg.params.arguments.text) || '';
            send({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    content: [{ type: 'text', text: `CONTROL-HTTP-MCP-OK:${text}` }],
                    isError: false
                }
            });
            return;
        }
        if (msg.id != null) {
            send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `unsupported method: ${method}` } });
        }
    });
});

server.listen(PORT, '127.0.0.1', () => {
    process.stdout.write('ready\n');
});
