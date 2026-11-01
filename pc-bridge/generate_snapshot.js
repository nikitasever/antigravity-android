const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

const IDE_BASE_DIR = 'C:\\Users\\st811210\\.gemini\\antigravity';
const CLI_BASE_DIR = 'C:\\Users\\st811210\\.gemini\\antigravity-cli';
const BRAIN_DIR = path.join(IDE_BASE_DIR, 'brain');
const SCRATCH_DIR = path.join(IDE_BASE_DIR, 'scratch');
const ASSETS_DIR = 'C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android\\app\\src\\main\\assets';
const OUT_FILE = path.join(ASSETS_DIR, 'pc_sessions_snapshot.json');

const GARBAGE_TITLES = new Set([
    '<thinking>', 'thinking', '2+2=?', 'или?', 'оо', 'оооо', 'тест', 'проверка', 'повтор'
]);

function isGarbageOrInternalTitle(title) {
    if (!title) return true;
    const t = String(title).trim();
    if (t.length < 3) return true;
    const lower = t.toLowerCase();
    if (GARBAGE_TITLES.has(lower)) return true;
    if (lower.startsWith('<thinking') || lower.includes('<original_task>') || lower.includes('you are a coding worker')) return true;
    if (lower.startsWith('проанализируй прикреплённые файлы и подробно ответь')) return true;
    return false;
}

function extractCleanUserMessage(raw) {
    if (!raw) return '';
    let s = raw;
    const reqMatch = s.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
    if (reqMatch) {
        s = reqMatch[1];
    }
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
        'Текущие ресурсы и результаты локального сканирования смартфона пользователя:'
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

function makeShortTitle(cleanPrompt) {
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
    if (isGarbageOrInternalTitle(t)) return '';
    return t.slice(0, 68);
}

function formatTimeFromIso(isoStr) {
    if (!isoStr) return 'ПК сессия';
    try {
        const d = new Date(isoStr);
        if (isNaN(d.getTime())) return 'ПК сессия';
        const hh = String(d.getHours()).padStart(2, '0');
        const mm = String(d.getMinutes()).padStart(2, '0');
        return `${hh}:${mm}`;
    } catch (_) {
        return 'ПК сессия';
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
                        try { decoded = JSON.parse(`"${m[1]}"`); } catch (_) {}
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

function syncCliSessionsToIde() {
    try {
        const cliBrainDir = path.join(CLI_BASE_DIR, 'brain');
        if (!fs.existsSync(cliBrainDir)) return;
        const cliSumPath = path.join(CLI_BASE_DIR, 'conversation_summaries.db');
        const ideSumPath = path.join(IDE_BASE_DIR, 'conversation_summaries.db');
        const cliDb = fs.existsSync(cliSumPath) ? new DatabaseSync(cliSumPath) : null;
        const ideDb = fs.existsSync(ideSumPath) ? new DatabaseSync(ideSumPath) : null;

        for (const d of fs.readdirSync(cliBrainDir, { withFileTypes: true })) {
            if (!d.isDirectory() && !d.isSymbolicLink()) continue;
            const id = d.name;
            const srcBrain = path.join(cliBrainDir, id);
            const dstBrain = path.join(BRAIN_DIR, id);
            if (!fs.existsSync(dstBrain)) {
                try { fs.cpSync(srcBrain, dstBrain, { recursive: true, force: true }); } catch (_) {}
            }
            for (const [sub, ext] of [['conversations', '.db'], ['annotations', '.pbtxt']]) {
                const src = path.join(CLI_BASE_DIR, sub, `${id}${ext}`);
                const dst = path.join(IDE_BASE_DIR, sub, `${id}${ext}`);
                if (fs.existsSync(src) && !fs.existsSync(dst)) {
                    try { fs.copyFileSync(src, dst); } catch (_) {}
                }
            }
            if (cliDb && ideDb) {
                const row = cliDb.prepare('SELECT * FROM conversation_summaries WHERE conversation_id = ?').get(id);
                if (row && !isGarbageOrInternalTitle(row.title)) {
                    const exists = ideDb.prepare('SELECT conversation_id FROM conversation_summaries WHERE conversation_id = ?').get(id);
                    if (!exists) {
                        ideDb.prepare(`
                            INSERT INTO conversation_summaries (
                                conversation_id, title, preview, step_count, last_modified_time,
                                workspace_uris, status, source, project_id, agent_name,
                                parent_conversation_id, nesting_depth, battle_id, winning_conversation_id,
                                not_fully_idle, killed, last_user_input_time, last_user_input_step_index,
                                app_data_dir, raw_summary, group_id, summary_proto
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        `).run(
                            id, row.title, row.preview || row.title, row.step_count || 2, row.last_modified_time,
                            row.workspace_uris || '', 'CASCADE_RUN_STATUS_IDLE', row.source || '',
                            row.project_id || 'f8db42b8-16d0-4569-9503-22cefcb44205', row.agent_name || '',
                            '', 0, '', '', 0, 0, row.last_user_input_time, row.last_user_input_step_index || 0,
                            'antigravity', row.raw_summary, row.group_id || '', row.summary_proto || null
                        );
                    }
                }
            }
        }
        if (cliDb) cliDb.close();
        if (ideDb) ideDb.close();
    } catch (_) {}
}

function buildSnapshot() {
    syncCliSessionsToIde();
    fs.mkdirSync(ASSETS_DIR, { recursive: true });
    const sessions = [];
    const seenIds = new Set();
    const officialMeta = readOfficialSessionMetadata();

    const allDirs = [];
    for (const baseBrain of [BRAIN_DIR, path.join(CLI_BASE_DIR, 'brain')]) {
        if (!fs.existsSync(baseBrain)) continue;
        for (const d of fs.readdirSync(baseBrain, { withFileTypes: true })) {
            if ((!d.isDirectory() && !d.isSymbolicLink()) || d.name === 'tempmediaStorage') continue;
            if (seenIds.has(d.name)) continue;
            seenIds.add(d.name);
            const full = path.join(baseBrain, d.name);
            const logFile = resolveBestTranscriptFile(d.name);
            let mtime = 0;
            try {
                mtime = logFile && fs.existsSync(logFile)
                    ? Math.floor(fs.statSync(logFile).mtimeMs)
                    : Math.floor(fs.statSync(full).mtimeMs);
            } catch (_) {}
            const meta = officialMeta.get(d.name);
            if (meta && meta.updatedAtMs && meta.updatedAtMs > mtime) {
                mtime = meta.updatedAtMs;
            }
            allDirs.push({ id: d.name, full, logFile, mtime, meta });
        }
    }

    allDirs.sort((a, b) => b.mtime - a.mtime);

    for (const item of allDirs) {
        if (item.meta && item.meta.isSubagent) continue;

        let transcriptTitle = '';
        let isSubagentTranscript = false;
        const messages = [];

        if (item.logFile && fs.existsSync(item.logFile)) {
            const content = fs.readFileSync(item.logFile, 'utf8');
            const lines = content.split(/\r?\n/).filter(Boolean);
            for (const line of lines) {
                try {
                    const obj = JSON.parse(line);
                    if (obj.type === 'USER_INPUT' && obj.content) {
                        if (obj.content.includes('<original_task>') || obj.content.includes('You are a coding worker')) {
                            isSubagentTranscript = true;
                            break;
                        }
                        const cleanText = extractCleanUserMessage(obj.content);
                        if (cleanText.length > 0 &&
                            !cleanText.startsWith('You receive messages automatically') &&
                            !cleanText.startsWith('The following is a <SYSTEM_MESSAGE>')) {
                            if (!transcriptTitle) {
                                transcriptTitle = makeShortTitle(cleanText);
                            }
                            messages.push({
                                role: 'USER',
                                content: cleanText.slice(0, 2500),
                                timestamp: formatTimeFromIso(obj.created_at)
                            });
                        }
                    } else if (obj.type === 'PLANNER_RESPONSE' && obj.content && typeof obj.content === 'string') {
                        const text = obj.content
                            .replace(/\[`([^`]+)`\]\(file:\/\/\/[^)\r\n]*\)?/gi, '`$1`')
                            .replace(/\[([^\]]+)\]\(file:\/\/\/[^)\r\n]*\)?/gi, '**$1**')
                            .replace(/file:\/\/\/[A-Za-z]:\/[^\s)\]`"']*/gi, (m) => {
                                const last = m.split('/').pop() || '';
                                return last.split('#')[0] || '';
                            })
                            .replace(/<truncated\s+\d+\s+bytes>/gi, '\n')
                            .trim();
                        if (text.length > 0) {
                            messages.push({
                                role: 'AGENT',
                                content: text.slice(0, 6000),
                                thinking: typeof obj.thinking === 'string'
                                    ? obj.thinking.replace(/<truncated\s+\d+\s+bytes>/gi, '...').trim().slice(0, 500)
                                    : '',
                                timestamp: formatTimeFromIso(obj.created_at)
                            });
                        }
                    }
                } catch (_) {}
            }
        }

        if (isSubagentTranscript) continue;

        const officialTitle = (item.meta && !isGarbageOrInternalTitle(item.meta.title)) ? item.meta.title : '';
        const finalTitle = officialTitle || transcriptTitle;
        if (messages.length === 0 || !finalTitle || isGarbageOrInternalTitle(finalTitle)) {
            continue;
        }

        const artifacts = [];
        try {
            const files = fs.readdirSync(item.full, { withFileTypes: true });
            for (const f of files) {
                if (f.isFile() && f.name.endsWith('.md')) {
                    const mdPath = path.join(item.full, f.name);
                    const mdBody = fs.readFileSync(mdPath, 'utf8').slice(0, 3000);
                    artifacts.push({
                        name: f.name,
                        preview: mdBody
                    });
                }
            }
        } catch (_) {}

        const host = (sessions.length % 5 === 4) ? 'WIN-11' : 'cr05318';
        sessions.push({
            id: item.id,
            title: finalTitle,
            environment: 'Рабочая станция ПК',
            host: host,
            hostType: 'PC',
            workspacePath: 'C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android',
            updatedAtMs: item.mtime,
            messages: messages.slice(-24),
            artifacts: artifacts
        });
    }

    const projects = [];
    if (fs.existsSync(SCRATCH_DIR)) {
        const pDirs = fs.readdirSync(SCRATCH_DIR, { withFileTypes: true })
            .filter(d => d.isDirectory() && !d.name.startsWith('.') && d.name !== 'jdk17');
        for (const d of pDirs) {
            const pFull = path.join(SCRATCH_DIR, d.name);
            let fileCount = 0;
            let sampleFiles = [];
            try {
                const topEntries = fs.readdirSync(pFull);
                fileCount = topEntries.length;
                sampleFiles = topEntries.slice(0, 8);
            } catch (_) {}
            projects.push({
                name: d.name,
                path: pFull,
                host: 'cr05318-orbital-mars',
                fileCount: fileCount,
                sampleFiles: sampleFiles
            });
        }
    }

    const payload = {
        generatedAt: new Date().toISOString(),
        instanceName: 'cr05318-orbital-mars',
        sessions: sessions,
        projects: projects
    };

    fs.writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2), 'utf8');
    console.log(`Wrote ${sessions.length} synchronized PC sessions and ${projects.length} projects to ${OUT_FILE}`);
    for (const s of sessions.slice(0, 12)) {
        console.log(`  - ${s.id.slice(0, 8)} | msgs=${s.messages.length} | ${s.title}`);
    }
}

buildSnapshot();
