const http = require('http');
const { readJsonBody } = require('./lib/read-json-body');
const extensionRegistry = require('./lib/extension-registry');
const mcpClientLib = require('./lib/mcp-client');
const extensionRuntime = require('./lib/extension-runtime');
// Этап 5: собственный исполнитель hooks и адаптеры компонентов плагинов.
const hookEngine = require('./lib/hook-engine');
const componentAdapters = require('./lib/component-adapters');
const https = require('https');
const { spawn, exec } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { DatabaseSync } = require('node:sqlite');
const { MsEdgeTTS, OUTPUT_FORMAT } = require('msedge-tts');

const PORT = 8787;
const AGY_BIN = 'C:\\Users\\st811210\\AppData\\Local\\agy\\bin\\agy.exe';
const NODE_BIN = 'C:\\Users\\st811210\\AppData\\Local\\Programs\\nodejs\\node.exe';
const IDE_BASE_DIR = 'C:\\Users\\st811210\\.gemini\\antigravity';
const CLI_BASE_DIR = 'C:\\Users\\st811210\\.gemini\\antigravity-cli';
const GEMINI_ROOT_DIR = 'C:\\Users\\st811210\\.gemini';
const SKILLS_DIR = path.join(GEMINI_ROOT_DIR, 'config', 'skills');
const PLUGINS_DIR = path.join(GEMINI_ROOT_DIR, 'config', 'plugins');
const MCP_CONFIG_FILE = path.join(GEMINI_ROOT_DIR, 'config', 'mcp_config.json');
const GEMINI_SETTINGS_FILE = path.join(GEMINI_ROOT_DIR, 'settings.json');
const BRAIN_DIR = path.join(IDE_BASE_DIR, 'brain');
const SCRATCH_DIR = path.join(IDE_BASE_DIR, 'scratch');
const DEFAULT_WORKSPACE = 'C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android';
const UPLOADS_DIR = path.join(DEFAULT_WORKSPACE, 'uploads');
const REGISTRY_INDEX_PATH = path.join(UPLOADS_DIR, 'extensions-registry.json');
// Этап 3: живые MCP-подключения. Статус «connected» и список инструментов
// берутся ТОЛЬКО из реального рукопожатия initialize → initialized →
// tools/list; без проверки сервер честно «настроен», без выдуманных имён.
const mcpClientManager = new mcpClientLib.McpClientManager();
const mcpLiveState = new Map();
const CHAT_FAST_WORKSPACE = path.join(UPLOADS_DIR, 'chat-fast');
const AGENT_SYSTEM_PROMPT_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'agent_system_prompt.md');
const BRIDGE_REQUESTS_LOG = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'bridge_requests.log');
const INSTANCE_NAME = 'cr05318-orbital-mars';

fs.mkdirSync(UPLOADS_DIR, { recursive: true });
fs.mkdirSync(CHAT_FAST_WORKSPACE, { recursive: true });
fs.mkdirSync(SKILLS_DIR, { recursive: true });
fs.mkdirSync(PLUGINS_DIR, { recursive: true });
fs.mkdirSync(path.join(CLI_BASE_DIR, 'conversations'), { recursive: true });
fs.mkdirSync(path.join(CLI_BASE_DIR, 'annotations'), { recursive: true });
fs.mkdirSync(path.join(CLI_BASE_DIR, 'brain'), { recursive: true });

function loadAgentSystemPrompt() {
    try {
        if (fs.existsSync(AGENT_SYSTEM_PROMPT_FILE)) {
            return fs.readFileSync(AGENT_SYSTEM_PROMPT_FILE, 'utf8').trim();
        }
    } catch (_) {}
    return '';
}

function appendBridgeRequestLog(entry) {
    try {
        const line = JSON.stringify({
            timestamp: new Date().toISOString(),
            ...entry
        }) + '\n';
        fs.appendFileSync(BRIDGE_REQUESTS_LOG, line, 'utf8');
    } catch (_) {}
}

// ============================================================================
// Каталог моделей agy.exe (динамически синхронизируется с `agy.exe models`)
// ============================================================================
const MODEL_BADGES_RU = {
    'gemini-4-argon-high': 'Основная флагманская модель • Глубокое мышление и архитектура',
    'gemini-4-argon-medium': 'Оптимальный баланс скорости и глубины анализа',
    'gemini-4-argon-low': 'Быстрые ответы Gemini 4 для повседневных задач',
    'gemini-3.8-flash-high': 'Мгновенный отклик • Высокая точность',
    'gemini-3.8-flash-medium': 'Универсальная быстрая модель для чата и голоса',
    'gemini-3.8-flash-low': 'Ультра-низкая задержка',
    'gemini-2.5-pro': 'Прямой Gemini API • Сложное рассуждение и длинный контекст',
    'gemini-2.5-flash': 'Прямой Gemini API • Сверхбыстрый мультимодальный режим'
};

let cachedAgyModels = [
    { id: 'gemini-4-argon-high', displayName: 'Gemini 4 Argon (High)', badge: MODEL_BADGES_RU['gemini-4-argon-high'] },
    { id: 'gemini-4-argon-medium', displayName: 'Gemini 4 Argon (Medium)', badge: MODEL_BADGES_RU['gemini-4-argon-medium'] },
    { id: 'gemini-4-argon-low', displayName: 'Gemini 4 Argon (Fast)', badge: MODEL_BADGES_RU['gemini-4-argon-low'] },
    { id: 'gemini-3.8-flash-high', displayName: 'Gemini 3.8 Flash (High)', badge: MODEL_BADGES_RU['gemini-3.8-flash-high'] },
    { id: 'gemini-3.8-flash-medium', displayName: 'Gemini 3.8 Flash (Medium)', badge: MODEL_BADGES_RU['gemini-3.8-flash-medium'] },
    { id: 'gemini-3.8-flash-low', displayName: 'Gemini 3.8 Flash (Low)', badge: MODEL_BADGES_RU['gemini-3.8-flash-low'] },
    { id: 'gemini-2.5-pro', displayName: 'Gemini 2.5 Pro', badge: MODEL_BADGES_RU['gemini-2.5-pro'] },
    { id: 'gemini-2.5-flash', displayName: 'Gemini 2.5 Flash', badge: MODEL_BADGES_RU['gemini-2.5-flash'] }
];

function refreshModelsFromAgyCli() {
    try {
        if (!fs.existsSync(AGY_BIN)) return;
        const child = spawn(AGY_BIN, ['models'], { windowsHide: true });
        let out = '';
        child.stdout.on('data', d => { out += d.toString('utf8'); });
        child.on('close', () => {
            const parsed = [];
            for (const line of out.split(/\r?\n/)) {
                const parts = line.trim().split(/\t+/);
                if (parts.length >= 2 && parts[0] && !parts[0].startsWith('Fetching')) {
                    const id = parts[0].trim();
                    if (id.toLowerCase().startsWith('claude-') || id.toLowerCase().startsWith('gpt-oss')) continue;
                    const displayName = id === 'gemini-4-argon-low' ? 'Gemini 4 Argon (Fast)' : parts[1].trim();
                    parsed.push({
                        id,
                        displayName,
                        badge: MODEL_BADGES_RU[id] || 'Модель Google Antigravity CLI'
                    });
                }
            }
            if (parsed.length > 0) {
                parsed.push(
                    { id: 'gemini-2.5-pro', displayName: 'Gemini 2.5 Pro', badge: MODEL_BADGES_RU['gemini-2.5-pro'] },
                    { id: 'gemini-2.5-flash', displayName: 'Gemini 2.5 Flash', badge: MODEL_BADGES_RU['gemini-2.5-flash'] }
                );
                parsed.sort((a, b) => {
                    if (a.id === 'gemini-4-argon-high') return -1;
                    if (b.id === 'gemini-4-argon-high') return 1;
                    return 0;
                });
                cachedAgyModels = parsed;
            }
        });
    } catch (_) {}
}
refreshModelsFromAgyCli();

function sendJson(res, status, data) {
    const body = JSON.stringify(data);
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization'
    });
    res.end(body);
}

/**
 * Очищает текст пользовательского сообщения от любых служебных обёрток,
 * системных промптов и инструкций `phone_action`, чтобы в пузырь пользователя
 * и заголовки сессий попадал ТОЛЬКО чистый запрос пользователя.
 */
function extractCleanUserMessage(raw) {
    if (!raw) return '';
    let s = raw;
    if (s.includes('<PHONE_TOOL_OBSERVATION>')) {
        return '';
    }
    const reqMatch = s.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
    if (reqMatch) {
        s = reqMatch[1];
    }
    s = s.replace(/<AGENT_SYSTEM_INSTRUCTION>[\s\S]*?<\/AGENT_SYSTEM_INSTRUCTION>/gi, '');
    s = s.replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, '');
    s = s.replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, '');
    s = s.replace(/<CONTEXT_SUMMARY>[\s\S]*?<\/CONTEXT_SUMMARY>/gi, '');
    s = s.replace(/<SYSTEM_MESSAGE>[\s\S]*?<\/SYSTEM_MESSAGE>/gi, '');
    s = s.replace(/<truncated\s+\d+\s+bytes>/gi, '...');
    s = s.replace(/\[Инструкция рассуждения:[\s\S]*$/gi, '');
    s = s.replace(/\(Ответь сразу текстом[\s\S]*$/gi, '');
    s = s.replace(/\(Примечание: ответь[\s\S]*$/gi, '');

    const phonePromptMarker = 'Запрос пользователя:';
    const lastPhoneIdx = s.lastIndexOf(phonePromptMarker);
    if (lastPhoneIdx >= 0) {
        s = s.slice(lastPhoneIdx + phonePromptMarker.length);
    }
    const ctxMarker = 'Новое сообщение пользователя:';
    const lastCtxIdx = s.lastIndexOf(ctxMarker);
    if (lastCtxIdx >= 0) {
        s = s.slice(lastCtxIdx + ctxMarker.length);
    }
    for (const cutMarker of [
        'Контекст смартфона пользователя:',
        'Прикреплённые файлы с телефона пользователя:',
        'Текущие ресурсы и результаты локального поиска смартфона:',
        'Контекст предыдущих сообщений диалога:'
    ]) {
        const idx = s.indexOf(cutMarker);
        if (idx >= 0) {
            s = s.slice(0, idx);
        }
    }
    if (s.includes('Ты ИИ-агент Google Antigravity') && s.includes('phone_action')) {
        return '';
    }
    return s.trim();
}

const GARBAGE_TITLES = new Set([
    '<thinking>', 'thinking', '2+2=?', 'или?', 'оо', 'оооо', 'тест', 'проверка', 'повтор'
]);

function isGarbageOrInternalTitle(title) {
    if (!title) return true;
    const t = title.trim();
    if (t.length < 3) return true;
    const lower = t.toLowerCase();
    if (GARBAGE_TITLES.has(lower)) return true;
    if (lower.startsWith('<thinking') || lower.includes('<original_task>') || lower.includes('you are a coding worker')) return true;
    if (lower.startsWith('проанализируй прикреплённые файлы и подробно ответь')) return true;
    return false;
}

function makeShortTitle(cleanPrompt, fallbackId) {
    if (!cleanPrompt) return '';
    if (cleanPrompt.includes('<original_task>') || cleanPrompt.includes('You are a coding worker')) {
        return '';
    }
    const firstLine = cleanPrompt.split(/\r?\n/).find(l => l.trim().length > 0) || cleanPrompt;
    let t = firstLine
        .replace(/@\[[^\]]+\]/g, '')
        .replace(/https?:\/\/(www\.)?/gi, '')
        .replace(/\s+/g, ' ')
        .trim();
    if (/^[a-zA-Z]:[\\/]Users[\\/]/i.test(t)) {
        if (t.toLowerCase().includes('round3')) return 'Финальная доработка клиента Antigravity Android';
        if (t.toLowerCase().includes('round2') || t.toLowerCase().includes('audit')) return 'Аудит и проверка функций Antigravity';
        return 'Рабочая сессия разработки Antigravity';
    }
    t = t.replace(/antigravity\.google\.com\/?/gi, 'Google Antigravity').trim();
    if (isGarbageOrInternalTitle(t)) {
        return '';
    }
    return t.slice(0, 68);
}

function encodeProtobufVarint(num) {
    const bytes = [];
    let v = num >>> 0;
    while (v >= 0x80) {
        bytes.push((v & 0x7f) | 0x80);
        v >>>= 7;
    }
    bytes.push(v);
    return Buffer.from(bytes);
}

function patchRawSummaryTitle(rawBuf, newTitle) {
    try {
        if (!rawBuf || !newTitle) return rawBuf;
        const buf = Buffer.from(rawBuf);
        if (buf.length < 2 || buf[0] !== 0x0a) return buf;
        let oldLen = 0;
        let shift = 0;
        let offset = 1;
        while (offset < buf.length) {
            const b = buf[offset++];
            oldLen |= (b & 0x7f) << shift;
            if ((b & 0x80) === 0) break;
            shift += 7;
        }
        const restStart = offset + oldLen;
        if (restStart > buf.length) return buf;
        const titleBytes = Buffer.from(newTitle, 'utf8');
        const lenVarint = encodeProtobufVarint(titleBytes.length);
        return Buffer.concat([Buffer.from([0x0a]), lenVarint, titleBytes, buf.subarray(restStart)]);
    } catch (_) {
        return rawBuf;
    }
}

function readOfficialSessionMetadata() {
    const metaMap = new Map();
    for (const baseDir of [CLI_BASE_DIR, IDE_BASE_DIR]) {
        const annotDir = path.join(baseDir, 'annotations');
        if (fs.existsSync(annotDir)) {
            try {
                for (const fname of fs.readdirSync(annotDir)) {
                    if (!fname.endsWith('.pbtxt')) continue;
                    const id = fname.replace(/\.pbtxt$/i, '');
                    const txt = fs.readFileSync(path.join(annotDir, fname), 'utf8');
                    const m = txt.match(/title:\s*"((?:[^"\\]|\\.)*)"/);
                    if (m && m[1]) {
                        let decoded = m[1];
                        try {
                            decoded = JSON.parse(`"${m[1]}"`);
                        } catch (_) {}
                        if (!isGarbageOrInternalTitle(decoded)) {
                            const prev = metaMap.get(id) || {};
                            metaMap.set(id, { ...prev, title: decoded.trim() });
                        }
                    }
                }
            } catch (_) {}
        }

        const sumDbPath = path.join(baseDir, 'conversation_summaries.db');
        if (fs.existsSync(sumDbPath)) {
            try {
                const db = new DatabaseSync(sumDbPath);
                const rows = db.prepare(
                    'SELECT conversation_id, title, preview, step_count, parent_conversation_id, last_modified_time, last_user_input_time FROM conversation_summaries'
                ).all();
                for (const r of rows) {
                    const id = r.conversation_id;
                    if (!id) continue;
                    const prev = metaMap.get(id) || {};
                    const isSubagent = Boolean(r.parent_conversation_id && String(r.parent_conversation_id).trim().length > 0);
                    const candidateTitle = (!isGarbageOrInternalTitle(r.title) ? r.title : (!isGarbageOrInternalTitle(r.preview) ? r.preview : '')) || prev.title || '';
                    let parsedTimeMs = prev.updatedAtMs || 0;
                    for (const ts of [r.last_modified_time, r.last_user_input_time]) {
                        if (ts && !String(ts).startsWith('0001-01-01')) {
                            const ms = Date.parse(String(ts));
                            if (Number.isFinite(ms) && ms > parsedTimeMs) parsedTimeMs = ms;
                        }
                    }
                    metaMap.set(id, {
                        title: candidateTitle.trim(),
                        isSubagent: prev.isSubagent || isSubagent,
                        stepCount: Math.max(prev.stepCount || 0, Number(r.step_count) || 0),
                        updatedAtMs: parsedTimeMs
                    });
                }
                db.close();
            } catch (_) {}
        }
    }
    return metaMap;
}

function syncConversationBeforeCli(conversationId) {
    if (!conversationId || conversationId.length < 32) return false;
    try {
        const ideDb = path.join(IDE_BASE_DIR, 'conversations', `${conversationId}.db`);
        const cliDb = path.join(CLI_BASE_DIR, 'conversations', `${conversationId}.db`);
        const idePb = path.join(IDE_BASE_DIR, 'annotations', `${conversationId}.pbtxt`);
        const cliPb = path.join(CLI_BASE_DIR, 'annotations', `${conversationId}.pbtxt`);
        const ideBrain = path.join(IDE_BASE_DIR, 'brain', conversationId);
        const cliBrain = path.join(CLI_BASE_DIR, 'brain', conversationId);

        if (fs.existsSync(ideDb)) {
            const shouldCopy = !fs.existsSync(cliDb) ||
                fs.statSync(ideDb).mtimeMs >= fs.statSync(cliDb).mtimeMs;
            if (shouldCopy) {
                fs.copyFileSync(ideDb, cliDb);
            }
        }
        if (fs.existsSync(idePb)) {
            fs.copyFileSync(idePb, cliPb);
        }
        if (fs.existsSync(ideBrain) && !fs.existsSync(cliBrain)) {
            try {
                fs.symlinkSync(ideBrain, cliBrain, 'junction');
            } catch (_) {}
        }
        return fs.existsSync(cliDb);
    } catch (_) {
        return false;
    }
}

function syncConversationAfterCli(conversationId, cleanUserPrompt = '') {
    if (!conversationId || conversationId.length < 32) return;
    const desiredTitle = makeShortTitle(cleanUserPrompt, conversationId);
    try {
        const ideDb = path.join(IDE_BASE_DIR, 'conversations', `${conversationId}.db`);
        const cliDb = path.join(CLI_BASE_DIR, 'conversations', `${conversationId}.db`);
        const idePb = path.join(IDE_BASE_DIR, 'annotations', `${conversationId}.pbtxt`);
        const cliPb = path.join(CLI_BASE_DIR, 'annotations', `${conversationId}.pbtxt`);
        const ideBrain = path.join(IDE_BASE_DIR, 'brain', conversationId);
        const cliBrain = path.join(CLI_BASE_DIR, 'brain', conversationId);

        if (fs.existsSync(cliDb)) {
            fs.copyFileSync(cliDb, ideDb);
        } else if (fs.existsSync(ideDb)) {
            fs.copyFileSync(ideDb, cliDb);
        }

        // Проверяем и исправляем .pbtxt, чтобы никогда не сохранялся title:"<thinking>"
        let pbContent = '';
        if (fs.existsSync(cliPb)) {
            pbContent = fs.readFileSync(cliPb, 'utf8');
        } else if (fs.existsSync(idePb)) {
            pbContent = fs.readFileSync(idePb, 'utf8');
        }
        if (desiredTitle) {
            if (!pbContent) {
                const nowSec = Math.floor(Date.now() / 1000);
                pbContent = `last_user_view_time:{seconds:${nowSec}} title:${JSON.stringify(desiredTitle)}\n`;
            } else if (pbContent.includes('title:"<thinking>"') || !pbContent.includes('title:')) {
                pbContent = pbContent.replace(/title:\s*"[^"]*"/, '').trim() + ` title:${JSON.stringify(desiredTitle)}\n`;
            }
        }
        if (pbContent) {
            try { fs.writeFileSync(cliPb, pbContent, 'utf8'); } catch (_) {}
            try { fs.writeFileSync(idePb, pbContent, 'utf8'); } catch (_) {}
        }

        if (fs.existsSync(cliBrain) && !fs.existsSync(ideBrain)) {
            try {
                fs.cpSync(cliBrain, ideBrain, { recursive: true, force: true });
            } catch (_) {
                try { fs.symlinkSync(cliBrain, ideBrain, 'junction'); } catch (_) {}
            }
        } else if (fs.existsSync(cliBrain) && fs.existsSync(ideBrain)) {
            try {
                const cliLogs = path.join(cliBrain, '.system_generated', 'logs');
                const ideLogs = path.join(ideBrain, '.system_generated', 'logs');
                if (fs.existsSync(cliLogs) && fs.realpathSync(cliBrain) !== fs.realpathSync(ideBrain)) {
                    fs.mkdirSync(ideLogs, { recursive: true });
                    for (const lf of ['transcript.jsonl', 'transcript_full.jsonl']) {
                        const src = path.join(cliLogs, lf);
                        const dst = path.join(ideLogs, lf);
                        if (fs.existsSync(src)) fs.copyFileSync(src, dst);
                    }
                }
            } catch (_) {}
        }

        // Синхронизируем строку в IDE_BASE_DIR/conversation_summaries.db и CLI_BASE_DIR/conversation_summaries.db
        const cliSumPath = path.join(CLI_BASE_DIR, 'conversation_summaries.db');
        const ideSumPath = path.join(IDE_BASE_DIR, 'conversation_summaries.db');
        if (fs.existsSync(cliSumPath) && fs.existsSync(ideSumPath)) {
            const cliSumDb = new DatabaseSync(cliSumPath);
            const ideSumDb = new DatabaseSync(ideSumPath);
            let row = cliSumDb.prepare('SELECT * FROM conversation_summaries WHERE conversation_id = ?').get(conversationId) ||
                      ideSumDb.prepare('SELECT * FROM conversation_summaries WHERE conversation_id = ?').get(conversationId);
            if (row) {
                const cleanTitle = (!isGarbageOrInternalTitle(row.title) ? row.title : desiredTitle) || 'Сессия Antigravity';
                const cleanPreview = (!isGarbageOrInternalTitle(row.preview) ? row.preview : cleanTitle);
                const patchedRaw = patchRawSummaryTitle(row.raw_summary, cleanTitle);
                const nowSql = new Date().toISOString().replace('T', ' ').replace('Z', '+00:00');

                const updateStmt = 'UPDATE conversation_summaries SET title = ?, preview = ?, last_modified_time = ?, last_user_input_time = ?, raw_summary = ? WHERE conversation_id = ?';
                cliSumDb.prepare(updateStmt).run(cleanTitle, cleanPreview, nowSql, nowSql, patchedRaw, conversationId);

                const existingIde = ideSumDb.prepare('SELECT conversation_id FROM conversation_summaries WHERE conversation_id = ?').get(conversationId);
                if (existingIde) {
                    ideSumDb.prepare(updateStmt).run(cleanTitle, cleanPreview, nowSql, nowSql, patchedRaw, conversationId);
                } else {
                    ideSumDb.prepare(`
                        INSERT INTO conversation_summaries (
                            conversation_id, title, preview, step_count, last_modified_time,
                            workspace_uris, status, source, project_id, agent_name,
                            parent_conversation_id, nesting_depth, battle_id, winning_conversation_id,
                            not_fully_idle, killed, last_user_input_time, last_user_input_step_index,
                            app_data_dir, raw_summary, group_id, summary_proto
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    `).run(
                        conversationId,
                        cleanTitle,
                        cleanPreview,
                        Math.max(Number(row.step_count) || 2, 2),
                        nowSql,
                        row.workspace_uris || '',
                        'CASCADE_RUN_STATUS_IDLE',
                        row.source || '',
                        row.project_id || 'f8db42b8-16d0-4569-9503-22cefcb44205',
                        row.agent_name || '',
                        '',
                        0,
                        row.battle_id || '',
                        row.winning_conversation_id || '',
                        0,
                        0,
                        nowSql,
                        Number(row.last_user_input_step_index) >= 0 ? row.last_user_input_step_index : 0,
                        'antigravity',
                        patchedRaw,
                        row.group_id || '',
                        row.summary_proto || null
                    );
                }
            }
            cliSumDb.close();
            ideSumDb.close();
        }
    } catch (_) {}
}

function ensureTranscriptUpdated(conversationId, cleanUserPrompt, aiReply, thinkingDurationSec, thinkingText) {
    if (!conversationId || conversationId.length < 8 || conversationId === 'phone-local-storage' || conversationId.startsWith('proj_')) return;
    const pureUserPrompt = extractCleanUserMessage(cleanUserPrompt) || cleanUserPrompt;
    for (const baseBrain of [BRAIN_DIR, path.join(CLI_BASE_DIR, 'brain')]) {
        try {
            const logDir = path.join(baseBrain, conversationId, '.system_generated', 'logs');
            fs.mkdirSync(logDir, { recursive: true });
            for (const fname of ['transcript.jsonl', 'transcript_full.jsonl']) {
                const logFile = path.join(logDir, fname);
                let lines = [];
                if (fs.existsSync(logFile)) {
                    lines = fs.readFileSync(logFile, 'utf8').split(/\r?\n/).filter(Boolean);
                }
                const tailText = lines.slice(-6).join('\n');
                if (pureUserPrompt && tailText.includes(pureUserPrompt.slice(0, 40))) {
                    continue;
                }

                const nextStep = lines.length + 1;
                const nowIso = new Date().toISOString();
                const userEntry = JSON.stringify({
                    step_index: nextStep,
                    source: 'USER_EXPLICIT',
                    type: 'USER_INPUT',
                    status: 'DONE',
                    created_at: nowIso,
                    content: `<USER_REQUEST>\n${pureUserPrompt}\n</USER_REQUEST>`
                });
                const aiEntry = JSON.stringify({
                    step_index: nextStep + 1,
                    source: 'MODEL',
                    type: 'PLANNER_RESPONSE',
                    status: 'DONE',
                    created_at: nowIso,
                    content: aiReply,
                    thinking: thinkingText || '',
                    thinking_duration_sec: thinkingDurationSec || 2
                });
                fs.appendFileSync(logFile, `${userEntry}\n${aiEntry}\n`, 'utf8');
            }
        } catch (_) {}
    }
}

function cleanRussianThinkingParagraphs(rawText) {
    if (!rawText || typeof rawText !== 'string') return '';
    const cleaned = rawText
        .replace(/<truncated\s+\d+\s+bytes>/gi, '...')
        .split(/\r?\n\r?\n+/)
        .map(p => p.trim())
        .filter(p => {
            if (!p || p.startsWith('• ')) return false;
            const cyrCount = (p.match(/[А-Яа-яЁё]/g) || []).length;
            const latCount = (p.match(/[A-Za-z]/g) || []).length;
            return cyrCount >= 8 && cyrCount >= latCount * 0.45;
        });
    return cleaned.join('\n\n').trim();
}

function normalizeTranscriptThinkingTags(conversationId, fallbackThinking = '', elapsedSec = 2) {
    if (!conversationId || conversationId.length < 8) return;
    for (const baseBrain of [BRAIN_DIR, path.join(CLI_BASE_DIR, 'brain')]) {
        try {
            const logDir = path.join(baseBrain, conversationId, '.system_generated', 'logs');
            if (!fs.existsSync(logDir)) continue;
            for (const fname of ['transcript.jsonl', 'transcript_full.jsonl']) {
                const logFile = path.join(logDir, fname);
                if (!fs.existsSync(logFile)) continue;
                const rawLines = fs.readFileSync(logFile, 'utf8').split(/\r?\n/).filter(Boolean);
                let modified = false;
                const updatedLines = rawLines.map((line, idx) => {
                    try {
                        const obj = JSON.parse(line);
                        if (obj.type === 'USER_INPUT' && typeof obj.content === 'string' && obj.content.includes('<AGENT_SYSTEM_INSTRUCTION>')) {
                            obj.content = obj.content.replace(/<AGENT_SYSTEM_INSTRUCTION>[\s\S]*?<\/AGENT_SYSTEM_INSTRUCTION>\s*/gi, '');
                            modified = true;
                        }
                        if (obj.type === 'PLANNER_RESPONSE') {
                            if (typeof obj.thinking === 'string' && obj.thinking.trim()) {
                                const cleanedT = cleanRussianThinkingParagraphs(obj.thinking);
                                if (cleanedT && cleanedT !== obj.thinking) {
                                    obj.thinking = cleanedT;
                                    modified = true;
                                }
                            }
                            if (typeof obj.content === 'string') {
                                const match = obj.content.match(/<thinking>([\s\S]*?)<\/thinking>/i);
                                if (match) {
                                    const tagThought = cleanRussianThinkingParagraphs(match[1] || '');
                                    obj.content = obj.content.replace(/<thinking>[\s\S]*?<\/thinking>/gi, '').trim();
                                    if (tagThought && (!obj.thinking || !obj.thinking.includes(tagThought))) {
                                        obj.thinking = obj.thinking ? cleanRussianThinkingParagraphs(`${obj.thinking}\n\n${tagThought}`) : tagThought;
                                    }
                                    if (!obj.thinking_duration_sec) obj.thinking_duration_sec = elapsedSec;
                                    modified = true;
                                } else if (idx === rawLines.length - 1 && fallbackThinking && !obj.thinking) {
                                    obj.thinking = cleanRussianThinkingParagraphs(fallbackThinking);
                                    obj.thinking_duration_sec = elapsedSec;
                                    modified = true;
                                }
                            }
                        }
                        return modified ? JSON.stringify(obj) : line;
                    } catch (_) {
                        return line;
                    }
                });
                if (modified) {
                    fs.writeFileSync(logFile, updatedLines.join('\n') + '\n', 'utf8');
                }
            }
        } catch (_) {}
    }
}

function resolveBestTranscriptFile(conversationId) {
    const candidates = [
        path.join(BRAIN_DIR, conversationId, '.system_generated', 'logs', 'transcript_full.jsonl'),
        path.join(CLI_BASE_DIR, 'brain', conversationId, '.system_generated', 'logs', 'transcript_full.jsonl'),
        path.join(BRAIN_DIR, conversationId, '.system_generated', 'logs', 'transcript.jsonl'),
        path.join(CLI_BASE_DIR, 'brain', conversationId, '.system_generated', 'logs', 'transcript.jsonl')
    ];
    for (const f of candidates) {
        try {
            if (fs.existsSync(f)) {
                const st = fs.statSync(f);
                if (st.size > 0 && st.size <= 4 * 1024 * 1024) {
                    return f;
                }
            }
        } catch (_) {}
    }
    for (const f of candidates.slice(2)) {
        if (fs.existsSync(f)) return f;
    }
    return '';
}

function listExistingPcSessions() {
    const sessions = [];
    const seenIds = new Set();
    const officialMeta = readOfficialSessionMetadata();

    try {
        const allDirs = [];
        for (const baseBrain of [BRAIN_DIR, path.join(CLI_BASE_DIR, 'brain')]) {
            if (!fs.existsSync(baseBrain)) continue;
            for (const d of fs.readdirSync(baseBrain, { withFileTypes: true })) {
                if ((!d.isDirectory() && !d.isSymbolicLink()) || d.name === 'tempmediaStorage' || d.name === 'phone-local-storage' || d.name.startsWith('proj_')) continue;
                if (typeof ephemeralCliIds !== 'undefined' && ephemeralCliIds.has(d.name)) continue;
                if (seenIds.has(d.name)) continue;
                seenIds.add(d.name);
                const full = path.join(baseBrain, d.name);
                const logPath = resolveBestTranscriptFile(d.name);
                let mtime = 0;
                try {
                    mtime = logPath && fs.existsSync(logPath)
                        ? Math.floor(fs.statSync(logPath).mtimeMs)
                        : Math.floor(fs.statSync(full).mtimeMs);
                } catch (_) {}
                const meta = officialMeta.get(d.name);
                if (meta && meta.updatedAtMs && meta.updatedAtMs > mtime) {
                    mtime = meta.updatedAtMs;
                }
                allDirs.push({ id: d.name, full, logPath, mtime, meta });
            }
        }

        allDirs.sort((a, b) => b.mtime - a.mtime);

        for (const item of allDirs) {
            if (item.meta && item.meta.isSubagent) continue;

            let transcriptTitle = '';
            let messageCount = 0;
            let isSubagentTranscript = false;

            if (item.logPath && fs.existsSync(item.logPath)) {
                const content = fs.readFileSync(item.logPath, 'utf8');
                const lines = content.split(/\r?\n/).filter(Boolean);
                for (const line of lines) {
                    try {
                        const obj = JSON.parse(line);
                        if (obj.type === 'USER_INPUT' && obj.content) {
                            if (obj.content.includes('<original_task>') || obj.content.includes('You are a coding worker')) {
                                isSubagentTranscript = true;
                                break;
                            }
                            const cleaned = extractCleanUserMessage(obj.content);
                            if (cleaned.length > 0 &&
                                !cleaned.startsWith('You receive messages automatically') &&
                                !cleaned.startsWith('The following is a <SYSTEM_MESSAGE>')) {
                                if (!transcriptTitle) transcriptTitle = makeShortTitle(cleaned, item.id);
                                messageCount++;
                            }
                        } else if (obj.type === 'PLANNER_RESPONSE' && obj.content && typeof obj.content === 'string' && obj.content.trim().length > 0) {
                            messageCount++;
                        }
                    } catch (_) {}
                }
            }
            if (isSubagentTranscript) continue;

            const officialTitle = (item.meta && !isGarbageOrInternalTitle(item.meta.title)) ? item.meta.title : '';
            let finalTitle = officialTitle || transcriptTitle;
            if (messageCount === 0 || !finalTitle || isGarbageOrInternalTitle(finalTitle)) continue;
            if (finalTitle.startsWith('media_') || finalTitle.includes('.jpg') || finalTitle.includes('.png')) {
                finalTitle = transcriptTitle && !transcriptTitle.startsWith('media_')
                    ? transcriptTitle
                    : 'Синхронизация чата ПК и поиск файлов на смартфоне';
            }

            const host = 'cr05318';
            sessions.push({
                id: item.id,
                title: finalTitle,
                environment: 'Рабочая станция ПК',
                host: host,
                hostType: 'PC',
                workspacePath: DEFAULT_WORKSPACE,
                updatedAtMs: item.mtime,
                messageCount: messageCount
            });
        }
    } catch (_) {}
    return sessions;
}

function isInternalSubagentOrchestrationText(text) {
    if (!text) return false;
    return (
        text.includes('Выполняю **Delegation Routine**') ||
        text.includes('Выполняю **Solo Routine**') ||
        (text.includes('Дочерний воркер') && text.includes('завершил работу'))
    );
}

const ephemeralCliIds = new Set();

function unquoteArgVal(val) {
    if (val === undefined || val === null) return '';
    return String(val).trim().replace(/^"+|"+$/g, '').replace(/\\\\/g, '\\');
}

function formatToolSummary(toolName, toolInfo) {
    const name = String(toolName || 'tool');
    if (!toolInfo || typeof toolInfo !== 'object') return name;
    const rawPath = toolInfo.Path || toolInfo.path || toolInfo.file_path || toolInfo.TargetFile || toolInfo.AbsolutePath || toolInfo.DirectoryPath || toolInfo.SearchPath || toolInfo.SearchDirectory;
    if (rawPath) {
        const p = unquoteArgVal(rawPath);
        const base = p.split(/[\\/]/).pop() || p;
        return base || p;
    }
    const rawCmd = toolInfo.CommandLine || toolInfo.command;
    if (rawCmd) {
        return unquoteArgVal(rawCmd).slice(0, 80);
    }
    const rawQuery = toolInfo.Query || toolInfo.query || toolInfo.Pattern || toolInfo.pattern || toolInfo.toolSummary;
    if (rawQuery) {
        return unquoteArgVal(rawQuery).slice(0, 60);
    }
    return name;
}

function extractDiffFromToolArgs(toolName, toolArgs, stepId = '') {
    const name = String(toolName || '').toLowerCase();
    if (!toolArgs || typeof toolArgs !== 'object') return null;
    const rawPath = toolArgs.TargetFile || toolArgs.Path || toolArgs.path || toolArgs.file_path || toolArgs.AbsolutePath || '';
    if (!rawPath) return null;
    const fullPath = unquoteArgVal(rawPath);
    const fileName = fullPath.split(/[\\/]/).pop() || fullPath;

    if (name.includes('replace_file_content') || name.includes('patch_file')) {
        const target = String(toolArgs.TargetContent || toolArgs.target || toolArgs.oldText || '');
        const replacement = String(toolArgs.ReplacementContent || toolArgs.replacement || toolArgs.newText || '');
        const chunks = Array.isArray(toolArgs.ReplacementChunks) ? toolArgs.ReplacementChunks : null;
        const diffLines = [];
        let added = 0;
        let removed = 0;

        if (chunks && chunks.length > 0) {
            for (const ch of chunks) {
                const tLines = String(ch.TargetContent || '').split(/\r?\n/);
                const rLines = String(ch.ReplacementContent || '').split(/\r?\n/);
                for (const l of tLines) {
                    if (l.trim() || tLines.length === 1) {
                        diffLines.push(`- ${l}`);
                        removed++;
                    }
                }
                for (const l of rLines) {
                    if (l.trim() || rLines.length === 1) {
                        diffLines.push(`+ ${l}`);
                        added++;
                    }
                }
            }
        } else if (target || replacement) {
            const tLines = target ? target.split(/\r?\n/) : [];
            const rLines = replacement ? replacement.split(/\r?\n/) : [];
            for (const l of tLines.slice(0, 40)) {
                diffLines.push(`- ${l}`);
                removed++;
            }
            for (const l of rLines.slice(0, 60)) {
                diffLines.push(`+ ${l}`);
                added++;
            }
        }
        if (added === 0 && removed === 0) return null;
        const patchText = diffLines.slice(0, 80).join('\n');
        return {
            id: stepId || `diff_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            path: fileName,
            filePath: fileName,
            fullPath: fullPath,
            additions: added,
            addedLines: added,
            deletions: removed,
            removedLines: removed,
            diffPatch: patchText,
            diffText: patchText,
            timestamp: new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
        };
    }

    if (name.includes('write_to_file') || name.includes('write_file') || name.includes('create_file')) {
        const content = String(toolArgs.CodeContent || toolArgs.content || toolArgs.text || '');
        if (!content) return null;
        const lines = content.split(/\r?\n/);
        const diffLines = lines.slice(0, 50).map(l => `+ ${l}`);
        const patchText = diffLines.join('\n');
        return {
            id: stepId || `diff_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            path: fileName,
            filePath: fileName,
            fullPath: fullPath,
            additions: lines.length,
            addedLines: lines.length,
            deletions: 0,
            removedLines: 0,
            diffPatch: patchText,
            diffText: patchText,
            timestamp: new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
        };
    }
    return null;
}

function loadSessionMessages(conversationId) {
    const messages = [];
    try {
        const logFile = resolveBestTranscriptFile(conversationId);
        if (!logFile || !fs.existsSync(logFile)) return messages;
        const content = fs.readFileSync(logFile, 'utf8');
        const lines = content.split(/\r?\n/).filter(Boolean);
        let pendingThinkingParts = [];
        let pendingTools = [];
        let pendingDiffs = [];

        for (const line of lines) {
            try {
                const obj = JSON.parse(line);
                if (obj.type === 'USER_INPUT' && obj.content) {
                    const text = extractCleanUserMessage(obj.content);
                    if (text &&
                        !text.startsWith('You receive messages automatically') &&
                        !text.startsWith('The following is a <SYSTEM_MESSAGE>')) {
                        pendingThinkingParts = [];
                        pendingTools = [];
                        pendingDiffs = [];
                        const timeLabel = obj.created_at
                            ? new Date(obj.created_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
                            : 'ПК';
                        messages.push({
                            id: `step_${messages.length}_${obj.step_index || 0}_u`,
                            role: 'USER',
                            content: text.slice(0, 3500),
                            timestamp: timeLabel
                        });
                    }
                } else if (obj.type === 'GENERIC' && typeof obj.content === 'string' && pendingTools.length > 0) {
                    const targetTool = pendingTools.find(t => !t.output) || pendingTools[pendingTools.length - 1];
                    if (targetTool) {
                        const cleanOut = obj.content
                            .replace(/^Created At:.*\r?\n/gm, '')
                            .replace(/^Completed At:.*\r?\n/gm, '')
                            .trim();
                        targetTool.output = cleanOut.slice(0, 1200);
                    }
                } else if (obj.type === 'PLANNER_RESPONSE') {
                    const rawStepThinking = cleanRussianThinkingParagraphs(String(obj.thinking || ''));
                    if (rawStepThinking && !pendingThinkingParts.includes(rawStepThinking)) {
                        pendingThinkingParts.push(rawStepThinking);
                    }
                    if (Array.isArray(obj.tool_calls)) {
                        for (const tc of obj.tool_calls) {
                            const tName = tc.name || 'pc_tool';
                            let tArgs = {};
                            try {
                                tArgs = typeof tc.arguments_json === 'string'
                                    ? JSON.parse(tc.arguments_json)
                                    : (tc.args || tc.arguments || {});
                            } catch (_) {}
                            const summaryText = formatToolSummary(tName, tArgs);
                            const tcId = tc.id || `tc_${obj.step_index || 0}_${pendingTools.length}`;
                            if (!pendingTools.some(pt => pt.name === tName && pt.summary === summaryText)) {
                                pendingTools.push({
                                    id: tcId,
                                    name: tName,
                                    summary: summaryText,
                                    status: 'COMPLETED',
                                    output: ''
                                });
                            }
                            const extractedDiff = extractDiffFromToolArgs(tName, tArgs, `diff_${tcId}`);
                            if (extractedDiff && !pendingDiffs.some(d => d.filePath === extractedDiff.filePath && d.addedLines === extractedDiff.addedLines)) {
                                pendingDiffs.push(extractedDiff);
                            }
                        }
                    }
                    if (obj.content && typeof obj.content === 'string') {
                        let rawContent = obj.content;
                        const tagMatch = rawContent.match(/<thinking>([\s\S]*?)<\/thinking>/i);
                        if (tagMatch && tagMatch[1]) {
                            const innerT = cleanRussianThinkingParagraphs(tagMatch[1]);
                            if (innerT && !pendingThinkingParts.includes(innerT)) {
                                pendingThinkingParts.push(innerT);
                            }
                            rawContent = rawContent.replace(/<thinking>[\s\S]*?<\/thinking>/gi, '').trim();
                        }
                        // Сохраняем все кликабельные ссылки [text](https://...) и [text](file:///...)!
                        const text = rawContent
                            .replace(/<truncated\s+\d+\s+bytes>/gi, '\n')
                            .trim();
                        if (text.length > 0 && !isInternalSubagentOrchestrationText(text)) {
                            const timeLabel = obj.created_at
                                ? new Date(obj.created_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
                                : 'ПК';
                            const combinedThinking = cleanRussianThinkingParagraphs(pendingThinkingParts.join('\n\n')).slice(0, 3500);
                            messages.push({
                                id: `step_${messages.length}_${obj.step_index || 0}_a`,
                                role: 'AGENT',
                                content: text.slice(0, 8000),
                                thinking: combinedThinking,
                                thinkingDurationSec: obj.thinking_duration_sec || (combinedThinking ? 4 : 0),
                                toolCalls: pendingTools.slice(0, 16),
                                createdDiffs: pendingDiffs.slice(0, 10),
                                timestamp: timeLabel
                            });
                            pendingThinkingParts = [];
                            pendingTools = [];
                            pendingDiffs = [];
                        }
                    }
                }
            } catch (_) {}
        }
    } catch (_) {}
    return messages.slice(-60);
}

function deleteTemporaryCliConversation(tempId, keepId) {
    if (!tempId || tempId === keepId || tempId.length < 20) return;
    try {
        for (const baseDir of [CLI_BASE_DIR, IDE_BASE_DIR]) {
            const brainDir = path.join(baseDir, 'brain', tempId);
            const convDb = path.join(baseDir, 'conversations', `${tempId}.db`);
            const annotPb = path.join(baseDir, 'annotations', `${tempId}.pbtxt`);
            try { if (fs.existsSync(brainDir)) fs.rmSync(brainDir, { recursive: true, force: true }); } catch (_) {}
            try { if (fs.existsSync(convDb)) fs.unlinkSync(convDb); } catch (_) {}
            try { if (fs.existsSync(annotPb)) fs.unlinkSync(annotPb); } catch (_) {}
            const sumPath = path.join(baseDir, 'conversation_summaries.db');
            if (fs.existsSync(sumPath)) {
                try {
                    const db = new DatabaseSync(sumPath);
                    db.prepare('DELETE FROM conversation_summaries WHERE conversation_id = ?').run(tempId);
                    db.close();
                } catch (_) {}
            }
        }
    } catch (_) {}
}

function extractThinkingAndCleanReply(rawReply, explicitThinking = '') {
    let reply = (rawReply || '').trim();
    if (reply.startsWith('{"conversation_id"') || reply.startsWith('{"event"')) {
        try {
            const parsedObj = JSON.parse(reply.split(/\r?\n/).pop());
            const resObj = parsedObj.result || parsedObj;
            if (!resObj.response || resObj.status === 'ERROR') {
                return { reply: '', thinking: '' };
            }
            reply = String(resObj.response).trim();
        } catch (_) {
            return { reply: '', thinking: '' };
        }
    }
    const thinkingBlocks = [];
    if (explicitThinking && String(explicitThinking).trim()) {
        thinkingBlocks.push(String(explicitThinking).trim());
    }
    const regex = /<thinking>([\s\S]*?)<\/thinking>/gi;
    let m;
    while ((m = regex.exec(reply)) !== null) {
        const inner = (m[1] || '').trim();
        if (inner && !thinkingBlocks.includes(inner)) {
            thinkingBlocks.push(inner);
        }
    }
    reply = reply.replace(/<thinking>[\s\S]*?<\/thinking>/gi, '').trim();
    const openIdx = reply.indexOf('<thinking>');
    if (openIdx >= 0) {
        const trailingThought = reply.slice(openIdx + 10).trim();
        if (trailingThought && !thinkingBlocks.includes(trailingThought)) {
            thinkingBlocks.push(trailingThought);
        }
        reply = reply.slice(0, openIdx).trim();
    }
    return {
        reply,
        thinking: thinkingBlocks.join('\n\n').trim()
    };
}

function extractPhoneActionsFromText(text) {
    const actions = [];
    if (!text) return actions;
    const regex = /```phone_action\s*([\s\S]*?)```/gi;
    let match;
    while ((match = regex.exec(text)) !== null) {
        const rawJson = (match[1] || '').trim();
        if (!rawJson) continue;
        try {
            const obj = JSON.parse(rawJson);
            if (obj && typeof obj === 'object' && (obj.action || obj.tool || obj.type)) {
                actions.push(obj);
            }
        } catch (_) {}
    }
    return actions;
}

function isExplicitPcWorkspaceTask(cleanPrompt) {
    const lower = (cleanPrompt || '').toLowerCase();
    return (
        lower.includes('readme') ||
        lower.includes('server.js') ||
        lower.includes('mainscreen.kt') ||
        lower.includes('gradlew') ||
        lower.includes('scratch') ||
        lower.includes('hello.txt') ||
        lower.includes('файл') ||
        lower.includes('создай') ||
        lower.includes('прочитай') ||
        lower.includes('проверь') ||
        lower.includes('структур') ||
        lower.includes('проект') ||
        lower.includes('в папке') ||
        lower.includes('команд') ||
        lower.includes('скилл') ||
        lower.includes('skill') ||
        lower.includes('mcp') ||
        lower.includes('плагин') ||
        lower.includes('пульс') ||
        lower.includes('станци') ||
        lower.includes('workstation') ||
        lower.includes('sysinfo') ||
        lower.includes('диагностик') ||
        lower.includes('git ')
    );
}

// ============================================================================
// Пул прогретых процессов `agy.exe --input-format stream-json --output-format stream-json`
// Обеспечивает мгновенный старт (< 5 с до первого токена на Gemini 4) и сохранение
// контекста многошагового агентского цикла без холодного старта языкового сервера.
// ============================================================================
const sessionWorkers = new Map(); // key: `${conversationId}::${model}::${cwd}` -> worker
const spareWorkers = new Map();   // key: `${model}::${cwd}` -> worker
const phoneLoopWorkers = new Map(); // key: phoneSessionKey -> worker
const inFlightTurns = new Map();  // key: dedupKey -> { promise, listeners, state }

function createStreamJsonWorker({ conversationId = '', model = 'gemini-4-argon-high', cwd = CHAT_FAST_WORKSPACE, isEphemeral = false, warmUp = false }) {
    const args = [
        '--input-format', 'stream-json',
        '--output-format', 'stream-json',
        '--model', model,
        '--dangerously-skip-permissions',
        '-p='
    ];
    if (conversationId) {
        args.unshift('--conversation', conversationId);
    }

    const child = spawn(AGY_BIN, args, {
        cwd: (cwd && fs.existsSync(cwd)) ? cwd : DEFAULT_WORKSPACE,
        windowsHide: true,
        env: process.env
    });

    const worker = {
        id: `w_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        child,
        args,
        model,
        cwd,
        requestedConversationId: conversationId,
        cliConversationId: conversationId || '',
        isEphemeral,
        isReady: false,
        warmingUp: Boolean(warmUp),
        busy: false,
        closed: false,
        lineBuffer: '',
        readyCallbacks: [],
        activeTurnHandler: null,
        createdAt: Date.now(),
        lastUsedAt: Date.now()
    };

    child.stdout.on('data', (chunk) => {
        worker.lineBuffer += chunk.toString('utf8');
        const lines = worker.lineBuffer.split(/\r?\n/);
        worker.lineBuffer = lines.pop() || '';
        for (const rawLine of lines) {
            const trimmed = rawLine.trim();
            if (!trimmed) continue;
            let obj;
            try {
                obj = JSON.parse(trimmed);
            } catch (_) {
                continue;
            }
            const u = obj.step_update || obj;
            const detectedId = obj.conversation_id || u.conversation_id || (obj.result && obj.result.conversation_id) || '';
            if (detectedId && !worker.cliConversationId) {
                worker.cliConversationId = detectedId;
                if (worker.isEphemeral) {
                    ephemeralCliIds.add(detectedId);
                }
            }
            if (worker.warmingUp) {
                if (obj.event === 'result') {
                    worker.warmingUp = false;
                    worker.isReady = true;
                    while (worker.readyCallbacks.length > 0) {
                        const cb = worker.readyCallbacks.shift();
                        try { cb(worker); } catch (_) {}
                    }
                }
                continue;
            }
            if (obj.event === 'init') {
                worker.isReady = true;
                while (worker.readyCallbacks.length > 0) {
                    const cb = worker.readyCallbacks.shift();
                    try { cb(worker); } catch (_) {}
                }
            }
            if (worker.activeTurnHandler) {
                try {
                    worker.activeTurnHandler(obj);
                } catch (_) {}
            }
        }
    });

    child.stderr.on('data', (chunk) => {
        if (!worker.warmingUp && worker.activeTurnHandler) {
            try {
                worker.activeTurnHandler({ event: 'stderr', text: chunk.toString('utf8') });
            } catch (_) {}
        }
    });

    child.on('close', (code) => {
        worker.closed = true;
        worker.warmingUp = false;
        worker.isReady = false;
        while (worker.readyCallbacks.length > 0) {
            const cb = worker.readyCallbacks.shift();
            try { cb(worker); } catch (_) {}
        }
        if (worker.activeTurnHandler) {
            try {
                worker.activeTurnHandler({ event: 'process_closed', code });
            } catch (_) {}
        }
        if (worker.isEphemeral && worker.cliConversationId) {
            deleteTemporaryCliConversation(worker.cliConversationId, '');
        }
    });

    child.on('error', (err) => {
        worker.closed = true;
        worker.warmingUp = false;
        if (worker.activeTurnHandler) {
            try {
                worker.activeTurnHandler({ event: 'process_error', error: err.message });
            } catch (_) {}
        }
    });

    if (warmUp) {
        const warmGuard = setTimeout(() => {
            if (worker.warmingUp) {
                worker.warmingUp = false;
                worker.isReady = true;
                while (worker.readyCallbacks.length > 0) {
                    const cb = worker.readyCallbacks.shift();
                    try { cb(worker); } catch (_) {}
                }
            }
        }, 14000);
        worker.readyCallbacks.push(() => clearTimeout(warmGuard));
        try {
            const warmMsg = JSON.stringify({
                event: 'user',
                message: {
                    content: '[SYSTEM_WARMUP] Ответь одним словом: Ready.'
                }
            }) + '\n';
            child.stdin.write(warmMsg, 'utf8');
        } catch (_) {
            clearTimeout(warmGuard);
            worker.warmingUp = false;
        }
    }

    return worker;
}

function killWorkerProcess(worker) {
    if (!worker) return;
    worker.closed = true;
    worker.warmingUp = false;
    try {
        if (worker.child && worker.child.pid) {
            exec(`taskkill /PID ${worker.child.pid} /T /F`, () => {});
        } else if (worker.child) {
            worker.child.kill();
        }
    } catch (_) {}
}

function waitForWorkerReady(worker, timeoutMs = 15000) {
    if (!worker.warmingUp || worker.closed) return Promise.resolve(worker);
    return new Promise((resolve) => {
        const t = setTimeout(() => {
            worker.warmingUp = false;
            resolve(worker);
        }, timeoutMs);
        worker.readyCallbacks.push(() => {
            clearTimeout(t);
            resolve(worker);
        });
    });
}

function ensureSpareWorker(model = 'gemini-4-argon-high', cwd = CHAT_FAST_WORKSPACE) {
    const key = `${model}::${cwd}`;
    const existing = spareWorkers.get(key);
    if (existing && !existing.closed && !existing.busy) {
        return existing;
    }
    const w = createStreamJsonWorker({
        conversationId: '',
        model,
        cwd,
        isEphemeral: true,
        warmUp: true
    });
    spareWorkers.set(key, w);
    return w;
}

function prewarmPcSessionWorker(conversationId, model = 'gemini-4-argon-high', cwd = DEFAULT_WORKSPACE) {
    if (!conversationId || conversationId.length < 32 || conversationId === 'phone-local-storage') return null;
    const hasDb = syncConversationBeforeCli(conversationId);
    if (!hasDb) return null;
    const key = `${conversationId}::${model}::${cwd}`;
    const existing = sessionWorkers.get(key);
    if (existing && !existing.closed) {
        return existing;
    }
    const w = createStreamJsonWorker({
        conversationId,
        model,
        cwd,
        isEphemeral: false
    });
    sessionWorkers.set(key, w);
    return w;
}

function invalidateAllIdleWorkers() {
    for (const [k, w] of spareWorkers.entries()) {
        if (!w.busy) {
            killWorkerProcess(w);
            spareWorkers.delete(k);
        }
    }
    for (const [k, w] of sessionWorkers.entries()) {
        if (!w.busy) {
            killWorkerProcess(w);
            sessionWorkers.delete(k);
        }
    }
    setTimeout(() => {
        const wHigh = ensureSpareWorker('gemini-4-argon-high', CHAT_FAST_WORKSPACE);
        waitForWorkerReady(wHigh, 12000).then(() => {
            setTimeout(() => ensureSpareWorker('gemini-4-argon-high', DEFAULT_WORKSPACE), 400);
            setTimeout(() => ensureSpareWorker('gemini-4-argon-low', CHAT_FAST_WORKSPACE), 900);
        });
    }, 300);
}

// Прогреваем пул воркеров Gemini 4 последовательно при старте моста (без конкуренции за SQLite)
setTimeout(() => {
    const wHigh = ensureSpareWorker('gemini-4-argon-high', CHAT_FAST_WORKSPACE);
    waitForWorkerReady(wHigh, 12000).then(() => {
        setTimeout(() => ensureSpareWorker('gemini-4-argon-high', DEFAULT_WORKSPACE), 400);
        setTimeout(() => ensureSpareWorker('gemini-4-argon-low', CHAT_FAST_WORKSPACE), 900);
    });
}, 300);

function buildSmartphoneToolsSystemPrompt() {
    return (
        `Ты работаешь в режиме **Локальные ресурсы смартфона (PHONE_LOCAL_RESOURCES)**.\n` +
        `У тебя есть прямой доступ к инструментам смартфона пользователя. Ты САМ решаешь, какой инструмент вызвать на каждом шаге.\n` +
        `Когда тебе нужно найти файлы, прочитать документ (включая PDF/DOCX/TXT/MD), создать или пропатчить файл, посмотреть медиафайлы/фото, контакты или открыть приложение — ` +
        `кратко сообщи пользователю о действии, если это полезно, и выведи блок \`\`\`phone_action ... \`\`\` с JSON-вызовом инструмента:\n\n` +
        `Доступные инструменты смартфона (\`phone_action\`):\n` +
        `1. \`search_files\` — поиск файлов и документов на смартфоне по ключевому слову или расширению:\n` +
        `   \`\`\`phone_action\n   {"action":"search_files","query":"договор","extension":"pdf"}\n   \`\`\`\n` +
        `2. \`search_media\` — поиск фото, скриншотов, видео или аудио в MediaStore (поддерживает фильтр по дате \`"today"\`, \`"week"\`, \`"all"\`):\n` +
        `   \`\`\`phone_action\n   {"action":"search_media","mediaType":"image","dateFilter":"today","query":""}\n   \`\`\`\n` +
        `3. \`read_file\` — чтение содержимого файла на смартфоне (автоматически извлекает чистый текст из PDF, DOCX, TXT, MD, JSON, XML, кода):\n` +
        `   \`\`\`phone_action\n   {"action":"read_file","path":"/sdcard/Download/example.pdf"}\n   \`\`\`\n` +
        `4. \`write_file\` — создание или полная перезапись файла на смартфоне:\n` +
        `   \`\`\`phone_action\n   {"action":"write_file","path":"/sdcard/Download/plan.md","content":"1. Первый пункт\\n2. Второй пункт\\n3. Третий пункт"}\n   \`\`\`\n` +
        `5. \`patch_file\` — точечное изменение (замена фрагмента или дозапись) в существующем файле на смартфоне:\n` +
        `   \`\`\`phone_action\n   {"action":"patch_file","path":"/sdcard/Download/plan.md","target":"3. Третий пункт","replacement":"3. Третий пункт\\n4. Четвёртый пункт"}\n   \`\`\`\n` +
        `6. \`list_dir\` — просмотр содержимого папки на смартфоне:\n` +
        `   \`\`\`phone_action\n   {"action":"list_dir","path":"/sdcard/Download"}\n   \`\`\`\n` +
        `7. \`contacts\` — поиск контактов на смартфоне:\n` +
        `   \`\`\`phone_action\n   {"action":"contacts","query":"Иван"}\n   \`\`\`\n` +
        `8. \`open_app\` — запуск установленного приложения на смартфоне:\n` +
        `   \`\`\`phone_action\n   {"action":"open_app","query":"Telegram"}\n   \`\`\`\n` +
        `9. \`share\` — отправка текста/файла через системное меню Поделиться:\n` +
        `   \`\`\`phone_action\n   {"action":"share","content":"Текст для отправки"}\n   \`\`\`\n\n` +
        `ПРАВИЛА МНОГОШАГОВОГО ЦИКЛА НА СМАРТФОНЕ:\n` +
        `- Если нужно найти файл и пересказать его (например: «Найди pdf про договор и перескажи главное»), НЕ выдумывай содержимое! На Шаге 1 вызови \`search_files\`, дождись списка реальных файлов от телефона, на Шаге 2 вызови \`read_file\` для найденного пути, и только после получения реального текста документа дай подробный ответ.\n` +
        `- Если нужно сначала создать файл, а затем изменить его (например: «Создай Download/plan.md со списком из 3 пунктов, потом добавь четвёртый»), выполни \`write_file\`, а затем \`patch_file\`.\n` +
        `- Все факты (имена файлов, даты, текст договора, список фото за сегодня) бери СТРОГО из вывода инструментов.`
    );
}

/**
 * Разбирает потоковый текст с поддержкой нескольких блоков `<thinking>...</thinking>`
 * и вырезает сырые блоки ```` ```phone_action ... ``` ```` из видимого ответа во время стриминга.
 */
function parseStreamingThinkingAndReply(rawText, nativeThinkingParts = []) {
    let working = String(rawText || '');
    const extractedThoughts = [...nativeThinkingParts];

    const completeThinkRegex = /<thinking>([\s\S]*?)<\/thinking>/gi;
    let m;
    while ((m = completeThinkRegex.exec(working)) !== null) {
        const t = cleanRussianThinkingParagraphs(m[1] || '');
        if (t && !extractedThoughts.includes(t)) {
            extractedThoughts.push(t);
        }
    }
    working = working.replace(completeThinkRegex, '');

    const lowerWorking = working.toLowerCase();
    const openIdx = lowerWorking.indexOf('<thinking>');
    if (openIdx >= 0) {
        const liveThought = cleanRussianThinkingParagraphs(working.slice(openIdx + 10));
        if (liveThought && !extractedThoughts.includes(liveThought)) {
            extractedThoughts.push(liveThought);
        }
        working = working.slice(0, openIdx);
    } else {
        // Скрываем незаконченный префикс тега `<thinking>` в самом конце строки
        const partialTagMatch = working.match(/<(?:t(?:h(?:i(?:n(?:k(?:i(?:n(?:g)?)?)?)?)?)?)?)?$/i);
        if (partialTagMatch) {
            working = working.slice(0, partialTagMatch.index);
        }
    }

    // Скрываем завершённые и незавершённые блоки ```phone_action ... ``` из видимого текста чата
    working = working.replace(/```phone_action[\s\S]*?```/gi, '');
    const openActionIdx = working.toLowerCase().indexOf('```phone_action');
    if (openActionIdx >= 0) {
        working = working.slice(0, openActionIdx);
    }

    return {
        combinedThinking: cleanRussianThinkingParagraphs(extractedThoughts.join('\n\n')),
        cleanVisibleReply: working.trimStart()
    };
}

// --- Этап 4: реестр расширений → модели и дочерние задачи -------------------

// Выбор навыка по `/имя-навыка`: каталог — только метаданные, полные
// инструкции загружаются ТОЛЬКО выбранного навыка. Отключённый/отсутствующий —
// явный отказ (запрет доступа действует), без выдуманных инструкций.
function selectSkillForPrompt(promptText) {
    try {
        const catalog = extensionRuntime.buildSkillCatalog([
            { dir: path.join(DEFAULT_WORKSPACE, '.agents', 'skills'), source: 'workspace' },
            { dir: SKILLS_DIR, source: 'global' },
            { dir: path.join(IDE_BASE_DIR, 'builtin', 'skills'), source: 'builtin' }
        ]);
        const det = extensionRuntime.detectSkillInvocation(promptText, catalog);
        if (!det.skillId) {
            return { skillId: null, restPrompt: promptText, block: '', refusal: null };
        }
        const sel = extensionRuntime.loadSelectedSkill(det.skillId, { catalog });
        if (sel.status !== 'ok') {
            return { skillId: det.skillId, restPrompt: det.restPrompt, block: '', refusal: sel.refusal };
        }
        return {
            skillId: det.skillId,
            restPrompt: det.restPrompt,
            block: extensionRuntime.composeSkillInstructionBlock(sel.skill),
            refusal: null
        };
    } catch (_) {
        return { skillId: null, restPrompt: promptText, block: '', refusal: null };
    }
}

// Вызов MCP-инструмента через реестр: строго по serverId, без взаимозамены
// одноимённых инструментов; авторизация — из конфига (без утечек).
function callMcpToolViaRegistry(serverId, toolName, args, { signal, timeoutMs } = {}) {
    const raw = findRawMcpEntry(serverId);
    const descriptor = raw ? mcpClientLib.buildMcpDescriptor(serverId, raw.cfg) : undefined;
    return mcpClientManager.callTool(serverId, toolName, (args && typeof args === 'object') ? args : {}, {
        descriptor,
        signal,
        requestTimeoutMs: timeoutMs
    });
}

// Реальные схемы разрешённых инструментов для адаптеров с нативным tool-calling
// (OpenRouter). Источник схем — только живой tools/list включённых серверов
// реестра (рукопожатие Этапа 3); ничего не угадывается. Политика: запрет
// pcMcpToolsExec → пустой набор (запрет доступа действует). Инструменты
// смартфона в function-схемы не включаются — они выполняются только на
// смартфоне (phone_action) и на ПК не перенаправляются.
async function buildOpenRouterToolSet() {
    const empty = { tools: [], nameToRef: new Map(), catalog: [], allowedRefs: new Set() };
    try {
        const perms = loadAiPermissionsConfig();
        if (perms && perms.pcMcpToolsExec === false) return empty;
    } catch (_) {}
    try {
        const servers = await listMcpServers();
        const liveServers = [];
        for (const s of servers) {
            if (!s.enabled) continue;
            const live = mcpClientManager.getLive(s.name);
            if (live && live.status === 'connected' && Array.isArray(live.toolsDetailed) && live.toolsDetailed.length > 0) {
                liveServers.push({ id: s.name, tools: live.toolsDetailed });
                continue;
            }
            // Нет живого tools/list — свежее реальное рукопожатие, не догадки.
            const raw = findRawMcpEntry(s.name);
            if (!raw) continue;
            const probe = await mcpClientManager.probe(mcpClientLib.buildMcpDescriptor(s.name, raw.cfg), {
                handshakeTimeoutMs: 8000,
                requestTimeoutMs: 15000
            });
            mcpLiveState.set(s.name, probe);
            if (probe.ok && probe.toolsDetailed.length > 0) {
                liveServers.push({ id: s.name, tools: probe.toolsDetailed });
            }
        }
        const catalog = extensionRuntime.buildToolCatalog({ mcpServers: liveServers, includePhoneTools: false });
        const { allowed } = extensionRuntime.filterAllowedTools(catalog, { allowAllPc: true });
        const { tools, nameToRef } = extensionRuntime.toOpenAiToolSchemas(allowed);
        return { tools, nameToRef, catalog, allowedRefs: new Set(allowed.map(t => t.ref)) };
    } catch (_) {
        return empty;
    }
}

// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): собственный исполнитель lifecycle
// hooks по проверенному контракту builtin/skills/agy-customizations/docs/hooks.md.
// Политика: недопустимые/неподдерживаемые hooks НИКОГДА не запускаются молча —
// каждый отказ (неизвестное событие, тип ≠ "command", enabled:false, запрет
// прав, тайм-аут, не-JSON на stdout) видимо попадает в события хода.
// Источники hooks.json (docs/plugins.md + docs/hooks.md):
//   ~/.gemini/config/hooks.json — пользовательский customization root;
//   <workspace>/.agents/hooks.json — рабочий каталог;
//   <plugin>/hooks.json — только ВКЛЮЧЁННЫХ плагинов (config.json выигрывает
//   у plugin.json disabled — docs/plugins.md).
function collectPluginHookSources() {
    const sources = [];
    let configPlugins = {};
    try {
        const cfg = JSON.parse(fs.readFileSync(path.join(GEMINI_ROOT_DIR, 'config', 'config.json'), 'utf8'));
        if (cfg.plugins && typeof cfg.plugins === 'object') configPlugins = cfg.plugins;
    } catch (_) {}
    let pluginDirs = [];
    try { pluginDirs = fs.readdirSync(PLUGINS_DIR); } catch (_) { return sources; }
    for (const dirName of pluginDirs) {
        const pluginDir = path.join(PLUGINS_DIR, dirName);
        const hooksPath = path.join(pluginDir, 'hooks.json');
        if (!fs.existsSync(hooksPath)) continue;
        let enabled;
        const cfgEntry = configPlugins[dirName];
        if (cfgEntry && typeof cfgEntry === 'object' && typeof cfgEntry.enabled === 'boolean') {
            enabled = cfgEntry.enabled;
        } else {
            // Фолбэк на plugin.json («disabled»): manifest может быть JSONC —
            // без полного JSONC-парсера честно пробуем строгий JSON.
            enabled = true;
            try {
                const manifest = JSON.parse(fs.readFileSync(path.join(pluginDir, 'plugin.json'), 'utf8'));
                if (manifest && manifest.disabled === true) enabled = false;
            } catch (_) {}
        }
        if (!enabled) continue; // отключённый плагин не грузит hooks (docs/plugins.md)
        sources.push({ path: hooksPath, origin: `plugin:${dirName}` });
    }
    return sources;
}

function loadExtensionHooks({ workspace = DEFAULT_WORKSPACE } = {}) {
    const sources = [
        { path: path.join(GEMINI_ROOT_DIR, 'config', 'hooks.json'), origin: 'user-config' },
        { path: path.join(workspace, '.agents', 'hooks.json'), origin: 'workspace' },
        ...collectPluginHookSources()
    ].filter(s => fs.existsSync(s.path));
    return hookEngine.loadHooksFromSources(sources);
}

// Контекст полезной нагрузки hook'ов (hooks.md, Common Input Fields).
// Пути transcript — реальные пути моста (brain/<conv>/.system_generated/logs),
// не выдуманный путь из примера документации.
function buildHookPayloadContext({ conversationId = '', modelName = 'auto', lastUserInput = '' } = {}) {
    return {
        conversationId,
        workspacePaths: [DEFAULT_WORKSPACE],
        modelName,
        lastUserInput,
        transcriptPath: conversationId
            ? path.join(BRAIN_DIR, conversationId, '.system_generated', 'logs', 'transcript.jsonl')
            : undefined
    };
}

// Runner для runToolCallLoop: события PreToolUse/PostToolUse/PreInvocation/
// PostInvocation/Stop/SessionStart. CLI-ходы (agy) используют собственную
// систему кастомизаций CLI — мост свои hooks там НЕ запускает (без двойного
// исполнения).
function buildHookRunner(ctx = {}) {
    let loaded = { namedHooks: [], refusals: [], issues: [] };
    try {
        loaded = loadExtensionHooks();
    } catch (err) {
        loaded = { namedHooks: [], refusals: [], issues: [`hooks не загружены: ${err.message}`] };
    }
    let perms = {};
    try { perms = loadAiPermissionsConfig() || {}; } catch (_) {}
    const permissions = {
        pcMcpToolsExec: perms.pcMcpToolsExec !== false && perms.pcPluginsMcpSkillsAccess !== false,
        pcTerminalExec: perms.pcTerminalExec !== false && perms.pcPowerShellExecution !== false
    };
    const payloadCtx = buildHookPayloadContext(ctx);
    const run = (event, inputCtx) => hookEngine.runEventHooks(
        loaded.namedHooks,
        event,
        Object.assign({}, payloadCtx, inputCtx || {}),
        { permissions, homeDir: os.homedir() }
    );
    return {
        // Парсинг-отказы видимы сразу при построении runner'а.
        initialRefusals: loaded.refusals.concat(
            loaded.issues.map(msg => ({ hookName: null, event: null, sourcePath: null, code: 'load_error', reason: msg }))
        ),
        async onSessionStart() {
            const r = await run('SessionStart', {});
            return { injectSteps: r.injectSteps, refusals: r.refusals };
        },
        async beforeToolCall({ ref, args, callId, stepIdx } = {}) {
            const r = await run('PreToolUse', { toolCall: { name: ref, args }, stepIdx, callId });
            const d = r.decision || { decision: 'allow', reason: '', notice: null, overwrite: {} };
            const overwrite = d.overwrite || {};
            const argsOut = Object.keys(overwrite).length > 0 ? Object.assign({}, args, overwrite) : args;
            return {
                allow: d.decision === 'allow',
                args: argsOut,
                notice: d.notice,
                reason: d.reason,
                permissionOverrides: d.permissionOverrides,
                refusals: r.refusals
            };
        },
        async afterToolCall({ ref, args, callId, result, stepIdx, error } = {}) {
            const r = await run('PostToolUse', { toolCall: { name: ref, args }, stepIdx, callId, error });
            return { refusals: r.refusals };
        },
        async onInvocation({ event, invocationNum, initialNumSteps, wouldStop } = {}) {
            const r = await run(event === 'PostInvocation' ? 'PostInvocation' : 'PreInvocation', {
                invocationNum,
                initialNumSteps
            });
            return { injectSteps: r.injectSteps, terminationBehavior: r.terminationBehavior, refusals: r.refusals };
        },
        async onStop({ terminationReason } = {}) {
            const r = await run('Stop', { executionNum: 1, terminationReason });
            return {
                shouldContinue: Boolean(r.stopDecision && r.stopDecision.shouldContinue),
                reason: (r.stopDecision && r.stopDecision.reason) || '',
                refusals: r.refusals
            };
        }
    };
}

// SessionStart выполняется один раз на разговор за жизнь процесса моста.
const hookSessionsStarted = new Set();

// Реестр активных и недавно завершённых ходов для бесшовного восстановления при обрыве SSE
const activeTurnsById = new Map();
const turnCancellations = new Map();
const activeTurnsByConv = new Map();
const resetConversationsSet = new Set();

function recordActiveTurnSnapshot(turnId, convId, patch) {
    const prev = (turnId ? activeTurnsById.get(turnId) : (convId && activeTurnsByConv.get(convId))) || {
        turnId: turnId || `turn_${Date.now()}`,
        conversationId: convId || '',
        status: 'running',
        thinking: '',
        reply: '',
        toolCalls: [],
        createdDiffs: [],
        phoneActions: [],
        generatedMedia: [],
        startedAtMs: Date.now(),
        updatedAtMs: Date.now()
    };
    const next = {
        ...prev,
        ...patch,
        turnId: turnId || prev.turnId,
        conversationId: convId || prev.conversationId,
        updatedAtMs: Date.now()
    };
    if (next.turnId) activeTurnsById.set(next.turnId, next);
    if (next.conversationId) activeTurnsByConv.set(next.conversationId, next);
    // Очистка старых записей (> 20 мин)
    if (activeTurnsById.size > 120) {
        const cutoff = Date.now() - 20 * 60 * 1000;
        for (const [k, v] of activeTurnsById.entries()) {
            if (v.updatedAtMs < cutoff && v.status !== 'running') activeTurnsById.delete(k);
        }
    }
    return next;
}

async function executeAgyTurnStreaming(options, onEvent = () => {}) {
    const startMs = Date.now();
    const {
        turnId = `turn_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        rawUserPrompt = '',
        cleanUserPrompt = '',
        model = 'gemini-4-argon-high',
        mode = 'PC_REMOTE',
        conversationId = '',
        workspacePath = DEFAULT_WORKSPACE,
        allowTranscriptWrite = true,
        clientHistory = [],
        phoneContext = '',
        attachments = [],
        voiceMode = false,
        phoneToolObservation = '',
        phoneStepIndex = 0
    } = options;

    let effectivePromptText = (cleanUserPrompt || rawUserPrompt || '').trim() ||
        (Array.isArray(attachments) && attachments.length > 0 ? 'Проанализируй прикреплённые файлы и выполни задачу.' : 'Продолжай выполнение задачи.');

    // Этап 4: выбор навыка реестра. Метаданные — всегда, инструкции — только
    // выбранного навыка. Токен `/имя-навыка` из запроса убирается, чтобы
    // инструкции не дублировались раскрытием слэш-команд самого CLI.
    const skillSelection = (typeof selectSkillForPrompt === 'function')
        ? selectSkillForPrompt(effectivePromptText)
        : { skillId: null, restPrompt: effectivePromptText, block: '', refusal: null };
    if (skillSelection.skillId && skillSelection.restPrompt.trim()) {
        effectivePromptText = skillSelection.restPrompt.trim();
    }

    // Если выбрана модель OpenRouter (или префикс openrouter/ или содержит слэш провайдера вроде anthropic/, openai/, deepseek/, meta-llama/, qwen/, black-forest-labs/, google/ и т.д.)
    const isOpenRouterModel = Boolean(
        options.provider === 'OPENROUTER' ||
        String(model || '').startsWith('openrouter/') ||
        (String(model || '').includes('/') && !String(model || '').startsWith('models/'))
    );
    if (isOpenRouterModel && typeof executeOpenRouterStreamingTurn === 'function') {
        return executeOpenRouterStreamingTurn({
            ...options,
            turnId,
            cleanUserPrompt: effectivePromptText,
            skillInstructionBlock: skillSelection.block,
            skillRefusal: skillSelection.refusal
        }, onEvent);
    }

    const dedupKey = `${mode}::${turnId}::${phoneStepIndex}`;
    if (inFlightTurns.has(dedupKey)) {
        const activeTurn = inFlightTurns.get(dedupKey);
        activeTurn.listeners.add(onEvent);
        if (activeTurn.state.initEvent) {
            try { onEvent(activeTurn.state.initEvent); } catch (_) {}
        }
        if (activeTurn.state.lastThinking) {
            try {
                onEvent({
                    type: 'thinking_delta',
                    delta: activeTurn.state.lastThinking,
                    thinking: activeTurn.state.lastThinking,
                    fullThinking: activeTurn.state.lastThinking,
                    elapsedMs: Date.now() - activeTurn.state.startMs
                });
            } catch (_) {}
        }
        for (const tEntry of activeTurn.state.tools.values()) {
            try {
                onEvent({
                    type: 'tool',
                    id: tEntry.id,
                    toolName: tEntry.name,
                    summary: tEntry.summary,
                    details: tEntry.output,
                    status: tEntry.status,
                    tool: tEntry,
                    createdDiffs: Array.from(activeTurn.state.diffs.values()),
                    elapsedMs: Date.now() - activeTurn.state.startMs
                });
            } catch (_) {}
        }
        if (activeTurn.state.lastCleanReply) {
            try {
                onEvent({
                    type: 'text_delta',
                    delta: activeTurn.state.lastCleanReply,
                    text: activeTurn.state.lastCleanReply,
                    fullText: activeTurn.state.lastCleanReply,
                    elapsedMs: Date.now() - activeTurn.state.startMs
                });
            } catch (_) {}
        }
        return activeTurn.promise;
    }

    const validIds = cachedAgyModels.map(m => m.id);
    if (!validIds.includes(model)) {
        const failure = { type: 'done', ok: false, turnId, conversationId, model,
            reply: 'Выбранная модель недоступна. Выберите другую модель.', error: 'Unsupported model' };
        onEvent(failure);
        return failure;
    }
    const requestedModel = model;
    // Приоритет Gemini 4: НИКОГДА не занижаем выбранную пользователем модель gemini-4-argon-high!
    const effectiveCliModel = requestedModel;

    const isPhoneMode = mode === 'PHONE_ON_DEVICE' || mode === 'PHONE_LOCAL_RESOURCES';
    const wasContextReset = conversationId && resetConversationsSet.has(conversationId);
    const hasExistingSessionDb = Boolean(
        !isPhoneMode &&
        !wasContextReset &&
        conversationId &&
        conversationId.length >= 32 &&
        conversationId !== 'phone-local-storage' &&
        syncConversationBeforeCli(conversationId)
    );

    // Быстрые диалоговые и мультимодальные вопросы выполняем в лёгкой среде CHAT_FAST_WORKSPACE из прогретого пула,
    // а явные задачи по файлам/коду проекта — в рабочей папке DEFAULT_WORKSPACE!
    const isWorkspaceTask = !isPhoneMode && !voiceMode && (isExplicitPcWorkspaceTask(effectivePromptText) || hasExistingSessionDb);
    const targetCwd = isWorkspaceTask
        ? ((workspacePath && fs.existsSync(workspacePath)) ? workspacePath : DEFAULT_WORKSPACE)
        : CHAT_FAST_WORKSPACE;

    const sysPromptText = loadAgentSystemPrompt();
    let attachmentSection = '';
    if (Array.isArray(attachments) && attachments.length > 0) {
        attachmentSection = '\n\nПрикреплённые файлы:\n' +
            attachments.map(a => `- Файл "${a.name}" (${a.mimeType || 'file'}, ${a.sizeBytes || 0} байт)` +
                (a.pcPath ? ` сохранён на ПК: ${a.pcPath}` : '') +
                (a.textPreview ? `\nСодержимое файла:\n\`\`\`\n${String(a.textPreview).slice(0, 3000)}\n\`\`\`` : '')
            ).join('\n');
    }

    const openRouterProfile = null;
    const openRouterAdaptationBlock = openRouterProfile
        ? `\n\n<OPENROUTER_MODEL_ADAPTATION>\n` +
          `Активная модель чата (выбрана пользователем в OpenRouter): ${openRouterProfile.name} (\`${openRouterProfile.id}\`).\n` +
          `Профиль модели: категория=${openRouterProfile.primaryCategory || 'TEXT'}, модальность=${openRouterProfile.modality || 'text->text'}, контекстное окно=${Math.round((openRouterProfile.contextLength || 128000) / 1000)}K токенов.\n` +
          `Отвечай и адаптируй формат ответа в точном соответствии с профилем выбранной модели ${openRouterProfile.name} (\`${openRouterProfile.id}\`), сохраняя доступ ко всем инструментам рабочей области, Google Диску (/AntigravityCloud) и смартфону.\n` +
          `</OPENROUTER_MODEL_ADAPTATION>`
        : '';

    let promptForCli = '';
    const isPhoneContinuation = Boolean(isPhoneMode && (phoneToolObservation || phoneStepIndex > 0));

    if (isPhoneContinuation) {
        promptForCli =
            `<PHONE_TOOL_OBSERVATION>\n` +
            `Результат выполнения инструмента на смартфоне (шаг ${phoneStepIndex}):\n` +
            `${phoneToolObservation || phoneContext}\n` +
            `</PHONE_TOOL_OBSERVATION>\n\n` +
            `Используй фактический результат инструмента. Не выдумывай результаты или ход выполнения. ` +
            `Если для полного выполнения задачи пользователя («${effectivePromptText}») нужно вызвать ещё один инструмент смартфона (например, \`read_file\` или \`patch_file\`), выведи следующий блок \`\`\`phone_action ... \`\`\`. ` +
            `Если все данные получены и задача выполнена — дай финальный проверенный ответ пользователю на русском языке.${openRouterAdaptationBlock}`;
    } else if (voiceMode) {
        promptForCli =
            `${effectivePromptText}\n\n` +
            `<AGENT_SYSTEM_INSTRUCTION>\n` +
            `Ты общаешься с пользователем в голосовом режиме реального времени (как в ChatGPT Live Voice). ` +
            `Отвечай естественной русской устной речью (2–3 лаконичных предложения без символов разметки Markdown, без списков и без технических путей), чтобы ответ сразу озвучивался по предложениям.\n` +
            `</AGENT_SYSTEM_INSTRUCTION>${openRouterAdaptationBlock}${attachmentSection}`;
    } else if (isPhoneMode) {
        const phoneSys = buildSmartphoneToolsSystemPrompt();
        const extraCtx = phoneContext ? `\n\nДополнительный контекст устройства:\n${phoneContext}` : '';
        promptForCli =
            `${effectivePromptText}\n\n` +
            `<AGENT_SYSTEM_INSTRUCTION>\n${sysPromptText}\n\n${phoneSys}\n</AGENT_SYSTEM_INSTRUCTION>` +
            `${openRouterAdaptationBlock}${extraCtx}${attachmentSection}`;
    } else if (!isWorkspaceTask) {
        let historyBlock = '';
        if (!wasContextReset && Array.isArray(clientHistory) && clientHistory.length > 0) {
            const ctxLines = clientHistory.slice(-6).map(m =>
                `${(m.role === 'USER' || m.role === 'user') ? 'Пользователь' : 'Агент'}: ${String(m.content || '').slice(0, 600)}`
            ).join('\n');
            historyBlock = `\n\nКонтекст предыдущих сообщений диалога:\n${ctxLines}`;
        }
        promptForCli =
            `СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ:\n${effectivePromptText}\n\n` +
            `<AGENT_SYSTEM_INSTRUCTION>\n` +
            `Ответь пользователю на русском языке быстро, точно и по существу вопроса. ` +
            `Дай чёткий ответ без искусственного процесса размышления. ` +
            `Если прикреплены файлы или скриншоты с путями на ПК, можешь посмотреть только указанные прикреплённые файлы, но НЕ сканируй другие папки и репозитории без явной просьбы.\n` +
            `</AGENT_SYSTEM_INSTRUCTION>` +
            `${openRouterAdaptationBlock}${attachmentSection}${historyBlock}`;
    } else {
        let historyBlock = '';
        if (!wasContextReset && Array.isArray(clientHistory) && clientHistory.length > 0) {
            const ctxLines = clientHistory.map(m =>
                `${(m.role === 'USER' || m.role === 'user') ? 'Пользователь' : 'Агент'}: ${String(m.content || '').slice(0, 700)}`
            ).join('\n');
            historyBlock = `\n\nКонтекст предыдущих сообщений диалога (для непрерывности контекста):\n${ctxLines}`;
        }
        promptForCli =
            `ЗАДАЧА ПОЛЬЗОВАТЕЛЯ:\n${effectivePromptText}\n\n` +
            `РАБОЧАЯ ДИРЕКТОРИЯ ПРОЕКТА: ${targetCwd}\n` +
            `<AGENT_SYSTEM_INSTRUCTION>\n${sysPromptText}\n</AGENT_SYSTEM_INSTRUCTION>` +
            `${openRouterAdaptationBlock}${attachmentSection}${historyBlock}`;
    }

    if (!isPhoneMode && phoneContext) {
        promptForCli += `\n\n<ANDROID_CLIENT_CONTEXT>\n${phoneContext}\n</ANDROID_CLIENT_CONTEXT>`;
    }

    // Этап 4: инструкции выбранного навыка (только его) либо явный отказ в
    // доступе. Клиентский протокол phone_action при этом сохраняется.
    if (skillSelection.refusal) {
        promptForCli += `\n\n<SKILL_ACCESS_REFUSAL>\n${skillSelection.refusal}\n</SKILL_ACCESS_REFUSAL>`;
    } else if (skillSelection.block) {
        promptForCli += `\n\n${skillSelection.block}`;
    }

    if (wasContextReset && conversationId) {
        resetConversationsSet.delete(conversationId);
    }

    // Выбираем или создаём воркер
    let worker = null;
    const phoneKey = conversationId || 'phone-default';

    if (isPhoneMode && !voiceMode) {
        if (isPhoneContinuation && phoneLoopWorkers.has(phoneKey)) {
            const candidate = phoneLoopWorkers.get(phoneKey);
            if (candidate && !candidate.closed && !candidate.busy && candidate.model === effectiveCliModel) {
                worker = candidate;
            }
        }
        if (!worker) {
            const spareKey = `${effectiveCliModel}::${CHAT_FAST_WORKSPACE}`;
            const candidate = spareWorkers.get(spareKey);
            if (candidate && !candidate.closed && !candidate.busy) {
                worker = candidate;
                spareWorkers.delete(spareKey);
                setTimeout(() => ensureSpareWorker(effectiveCliModel, CHAT_FAST_WORKSPACE), 250);
            } else {
                worker = createStreamJsonWorker({
                    conversationId: '',
                    model: effectiveCliModel,
                    cwd: CHAT_FAST_WORKSPACE,
                    isEphemeral: true
                });
            }
            phoneLoopWorkers.set(phoneKey, worker);
        }
    } else if (hasExistingSessionDb && isWorkspaceTask) {
        const sessKey = `${conversationId}::${effectiveCliModel}::${targetCwd}`;
        const existing = sessionWorkers.get(sessKey);
        if (existing && !existing.closed && !existing.busy) {
            worker = existing;
        } else {
            worker = createStreamJsonWorker({
                conversationId,
                model: effectiveCliModel,
                cwd: targetCwd,
                isEphemeral: false
            });
            sessionWorkers.set(sessKey, worker);
        }
    } else {
        const spareKey = `${effectiveCliModel}::${targetCwd}`;
        const candidate = spareWorkers.get(spareKey);
        if (candidate && !candidate.closed && !candidate.busy) {
            worker = candidate;
            spareWorkers.delete(spareKey);
            setTimeout(() => ensureSpareWorker(effectiveCliModel, targetCwd), 250);
        } else {
            worker = createStreamJsonWorker({
                conversationId: '',
                model: effectiveCliModel,
                cwd: targetCwd,
                isEphemeral: true
            });
        }
    }

    worker.busy = true;
    worker.lastUsedAt = Date.now();

    const listeners = new Set([onEvent]);
    const turnState = {
        turnId,
        startMs,
        initEvent: null,
        lastThinking: '',
        lastCleanReply: '',
        tools: new Map(),
        diffs: new Map()
    };
    const emitToAll = (evt) => {
        for (const fn of listeners) {
            try { fn(evt); } catch (_) {}
        }
    };

    const clientConversationId = conversationId || require('crypto').randomUUID();
    const initialConversationId = (hasExistingSessionDb && isWorkspaceTask)
        ? conversationId
        : clientConversationId;
    turnState.initEvent = {
        type: 'init',
        turnId,
        conversationId: initialConversationId,
        model: effectiveCliModel,
        instanceName: INSTANCE_NAME,
        elapsedMs: Date.now() - startMs
    };
    recordActiveTurnSnapshot(turnId, initialConversationId, {
        status: 'running',
        model: effectiveCliModel,
        prompt: effectivePromptText
    });
    emitToAll(turnState.initEvent);

    const turnPromise = (async () => {
        await waitForWorkerReady(worker, 15000);

        let baselineTranscriptLines = 0;
        const activeConvIdForLogs = (hasExistingSessionDb && isWorkspaceTask)
            ? conversationId
            : (worker.cliConversationId || clientConversationId);
        if (activeConvIdForLogs) {
            const existingLog = resolveBestTranscriptFile(activeConvIdForLogs);
            if (existingLog && fs.existsSync(existingLog)) {
                try {
                    baselineTranscriptLines = fs.readFileSync(existingLog, 'utf8').split(/\r?\n/).filter(Boolean).length;
                } catch (_) {}
            }
        }

        return new Promise((resolve) => {
            let settled = false;
            let ttfbMs = 0;
            let rawStreamText = '';
            let lastEmittedCleanReply = '';
            let lastEmittedThinking = '';
            const nativeThinkingParts = [];
            const toolCallsMap = turnState.tools;
            const diffsMap = turnState.diffs;

            const recordFirstChunk = () => {
                if (!ttfbMs) {
                    ttfbMs = Date.now() - startMs;
                }
            };

            const pollTranscriptForNativeThinking = () => {
                const cid = (hasExistingSessionDb && isWorkspaceTask)
                    ? conversationId
                    : (worker.cliConversationId || clientConversationId);
                if (!cid) return;
                const logFile = resolveBestTranscriptFile(cid);
                if (!logFile || !fs.existsSync(logFile)) return;
                try {
                    const lines = fs.readFileSync(logFile, 'utf8').split(/\r?\n/).filter(Boolean);
                    for (let i = baselineTranscriptLines; i < lines.length; i++) {
                        try {
                            const obj = JSON.parse(lines[i]);
                            if (obj.type === 'PLANNER_RESPONSE') {
                                if (obj.thinking) {
                                    const t = cleanRussianThinkingParagraphs(String(obj.thinking));
                                    if (t && !nativeThinkingParts.includes(t)) {
                                        nativeThinkingParts.push(t);
                                        recordFirstChunk();
                                        const parsed = parseStreamingThinkingAndReply(rawStreamText, nativeThinkingParts);
                                        if (parsed.combinedThinking && parsed.combinedThinking !== lastEmittedThinking) {
                                            const delta = parsed.combinedThinking.startsWith(lastEmittedThinking)
                                                ? parsed.combinedThinking.slice(lastEmittedThinking.length)
                                                : parsed.combinedThinking;
                                            lastEmittedThinking = parsed.combinedThinking;
                                            turnState.lastThinking = lastEmittedThinking;
                                            recordActiveTurnSnapshot(turnId, initialConversationId, {
                                                status: 'running',
                                                thinking: lastEmittedThinking
                                            });
                                            emitToAll({
                                                type: 'thinking_delta',
                                                turnId,
                                                delta,
                                                thinking: lastEmittedThinking,
                                                fullThinking: lastEmittedThinking,
                                                elapsedMs: Date.now() - startMs
                                            });
                                        }
                                    }
                                }
                                if (Array.isArray(obj.tool_calls)) {
                                    obj.tool_calls.forEach((tc, tcIdx) => {
                                        const tName = tc.name || 'pc_tool';
                                        let tArgs = {};
                                        try {
                                            tArgs = typeof tc.arguments_json === 'string'
                                                ? JSON.parse(tc.arguments_json)
                                                : (tc.args || tc.arguments || {});
                                        } catch (_) {}
                                        const summary = formatToolSummary(tName, tArgs);
                                        const key = `tr_${obj.step_index || i}_${tcIdx}`;
                                        const diffCard = extractDiffFromToolArgs(tName, tArgs, `diff_${key}`);
                                        if (diffCard) {
                                            diffsMap.set(diffCard.id, diffCard);
                                        }
                                        if (!toolCallsMap.has(key) && !Array.from(toolCallsMap.values()).some(x => x.name === tName && x.summary === summary)) {
                                            const toolEntry = {
                                                id: key,
                                                name: tName,
                                                summary,
                                                status: 'COMPLETED',
                                                output: ''
                                            };
                                            toolCallsMap.set(key, toolEntry);
                                            recordActiveTurnSnapshot(turnId, initialConversationId, {
                                                status: 'running',
                                                toolCalls: Array.from(toolCallsMap.values()),
                                                createdDiffs: Array.from(diffsMap.values())
                                            });
                                            emitToAll({
                                                type: 'tool',
                                                turnId,
                                                id: toolEntry.id,
                                                toolName: toolEntry.name,
                                                summary: toolEntry.summary,
                                                details: toolEntry.output,
                                                status: toolEntry.status,
                                                tool: toolEntry,
                                                createdDiffs: Array.from(diffsMap.values()),
                                                elapsedMs: Date.now() - startMs
                                            });
                                        }
                                    });
                                }
                            } else if (obj.type === 'GENERIC' && typeof obj.content === 'string') {
                                const cleanOut = obj.content
                                    .replace(/^Created At:.*\r?\n/gm, '')
                                    .replace(/^Completed At:.*\r?\n/gm, '')
                                    .trim()
                                    .slice(0, 1200);
                                if (cleanOut) {
                                    const targetEntry = Array.from(toolCallsMap.values()).find(x => !x.output);
                                    if (targetEntry) {
                                        targetEntry.output = cleanOut;
                                        recordActiveTurnSnapshot(turnId, initialConversationId, {
                                            status: 'running',
                                            toolCalls: Array.from(toolCallsMap.values()),
                                            createdDiffs: Array.from(diffsMap.values())
                                        });
                                        emitToAll({
                                            type: 'tool',
                                            turnId,
                                            id: targetEntry.id,
                                            toolName: targetEntry.name,
                                            summary: targetEntry.summary,
                                            details: targetEntry.output,
                                            status: targetEntry.status,
                                            tool: targetEntry,
                                            createdDiffs: Array.from(diffsMap.values()),
                                            elapsedMs: Date.now() - startMs
                                        });
                                    }
                                }
                            }
                        } catch (_) {}
                    }
                } catch (_) {}
            };

            const fastPollTimer = setInterval(() => {
                if (settled) return;
                pollTranscriptForNativeThinking();
            }, 250);

            const heartbeatTimer = setInterval(() => {
                if (settled) return;
                emitToAll({
                    type: 'heartbeat',
                    turnId,
                    elapsedMs: Date.now() - startMs
                });
            }, 2000);

            const finishTurn = (resultObj, isTimeout = false) => {
                if (settled) return;
                settled = true;
                clearInterval(fastPollTimer);
                clearInterval(heartbeatTimer);
                clearTimeout(turnTimeout);
                inFlightTurns.delete(dedupKey);
                turnCancellations.delete(turnId);
                worker.activeTurnHandler = null;
                worker.busy = false;

                pollTranscriptForNativeThinking();

                const elapsedMs = Date.now() - startMs;
                const elapsedSec = Math.max(1, Math.round(elapsedMs / 1000));
                const finalRawResponse = (resultObj && typeof resultObj.response === 'string' && resultObj.response.trim())
                    ? resultObj.response.trim()
                    : rawStreamText.trim();

                const separated = extractThinkingAndCleanReply(
                    finalRawResponse,
                    nativeThinkingParts.join('\n\n')
                );
                const finalThinking = cleanRussianThinkingParagraphs(separated.thinking || lastEmittedThinking || '');
                const toolCallsList = Array.from(toolCallsMap.values());
                const createdDiffsList = Array.from(diffsMap.values());
                let fallbackTimeoutReply = '';
                if (isTimeout) {
                    if (toolCallsList.length > 0) {
                        fallbackTimeoutReply = `Выполнено шагов инструментов: ${toolCallsList.length} (${toolCallsList.map(t => t.summary || t.name).slice(0, 4).join(', ')}). Ход занял более 4 минут и был сохранён.`;
                    } else {
                        fallbackTimeoutReply = 'Превышено время ожидания ответа от модели (240 с).';
                    }
                }
                const finalReply = separated.reply || lastEmittedCleanReply || fallbackTimeoutReply || (resultObj && resultObj.error) || 'Модель завершила запрос без ответа. Повторите попытку.';
                const resolvedConvId = (hasExistingSessionDb && isWorkspaceTask)
                    ? conversationId
                    : clientConversationId;
                const phoneActions = extractPhoneActionsFromText(finalReply);

                if (allowTranscriptWrite && !isPhoneMode && resolvedConvId) {
                    if (hasExistingSessionDb && isWorkspaceTask) {
                        normalizeTranscriptThinkingTags(resolvedConvId, finalThinking, elapsedSec);
                        syncConversationAfterCli(resolvedConvId, effectivePromptText);
                        normalizeTranscriptThinkingTags(resolvedConvId, finalThinking, elapsedSec);
                    } else {
                        if (worker.cliConversationId && worker.cliConversationId !== resolvedConvId) {
                            deleteTemporaryCliConversation(worker.cliConversationId, resolvedConvId);
                        }
                        ensureTranscriptUpdated(resolvedConvId, effectivePromptText, finalReply, elapsedSec, finalThinking);
                        syncConversationAfterCli(resolvedConvId, effectivePromptText);
                    }
                } else if (isPhoneMode && phoneActions.length === 0 && worker.cliConversationId) {
                    deleteTemporaryCliConversation(worker.cliConversationId, '');
                }

                if (worker.isEphemeral) {
                    const keepForPhoneNextStep = Boolean(isPhoneMode && !voiceMode && phoneActions.length > 0);
                    if (!keepForPhoneNextStep) {
                        if (phoneLoopWorkers.get(phoneKey) === worker) {
                            phoneLoopWorkers.delete(phoneKey);
                        }
                        killWorkerProcess(worker);
                        setTimeout(() => ensureSpareWorker(effectiveCliModel, targetCwd), 300);
                    }
                }

                appendBridgeRequestLog({
                    turnId,
                    mode,
                    voiceMode,
                    requestedModel,
                    effectiveCliModel,
                    conversationId: resolvedConvId,
                    cwd: targetCwd,
                    agyArgs: worker.args,
                    systemPromptInjected: true,
                    systemPromptChars: sysPromptText.length,
                    ttfbMs: ttfbMs || elapsedMs,
                    totalMs: elapsedMs,
                    toolCallsCount: toolCallsList.length,
                    diffsCount: createdDiffsList.length,
                    phoneActionsCount: phoneActions.length,
                    thinkingChars: finalThinking.length,
                    replyChars: finalReply.length
                });

                const reportedModelId = options.requestedOpenRouterModelId || effectiveCliModel;
                const estOutTokens = Math.max(16, Math.round((finalReply || '').length / 3.5));
                const estTps = Number((estOutTokens / Math.max(0.25, elapsedMs / 1000)).toFixed(1));
                const donePayload = {
                    type: 'done',
                    ok: !isTimeout && !(resultObj && resultObj.error),
                    turnId,
                    instanceName: INSTANCE_NAME,
                    conversationId: resolvedConvId,
                    model: reportedModelId,
                    thinking: finalThinking,
                    thinkingDurationSec: finalThinking ? elapsedSec : 0,
                    reply: finalReply,
                    toolCalls: toolCallsList,
                    createdDiffs: createdDiffsList,
                    generatedMedia: [],
                    phoneActions: phoneActions,
                    ttfbMs: ttfbMs || elapsedMs,
                    totalMs: elapsedMs,
                    outputTokensCount: estOutTokens,
                    tokensPerSecond: estTps
                };

                recordActiveTurnSnapshot(turnId, resolvedConvId, {
                    status: isTimeout || (resultObj && resultObj.error) ? 'failed' : 'completed',
                    conversationId: resolvedConvId,
                    model: reportedModelId,
                    thinking: finalThinking,
                    thinkingDurationSec: finalThinking ? elapsedSec : 0,
                    reply: finalReply,
                    toolCalls: toolCallsList,
                    createdDiffs: createdDiffsList,
                    generatedMedia: [],
                    phoneActions: phoneActions,
                    totalMs: elapsedMs,
                    outputTokensCount: estOutTokens,
                    tokensPerSecond: estTps
                });

                emitToAll(donePayload);
                resolve(donePayload);
            };

            const turnTimeout = setTimeout(() => {
                try { worker.child.kill(); } catch (_) {}
                finishTurn(null, true);
            }, 240000);

            turnCancellations.set(turnId, () => {
                killWorkerProcess(worker);
                finishTurn({ response: lastEmittedCleanReply, error: 'Generation cancelled' }, false);
            });
            worker.activeTurnHandler = (obj) => {
                if (!obj || settled) return;
                if (obj.event === 'process_closed' || obj.event === 'process_error') {
                    return finishTurn(null, false);
                }
                const u = obj.step_update || obj;
                if (obj.event === 'step_update' && u) {
                    if (u.step_type === 'tool') {
                        recordFirstChunk();
                        pollTranscriptForNativeThinking();
                        const stepIdx = u.step_index || 0;
                        const toolName = u.tool_name || 'pc_tool';
                        const status = (u.state === 'DONE' || u.status === 'DONE') ? 'COMPLETED' : 'RUNNING';
                        const prev = toolCallsMap.get(stepIdx) || {};
                        const summary = u.tool_info ? formatToolSummary(toolName, u.tool_info) : (prev.summary || toolName);
                        const output = u.result ? String(u.result).slice(0, 1200) : (prev.output || '');
                        if (u.tool_info) {
                            const dCard = extractDiffFromToolArgs(toolName, u.tool_info, `diff_step_${stepIdx}`);
                            if (dCard) diffsMap.set(dCard.id, dCard);
                        }
                        const toolEntry = {
                            id: `tool_${stepIdx}`,
                            name: toolName,
                            summary,
                            status,
                            output
                        };
                        toolCallsMap.set(stepIdx, toolEntry);
                        recordActiveTurnSnapshot(turnId, initialConversationId, {
                            status: 'running',
                            toolCalls: Array.from(toolCallsMap.values()),
                            createdDiffs: Array.from(diffsMap.values())
                        });
                        emitToAll({
                            type: 'tool',
                            turnId,
                            id: toolEntry.id,
                            toolName: toolEntry.name,
                            summary: toolEntry.summary,
                            details: toolEntry.output,
                            status: toolEntry.status,
                            tool: toolEntry,
                            createdDiffs: Array.from(diffsMap.values()),
                            elapsedMs: Date.now() - startMs
                        });
                    } else if (typeof u.text_delta === 'string' && u.text_delta.length > 0) {
                        recordFirstChunk();
                        rawStreamText += u.text_delta;

                        const parsed = parseStreamingThinkingAndReply(rawStreamText, nativeThinkingParts);
                        if (parsed.combinedThinking && parsed.combinedThinking !== lastEmittedThinking) {
                            const delta = parsed.combinedThinking.startsWith(lastEmittedThinking)
                                ? parsed.combinedThinking.slice(lastEmittedThinking.length)
                                : parsed.combinedThinking;
                            lastEmittedThinking = parsed.combinedThinking;
                            turnState.lastThinking = lastEmittedThinking;
                            recordActiveTurnSnapshot(turnId, initialConversationId, {
                                status: 'running',
                                thinking: lastEmittedThinking
                            });
                            emitToAll({
                                type: 'thinking_delta',
                                turnId,
                                delta,
                                thinking: lastEmittedThinking,
                                fullThinking: lastEmittedThinking,
                                elapsedMs: Date.now() - startMs
                            });
                        }

                        if (parsed.cleanVisibleReply && parsed.cleanVisibleReply !== lastEmittedCleanReply) {
                            const textDelta = parsed.cleanVisibleReply.startsWith(lastEmittedCleanReply)
                                ? parsed.cleanVisibleReply.slice(lastEmittedCleanReply.length)
                                : '';
                            lastEmittedCleanReply = parsed.cleanVisibleReply;
                            turnState.lastCleanReply = lastEmittedCleanReply;
                            recordActiveTurnSnapshot(turnId, initialConversationId, {
                                status: 'running',
                                reply: lastEmittedCleanReply
                            });
                            emitToAll({
                                type: 'text_delta',
                                turnId,
                                delta: textDelta,
                                text: lastEmittedCleanReply,
                                fullText: lastEmittedCleanReply,
                                elapsedMs: Date.now() - startMs
                            });
                        }
                    }
                } else if (obj.event === 'result') {
                    const resData = obj.result || obj;
                    finishTurn(resData, false);
                }
            };

            try {
                const msgLine = JSON.stringify({
                    event: 'user',
                    message: {
                        content: promptForCli
                    }
                }) + '\n';
                worker.child.stdin.write(msgLine, 'utf8');
            } catch (err) {
                finishTurn({ response: '', error: err.message }, false);
            }
        });
    })();

    inFlightTurns.set(dedupKey, {
        promise: turnPromise,
        listeners,
        state: turnState
    });

    return turnPromise;
}

// ============================================================================
// Управление плагинами (`agy plugin`), скиллами (`config/skills`) и MCP (`agy mcp`)
// ============================================================================
function runAgySubcommand(args, timeoutMs = 12000) {
    return new Promise((resolve) => {
        try {
            const child = spawn(AGY_BIN, args, {
                cwd: DEFAULT_WORKSPACE,
                windowsHide: true,
                env: process.env
            });
            let stdout = '';
            let stderr = '';
            const timer = setTimeout(() => {
                try { child.kill(); } catch (_) {}
                resolve({ ok: false, stdout: stdout.trim(), stderr: 'Timeout executing agy ' + args.join(' ') });
            }, timeoutMs);
            child.stdout.on('data', d => { stdout += d.toString('utf8'); });
            child.stderr.on('data', d => { stderr += d.toString('utf8'); });
            child.on('close', (code) => {
                clearTimeout(timer);
                resolve({
                    ok: code === 0,
                    code,
                    stdout: stdout.trim(),
                    stderr: stderr.trim()
                });
            });
            child.on('error', (err) => {
                clearTimeout(timer);
                resolve({ ok: false, stdout: '', stderr: err.message });
            });
        } catch (err) {
            resolve({ ok: false, stdout: '', stderr: err.message });
        }
    });
}

async function listPlugins() {
    const res = await runAgySubcommand(['plugin', 'list', '--json']);
    const list = [];
    const seen = new Set();
    if (res.stdout) {
        try {
            const parsed = JSON.parse(res.stdout);
            if (Array.isArray(parsed)) {
                for (const p of parsed) {
                    let description = p.description || '';
                    if (!description && p.path) {
                        const pj = path.join(p.path, 'plugin.json');
                        if (fs.existsSync(pj)) {
                            try {
                                const meta = JSON.parse(fs.readFileSync(pj, 'utf8'));
                                description = meta.description || '';
                            } catch (_) {}
                        }
                    }
                    seen.add(p.name);
                    list.push({
                        id: p.name,
                        name: p.name,
                        version: p.version || '1.0.0',
                        enabled: Boolean(p.enabled),
                        source: p.source || 'agy-cli',
                        path: p.path || '',
                        description: description || `Плагин Antigravity CLI (${p.source || 'local'})`
                    });
                }
            }
        } catch (_) {}
    }

    // Read real installed Antigravity IDE / VS Code extensions from ~/.antigravity/extensions/extensions.json
    try {
        const extJsonPath = path.join(os.homedir(), '.antigravity', 'extensions', 'extensions.json');
        if (fs.existsSync(extJsonPath)) {
            const extArr = JSON.parse(fs.readFileSync(extJsonPath, 'utf8'));
            if (Array.isArray(extArr)) {
                for (const item of extArr) {
                    const id = (item.identifier && item.identifier.id) || '';
                    if (!id || seen.has(id)) continue;
                    seen.add(id);
                    const locPath = (item.location && item.location.fsPath) || '';
                    let description = `Расширение IDE Antigravity (${id})`;
                    if (locPath) {
                        const pkgPath = path.join(locPath, 'package.json');
                        if (fs.existsSync(pkgPath)) {
                            try {
                                const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
                                if (pkg.displayName && !pkg.displayName.startsWith('%')) {
                                    description = pkg.displayName + (pkg.description && !pkg.description.startsWith('%') ? ` — ${pkg.description}` : '');
                                }
                            } catch (_) {}
                        }
                    }
                    list.push({
                        id,
                        name: id,
                        version: item.version || '1.0.0',
                        enabled: true,
                        source: 'antigravity-ide',
                        path: locPath,
                        description
                    });
                }
            }
        }
    } catch (_) {}

    return list;
}

// Этап 0 (EXTENSIONS_INTEGRATION_PLAN.md): автозасев фиктивных плагинов
// удалён — пустой каталог остаётся пустым. Наличие manifest не подтверждает
// обещанную функциональность, поэтому «установка готового» и «создание нового
// пакета» разделены: install принимает только реальный путь или URL.
async function managePlugin(payload) {
    let action = String(payload.action || '').toLowerCase();
    if (action === 'toggle') {
        action = payload.enabled === false ? 'disable' : 'enable';
    }
    const rawName = String(payload.name || payload.id || payload.target || '').trim();
    if (!rawName) {
        return { ok: false, error: 'Не указано имя или путь плагина' };
    }

    if (action === 'install' || action === 'add') {
        const isUrlOrPath = rawName.startsWith('http://') || rawName.startsWith('https://') || fs.existsSync(rawName);
        if (!isUrlOrPath) {
            return {
                ok: false,
                error: `Установка возможна только из реального пути или URL. «${rawName}» не найден: укажите путь к пакету плагина или используйте действие create для создания нового пакета.`,
                plugins: await listPlugins()
            };
        }
        const r = await runAgySubcommand(['plugin', 'install', rawName]);
        invalidateAllIdleWorkers();
        const plugins = await listPlugins();
        return {
            ok: r.ok,
            message: r.stdout || r.stderr,
            error: r.ok ? '' : (r.stderr || r.stdout || 'Ошибка установки плагина'),
            plugins
        };
    }

    if (action === 'create') {
        // Явный редактор нового локального пакета: пользователь задаёт имя и
        // описание сам. Manifest фиксирует только метаданные и не заявляет
        // рабочих инструментов — карточка показывает это как локальный пакет.
        const slug = rawName.toLowerCase().replace(/[^a-z0-9_-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || `plugin-${Date.now()}`;
        const stageDir = path.join(UPLOADS_DIR, 'plugin-staging', slug);
        fs.mkdirSync(stageDir, { recursive: true });
        const manifest = {
            name: slug,
            version: String(payload.version || '1.0.0'),
            description: String(payload.description || `Локальный пакет ${slug}`),
            created: new Date().toISOString(),
            verifiedTools: []
        };
        fs.writeFileSync(path.join(stageDir, 'plugin.json'), JSON.stringify(manifest, null, 2), 'utf8');
        const r = await runAgySubcommand(['plugin', 'install', stageDir]);
        invalidateAllIdleWorkers();
        const plugins = await listPlugins();
        return {
            ok: r.ok,
            message: r.ok
                ? `Локальный пакет "${slug}" создан. Инструменты пакета не проверены — карточка показывает «требует проверки».`
                : (r.stderr || r.stdout || 'Ошибка создания пакета'),
            error: r.ok ? '' : (r.stderr || r.stdout || 'Ошибка создания пакета'),
            plugins
        };
    }

    if (action === 'enable') {
        const r = await runAgySubcommand(['plugin', 'enable', rawName]);
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `Плагин ${rawName} включён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            plugins: await listPlugins()
        };
    }

    if (action === 'disable') {
        const r = await runAgySubcommand(['plugin', 'disable', rawName]);
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `Плагин ${rawName} отключён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            plugins: await listPlugins()
        };
    }

    if (action === 'uninstall' || action === 'remove' || action === 'delete') {
        const r = await runAgySubcommand(['plugin', 'uninstall', rawName]);
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `Плагин ${rawName} удалён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            plugins: await listPlugins()
        };
    }

    return { ok: false, error: `Неизвестное действие для плагина: ${action}`, plugins: await listPlugins() };
}

function parseSkillMarkdown(rawMd, fallbackName) {
    let name = fallbackName;
    let description = '';
    let instruction = rawMd;
    const fmMatch = rawMd.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
    if (fmMatch) {
        const yamlPart = fmMatch[1];
        instruction = (fmMatch[2] || '').trim();
        const nameMatch = yamlPart.match(/^name:\s*(.+)$/m);
        if (nameMatch) name = nameMatch[1].trim().replace(/^["']|["']$/g, '');
        const descBlockMatch = yamlPart.match(/^description:\s*\|?\s*\r?\n((?:[ \t]+.+\r?\n?)+)/m);
        if (descBlockMatch) {
            description = descBlockMatch[1].replace(/^[ \t]+/gm, '').replace(/\s+/g, ' ').trim();
        } else {
            const descLineMatch = yamlPart.match(/^description:\s*(.+)$/m);
            if (descLineMatch) description = descLineMatch[1].trim().replace(/^["']|["']$/g, '');
        }
    }
    return { name, description, instruction };
}

function listSkills() {
    const skills = [];
    const seenIds = new Set();

    // Этап 0: просмотр списка ничего не включает. Раньше здесь незаметно
    // восстанавливался отключённый autonomous-software-engineer — отключение
    // пользователя переживало только пока список не открывали заново.
    const skillRoots = [
        { dir: path.join(DEFAULT_WORKSPACE, '.agents', 'skills'), source: 'workspace' },
        { dir: SKILLS_DIR, source: 'global' },
        { dir: path.join(IDE_BASE_DIR, 'builtin', 'skills'), source: 'builtin' }
    ];

    for (const root of skillRoots) {
        try {
            if (!fs.existsSync(root.dir)) continue;
            for (const entry of fs.readdirSync(root.dir, { withFileTypes: true })) {
                if (!entry.isDirectory()) continue;
                const folderPath = path.join(root.dir, entry.name);
                const activeFile = path.join(folderPath, 'SKILL.md');
                const disabledFile = path.join(folderPath, 'SKILL.md.disabled');
                let filePath = '';
                let enabled = true;
                if (fs.existsSync(activeFile)) {
                    filePath = activeFile;
                    enabled = true;
                } else if (fs.existsSync(disabledFile)) {
                    filePath = disabledFile;
                    enabled = false;
                } else {
                    continue;
                }
                const raw = fs.readFileSync(filePath, 'utf8');
                const parsed = parseSkillMarkdown(raw, entry.name);
                const skillId = parsed.name || entry.name;
                if (seenIds.has(skillId)) continue;
                seenIds.add(skillId);
                skills.push({
                    id: entry.name,
                    name: skillId,
                    description: parsed.description || `Скилл Antigravity (${root.source})`,
                    instruction: parsed.instruction.slice(0, 4000),
                    instructionsPreview: parsed.instruction.slice(0, 240),
                    enabled,
                    source: root.source,
                    path: activeFile
                });
            }
        } catch (_) {}
    }
    return skills;
}

function manageSkill(payload) {
    let action = String(payload.action || '').toLowerCase();
    if (action === 'toggle') {
        action = payload.enabled === false ? 'disable' : 'enable';
    }
    const rawName = String(payload.name || payload.id || '').trim();
    if (!rawName) {
        return { ok: false, error: 'Не указано имя скилла', skills: listSkills() };
    }
    const slug = rawName.toLowerCase().replace(/[^a-z0-9_-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || `skill-${Date.now()}`;
    const folderPath = path.join(SKILLS_DIR, fs.existsSync(path.join(SKILLS_DIR, rawName)) ? rawName : slug);
    const activeFile = path.join(folderPath, 'SKILL.md');
    const disabledFile = path.join(folderPath, 'SKILL.md.disabled');

    if (action === 'add' || action === 'install' || action === 'create') {
        const description = String(payload.description || `Специализированный навык ${rawName}`).trim();
        const instruction = String(payload.instruction || payload.instructions || payload.content || `# ${rawName}\n\nИнструкция выполнения навыка ${rawName}.`).trim();
        fs.mkdirSync(folderPath, { recursive: true });
        const mdContent =
            `---\n` +
            `name: ${slug}\n` +
            `description: ${JSON.stringify(description)}\n` +
            `---\n\n` +
            `${instruction}\n`;
        fs.writeFileSync(activeFile, mdContent, 'utf8');
        if (fs.existsSync(disabledFile)) {
            try { fs.unlinkSync(disabledFile); } catch (_) {}
        }
        invalidateAllIdleWorkers();
        return {
            ok: true,
            message: `Скилл "${slug}" создан в ${activeFile}`,
            skills: listSkills()
        };
    }

    if (action === 'enable') {
        if (fs.existsSync(disabledFile) && !fs.existsSync(activeFile)) {
            fs.renameSync(disabledFile, activeFile);
        }
        invalidateAllIdleWorkers();
        return { ok: true, message: `Скилл "${rawName}" включён`, skills: listSkills() };
    }

    if (action === 'disable') {
        if (fs.existsSync(activeFile)) {
            fs.renameSync(activeFile, disabledFile);
        }
        invalidateAllIdleWorkers();
        return { ok: true, message: `Скилл "${rawName}" отключён`, skills: listSkills() };
    }

    if (action === 'remove' || action === 'uninstall' || action === 'delete') {
        if (fs.existsSync(folderPath)) {
            fs.rmSync(folderPath, { recursive: true, force: true });
        }
        invalidateAllIdleWorkers();
        return { ok: true, message: `Скилл "${rawName}" удалён`, skills: listSkills() };
    }

    return { ok: false, error: `Неизвестное действие для скилла: ${action}`, skills: listSkills() };
}

function syncMcpConfigFileFromSettings() {
    try {
        if (!fs.existsSync(MCP_CONFIG_FILE)) return;
        const mcpConfig = JSON.parse(fs.readFileSync(MCP_CONFIG_FILE, 'utf8'));
        const allMcp = mcpConfig.mcpServers || {};
        let settings = {};
        if (fs.existsSync(GEMINI_SETTINGS_FILE)) {
            try {
                settings = JSON.parse(fs.readFileSync(GEMINI_SETTINGS_FILE, 'utf8')) || {};
            } catch (_) {
                settings = {};
            }
        }
        settings.mcpServers = allMcp;
        fs.writeFileSync(GEMINI_SETTINGS_FILE, JSON.stringify(settings, null, 2), 'utf8');
    } catch (_) {}
}

function splitCommandAndArgs(commandOrUrl) {
    const tokens = [];
    const tokenRegex = /"([^"]+)"|'([^']+)'|(\S+)/g;
    let m;
    while ((m = tokenRegex.exec(commandOrUrl || '')) !== null) {
        tokens.push(m[1] || m[2] || m[3]);
    }
    return {
        command: tokens[0] || commandOrUrl || '',
        args: tokens.slice(1)
    };
}

// Этап 3: сырьё записи MCP-сервера (env/headers для авторизации). Значения
// остаются в памяти процесса и не попадают в логи и ответы API.
function findRawMcpEntry(name) {
    for (const file of [MCP_CONFIG_FILE, GEMINI_SETTINGS_FILE]) {
        try {
            const cfg = JSON.parse(fs.readFileSync(file, 'utf8'));
            const map = (cfg && cfg.mcpServers) || {};
            if (map[name] && typeof map[name] === 'object') {
                return { file, cfg: map[name] };
            }
        } catch (_) {}
    }
    return null;
}

// Слияние живого статуса MCP-проверки в карточку сервера. connected — только
// после успешного полного рукопожатия; при отказе — failed с причиной, tools
// остаются пустыми (ничего не угадывается).
function mergeMcpLiveState(server) {
    const live = (typeof mcpLiveState !== 'undefined' && mcpLiveState && typeof mcpLiveState.get === 'function')
        ? mcpLiveState.get(server.id)
        : null;
    if (!live) return server;
    server.status = live.status === 'connected' ? 'connected' : 'failed';
    server.tools = Array.isArray(live.tools) ? live.tools.slice() : [];
    server.serverInfo = live.serverInfo || null;
    server.protocolVersion = live.protocolVersion || '';
    server.capabilities = live.capabilities || null;
    server.error = live.error || '';
    server.errorCode = live.errorCode || '';
    server.probedAt = live.probedAt || '';
    return server;
}

async function listMcpServers() {
    const res = await runAgySubcommand(['mcp', 'list']);
    const servers = [];
    const seen = new Set();

    if (res.stdout) {
        const lines = res.stdout.split(/\r?\n/).filter(Boolean);
        for (const line of lines) {
            if (line.startsWith('NAME') || line.startsWith('No MCP')) continue;
            const match = line.trim().match(/^(\S+)\s+(stdio|http|sse)\s+(enabled|disabled)\s+(.+)$/i);
            if (match) {
                const name = match[1].trim();
                const transport = match[2].toLowerCase();
                const enabled = match[3].toLowerCase() === 'enabled';
                const commandOrUrl = match[4].trim();
                const parsedCmd = splitCommandAndArgs(commandOrUrl);
                seen.add(name);
                const server = {
                    id: name,
                    name,
                    transport,
                    enabled,
                    command: parsedCmd.command,
                    args: parsedCmd.args,
                    commandOrUrl,
                    // Этап 3: имена инструментов не выдумываются ни по имени
                    // сервера, ни по декларации — только живой tools/list.
                    tools: [],
                    // «Включён в конфиге» ≠ «подключён». connected появляется
                    // только после реального MCP-рукопожатия (см. mergeMcpLiveState).
                    status: enabled ? 'configured' : 'disabled'
                };
                if (typeof mergeMcpLiveState === 'function') mergeMcpLiveState(server);
                servers.push(server);
            }
        }
    }

    try {
        if (fs.existsSync(GEMINI_SETTINGS_FILE)) {
            const settings = JSON.parse(fs.readFileSync(GEMINI_SETTINGS_FILE, 'utf8'));
            const mcpObj = settings.mcpServers || {};
            for (const [name, cfg] of Object.entries(mcpObj)) {
                if (seen.has(name) || !cfg || typeof cfg !== 'object') continue;
                const cmd = cfg.url || [cfg.command, ...(Array.isArray(cfg.args) ? cfg.args : [])].filter(Boolean).join(' ');
                const enabled = !Boolean(cfg.disabled);
                const parsedCmd = splitCommandAndArgs(cmd);
                const server = {
                    id: name,
                    name,
                    transport: cfg.url ? 'http' : 'stdio',
                    enabled,
                    command: cfg.command || parsedCmd.command,
                    args: Array.isArray(cfg.args) ? cfg.args : parsedCmd.args,
                    commandOrUrl: cmd,
                    tools: [],
                    status: enabled ? 'configured' : 'disabled'
                };
                if (typeof mergeMcpLiveState === 'function') mergeMcpLiveState(server);
                servers.push(server);
            }
        }
    } catch (_) {}

    return servers;
}

async function manageMcpServer(payload) {
    let action = String(payload.action || '').toLowerCase();
    if (action === 'toggle') {
        action = payload.enabled === false ? 'disable' : 'enable';
    }
    const name = String(payload.name || payload.id || '').trim();
    if (!name) {
        return { ok: false, error: 'Не указано имя MCP-сервера', mcpServers: await listMcpServers() };
    }

    // --- Этап 3: живое MCP-рукопожатие и вызовы инструментов -----------------
    // probe/check: полный цикл initialize → initialized → tools/list; статус
    // connected выставляется только при его успехе. call: реальный tools/call.
    // Авторизация (env/headers/токен) пробрасывается из конфига в транспорт;
    // секреты не попадают в логи и ответы (redactSecrets в mcp-client).
    if (action === 'probe' || action === 'check' || action === 'test') {
        const raw = findRawMcpEntry(name);
        if (!raw) {
            return { ok: false, error: `MCP-сервер не найден в конфигурации: ${name}`, mcpServers: await listMcpServers() };
        }
        const descriptor = mcpClientLib.buildMcpDescriptor(name, raw.cfg);
        const timeoutMs = Number(payload.timeoutMs) > 0 ? Number(payload.timeoutMs) : undefined;
        const probe = await mcpClientManager.probe(descriptor, timeoutMs
            ? { handshakeTimeoutMs: Math.min(timeoutMs, 60000), requestTimeoutMs: Math.min(timeoutMs, 120000) }
            : {});
        mcpLiveState.set(name, probe);
        appendBridgeRequestLog({ type: 'mcp-probe', name, ok: probe.ok, status: probe.status, errorCode: probe.errorCode || null });
        return {
            ok: probe.ok,
            error: probe.ok ? '' : probe.error,
            probe,
            mcpServers: await listMcpServers()
        };
    }

    if (action === 'call' || action === 'call_tool' || action === 'tool') {
        const toolName = String(payload.tool || payload.toolName || '').trim();
        if (!toolName) {
            return { ok: false, error: 'Не указано имя инструмента', mcpServers: await listMcpServers() };
        }
        const raw = findRawMcpEntry(name);
        if (!raw) {
            return { ok: false, error: `MCP-сервер не найден в конфигурации: ${name}`, mcpServers: await listMcpServers() };
        }
        const descriptor = mcpClientLib.buildMcpDescriptor(name, raw.cfg);
        const args = (payload.arguments && typeof payload.arguments === 'object' && !Array.isArray(payload.arguments))
            ? payload.arguments
            : ((payload.args && typeof payload.args === 'object' && !Array.isArray(payload.args)) ? payload.args : {});
        const callTimeoutMs = Number(payload.timeoutMs) > 0 ? Number(payload.timeoutMs) : undefined;
        const call = await mcpClientManager.callTool(name, toolName, args, {
            descriptor,
            requestTimeoutMs: callTimeoutMs ? Math.min(callTimeoutMs, 300000) : undefined
        });
        // Синхронизация живого статуса: смерть процесса снимает connected.
        if (call.ok) {
            const live = mcpClientManager.getLive(name);
            if (live) mcpLiveState.set(name, live);
        } else if (['process_exit', 'connect_failed', 'closed', 'not_connected'].includes(call.errorCode)) {
            mcpLiveState.set(name, {
                id: name,
                status: 'failed',
                tools: [],
                serverInfo: null,
                protocolVersion: '',
                capabilities: null,
                error: call.error || 'Соединение с MCP-сервером потеряно',
                errorCode: call.errorCode,
                probedAt: new Date().toISOString()
            });
            await mcpClientManager.disconnect(name).catch(() => {});
        }
        appendBridgeRequestLog({ type: 'mcp-call', name, tool: toolName, ok: call.ok, errorCode: call.errorCode || null });
        return {
            ok: call.ok,
            tool: toolName,
            result: call.ok ? call.result : null,
            error: call.ok ? '' : call.error,
            errorCode: call.errorCode || null,
            mcpServers: await listMcpServers()
        };
    }

    if (action === 'disconnect' || action === 'stop') {
        await mcpClientManager.disconnect(name);
        mcpLiveState.delete(name);
        appendBridgeRequestLog({ type: 'mcp-disconnect', name });
        return { ok: true, mcpServers: await listMcpServers() };
    }
    // ------------------------------------------------------------------------

    if (action === 'add' || action === 'install' || action === 'create') {
        let rawCmd = String(payload.commandOrUrl || payload.command || payload.url || '').trim();
        const extraPayloadArgs = Array.isArray(payload.args)
            ? payload.args.map(a => String(a || '').trim()).filter(Boolean)
            : [];
        if (!rawCmd) {
            return { ok: false, error: 'Не указана команда или URL для MCP-сервера', mcpServers: await listMcpServers() };
        }
        if (rawCmd === 'builtin-sysinfo' || rawCmd === 'antigravity-sysinfo') {
            rawCmd = NODE_BIN;
            extraPayloadArgs.unshift(path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'mcp_sysinfo_server.js'));
        } else if (rawCmd === 'node') {
            rawCmd = NODE_BIN;
        } else if (rawCmd.startsWith('node ')) {
            rawCmd = `${NODE_BIN} ${rawCmd.slice(5).trim()}`;
        }

        const isHttp = rawCmd.startsWith('http://') || rawCmd.startsWith('https://');
        let cliArgs;
        if (isHttp) {
            cliArgs = ['mcp', 'add', '--type', 'http', name, rawCmd];
        } else {
            const parsed = splitCommandAndArgs(rawCmd);
            const executable = parsed.command || rawCmd;
            const combinedArgs = [...parsed.args, ...extraPayloadArgs];
            cliArgs = ['mcp', 'add', name, executable, ...combinedArgs];
        }

        const r = await runAgySubcommand(cliArgs);
        syncMcpConfigFileFromSettings();
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `MCP-сервер "${name}" добавлен`,
            error: r.ok ? '' : (r.stderr || r.stdout || 'Не удалось добавить MCP-сервер'),
            mcpServers: await listMcpServers()
        };
    }

    if (action === 'enable') {
        const r = await runAgySubcommand(['mcp', 'enable', name]);
        syncMcpConfigFileFromSettings();
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `MCP-сервер "${name}" включён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            mcpServers: await listMcpServers()
        };
    }

    if (action === 'disable') {
        const r = await runAgySubcommand(['mcp', 'disable', name]);
        syncMcpConfigFileFromSettings();
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `MCP-сервер "${name}" отключён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            mcpServers: await listMcpServers()
        };
    }

    if (action === 'remove' || action === 'uninstall' || action === 'delete') {
        const r = await runAgySubcommand(['mcp', 'remove', name]);
        syncMcpConfigFileFromSettings();
        invalidateAllIdleWorkers();
        return {
            ok: r.ok,
            message: r.stdout || `MCP-сервер "${name}" удалён`,
            error: r.ok ? '' : (r.stderr || r.stdout),
            mcpServers: await listMcpServers()
        };
    }

    return { ok: false, error: `Неизвестное действие для MCP: ${action}`, mcpServers: await listMcpServers() };
}

// ============================================================================
// Потоковый нейросетевой синтез речи Microsoft Edge Neural TTS (без ключей!)
// Пул прогретых WebSocket-сокетов MsEdgeTTS обеспечивает первый байт за ~0.35 с
// ============================================================================
const warmTtsSockets = new Map();
const ttsAudioCache = new Map();
const MAX_TTS_CACHE = 80;

function resolveEdgeNeuralVoice(requestedVoice, hasCyrillic) {
    const v = (requestedVoice || 'Aoede').trim().toLowerCase();
    const isMale = v.includes('dmitry') || v.includes('puck') || v.includes('charon') || v.includes('fenrir') || v.includes('male');
    if (hasCyrillic) {
        return isMale ? 'ru-RU-DmitryNeural' : 'ru-RU-SvetlanaNeural';
    }
    return isMale ? 'en-US-GuyNeural' : 'en-US-AriaNeural';
}

function escapeXmlForSsml(str) {
    return (str || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&apos;');
}

async function createConnectedTtsClient(edgeVoice) {
    const tts = new MsEdgeTTS({ connectionTimeoutMs: 9000, synthesisTimeoutMs: 25000 });
    await tts.setMetadata(edgeVoice, OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3);
    return tts;
}

function replenishWarmTtsSocket(edgeVoice) {
    if (warmTtsSockets.has(edgeVoice)) {
        const cur = warmTtsSockets.get(edgeVoice);
        if (cur && cur.ws && cur.ws.readyState === 1 && (Date.now() - cur.createdAt < 22000)) {
            return;
        }
    }
    createConnectedTtsClient(edgeVoice)
        .then(tts => {
            warmTtsSockets.set(edgeVoice, { tts, ws: tts._ws, createdAt: Date.now() });
        })
        .catch(() => {});
}

async function acquireWarmTtsClient(edgeVoice) {
    const entry = warmTtsSockets.get(edgeVoice);
    warmTtsSockets.delete(edgeVoice);
    // Сразу прогреваем следующее соединение в фоне
    setImmediate(() => replenishWarmTtsSocket(edgeVoice));

    if (entry && entry.tts && entry.ws && entry.ws.readyState === 1 && (Date.now() - entry.createdAt < 22000)) {
        return entry.tts;
    }
    if (entry && entry.tts) {
        try { entry.tts.close(); } catch (_) {}
    }
    return await createConnectedTtsClient(edgeVoice);
}

// Прогреваем оба русских нейроголоса при старте сервера и поддерживаем пул горячим
setTimeout(() => {
    replenishWarmTtsSocket('ru-RU-SvetlanaNeural');
    setTimeout(() => replenishWarmTtsSocket('ru-RU-DmitryNeural'), 800);
}, 150);
setInterval(() => {
    replenishWarmTtsSocket('ru-RU-SvetlanaNeural');
    replenishWarmTtsSocket('ru-RU-DmitryNeural');
}, 18000);

async function streamEdgeNeuralTtsAudio(rawText, requestedVoice, rateParam, res) {
    const clean = (rawText || 'Готов к работе')
        .replace(/```[\s\S]*?```/g, ' ')
        .replace(/[#*`_~>\[\]()]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 600);

    const hasCyrillic = /[а-яёА-ЯЁ]/.test(clean);
    const edgeVoice = resolveEdgeNeuralVoice(requestedVoice, hasCyrillic);
    const numericRate = parseFloat(rateParam || '1.05');
    const ratePct = Number.isFinite(numericRate)
        ? `${ numericRate >= 1 ? '+' : '' }${ Math.round((numericRate - 1) * 100) }%`
        : '+5%';

    const cacheKey = `${edgeVoice}|${ratePct}|${clean}`;
    if (ttsAudioCache.has(cacheKey)) {
        const cachedBuf = ttsAudioCache.get(cacheKey);
        res.writeHead(200, {
            'Content-Type': 'audio/mpeg',
            'Content-Length': cachedBuf.length,
            'Access-Control-Allow-Origin': '*',
            'X-TTS-Provider': `MsEdgeTTS/${edgeVoice} (cache)`
        });
        return res.end(cachedBuf);
    }

    const safeInput = escapeXmlForSsml(clean || 'Готов.');

    for (let attempt = 0; attempt < 2; attempt++) {
        try {
            const tts = await acquireWarmTtsClient(edgeVoice);
            const { audioStream } = tts.toStream(safeInput, { rate: ratePct });

            let headersSent = false;
            const chunks = [];

            await new Promise((resolve, reject) => {
                audioStream.on('data', (chunk) => {
                    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
                    chunks.push(buf);
                    if (!headersSent && !res.writableEnded) {
                        headersSent = true;
                        res.writeHead(200, {
                            'Content-Type': 'audio/mpeg',
                            'Transfer-Encoding': 'chunked',
                            'Access-Control-Allow-Origin': '*',
                            'Cache-Control': 'no-cache',
                            'X-TTS-Provider': `MsEdgeTTS/${edgeVoice}`
                        });
                    }
                    if (!res.writableEnded) {
                        res.write(buf);
                    }
                });
                audioStream.on('end', () => resolve());
                audioStream.on('close', () => resolve());
                audioStream.on('error', (err) => reject(err));
            });

            if (chunks.length > 0) {
                const fullBuf = Buffer.concat(chunks);
                if (fullBuf.length > 256) {
                    if (ttsAudioCache.size >= MAX_TTS_CACHE) {
                        const oldestKey = ttsAudioCache.keys().next().value;
                        ttsAudioCache.delete(oldestKey);
                    }
                    ttsAudioCache.set(cacheKey, fullBuf);
                }
                if (!res.writableEnded) {
                    res.end();
                }
                return;
            }
        } catch (_) {
            if (res.headersSent) {
                try { res.end(); } catch (_) {}
                return;
            }
        }
    }

    if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, error: 'Neural TTS stream unavailable' }));
    }
}

/**
 * Живая мобильная веб-консоль Antigravity (/web) в тёмном стиле ChatGPT 1.2026.293.
 */
function renderLiveWebConsoleHtml(initialSessionId) {
    return `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
<title>Antigravity Web</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  html, body { width: 100%; height: 100%; background: #000000; color: #ECECEC; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; overflow: hidden; }
  .app { display: flex; flex-direction: column; width: 100%; height: 100%; background: #000000; }
  .toolbar { background: #141414; border-bottom: 1px solid #242424; padding: 8px 12px; display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-shrink: 0; }
  .toolbar-left { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: #10A37F; flex-shrink: 0; box-shadow: 0 0 6px #10A37F; }
  select { background: #212121; color: #FFFFFF; border: 1px solid #2F2F2F; border-radius: 16px; padding: 6px 10px; font-size: 12.5px; font-weight: 500; outline: none; max-width: 100%; text-overflow: ellipsis; }
  #sessionSelect { flex: 1; min-width: 110px; }
  #modelSelect { width: 145px; flex-shrink: 0; }
  #chat { flex: 1; overflow-y: auto; padding: 14px 14px 18px 14px; display: flex; flex-direction: column; gap: 14px; scroll-behavior: smooth; }
  .empty-state { margin: auto; text-align: center; color: #8E8E8E; font-size: 14px; padding: 24px; }
  .empty-state h2 { color: #FFFFFF; font-size: 20px; margin-bottom: 6px; }
  .msg-wrap { display: flex; flex-direction: column; max-width: 100%; }
  .msg-wrap.USER { align-items: flex-end; }
  .msg-wrap.AGENT { align-items: flex-start; }
  .bubble { line-height: 1.48; font-size: 14.5px; white-space: pre-wrap; word-break: break-word; }
  .USER .bubble { background: #2F2F2F; color: #FFFFFF; padding: 10px 15px; border-radius: 20px; max-width: 85%; }
  .AGENT .bubble { color: #ECECEC; padding: 2px 2px; width: 100%; }
  .thinking { font-size: 12px; color: #8E8E8E; margin-bottom: 5px; display: flex; align-items: center; gap: 4px; }
  .meta { font-size: 11px; color: #6E6E6E; margin-top: 4px; }
  pre { background: #0D0D0D; border: 1px solid #262626; border-radius: 10px; padding: 10px 12px; overflow-x: auto; font-family: 'Menlo', 'Consolas', monospace; font-size: 12px; color: #E0E0E0; margin: 6px 0; white-space: pre; }
  footer { background: #000000; padding: 8px 12px 12px 12px; flex-shrink: 0; }
  .input-pill { background: #212121; border: 1px solid #2F2F2F; border-radius: 26px; padding: 5px 6px 5px 14px; display: flex; align-items: center; gap: 8px; }
  input { flex: 1; background: transparent; color: #FFFFFF; border: none; font-size: 15px; outline: none; padding: 6px 0; }
  input::placeholder { color: #8E8E8E; }
  button { background: #FFFFFF; color: #000000; border: none; border-radius: 50%; width: 36px; height: 36px; font-weight: 700; cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
  button:disabled { opacity: 0.35; }
</style>
</head>
<body>
<div class="app">
  <div class="toolbar">
    <div class="toolbar-left">
      <span class="dot"></span>
      <select id="sessionSelect" title="Выбор сессии ПК"></select>
    </div>
    <select id="modelSelect" title="Выбор модели ИИ"></select>
  </div>
  <div id="chat">
    <div class="empty-state"><h2>Чем я могу помочь?</h2>Выберите сессию сверху или отправьте сообщение</div>
  </div>
  <footer>
    <div class="input-pill">
      <input id="promptInput" placeholder="Спросить Antigravity..." autocomplete="off" />
      <button id="sendBtn" onclick="sendMsg()">↑</button>
    </div>
  </footer>
</div>
<script>
let currentSessionId = ${JSON.stringify(initialSessionId || '')};
let lastMsgHash = '';

function renderFormattedContent(container, text) {
  const parts = String(text || '').split(/(\`\`\`[\\s\\S]*?\`\`\`)/g);
  parts.forEach(part => {
    if (part.startsWith('\`\`\`') && part.endsWith('\`\`\`')) {
      const lines = part.slice(3, -3).split('\\n');
      if (lines.length > 0 && /^[a-zA-Z0-9_-]+$/.test(lines[0].trim())) {
        lines.shift();
      }
      const pre = document.createElement('pre');
      pre.textContent = lines.join('\\n').trim();
      container.appendChild(pre);
    } else if (part.trim().length > 0) {
      const span = document.createElement('div');
      const cleaned = part
        .replace(/\\[\`([^\`]+)\`\\]\\([^)]+\\)/g, '$1')
        .replace(/\\[([^\\]]+)\\]\\([^)]+\\)/g, '$1')
        .replace(/file:\\/\\/\\/[A-Za-z]:\\/[^\\s)\\]\`"']+/g, m => {
          const base = m.split('/').pop() || 'файл';
          return base.split('#')[0];
        });
      span.textContent = cleaned;
      container.appendChild(span);
    }
  });
}

async function initConsole() {
  try {
    const mr = await fetch('/api/models');
    const md = await mr.json();
    const msel = document.getElementById('modelSelect');
    msel.innerHTML = '';
    (md.models || []).forEach(m => {
      const opt = document.createElement('option');
      opt.value = m.id;
      opt.textContent = m.displayName;
      msel.appendChild(opt);
    });
  } catch (_) {}

  try {
    const r = await fetch('/api/sessions');
    const d = await r.json();
    const sel = document.getElementById('sessionSelect');
    sel.innerHTML = '';
    (d.sessions || []).forEach((s, idx) => {
      const opt = document.createElement('option');
      opt.value = s.id;
      opt.textContent = s.title + ' (' + (s.messageCount || 0) + ')';
      if ((currentSessionId && s.id === currentSessionId) || (!currentSessionId && idx === 0)) {
        opt.selected = true;
        currentSessionId = s.id;
      }
      sel.appendChild(opt);
    });
    sel.onchange = () => {
      currentSessionId = sel.value;
      lastMsgHash = '';
      pollMessages();
    };
  } catch (_) {}
  pollMessages();
}

async function pollMessages() {
  if (!currentSessionId) return;
  try {
    const r = await fetch('/api/session?id=' + encodeURIComponent(currentSessionId));
    const d = await r.json();
    const msgs = d.messages || [];
    const hash = msgs.length + ':' + (msgs.length ? msgs[msgs.length - 1].id + '_' + msgs[msgs.length - 1].content.length : '');
    if (hash !== lastMsgHash) {
      lastMsgHash = hash;
      const box = document.getElementById('chat');
      box.innerHTML = '';
      if (msgs.length === 0) {
        box.innerHTML = '<div class="empty-state"><h2>Чем я могу помочь?</h2>Отправьте сообщение для начала диалога</div>';
        return;
      }
      msgs.forEach(m => {
        const wrap = document.createElement('div');
        wrap.className = 'msg-wrap ' + m.role;
        if (m.role === 'AGENT') {
          const th = document.createElement('div');
          th.className = 'thinking';
          th.textContent = 'Думал ' + (m.thinkingDurationSec || 2) + ' с';
          wrap.appendChild(th);
        }
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        renderFormattedContent(bubble, m.content);
        wrap.appendChild(bubble);
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = (m.role === 'USER' ? 'Вы' : 'Antigravity') + (m.timestamp ? ' • ' + m.timestamp : '');
        wrap.appendChild(meta);
        box.appendChild(wrap);
      });
      box.scrollTop = box.scrollHeight;
    }
  } catch (_) {}
}

async function sendMsg() {
  const inp = document.getElementById('promptInput');
  const btn = document.getElementById('sendBtn');
  const msel = document.getElementById('modelSelect');
  const text = inp.value.trim();
  if (!text) return;
  inp.value = '';
  btn.disabled = true;

  const box = document.getElementById('chat');
  const optimistic = document.createElement('div');
  optimistic.className = 'msg-wrap USER';
  optimistic.innerHTML = '<div class="bubble"></div>';
  optimistic.querySelector('.bubble').textContent = text;
  box.appendChild(optimistic);
  box.scrollTop = box.scrollHeight;

  try {
    await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: text,
        conversationId: currentSessionId,
        model: msel.value || 'gemini-4-argon-high'
      })
    });
    lastMsgHash = '';
    await pollMessages();
  } finally {
    btn.disabled = false;
  }
}

document.getElementById('promptInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendMsg();
});

initConsole();
setInterval(pollMessages, 2000);
</script>
</body>
</html>`;
}

// ============================================================================
// ROUND 5: Живая синхронизация OpenRouter API, Генератор MP4/PNG, Мульти-Агенты (Лидер),
// Управление контекстным окном (/api/context/*) и Политики разрешений Antigravity 2.0
// ============================================================================
const zlib = require('zlib');
const MEDIA_DIR = path.join(UPLOADS_DIR, 'media');
fs.mkdirSync(MEDIA_DIR, { recursive: true });

const OPENROUTER_CONFIG_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'openrouter-config.json');
const OPENROUTER_CACHE_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'openrouter-models-cache.json');
const PERMISSIONS_CONFIG_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'permissions-config.json');
const SWARM_STATE_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'swarm-state.json');
const SYNC_STATE_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'sync-state.json');

function loadOpenRouterConfig() {
    try {
        if (fs.existsSync(OPENROUTER_CONFIG_FILE)) {
            return JSON.parse(fs.readFileSync(OPENROUTER_CONFIG_FILE, 'utf8'));
        }
    } catch (_) {}
    return {
        apiKey: process.env.OPENROUTER_API_KEY || '',
        enabled: true,
        selectedModel: '',
        autoFreeFallback: true
    };
}

function saveOpenRouterConfig(patch) {
    const current = loadOpenRouterConfig();
    const updated = { ...current, ...patch, updatedAt: new Date().toISOString() };
    const aliases = {"phoneFileSystemAccess":"phoneFileReadWrite","phoneAndroidCliAccess":"phoneAndroidCliShell","phoneVirtualSandboxAccess":"phoneVirtualSandboxExec","phoneCameraMediaAccess":"phoneMediaCameraMic","phoneContactsCalendarSmsAccess":"phoneContactsRead","pcWorkspaceReadWrite":"pcFilesystemWrite","pcPowerShellExecution":"pcTerminalExec","pcBrowserAndNetworkAccess":"pcBrowserAutomation","pcPluginsMcpSkillsAccess":"pcMcpToolsExec"};
    for (const [alias, canonical] of Object.entries(aliases)) {
        if (typeof patch[canonical] === 'boolean') updated[alias] = patch[canonical];
        else if (typeof patch[alias] === 'boolean') updated[canonical] = patch[alias];
    }
    try {
        fs.writeFileSync(OPENROUTER_CONFIG_FILE, JSON.stringify(updated, null, 2), 'utf8');
    } catch (_) {}
    return updated;
}

let liveOpenRouterModelsMemoryCache = [];
let lastOpenRouterSyncMs = 0;

function classifyOpenRouterModel(raw) {
    const id = String(raw.id || '').trim();
    const name = String(raw.name || id).trim();
    const created = Number(raw.created) || 0;
    const contextLength = Number(raw.context_length) || (raw.top_provider && Number(raw.top_provider.context_length)) || 128000;
    const arch = raw.architecture || {};
    const modality = String(arch.modality || 'text->text').toLowerCase();
    const inputModalities = Array.isArray(arch.input_modalities)
        ? arch.input_modalities.map(x => String(x).toLowerCase())
        : (modality.split('->')[0] || 'text').split('+');
    const outputModalities = Array.isArray(arch.output_modalities)
        ? arch.output_modalities.map(x => String(x).toLowerCase())
        : (modality.split('->')[1] || 'text').split('+');
    const supportedParams = Array.isArray(raw.supported_parameters)
        ? raw.supported_parameters.map(x => String(x).toLowerCase())
        : [];
    const pricing = raw.pricing || {};
    const promptPrice = String(pricing.prompt ?? '0');
    const completionPrice = String(pricing.completion ?? '0');

    const isFree = id.endsWith(':free') || (
        (promptPrice === '0' || promptPrice === '0.0' || Number(promptPrice) === 0) &&
        (completionPrice === '0' || completionPrice === '0.0' || Number(completionPrice) === 0)
    );
    const lowerId = id.toLowerCase();
    const lowerName = name.toLowerCase();

    const isImageGen = outputModalities.includes('image') ||
        modality.includes('->image') ||
        /(flux|stable-diffusion|sdxl|dall-e|ideogram|recraft|imagen|seedream|midjourney|playground|gpt-image|riverflow)/i.test(lowerId);

    const isVideoGen = outputModalities.includes('video') ||
        modality.includes('->video') ||
        /\b(veo|sora|kling|runway|luma|ray-2|pika|wan|hunyuan-video|minimax\/video|minimax-video|cogvideo|mochi|seedance|vidu|hailuo|video-01)\b/i.test(lowerId) ||
        lowerId.includes('video');

    const isAudio = inputModalities.includes('audio') || outputModalities.includes('audio') ||
        lowerId.includes('audio') || lowerId.includes('whisper') || lowerId.includes('sonar');

    const isVision = inputModalities.includes('image') || inputModalities.includes('video') ||
        modality.includes('image') || lowerId.includes('-vl') || lowerId.includes('vision') || lowerId.includes('pixtral');

    const isReasoning = supportedParams.includes('reasoning') ||
        supportedParams.includes('include_reasoning') ||
        lowerId.includes('thinking') || lowerId.includes('reasoner') ||
        lowerId.includes('deepseek-r1') || lowerId.includes('qwq') ||
        lowerName.includes('thinking') || lowerName.includes('reasoning');

    const categories = ['ALL'];
    if (isReasoning) categories.push('REASONING');
    if (isVision) categories.push('VISION');
    if (isImageGen) categories.push('IMAGE_GEN');
    if (isVideoGen) categories.push('VIDEO_GEN');
    if (isAudio) categories.push('AUDIO');
    if (isFree) categories.push('FREE');
    categories.push('TEXT');

    let primaryCategory = 'TEXT';
    if (isVideoGen) primaryCategory = 'VIDEO_GEN';
    else if (isImageGen) primaryCategory = 'IMAGE_GEN';
    else if (isAudio) primaryCategory = 'AUDIO';
    else if (isReasoning) primaryCategory = 'REASONING';
    else if (isVision) primaryCategory = 'VISION';

    const createdDateStr = created > 0
        ? new Date(created * 1000).toISOString().slice(0, 10)
        : '';

    const pricePerM = (!isFree && Number(promptPrice) > 0)
        ? `$${(Number(promptPrice) * 1000000).toFixed(2)}/1M`
        : 'Бесплатно (Free)';

    return {
        id,
        name,
        created,
        createdDate: createdDateStr,
        description: String(raw.description || '').slice(0, 320),
        contextLength,
        modality,
        inputModalities,
        outputModalities,
        supportedParameters: supportedParams,
        isFree,
        isReasoning,
        isVision,
        isImageGen,
        isVideoGen,
        isAudio,
        primaryCategory,
        categories,
        priceLabel: pricePerM
    };
}

function fetchLiveOpenRouterModels(forceRefresh = false) {
    const OPENROUTER_MODELS_CACHE_JSON = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'openrouter_models_cache.json');
    if (liveOpenRouterModelsMemoryCache.length === 0) {
        for (const cFile of [OPENROUTER_MODELS_CACHE_JSON, OPENROUTER_CACHE_FILE]) {
            try {
                if (fs.existsSync(cFile)) {
                    const diskObj = JSON.parse(fs.readFileSync(cFile, 'utf8'));
                    if (Array.isArray(diskObj.models) && diskObj.models.length > 0) {
                        liveOpenRouterModelsMemoryCache = diskObj.models;
                        lastOpenRouterSyncMs = diskObj.cachedAtMs || Date.parse(diskObj.syncedAt || diskObj.cachedAt || '') || Date.now();
                        if (!fs.existsSync(OPENROUTER_MODELS_CACHE_JSON)) {
                            fs.writeFileSync(OPENROUTER_MODELS_CACHE_JSON, JSON.stringify({
                                cachedAt: new Date(lastOpenRouterSyncMs).toISOString(),
                                cachedAtMs: lastOpenRouterSyncMs,
                                count: liveOpenRouterModelsMemoryCache.length,
                                models: liveOpenRouterModelsMemoryCache
                            }), 'utf8');
                        }
                        break;
                    }
                }
            } catch (_) {}
        }
    }

    return new Promise((resolve) => {
        const now = Date.now();
        if (!forceRefresh && liveOpenRouterModelsMemoryCache.length > 0) {
            if ((now - lastOpenRouterSyncMs) >= 10 * 60 * 1000) {
                setImmediate(() => { fetchLiveOpenRouterModels(true).catch(() => {}); });
            }
            return resolve({
                ok: true,
                fromCache: true,
                source: 'disk+memory-cache',
                syncedAt: new Date(lastOpenRouterSyncMs || now).toISOString(),
                cachedAt: new Date(lastOpenRouterSyncMs || now).toISOString(),
                totalCount: liveOpenRouterModelsMemoryCache.length,
                totalCached: liveOpenRouterModelsMemoryCache.length,
                models: liveOpenRouterModelsMemoryCache
            });
        }

        const req = https.get('https://openrouter.ai/api/v1/models', {
            headers: {
                'Accept': 'application/json',
                'User-Agent': 'Antigravity-Android-2.0'
            },
            timeout: 12000
        }, (res) => {
            let body = '';
            res.on('data', chunk => { body += chunk.toString('utf8'); });
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(body);
                    const rawList = Array.isArray(parsed.data) ? parsed.data : [];
                    if (rawList.length > 0) {
                        const classified = rawList
                            .map(classifyOpenRouterModel)
                            .filter(m => m.id && m.name)
                            .sort((a, b) => (b.created || 0) - (a.created || 0));
                        liveOpenRouterModelsMemoryCache = classified;
                        lastOpenRouterSyncMs = Date.now();
                        const cachePayload = JSON.stringify({
                            cachedAt: new Date(lastOpenRouterSyncMs).toISOString(),
                            syncedAt: new Date(lastOpenRouterSyncMs).toISOString(),
                            cachedAtMs: lastOpenRouterSyncMs,
                            count: classified.length,
                            models: classified
                        });
                        try {
                            fs.writeFileSync(OPENROUTER_CACHE_FILE, cachePayload, 'utf8');
                            fs.writeFileSync(OPENROUTER_MODELS_CACHE_JSON, cachePayload, 'utf8');
                        } catch (_) {}
                        return resolve({
                            ok: true,
                            fromCache: false,
                            source: 'openrouter.ai/api/v1/models',
                            syncedAt: new Date(lastOpenRouterSyncMs).toISOString(),
                            cachedAt: new Date(lastOpenRouterSyncMs).toISOString(),
                            totalCount: classified.length,
                            totalCached: classified.length,
                            models: classified
                        });
                    }
                } catch (_) {}
                resolve(loadCachedOpenRouterModelsFallback());
            });
        });

        req.on('error', () => resolve(loadCachedOpenRouterModelsFallback()));
        req.on('timeout', () => {
            try { req.destroy(); } catch (_) {}
            resolve(loadCachedOpenRouterModelsFallback());
        });
    });
}

function loadCachedOpenRouterModelsFallback() {
    const OPENROUTER_MODELS_CACHE_JSON = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'openrouter_models_cache.json');
    if (liveOpenRouterModelsMemoryCache.length > 0) {
        const ts = new Date(lastOpenRouterSyncMs || Date.now()).toISOString();
        return {
            ok: true,
            fromCache: true,
            source: 'memory-cache',
            syncedAt: ts,
            cachedAt: ts,
            totalCount: liveOpenRouterModelsMemoryCache.length,
            totalCached: liveOpenRouterModelsMemoryCache.length,
            models: liveOpenRouterModelsMemoryCache
        };
    }
    for (const cFile of [OPENROUTER_MODELS_CACHE_JSON, OPENROUTER_CACHE_FILE]) {
        try {
            if (fs.existsSync(cFile)) {
                const cached = JSON.parse(fs.readFileSync(cFile, 'utf8'));
                if (Array.isArray(cached.models) && cached.models.length > 0) {
                    liveOpenRouterModelsMemoryCache = cached.models;
                    const ts = cached.cachedAt || cached.syncedAt || new Date().toISOString();
                    return {
                        ok: true,
                        fromCache: true,
                        source: 'disk-cache',
                        syncedAt: ts,
                        cachedAt: ts,
                        totalCount: cached.models.length,
                        totalCached: cached.models.length,
                        models: cached.models
                    };
                }
            }
        } catch (_) {}
    }
    return {
        ok: false,
        fromCache: false,
        source: 'offline',
        syncedAt: new Date().toISOString(),
        cachedAt: new Date().toISOString(),
        totalCount: 0,
        totalCached: 0,
        models: []
    };
}

// Мгновенная инициализация кэша с диска + фоновое обновление при старте моста
fetchLiveOpenRouterModels(false).catch(() => {});
setTimeout(() => { fetchLiveOpenRouterModels(true).catch(() => {}); }, 800);

// ----------------------------------------------------------------------------
// Google Диск (Google Drive Cloud Workspace) для Gemini И OpenRouter (Раздел 11.5)
// ----------------------------------------------------------------------------
const GDRIVE_WORKSPACE_DIR = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'gdrive_cloud_workspace');
const GDRIVE_METADATA_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'gdrive_metadata.json');
fs.mkdirSync(GDRIVE_WORKSPACE_DIR, { recursive: true });

function loadGDriveMetadataMap() {
    try {
        if (fs.existsSync(GDRIVE_METADATA_FILE)) {
            return JSON.parse(fs.readFileSync(GDRIVE_METADATA_FILE, 'utf8'));
        }
    } catch (_) {}
    return {};
}

function saveGDriveMetadataMap(mapObj) {
    try {
        fs.writeFileSync(GDRIVE_METADATA_FILE, JSON.stringify(mapObj, null, 2), 'utf8');
    } catch (_) {}
}

function listGoogleDriveFiles(query = '') {
    const meta = loadGDriveMetadataMap();
    const q = String(query || '').toLowerCase().trim();
    const entries = [];
    try {
        for (const f of fs.readdirSync(GDRIVE_WORKSPACE_DIR, { withFileTypes: true })) {
            if (!f.isFile()) continue;
            if (q && !f.name.toLowerCase().includes(q)) continue;
            const fullPath = path.join(GDRIVE_WORKSPACE_DIR, f.name);
            const st = fs.statSync(fullPath);
            const rawText = fs.readFileSync(fullPath, 'utf8');
            const m = meta[f.name] || {};
            entries.push({
                id: m.id || `gdrive_${Buffer.from(f.name).toString('hex').slice(0, 16)}`,
                name: f.name,
                folder: m.folder || '/AntigravityCloud',
                engine: m.engine || 'Gemini',
                modelId: m.modelId || 'gemini-4-argon-high',
                mimeType: m.mimeType || (f.name.endsWith('.json') ? 'application/json' : 'text/markdown'),
                sizeBytes: st.size,
                updatedAt: m.updatedAt || new Date(st.mtimeMs).toISOString(),
                webViewLink: `/download/gdrive/${encodeURIComponent(f.name)}`,
                downloadUrl: `/download/gdrive/${encodeURIComponent(f.name)}`,
                localUri: `file:///${fullPath.replace(/\\/g, '/')}`,
                previewSnippet: rawText.slice(0, 260)
            });
        }
    } catch (_) {}
    entries.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
    return entries;
}

function readGoogleDriveFile(fileName) {
    const safeName = path.basename(String(fileName || '').trim());
    const fullPath = path.join(GDRIVE_WORKSPACE_DIR, safeName);
    if (!fs.existsSync(fullPath)) {
        return { ok: false, error: `Файл ${safeName} не найден на Google Диске` };
    }
    const content = fs.readFileSync(fullPath, 'utf8');
    const meta = loadGDriveMetadataMap()[safeName] || {};
    return {
        ok: true,
        name: safeName,
        folder: meta.folder || '/AntigravityCloud',
        engine: meta.engine || 'Gemini',
        modelId: meta.modelId || 'gemini-4-argon-high',
        content,
        sizeBytes: Buffer.byteLength(content, 'utf8'),
        webViewLink: `/download/gdrive/${encodeURIComponent(safeName)}`,
        downloadUrl: `/download/gdrive/${encodeURIComponent(safeName)}`
    };
}

function createOrUpdateGoogleDriveFile(payload = {}) {
    const rawName = String(payload.name || payload.fileName || `antigravity_doc_${Date.now()}.md`).trim();
    const safeName = path.basename(rawName).replace(/[^a-zA-Z0-9._\-\u0400-\u04FF]/g, '_') || 'document.md';
    const engine = String(payload.engine || (String(payload.modelId || '').includes('openrouter') || String(payload.modelId || '').includes('/') ? 'OpenRouter' : 'Gemini'));
    const modelId = String(payload.modelId || (engine === 'OpenRouter' ? 'particle-ai/particle-1' : 'gemini-4-argon-high'));
    const folder = String(payload.folder || '/AntigravityCloud');
    const fullPath = path.join(GDRIVE_WORKSPACE_DIR, safeName);
    const existed = fs.existsSync(fullPath);
    const prevContent = existed ? fs.readFileSync(fullPath, 'utf8') : '';
    if (payload.content === undefined || payload.content === null) {
        return { ok: false, error: 'Provide file content; AI file generation is not connected.' };
    }
    const content = String(payload.content);

    fs.writeFileSync(fullPath, content, 'utf8');
    const meta = loadGDriveMetadataMap();
    const itemMeta = {
        id: (meta[safeName] && meta[safeName].id) || `gdrive_${Date.now().toString(36)}`,
        name: safeName,
        folder,
        engine,
        modelId,
        mimeType: payload.mimeType || (safeName.endsWith('.json') ? 'application/json' : (safeName.endsWith('.py') || safeName.endsWith('.kt') ? 'text/plain' : 'text/markdown')),
        updatedAt: new Date().toISOString(),
        webViewLink: `/download/gdrive/${encodeURIComponent(safeName)}`
    };
    meta[safeName] = itemMeta;
    saveGDriveMetadataMap(meta);

    const addedLines = content.split(/\r?\n/).length;
    const removedLines = existed ? prevContent.split(/\r?\n/).length : 0;
    const diffSnippet = [
        `@@ -${existed ? 1 : 0},${removedLines} +1,${addedLines} @@`,
        ...(existed ? prevContent.split(/\r?\n/).slice(0, 4).map(l => `-${l}`) : []),
        ...content.split(/\r?\n/).slice(0, 10).map(l => `+${l}`)
    ].join('\n');

    return {
        ok: true,
        action: existed ? 'updated' : 'created',
        file: {
            ...itemMeta,
            sizeBytes: Buffer.byteLength(content, 'utf8'),
            downloadUrl: `/download/gdrive/${encodeURIComponent(safeName)}`,
            localUri: `file:///${fullPath.replace(/\\/g, '/')}`,
            previewSnippet: content.slice(0, 260),
            content
        },
        diff: {
            path: `${folder}/${safeName}`,
            additions: addedLines,
            deletions: removedLines,
            diffPatch: diffSnippet
        }
    };
}

function deleteGoogleDriveFile(fileName) {
    const safeName = path.basename(String(fileName || '').trim());
    const fullPath = path.join(GDRIVE_WORKSPACE_DIR, safeName);
    if (fs.existsSync(fullPath)) {
        try { fs.unlinkSync(fullPath); } catch (_) {}
    }
    const meta = loadGDriveMetadataMap();
    delete meta[safeName];
    saveGDriveMetadataMap(meta);
    return { ok: true, deleted: safeName };
}

// ----------------------------------------------------------------------------
// CRC32 и генератор валидных PNG изображений (640x360 RGB) и H.264 MP4 видео
// ----------------------------------------------------------------------------
const crc32Table = (() => {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        }
        table[n] = c >>> 0;
    }
    return table;
})();

async function executeOpenRouterStreamingTurn(options, onEvent = () => {}) {
    const startMs = Date.now();
    const {
        turnId = `turn_or_${Date.now()}`,
        cleanUserPrompt = '',
        model = '',
        conversationId = '',
        clientHistory = []
    } = options;

    const resolvedConvId = conversationId || require('crypto').randomUUID();
    const cleanModelId = String(model || '').replace(/^openrouter\//i, '').trim() || 'google/gemini-2.0-flash-exp:free';
    const orConfig = loadOpenRouterConfig();
    // Этап 5: собственный исполнитель hooks (docs/hooks.md). В CLI-ходах agy
    // мост свои hooks не запускает — там действует система кастомизаций CLI.
    const hookRunner = (typeof buildHookRunner === 'function')
        ? buildHookRunner({ conversationId: resolvedConvId, modelName: cleanModelId, lastUserInput: cleanUserPrompt })
        : null;
    if (hookRunner && Array.isArray(hookRunner.initialRefusals) && hookRunner.initialRefusals.length > 0) {
        try { onEvent({ type: 'hook-refusal', turnId, refusals: hookRunner.initialRefusals }); } catch (_) {}
    }

    const initEvt = {
        type: 'init',
        turnId,
        conversationId: resolvedConvId,
        model: cleanModelId,
        instanceName: INSTANCE_NAME,
        elapsedMs: 5
    };
    recordActiveTurnSnapshot(turnId, resolvedConvId, {
        status: 'running',
        model: cleanModelId,
        prompt: cleanUserPrompt
    });
    try { onEvent(initEvt); } catch (_) {}

    const modelProfile = liveOpenRouterModelsMemoryCache.find(m => m.id === cleanModelId) ||
        classifyOpenRouterModel({ id: cleanModelId, name: cleanModelId });
    // Если задан API-ключ OpenRouter — обращаемся напрямую к https://openrouter.ai/api/v1/chat/completions
    if (orConfig.apiKey && orConfig.apiKey.trim().length > 10) {
        const abortController = new AbortController();
        turnCancellations.set(turnId, () => abortController.abort());
        try {
            let sysContent = loadAgentSystemPrompt() +
                `\n\nТы работаешь как модель ${modelProfile.name || cleanModelId} (${cleanModelId}) через OpenRouter. Адаптируй ответы под свой профиль (${modelProfile.primaryCategory || 'TEXT'}, модальность ${modelProfile.modality || 'text->text'}).`;
            // Этап 4: инструкции выбранного навыка (только его) или отказ в доступе.
            if (options.skillRefusal) {
                sysContent += `\n\n<SKILL_ACCESS_REFUSAL>\n${options.skillRefusal}\n</SKILL_ACCESS_REFUSAL>`;
            } else if (options.skillInstructionBlock) {
                sysContent += `\n\n${options.skillInstructionBlock}`;
            }
            const messagesPayload = [
                { role: 'system', content: sysContent }
            ];
            if (Array.isArray(clientHistory)) {
                for (const m of clientHistory) {
                    messagesPayload.push({
                        role: (m.role === 'USER' || m.role === 'user') ? 'user' : 'assistant',
                        content: String(m.content || '')
                    });
                }
            }
            // Этап 5: SessionStart — один раз на разговор (hooks.md).
            if (hookRunner && typeof hookRunner.onSessionStart === 'function') {
                let alreadyStarted = false;
                if (typeof hookSessionsStarted !== 'undefined' && hookSessionsStarted) {
                    alreadyStarted = hookSessionsStarted.has(resolvedConvId);
                    hookSessionsStarted.add(resolvedConvId);
                }
                if (!alreadyStarted) {
                    try {
                        const sess = await hookRunner.onSessionStart();
                        if (sess && Array.isArray(sess.injectSteps) && sess.injectSteps.length > 0) {
                            messagesPayload.push(...extensionRuntime.injectStepsToMessages(sess.injectSteps));
                        }
                        if (sess && Array.isArray(sess.refusals) && sess.refusals.length > 0) {
                            try { onEvent({ type: 'hook-refusal', turnId, refusals: sess.refusals }); } catch (_) {}
                        }
                    } catch (_) {}
                }
            }
            messagesPayload.push({ role: 'user', content: cleanUserPrompt });

            // Этап 4: реальные схемы разрешённых инструментов (живой tools/list
            // реестра) и цикл вызовов с возвратом результатов в тот же цикл.
            const toolSet = await buildOpenRouterToolSet();
            let lastUsage = null;
            const orRequest = (msgs, toolSchemas) => new Promise((resolve, reject) => {
                const payload = {
                    model: cleanModelId,
                    messages: msgs,
                    include_reasoning: true
                };
                if (Array.isArray(toolSchemas) && toolSchemas.length > 0) {
                    payload.tools = toolSchemas;
                }
                const bodyStr = JSON.stringify(payload);
                const r = https.request('https://openrouter.ai/api/v1/chat/completions', {
                    method: 'POST',
                    headers: {
                        'Authorization': `Bearer ${orConfig.apiKey.trim()}`,
                        'Content-Type': 'application/json',
                        'HTTP-Referer': 'https://antigravity.google',
                        'X-Title': 'Google Antigravity Android 2.0'
                    },
                    timeout: 45000
                }, (resp) => {
                    let b = '';
                    resp.on('data', ch => { b += ch.toString('utf8'); });
                    resp.on('end', () => resolve({ status: resp.statusCode, body: b }));
                });
                r.on('error', reject);
                r.on('timeout', () => r.destroy(new Error('OpenRouter request timed out')));
                const onAbort = () => r.destroy(new Error('Generation cancelled'));
                if (abortController.signal.aborted) {
                    r.destroy();
                    reject(new Error('Generation cancelled'));
                    return;
                }
                abortController.signal.addEventListener('abort', onAbort, { once: true });
                r.write(bodyStr);
                r.end();
            });

            const loop = await extensionRuntime.runToolCallLoop({
                messages: messagesPayload,
                tools: toolSet.tools,
                nameToRef: toolSet.nameToRef,
                maxRounds: 6,
                signal: abortController.signal,
                hookRunner,
                chat: async (msgs, toolSchemas) => {
                    const resp = await orRequest(msgs, toolSchemas);
                    const parsed = JSON.parse(resp.body || '{}');
                    lastUsage = parsed.usage || lastUsage;
                    const choice = parsed.choices && parsed.choices[0] && parsed.choices[0].message;
                    if (!choice) {
                        throw new Error('OpenRouter не вернул ответ выбранной модели');
                    }
                    return { message: choice };
                },
                execute: (ref, args, { signal } = {}) => {
                    return extensionRuntime.executeBrokerToolCall(ref, args, {
                        catalog: toolSet.catalog,
                        allowedRefs: toolSet.allowedRefs,
                        callPcTool: callMcpToolViaRegistry,
                        signal
                    });
                },
                onToolEvent: (evt) => {
                    // Этап 5: события hooks — в поток хода (видимые отказы и
                    // блокировки, ничего не запускается молча).
                    if (evt.type === 'hook-refusal') {
                        try {
                            onEvent({ type: 'hook-refusal', turnId, refusals: evt.refusals, elapsedMs: Date.now() - startMs });
                        } catch (_) {}
                        return;
                    }
                    if (evt.type === 'hook') {
                        try {
                            onEvent({ type: 'hook', turnId, id: evt.callId ? `hook_${evt.callId}` : `hook_${Date.now()}`,
                                toolName: evt.ref, status: evt.status || 'COMPLETED', summary: `hook ${evt.status || ''}`,
                                details: evt.reason || '', elapsedMs: Date.now() - startMs });
                        } catch (_) {}
                        return;
                    }
                    if (evt.type !== 'end') return;
                    const summary = extensionRuntime.toolResultToText(evt.result).slice(0, 400);
                    try {
                        onEvent({
                            type: 'tool',
                            turnId,
                            id: `or_${evt.callId}`,
                            toolName: evt.ref,
                            summary: formatToolSummary(evt.ref.split(':').pop() || evt.ref, {}),
                            details: summary,
                            status: evt.result && evt.result.ok ? 'COMPLETED' : 'FAILED',
                            elapsedMs: Date.now() - startMs
                        });
                    } catch (_) {}
                }
            });

            if (loop.cancelled || abortController.signal.aborted) {
                const cancelError = 'Остановлено пользователем (Stop).';
                const cancelled = { type: 'done', ok: false, turnId, conversationId: resolvedConvId,
                    model: cleanModelId, reply: cancelError, error: cancelError,
                    thinking: '', toolCalls: [], createdDiffs: [], generatedMedia: [] };
                recordActiveTurnSnapshot(turnId, resolvedConvId, { status: 'cancelled', ...cancelled });
                try { onEvent(cancelled); } catch (_) {}
                turnCancellations.delete(turnId);
                return cancelled;
            }

            const choice = loop.finalMessage;
            if (choice && (choice.content || loop.toolCalls.length > 0)) {
                const extracted = extractThinkingAndCleanReply(String(choice.content || ''), choice.reasoning || '');
                const finalReplyText = extracted.reply || String(choice.content || '');
                const totalMs = Math.max(200, Date.now() - startMs);
                const outTokens = Number(lastUsage && lastUsage.completion_tokens) || 0;
                const tokPerSec = Number((outTokens / Math.max(0.25, totalMs / 1000)).toFixed(1));
                try {
                    onEvent({
                        type: 'text_delta',
                        turnId,
                        delta: finalReplyText,
                        text: finalReplyText,
                        fullText: finalReplyText,
                        tokensPerSecond: tokPerSec,
                        outputTokensCount: outTokens,
                        elapsedMs: totalMs
                    });
                } catch (_) {}
                const donePayload = {
                    type: 'done',
                    ok: true,
                    turnId,
                    instanceName: INSTANCE_NAME,
                    conversationId: resolvedConvId,
                    model: cleanModelId,
                    thinking: extracted.thinking || '',
                    thinkingDurationSec: Math.max(1, Math.round(totalMs / 1000)),
                    reply: finalReplyText,
                    toolCalls: loop.toolCalls.map(tc => ({
                        id: tc.callId,
                        name: tc.ref,
                        summary: tc.ref,
                        output: tc.ok ? 'выполнен' : (tc.code || 'ошибка'),
                        status: tc.ok ? 'COMPLETED' : 'FAILED'
                    })),
                    createdDiffs: [],
                    generatedMedia: [],
                    phoneActions: extractPhoneActionsFromText(finalReplyText),
                    ttfbMs: totalMs,
                    totalMs,
                    outputTokensCount: outTokens,
                    tokensPerSecond: tokPerSec
                };
                recordActiveTurnSnapshot(turnId, resolvedConvId, { status: 'completed', ...donePayload });
                try { onEvent(donePayload); } catch (_) {}
                turnCancellations.delete(turnId);
                if (options.allowTranscriptWrite !== false) ensureTranscriptUpdated(resolvedConvId, cleanUserPrompt, finalReplyText, Math.round(totalMs / 1000), extracted.thinking || "");
                return donePayload;
            }
        } catch (err) {
            if (abortController.signal.aborted) {
                const cancelError = 'Остановлено пользователем (Stop).';
                const cancelled = { type: 'done', ok: false, turnId, conversationId: resolvedConvId,
                    model: cleanModelId, reply: cancelError, error: cancelError,
                    thinking: '', toolCalls: [], createdDiffs: [], generatedMedia: [] };
                recordActiveTurnSnapshot(turnId, resolvedConvId, { status: 'cancelled', ...cancelled });
                try { onEvent(cancelled); } catch (_) {}
                turnCancellations.delete(turnId);
                return cancelled;
            }
        } finally {
            turnCancellations.delete(turnId);
        }
    }

    turnCancellations.delete(turnId);
    const error = orConfig.apiKey ? 'OpenRouter не вернул ответ выбранной модели. Повторите запрос.' : 'Для выбранной модели требуется ключ OpenRouter в настройках.';
    const failure = { type: 'done', ok: false, turnId, conversationId: resolvedConvId,
        model: cleanModelId, reply: error, error, thinking: '', toolCalls: [], createdDiffs: [], generatedMedia: [] };
    recordActiveTurnSnapshot(turnId, resolvedConvId, { status: 'failed', ...failure });
    onEvent(failure);
    return failure;
}

// ----------------------------------------------------------------------------
// Политики разрешений ИИ Antigravity 2.0 (Смартфон и ПК) — `references/app.md` Раздел 2
// ----------------------------------------------------------------------------
function loadAiPermissionsConfig() {
    try {
        if (fs.existsSync(PERMISSIONS_CONFIG_FILE)) {
            return JSON.parse(fs.readFileSync(PERMISSIONS_CONFIG_FILE, 'utf8'));
        }
    } catch (_) {}
    return {
        preset: 'AUTOPILOT', // AUTOPILOT | STANDARD | SANDBOX_ONLY
        // Глобальные политики Antigravity 2.0 (references/app.md Section 2)
        toolExecutionPolicy: 'always-proceed', // always-proceed | request-review | strict | proceed-in-sandbox
        terminalSandbox: false,
        nonWorkspaceFileAccess: 'allow',       // allow | ask | deny
        internetAccessPolicy: 'allow',         // allow | ask | deny
        artifactReviewMode: 'always-proceed',  // always-proceed | agent-decides | asks-for-review
        runInBackgroundKeepAwake: true,
        // Разрешения на смартфоне (Android)
        phoneFileSystemAccess: true,
        phoneAndroidCliAccess: true,
        phoneVirtualSandboxAccess: true,
        phoneCameraMediaAccess: true,
        phoneContactsCalendarSmsAccess: true,
        phoneInstallPackagesAccess: true,
        // Разрешения на рабочей станции (ПК)
        pcWorkspaceReadWrite: true,
        pcPowerShellExecution: true,
        pcBrowserAndNetworkAccess: true,
        pcPluginsMcpSkillsAccess: true,
        pcFullDiskAccess: true
    };
}

function saveAiPermissionsConfig(patch) {
    const current = loadAiPermissionsConfig();
    const updated = { ...current, ...patch, updatedAt: new Date().toISOString() };
    try {
        fs.writeFileSync(PERMISSIONS_CONFIG_FILE, JSON.stringify(updated, null, 2), 'utf8');
    } catch (_) {}
    return updated;
}

// ----------------------------------------------------------------------------
// Синхронизация представления чатов и библиотеки ответов (телефон ⇄ ПК).
// Названия, закрепления, архив и сохранённые ответы переживают переустановку
// телефона и доступны ПК-клиенту. На запись побеждает более новый `updatedAtMs`;
// удаления передаются надгробиями `deletedIds[id] = deletedAtMs`.
// ----------------------------------------------------------------------------
function loadSyncState() {
    try {
        if (fs.existsSync(SYNC_STATE_FILE)) {
            const parsed = JSON.parse(fs.readFileSync(SYNC_STATE_FILE, 'utf8'));
            return {
                chatPrefs: parsed.chatPrefs && typeof parsed.chatPrefs === 'object' && !Array.isArray(parsed.chatPrefs) ? parsed.chatPrefs : {},
                savedArtifacts: Array.isArray(parsed.savedArtifacts) ? parsed.savedArtifacts : [],
                projectWorkspaces: Array.isArray(parsed.projectWorkspaces) ? parsed.projectWorkspaces : [],
                deletedIds: parsed.deletedIds && typeof parsed.deletedIds === 'object' && !Array.isArray(parsed.deletedIds) ? parsed.deletedIds : {}
            };
        }
    } catch (_) {}
    return { chatPrefs: {}, savedArtifacts: [], projectWorkspaces: [], deletedIds: {} };
}

function saveSyncState(state) {
    const temporary = `${SYNC_STATE_FILE}.tmp`;
    try {
        fs.writeFileSync(temporary, JSON.stringify(state, null, 2), 'utf8');
        fs.renameSync(temporary, SYNC_STATE_FILE);
    } catch (error) {
        try { fs.unlinkSync(temporary); } catch (_) {}
        throw error;
    }
    return state;
}

function mergeSyncState(stored, incoming) {
    const chatPrefs = Object.assign({}, (stored && stored.chatPrefs) || {});
    const savedArtifacts = ((stored && stored.savedArtifacts) || []).slice();
    const projectWorkspaces = ((stored && stored.projectWorkspaces) || []).slice();
    const deletedIds = Object.assign({}, (stored && stored.deletedIds) || {});

    const incomingPrefs = (incoming && incoming.chatPrefs) || {};
    for (const [id, entry] of Object.entries(incomingPrefs)) {
        if (!entry || typeof entry !== 'object') continue;
        const at = Number(entry.updatedAtMs) || 0;
        if (Object.prototype.hasOwnProperty.call(deletedIds, id) && at <= (Number(deletedIds[id]) || 0)) continue; // запись старее удаления
        const existing = chatPrefs[id];
        if (existing && (Number(existing.updatedAtMs) || 0) > at) continue; // хранящаяся новее
        chatPrefs[id] = entry;
        delete deletedIds[id];
    }

    const upsertById = (list, entry) => {
        if (!entry || typeof entry !== 'object') return;
        const id = String(entry.id || '');
        if (!id) return;
        const at = Number(entry.updatedAtMs) || 0;
        if (Object.prototype.hasOwnProperty.call(deletedIds, id) && at <= (Number(deletedIds[id]) || 0)) return;
        const index = list.findIndex(a => String(a && a.id) === id);
        const existing = index >= 0 ? list[index] : null;
        if (existing && (Number(existing.updatedAtMs) || 0) > at) return;
        if (index >= 0) list[index] = entry; else list.push(entry);
        delete deletedIds[id];
    };

    const incomingArtifacts = (incoming && Array.isArray(incoming.savedArtifacts)) ? incoming.savedArtifacts : [];
    for (const entry of incomingArtifacts) upsertById(savedArtifacts, entry);

    const incomingWorkspaces = (incoming && Array.isArray(incoming.projectWorkspaces)) ? incoming.projectWorkspaces : [];
    for (const entry of incomingWorkspaces) upsertById(projectWorkspaces, entry);

    const incomingDeleted = (incoming && incoming.deletedIds) || {};
    for (const [id, deletedAtMs] of Object.entries(incomingDeleted)) {
        const at = Number(deletedAtMs) || 0;
        const prefAt = Number((chatPrefs[id] && chatPrefs[id].updatedAtMs)) || 0;
        const findIn = (list) => {
            const index = list.findIndex(a => String(a && a.id) === id);
            return { index, at: index >= 0 ? (Number(list[index].updatedAtMs) || 0) : 0 };
        };
        const artifact = findIn(savedArtifacts);
        const workspace = findIn(projectWorkspaces);
        if (at < Math.max(prefAt, artifact.at, workspace.at)) continue; // удаление старее записи
        delete chatPrefs[id];
        if (artifact.index >= 0) savedArtifacts.splice(artifact.index, 1);
        if (workspace.index >= 0) projectWorkspaces.splice(workspace.index, 1);
        deletedIds[id] = Math.max(at, Number(deletedIds[id]) || 0);
    }

    savedArtifacts.sort((a, b) => (Number(b && b.updatedAtMs) || 0) - (Number(a && a.updatedAtMs) || 0));
    projectWorkspaces.sort((a, b) => (Number(b && b.updatedAtMs) || 0) - (Number(a && a.updatedAtMs) || 0));
    return { chatPrefs, savedArtifacts, projectWorkspaces, deletedIds };
}

// ----------------------------------------------------------------------------
// Настоящие серверные функции: генерация медиа, сжатие контекста, Google Drive.
// Искусственные заглушки отключены: каждая функция либо работает с реальным
// внешним API, либо честно сообщает о недоступности.
// ----------------------------------------------------------------------------
const GENERATED_MEDIA_DIR = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'generated_media');
const GDRIVE_OAUTH_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'gdrive_oauth.json');
fs.mkdirSync(GENERATED_MEDIA_DIR, { recursive: true });

/** Promise-обёртка HTTPS/HTTP JSON-запроса к внешнему API. */
function httpsJsonRequest(urlStr, { method = 'GET', headers = {}, body = null, timeoutMs = 60000 } = {}) {
    return new Promise((resolve, reject) => {
        let parsed;
        try { parsed = new URL(urlStr); } catch (error) { reject(error); return; }
        const transport = parsed.protocol === 'http:' ? http : https;
        const payload = body == null
            ? null
            : (Buffer.isBuffer(body) ? body : (typeof body === 'string' ? body : JSON.stringify(body)));
        const request = transport.request(parsed, {
            method,
            headers: {
                'Content-Type': 'application/json; charset=utf-8',
                ...(payload ? { 'Content-Length': Buffer.byteLength(payload) } : {}),
                ...headers
            },
            timeout: timeoutMs
        }, (response) => {
            const chunks = [];
            response.on('data', (c) => chunks.push(c));
            response.on('end', () => {
                const raw = Buffer.concat(chunks).toString('utf8');
                let json = null;
                try { json = JSON.parse(raw); } catch (_) {}
                resolve({ status: response.statusCode || 0, raw, json });
            });
        });
        request.on('timeout', () => request.destroy(new Error('Внешний API не ответил вовремя')));
        request.on('error', reject);
        if (payload) request.write(payload);
        request.end();
    });
}

// --- Генерация изображений через реальный OpenRouter API ---------------------
function extractOpenRouterImageBase64(json) {
    const choices = json && Array.isArray(json.choices) ? json.choices : [];
    for (const choice of choices) {
        const message = choice && choice.message;
        const images = message && Array.isArray(message.images) ? message.images : [];
        for (const image of images) {
            const url = image && image.image_url && image.image_url.url;
            if (typeof url === 'string' && url.startsWith('data:')) {
                const comma = url.indexOf(',');
                if (comma > 0) return url.slice(comma + 1);
            }
            if (image && typeof image.b64_json === 'string') return image.b64_json;
        }
        const content = message && Array.isArray(message.content) ? message.content : [];
        for (const part of content) {
            if (part && typeof part.image_url === 'string' && part.image_url.startsWith('data:')) {
                const comma = part.image_url.indexOf(',');
                if (comma > 0) return part.image_url.slice(comma + 1);
            }
            if (part && typeof part.b64_json === 'string') return part.b64_json;
        }
    }
    return null;
}

/** Реальные размеры PNG из IHDR; null, если буфер не PNG. */
function parsePngDimensions(buf) {
    if (!buf || buf.length < 24) return null;
    if (buf[0] !== 0x89 || buf[1] !== 0x50 || buf[2] !== 0x4E || buf[3] !== 0x47) return null;
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

function loadOpenRouterApiKey(payloadApiKey) {
    const fromPayload = String(payloadApiKey || '').trim();
    if (fromPayload) return fromPayload;
    try {
        return String(loadOpenRouterConfig().apiKey || '').trim();
    } catch (_) { return ''; }
}

async function handleOpenRouterImageGeneration(payload) {
    const apiKey = loadOpenRouterApiKey(payload.apiKey);
    if (!apiKey) {
        return { status: 400, body: { ok: false, error: 'Нет API-ключа OpenRouter: добавьте ключ в настройках OpenRouter на телефоне. Генерация выполняется реальным API, заглушки отключены.' } };
    }
    const prompt = String(payload.prompt || '').trim();
    if (!prompt) return { status: 400, body: { ok: false, error: 'Не задан промпт для генерации изображения' } };
    const model = String(payload.model || '').includes('image') ? String(payload.model) : 'google/gemini-2.5-flash-image-preview';
    try {
        const response = await httpsJsonRequest('https://openrouter.ai/api/v1/chat/completions', {
            method: 'POST',
            headers: { Authorization: `Bearer ${apiKey}` },
            body: {
                model,
                modalities: ['image', 'text'],
                messages: [{ role: 'user', content: prompt }]
            },
            timeoutMs: 120000
        });
        if (response.status !== 200 || !response.json) {
            const apiError = response.json && response.json.error && response.json.error.message;
            return { status: 502, body: { ok: false, error: `OpenRouter вернул ошибку${response.status ? ` (${response.status})` : ''}: ${apiError || 'изображение не сгенерировано'}` } };
        }
        const b64 = extractOpenRouterImageBase64(response.json);
        if (!b64) {
            return { status: 502, body: { ok: false, error: 'Модель не вернула изображение в ответе OpenRouter' } };
        }
        const bytes = Buffer.from(b64, 'base64');
        const dims = parsePngDimensions(bytes);
        const fileName = `openrouter_image_${Date.now().toString(36)}.png`;
        const fullPath = path.join(GENERATED_MEDIA_DIR, fileName);
        fs.writeFileSync(fullPath, bytes);
        const width = dims ? dims.width : 0;
        const height = dims ? dims.height : 0;
        return {
            status: 200,
            body: {
                ok: true,
                model,
                media: {
                    fileName,
                    mimeType: 'image/png',
                    mediaUrl: `/download/media/${encodeURIComponent(fileName)}`,
                    sizeBytes: bytes.length,
                    width,
                    height,
                    resolution: width && height ? `${width}x${height}` : ''
                }
            }
        };
    } catch (error) {
        return { status: 502, body: { ok: false, error: `Ошибка обращения к OpenRouter: ${error.message}` } };
    }
}

// --- Сжатие контекста реальной моделью --------------------------------------
function buildCompactionPrompt(messages) {
    const transcript = messages.map((m) => {
        const role = m && m.role === 'USER' ? 'Пользователь' : 'Ассистент';
        return `${role}: ${String((m && m.content) || '').trim()}`;
    }).join('\n\n');
    return 'Сожми историю переписки ниже в компактную сводку для продолжения разговора. ' +
        'Сохрани факты, решения, имена файлов и незавершённые задачи. Не добавляй вопросов. ' +
        'Ответь только сводкой на языке переписки.\n\n--- ИСТОРИЯ ---\n' + transcript;
}

/** Разрешена ли запись транскрипта сессии: временный чат и телефонные режимы не сохраняются. */
function transcriptWriteAllowed(mode, transient) {
    return mode !== 'PHONE_ON_DEVICE' && mode !== 'PHONE_LOCAL_RESOURCES' && !transient;
}

async function handleContextCompaction(payload) {
    const messages = (Array.isArray(payload.messages) ? payload.messages : [])
        .filter((m) => m && typeof m === 'object' && String(m.content || '').trim());
    if (messages.length === 0) {
        return { status: 400, body: { ok: false, error: 'Нет сообщений для сжатия' } };
    }
    const prompt = buildCompactionPrompt(messages);
    const result = await executeAgyTurnStreaming({
        turnId: `compact_${Date.now()}`,
        provider: String(payload.provider || ''),
        rawUserPrompt: prompt,
        cleanUserPrompt: prompt,
        model: String(payload.model || 'gemini-4-argon-high'),
        mode: 'PC_REMOTE',
        conversationId: '',
        workspacePath: DEFAULT_WORKSPACE,
        allowTranscriptWrite: false,
        clientHistory: [],
        phoneContext: '',
        phoneToolObservation: '',
        phoneStepIndex: 0,
        voiceMode: false,
        attachments: []
    });
    const summary = String((result && result.reply) || '').trim();
    if (!result || !result.ok || !summary) {
        return { status: 502, body: { ok: false, error: 'Модель не вернула сводку — история сохранена без изменений' } };
    }
    return {
        status: 200,
        body: {
            ok: true,
            summary,
            model: result.model || '',
            originalMessages: messages.length,
            originalChars: messages.reduce((acc, m) => acc + String(m.content || '').length, 0),
            summaryChars: summary.length
        }
    };
}

// --- Google Drive REST API с реальным OAuth ---------------------------------
function loadGDriveOAuthConfig() {
    try {
        if (fs.existsSync(GDRIVE_OAUTH_FILE)) {
            const parsed = JSON.parse(fs.readFileSync(GDRIVE_OAUTH_FILE, 'utf8'));
            return {
                accessToken: String(parsed.accessToken || ''),
                refreshToken: String(parsed.refreshToken || ''),
                clientId: String(parsed.clientId || ''),
                clientSecret: String(parsed.clientSecret || ''),
                accountEmail: String(parsed.accountEmail || '')
            };
        }
    } catch (_) {}
    return { accessToken: '', refreshToken: '', clientId: '', clientSecret: '', accountEmail: '' };
}

function saveGDriveOAuthConfig(config) {
    try {
        fs.writeFileSync(GDRIVE_OAUTH_FILE, JSON.stringify(config, null, 2), 'utf8');
    } catch (_) {}
    return config;
}

async function refreshGDriveAccessToken(config) {
    if (!config.refreshToken || !config.clientId || !config.clientSecret) return null;
    const response = await httpsJsonRequest('https://oauth2.googleapis.com/token', {
        method: 'POST',
        body: {
            grant_type: 'refresh_token',
            refresh_token: config.refreshToken,
            client_id: config.clientId,
            client_secret: config.clientSecret
        }
    });
    const json = response.json;
    if (!json || !json.access_token) return null;
    return saveGDriveOAuthConfig({ ...config, accessToken: json.access_token });
}

/** Возвращает рабочий access-трокен или null; при возможности обновляет его. */
async function resolveGDriveAccessToken() {
    let config = loadGDriveOAuthConfig();
    if (!config.accessToken && config.refreshToken) {
        config = (await refreshGDriveAccessToken(config)) || config;
    }
    return config.accessToken ? config : null;
}

async function driveApiRequest(pathname, { method = 'GET', headers = {}, body = null, raw = false } = {}) {
    let config = await resolveGDriveAccessToken();
    if (!config) return { status: 401, json: { error: { message: 'Google Drive не подключён: нет OAuth-токена' } }, raw: '' };
    let response = await httpsJsonRequest(`https://www.googleapis.com${pathname}`, {
        method,
        headers: { Authorization: `Bearer ${config.accessToken}`, ...headers },
        body
    });
    if (response.status === 401 && config.refreshToken) {
        const refreshed = await refreshGDriveAccessToken(config);
        if (refreshed) {
            config = refreshed;
            response = await httpsJsonRequest(`https://www.googleapis.com${pathname}`, {
                method,
                headers: { Authorization: `Bearer ${config.accessToken}`, ...headers },
                body
            });
        }
    }
    return response;
}

function mapDriveFileToClient(file, snippet = '') {
    return {
        id: file.id,
        name: file.name,
        folder: '/GoogleDrive',
        engine: 'Drive',
        modelId: '',
        mimeType: file.mimeType || 'application/octet-stream',
        sizeBytes: Number(file.size || 0),
        updatedAt: file.modifiedTime || '',
        webViewLink: file.webViewLink || '',
        downloadUrl: `/download/gdrive_id/${encodeURIComponent(file.id)}`,
        localUri: '',
        previewSnippet: snippet
    };
}

async function listGoogleDriveRealFiles(query = '') {
    const q = String(query || '').trim();
    const driveQuery = q
        ? `trashed = false and name contains '${q.replace(/'/g, "\\'")}'`
        : 'trashed = false';
    const response = await driveApiRequest(
        `/drive/v3/files?q=${encodeURIComponent(driveQuery)}&fields=files(id,name,mimeType,size,modifiedTime,webViewLink)&pageSize=100&orderBy=modifiedTime desc`
    );
    if (response.status !== 200 || !response.json) {
        return { ok: false, error: (response.json && response.json.error && response.json.error.message) || `Ошибка Google Drive API (${response.status})` };
    }
    return { ok: true, files: (response.json.files || []).map((f) => mapDriveFileToClient(f)) };
}

async function readGoogleDriveRealFile(fileId) {
    const meta = await driveApiRequest(`/drive/v3/files/${encodeURIComponent(fileId)}?fields=id,name,mimeType,size,modifiedTime,webViewLink`);
    if (meta.status !== 200 || !meta.json) {
        return { ok: false, error: (meta.json && meta.json.error && meta.json.error.message) || `Файл не найден (${meta.status})` };
    }
    const content = await driveApiRequest(`/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`, { raw: true });
    if (content.status !== 200) {
        return { ok: false, error: `Не удалось прочитать файл (${content.status})` };
    }
    return {
        ok: true,
        id: meta.json.id,
        name: meta.json.name,
        folder: '/GoogleDrive',
        engine: 'Drive',
        modelId: '',
        mimeType: meta.json.mimeType || 'text/plain',
        content: content.raw,
        sizeBytes: Number(meta.json.size || Buffer.byteLength(content.raw, 'utf8')),
        updatedAt: meta.json.modifiedTime || '',
        webViewLink: meta.json.webViewLink || '',
        downloadUrl: `/download/gdrive_id/${encodeURIComponent(meta.json.id)}`
    };
}

async function createOrUpdateGoogleDriveRealFile(payload) {
    const name = String(payload.name || payload.fileName || `antigravity_doc_${Date.now()}.md`).trim();
    const content = String(payload.content == null ? '' : payload.content);
    const mimeType = String(payload.mimeType || (name.endsWith('.json') ? 'application/json' : 'text/markdown'));
    const boundary = `antigravity_${Date().toString(36)}`;
    const multipart = Buffer.concat([
        Buffer.from(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify({ name })}\r\n--${boundary}\r\nContent-Type: ${mimeType}\r\n\r\n`),
        Buffer.from(content, 'utf8'),
        Buffer.from(`\r\n--${boundary}--\r\n`)
    ]);
    let response;
    if (payload.id) {
        response = await driveApiRequest(`/upload/drive/v3/files/${encodeURIComponent(payload.id)}?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime,webViewLink`, {
            method: 'PATCH',
            headers: { 'Content-Type': `multipart/related; boundary=${boundary}` },
            body: multipart
        });
    } else {
        response = await driveApiRequest('/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime,webViewLink', {
            method: 'POST',
            headers: { 'Content-Type': `multipart/related; boundary=${boundary}` },
            body: multipart
        });
    }
    if ((response.status !== 200 && response.status !== 201) || !response.json) {
        return { ok: false, error: (response.json && response.json.error && response.json.error.message) || `Ошибка записи в Google Drive (${response.status})` };
    }
    return {
        ok: true,
        action: payload.id ? 'updated' : 'created',
        file: mapDriveFileToClient(response.json)
    };
}

async function deleteGoogleDriveRealFile(fileId) {
    const response = await driveApiRequest(`/drive/v3/files/${encodeURIComponent(fileId)}`, { method: 'DELETE' });
    if (response.status !== 204 && response.status !== 200) {
        return { ok: false, error: (response.json && response.json.error && response.json.error.message) || `Ошибка удаления (${response.status})` };
    }
    return { ok: true, deleted: fileId };
}

async function googleDriveStatus() {
    const config = await resolveGDriveAccessToken();
    if (!config) {
        return { ok: true, connected: false, accountEmail: '', folderPath: '/AntigravityCloud', mirrorDir: GDRIVE_WORKSPACE_DIR, engines: ['Drive'] };
    }
    const about = await driveApiRequest('/drive/v3/about?fields=user(emailAddress,displayName),storageQuota');
    const user = about.json && about.json.user;
    const quota = about.json && about.json.storageQuota;
    return {
        ok: true,
        connected: about.status === 200,
        accountEmail: (user && user.emailAddress) || config.accountEmail || '',
        folderPath: '/GoogleDrive',
        mirrorDir: GDRIVE_WORKSPACE_DIR,
        usedBytes: Number(quota && quota.usage || 0),
        quotaBytes: Number(quota && quota.limit || 0),
        engines: ['Drive']
    };
}

// ----------------------------------------------------------------------------
// Многоагентный рой (Multi-Agent Swarm) с пометкой 👑 ЛИДЕР
// ----------------------------------------------------------------------------
const { SubagentRuntime } = require('./lib/subagent-runtime');
const { AgentWorkspace } = require('./lib/agent-workspace');
const { createSubagentRunner } = require('./lib/subagent-cli');
const agentWorkspaces = new AgentWorkspace(DEFAULT_WORKSPACE, path.join(SCRATCH_DIR, 'agent-workspaces'));
const subagentRuntime = new SubagentRuntime({
    // Этап 4: дочерние задачи получают только нужный поднабор прав.
    // По умолчанию анализ/код — без MCP-инструментов (пустой поднабор —
    // явный запрет); аннотации MCP прав не дают.
    run: createSubagentRunner({
        executable: AGY_BIN,
        repository: DEFAULT_WORKSPACE,
        workspaces: agentWorkspaces,
        permissions: { allowedServers: [], allowedRefs: [] }
    }),
    onChange: state => {
        const temporary = SWARM_STATE_FILE + '.tmp';
        try { fs.writeFileSync(temporary, JSON.stringify(state), 'utf8'); fs.renameSync(temporary, SWARM_STATE_FILE); }
        catch (error) { console.error('[subagents] Cannot persist task state:', error.message); }
    }
});

function loadMultiAgentSwarmState(requestId = '') {
    if (requestId && subagentRuntime.requestIds.has(requestId)) return subagentRuntime.snapshotFor(requestId);
    if (!requestId && subagentRuntime.jobs.length) return subagentRuntime.snapshot();
    try {
        const previous = JSON.parse(fs.readFileSync(SWARM_STATE_FILE, 'utf8'));
        if (previous.strategy === 'PARALLEL_ANALYSIS' && (!requestId || previous.batchId === requestId)) return { ...previous, swarmActive: false,
            agents: (previous.agents || []).map(agent => ['queued', 'running'].includes(agent.status)
                ? { ...agent, settling: false, status: 'failed', logs: ['Мост перезапущен. Запустите анализ заново.'] } : { ...agent, settling: false }) };
    } catch (_) {}
    return { swarmActive: false, strategy: 'PARALLEL_ANALYSIS', agents: [] };
}

function updateMultiAgentSwarmState(payload) {
    const action = String(payload.action || '').toLowerCase();
    if (action === 'cancel_task') return subagentRuntime.cancel(payload.requestId || '');
    if (!['dispatch_task', 'dispatch_code_task'].includes(action)) throw new Error('Для субагентов анализа доступны запуск и отмена задачи');
    if (!fs.existsSync(AGY_BIN)) throw new Error('CLI Antigravity (agy) не доступен на ПК: невозможно запустить субагентов');
    const model = String(payload.model || 'gemini-4-argon-high');
    if (!cachedAgyModels.some(entry => entry.id === model)) throw new Error('Выбранная модель CLI недоступна');
    return subagentRuntime.dispatch({ task: payload.task, model, requestId: payload.requestId, mode: action === 'dispatch_code_task' ? 'code' : 'analysis' });
}

// ----------------------------------------------------------------------------
// Инспектор контекстного окна и самостоятельный сброс (/api/context/*)
// ----------------------------------------------------------------------------
function computeSessionContextWindowStatus(conversationId = '', modelId = 'gemini-4-argon-high') {
    const sysPromptText = loadAgentSystemPrompt();
    const skillsList = listSkills().filter(s => s.enabled);
    const systemPromptChars = sysPromptText.length + skillsList.reduce((acc, s) => acc + (s.name.length + s.description.length + 40), 0);
    const systemPromptTokens = Math.max(420, Math.round(systemPromptChars / 3.6));

    // Определяем максимальный размер контекстного окна выбранной модели
    let maxContextTokens = 1000000; // Gemini 4 Argon = 1M токенов
    const cleanModel = String(modelId || '').replace(/^openrouter\//i, '');
    const foundOr = liveOpenRouterModelsMemoryCache.find(m => m.id === cleanModel);
    if (foundOr && foundOr.contextLength > 0) {
        maxContextTokens = foundOr.contextLength;
    } else if (cleanModel.includes('3.8-flash')) {
        maxContextTokens = 1000000;
    } else if (cleanModel.includes('128k')) {
        maxContextTokens = 128000;
    }

    const wasReset = conversationId && resetConversationsSet.has(conversationId);
    if (!conversationId || wasReset) {
        return {
            ok: true,
            conversationId,
            model: modelId,
            wasReset: Boolean(wasReset),
            maxContextTokens,
            usedContextTokens: systemPromptTokens,
            systemPromptTokens,
            historyTokens: 0,
            toolOutputTokens: 0,
            messagesCount: 0,
            usagePercent: Number(((systemPromptTokens / maxContextTokens) * 100).toFixed(2))
        };
    }

    const msgs = loadSessionMessages(conversationId);
    let historyChars = 0;
    let toolChars = 0;
    for (const m of msgs) {
        historyChars += String(m.content || '').length + String(m.thinking || '').length;
        if (Array.isArray(m.toolCalls)) {
            for (const tc of m.toolCalls) {
                toolChars += String(tc.summary || '').length + String(tc.output || '').length + 40;
            }
        }
        if (Array.isArray(m.createdDiffs)) {
            for (const d of m.createdDiffs) {
                toolChars += String(d.diffText || '').length + 30;
            }
        }
    }
    const historyTokens = Math.round(historyChars / 3.6);
    const toolOutputTokens = Math.round(toolChars / 3.6);
    const usedContextTokens = systemPromptTokens + historyTokens + toolOutputTokens;

    return {
        ok: true,
        conversationId,
        model: modelId,
        wasReset: false,
        maxContextTokens,
        usedContextTokens,
        systemPromptTokens,
        historyTokens,
        toolOutputTokens,
        messagesCount: msgs.length,
        usagePercent: Number(((usedContextTokens / maxContextTokens) * 100).toFixed(2))
    };
}

function resetSessionContextWindow(conversationId = '', clearTranscript = false) {
    if (conversationId) {
        resetConversationsSet.add(conversationId);
        // Останавливаем и удаляем закешированные воркеры этой сессии, чтобы следующий ход шёл со 100% чистым контекстом
        for (const [k, w] of sessionWorkers.entries()) {
            if (k.startsWith(`${conversationId}::`)) {
                killWorkerProcess(w);
                sessionWorkers.delete(k);
            }
        }
        if (phoneLoopWorkers.has(conversationId)) {
            killWorkerProcess(phoneLoopWorkers.get(conversationId));
            phoneLoopWorkers.delete(conversationId);
        }
        if (clearTranscript) {
            deleteTemporaryCliConversation(conversationId, '');
        }
    }
    const status = computeSessionContextWindowStatus(conversationId);
    return {
        ...status,
        wasReset: true,
        historyTokens: 0,
        toolOutputTokens: 0,
        usedContextTokens: status.systemPromptTokens,
        message: 'Контекстное окно модели успешно сброшено (0 токенов истории).'
    };
}

const server = http.createServer(async (req, res) => {
    if (req.method === 'OPTIONS') {
        return sendJson(res, 200, { ok: true });
    }

    const urlObj = new URL(req.url, `http://localhost:${PORT}`);

    if (req.method === 'GET' && (urlObj.pathname === '/web' || urlObj.pathname === '/')) {
        const sessionId = urlObj.searchParams.get('session') || '';
        const html = renderLiveWebConsoleHtml(sessionId);
        res.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Access-Control-Allow-Origin': '*'
        });
        return res.end(html);
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/tts') {
        const text = urlObj.searchParams.get('text') || '';
        const voice = urlObj.searchParams.get('voice') || 'Aoede';
        const rate = urlObj.searchParams.get('rate') || '1.05';
        return streamEdgeNeuralTtsAudio(text, voice, rate, res);
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/voices') {
        return sendJson(res, 200, {
            ok: true,
            voices: [
                { id: 'Aoede', name: 'Aoede (Светлана Neural)', engineVoice: 'ru-RU-SvetlanaNeural', gender: 'FEMALE' },
                { id: 'Kore', name: 'Kore (Спокойный женский)', engineVoice: 'ru-RU-SvetlanaNeural', gender: 'FEMALE' },
                { id: 'Puck', name: 'Puck (Дмитрий Neural)', engineVoice: 'ru-RU-DmitryNeural', gender: 'MALE' },
                { id: 'Charon', name: 'Charon (Глубокий мужской)', engineVoice: 'ru-RU-DmitryNeural', gender: 'MALE' },
                { id: 'Fenrir', name: 'Fenrir (Динамичный мужской)', engineVoice: 'ru-RU-DmitryNeural', gender: 'MALE' }
            ]
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/models') {
        return sendJson(res, 200, {
            ok: true,
            models: cachedAgyModels
        });
    }

    // ROUND 5 GET endpoints:
    if (req.method === 'GET' && urlObj.pathname === '/api/chat/turn-status') {
        const turnId = urlObj.searchParams.get('turnId') || '';
        const convId = urlObj.searchParams.get('conversationId') || '';
        const snap = (turnId && activeTurnsById.get(turnId)) || (convId && activeTurnsByConv.get(convId));
        if (!snap) {
            return sendJson(res, 200, { ok: false, status: 'not_found' });
        }
        return sendJson(res, 200, { ok: true, found: true, ...snap });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/chat/active') {
        const running = [];
        for (const v of activeTurnsById.values()) {
            if (v.status === 'running' || (Date.now() - v.updatedAtMs) < 120000) {
                running.push(v);
            }
        }
        return sendJson(res, 200, { ok: true, turns: running });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/openrouter/config') {
        const cfg = loadOpenRouterConfig();
        return sendJson(res, 200, {
            ok: true,
            hasKey: Boolean(cfg.apiKey && cfg.apiKey.length > 8),
            maskedKey: cfg.apiKey ? `${cfg.apiKey.slice(0, 9)}...${cfg.apiKey.slice(-4)}` : '',
            enabled: cfg.enabled !== false,
            selectedModel: cfg.selectedModel || '',
            autoFreeFallback: cfg.autoFreeFallback !== false
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/openrouter/models') {
        const forceRefresh = urlObj.searchParams.get('refresh') === '1' || urlObj.searchParams.get('force') === 'true';
        const category = (urlObj.searchParams.get('category') || 'ALL').toUpperCase();
        const search = (urlObj.searchParams.get('q') || '').toLowerCase().trim();
        const result = await fetchLiveOpenRouterModels(forceRefresh);
        let filtered = result.models || [];
        if (category && category !== 'ALL') {
            filtered = filtered.filter(m => Array.isArray(m.categories) && m.categories.includes(category));
        }
        if (search) {
            filtered = filtered.filter(m =>
                m.id.toLowerCase().includes(search) ||
                m.name.toLowerCase().includes(search) ||
                (m.description && m.description.toLowerCase().includes(search))
            );
        }
        return sendJson(res, 200, {
            ok: true,
            fromCache: Boolean(result.fromCache),
            cachedAt: result.cachedAt || result.syncedAt,
            source: result.source,
            syncedAt: result.syncedAt,
            total: result.totalCount || filtered.length,
            totalCached: result.totalCached || result.totalCount || filtered.length,
            totalSyncedCount: result.totalCount || filtered.length,
            filteredCount: filtered.length,
            models: filtered.slice(0, 1000)
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/status') {
        // Настоящий Google Drive при наличии OAuth-токена; иначе — локальное зеркало
        // с честным connected: false (имитация облачного аккаунта удалена).
        const oauth = loadGDriveOAuthConfig();
        if (oauth.accessToken || oauth.refreshToken) {
            const status = await googleDriveStatus();
            return sendJson(res, 200, status);
        }
        const files = listGoogleDriveFiles();
        const usedBytes = files.reduce((acc, f) => acc + (f.sizeBytes || 0), 0);
        return sendJson(res, 200, {
            ok: true,
            connected: false,
            accountEmail: '',
            folderPath: '/AntigravityCloud',
            mirrorDir: GDRIVE_WORKSPACE_DIR,
            filesCount: files.length,
            usedBytes,
            quotaBytes: 0,
            engines: ['Gemini', 'OpenRouter']
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/files') {
        const q = urlObj.searchParams.get('q') || '';
        const oauth = loadGDriveOAuthConfig();
        if (oauth.accessToken || oauth.refreshToken) {
            const real = await listGoogleDriveRealFiles(q);
            if (real.ok) {
                return sendJson(res, 200, {
                    ok: true,
                    connected: true,
                    folderPath: '/GoogleDrive',
                    total: real.files.length,
                    files: real.files
                });
            }
            return sendJson(res, 502, { ok: false, connected: false, error: real.error, total: 0, files: [] });
        }
        const files = listGoogleDriveFiles(q);
        return sendJson(res, 200, {
            ok: true,
            connected: false,
            folderPath: '/AntigravityCloud',
            total: files.length,
            files
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/read') {
        const id = urlObj.searchParams.get('id') || '';
        const oauth = loadGDriveOAuthConfig();
        if (id && (oauth.accessToken || oauth.refreshToken)) {
            const real = await readGoogleDriveRealFile(id);
            return sendJson(res, real.ok ? 200 : (real.error && real.error.includes('не подключён') ? 401 : 404), real);
        }
        const name = urlObj.searchParams.get('name') || '';
        const resObj = readGoogleDriveFile(name);
        return sendJson(res, resObj.ok ? 200 : 404, resObj);
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && urlObj.pathname.startsWith('/download/gdrive_id/')) {
        // Прокси скачивания реального файла Drive по ID (токен остаётся на ПК).
        const fileId = decodeURIComponent(urlObj.pathname.slice('/download/gdrive_id/'.length));
        const content = await driveApiRequest(`/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`, { raw: true });
        if (content.status !== 200) {
            return sendJson(res, 404, { ok: false, error: 'Google Drive file not found' });
        }
        res.writeHead(200, {
            'Content-Type': 'text/plain; charset=utf-8',
            'Access-Control-Allow-Origin': '*'
        });
        if (req.method === 'HEAD') res.end();
        else res.end(content.raw);
        return;
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && urlObj.pathname.startsWith('/download/media/')) {
        const rawFile = path.basename(decodeURIComponent(urlObj.pathname.slice('/download/media/'.length)));
        const fullPath = path.join(GENERATED_MEDIA_DIR, rawFile);
        if (fs.existsSync(fullPath)) {
            const stat = fs.statSync(fullPath);
            res.writeHead(200, {
                'Content-Type': rawFile.endsWith('.png') ? 'image/png' : 'application/octet-stream',
                'Content-Disposition': `inline; filename="${rawFile}"`,
                'Content-Length': stat.size,
                'Access-Control-Allow-Origin': '*'
            });
            if (req.method === 'HEAD') res.end();
            else fs.createReadStream(fullPath).pipe(res);
            return;
        }
        return sendJson(res, 404, { ok: false, error: 'Media file not found' });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/oauth/start') {
        // Начало реального OAuth-потока Google: пользователь открывает ссылку в браузере.
        const config = loadGDriveOAuthConfig();
        if (!config.clientId) {
            return sendJson(res, 400, { ok: false, error: 'Сначала сохраните Client ID/Secret через POST /api/gdrive/config (Google Cloud Console → OAuth client).' });
        }
        const redirectUri = `http://${req.headers.host || '127.0.0.1:8787'}/api/gdrive/oauth/callback`;
        const authUrl = 'https://accounts.google.com/o/oauth2/v2/auth?' + new URLSearchParams({
            client_id: config.clientId,
            redirect_uri: redirectUri,
            response_type: 'code',
            scope: 'https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email',
            access_type: 'offline',
            prompt: 'consent'
        }).toString();
        return sendJson(res, 200, { ok: true, authUrl, redirectUri });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/gdrive/oauth/callback') {
        const code = urlObj.searchParams.get('code') || '';
        const config = loadGDriveOAuthConfig();
        if (!code || !config.clientId || !config.clientSecret) {
            res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('OAuth: нет кода авторизации или не настроен Client ID/Secret.');
            return;
        }
        const redirectUri = `http://${req.headers.host || '127.0.0.1:8787'}/api/gdrive/oauth/callback`;
        const tokenResponse = await httpsJsonRequest('https://oauth2.googleapis.com/token', {
            method: 'POST',
            body: {
                code,
                client_id: config.clientId,
                client_secret: config.clientSecret,
                redirect_uri: redirectUri,
                grant_type: 'authorization_code'
            }
        });
        const tokens = tokenResponse.json;
        if (!tokens || !tokens.access_token) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('OAuth: не удалось обменять код на токен. Проверьте Client ID/Secret и redirect URI.');
            return;
        }
        saveGDriveOAuthConfig({
            ...config,
            accessToken: tokens.access_token,
            refreshToken: tokens.refresh_token || config.refreshToken
        });
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end('<html><body><h3>Google Drive подключён к Antigravity</h3><p>Можно закрыть вкладку и вернуться в приложение.</p></body></html>');
        return;
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && urlObj.pathname.startsWith('/download/gdrive/')) {
        const rawFile = path.basename(decodeURIComponent(urlObj.pathname.slice('/download/gdrive/'.length)));
        const fullPath = path.join(GDRIVE_WORKSPACE_DIR, rawFile);
        if (fs.existsSync(fullPath)) {
            const stat = fs.statSync(fullPath);
            res.writeHead(200, {
                'Content-Type': rawFile.endsWith('.json') ? 'application/json; charset=utf-8' : 'text/plain; charset=utf-8',
                'Content-Disposition': `inline; filename="${rawFile}"`,
                'Content-Length': stat.size,
                'Access-Control-Allow-Origin': '*'
            });
            if (req.method === 'HEAD') res.end();
            else fs.createReadStream(fullPath).pipe(res);
            return;
        }
        return sendJson(res, 404, { ok: false, error: 'Google Drive file not found' });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/permissions') {
        return sendJson(res, 200, {
            ok: true,
            permissions: loadAiPermissionsConfig()
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/sync/state') {
        return sendJson(res, 200, Object.assign({ ok: true }, loadSyncState()));
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/agents/swarm') {
        return sendJson(res, 200, {
            ok: true,
            swarm: loadMultiAgentSwarmState(urlObj.searchParams.get('requestId') || '')
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/context/status') {
        const cid = urlObj.searchParams.get('conversationId') || '';
        const model = urlObj.searchParams.get('model') || 'gemini-4-argon-high';
        return sendJson(res, 200, computeSessionContextWindowStatus(cid, model));
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && urlObj.pathname.startsWith('/download/media/')) {
        const rawFile = path.basename(decodeURIComponent(urlObj.pathname.slice('/download/media/'.length)));
        const mediaPath = path.join(MEDIA_DIR, rawFile);
        if (fs.existsSync(mediaPath)) {
            const stat = fs.statSync(mediaPath);
            const totalSize = stat.size;
            const isMp4 = rawFile.toLowerCase().endsWith('.mp4');
            const mime = isMp4 ? 'video/mp4' : (rawFile.toLowerCase().endsWith('.png') ? 'image/png' : 'application/octet-stream');
            const forceDownload = urlObj.searchParams.get('download') === '1';
            const disp = forceDownload ? `attachment; filename="${rawFile}"` : `inline; filename="${rawFile}"`;
            const range = req.headers.range;

            if (range) {
                const parts = range.replace(/bytes=/, '').split('-');
                const start = parseInt(parts[0], 10);
                const end = parts[1] ? parseInt(parts[1], 10) : totalSize - 1;
                if (start >= 0 && start < totalSize && end >= start && end < totalSize) {
                    const chunkSize = (end - start) + 1;
                    res.writeHead(206, {
                        'Content-Range': `bytes ${start}-${end}/${totalSize}`,
                        'Accept-Ranges': 'bytes',
                        'Content-Length': chunkSize,
                        'Content-Type': mime,
                        'Content-Disposition': disp,
                        'Access-Control-Allow-Origin': '*'
                    });
                    if (req.method === 'HEAD') res.end();
                    else fs.createReadStream(mediaPath, { start, end }).pipe(res);
                    return;
                }
            }

            res.writeHead(200, {
                'Content-Type': mime,
                'Content-Disposition': disp,
                'Accept-Ranges': 'bytes',
                'Content-Length': totalSize,
                'Access-Control-Allow-Origin': '*'
            });
            if (req.method === 'HEAD') res.end();
            else fs.createReadStream(mediaPath).pipe(res);
            return;
        }
        return sendJson(res, 404, { ok: false, error: 'Media file not found' });
    }

    if (req.method === 'GET' && (urlObj.pathname === '/api/status' || urlObj.pathname === '/api/health')) {
        const activeUrls = getActiveTunnelUrlList();
        const primaryUrl = activeUrls[0] || lastPublishedTunnelPayload.split(/\s+/)[0] || '';
        return sendJson(res, 200, {
            ok: true,
            status: 'online',
            instanceName: INSTANCE_NAME,
            hostname: os.hostname(),
            platform: os.platform(),
            workspace: DEFAULT_WORKSPACE,
            sessionsCount: listExistingPcSessions().length,
            ttsEngine: 'MsEdgeTTS (ru-RU-SvetlanaNeural / ru-RU-DmitryNeural)',
            modelsCount: cachedAgyModels.length,
            openRouterModelsCount: liveOpenRouterModelsMemoryCache.length,
            publicUrl: primaryUrl,
            publicTunnelUrl: primaryUrl,
            publicTunnelUrls: activeUrls
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/sessions') {
        const sessions = listExistingPcSessions();
        if (sessions.length > 0) {
            setImmediate(() => prewarmPcSessionWorker(sessions[0].id, 'gemini-4-argon-high', DEFAULT_WORKSPACE));
        }
        return sendJson(res, 200, {
            ok: true,
            instanceName: INSTANCE_NAME,
            hostname: os.hostname(),
            sessions
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/session') {
        const id = urlObj.searchParams.get('id') || '';
        if (id && id.length >= 32) {
            setImmediate(() => prewarmPcSessionWorker(id, 'gemini-4-argon-high', DEFAULT_WORKSPACE));
        }
        return sendJson(res, 200, {
            ok: true,
            id: id,
            messages: loadSessionMessages(id)
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/plugins') {
        const plugins = await listPlugins();
        // Этап 5: у каждого плагина — проверенный путь исполнения компонента
        // либо конкретный блокер (адаптеры компонентов по реальным файлам).
        for (const p of plugins) {
            try {
                const pluginJson = p.path ? path.join(p.path, 'plugin.json') : null;
                if (pluginJson && fs.existsSync(pluginJson)) {
                    const a = componentAdapters.analyzePluginAdapters(p.path, { name: p.id, enabled: p.enabled });
                    p.composition = {
                        supportStatus: a.support.supportStatus,
                        fullSupportAllowed: a.support.fullSupportAllowed,
                        records: a.records.map(r => ({
                            componentType: r.componentType,
                            strategy: r.strategy,
                            status: r.status,
                            blocker: r.blocker,
                            verification: r.verification
                        })),
                        license: a.licenseCheck,
                        blockers: a.support.blockers
                    };
                } else {
                    p.composition = {
                        supportStatus: 'unknown',
                        fullSupportAllowed: false,
                        records: [],
                        license: null,
                        blockers: [{ componentType: 'plugin', reason: 'plugin.json не найден — состав не проверен' }]
                    };
                }
            } catch (err) {
                p.composition = {
                    supportStatus: 'unknown',
                    fullSupportAllowed: false,
                    records: [],
                    license: null,
                    blockers: [{ componentType: 'plugin', reason: `состав не проверен: ${err.message}` }]
                };
            }
        }
        return sendJson(res, 200, {
            ok: true,
            plugins
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/skills') {
        return sendJson(res, 200, {
            ok: true,
            skills: listSkills()
        });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/mcp') {
        const mcpServers = await listMcpServers();
        return sendJson(res, 200, {
            ok: true,
            mcpServers
        });
    }

    // Этап 2 (EXTENSIONS_INTEGRATION_PLAN.md): общий реестр расширений.
    // Позиции инвентаря Этапа 1 подключаются по запросу ?inventory=1.
    if (req.method === 'GET' && urlObj.pathname === '/api/extensions/registry') {
        let inventory = null;
        if (urlObj.searchParams.get('inventory') === '1') {
            const invPath = path.join(DEFAULT_WORKSPACE, 'extensions-inventory.json');
            if (fs.existsSync(invPath)) {
                try { inventory = JSON.parse(fs.readFileSync(invPath, 'utf8')); } catch (_) {}
            }
        }
        const registryData = extensionRegistry.buildRegistry({
            registryIndexPath: REGISTRY_INDEX_PATH,
            skillRoots: [
                { dir: path.join(DEFAULT_WORKSPACE, '.agents', 'skills'), source: 'workspace', sourceProduct: 'Antigravity local' },
                { dir: SKILLS_DIR, source: 'global', sourceProduct: 'Antigravity local' },
                { dir: path.join(IDE_BASE_DIR, 'builtin', 'skills'), source: 'builtin', sourceProduct: 'Antigravity local' }
            ],
            pluginRoots: [PLUGINS_DIR, path.join(UPLOADS_DIR, 'plugin-staging')],
            configPath: path.join(GEMINI_ROOT_DIR, 'config', 'config.json'),
            mcpConfigPath: MCP_CONFIG_FILE,
            settingsPath: GEMINI_SETTINGS_FILE,
            cliPlugins: await listPlugins(),
            cliMcp: await listMcpServers(),
            inventory
        });
        return sendJson(res, 200, { ok: true, registry: registryData });
    }

    // Этап 5: матрица адаптеров компонентов — у каждого компонента инвентаря
    // стратегия (preserve/convert/own-executor) и проверенный путь либо
    // конкретный блокер; право переиспользования/распространения — отдельно.
    if (req.method === 'GET' && urlObj.pathname === '/api/extensions/adapters') {
        let inventory = null;
        const invPath = path.join(DEFAULT_WORKSPACE, 'extensions-inventory.json');
        if (fs.existsSync(invPath)) {
            try { inventory = JSON.parse(fs.readFileSync(invPath, 'utf8')); } catch (_) {}
        }
        const pluginAnalyses = [];
        for (const root of [PLUGINS_DIR, path.join(UPLOADS_DIR, 'plugin-staging')]) {
            let dirs = [];
            try { dirs = fs.readdirSync(root); } catch (_) { continue; }
            for (const dirName of dirs) {
                const pluginDir = path.join(root, dirName);
                if (fs.existsSync(path.join(pluginDir, 'plugin.json'))) {
                    try { pluginAnalyses.push(componentAdapters.analyzePluginAdapters(pluginDir, { name: dirName })); } catch (_) {}
                }
            }
        }
        const matrix = componentAdapters.buildAdapterMatrix(inventory, { pluginAnalyses });
        return sendJson(res, 200, { ok: true, matrix });
    }

    if (req.method === 'GET' && urlObj.pathname === '/api/system-prompt') {
        return sendJson(res, 200, {
            ok: true,
            path: AGENT_SYSTEM_PROMPT_FILE,
            systemPrompt: loadAgentSystemPrompt()
        });
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && urlObj.pathname === '/download/apk') {
        const apkPath = path.join(DEFAULT_WORKSPACE, 'Antigravity-Android-2.0.0.apk');
        if (fs.existsSync(apkPath)) {
            const stat = fs.statSync(apkPath);
            const totalSize = stat.size;
            const range = req.headers.range;

            if (range) {
                const parts = range.replace(/bytes=/, '').split('-');
                const start = parseInt(parts[0], 10);
                const end = parts[1] ? parseInt(parts[1], 10) : totalSize - 1;
                if (start >= 0 && start < totalSize && end >= start && end < totalSize) {
                    const chunkSize = (end - start) + 1;
                    res.writeHead(206, {
                        'Content-Range': `bytes ${start}-${end}/${totalSize}`,
                        'Accept-Ranges': 'bytes',
                        'Content-Length': chunkSize,
                        'Content-Type': 'application/vnd.android.package-archive',
                        'Content-Disposition': 'attachment; filename="Antigravity-Android-2.0.0.apk"',
                        'Cache-Control': 'no-cache'
                    });
                    if (req.method === 'HEAD') {
                        res.end();
                    } else {
                        fs.createReadStream(apkPath, { start, end }).pipe(res);
                    }
                    return;
                }
            }

            res.writeHead(200, {
                'Content-Type': 'application/vnd.android.package-archive',
                'Content-Disposition': 'attachment; filename="Antigravity-Android-2.0.0.apk"',
                'Accept-Ranges': 'bytes',
                'Content-Length': totalSize,
                'Cache-Control': 'no-cache'
            });
            if (req.method === 'HEAD') {
                res.end();
            } else {
                fs.createReadStream(apkPath).pipe(res);
            }
            return;
        }
        return sendJson(res, 404, { ok: false, error: 'APK not found' });
    }

    if (req.method === 'POST') {
        let payload;
        try { payload = await readJsonBody(req); }
        catch (error) { return sendJson(res, error.statusCode || 400, { ok: false, error: error.message }); }
        try {
            if (urlObj.pathname === '/api/chat/cancel') {
                const cancel = turnCancellations.get(String(payload.turnId || ''));
                if (cancel) cancel();
                return sendJson(res, 200, { ok: true, cancelled: Boolean(cancel) });
            }
            if (urlObj.pathname === '/api/tts') {
                const text = payload.text || '';
                const voice = payload.voice || 'Aoede';
                const rate = payload.rate || '1.05';
                return streamEdgeNeuralTtsAudio(text, voice, rate, res);
            }

            if (urlObj.pathname === '/api/plugins') {
                const result = await managePlugin(payload);
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/skills') {
                const result = manageSkill(payload);
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/mcp') {
                const result = await manageMcpServer(payload);
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            // Этап 2: просмотр состава до установки, импорт, состояние, удаление
            // и экспорт собственного набора через общий реестр.
            if (urlObj.pathname === '/api/extensions/inspect') {
                const sourcePath = String(payload.path || payload.sourcePath || '').trim();
                if (!sourcePath) {
                    return sendJson(res, 400, { ok: false, error: 'Не указан путь к пакету (каталог или ZIP)' });
                }
                const preview = extensionRegistry.inspectPackageSource({ sourcePath, kind: payload.kind || 'auto' });
                return sendJson(res, preview.ok ? 200 : 400, preview);
            }

            if (urlObj.pathname === '/api/extensions/import') {
                const sourcePath = String(payload.path || payload.sourcePath || '').trim();
                if (!sourcePath) {
                    return sendJson(res, 400, { ok: false, error: 'Не указан путь к пакету' });
                }
                const result = extensionRegistry.importPackage({
                    sourcePath,
                    kind: payload.kind || 'auto',
                    targetRoot: payload.targetRoot ? String(payload.targetRoot) : PLUGINS_DIR,
                    registryIndexPath: REGISTRY_INDEX_PATH,
                    onCollision: ['refuse', 'replace', 'rename'].includes(payload.onCollision) ? payload.onCollision : 'refuse',
                    sourceProduct: payload.sourceProduct ? String(payload.sourceProduct) : 'Antigravity local',
                    publisher: payload.publisher ? String(payload.publisher) : 'локальный импорт',
                    sourceUrl: payload.sourceUrl ? String(payload.sourceUrl) : null
                });
                invalidateAllIdleWorkers();
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/extensions/state') {
                const packageId = String(payload.id || payload.packageId || '').trim();
                const enabled = payload.enabled !== false;
                const result = extensionRegistry.setPackageEnabled({
                    packageId,
                    enabled,
                    registryIndexPath: REGISTRY_INDEX_PATH,
                    configPath: path.join(GEMINI_ROOT_DIR, 'config', 'config.json'),
                    mcpConfigPath: MCP_CONFIG_FILE,
                    settingsPath: GEMINI_SETTINGS_FILE,
                    cli: true
                });
                // Единый источник состояния: действие реестра продублировано в
                // agy CLI, чтобы оба источника сходились (тест-сценарий Этапа 2).
                if (result.ok && result.entry && result.entry.kind === 'plugin') {
                    const r = await runAgySubcommand(['plugin', enabled ? 'enable' : 'disable', result.entry.name]);
                    result.cli = { ok: r.ok, output: r.stdout || r.stderr };
                }
                invalidateAllIdleWorkers();
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/extensions/remove') {
                const packageId = String(payload.id || payload.packageId || '').trim();
                const result = extensionRegistry.removePackage({
                    packageId,
                    registryIndexPath: REGISTRY_INDEX_PATH,
                    keepFiles: payload.keepFiles === true
                });
                invalidateAllIdleWorkers();
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/extensions/export') {
                const outPath = String(payload.outPath || path.join(UPLOADS_DIR, 'exports', `extensions-${Date.now()}.zip`));
                const result = extensionRegistry.exportOwnSet({
                    registryIndexPath: REGISTRY_INDEX_PATH,
                    outPath,
                    packageIds: Array.isArray(payload.packageIds) ? payload.packageIds : null
                });
                return sendJson(res, result.ok ? 200 : 400, result);
            }

            if (urlObj.pathname === '/api/openrouter/config') {
                const saved = saveOpenRouterConfig(payload);
                return sendJson(res, 200, {
                    ok: true,
                    hasKey: Boolean(saved.apiKey && saved.apiKey.length > 8),
                    maskedKey: saved.apiKey ? `${saved.apiKey.slice(0, 9)}...${saved.apiKey.slice(-4)}` : '',
                    enabled: saved.enabled !== false,
                    selectedModel: saved.selectedModel || ''
                });
            }

            if (urlObj.pathname === '/api/openrouter/generate-image') {
                const generated = await handleOpenRouterImageGeneration(payload);
                return sendJson(res, generated.status, generated.body);
            }

            if (urlObj.pathname === '/api/openrouter/generate-video') {
                return sendJson(res, 501, {
                    ok: false,
                    error: 'Генерация видео не подключена: через OpenRouter нет доступных видео-моделей. Заглушки отключены — используйте генерацию изображений.'
                });
            }

            if (urlObj.pathname === '/api/permissions') {
                const updated = saveAiPermissionsConfig(payload);
                return sendJson(res, 200, {
                    ok: true,
                    permissions: updated
                });
            }

            if (urlObj.pathname === '/api/sync/state') {
                const merged = mergeSyncState(loadSyncState(), payload);
                saveSyncState(merged);
                return sendJson(res, 200, Object.assign({ ok: true }, merged));
            }

            if (urlObj.pathname === '/api/agents/swarm') {
                const swarm = updateMultiAgentSwarmState(payload);
                return sendJson(res, 200, {
                    ok: true,
                    swarm
                });
            }

            if (urlObj.pathname === '/api/context/reset') {
                const cid = payload.conversationId || '';
                const clearTranscript = Boolean(payload.clearTranscript);
                const result = resetSessionContextWindow(cid, clearTranscript);
                return sendJson(res, 200, result);
            }

            if (urlObj.pathname === '/api/context/compact') {
                const compacted = await handleContextCompaction(payload);
                return sendJson(res, compacted.status, compacted.body);
            }

            if (urlObj.pathname === '/api/gdrive/config') {
                // Сохранение реальных OAuth-данных Google (Client ID/Secret, токены).
                const current = loadGDriveOAuthConfig();
                const updated = saveGDriveOAuthConfig({
                    accessToken: String(payload.accessToken || current.accessToken || ''),
                    refreshToken: String(payload.refreshToken || current.refreshToken || ''),
                    clientId: String(payload.clientId || current.clientId || ''),
                    clientSecret: String(payload.clientSecret || current.clientSecret || ''),
                    accountEmail: String(payload.accountEmail || current.accountEmail || '')
                });
                const status = await googleDriveStatus();
                return sendJson(res, 200, { ok: true, ...status, hasRefreshToken: Boolean(updated.refreshToken) });
            }

            if (urlObj.pathname === '/api/gdrive/create' || urlObj.pathname === '/api/gdrive/update') {
                const oauth = loadGDriveOAuthConfig();
                if (oauth.accessToken || oauth.refreshToken) {
                    const gdRes = await createOrUpdateGoogleDriveRealFile(payload);
                    return sendJson(res, gdRes.ok ? 200 : 502, gdRes);
                }
                const gdRes = createOrUpdateGoogleDriveFile(payload);
                return sendJson(res, 200, {
                    ...gdRes,
                    files: listGoogleDriveFiles()
                });
            }

            if (urlObj.pathname === '/api/gdrive/delete') {
                const oauth = loadGDriveOAuthConfig();
                if (oauth.accessToken || oauth.refreshToken) {
                    const delRes = await deleteGoogleDriveRealFile(String(payload.id || ''));
                    return sendJson(res, delRes.ok ? 200 : 502, delRes);
                }
                const delRes = deleteGoogleDriveFile(payload.name || payload.fileName || '');
                return sendJson(res, 200, {
                    ...delRes,
                    files: listGoogleDriveFiles()
                });
            }

            if (urlObj.pathname === '/api/upload') {
                const rawName = (payload.fileName || 'uploaded_file.bin').replace(/[^a-zA-Z0-9._\-\u0400-\u04FF]/g, '_');
                const destPath = path.join(UPLOADS_DIR, rawName);
                try {
                    if (payload.base64Data) {
                        const buf = Buffer.from(payload.base64Data, 'base64');
                        fs.writeFileSync(destPath, buf);
                    } else if (payload.textContent) {
                        fs.writeFileSync(destPath, payload.textContent, 'utf8');
                    } else {
                        fs.writeFileSync(destPath, '', 'utf8');
                    }
                    return sendJson(res, 200, {
                        ok: true,
                        fileName: rawName,
                        pcPath: destPath
                    });
                } catch (err) {
                    return sendJson(res, 500, { ok: false, error: err.message });
                }
            }

            if (urlObj.pathname === '/api/chat' || urlObj.pathname === '/api/chat/stream' || urlObj.pathname === '/api/openrouter/chat') {
                const turnId = payload.turnId || `turn_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
                const rawPromptField = payload.prompt || payload.message || '';
                const cleanUserPrompt = String(rawPromptField).trim();
                const model = payload.model || 'gemini-4-argon-high';
                const mode = payload.mode || 'PC_REMOTE';
                const provider = urlObj.pathname === '/api/openrouter/chat' ? 'OPENROUTER' : (payload.provider || '');
                const conversationId = payload.conversationId || '';
                const workspacePath = payload.workspacePath || DEFAULT_WORKSPACE;
                const phoneContext = (payload.phoneContext || '').trim();
                const phoneToolObservation = (payload.phoneToolObservation || '').trim();
                const phoneStepIndex = Number(payload.phoneStepIndex) || 0;
                const voiceMode = Boolean(payload.voiceMode);
                const attachments = Array.isArray(payload.attachments) ? payload.attachments : [];
                const clientHistory = Array.isArray(payload.history) ? payload.history : [];
                const wantsStream = urlObj.pathname === '/api/chat/stream' ||
                    payload.stream === true ||
                    (req.headers.accept || '').includes('text/event-stream') ||
                    (req.headers.accept || '').includes('application/x-ndjson');

                const turnOptions = {
                    turnId,
                    provider,
                    rawUserPrompt: rawPromptField,
                    cleanUserPrompt,
                    model,
                    mode,
                    conversationId,
                    workspacePath,
                    allowTranscriptWrite: transcriptWriteAllowed(mode, Boolean(payload.transient)),
                    clientHistory,
                    phoneContext,
                    phoneToolObservation,
                    phoneStepIndex,
                    voiceMode,
                    attachments
                };

                if (wantsStream) {
                    res.writeHead(200, {
                        'Content-Type': 'application/x-ndjson; charset=utf-8',
                        'Cache-Control': 'no-cache, no-transform',
                        'Connection': 'keep-alive',
                        'X-Accel-Buffering': 'no',
                        'Access-Control-Allow-Origin': '*'
                    });

                    await executeAgyTurnStreaming(turnOptions, (evt) => {
                        if (!res.writableEnded) {
                            try {
                                res.write(JSON.stringify(evt) + '\n');
                            } catch (_) {}
                        }
                    });
                    if (!res.writableEnded) {
                        res.end();
                    }
                    return;
                }

                const result = await executeAgyTurnStreaming(turnOptions);
                return sendJson(res, 200, {
                    ok: result.ok,
                    turnId: result.turnId || turnId,
                    instanceName: INSTANCE_NAME,
                    conversationId: result.conversationId,
                    model: result.model,
                    thinking: result.thinking || '',
                    thinkingDurationSec: result.thinkingDurationSec || 0,
                    reply: result.reply,
                    toolCalls: result.toolCalls || [],
                    createdDiffs: result.createdDiffs || [],
                    generatedMedia: result.generatedMedia || [],
                    phoneActions: result.phoneActions || [],
                    ttfbMs: result.ttfbMs,
                    totalMs: result.totalMs
                });
            }

            if (urlObj.pathname === '/api/pc/shell') {
                const perms = loadAiPermissionsConfig();
                if (!perms.pcPowerShellExecution || perms.toolExecutionPolicy === 'strict') {
                    return sendJson(res, 403, {
                        ok: false,
                        output: 'Выполнение команд терминала ПК заблокировано политикой разрешений ИИ (Доступ ИИ -> Команды терминала ПК).'
                    });
                }
                const cmd = payload.command || 'git status';
                exec(cmd, { cwd: DEFAULT_WORKSPACE, shell: 'powershell.exe', timeout: 20000 }, (err, stdout, stderr) => {
                    return sendJson(res, 200, {
                        ok: !err,
                        output: (stdout || '') + (stderr || '')
                    });
                });
                return;
            }

            return sendJson(res, 404, { ok: false, error: 'Unknown route' });
            } catch (error) {
                console.error('[bridge] Request failed:', error.message);
                if (!res.headersSent) return sendJson(res, 500, { ok: false, error: 'Bridge request failed' });
                if (!res.writableEnded && !res.destroyed) {
                    res.end(JSON.stringify({ type: 'done', ok: false, reply: 'Запрос завершился с ошибкой. Повторите попытку.' }) + '\n');
                }
            }
        return;
    }

    return sendJson(res, 404, { ok: false, error: 'Not found' });
});

// ============================================================================
// Отказоустойчивый менеджер 3 независимых HTTPS-туннелей (serveo.net + pinggy.io + localhost.run)
// ============================================================================
const TUNNEL_URL_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'tunnel.url');
const TUNNELS_JSON_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'tunnels.json');
const TUNNEL_LOG_FILE = path.join(DEFAULT_WORKSPACE, 'pc-bridge', 'tunnel.log');

const TUNNEL_PROVIDERS = [
    {
        key: 'serveo',
        label: 'Serveo HTTPS',
        sshArgs: [
            '-T',
            '-o', 'StrictHostKeyChecking=no',
            '-o', 'ServerAliveInterval=20',
            '-o', 'ServerAliveCountMax=3',
            '-o', 'ExitOnForwardFailure=yes',
            '-R', `80:127.0.0.1:${PORT}`,
            'serveo.net'
        ],
        urlRegex: /https:\/\/[a-z0-9\-]+\.serveousercontent\.com/gi
    },
    {
        key: 'pinggy',
        label: 'Pinggy Port-443 HTTPS',
        sshArgs: [
            '-T',
            '-p', '443',
            '-o', 'StrictHostKeyChecking=no',
            '-o', 'ServerAliveInterval=20',
            '-o', 'ServerAliveCountMax=3',
            '-o', 'ExitOnForwardFailure=yes',
            `-R0:127.0.0.1:${PORT}`,
            'a.pinggy.io'
        ],
        urlRegex: /https:\/\/[a-z0-9\-]+\.(?:free\.)?pinggy\.net|https:\/\/[a-z0-9\-]+\.a\.free\.pinggy\.link/gi
    },
    {
        key: 'lhr',
        label: 'Localhost.run HTTPS',
        sshArgs: [
            '-T',
            '-o', 'StrictHostKeyChecking=no',
            '-o', 'ServerAliveInterval=20',
            '-o', 'ServerAliveCountMax=3',
            '-o', 'ExitOnForwardFailure=yes',
            '-R', `80:127.0.0.1:${PORT}`,
            'nokey@localhost.run'
        ],
        urlRegex: /https:\/\/[a-z0-9]+\.lhr\.life/gi
    }
];

const tunnelState = {
    serveo: { child: null, url: '', failures: 0 },
    pinggy: { child: null, url: '', failures: 0 },
    lhr: { child: null, url: '', failures: 0 }
};

let lastPublishedTunnelPayload = '';
let lastNtfyPublishTimeMs = 0;
let pendingNtfyTimer = null;

function getActiveTunnelUrlList() {
    // Приоритет: serveo (не ротируется каждые 5 минут) -> pinggy (60 мин, порт 443) -> lhr
    const ordered = [
        tunnelState.serveo.url,
        tunnelState.pinggy.url,
        tunnelState.lhr.url
    ].filter(u => u && u.startsWith('https://'));
    return [...new Set(ordered)];
}

function persistTunnelFiles() {
    const urls = getActiveTunnelUrlList();
    if (urls.length === 0) return;
    try {
        fs.writeFileSync(TUNNEL_URL_FILE, urls.join('\n') + '\n', 'utf8');
        fs.writeFileSync(TUNNELS_JSON_FILE, JSON.stringify({
            updatedAt: new Date().toISOString(),
            primaryUrl: urls[0],
            urls: urls,
            providers: {
                serveo: tunnelState.serveo.url || '',
                pinggy: tunnelState.pinggy.url || '',
                lhr: tunnelState.lhr.url || ''
            }
        }, null, 2), 'utf8');
    } catch (_) {}
}

function publishActiveTunnelsToNtfy(force = false) {
    const urls = getActiveTunnelUrlList();
    if (urls.length === 0) return;
    persistTunnelFiles();
    const payload = urls.join('\n');
    const now = Date.now();
    if (!force && payload === lastPublishedTunnelPayload && (now - lastNtfyPublishTimeMs) < 1800000) {
        return;
    }
    lastPublishedTunnelPayload = payload;
    lastNtfyPublishTimeMs = now;
    for (const topic of ['ag_bridge_cr05318_st811210', 'ag_bridge_cr05318_backup']) {
        try {
            const req = https.request(`https://ntfy.sh/${topic}`, {
                method: 'POST',
                headers: { 'Content-Type': 'text/plain; charset=utf-8' }
            }, (res) => {
                res.resume();
            });
            req.on('error', () => {});
            req.write(payload);
            req.end();
        } catch (_) {}
    }
}

function scheduleNtfyPublish() {
    persistTunnelFiles();
    if (pendingNtfyTimer) {
        clearTimeout(pendingNtfyTimer);
    }
    pendingNtfyTimer = setTimeout(() => {
        pendingNtfyTimer = null;
        publishActiveTunnelsToNtfy(true);
    }, 3500);
}

function startProviderTunnel(provider) {
    const st = tunnelState[provider.key];
    if (!st) return;
    if (st.child) {
        const old = st.child;
        st.child = null;
        try { old.kill(); } catch (_) {}
    }
    try {
        const child = spawn('ssh', provider.sshArgs, { windowsHide: true });
        st.child = child;

        const handleOutput = (chunk) => {
            const raw = chunk.toString('utf8');
            // Удаляем ANSI escape-последовательности (важно для pinggy.io и serveo.net)
            const cleanText = raw.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
            const matches = cleanText.match(provider.urlRegex);
            if (matches && matches.length > 0) {
                const foundUrl = matches[matches.length - 1].trim();
                if (foundUrl && foundUrl !== st.url) {
                    st.url = foundUrl;
                    st.failures = 0;
                    const logLine = `[${new Date().toISOString()}] [Tunnel:${provider.key}] Active HTTPS bridge URL: ${foundUrl}\n`;
                    console.log(logLine.trim());
                    try { fs.appendFileSync(TUNNEL_LOG_FILE, logLine, 'utf8'); } catch (_) {}
                    scheduleNtfyPublish();
                }
            }
        };

        child.stdout.on('data', handleOutput);
        child.stderr.on('data', handleOutput);
        child.on('close', () => {
            if (st.child === child) {
                st.child = null;
                setTimeout(() => startProviderTunnel(provider), 3000);
            }
        });
        child.on('error', () => {
            if (st.child === child) {
                st.child = null;
                setTimeout(() => startProviderTunnel(provider), 4500);
            }
        });
    } catch (_) {
        setTimeout(() => startProviderTunnel(provider), 5000);
    }
}

function startAllManagedTunnels() {
    for (const provider of TUNNEL_PROVIDERS) {
        startProviderTunnel(provider);
    }
}

function verifyAllTunnelsHealth() {
    for (const provider of TUNNEL_PROVIDERS) {
        const st = tunnelState[provider.key];
        if (!st || !st.url) continue;
        try {
            const req = https.get(`${st.url}/api/status`, { timeout: 7000 }, (res) => {
                res.resume();
                if (res.statusCode === 200) {
                    st.failures = 0;
                } else {
                    st.failures++;
                    if (st.failures >= 2) {
                        st.failures = 0;
                        startProviderTunnel(provider);
                    }
                }
            });
            req.on('timeout', () => {
                req.destroy();
            });
            req.on('error', () => {
                st.failures++;
                if (st.failures >= 2) {
                    st.failures = 0;
                    startProviderTunnel(provider);
                }
            });
        } catch (_) {}
    }
}

process.on('uncaughtException', (err) => {
    console.error('[PC-Bridge] Uncaught exception:', err && err.message ? err.message : err);
});

process.on('unhandledRejection', (reason) => {
    console.error('[PC-Bridge] Unhandled rejection:', reason);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Antigravity PC Bridge listening on http://0.0.0.0:${PORT} (Instance: ${INSTANCE_NAME})`);
    startAllManagedTunnels();
    setInterval(verifyAllTunnelsHealth, 40000);
    setInterval(() => {
        publishActiveTunnelsToNtfy(false);
    }, 600000);
});



