'use strict';
// QA/Security-аудит, Этап 7 (EXTENSIONS_INTEGRATION_PLAN.md): реальный stdio
// MCP-сервер для тестов дефектов Medium #2 (редактирование секретов) и
// Critical #2 (inflight-гонка). На старте пишет маркер в QA_MARKER_FILE
// (чтобы тест мог посчитать фактические запуски процесса); QA_DELAY_MS
// задерживает ответ initialize (окно для проверки параллельных probe).
const fs = require('node:fs');
const readline = require('node:readline');

if (process.env.QA_MARKER_FILE) {
    try { fs.appendFileSync(process.env.QA_MARKER_FILE, 'start\n'); } catch (_) {}
}
const delayMs = Number(process.env.QA_DELAY_MS || 0);

function send(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
    let msg = {};
    try { msg = JSON.parse(line); } catch (_) { return; }
    const method = typeof msg.method === 'string' ? msg.method : '';
    if (method === 'initialize') {
        const reply = () => send({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                protocolVersion: (msg.params && msg.params.protocolVersion) || '2025-06-18',
                capabilities: { tools: {} },
                serverInfo: { name: 'qa-secret-echo', version: '1.0.0' }
            }
        });
        if (delayMs > 0) setTimeout(reply, delayMs); else reply();
        return;
    }
    if (method === 'notifications/initialized') return;
    if (method === 'tools/list') {
        send({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                tools: [{
                    name: 'echo',
                    description: 'эхо: возвращает текст во всех полях результата',
                    inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: [] }
                }]
            }
        });
        return;
    }
    if (method === 'tools/call') {
        const text = (msg.params && msg.params.arguments && msg.params.arguments.text) || '';
        send({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                content: [{ type: 'text', text }],
                structuredContent: { nested: { deep: text } },
                extra: { inner: text }
            }
        });
        return;
    }
    if (msg.id != null) {
        send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `unsupported: ${method}` } });
    }
});
