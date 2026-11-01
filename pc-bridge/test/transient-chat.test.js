const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

const start = source.indexOf('function transcriptWriteAllowed(');
assert.ok(start > 0, 'transcriptWriteAllowed найден в server.js');
const end = source.indexOf('\n}', start) + 2;
const transcriptWriteAllowed = vm.runInNewContext(`(${source.slice(start, end).trim()})`, {});

test('transcriptWriteAllowed: временный чат и телефонные режимы не пишут транскрипт', () => {
    // Обычная сессия ПК — транскрипт пишется.
    assert.equal(transcriptWriteAllowed('PC_REMOTE', false), true);
    assert.equal(transcriptWriteAllowed('PC_REMOTE', undefined), true);
    // Временный чат — транскрипт не пишется.
    assert.equal(transcriptWriteAllowed('PC_REMOTE', true), false);
    // Телефонные режимы — не пишутся независимо от флага.
    assert.equal(transcriptWriteAllowed('PHONE_ON_DEVICE', false), false);
    assert.equal(transcriptWriteAllowed('PHONE_LOCAL_RESOURCES', false), false);
});
