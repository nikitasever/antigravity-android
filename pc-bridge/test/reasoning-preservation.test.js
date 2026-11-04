const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the actual bridge normalizer without starting the HTTP server or CLI.
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
const start = source.indexOf('function cleanRussianThinkingParagraphs(');
const end = source.indexOf('\nfunction normalizeTranscriptThinkingTags(', start);
assert.ok(start >= 0 && end > start);
const clean = vm.runInNewContext(`(${source.slice(start, end).trim()})`);

test('provider reasoning is preserved regardless of language or paragraph length', () => {
    for (const text of ['Check files first.', 'Проверю.', '• Read the config\n\n• Run the checks', '先检查配置。']) {
        assert.equal(clean(text), text);
    }
});

test('reasoning normalizer only removes transport truncation markers and outer whitespace', () => {
    assert.equal(clean('  Plan\n\n<truncated 42 bytes>\n\nVerify.  '), 'Plan\n\n...\n\nVerify.');
    assert.equal(clean(''), '');
    assert.equal(clean(null), '');
});
