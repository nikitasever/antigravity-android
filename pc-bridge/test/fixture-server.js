// Isolated emulator fixture. Never imported by the production bridge.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const sessions = new Map();
const active = new Map();
const events = [];
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const json = body => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
    if (req.method === 'GET') {
        if (url.pathname === '/api/status') return json({ ok: true, instanceName: 'Emulator test fixture', hostname: 'test', workspace: 'test' });
        if (url.pathname === '/test/events') return json(events);
        if (url.pathname === '/api/models') return json({ ok: true, models: [{ id: 'gemini-4-argon-high', displayName: 'Test fixture' }] });
        if (url.pathname === '/api/sessions') return json({ ok: true, sessions: [...sessions].map(([id, messages]) => ({ id, title: messages[0]?.content || 'Test', messageCount: messages.length })) });
        if (url.pathname === '/api/session') return json({ ok: true, messages: sessions.get(url.searchParams.get('id')) || [] });
        return json({ ok: true, models: [], files: [], plugins: [], skills: [], servers: [], agents: [], connected: false });
    }
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
        let data;
        try { data = JSON.parse(Buffer.concat(chunks).toString() || '{}'); }
        catch { res.writeHead(400); return res.end(); }
        if (url.pathname === '/api/chat/cancel') {
            events.push({ type: 'cancel', turnId: data.turnId });
            active.get(data.turnId)?.();
            return json({ ok: true });
        }
        if (url.pathname === '/api/chat/stream') {
            events.push({ type: 'prompt', prompt: data.prompt, conversationId: data.conversationId, history: data.history });
            res.writeHead(200, { 'Content-Type': 'application/x-ndjson' });
            const send = event => { if (!res.destroyed) res.write(JSON.stringify(event) + '\n'); };
            send({ type: 'init', turnId: data.turnId, conversationId: data.conversationId });
            const reply = data.prompt.includes('CODE_VIEW')
                ? '```kotlin\nfun main() {\n    println("Hello from fixture")\n}\n```'
                : `[Test fixture] Ответ: ${data.prompt}`;
            const timer = setTimeout(() => {
                send({ type: 'text_delta', fullText: reply, delta: reply });
                send({ type: 'done', ok: true, conversationId: data.conversationId, reply });
                sessions.set(data.conversationId, [
                    { id: `${data.turnId}-u`, role: 'USER', content: data.prompt },
                    { id: `${data.turnId}-a`, role: 'AGENT', content: reply }
                ]);
                active.delete(data.turnId);
                res.end();
            }, data.prompt.includes('SLOW') ? 15000 : 600);
            active.set(data.turnId, () => { clearTimeout(timer); active.delete(data.turnId); res.end(); });
            return;
        }
        return json({ ok: true });
    });
});
server.listen(18787, '127.0.0.1', () => process.stdout.write('Test fixture listening on 18787\n'));
