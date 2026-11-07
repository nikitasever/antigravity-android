'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const action = process.argv[2];
if (!['pair', 'web'].includes(action)) throw Error('Usage: node pc-bridge/owner-access.cjs pair|web');
const credential = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.antigravity-private', 'bridge-owner.json'), 'utf8'));
if (credential.version !== 1 || !/^[a-f0-9]{64}$/.test(credential.token)) throw Error('Invalid owner credential');
(async () => {
    const response = await fetch(`http://127.0.0.1:8787/api/${action === 'pair' ? 'pairing/open' : 'web-ticket'}`, {
        method: 'POST', headers: { 'X-Antigravity-Bridge-Token': credential.token, 'Content-Type': 'application/json' },
        body: '{}', redirect: 'error', signal: AbortSignal.timeout(10000)
    });
    if (!response.ok) throw Error(`Bridge returned HTTP ${response.status}`);
    const data = await response.json();
    if (action === 'pair' && /^\d{6}$/.test(data.code)) console.log(`Код сопряжения: ${data.code} (5 минут, 5 попыток)`);
    else if (action === 'web' && /^[a-f0-9]{64}$/.test(data.ticket)) console.log(`http://127.0.0.1:8787/web?ticket=${data.ticket}`);
    else throw Error('Invalid bridge response');
})().catch(() => { console.error('Не удалось связаться с локальным мостом. Проверьте, что PC-Bridge запущен.'); process.exitCode = 1; });
