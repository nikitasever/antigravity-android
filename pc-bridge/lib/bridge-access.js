'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const digest = value => crypto.createHash('sha256').update(String(value)).digest();
const equal = (a, b) => crypto.timingSafeEqual(digest(a), digest(b));

/** Single-owner bridge credential. Pairing is explicitly opened locally, expires and is consumed once. */
class BridgeAccess {
    constructor({ file, now = Date.now, randomBytes = crypto.randomBytes, randomInt = crypto.randomInt }) {
        this.file = file; this.now = now; this.randomBytes = randomBytes; this.randomInt = randomInt;
        this.pairing = null; this.webTickets = new Map(); this.webSessions = new Map();
        if (!fs.existsSync(file)) {
            fs.mkdirSync(path.dirname(file), { recursive: true });
            const temporary = file + '.' + crypto.randomUUID() + '.tmp';
            const descriptor = fs.openSync(temporary, 'wx', 0o600);
            try { fs.writeFileSync(descriptor, JSON.stringify({ version: 1, token: randomBytes(32).toString('hex') })); fs.fsyncSync(descriptor); }
            finally { fs.closeSync(descriptor); }
            try { fs.linkSync(temporary, file); }
            catch (error) { if (error.code !== 'EEXIST') throw error; }
            finally { fs.unlinkSync(temporary); }
        }
        const stat = fs.statSync(file);
        if (!stat.isFile() || stat.size > 1024) throw Error('Invalid private bridge credential store');
        const data = JSON.parse(fs.readFileSync(file, 'utf8'));
        if (data.version !== 1 || !/^[a-f0-9]{64}$/.test(data.token || '')) throw Error('Invalid private bridge credential store');
        this.token = data.token;
    }
    authorize(token) { return typeof token === 'string' && /^[a-f0-9]{64}$/.test(token) && equal(token, this.token); }
    openPairing() {
        const code = this.randomInt(0, 1000000).toString().padStart(6, '0');
        this.pairing = { digest: digest(code), expiresAt: this.now() + 5 * 60000, attempts: 0 };
        return { code, expiresAt: this.pairing.expiresAt };
    }
    pair(code) {
        const window = this.pairing;
        if (!window || this.now() >= window.expiresAt || window.attempts >= 5) {
            this.pairing = null; return null;
        }
        window.attempts++;
        if (typeof code !== 'string' || !/^\d{6}$/.test(code) || !crypto.timingSafeEqual(digest(code), window.digest)) return null;
        this.pairing = null;
        return { bridgeToken: this.token };
    }
    createWebTicket() {
        this.prune();
        if (this.webTickets.size >= 32) throw Error('Too many pending browser sessions');
        const ticket = this.randomBytes(32).toString('hex');
        this.webTickets.set(digest(ticket).toString('hex'), this.now() + 30000);
        return ticket;
    }
    consumeWebTicket(ticket) {
        this.prune();
        if (typeof ticket !== 'string' || !/^[a-f0-9]{64}$/.test(ticket)) return null;
        const hash = digest(ticket).toString('hex');
        if (!this.webTickets.has(hash)) return null;
        this.webTickets.delete(hash);
        if (this.webSessions.size >= 32) return null;
        const session = this.randomBytes(32).toString('hex');
        this.webSessions.set(digest(session).toString('hex'), this.now() + 3600000);
        return session;
    }
    authorizeWebSession(session) {
        this.prune();
        return typeof session === 'string' && /^[a-f0-9]{64}$/.test(session) && this.webSessions.has(digest(session).toString('hex'));
    }
    prune() {
        for (const collection of [this.webTickets, this.webSessions]) for (const [key, expiry] of collection) if (this.now() >= expiry) collection.delete(key);
    }
}
module.exports = { BridgeAccess };
