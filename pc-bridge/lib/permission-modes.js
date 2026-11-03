'use strict';
// Режимы разрешений — «как у них» по составу значений (референс Claude:
// enum com.anthropic.claude.sessions.types.PermissionMode =
// default | plan | auto | acceptEdits | dontAsk | bypassPermissions; строки
// «Auto mode lets Claude handle permission prompts automatically. Claude checks
// each tool call for risky actions and prompt injection before executing, runs
// the ones it assesses as lower-risk, and blocks the rest.», «Enable Auto
// mode?», «Accept edits», «This model requires Auto mode», «Your organization
// turned off Auto mode…»).
//
// ЧЕСТНАЯ ГРАНИЦА: их Auto-режим использует модельную оценку рисков и защиту
// от prompt injection на стороне Claude — это НЕ воспроизводится и не
// выдаётся за воспроизведение («требует проверки»). Наш auto-режим —
// задокументированная БИНАРНАЯ эвристика (classifyToolRisk): имя инструмента
// и аргументы сопоставляются со списком опасных паттернов ниже. Список открыт,
// случаи вне списка относятся к low-risk — это заявленное ограничение модели.
//
// Семантика режимов (наша модель; поверх поштучных прав tool-permissions):
//   default            — прежнее поведение: только права инструментов.
//   plan               — чтение как обычно; запись/выполнение/разрушение —
//                        честный отказ «режим плана» (изменения запрещены).
//   auto               — low-risk выполняются; high-risk (или опасные аргументы)
//                        — «Спрашивать»; явный deny прав не обходится.
//   acceptEdits        — запись разрешается автоматически; выполнение/
//                        разрушение — по правам инструментов; опасные аргументы
//                        — «Спрашивать».
//   dontAsk            — всё разрешено, кроме явного deny.
//   bypassPermissions  — всё разрешено, кроме явного deny (deny прав всегда
//                        побеждает; точная семантика их bypass — «требует
//                        проверки»).
// Правило данных: явный запрет (deny) любого скоупа НИКОГДА не обходится.

const fs = require('fs');
const path = require('path');

const VALID_MODES = ['default', 'plan', 'auto', 'acceptEdits', 'dontAsk', 'bypassPermissions'];

// Открытые паттерны эвристики (документируются как ограничение модели).
const EXEC_DESTRUCTIVE_NAME = /(exec|shell|run[_-]?command|terminal|powershell|bash|cmd|kill|delete|remove|drop|purge|truncate|force|uninstall|format|mkfs|rm\b|destroy|wipe)/i;
const WRITE_NAME = /(write|create|patch|edit|move|rename|save|upload|insert|update|set[_-]?value|put\b)/i;
const DANGEROUS_ARGS = [
    /\brm\s+-[a-z]*r[a-z]*f/i,
    /\brm\s+-[a-z]*f[a-z]*r/i,
    /drop\s+(table|database|schema)/i,
    /truncate\s+table/i,
    /:\(\)\s*\{.*\}\s*;/,                 // fork bomb
    /\bmkfs(\.|\b)/i,
    /\bsudo\s+/i,
    /\|\s*(ba)?sh\b/,
    /\|\s*powershell\b/i,
    /\bcurl\b[^\n|]*\|\s*(ba)?sh/i,
    /--force\s+push/i,
    /git\s+push[^\n]*--force/i,
    /\.\.[\\/]\.\.[\\/]/,                  // выход за пределы каталога
    /format\s+[a-z]:/i
];

function classifyToolRisk(toolName, args) {
    const name = String(toolName || '');
    const reasons = [];
    if (EXEC_DESTRUCTIVE_NAME.test(name)) reasons.push(`имя инструмента «${name}» по паттерну выполнения/разрушения`);
    let argText = '';
    try {
        argText = typeof args === 'string' ? args : JSON.stringify(args ?? {});
    } catch (_) {
        argText = String(args ?? '');
    }
    for (const re of DANGEROUS_ARGS) {
        const m = argText.match(re);
        if (m) reasons.push(`опасный паттерн в аргументах: «${String(m[0]).slice(0, 60)}»`);
    }
    const category = EXEC_DESTRUCTIVE_NAME.test(name) ? 'exec_destructive'
        : (WRITE_NAME.test(name) ? 'write' : 'read');
    return { risk: reasons.length ? 'high' : 'low', category, reasons };
}

function defaultConfigPath() {
    return path.join(__dirname, '..', 'permissions-config.json');
}

function createPermissionModes({ configPath } = {}) {
    const file = configPath || defaultConfigPath();

    function loadConfig() {
        try {
            if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf8'));
        } catch (_) {}
        return {};
    }

    function getMode() {
        const m = String(loadConfig().permissionMode || 'default');
        return VALID_MODES.includes(m) ? m : 'default';
    }

    function setMode(mode) {
        const value = String(mode || '');
        if (!VALID_MODES.includes(value)) {
            return { ok: false, error: `Режим должен быть одним из: ${VALID_MODES.join(', ')}` };
        }
        let config = loadConfig();
        config = { ...config, permissionMode: value, updatedAt: new Date().toISOString() };
        try {
            fs.writeFileSync(file, JSON.stringify(config, null, 2), 'utf8');
            return { ok: true, mode: value };
        } catch (_) {
            return { ok: false, error: 'Не удалось записать permissions-config.json' };
        }
    }

    /**
     * Применяет режим к базовому результату прав инструментов.
     * @param {object} baseResult результат toolPermissions.checkToolCall
     *   ({ allow, permission, source } либо { allow:false, errorCode, error }).
     * @returns {object} итоговый вердикт (формат checkToolCall).
     */
    function applyModeToCheck(baseResult, toolName, args, { userInitiated = false, mode = getMode() } = {}) {
        const isDenied = baseResult && baseResult.allow === false && baseResult.errorCode === 'tool_denied';
        const isAsk = baseResult && baseResult.allow === false && baseResult.errorCode === 'tool_needs_approval';

        // Явный deny прав никогда не обходится никаким режимом.
        if (isDenied) return baseResult;

        const { risk, category, reasons } = classifyToolRisk(toolName, args);
        const reasonText = reasons.length ? ` Основание: ${reasons.join('; ')}.` : '';
        const allowResult = source => ({ allow: true, permission: 'allow', source });

        switch (mode) {
            case 'plan':
                if (category !== 'read') {
                    return {
                        allow: false,
                        errorCode: 'tool_denied',
                        error: `Инструмент «${toolName}» недоступен в режиме «План»: изменения и выполнение запрещены (наш режим планирования; категория: ${category}).${reasonText}`
                    };
                }
                return baseResult;
            case 'auto': {
                if (risk === 'high' && !userInitiated) {
                    return {
                        allow: false,
                        errorCode: 'tool_needs_approval',
                        error: `Инструмент «${toolName}» оценён как рискованный и требует подтверждения (автоматический режим — НАША эвристическая оценка, не модельная проверка Claude; их Auto-режим «требует проверки»).${reasonText} Подтвердите вызов вручную или измените режим.`
                    };
                }
                if (risk === 'high' && userInitiated) return allowResult('auto-mode');
                return baseResult;
            }
            case 'acceptEdits': {
                if (category === 'write') return allowResult('acceptEdits-mode');
                if (category === 'exec_destructive' && risk === 'high' && !userInitiated && !isAsk) {
                    return {
                        allow: false,
                        errorCode: 'tool_needs_approval',
                        error: `Инструмент «${toolName}» (выполнение/разрушение) требует подтверждения в режиме «Принимать правки» (правки файлов принимаются автоматически).${reasonText}`
                    };
                }
                return baseResult;
            }
            case 'dontAsk':
            case 'bypassPermissions':
                // Снимаем «Спрашивать» (в т.ч. базовый); deny уже отсечён выше.
                return allowResult(`${mode}-mode`);
            case 'default':
            default:
                return baseResult;
        }
    }

    return {
        configPath: file,
        validModes: VALID_MODES.slice(),
        getMode,
        setMode,
        classifyToolRisk,
        applyModeToCheck
    };
}

module.exports = { createPermissionModes, VALID_MODES, classifyToolRisk };
