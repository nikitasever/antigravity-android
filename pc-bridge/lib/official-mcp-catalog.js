'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { buildMcpDescriptor } = require('./mcp-client');
const ROOT = path.resolve(__dirname, '../..');
function loadOfficialMcp() {
    const catalog = JSON.parse(fs.readFileSync(path.join(ROOT, 'extensions/official-mcp.json'), 'utf8'));
    const positions = new Set(), ids = new Set();
    for (const s of catalog.servers) {
        if (ids.has(s.id) || !s.evidence?.length || !s.sourceUrl?.startsWith('https://github.com/') || s.license !== 'MIT') throw new Error('Invalid official MCP evidence: ' + s.id);
        ids.add(s.id);
        const parsed = buildMcpDescriptor(s.id, s.descriptor);
        if (!parsed.command && !parsed.url) throw new Error('Invalid MCP descriptor: ' + s.id);
        if (s.descriptor.env || s.descriptor.headers || s.descriptor.authToken || /token=|password=|secret=/i.test(JSON.stringify(s.descriptor))) throw new Error('Secrets are forbidden in public descriptors');
        for (const id of s.positionIds) {
            if (positions.has(id)) throw new Error('Duplicate MCP binding: ' + id);
            positions.add(id);
        }
    }
    return catalog;
}
function descriptorForPosition(id) { return loadOfficialMcp().servers.find(s => s.positionIds.includes(id)) || null; }
module.exports = { loadOfficialMcp, descriptorForPosition };
