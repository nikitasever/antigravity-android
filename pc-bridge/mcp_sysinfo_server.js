#!/usr/bin/env node
// Minimal MCP (Model Context Protocol) stdio JSON-RPC 2.0 server for Google Antigravity
const os = require('os');
const readline = require('readline');

const rl = readline.createInterface({
    input: process.stdin,
    terminal: false
});

function sendResponse(id, result) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, result });
    process.stdout.write(msg + '\n');
}

function sendError(id, code, message) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });
    process.stdout.write(msg + '\n');
}

rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    let req;
    try {
        req = JSON.parse(trimmed);
    } catch (_) {
        return;
    }

    const { id, method, params } = req;
    if (method === 'initialize') {
        return sendResponse(id, {
            protocolVersion: (params && params.protocolVersion) || '2024-11-05',
            capabilities: {
                tools: {}
            },
            serverInfo: {
                name: 'antigravity-sysinfo-mcp',
                version: '1.0.0'
            }
        });
    }

    if (method === 'notifications/initialized' || method === 'initialized') {
        return;
    }

    if (method === 'ping') {
        return sendResponse(id, {});
    }

    if (method === 'tools/list') {
        return sendResponse(id, {
            tools: [
                {
                    name: 'get_workstation_pulse',
                    description: 'Возвращает точную телеметрию рабочей станции ПК (хост, аптайм, свободная память, проверочный код MCP).',
                    inputSchema: {
                        type: 'object',
                        properties: {},
                        required: []
                    }
                }
            ]
        });
    }

    if (method === 'tools/call') {
        const toolName = params && params.name;
        if (toolName === 'get_workstation_pulse') {
            const freeGb = (os.freemem() / (1024 * 1024 * 1024)).toFixed(2);
            const totalGb = (os.totalmem() / (1024 * 1024 * 1024)).toFixed(2);
            const info = {
                hostname: os.hostname(),
                platform: os.platform(),
                release: os.release(),
                freeMemoryGb: freeGb,
                totalMemoryGb: totalGb,
                uptimeHours: (os.uptime() / 3600).toFixed(1),
                mcpVerificationBadge: 'ANTIGRAVITY-MCP-VERIFIED-2026'
            };
            return sendResponse(id, {
                content: [
                    {
                        type: 'text',
                        text: JSON.stringify(info, null, 2)
                    }
                ],
                isError: false
            });
        }
        return sendError(id, -32601, `Unknown tool: ${toolName}`);
    }

    if (id !== undefined) {
        sendResponse(id, {});
    }
});
