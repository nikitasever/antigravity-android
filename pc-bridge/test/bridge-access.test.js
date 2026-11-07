'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { BridgeAccess } = require('../lib/bridge-access');

function fixture(block) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-access-qa-'));
    try { return block(path.join(directory, 'access.json')); }
    finally {
        assert.ok(fs.realpathSync(directory).startsWith(fs.realpathSync(os.tmpdir()) + path.sep));
        assert.ok(path.basename(directory).startsWith('bridge-access-qa-'));
        fs.rmSync(directory, { recursive: true, force: true });
    }
}
test('bridge credential is persistent, requires exact possession and is never the pairing code', () => fixture(file => {
    const access = new BridgeAccess({ file });
    const pin = access.openPairing().code;
    assert.equal(access.authorize(pin), false);
    const paired = access.pair(pin);
    assert.ok(paired && access.authorize(paired.bridgeToken));
    assert.equal(access.authorize(paired.bridgeToken + '\n'), false);
    assert.equal(access.authorize('0'.repeat(64)), false);
    assert.equal(access.pair(pin), null);
    assert.ok(new BridgeAccess({ file }).authorize(paired.bridgeToken));
}));
test('pairing window expires and five bad attempts close it until local reopening', () => fixture(file => {
    let now = 1000;
    const access = new BridgeAccess({ file, now: () => now, randomInt: () => 123456 });
    access.openPairing();
    for (let i = 0; i < 5; i++) assert.equal(access.pair('wrong'), null);
    assert.equal(access.pair('123456'), null);
    access.openPairing(); now += 5 * 60000;
    assert.equal(access.pair('123456'), null);
    access.openPairing(); assert.ok(access.pair('123456'));
}));
test('malformed existing credential store fails closed without replacing owner identity', () => fixture(file => {
    for (const contents of ['{broken', '{"version":9,"token":"abc"}']) {
        fs.writeFileSync(file, contents);
        assert.throws(() => new BridgeAccess({ file }));
        assert.equal(fs.readFileSync(file, 'utf8'), contents);
    }
}));
test('browser ticket is single-use, short-lived and not the reusable bridge credential', () => fixture(file => {
    let now = 0;
    const access = new BridgeAccess({ file, now: () => now });
    const ticket = access.createWebTicket();
    assert.equal(access.authorize(ticket), false);
    const session = access.consumeWebTicket(ticket);
    assert.ok(session && access.authorizeWebSession(session));
    assert.equal(access.authorize(session), false);
    assert.equal(access.consumeWebTicket(ticket), null);
    const expired = access.createWebTicket(); now += 30000;
    assert.equal(access.consumeWebTicket(expired), null);
    now = 3600000;
    assert.equal(access.authorizeWebSession(session), false);
}));
