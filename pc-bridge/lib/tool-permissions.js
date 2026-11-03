'use strict';
// Поштучные права инструментов MCP — «как у них» (референс Claude: экраны
// ChatOptionsBottomSheetDestination$McpToolPermission /
// $McpSetAllToolsPermission; строки ресурсов «Change tool permission»,
// «Change permission for all tools», значения «Ask before sending to Claude» /
// «Always allow» / «Deny»; скоупы «Always allow for this project / session /
// task / website»).
//
// Значения прав: allow | ask | deny. Приоритет (наиболее специфичный побеждает):
//   1) скоуп задачи (task, по taskId — в памяти процесса);
//   2) скоуп сессии (session, по sessionId — в памяти процесса);
//   3) явное право инструмента (tool, скоуп project — persist);
//   4) скоуп сайта (website, по origin HTTP-подключения — persist);
//   5) право сервера «все инструменты» (serverDefault — persist);
//   6) глобальная политика toolExecutionPolicy файла permissions-config.json
//      (always-proceed → allow, иначе ask).
// Семантика скоупов — наша честная модель (точная семантика скоупов референса
// по его коду не разобрана, «требует проверки»): project — этот рабочий
// каталог/ПК (persist), session — текущий разговор (пока жив мост), task —
// текущая пачка субагентов (пока жив мост), website — origin HTTP-подключения
// (для stdio-серверов скоуп website честно недоступен, не выдумывается).
// Запись project/website-прав ведётся только в ключ `toolPermissions` файла
// permissions-config.json; session/task-права хранятся только в памяти и в
// файл не пишутся. Остальные ключи файла сохраняются как есть.
// deny блокирует ВСЕ вызовы инструмента; ask блокирует только вызовы модели
// (пользовательские явные вызовы с userInitiated проходят — «спросить»
// обрабатывается самим пользователем в момент действия).

const fs = require('fs');
const path = require('path');

const VALID_PERMISSIONS = ['allow', 'ask', 'deny'];
const VALID_SCOPES = ['project', 'session', 'task', 'website'];

function defaultConfigPath() {
    return path.join(__dirname, '..', 'permissions-config.json');
}

function createToolPermissions({ configPath } = {}) {
    const file = configPath || defaultConfigPath();
    // Скоупы session/task — только память процесса (переживают до перезапуска
    // моста; как и у референса — скоуп «для этой сессии» не является вечным).
    const runtimeGrants = {
        session: new Map(), // sessionId -> { serverId -> { tools: {...}, default } }
        task: new Map()     // taskId   -> { serverId -> { tools: {...}, default } }
    };

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

    // --- Скоупы session/task (память процесса) --------------------------------
    function runtimeServerEntry(scope, scopeId, serverId, create) {
        const byServer = runtimeGrants[scope].get(scopeId) || (create ? {} : null);
        if (!byServer) return null;
        if (create) {
            if (!runtimeGrants[scope].has(scopeId)) runtimeGrants[scope].set(scopeId, byServer);
            if (!byServer[String(serverId)]) byServer[String(serverId)] = {};
            return byServer[String(serverId)];
        }
        return byServer[String(serverId)] || null;
    }

    function runtimeGet(scope, scopeId, serverId, toolName) {
        if (!scopeId) return null;
        const srv = runtimeServerEntry(scope, scopeId, serverId, false);
        if (!srv) return null;
        const explicit = srv.tools && normalize(srv.tools[String(toolName)]);
        if (explicit) return { permission: explicit, source: scope };
        const level = normalize(srv.default);
        if (level) return { permission: level, source: `${scope}-default` };
        return null;
    }

    function runtimeSet(scope, scopeId, serverId, toolName, permission) {
        const value = normalize(permission);
        if (!value) return { ok: false, error: 'permission должен быть allow|ask|deny' };
        const srv = runtimeServerEntry(scope, scopeId, serverId, true);
        if (toolName) {
            srv.tools = { ...(srv.tools || {}), [String(toolName)]: value };
        } else {
            srv.default = value;
        }
        return { ok: true, permission: value, scope, scopeId };
    }

    // --- Скоуп website (persist, по origin HTTP-подключения) ------------------
    function websiteKey(origin) {
        return `website:${String(origin)}`;
    }

    function getWebsitePermission(origin, toolName) {
        const s = section()[websiteKey(origin)] || {};
        const explicit = s.tools && normalize(s.tools[String(toolName)]);
        if (explicit) return { permission: explicit, source: 'website' };
        const level = normalize(s.default);
        if (level) return { permission: level, source: 'website-default' };
        return null;
    }

    function setWebsitePermission(origin, toolName, permission) {
        const value = normalize(permission);
        if (!value) return { ok: false, error: 'permission должен быть allow|ask|deny' };
        const sec = section();
        const key = websiteKey(origin);
        const server = { ...(sec[key] || {}) };
        if (toolName) {
            server.tools = { ...(server.tools || {}), [String(toolName)]: value };
        } else {
            server.default = value;
        }
        sec[key] = server;
        const written = saveToolPermissionsSection(sec);
        return { ok: written, error: written ? '' : 'Не удалось записать permissions-config.json', permission: value, scope: 'website' };
    }

    function getToolPermission(serverId, toolName, { sessionId = '', taskId = '', origin = '' } = {}) {
        // 1–2: скоупы задачи и сессии (наиболее специфичные).
        if (taskId) {
            const r = runtimeGet('task', taskId, serverId, toolName);
            if (r) return r;
        }
        if (sessionId) {
            const r = runtimeGet('session', sessionId, serverId, toolName);
            if (r) return r;
        }
        // 3: явное право инструмента проекта.
        const server = section()[String(serverId)] || {};
        const explicit = server.tools && normalize(server.tools[String(toolName)]);
        if (explicit) return { permission: explicit, source: 'tool' };
        // 4: скоуп сайта (origin HTTP-подключения).
        if (origin) {
            const w = getWebsitePermission(origin, toolName);
            if (w) return w;
        }
        // 5: право сервера «все инструменты».
        const serverLevel = normalize(server.default);
        if (serverLevel) return { permission: serverLevel, source: 'server' };
        // 6: глобальная политика.
        return { permission: globalDefault(), source: 'policy' };
    }

    function setToolPermission(serverId, toolName, permission, { scope = 'project', scopeId = '', origin = '' } = {}) {
        const value = normalize(permission);
        if (!value) return { ok: false, error: 'permission должен быть allow|ask|deny' };
        const cleanScope = VALID_SCOPES.includes(String(scope)) ? String(scope) : 'project';
        if (cleanScope === 'session' || cleanScope === 'task') {
            if (!String(scopeId).trim()) {
                return { ok: false, error: `Для скоупа «${cleanScope}» нужен ${cleanScope === 'session' ? 'sessionId' : 'taskId'} (скоуп действует только в памяти моста)` };
            }
            return runtimeSet(cleanScope, String(scopeId), serverId, toolName, value);
        }
        if (cleanScope === 'website') {
            if (!String(origin).trim()) {
                return {
                    ok: false,
                    error: 'Скоуп «для этого сайта» доступен только для HTTP-подключений с известным origin (у stdio-серверов сайта нет — не выдумываем)',
                    code: 'website_scope_requires_origin'
                };
            }
            return setWebsitePermission(String(origin), toolName, value);
        }
        // project (persist) — прежняя поведение.
        const sec = section();
        const server = { ...(sec[String(serverId)] || {}) };
        if (toolName) {
            server.tools = { ...(server.tools || {}), [String(toolName)]: value };
        } else {
            server.default = value;
        }
        sec[String(serverId)] = server;
        const written = saveToolPermissionsSection(sec);
        return { ok: written, error: written ? '' : 'Не удалось записать permissions-config.json', permission: value, scope: 'project' };
    }

    // Проверка перед tools/call. Возвращает либо { allow: true }, либо отказ
    // с errorCode и человекочитаемой ошибкой (без выдуманного успеха).
    function checkToolCall(serverId, toolName, { userInitiated = false, sessionId = '', taskId = '', origin = '' } = {}) {
        const { permission, source } = getToolPermission(serverId, toolName, { sessionId, taskId, origin });
        const tool = String(toolName);
        if (permission === 'deny') {
            return {
                allow: false,
                errorCode: 'tool_denied',
                error: `Инструмент «${tool}» запрещён настройкой прав («Запретить»${source.includes('default') || source === 'server' ? ' для всех инструментов сервера' : ''}, скоуп: ${source}). Разрешите его в карточке подключения.`
            };
        }
        if (permission === 'ask' && !userInitiated) {
            return {
                allow: false,
                errorCode: 'tool_needs_approval',
                error: `Инструмент «${tool}» требует разрешения пользователя (режим «Спрашивать», скоуп: ${source}). Разрешите его в карточке подключения — «Всегда разрешать».`
            };
        }
        return { allow: true, permission, source };
    }

    return {
        configPath: file,
        validPermissions: VALID_PERMISSIONS.slice(),
        validScopes: VALID_SCOPES.slice(),
        globalDefault,
        getServerDefault,
        setServerDefault,
        getToolPermission,
        setToolPermission,
        setWebsitePermission,
        getWebsitePermission,
        checkToolCall
    };
}

module.exports = { createToolPermissions, VALID_PERMISSIONS, VALID_SCOPES };
