'use strict';
// Temporary read-only transport for already public release APKs. Never serves a directory or signing key.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const directory = fs.realpathSync(process.argv[2]);
const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'transport.json'), 'utf8'));
const files = new Map();
for (const asset of manifest.assets) {
    if (!/^[a-f0-9]{64}$/.test(asset.sha256) || path.basename(asset.file) !== asset.file || !asset.file.endsWith('.apk')) throw Error('Invalid artifact');
    const file = fs.realpathSync(path.join(directory, asset.file));
    if (path.dirname(file) !== directory) throw Error('Artifact escaped transport directory');
    const digest = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (digest !== asset.sha256) throw Error('Artifact digest mismatch');
    files.set(`/artifact/${digest}/${asset.file}`, file);
}
http.createServer((request, response) => {
    const file = files.get(new URL(request.url, 'http://127.0.0.1').pathname);
    if (!file || !['GET', 'HEAD'].includes(request.method) || request.headers.range) { response.writeHead(404); return response.end(); }
    response.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': fs.statSync(file).size });
    if (request.method === 'HEAD') response.end(); else fs.createReadStream(file).pipe(response);
}).listen(8818, '127.0.0.1', () => process.stdout.write(`Serving ${files.size} validated APKs only on 127.0.0.1:8818\n`));
