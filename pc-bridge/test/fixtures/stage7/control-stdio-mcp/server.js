'use strict';
// Контрольный пакет 4 (Этап 7): настоящий stdio MCP-сервер (JSON-RPC 2.0 по
// строкам stdin → stdout). Запускается как дочерний процесс; рукопожатие и
// tools/call выполняются клиентом по-настоящему.
const readline = require('node:readline');

const TOOLS = [
    {
        name: 'stage7_stdio_echo',
        description: 'эхо контрольного stdio MCP: возвращает переданный текст',
        inputSchema: {
            type: 'object',
            properties: { text: { type: 'string' } },
            required: []
        }
    }
];

function send(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
    let msg = {};
    try { msg = JSON.parse(line); } catch (_) { return; }
    const method = typeof msg.method === 'string' ? msg.method : '';
    if (method === 'initialize') {
        send({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                protocolVersion: (msg.params && msg.params.protocolVersion) || '2025-06-18',
                capabilities: { tools: {} },
                serverInfo: { name: 'stage7-stdio-echo', version: '1.0.0' }
            }
        });
        return;
    }
    if (method === 'notifications/initialized') return;
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
                content: [{ type: 'text', text: `CONTROL-STDIO-MCP-OK:${text}` }],
                isError: false
            }
        });
        return;
    }
    if (msg.id != null) {
        send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `unsupported method: ${method}` } });
    }
});
