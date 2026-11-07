'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { BridgeAccess } = require('../../lib/bridge-access');
const { createBridgeHttpAccess } = require('../../lib/bridge-http-access');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-pairing-fixture-'));
const access = new BridgeAccess({ file: path.join(directory, 'owner.json'), randomInt: () => 123456 });
const guard = createBridgeHttpAccess(access);
access.openPairing();
let crossOriginRequests = 0;
const other = http.createServer((req, res) => {
    crossOriginRequests++;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ credentialReceived: Boolean(req.headers['x-antigravity-bridge-token']) }));
}).listen(8820, '127.0.0.1');
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (await guard(req, res, url)) return;
    if (url.pathname === '/web') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        return res.end('<html><head><title>Loading</title></head><body>QA<script>fetch("/api/status").then(r=>document.title=r.status===200?"Paired QA Web":"Unauthorized")</script></body></html>');
    }
    if (url.pathname === '/api/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ ok: true, instanceName: 'Paired QA', hostname: 'QA', workspace: '', publicTunnelUrls: [] }));
    }
    if (url.pathname === '/sample.txt') { res.writeHead(200, { 'Content-Length': 5 }); return res.end('hello'); }
    if (url.pathname === '/api/tts') { res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Content-Length': 512 }); return res.end(Buffer.alloc(512)); }
    if (url.pathname === '/redirect') { res.writeHead(302, { Location: 'http://127.0.0.1:8820/stolen' }); return res.end(); }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, crossOriginRequests }));
}).listen(8819, '127.0.0.1', () => console.log('Paired QA fixture ready, code 123456, ports 8819/8820'));
const close = () => {
    server.closeAllConnections(); other.closeAllConnections(); server.close(); other.close();
    if (path.dirname(fs.realpathSync(directory)) !== fs.realpathSync(os.tmpdir()) || !path.basename(directory).startsWith('bridge-pairing-fixture-')) throw Error('Unsafe cleanup');
    fs.rmSync(directory, { recursive: true }); process.exit(0);
};
process.on('SIGINT', close); process.on('SIGTERM', close);
