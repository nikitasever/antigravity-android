'use strict';
// Этап 4 (EXTENSIONS_INTEGRATION_PLAN.md): подключение реестра расширений
// к моделям и дочерним задачам.
//
// Экономика: метаданные доступных навыков — всегда; полные инструкции — только
// выбранного навыка; схемы инструментов — только разрешённых и только из живого
// tools/list (реестр Этапа 3), без угадывания.
//
// Стороны исполнения: инструменты MCP-серверов выполняются на ПК (через
// McpClientManager), инструменты смартфона — только на телефоне (протокол
// phone_action). Одноимённые инструменты разных серверов имеют разные ссылки
// `serverId:toolName` и НИКОГДА не взаимозаменяемы; запрос телефона не
// перенаправляется на ПК.
//
// Права: набор инструментов выдаётся только по явному разрешающему списку.
// Аннотации MCP (readOnlyHint и подобные) сами по себе НЕ дают прав и
// проверяются как данные, а не как основание доступа.
//
// Проверенный механизм CLI (`agy --help`, `agy help mcp`): слэш-команды и навыки
// раскрывает сам CLI в print-режиме; инструменты предоставляет зарегистрированными
// MCP-серверами (`agy mcp add`), произвольной инъекции схем нет; `--settings`
// отвергает ключ "mcpServers" (unknown setting) — посессионный поднабор MCP для
// CLI невозможен и не выдумывается.

const fs = require('node:fs');
const path = require('node:path');
const { parseSkillMarkdown, findFileReferences } = require('./extension-formats');

// ---------------------------------------------------------------------------
// Навыки: каталог метаданных → выбор → загрузка инструкций ТОЛЬКО выбранного.
// ---------------------------------------------------------------------------

function buildSkillCatalog(skillRoots) {
    const catalog = [];
    const seen = new Set();
    for (const root of Array.isArray(skillRoots) ? skillRoots : []) {
        if (!root || !root.dir) continue;
        let entries = [];
        try {
            entries = fs.readdirSync(root.dir, { withFileTypes: true });
        } catch (_) {
            continue;
        }
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const skillDir = path.join(root.dir, entry.name);
            const live = path.join(skillDir, 'SKILL.md');
            const disabled = path.join(skillDir, 'SKILL.md.disabled');
            const hasLive = fs.existsSync(live);
            const hasDisabled = fs.existsSync(disabled);
            if (!hasLive && !hasDisabled) continue;
            let name = entry.name;
            let description = '';
            try {
                const raw = fs.readFileSync(hasLive ? live : disabled, 'utf8');
                const parsed = parseSkillMarkdown(raw, entry.name);
                if (parsed && parsed.name) name = parsed.name;
                if (parsed && parsed.description) description = parsed.description;
            } catch (_) {}
            const key = `${root.source || ''}::${name}`;
            if (seen.has(key)) continue;
            seen.add(key);
            catalog.push({
                name,
                description,
                enabled: hasLive,
                source: root.source || 'unknown',
                dir: skillDir
            });
        }
    }
    return catalog;
}

// Выбор навыка по первому токену `/имя-навыка`. Возвращает каноническое имя и
// остаток запроса; без совпадения — skillId null.
function detectSkillInvocation(prompt, catalog) {
    const m = String(prompt || '').match(/^\s*\/([A-Za-z0-9][A-Za-z0-9_-]{0,62})\s*/);
    if (!m) return { skillId: null, restPrompt: String(prompt || '') };
    const token = m[1].toLowerCase();
    const hit = (catalog || []).find(s => String(s.name).toLowerCase() === token);
    if (!hit) return { skillId: null, restPrompt: String(prompt || '') };
    return { skillId: hit.name, restPrompt: String(prompt || '').slice(m[0].length) };
}

// Загрузка инструкций выбранного навыка. Отключённый/отсутствующий навык —
// явный отказ (запрет доступа действует), без выдуманных инструкций.
function loadSelectedSkill(skillId, { skillRoots, catalog } = {}) {
    const cat = catalog || buildSkillCatalog(skillRoots);
    const hit = cat.find(s => String(s.name).toLowerCase() === String(skillId || '').toLowerCase());
    if (!hit) {
        return {
            status: 'not_found',
            skill: null,
            refusal: `Навык "${skillId}" не найден в реестре. Инструкции не загружены; выполни запрос без навыка или уточни имя.`
        };
    }
    if (!hit.enabled) {
        return {
            status: 'disabled',
            skill: null,
            refusal: `Навык "${hit.name}" отключён — доступ запрещён, инструкции не загружены.`
        };
    }
    let parsed = null;
    let raw = '';
    try {
        raw = fs.readFileSync(path.join(hit.dir, 'SKILL.md'), 'utf8');
        parsed = parseSkillMarkdown(raw, hit.name);
    } catch (err) {
        return {
            status: 'not_found',
            skill: null,
            refusal: `Навык "${hit.name}" не читается: ${err.message}. Инструкции не загружены.`
        };
    }
    const instructions = String((parsed && parsed.instruction) || '').trim();
    if (!instructions) {
        return {
            status: 'not_found',
            skill: null,
            refusal: `Навык "${hit.name}" не содержит инструкций (пустой SKILL.md).`
        };
    }
    // Ресурсы: только перечень путей для загрузки по необходимости (экономия).
    const resourcePaths = [];
    for (const sub of ['scripts', 'references', 'assets']) {
        const subDir = path.join(hit.dir, sub);
        try {
            for (const f of fs.readdirSync(subDir)) {
                resourcePaths.push(path.join(sub, f));
            }
        } catch (_) {}
    }
    for (const ref of findFileReferences(instructions)) {
        if (!resourcePaths.includes(ref)) resourcePaths.push(ref);
    }
    return {
        status: 'ok',
        skill: {
            name: hit.name,
            description: hit.description || (parsed && parsed.description) || '',
            source: hit.source,
            dir: hit.dir,
            instructions,
            resourcePaths
        },
        refusal: null
    };
}

// Блок инструкций для промпта. Ресурсы перечислены путями и НЕ загружаются
// без необходимости.
function composeSkillInstructionBlock(skill) {
    const resourceLines = (skill.resourcePaths || [])
        .slice(0, 20)
        .map(p => `- ${p}`)
        .join('\n');
    return (
        `<SKILL_INSTRUCTION name="${skill.name}">\n` +
        `Выбран навык "${skill.name}"${skill.description ? `: ${skill.description}` : ''}.\n` +
        `Инструкция навыка (загружены инструкции ТОЛЬКО этого навыка):\n` +
        `${skill.instructions}\n` +
        (resourceLines
            ? `\nФайлы-ресурсы навыка (читать только при реальной необходимости):\n${resourceLines}\n`
            : '') +
        `</SKILL_INSTRUCTION>`
    );
}

// ---------------------------------------------------------------------------
// Каталог инструментов: реальные схемы живого tools/list + инструменты
// смартфона (телефонная сторона). Одноимённые инструменты разных серверов —
// разные позиции с разными ссылками.
// ---------------------------------------------------------------------------

// Инструменты смартфона (протокол phone_action) — выполняются ТОЛЬКО на
// смартфоне; этот список совпадает с buildSmartphoneToolsSystemPrompt().
const PHONE_ACTION_TOOLS = [
    { action: 'search_files', description: 'Поиск файлов и документов на смартфоне', inputSchema: { type: 'object', properties: { query: { type: 'string' }, extension: { type: 'string' } } } },
    { action: 'search_media', description: 'Поиск фото/видео/аудио в MediaStore', inputSchema: { type: 'object', properties: { mediaType: { type: 'string' }, dateFilter: { type: 'string' }, query: { type: 'string' } } } },
    { action: 'read_file', description: 'Чтение файла на смартфоне', inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] } },
    { action: 'write_file', description: 'Создание/перезапись файла на смартфоне', inputSchema: { type: 'object', properties: { path: { type: 'string' }, content: { type: 'string' } }, required: ['path', 'content'] } },
    { action: 'patch_file', description: 'Точечное изменение файла на смартфоне', inputSchema: { type: 'object', properties: { path: { type: 'string' }, target: { type: 'string' }, replacement: { type: 'string' } }, required: ['path'] } },
    { action: 'list_dir', description: 'Просмотр папки на смартфоне', inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] } },
    { action: 'contacts', description: 'Поиск контактов на смартфоне', inputSchema: { type: 'object', properties: { query: { type: 'string' } } } },
    { action: 'open_app', description: 'Запуск приложения на смартфоне', inputSchema: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'] } },
    { action: 'share', description: 'Отправка через меню «Поделиться» на смартфоне', inputSchema: { type: 'object', properties: { content: { type: 'string' } }, required: ['content'] } }
];

function buildToolCatalog({ mcpServers, includePhoneTools = false } = {}) {
    const catalog = [];
    for (const server of Array.isArray(mcpServers) ? mcpServers : []) {
        if (!server || !server.id) continue;
        for (const tool of Array.isArray(server.tools) ? server.tools : []) {
            if (!tool || typeof tool.name !== 'string') continue;
            catalog.push({
                ref: `${server.id}:${tool.name}`,
                serverId: String(server.id),
                toolName: tool.name,
                name: tool.name,
                description: typeof tool.description === 'string' ? tool.description : '',
                inputSchema: (tool.inputSchema && typeof tool.inputSchema === 'object')
                    ? tool.inputSchema
                    : { type: 'object', properties: {} },
                side: 'pc',
                // Аннотации сохраняются как данные; права они не дают.
                annotations: tool.annotations && typeof tool.annotations === 'object' ? tool.annotations : null
            });
        }
    }
    if (includePhoneTools) {
        for (const t of PHONE_ACTION_TOOLS) {
            catalog.push({
                ref: `phone:${t.action}`,
                serverId: 'phone',
                toolName: t.action,
                name: t.action,
                description: t.description,
                inputSchema: t.inputSchema,
                side: 'phone',
                annotations: null
            });
        }
    }
    return catalog;
}

// Разрешение доступа ТОЛЬКО по явному списку. Аннотации не дают прав:
// инструмент с readOnlyHint=true, но вне списка — в denied.
function filterAllowedTools(catalog, { allowRefs, allowServers, allowAllPc = false } = {}) {
    const allowed = [];
    const denied = [];
    const refSet = Array.isArray(allowRefs) ? new Set(allowRefs.map(String)) : null;
    const serverSet = Array.isArray(allowServers) ? new Set(allowServers.map(String)) : null;
    for (const entry of catalog || []) {
        if (entry.side === 'phone') {
            // Телефонные инструменты не входят в набор исполняемых на ПК.
            denied.push({ ...entry, denyReason: 'phone-side' });
            continue;
        }
        const explicitlyAllowed =
            (refSet && refSet.has(entry.ref)) ||
            (serverSet && serverSet.has(entry.serverId));
        if (allowAllPc || explicitlyAllowed) {
            allowed.push(entry);
        } else {
            denied.push({ ...entry, denyReason: 'not-in-allowlist' });
        }
    }
    return { allowed, denied };
}

// Схемы для OpenAI-совместимого tools-параметра. Имя функции — безопасная
// форма ссылки `serverId__toolName` (двоеточие недопустимо в именах функций);
// обратное соответствие хранится в nameToRef, чтобы серверы не смешивались.
function toOpenAiToolSchemas(allowed) {
    const tools = [];
    const nameToRef = new Map();
    const used = new Set();
    for (const entry of allowed || []) {
        if (entry.side !== 'pc') continue;
        let fnName = `${entry.serverId}__${entry.toolName}`.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 60);
        if (!/^[A-Za-z_]/.test(fnName)) fnName = `t_${fnName}`;
        let unique = fnName;
        let n = 2;
        while (used.has(unique)) unique = `${fnName.slice(0, 56)}_${n++}`;
        used.add(unique);
        nameToRef.set(unique, entry.ref);
        tools.push({
            type: 'function',
            function: {
                name: unique,
                description: `[${entry.serverId}] ${entry.description}`.trim(),
                parameters: entry.inputSchema
            }
        });
    }
    return { tools, nameToRef };
}

// ---------------------------------------------------------------------------
// Вызов инструмента: строгая привязка к серверу и стороне, с проверкой
// разрешающего списка на момент вызова.
// ---------------------------------------------------------------------------

async function executeBrokerToolCall(ref, args, { catalog, callPcTool, allowedRefs, signal, timeoutMs } = {}) {
    const entry = (catalog || []).find(t => t.ref === ref);
    if (!entry) {
        return {
            ok: false,
            code: 'tool_not_found',
            error: `Инструмент "${ref}" отсутствует в реестре. Одноимённые инструменты разных серверов не взаимозаменяемы.`
        };
    }
    if (entry.side === 'phone') {
        return {
            ok: false,
            code: 'wrong_side',
            error: `Инструмент "${ref}" выполняется на смартфоне (phone_action) и не перенаправляется на ПК.`
        };
    }
    if (allowedRefs && !allowedRefs.has(ref)) {
        return {
            ok: false,
            code: 'denied',
            error: `Доступ к инструменту "${ref}" запрещён политикой (вне разрешённого набора).`
        };
    }
    if (typeof callPcTool !== 'function') {
        return { ok: false, code: 'no_executor', error: 'Исполнитель PC-инструментов не подключён' };
    }
    const call = await callPcTool(entry.serverId, entry.toolName, (args && typeof args === 'object') ? args : {}, { signal, timeoutMs });
    if (call && call.ok) {
        return { ok: true, side: 'pc', serverId: entry.serverId, toolName: entry.toolName, result: call.result };
    }
    return {
        ok: false,
        code: (call && call.errorCode) || 'tool_error',
        error: (call && call.error) || 'Вызов инструмента не выполнен',
        side: 'pc',
        serverId: entry.serverId,
        toolName: entry.toolName
    };
}

// ---------------------------------------------------------------------------
// Цикл «модель → вызовы инструментов → результаты → модель» для адаптеров с
// нативным tool-calling (OpenRouter/OpenAI-совместимые). Результаты возвращаются
// в тот же цикл; Stop (signal) отменяет и HTTP-запрос, и вызовы инструментов.
// ---------------------------------------------------------------------------

function toolResultToText(result) {
    if (!result) return 'Результат отсутствует.';
    if (result.ok) {
        const r = result.result;
        if (r && Array.isArray(r.content)) {
            const texts = r.content
                .filter(c => c && typeof c.text === 'string')
                .map(c => c.text);
            return texts.join('\n') || 'Инструмент выполнен без текстового результата.';
        }
        return JSON.stringify(r);
    }
    return `ОШИБКА ИНСТРУМЕНТА (${result.code || 'tool_error'}): ${result.error || 'без описания'}`;
}

// Этап 5: injectSteps hook'ов (hooks.md) → сообщения контекста хода.
// systemMessage сохраняется в траектории; ephemeralMessage действует только
// в текущем ходе (контекст хода собирается заново на каждый ход).
function injectStepsToMessages(steps) {
    const out = [];
    for (const s of steps || []) {
        if (!s || typeof s !== 'object') continue;
        if (s.kind === 'systemMessage') out.push({ role: 'system', content: String(s.text || '') });
        else if (s.kind === 'userMessage') out.push({ role: 'user', content: String(s.text || '') });
        else if (s.kind === 'ephemeralMessage') out.push({ role: 'system', content: `[ephemeral] ${String(s.text || '')}` });
    }
    return out;
}

async function runToolCallLoop({ messages, tools, nameToRef, chat, execute, maxRounds = 6, signal, onToolEvent, hookRunner = null } = {}) {
    const working = (messages || []).map(m => ({ ...m }));
    const toolCalls = [];
    const emit = (evt) => {
        if (typeof onToolEvent === 'function') {
            try { onToolEvent(evt); } catch (_) {}
        }
    };
    const emitHookRefusals = (refusals) => {
        if (Array.isArray(refusals) && refusals.length > 0) {
            emit({ type: 'hook-refusal', refusals });
        }
    };
    // Этап 5: injectSteps hook'ов (hooks.md) — в текущий контекст хода.
    const applyInjectSteps = (steps) => {
        working.push(...injectStepsToMessages(steps));
    };
    for (let round = 1; round <= (maxRounds || 6); round++) {
        if (signal && signal.aborted) {
            // Отмена пользователем: Stop-hook'и не выполняются — решение
            // пользователя приоритетно (политика Этапа 5).
            return { finalMessage: null, rounds: round - 1, toolCalls, cancelled: true };
        }
        // Этап 5: PreInvocation — до вызова модели (hooks.md).
        if (hookRunner && typeof hookRunner.onInvocation === 'function') {
            const pre = await hookRunner.onInvocation({ event: 'PreInvocation', invocationNum: round, initialNumSteps: working.length });
            if (pre && Array.isArray(pre.injectSteps)) applyInjectSteps(pre.injectSteps);
            if (pre) emitHookRefusals(pre.refusals);
            if (pre && pre.terminationBehavior === 'terminate') {
                return { finalMessage: null, rounds: round, toolCalls, hookTerminated: true };
            }
        }
        const resp = await chat(working, tools || [], { signal });
        const msg = (resp && resp.message) ? resp.message : resp;
        const calls = (msg && Array.isArray(msg.tool_calls)) ? msg.tool_calls : [];
        if (!msg || typeof msg !== 'object') {
            return { finalMessage: null, rounds: round, toolCalls, exhausted: true };
        }
        if (calls.length === 0) {
            // Этап 5: PostInvocation (может продлить цикл) и Stop (может
            // заблокировать остановку). maxRounds — жёсткий предел моста.
            let shouldStop = true;
            if (hookRunner && typeof hookRunner.onInvocation === 'function') {
                const post = await hookRunner.onInvocation({ event: 'PostInvocation', invocationNum: round, initialNumSteps: working.length, wouldStop: true });
                if (post && Array.isArray(post.injectSteps)) applyInjectSteps(post.injectSteps);
                if (post) emitHookRefusals(post.refusals);
                if (post && post.terminationBehavior === 'force_continue') shouldStop = false;
            }
            if (shouldStop && hookRunner && typeof hookRunner.onStop === 'function') {
                const stop = await hookRunner.onStop({ terminationReason: 'NO_TOOL_CALL' });
                if (stop) emitHookRefusals(stop.refusals);
                if (stop && stop.shouldContinue) {
                    shouldStop = false;
                    if (stop.reason) working.push({ role: 'system', content: stop.reason });
                }
            }
            if (shouldStop) {
                return { finalMessage: msg, rounds: round, toolCalls, cancelled: false };
            }
            continue;
        }
        working.push({ role: 'assistant', content: typeof msg.content === 'string' ? msg.content : '', tool_calls: calls });
        for (const c of calls) {
            if (signal && signal.aborted) {
                return { finalMessage: null, rounds: round, toolCalls, cancelled: true };
            }
            const fnName = c && c.function && c.function.name ? String(c.function.name) : '';
            const ref = (nameToRef && nameToRef.get(fnName)) || fnName;
            let args = {};
            try {
                args = JSON.parse((c && c.function && c.function.arguments) || '{}');
            } catch (_) {
                args = {};
            }
            if (typeof onToolEvent === 'function') {
                try { onToolEvent({ type: 'start', callId: c.id, ref, args }); } catch (_) {}
            }
            // Этап 5: PreToolUse (hooks.md): решение deny/ask — видимый отказ,
            // overwrite — перезапись аргументов с уведомлением в результате.
            let callArgs = args;
            let hookNotice = null;
            if (hookRunner && typeof hookRunner.beforeToolCall === 'function') {
                const pre = await hookRunner.beforeToolCall({ ref, args, callId: c.id, stepIdx: toolCalls.length });
                if (pre) emitHookRefusals(pre.refusals);
                if (pre && pre.allow === false) {
                    const refusalText = `Вызов заблокирован hook'ом PreToolUse: ${pre.reason || 'решение deny'}`;
                    toolCalls.push({ callId: c.id, ref, ok: false, code: 'hook_denied', serverId: null, toolName: null });
                    emit({ type: 'hook', callId: c.id, ref, status: 'BLOCKED', reason: pre.reason || 'deny' });
                    working.push({ role: 'tool', tool_call_id: c.id, content: refusalText });
                    continue;
                }
                if (pre && pre.args && typeof pre.args === 'object') callArgs = pre.args;
                if (pre && pre.notice) hookNotice = pre.notice;
            }
            const result = await execute(ref, callArgs, { signal });
            if (hookRunner && typeof hookRunner.afterToolCall === 'function') {
                const post = await hookRunner.afterToolCall({
                    ref, args: callArgs, callId: c.id, result, stepIdx: toolCalls.length,
                    error: result && result.ok ? null : (result && result.code) || 'tool_error'
                });
                if (post) emitHookRefusals(post.refusals);
            }
            toolCalls.push({ callId: c.id, ref, ok: result.ok, code: result.code || null, serverId: result.serverId || null, toolName: result.toolName || null });
            if (typeof onToolEvent === 'function') {
                try { onToolEvent({ type: 'end', callId: c.id, ref, result }); } catch (_) {}
            }
            // По контракту hooks.md переписанные hook'ом аргументы помечаются
            // уведомлением перед результатом.
            const resultText = hookNotice ? `${hookNotice}\n${toolResultToText(result)}` : toolResultToText(result);
            working.push({
                role: 'tool',
                tool_call_id: c.id,
                content: resultText
            });
        }
        // Этап 5: PostInvocation — после завершения вызовов инструментов.
        if (hookRunner && typeof hookRunner.onInvocation === 'function') {
            const post = await hookRunner.onInvocation({ event: 'PostInvocation', invocationNum: round, initialNumSteps: working.length, wouldStop: false });
            if (post && Array.isArray(post.injectSteps)) applyInjectSteps(post.injectSteps);
            if (post) emitHookRefusals(post.refusals);
            if (post && post.terminationBehavior === 'terminate') {
                return { finalMessage: null, rounds: round, toolCalls, hookTerminated: true };
            }
        }
    }
    // Лимит ходов исчерпан: maxRounds — жёсткий предел, Stop-hook не продлевает.
    if (hookRunner && typeof hookRunner.onStop === 'function') {
        const stop = await hookRunner.onStop({ terminationReason: 'MAX_INVOCATIONS' });
        const extraRefusals = (stop && Array.isArray(stop.refusals)) ? stop.refusals.slice() : [];
        if (stop && stop.shouldContinue) {
            // Решение hook'а видимо отражается, но цикл не продлевается.
            extraRefusals.push({
                hookName: null,
                event: 'Stop',
                code: 'hard_round_limit',
                reason: `Stop-hook запросил продолжение, но лимит ходов (${maxRounds}) — жёсткий предел моста`
            });
        }
        emitHookRefusals(extraRefusals);
    }
    return { finalMessage: null, rounds: maxRounds, toolCalls, exhausted: true };
}

// ---------------------------------------------------------------------------
// Дочерние задачи: явный поднабор прав. Аннотации MCP не дают прав; список
// разрешений — единственный источник. Проверенный механизм CLI: только промпт
// (--settings отвергает mcpServers — см. шапку модуля).
// ---------------------------------------------------------------------------

function buildChildTaskPermissionBlock({ allowedRefs, allowedServers } = {}) {
    const refs = Array.isArray(allowedRefs) ? allowedRefs : [];
    const servers = Array.isArray(allowedServers) ? allowedServers : [];
    const lines = [];
    if (servers.length > 0) {
        lines.push(`- MCP-серверы (только эти): ${servers.join(', ')}`);
    }
    for (const r of refs) lines.push(`- ${r}`);
    return (
        `<CHILD_TASK_PERMISSIONS>\n` +
        `Дочерняя задача выполняется с ограниченным поднабором прав.\n` +
        (lines.length > 0
            ? `Разрешённые инструменты (использовать ТОЛЬКО их):\n${lines.join('\n')}\n`
            : `Разрешённых инструментов НЕТ — работай только анализом и текстом, не вызывай инструменты.\n`) +
        `Аннотации инструментов MCP (readOnlyHint и подобные) НЕ дают прав и не отменяют этот список.\n` +
        `</CHILD_TASK_PERMISSIONS>`
    );
}

module.exports = {
    buildSkillCatalog,
    detectSkillInvocation,
    injectStepsToMessages,
    loadSelectedSkill,
    composeSkillInstructionBlock,
    PHONE_ACTION_TOOLS,
    buildToolCatalog,
    filterAllowedTools,
    toOpenAiToolSchemas,
    executeBrokerToolCall,
    runToolCallLoop,
    toolResultToText,
    buildChildTaskPermissionBlock
};
