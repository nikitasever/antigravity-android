#!/usr/bin/env node
'use strict';
// Контрольный hook Этапа 7: официальный контракт hooks.md — JSON на stdin,
// JSON на stdout. Ничего не запускает молча: отказ виден в поле refuseReason.
let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => { raw += c; });
process.stdin.on('end', () => {
    let input = {};
    try { input = JSON.parse(raw || '{}'); } catch (err) {
        process.stdout.write(JSON.stringify({
            ok: false,
            marker: 'CONTROL-HOOK-OK',
            refuseReason: `вход не JSON: ${err.message}`
        }) + '\n');
        return;
    }
    process.stdout.write(JSON.stringify({
        ok: true,
        marker: 'CONTROL-HOOK-OK',
        event: input.event || null,
        decision: 'approve'
    }) + '\n');
});
