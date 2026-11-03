'use strict';
// Поштучные права инструментов MCP — «как у них» (референс Claude: экраны
// ChatOptionsBottomSheetDestination$McpToolPermission /
// $McpSetAllToolsPermission; строки ресурсов «Change tool permission»,
// «Change permission for all tools», значения «Ask before sending to Claude» /
// «Always allow» / «Deny»).
//
// Значения прав: allow | ask | deny. Приоритет:
//   1) явное право инструмента (tool);
//   2) право сервера «все инструменты» (serverDefault);
//   3) глобальная политика toolExecutionPolicy файла permissions-config.json
//      (always-proceed → allow, иначе ask).
// Запись ведётся только в ключ `toolPermissions` файла permissions-config.json;
// остальные ключи (preset, политики, разрешения устройств) сохраняются как есть.
// deny блокирует ВСЕ вызовы инструмента; ask блокирует только вызовы модели
// (пользовательские явные вызовы с userInitiated проходят — «спросить»
// обрабатывается самим пользователем в момент действия).

const fs = require('fs');
const path = require('path');

const VALID_PERMISSIONS = ['allow', 'ask', 'deny'];

function defaultConfigPath() {
    return path.join(__dirname, '..', 'permissions-config.json');
}

function createToolPermissions({ configPath } = {}) {
    const file = configPath || defaultConfigPath();

    function loadConfig() {
        try {
            if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf8'));
        } catch (_) {}
        return {};
    }

    function saveToolPermissionsSection(toolPermissions) {
        let config = loadConfig();
        config = { ...config, toolPermissions, updatedAt: new Date().toISOString() };
        try {
            fs.writeFileSync(file, JSON.stringify(config, null, 2), 'utf8');
            return true;
        } catch (_) {
            return false;
        }
    }

    function section() {
        const s = loadConfig().toolPermissions;
        return (s && typeof s === 'object') ? s : {};
    }

    function globalDefault() {
        const cfg = loadConfig();
        // Глобальная политика совпадает с полем toolExecutionPolicy Antigravity 2.0;
        // без файла/ключа действует дефолт продукта AUTOPILOT (always-proceed → allow).
        const policy = cfg.toolExecutionPolicy || 'always-proceed';
        return policy === 'always-proceed' ? 'allow' : 'ask';
    }

    function normalize(value) {
        return VALID_PERMISSIONS.includes(String(value)) ? String(value) : null;
    }

    function getServerDefault(serverId) {
        const s = section()[String(serverId)];
        return (s && normalize(s.default)) || null;
    }

    function setServerDefault(serverId, permission) {
        const value = normalize(permission);
        if (!value) return { ok: false, error: 'permission должен быть allow|ask|deny' };
        const sec = section();
        const server = { ...(sec[String(serverId)] || {}), default: value };
        sec[String(serverId)] = server;
        const written = saveToolPermissionsSection(sec);
        return { ok: written, error: written ? '' : 'Не удалось записать permissions-config.json', permission: value };
    }

    function getToolPermission(serverId, toolName) {
        const server = section()[String(serverId)] || {};
        const explicit = server.tools && normalize(server.tools[String(toolName)]);
        if (explicit) return { permission: explicit, source: 'tool' };
        const serverLevel = normalize(server.default);
        if (serverLevel) return { permission: serverLevel, source: 'server' };
        return { permission: globalDefault(), source: 'policy' };
    }

    function setToolPermission(serverId, toolName, permission) {
        const value = normalize(permission);
        if (!value) return { ok: false, error: 'permission должен быть allow|ask|deny' };
        const sec = section();
        const server = { ...(sec[String(serverId)] || {}) };
        server.tools = { ...(server.tools || {}), [String(toolName)]: value };
        sec[String(serverId)] = server;
        const written = saveToolPermissionsSection(sec);
        return { ok: written, error: written ? '' : 'Не удалось записать permissions-config.json', permission: value };
    }

    // Проверка перед tools/call. Возвращает либо { allow: true }, либо отказ
    // с errorCode и человекочитаемой ошибкой (без выдуманного успеха).
    function checkToolCall(serverId, toolName, { userInitiated = false } = {}) {
        const { permission, source } = getToolPermission(serverId, toolName);
        const tool = String(toolName);
        if (permission === 'deny') {
            return {
                allow: false,
                errorCode: 'tool_denied',
                error: `Инструмент «${tool}» запрещён настройкой прав («Запретить»${source === 'server' ? ' для всех инструментов сервера' : ''}). Разрешите его в карточке подключения.`
            };
        }
        if (permission === 'ask' && !userInitiated) {
            return {
                allow: false,
                errorCode: 'tool_needs_approval',
                error: `Инструмент «${tool}» требует разрешения пользователя (режим «Спрашивать»). Разрешите его в карточке подключения — «Всегда разрешать».`
            };
        }
        return { allow: true, permission, source };
    }

    return {
        configPath: file,
        validPermissions: VALID_PERMISSIONS.slice(),
        globalDefault,
        getServerDefault,
        setServerDefault,
        getToolPermission,
        setToolPermission,
        checkToolCall
    };
}

module.exports = { createToolPermissions, VALID_PERMISSIONS };
