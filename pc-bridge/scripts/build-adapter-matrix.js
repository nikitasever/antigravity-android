'use strict';
// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): генератор extensions-adapter-matrix.json
// из extensions-inventory.json. У каждого компонента каждой позиции — стратегия
// (preserve/convert/own-executor), проверенный путь исполнения либо конкретный
// блокер, и отдельно — проверка права переиспользования/распространения.
const fs = require('node:fs');
const path = require('node:path');
const adapters = require('../lib/component-adapters');
const { loadOfficialMcp } = require('../lib/official-mcp-catalog');

const PROJECT_ROOT = path.join(__dirname, '..', '..');
const invPath = path.join(PROJECT_ROOT, 'extensions-inventory.json');
const outPath = path.join(PROJECT_ROOT, 'extensions-adapter-matrix.json');

const inventory = JSON.parse(fs.readFileSync(invPath, 'utf8'));
const official = loadOfficialMcp();
const inventoryIds = new Set(inventory.positions.map(p => p.id));
for (const server of official.servers) {
    for (const id of server.positionIds) {
        if (!inventoryIds.has(id)) throw new Error(`Official MCP ${server.id}: unknown inventory position ${id}`);
    }
}
const matrix = adapters.buildAdapterMatrix(inventory);
fs.writeFileSync(outPath, JSON.stringify(matrix, null, 2) + '\n', 'utf8');
console.log(`written: ${outPath}`);
console.log(`positions=${matrix.summary.positionsTotal} records=${matrix.summary.recordsTotal} fully-supported=${matrix.summary.fullySupported.length} license-unknown=${matrix.summary.licenseUnknown.length}`);
console.log(`official MCP descriptors=${official.servers.length}; live authentication remains a separate check`);
