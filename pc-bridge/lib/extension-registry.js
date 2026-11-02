'use strict';
// Этап 2 (EXTENSIONS_INTEGRATION_PLAN.md): общий реестр расширений.
//
// Назначение по §3 плана: ExtensionRegistry — идентичность, издатель, исходный
// тип/продукт, версия, источник, лицензия, хэш, состав, зависимости,
// совместимость и проверенные возможности. Здесь же — импорт с просмотром
// состава до установки, жизненный цикл (установка → перезапуск → отключение →
// удаление) без потери соседних пакетов и экспорт собственного набора.
//
// Единый источник состояния (блокеры local-plugin-kotlin-compose-inspector и
// local-mcp-rea): действия пишутся в индекс реестра И в нижележащие хранилища;
// расхождения источников (CLI, config.json, mcp_config.json, settings.json,
// файловая система) не скрываются — карточка получает «требует сверки
// состояния», а не ложное «включено».
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const formats = require('./extension-formats');
const archive = require('./extension-archive');
const scriptRunner = require('./script-runner');

const REGISTRY_INDEX_VERSION = 1;
const USER_STATE_VERSION = 1;

function sha256Of(buf) {
    return crypto.createHash('sha256').update(buf).digest('hex');
}

// ---------------------------------------------------------------------------
// Валидация имени пакета (QA/Security, Critical #1): имя из manifest.name и
// front matter попадает в пути файловой системы и в id реестра — поэтому
// запрещены пустые строки, длина > 64, слэши, двоеточия, "..", символы вне
// /^[a-z0-9][a-z0-9._-]*$/i, а raw обязан совпадать с path.basename(raw).
// ---------------------------------------------------------------------------

const PACKAGE_NAME_RE = /^[a-z0-9][a-z0-9._-]*$/i;
const PACKAGE_NAME_MAX = 64;

function validatePackageName(raw) {
    const issues = [];
    const name = String(raw == null ? '' : raw);
    if (!name.trim()) issues.push('имя пакета пустое');
    if (name.length > PACKAGE_NAME_MAX) issues.push(`имя пакета длиннее ${PACKAGE_NAME_MAX}: ${name.length}`);
    if (/[\\/]/.test(name)) issues.push(`слэш в имени пакета запрещён: ${name}`);
    if (/:/.test(name)) issues.push(`двоеточие в имени пакета запрещено: ${name}`);
    if (name.includes('..')) issues.push(`подкаталог ".." в имени пакета запрещён: ${name}`);
    if (name && !PACKAGE_NAME_RE.test(name)) issues.push(`недопустимые символы в имени пакета: ${name}`);
    if (name !== path.basename(name)) issues.push(`имя пакета не является одиночным сегментом пути: ${name}`);
    return { valid: issues.length === 0, name, issues };
}

// ---------------------------------------------------------------------------
// Атомарная запись индекса (QA/Security, High #1): временный файл + rename,
// чтобы параллельный читатель не увидел полузаписанный JSON. Плюс мьютекс
// модификаций реестра в рамках процесса: state/import/remove выполняются
// последовательно и не затирают записи друг друга.
// ---------------------------------------------------------------------------

let registryMutationChain = Promise.resolve();

function withRegistryLock(fn) {
    const run = registryMutationChain.then(() => fn());
    // Следующая операция не должна зависеть от ошибки предыдущей.
    registryMutationChain = run.then(() => undefined, () => undefined);
    return run;
}

// ---------------------------------------------------------------------------
// Индекс реестра — персистентная запись об импорте и явных действиях
// пользователя (переживает перезапуск).
//
// Этап 8: повреждение или отсутствие индекса не должно молча терять
// установленные на диске пакеты. Повреждённый снимок сохраняется в
// *.corrupt-*.backup, а по targetRoots индекс пересобирается с диска;
// событие восстановления фиксируется в recoveryLog (честная диагностика).
// ---------------------------------------------------------------------------

function atomicWriteJson(filePath, obj) {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    const tmpPath = `${filePath}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(tmpPath, JSON.stringify(obj, null, 2), 'utf8');
    fs.renameSync(tmpPath, filePath);
    return obj;
}

function preserveCorruptSnapshot(filePath, reason) {
    // Повреждённый снимок сохраняется, а не выбрасывается: содержимое доступно
    // для ручного разбора, потери данных нет.
    const backupPath = `${filePath}.corrupt-${Date.now()}-${process.pid}.backup`;
    try {
        fs.renameSync(filePath, backupPath);
        return { backupPath, reason };
    } catch (err) {
        return { backupPath: null, reason, backupError: err.message };
    }
}

function listInstalledPackageDirs(targetRoot) {
    const dirs = [];
    let names = [];
    try { names = fs.readdirSync(targetRoot); } catch (_) { return dirs; }
    for (const name of names) {
        // Служебные каталоги (staging/backup/temp) — не пакеты.
        if (name.startsWith('.')) continue;
        const dirPath = path.join(targetRoot, name);
        try {
            if (!fs.statSync(dirPath).isDirectory()) continue;
        } catch (_) { continue; }
        const hasManifest = ['plugin.json', '.claude-plugin/plugin.json', '.codex-plugin/plugin.json']
            .some(rel => fs.existsSync(path.join(dirPath, ...rel.split('/'))));
        const hasSkill = fs.existsSync(path.join(dirPath, 'SKILL.md'))
            || fs.existsSync(path.join(dirPath, 'SKILL.md.disabled'));
        if (hasManifest || hasSkill) dirs.push({ name, dirPath, kind: hasManifest && !hasSkill ? 'plugin' : (hasManifest ? 'plugin' : 'skill') });
    }
    return dirs;
}

function rebuildIndexFromDisk(targetRoots, userState = null) {
    // Пересборка по диску: каждый установленный каталог с manifest или
    // SKILL.md(.disabled) снова попадает в индекс. Пользовательское состояние
    // (enabled/разрешения/привязки) применяется поверх — отключения не теряются.
    const packages = {};
    const rebuilt = [];
    for (const root of targetRoots || []) {
        if (!root) continue;
        for (const found of listInstalledPackageDirs(root)) {
            const pkgKind = found.kind;
            const id = `${pkgKind}:${found.name}`;
            if (packages[id]) continue;
            let description = null;
            let files = [];
            let packageDigest = null;
            try {
                const src = readSourceEntries({ sourcePath: found.dirPath, kind: 'dir' });
                description = analyzeVirtualPackage(src.entries, found.name, src.getEntryData);
                files = src.entries.map(e => ({ path: e.path, size: e.size, sha256: e.sha256, executable: e.executable }));
                packageDigest = formats.packageDigestOf(files);
            } catch (_) { /* состав недоступен — запись остаётся честной */ }
            const userEntry = userState && userState.packages && userState.packages[id] ? userState.packages[id] : null;
            packages[id] = {
                id,
                name: found.name,
                kind: pkgKind,
                sourceProduct: 'Antigravity local',
                publisher: 'локальная установка',
                sourceUrl: null,
                version: description && description.manifest && description.manifest.version
                    ? description.manifest.version
                    : (description && description.skills[0] && description.skills[0].skill && description.skills[0].skill.metadata
                        ? (description.skills[0].skill.metadata.version || null) : null),
                license: description && description.manifest ? description.manifest.license : null,
                importPath: found.dirPath,
                importedAt: null,
                packageDigest,
                files,
                compositionStatus: description ? description.compositionStatus : 'требует проверки',
                skills: description ? description.skills.map(s => ({ relativePath: s.relativePath, ownerPlugin: s.ownerPlugin, name: s.skill ? s.skill.name : null })) : [],
                pluginSkillLinks: description ? description.pluginSkillLinks : [],
                unknownComponents: description ? description.unknownComponents : [],
                executableComponents: description ? description.executableComponents : [],
                enabledOverride: userEntry && typeof userEntry.enabled === 'boolean' ? userEntry.enabled : null,
                restoredFromDisk: true
            };
            rebuilt.push(id);
        }
    }
    return { packages, rebuilt };
}

function loadRegistryIndex(indexPath, options = {}) {
    const targetRoots = Array.isArray(options.targetRoots) ? options.targetRoots.filter(Boolean) : [];
    const userStatePath = options.userStatePath || null;
    let reason = null;
    let snapshot = null;
    try {
        if (fs.existsSync(indexPath)) {
            const raw = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
            if (raw && raw.version === REGISTRY_INDEX_VERSION && raw.packages && typeof raw.packages === 'object' && !Array.isArray(raw.packages)) {
                return raw;
            }
            // Валидный JSON, но не наша форма — тоже повреждение.
            snapshot = preserveCorruptSnapshot(indexPath, 'повреждённая структура индекса');
            reason = 'corrupt';
        } else {
            reason = 'missing';
        }
    } catch (_) {
        // Повреждённый JSON: снимок сохраняется, индекс пересобирается.
        snapshot = preserveCorruptSnapshot(indexPath, 'индекс не разбирается как JSON');
        reason = 'corrupt';
    }
    const userState = userStatePath ? loadUserState(userStatePath) : null;
    const rebuiltIndex = targetRoots.length > 0 ? rebuildIndexFromDisk(targetRoots, userState) : null;
    const index = {
        version: REGISTRY_INDEX_VERSION,
        updatedAt: null,
        packages: rebuiltIndex ? rebuiltIndex.packages : {},
        recoveryLog: []
    };
    // Диагностика: corrupt фиксируется всегда; missing — только когда есть что
    // пересобирать (обычный первый запуск без пакетов не засоряет журнал).
    const worthLogging = reason === 'corrupt' || (rebuiltIndex && rebuiltIndex.rebuilt.length > 0);
    if (worthLogging) {
        index.recoveryLog.push({
            at: new Date().toISOString(),
            reason,
            corruptBackup: snapshot ? snapshot.backupPath : null,
            corruptBackupNote: snapshot ? snapshot.reason : null,
            diskRebuild: rebuiltIndex ? rebuiltIndex.rebuilt : [],
            pendingDiskRebuild: !rebuiltIndex,
            note: rebuiltIndex
                ? 'индекс пересобран по установленным на диске пакетам; пользовательское состояние применено'
                : 'пересборка по диску не выполнялась: targetRoots не переданы'
        });
    }
    if (rebuiltIndex || reason === 'corrupt') saveRegistryIndex(indexPath, index);
    return index;
}

function saveRegistryIndex(indexPath, index) {
    index.updatedAt = new Date().toISOString();
    fs.mkdirSync(path.dirname(indexPath), { recursive: true });
    // Атомарная запись (High #1): временный файл в том же каталоге + rename.
    const tmpPath = `${indexPath}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(tmpPath, JSON.stringify(index, null, 2), 'utf8');
    fs.renameSync(tmpPath, indexPath);
    return index;
}

// ---------------------------------------------------------------------------
// Этап 8. Пользовательское состояние расширений (enabled/разрешения/привязки).
//
// Отдельный файл рядом с индексом: переживает перезапуск pc-bridge и Android-
// клиента (Android перечитывает состояние с моста), переживает пересборку
// индекса по диску (восстановление индекса не теряет отключения) и никогда не
// содержит секретов/токенов. Разрешения выдаются ТОЛЬКО явным действием
// (grantPackagePermissions) — обновление и восстановление их не расширяют.
// ---------------------------------------------------------------------------

function loadUserState(statePath) {
    const empty = { version: USER_STATE_VERSION, updatedAt: null, packages: {}, recoveryLog: [] };
    if (!statePath || !fs.existsSync(statePath)) return empty;
    try {
        const raw = JSON.parse(fs.readFileSync(statePath, 'utf8'));
        if (raw && raw.version === USER_STATE_VERSION && raw.packages && typeof raw.packages === 'object' && !Array.isArray(raw.packages)) {
            if (Array.isArray(raw.recoveryLog)) return raw;
            raw.recoveryLog = [];
            return raw;
        }
        const snapshot = preserveCorruptSnapshot(statePath, 'повреждённая структура состояния');
        empty.recoveryLog.push({ at: new Date().toISOString(), reason: 'corrupt', corruptBackup: snapshot.backupPath, note: 'состояние сброшено; пакеты на диске не затронуты' });
        return empty;
    } catch (_) {
        const snapshot = preserveCorruptSnapshot(statePath, 'состояние не разбирается как JSON');
        empty.recoveryLog.push({ at: new Date().toISOString(), reason: 'corrupt', corruptBackup: snapshot.backupPath, note: 'состояние сброшено; пакеты на диске не затронуты' });
        return empty;
    }
}

function saveUserState(statePath, state) {
    state.updatedAt = new Date().toISOString();
    atomicWriteJson(statePath, state);
    return state;
}

function getUserStateEntry(statePath, packageId) {
    const state = loadUserState(statePath);
    return state.packages[packageId] || null;
}

function touchUserEntry(state, packageId) {
    if (!state.packages[packageId]) {
        state.packages[packageId] = { enabled: null, permissions: { granted: [], denied: [], requested: [] }, bindings: { conversations: {} }, updatedAt: null };
    }
    const entry = state.packages[packageId];
    entry.permissions = entry.permissions || { granted: [], denied: [], requested: [] };
    entry.permissions.granted = Array.isArray(entry.permissions.granted) ? entry.permissions.granted : [];
    entry.permissions.denied = Array.isArray(entry.permissions.denied) ? entry.permissions.denied : [];
    entry.permissions.requested = Array.isArray(entry.permissions.requested) ? entry.permissions.requested : [];
    entry.bindings = entry.bindings || { conversations: {} };
    entry.bindings.conversations = entry.bindings.conversations && typeof entry.bindings.conversations === 'object' ? entry.bindings.conversations : {};
    return entry;
}

function setUserState({ statePath, packageId, enabled = undefined, permissions = undefined, bindings = undefined }) {
    const state = loadUserState(statePath);
    const entry = touchUserEntry(state, packageId);
    if (enabled !== undefined) entry.enabled = enabled === null ? null : Boolean(enabled);
    if (permissions && typeof permissions === 'object') {
        // Явное действие пользователя: разрешения заменяются только когда
        // переданы; автоматического расширения нет ни откуда.
        if (Array.isArray(permissions.granted)) entry.permissions.granted = permissions.granted.map(String);
        if (Array.isArray(permissions.denied)) entry.permissions.denied = permissions.denied.map(String);
        if (Array.isArray(permissions.requested)) entry.permissions.requested = permissions.requested.map(String);
    }
    if (bindings && typeof bindings === 'object') {
        if (bindings.conversationId != null) {
            const cid = String(bindings.conversationId);
            entry.bindings.conversations[cid] = (Array.isArray(bindings.refs) ? bindings.refs : []).map(String);
        }
        if (bindings.conversations && typeof bindings.conversations === 'object') {
            for (const [cid, refs] of Object.entries(bindings.conversations)) {
                entry.bindings.conversations[String(cid)] = (Array.isArray(refs) ? refs : []).map(String);
            }
        }
    }
    entry.updatedAt = new Date().toISOString();
    saveUserState(statePath, state);
    return { ok: true, packageId, entry };
}

function grantPackagePermissions({ statePath, packageId, grant = [], deny = [] }) {
    // Единственный путь выдачи прав: явное действие («предусмотренное действие»
    // по приёмке Этапа 8). Обновление/восстановление сюда не заходят.
    const state = loadUserState(statePath);
    const entry = touchUserEntry(state, packageId);
    for (const p of grant.map(String)) {
        entry.permissions.denied = entry.permissions.denied.filter(x => x !== p);
        if (!entry.permissions.granted.includes(p)) entry.permissions.granted.push(p);
    }
    for (const p of deny.map(String)) {
        entry.permissions.granted = entry.permissions.granted.filter(x => x !== p);
        if (!entry.permissions.denied.includes(p)) entry.permissions.denied.push(p);
    }
    entry.permissions.requested = entry.permissions.requested.filter(p => !entry.permissions.granted.includes(p) && !entry.permissions.denied.includes(p));
    entry.updatedAt = new Date().toISOString();
    saveUserState(statePath, state);
    return { ok: true, packageId, permissions: entry.permissions };
}

function setConversationBindings({ statePath, packageId, conversationId, refs = [] }) {
    return setUserState({ statePath, packageId, bindings: { conversationId, refs } });
}

// ---------------------------------------------------------------------------
// Разрешение состояния пакета по заявкам источников.
//   index      — явное действие пользователя через реестр
//   filesystem — SKILL.md vs SKILL.md.disabled
//   config.json / mcp_config.json / settings.json — конфигурационные файлы
//   cli        — agy plugin list / agy mcp list (наблюдение)
// Расхождение заявок (true vs false) → drift; известный пакет без записи в
// авторитетном хранилище → registrationGap. В обоих случаях resolvedEnabled
// остаётся неизвестным (null): карточка не показывает ложное «включено».
// ---------------------------------------------------------------------------

function resolvePackageState(claims, { authoritativeRecorded = true } = {}) {
    const claimTable = [];
    const values = [];
    for (const [source, claim] of Object.entries(claims || {})) {
        if (claim === true || claim === false) {
            claimTable.push({ source, claim });
            values.push(claim);
        } else if (typeof claim === 'string') {
            claimTable.push({ source, claim });
        } else {
            claimTable.push({ source, claim: 'не записано' });
        }
    }
    const drift = values.includes(true) && values.includes(false);
    const registrationGap = authoritativeRecorded === false;
    let resolvedEnabled = null;
    let displayState = 'требует сверки состояния';
    if (!drift && !registrationGap && values.length > 0) {
        resolvedEnabled = values[0];
        displayState = resolvedEnabled ? 'включён' : 'отключён';
    }
    return { resolvedEnabled, drift, registrationGap, displayState, claimTable };
}

// ---------------------------------------------------------------------------
// Слияние позиций инвентаря (Этап 1): каталог-позиции остаются позициями
// наблюдения (дампы/строки APK), а не установленными расширениями.
// Разделение продуктов (блокер codex-remote-composer-extensions) сохраняется.
// ---------------------------------------------------------------------------

function mergeCatalogPositions(inventory) {
    const positions = inventory && Array.isArray(inventory.positions) ? inventory.positions : [];
    return positions.filter(p => !p.canonicalId || p.canonicalId === p.id).map(p => {
        const separation = formats.classifySourceProduct({
            sourceId: p.sourceId || '',
            name: p.name || '',
            description: p.blocker || '',
            declaredProduct: p.sourceProduct || ''
        });
        return {
            id: `catalog:${p.id}`,
            name: p.name,
            kind: 'catalog-position',
            sourceProduct: separation.resolvedProduct,
            declaredProduct: p.sourceProduct,
            sourceId: p.sourceId,
            publisher: p.publisher,
            sourceUrl: p.sourceUrl,
            version: p.version,
            license: p.license,
            componentTypes: p.componentTypes || [],
            dependencies: p.dependencies || [],
            auth: p.auth,
            executionTargets: p.executionTargets,
            availability: p.availability,
            observationType: p.observationType,
            implementationStatus: p.implementationStatus,
            blocker: p.blocker,
            evidence: p.evidence || [],
            productSeparation: { hinted: separation.hintedProduct, conflict: separation.conflict, note: separation.note },
            state: { resolvedEnabled: null, drift: false, registrationGap: false, displayState: 'каталог (не установлено)', claimTable: [] }
        };
    });
}

// ---------------------------------------------------------------------------
// Просмотр состава пакета ДО установки (план: «просмотр состава до установки»).
// Источник: каталог или ZIP-архив. Проверяются пути архива, размер, хэш,
// коллизии имён и наличие исполняемых компонентов.
// ---------------------------------------------------------------------------

function stripSingleRoot(entries) {
    const tops = new Set(entries.map(e => e.path.split('/')[0]));
    if (tops.size === 1) {
        const root = [...tops][0];
        const allUnder = entries.every(e => e.path.startsWith(`${root}/`));
        if (allUnder && root && !entries.some(e => e.path === root)) {
            return {
                rootName: root,
                entries: entries.map(e => Object.assign({}, e, { path: e.path.slice(root.length + 1) }))
            };
        }
    }
    return { rootName: null, entries };
}

function classifyVirtualPath(relPath) {
    const top = relPath.split('/')[0];
    if (relPath === 'plugin.json' || top === '.claude-plugin' || top === '.codex-plugin') return 'manifest';
    if (relPath === '.mcp.json' || relPath === 'mcp.json') return 'mcp-server';
    if (relPath === '.lsp.json') return 'lsp-server';
    if (top === 'hooks') return 'hook';
    if (relPath === '.app.json') return 'app';
    if (relPath === 'settings.json' || relPath === 'CLAUDE.md') return 'settings';
    if (top === 'skills') return 'skill';
    if (top === 'commands') return 'command';
    if (top === 'agents') return 'agent';
    if (top === 'output-styles') return 'output-style';
    if (top === 'workflows') return 'workflow';
    if (top === 'themes') return 'theme';
    if (top === 'monitors') return 'monitor';
    if (top === 'bin' || top === 'scripts') return 'executable';
    if (top === 'assets') return 'asset';
    if (/^SKILL\.md(\.disabled)?$/i.test(relPath)) return 'skill';
    return 'unknown';
}

function analyzeVirtualPackage(entries, rootName, getEntryData = e => e.data) {
    const readText = e => getEntryData(e).toString('utf8');
    const manifestEntry = entries.find(e => e.path === '.claude-plugin/plugin.json')
        || entries.find(e => e.path === 'plugin.json')
        || entries.find(e => e.path === '.codex-plugin/plugin.json');
    const skillMdEntry = entries.find(e => /^SKILL\.md(\.disabled)?$/i.test(e.path));
    const result = {
        rootName,
        manifest: null,
        packageKind: 'plugin',
        skills: [],
        pluginSkillLinks: [],
        mcpServers: [],
        unknownComponents: [],
        compositionStatus: 'требует проверки'
    };
    if (skillMdEntry && !manifestEntry) result.packageKind = 'skill';
    if (manifestEntry) {
        try {
            const raw = JSON.parse(readText(manifestEntry));
            result.manifest = formats.normalizePluginManifest(raw, {
                manifestPath: manifestEntry.path,
                hasSkillsDir: entries.some(e => e.path.startsWith('skills/') && /SKILL\.md(\.disabled)?$/i.test(e.path)),
                compositionScanned: true
            });
        } catch (err) {
            result.unknownComponents.push({ path: manifestEntry.path, status: 'требует проверки', reason: `manifest не разобран: ${err.message}` });
        }
    }
    for (const e of entries) {
        const kind = classifyVirtualPath(e.path);
        if (kind === 'unknown') {
            result.unknownComponents.push({ path: e.path, status: 'требует проверки', reason: 'файл не отнесён к известным видам компонентов' });
        }
        if (kind === 'skill' && /(^|\/)SKILL\.md(\.disabled)?$/i.test(e.path)) {
            const dirRel = e.path.split('/').slice(0, -1).join('/');
            const owner = (result.manifest && result.manifest.name) || rootName;
            if (!result.pluginSkillLinks.some(l => l.skillPath === dirRel)) {
                result.pluginSkillLinks.push({ plugin: owner, skillPath: dirRel, owned: true });
            }
        }
    }
    // Состав навыков: front matter SKILL.md разбирается прямо из данных архива.
    for (const link of result.pluginSkillLinks) {
        const prefix = link.skillPath ? `${link.skillPath}/` : '';
        const mdPath = `${prefix}SKILL.md`;
        const mdDisabled = `${prefix}SKILL.md.disabled`;
        const md = entries.find(e => e.path === mdPath) || entries.find(e => e.path === mdDisabled);
        if (!md) continue;
        const skillFiles = entries.filter(e => e.path.startsWith(prefix) && !e.path.slice(prefix.length).includes('/'));
        const parsed = formats.parseSkillMarkdown(readText(md), link.skillPath || rootName);
        result.skills.push({
            relativePath: link.skillPath,
            ownerPlugin: link.plugin,
            enabled: Boolean(entries.find(e => e.path === mdPath)),
            skill: parsed,
            files: skillFiles.map(f => ({ path: f.path, size: f.size, sha256: f.sha256, executable: f.executable })),
            missingReferences: formats.findFileReferences(parsed.instruction)
                .filter(ref => !entries.some(f => f.path === ref || f.path === `${ref}.disabled`))
        });
    }
    for (const e of entries) {
        if (e.path === '.mcp.json' || e.path === 'mcp.json') {
            try {
                const cfg = JSON.parse(readText(e));
                result.mcpServers.push(...formats.parseMcpConfig(cfg).servers);
            } catch (err) {
                result.unknownComponents.push({ path: e.path, status: 'требует проверки', reason: `mcp-конфиг не разобран: ${err.message}` });
            }
        }
    }
    result.compositionStatus = result.unknownComponents.length > 0
        ? 'частичное: есть компоненты «требует проверки»'
        : ((result.manifest || result.skills.length > 0) ? 'подтверждено сканированием' : 'требует проверки');
    return result;
}

function readSourceEntries({ sourcePath, kind = 'auto', buffer = null, limits = {} }) {
    if (buffer || kind === 'zip') {
        // QA/Security, Medium #3: размер архива проверяется ДО чтения файла в
        // память (64 МБ) — гигантский файл не вызывает OOM и не блокирует loop.
        const MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
        let buf = buffer;
        if (!buf) {
            const st = fs.statSync(sourcePath);
            if (st.size > MAX_ARCHIVE_BYTES) {
                throw new Error(`архив больше лимита (${st.size} > ${MAX_ARCHIVE_BYTES}): ${sourcePath}`);
            }
            buf = fs.readFileSync(sourcePath);
        }
        const zip = archive.readZipEntries(buf, limits);
        const verified = archive.verifyArchiveEntries(zip.entries, limits);
        const stripped = stripSingleRoot(verified.entries);
        return {
            sourceKind: 'zip',
            entries: stripped.entries,
            rootName: stripped.rootName || (sourcePath ? path.basename(String(sourcePath).replace(/\.[Zz][Ii][Pp]$/, '')) : 'package'),
            pathIssues: verified.pathIssues,
            nameCollisions: verified.nameCollisions,
            executableComponents: verified.executableComponents,
            walkIssues: [],
            getEntryData: e => e.data
        };
    }
    const abs = path.resolve(sourcePath);
    if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) {
        throw new Error(`каталог не найден: ${abs}`);
    }
    const walked = formats.walkFiles(abs, limits);
    return {
        sourceKind: 'dir',
        entries: walked.files.map(f => ({ path: f.path, size: f.size, sha256: f.sha256, executable: f.executable, data: null })),
        rootName: path.basename(abs),
        pathIssues: [],
        nameCollisions: [],
        executableComponents: walked.files.filter(f => f.executable).map(f => ({ path: f.path, reason: f.executableReason || 'по расширению', sha256: f.sha256 })),
        walkIssues: walked.issues,
        getEntryData: e => fs.readFileSync(path.join(abs, ...String(e.path).split('/')))
    };
}

function inspectPackageSource({ sourcePath, kind = 'auto', buffer = null, limits = {} }) {
    let src;
    try {
        src = readSourceEntries({ sourcePath, kind, buffer, limits });
    } catch (err) {
        return { ok: false, sourceKind: kind === 'zip' ? 'zip' : 'dir', verdict: { readyForInstall: false, blockers: [err.message] } };
    }
    const composition = analyzeVirtualPackage(src.entries, src.rootName, src.getEntryData);
    const files = src.entries.map(e => ({ path: e.path, size: e.size, sha256: e.sha256, executable: e.executable }));
    const packageDigest = formats.packageDigestOf(files);
    const blockers = [];
    blockers.push(...src.pathIssues);
    blockers.push(...src.nameCollisions.map(c => `коллизия имён в архиве: ${c.first} / ${c.second}`));
    blockers.push(...src.walkIssues);
    if (!composition.manifest && composition.skills.length === 0) {
        blockers.push('не найден ни manifest плагина, ни SKILL.md — состав пакета не определён');
    }
    return {
        ok: blockers.length === 0,
        sourceKind: src.sourceKind,
        rootName: composition.rootName || src.rootName,
        packageKind: composition.packageKind,
        files,
        fileCount: files.length,
        totalBytes: files.reduce((s, f) => s + f.size, 0),
        packageDigest,
        pathIssues: src.pathIssues,
        nameCollisions: src.nameCollisions,
        executableComponents: src.executableComponents,
        manifest: composition.manifest,
        compositionStatus: composition.compositionStatus,
        skills: composition.skills,
        pluginSkillLinks: composition.pluginSkillLinks,
        mcpServers: composition.mcpServers,
        unknownComponents: composition.unknownComponents,
        verdict: { readyForInstall: blockers.length === 0, blockers }
    };
}

// ---------------------------------------------------------------------------
// Импорт пакета: просмотр → проверка → установка в целевой каталог.
// Соседние пакеты не затрагиваются; коллизии имён обрабатываются явно.
// ---------------------------------------------------------------------------

function importPackage({ sourcePath, kind = 'auto', buffer = null, targetRoot, registryIndexPath, onCollision = 'refuse', sourceProduct = 'Antigravity local', publisher = 'локальный импорт', sourceUrl = null, limits = {} }) {
    const preview = inspectPackageSource({ sourcePath, kind, buffer, limits });
    if (!preview.ok) {
        return { ok: false, error: `Импорт отклонён: ${preview.verdict.blockers.join('; ')}`, preview };
    }
    // Этап 8: при повреждении/отсутствии индекса соседние пакеты того же корня
    // пересобираются по диску до записи — импорт не теряет установленное.
    const index = loadRegistryIndex(registryIndexPath, { targetRoots: [targetRoot] });
    const pkgKind = preview.packageKind === 'skill' ? 'skill' : 'plugin';
    // Идентичность: manifest плагина → имя навыка из front matter (спека Agent
    // Skills) → имя каталога. Никаких выдуманных имён. QA/Security, Critical #1:
    // объявленное в manifest.name пустое имя — это ОШИБКА валидации, а не
    // повод молча подменить его другим источником.
    const frontMatterName = preview.skills[0] && preview.skills[0].skill ? preview.skills[0].skill.name : null;
    const manifestDeclaredName = preview.manifest && typeof preview.manifest.name === 'string'
        ? preview.manifest.name
        : null;
    const rawName = manifestDeclaredName !== null
        ? manifestDeclaredName
        : ((pkgKind === 'skill' ? frontMatterName : null) || preview.rootName || 'package');
    // QA/Security, Critical #1: жёсткая валидация имени до каких-либо
    // файловых операций — имя попадает в пути и в id реестра.
    const nameCheck = validatePackageName(rawName);
    if (!nameCheck.valid) {
        return { ok: false, error: `Импорт отклонён: имя пакета не прошло валидацию: ${nameCheck.issues.join('; ')}`, preview };
    }
    const name = nameCheck.name;
    const id = `${pkgKind}:${name}`;
    fs.mkdirSync(targetRoot, { recursive: true });
    const existsOnDisk = fs.existsSync(path.join(targetRoot, name));
    const existsInIndex = Boolean(index.packages[id]);
    if ((existsOnDisk || existsInIndex) && onCollision === 'refuse') {
        return {
            ok: false,
            error: `Коллизия имён: «${name}» уже существует (диск: ${existsOnDisk}, реестр: ${existsInIndex}). Укажите onCollision: 'replace' или 'rename'.`,
            preview
        };
    }
    let finalName = name;
    if ((existsOnDisk || existsInIndex) && onCollision === 'rename') {
        let n = 2;
        while (fs.existsSync(path.join(targetRoot, `${name}-${n}`)) || index.packages[`${pkgKind}:${name}-${n}`]) n++;
        finalName = `${name}-${n}`;
    }
    const finalDir = path.join(targetRoot, finalName);

    // QA/Security, Medium #4: распаковка идёт во временный staging-каталог и
    // подключается rename-ом только после успешной записи всех файлов; прерывание
    // не оставляет полузаписанного пакета и не трогает существующий каталог.
    const stagingDir = path.join(targetRoot, `.staging-${finalName}-${process.pid}-${Date.now()}`);
    fs.mkdirSync(stagingDir, { recursive: true });
    let src;
    try {
        src = readSourceEntries({ sourcePath, kind, buffer, limits });
        const byPath = new Map(src.entries.map(e => [e.path, e]));
        const written = [];
        for (const f of preview.files) {
            const dest = path.join(stagingDir, ...f.path.split('/'));
            const resolvedDest = path.resolve(dest);
            if (!resolvedDest.startsWith(path.resolve(stagingDir))) {
                throw new Error(`путь выходит за корень пакета: ${f.path}`);
            }
            fs.mkdirSync(path.dirname(resolvedDest), { recursive: true });
            const entry = byPath.get(f.path);
            const data = entry && entry.data ? entry.data : fs.readFileSync(path.resolve(sourcePath, ...f.path.split('/')));
            fs.writeFileSync(resolvedDest, data);
            written.push(f.path);
        }
        if (onCollision === 'replace' && (existsOnDisk || existsInIndex)) {
            // Замена только собственного каталога пакета; соседи не затрагиваются.
            if (existsOnDisk) fs.rmSync(finalDir, { recursive: true, force: true });
        }
        fs.renameSync(stagingDir, finalDir);
        var writtenFiles = written;
    } catch (err) {
        // Полный откат staging-каталога: полузаписанный пакет не остаётся на диске.
        try { fs.rmSync(stagingDir, { recursive: true, force: true }); } catch (_) {}
        return { ok: false, error: `Импорт отклонён: запись пакета прервана: ${err.message}`, preview };
    }

    const entry = {
        id: `${pkgKind}:${finalName}`,
        name: finalName,
        kind: pkgKind,
        sourceProduct,
        publisher,
        sourceUrl,
        version: preview.manifest && preview.manifest.version
            ? preview.manifest.version
            : (preview.skills[0] && preview.skills[0].skill && preview.skills[0].skill.metadata
                ? (preview.skills[0].skill.metadata.version || null)
                : null),
        license: preview.manifest && preview.manifest.license ? preview.manifest.license : null,
        importPath: finalDir,
        importedAt: new Date().toISOString(),
        packageDigest: preview.packageDigest,
        files: preview.files,
        compositionStatus: preview.compositionStatus,
        skills: preview.skills.map(s => ({ relativePath: s.relativePath, ownerPlugin: s.ownerPlugin, name: s.skill ? s.skill.name : null })),
        pluginSkillLinks: preview.pluginSkillLinks,
        unknownComponents: preview.unknownComponents,
        executableComponents: preview.executableComponents,
        enabledOverride: null
    };
    index.packages[entry.id] = entry;
    saveRegistryIndex(registryIndexPath, index);
    return {
        ok: true,
        entry,
        writtenFiles,
        preview,
        message: `Пакет «${finalName}» импортирован (${writtenFiles.length} файлов). Состав: ${preview.compositionStatus}.`
    };
}

// ---------------------------------------------------------------------------
// Жизненный цикл: отключение/включение и удаление без потери соседей.
// ---------------------------------------------------------------------------

function setPackageEnabled({ packageId, enabled, registryIndexPath, configPath = null, mcpConfigPath = null, settingsPath = null, cli = null, userStatePath = null }) {
    const index = loadRegistryIndex(registryIndexPath);
    const entry = index.packages[packageId];
    if (!entry) return { ok: false, error: `Пакет не найден в реестре: ${packageId}` };
    entry.enabledOverride = Boolean(enabled);
    index.packages[packageId] = entry;
    saveRegistryIndex(registryIndexPath, index);
    // Этап 8: включённость дублируется в пользовательское состояние — переживает
    // перезапуск и пересборку индекса по диску (отключения не теряются).
    if (userStatePath) {
        setUserState({ statePath: userStatePath, packageId, enabled: Boolean(enabled) });
    }

    const applied = [];
    const errors = [];
    if (entry.kind === 'skill' && entry.importPath) {
        const activeFile = path.join(entry.importPath, 'SKILL.md');
        const disabledFile = path.join(entry.importPath, 'SKILL.md.disabled');
        try {
            if (enabled && fs.existsSync(disabledFile) && !fs.existsSync(activeFile)) {
                fs.renameSync(disabledFile, activeFile);
                applied.push('SKILL.md.disabled → SKILL.md');
            } else if (!enabled && fs.existsSync(activeFile)) {
                fs.renameSync(activeFile, disabledFile);
                applied.push('SKILL.md → SKILL.md.disabled');
            }
        } catch (err) {
            errors.push(`файловое состояние навыка: ${err.message}`);
        }
    }
    if (entry.kind === 'plugin') {
        if (configPath) {
            try {
                const cfg = fs.existsSync(configPath) ? JSON.parse(fs.readFileSync(configPath, 'utf8')) : {};
                cfg.plugins = cfg.plugins || {};
                cfg.plugins[entry.name] = Object.assign({}, cfg.plugins[entry.name], { enabled: Boolean(enabled) });
                fs.mkdirSync(path.dirname(configPath), { recursive: true });
                fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2), 'utf8');
                applied.push('config.json');
            } catch (err) {
                errors.push(`config.json: ${err.message}`);
            }
        }
        if (cli) {
            applied.push(`ожидает вызова agy plugin ${enabled ? 'enable' : 'disable'} ${entry.name}`);
        }
    }
    if (entry.kind === 'mcp-server' && mcpConfigPath) {
        try {
            const cfg = fs.existsSync(mcpConfigPath) ? JSON.parse(fs.readFileSync(mcpConfigPath, 'utf8')) : { mcpServers: {} };
            cfg.mcpServers = cfg.mcpServers || {};
            if (cfg.mcpServers[entry.name]) {
                cfg.mcpServers[entry.name].disabled = !enabled;
                fs.writeFileSync(mcpConfigPath, JSON.stringify(cfg, null, 2), 'utf8');
                applied.push('mcp_config.json');
            } else {
                errors.push(`mcp_config.json: сервер «${entry.name}» не записан — требуется регистрация`);
            }
        } catch (err) {
            errors.push(`mcp_config.json: ${err.message}`);
        }
        if (settingsPath) {
            try {
                const settings = fs.existsSync(settingsPath) ? JSON.parse(fs.readFileSync(settingsPath, 'utf8')) : {};
                settings.mcpServers = settings.mcpServers || {};
                if (settings.mcpServers[entry.name]) {
                    settings.mcpServers[entry.name].disabled = !enabled;
                    fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2), 'utf8');
                    applied.push('settings.json');
                }
            } catch (err) {
                errors.push(`settings.json: ${err.message}`);
            }
        }
    }
    return {
        ok: errors.length === 0,
        entry,
        applied,
        errors,
        message: errors.length === 0
            ? `Состояние «${entry.name}» записано: ${applied.join(', ') || 'только индекс реестра'}`
            : `Частично записано (${applied.join(', ')}); ошибки: ${errors.join('; ')}`
    };
}

function removePackage({ packageId, registryIndexPath, keepFiles = false }) {
    const index = loadRegistryIndex(registryIndexPath);
    const entry = index.packages[packageId];
    if (!entry) return { ok: false, error: `Пакет не найден в реестре: ${packageId}` };
    const removedFiles = [];
    if (!keepFiles && entry.importPath && fs.existsSync(entry.importPath)) {
        const root = path.resolve(entry.importPath);
        for (const f of entry.files || []) {
            const target = path.resolve(root, ...String(f.path).split('/'));
            if (!target.startsWith(root)) continue; // защита от выхода за каталог пакета
            if (fs.existsSync(target)) {
                fs.unlinkSync(target);
                removedFiles.push(f.path);
            }
        }
        // Удаление только пустых каталогов пакета; соседи не затрагиваются.
        const dirs = [];
        const stack = [root];
        while (stack.length) {
            const dir = stack.pop();
            dirs.push(dir);
            for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
                if (e.isDirectory()) stack.push(path.join(dir, e.name));
            }
        }
        dirs.sort((a, b) => b.length - a.length);
        for (const dir of dirs) {
            if (dir === root) continue;
            try {
                if (fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
            } catch (_) { /* не пустой — остаётся */ }
        }
        try {
            if (fs.readdirSync(root).length === 0) fs.rmdirSync(root);
        } catch (_) { /* не пустой — остаётся */ }
    }
    delete index.packages[packageId];
    saveRegistryIndex(registryIndexPath, index);
    return { ok: true, entry, removedFiles, message: `Пакет «${entry.name}» удалён из реестра (файлов удалено: ${removedFiles.length}).` };
}

// ---------------------------------------------------------------------------
// Экспорт собственного набора: переносимый архив с manifest и хэшами.
// Секреты (env-значения MCP, токены) не включаются.
// ---------------------------------------------------------------------------

function exportOwnSet({ registryIndexPath, outPath, packageIds = null }) {
    const index = loadRegistryIndex(registryIndexPath);
    const ids = packageIds || Object.keys(index.packages);
    const items = [];
    const manifestPackages = [];
    for (const id of ids) {
        const entry = index.packages[id];
        if (!entry) continue;
        const files = [];
        for (const f of entry.files || []) {
            const abs = path.join(entry.importPath, ...String(f.path).split('/'));
            if (!fs.existsSync(abs)) continue;
            const data = fs.readFileSync(abs);
            const zipPath = `${entry.kind === 'skill' ? 'skills' : 'plugins'}/${entry.name}/${f.path}`;
            items.push({ name: zipPath, data });
            files.push({ path: f.path, size: data.length, sha256: sha256Of(data) });
        }
        manifestPackages.push({
            id: entry.id,
            name: entry.name,
            kind: entry.kind,
            sourceProduct: entry.sourceProduct,
            publisher: entry.publisher,
            version: entry.version,
            license: entry.license,
            packageDigest: entry.packageDigest,
            compositionStatus: entry.compositionStatus,
            pluginSkillLinks: entry.pluginSkillLinks || [],
            files
        });
    }
    const exportManifest = {
        exportedAt: new Date().toISOString(),
        exporter: 'antigravity-pc-bridge',
        registryIndexVersion: REGISTRY_INDEX_VERSION,
        secretsExcluded: true,
        packages: manifestPackages
    };
    items.push({ name: 'export-manifest.json', data: Buffer.from(JSON.stringify(exportManifest, null, 2), 'utf8') });
    const buf = archive.writeZipEntries(items);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, buf);
    return { ok: true, outPath, bytes: buf.length, packages: manifestPackages.length, exportManifest };
}

// ---------------------------------------------------------------------------
// Этап 8. Заявленные права и зависимости пакета (сравнение при обновлении).
//
// Права берутся только из реальных полей: allowed-tools навыков, ключи
// userConfig манифеста (только имена, значения никогда не читаются), hooks,
// MCP-серверы и исполняемые скрипты. Зависимости: объявленные в manifest и
// реальные require/import скриптов. Ничего не угадывается.
// ---------------------------------------------------------------------------

function collectDeclaredPermissions(desc, executableComponents = []) {
    const perms = new Set();
    for (const s of (desc && desc.skills) || []) {
        const tools = s.skill && Array.isArray(s.skill.allowedTools) ? s.skill.allowedTools : [];
        for (const t of tools) perms.add(`allowed-tool:${t}`);
    }
    const manifest = desc && desc.manifest;
    if (manifest && Array.isArray(manifest.userConfigKeys)) {
        for (const k of manifest.userConfigKeys) perms.add(`user-config:${k.key}`);
    }
    if (manifest && manifest.declaredComponents && manifest.declaredComponents.hooks) perms.add('hooks:command');
    for (const s of (desc && desc.mcpServers) || []) perms.add(`mcp-server:${s.name}`);
    for (const e of executableComponents || []) perms.add(`script:${e.path}`);
    return [...perms].sort();
}

function collectDeclaredDependencies(desc, src = null) {
    const deps = new Set();
    const manifest = desc && desc.manifest;
    for (const d of (manifest && manifest.dependencies) || []) {
        deps.add(d.version ? `${d.name}@${d.version}` : String(d.name));
    }
    for (const e of (src && src.entries) || []) {
        if (!e.executable) continue;
        let text = '';
        try { text = src.getEntryData(e).toString('utf8'); } catch (_) { continue; }
        if (/\.(js|mjs|cjs)$/i.test(e.path)) {
            for (const m of scriptRunner.scanNodeExternals(text)) deps.add(`node-module:${m}`);
        } else if (/\.py$/i.test(e.path)) {
            for (const m of scriptRunner.scanPythonImports(text)) deps.add(`python-module:${m}`);
        }
    }
    return [...deps].sort();
}

function diffPackageDeclarations(before, after) {
    const diffSets = (a, b) => ({
        added: b.filter(x => !a.includes(x)).sort(),
        removed: a.filter(x => !b.includes(x)).sort()
    });
    return {
        permissions: diffSets(before.permissions || [], after.permissions || []),
        dependencies: diffSets(before.dependencies || [], after.dependencies || [])
    };
}

// ---------------------------------------------------------------------------
// Этап 8. Проверка установленного кандидата: манифест, целостность, схема и
// зависимости. Выполняется ПОСЛЕ замены под backup — провал любого пункта
// автоматически откатывает к предыдущей рабочей версии (см. updatePackage).
// ---------------------------------------------------------------------------

function validateInstalledUpdate({ installDir, stagedFiles, packageKind, expectedName }) {
    const failures = { manifest: [], integrity: [], schema: [], dependencies: [] };
    const notes = [];

    // Целостность: каждый записанный файл на месте и совпадает по sha256.
    for (const f of stagedFiles) {
        const abs = path.join(installDir, ...String(f.path).split('/'));
        if (!fs.existsSync(abs)) {
            failures.integrity.push(`файл отсутствует после установки: ${f.path}`);
            continue;
        }
        const data = fs.readFileSync(abs);
        if (sha256Of(data) !== f.sha256) failures.integrity.push(`хэш файла не совпал после установки: ${f.path}`);
    }

    // Манифест и схема — по виду пакета, из установленного каталога.
    const manifestRel = ['plugin.json', '.claude-plugin/plugin.json', '.codex-plugin/plugin.json']
        .find(rel => fs.existsSync(path.join(installDir, ...rel.split('/')))) || null;
    const skillRel = fs.existsSync(path.join(installDir, 'SKILL.md')) ? 'SKILL.md'
        : (fs.existsSync(path.join(installDir, 'SKILL.md.disabled')) ? 'SKILL.md.disabled' : null);
    let declaredName = null;
    let version = null;
    let license = null;
    if (packageKind === 'plugin') {
        if (!manifestRel) {
            failures.manifest.push('manifest плагина не найден после установки');
        } else {
            let raw = null;
            try {
                raw = JSON.parse(fs.readFileSync(path.join(installDir, ...manifestRel.split('/')), 'utf8'));
            } catch (err) {
                failures.manifest.push(`manifest не разбирается: ${err.message}`);
            }
            if (raw) {
                const norm = formats.normalizePluginManifest(raw, {
                    manifestPath: manifestRel,
                    hasSkillsDir: stagedFiles.some(f => /(^|\/)skills\/[^/]+\/SKILL\.md(\.disabled)?$/i.test(f.path)),
                    compositionScanned: true
                });
                declaredName = norm.name;
                version = norm.version;
                license = norm.license;
                // parseIssues включает и манифест, и схему (userConfig/dependencies).
                failures.manifest.push(...norm.parseIssues);
                if (norm.name != null && expectedName && norm.name !== expectedName) {
                    failures.manifest.push(`manifest объявляет другое имя: «${norm.name}» ≠ «${expectedName}»`);
                }
                if (norm.name != null) {
                    const check = validatePackageName(norm.name);
                    if (!check.valid) failures.schema.push(...check.issues);
                }
            }
        }
    } else if (!skillRel) {
        failures.manifest.push('SKILL.md не найден после установки');
    }

    // Навыки (одиночные и внутри плагина): front matter и ссылки тела.
    const skillMdPaths = [];
    if (skillRel) skillMdPaths.push(skillRel);
    for (const f of stagedFiles) {
        if (/(^|\/)skills\/[^/]+\/SKILL\.md(\.disabled)?$/i.test(f.path)) skillMdPaths.push(f.path);
    }
    for (const rel of skillMdPaths) {
        const text = fs.readFileSync(path.join(installDir, ...rel.split('/')), 'utf8');
        const parsed = formats.parseSkillMarkdown(text, expectedName || path.basename(path.dirname(rel)));
        if (parsed.parseIssues.length > 0) failures.schema.push(`${rel}: ${parsed.parseIssues.join('; ')}`);
        if (!parsed.specValidation.valid) {
            notes.push(`${rel}: отступления от спеки Agent Skills: ${parsed.specValidation.issues.map(i => `${i.field} — ${i.problem}`).join('; ')}`);
        }
        const missingRefs = formats.findFileReferences(parsed.instruction).filter(ref =>
            !fs.existsSync(path.join(installDir, ...String(ref).split('/')))
            && !fs.existsSync(path.join(installDir, ...`${ref}.disabled`.split('/'))));
        if (missingRefs.length > 0) failures.integrity.push(`${rel}: битые ссылки: ${missingRefs.join(', ')}`);
        if (packageKind === 'skill') {
            if (parsed.nameSource === 'frontmatter') declaredName = parsed.name;
            version = (parsed.metadata && parsed.metadata.version) ? String(parsed.metadata.version) : version;
            license = parsed.license || license;
            if (parsed.nameSource === 'frontmatter' && expectedName && parsed.name && parsed.name !== expectedName) {
                failures.manifest.push(`SKILL.md объявляет другое имя: «${parsed.name}» ≠ «${expectedName}»`);
            }
        }
    }

    // Зависимости скриптов: недостающие node-модули — жёсткий провал;
    // недоступный интерпретатор — честная запись, не догадка.
    for (const f of stagedFiles) {
        if (!f.executable || !/\.(js|mjs|cjs)$/i.test(f.path)) continue;
        const abs = path.join(installDir, ...String(f.path).split('/'));
        const deps = scriptRunner.checkScriptDependencies({ scriptPath: abs, content: null, packageRoot: installDir });
        if (deps.missing.length > 0) {
            failures.dependencies.push(`${f.path}: недостающие зависимости: ${deps.missing.join(', ')}`);
        }
        if (deps.interpreter && deps.interpreter.available === false) {
            notes.push(`${f.path}: интерпретатор недоступен (${deps.interpreter.probeError || deps.interpreter.kind}) — исполнение не подтверждено`);
        }
    }

    const ok = Object.values(failures).every(arr => arr.length === 0);
    return { ok, failures, notes, declaredName, version, license };
}

// ---------------------------------------------------------------------------
// Этап 8. Обновление пакета с откатом.
//
// Порядок: структурная безопасность источника → staging → прежняя версия уходит
// во временный backup → замена → проверка манифеста/целостности/схемы/
// зависимостей установленного кандидата → успех фиксируется в индексе (backup
// остаётся точкой отката), провал автоматически откатывает к backup.
// Пользовательские настройки (enabled/разрешения/привязки) сохраняются; новые
// права не выдаются автоматически — попадают в «запрошено» до явного действия.
// Сетевых и модельных вызовов операция не выполняет.
// ---------------------------------------------------------------------------

function updatePackage({ packageId, sourcePath, kind = 'auto', buffer = null, registryIndexPath, userStatePath = null, targetRoot = null, limits = {}, sourceUrl = undefined }) {
    const index = loadRegistryIndex(registryIndexPath, {
        targetRoots: [targetRoot].filter(Boolean),
        userStatePath
    });
    const entry = index.packages[packageId];
    if (!entry) return { ok: false, stage: 'rejected', error: `Пакет не найден в реестре: ${packageId}` };
    if (entry.kind !== 'skill' && entry.kind !== 'plugin') {
        return { ok: false, stage: 'rejected', error: `Обновление через реестр доступно для пакетов skill/plugin; «${entry.name}» имеет вид ${entry.kind}` };
    }
    const currentDir = entry.importPath ? path.resolve(entry.importPath) : null;
    if (!currentDir || !fs.existsSync(currentDir)) {
        return {
            ok: false,
            stage: 'rejected',
            error: `Файлы текущей версии отсутствуют (${entry.importPath}) — требуется переустановка, не обновление`
        };
    }
    const parentDir = path.dirname(currentDir);

    // --- До замены: структурная безопасность источника (пути архива, размеры,
    //     коллизии имён). Рабочая версия не затрагивается.
    let src;
    try {
        src = readSourceEntries({ sourcePath, kind, buffer, limits });
    } catch (err) {
        return { ok: false, stage: 'rejected-before-swap', currentPreserved: true, error: `Обновление отклонено до замены: ${err.message}` };
    }
    const structural = [...src.pathIssues, ...src.nameCollisions.map(c => `коллизия имён: ${c.first} / ${c.second}`)];
    if (structural.length > 0) {
        return {
            ok: false,
            stage: 'rejected-before-swap',
            currentPreserved: true,
            error: `Обновление отклонено до замены: ${structural.join('; ')}`
        };
    }

    // --- Описание кандидата и заявленные права/зависимости (для сравнения).
    const candidate = analyzeVirtualPackage(src.entries, src.rootName, src.getEntryData);
    const candidatePerms = collectDeclaredPermissions(candidate, src.executableComponents);
    const candidateDeps = collectDeclaredDependencies(candidate, src);

    // --- Заявленные права/зависимости прежней версии — честное сравнение.
    let changes = null;
    try {
        const prevSrc = readSourceEntries({ sourcePath: currentDir, kind: 'dir' });
        const prevDesc = analyzeVirtualPackage(prevSrc.entries, path.basename(currentDir), prevSrc.getEntryData);
        changes = diffPackageDeclarations(
            {
                permissions: collectDeclaredPermissions(prevDesc, prevSrc.executableComponents),
                dependencies: collectDeclaredDependencies(prevDesc, prevSrc)
            },
            { permissions: candidatePerms, dependencies: candidateDeps }
        );
    } catch (_) { /* прежняя версия не описалась — сравнение честно отсутствует */ }

    // --- Staging: запись кандидата со хэшами фактически записанных байт.
    const stamp = `${process.pid}-${Date.now()}`;
    const stagingDir = path.join(parentDir, `.staging-update-${entry.name}-${stamp}`);
    const backupDir = path.join(parentDir, `.backup-${entry.name}-${stamp}`);
    fs.mkdirSync(stagingDir, { recursive: true });
    let stagedFiles;
    try {
        stagedFiles = [];
        for (const e of src.entries) {
            const dest = path.join(stagingDir, ...String(e.path).split('/'));
            const resolvedDest = path.resolve(dest);
            if (!resolvedDest.startsWith(path.resolve(stagingDir))) {
                throw new Error(`путь выходит за корень пакета: ${e.path}`);
            }
            fs.mkdirSync(path.dirname(resolvedDest), { recursive: true });
            const data = e.data ? e.data : fs.readFileSync(path.resolve(sourcePath, ...String(e.path).split('/')));
            fs.writeFileSync(resolvedDest, data);
            stagedFiles.push({ path: e.path, size: data.length, sha256: sha256Of(data), executable: Boolean(e.executable) });
        }
    } catch (err) {
        try { fs.rmSync(stagingDir, { recursive: true, force: true }); } catch (_) {}
        return {
            ok: false,
            stage: 'rejected-before-swap',
            currentPreserved: true,
            error: `Обновление отклонено до замены: запись кандидата прервана: ${err.message}`
        };
    }

    // --- Замена под backup: прежняя рабочая версия сохраняется до полного
    //     завершения валидации и замены.
    const fromVersion = entry.version || null;
    fs.renameSync(currentDir, backupDir);
    // Хэши прежней версии — по фактическому дереву backup, а не по записям
    // индекса: файловое состояние SKILL.md ↔ SKILL.md.disabled могло меняться
    // после импорта (отключение), записи индекса об этом не знают.
    let fromFiles;
    try {
        fromFiles = formats.walkFiles(backupDir).files.map(f => ({ path: f.path, size: f.size, sha256: f.sha256, executable: Boolean(f.executable) }));
    } catch (_) {
        fromFiles = (entry.files || []).map(f => ({ path: f.path, size: f.size, sha256: f.sha256, executable: Boolean(f.executable) }));
    }
    try {
        fs.renameSync(stagingDir, currentDir);
    } catch (err) {
        try { fs.renameSync(backupDir, currentDir); } catch (_) { /* остаётся backup — видно в диагностике */ }
        try { fs.rmSync(stagingDir, { recursive: true, force: true }); } catch (_) {}
        return {
            ok: false,
            stage: 'swap-failed',
            rolledBack: true,
            error: `Замена не удалась, прежняя версия восстановлена: ${err.message}`
        };
    }

    // --- Валидация установленного кандидата: манифест, целостность, схема,
    //     зависимости. Провал → автоматический откат к прежней версии.
    const validation = validateInstalledUpdate({
        installDir: currentDir,
        stagedFiles,
        packageKind: entry.kind,
        expectedName: entry.name
    });
    if (!validation.ok) {
        let rollbackError = null;
        try {
            fs.rmSync(currentDir, { recursive: true, force: true });
            fs.renameSync(backupDir, currentDir);
        } catch (err) {
            rollbackError = err.message;
        }
        try { fs.rmSync(stagingDir, { recursive: true, force: true }); } catch (_) {}
        const failedAt = new Date().toISOString();
        entry.updateHistory = (entry.updateHistory || []).concat([{
            at: failedAt,
            action: 'update-failed',
            fromVersion,
            candidateVersion: validation.version || null,
            failures: validation.failures,
            rolledBack: rollbackError ? null : true
        }]);
        index.packages[packageId] = entry;
        saveRegistryIndex(registryIndexPath, index);
        const FAILURE_LABELS = { manifest: 'манифест', integrity: 'целостность', schema: 'схема', dependencies: 'зависимости' };
        const failedParts = Object.entries(validation.failures)
            .filter(([, v]) => v.length > 0)
            .map(([k]) => FAILURE_LABELS[k] || k);
        return {
            ok: false,
            stage: 'validation-failed',
            rolledBack: rollbackError ? null : true,
            rollbackError,
            validation,
            changes,
            error: rollbackError
                ? `Обновление «${entry.name}» не прошло проверку (${failedParts.join(', ')}) и откат выполнен не полностью: ${rollbackError}`
                : `Обновление «${entry.name}» не прошло проверку (${failedParts.join(', ')}); прежняя рабочая версия ${fromVersion || 'без версии'} восстановлена автоматически`
        };
    }

    // --- Успех: новая версия фиксируется в индексе; backup остаётся точкой
    //     отката (rollbackPackage). Пользовательские настройки сохраняются.
    const now = new Date().toISOString();
    // Сохранение отключений (приёмка Этапа 8): отключённый навык остаётся
    // отключённым и после обновления — файловое состояние SKILL.md ↔
    // SKILL.md.disabled восстанавливается по пользовательскому состоянию.
    let disabledPreserved = false;
    const usSnapshot = userStatePath ? loadUserState(userStatePath) : null;
    const usEnabled = usSnapshot && usSnapshot.packages[packageId] && typeof usSnapshot.packages[packageId].enabled === 'boolean'
        ? Boolean(usSnapshot.packages[packageId].enabled)
        : !(entry.enabledOverride === false);
    if (entry.kind === 'skill' && usEnabled === false) {
        const activeMd = path.join(currentDir, 'SKILL.md');
        const inactiveMd = path.join(currentDir, 'SKILL.md.disabled');
        if (fs.existsSync(activeMd) && !fs.existsSync(inactiveMd)) {
            fs.renameSync(activeMd, inactiveMd);
            stagedFiles = stagedFiles.map(f => (f.path === 'SKILL.md' ? Object.assign({}, f, { path: 'SKILL.md.disabled' }) : f));
            disabledPreserved = true;
        }
    }
    entry.version = validation.version || null;
    entry.license = validation.license || entry.license || null;
    entry.files = stagedFiles;
    entry.packageDigest = formats.packageDigestOf(stagedFiles);
    entry.compositionStatus = candidate.compositionStatus;
    entry.skills = candidate.skills.map(s => ({ relativePath: s.relativePath, ownerPlugin: s.ownerPlugin, name: s.skill ? s.skill.name : null }));
    entry.pluginSkillLinks = candidate.pluginSkillLinks;
    entry.unknownComponents = candidate.unknownComponents;
    entry.executableComponents = src.executableComponents;
    if (sourceUrl !== undefined) entry.sourceUrl = sourceUrl;
    entry.rollback = {
        backupPath: backupDir,
        fromVersion,
        toVersion: validation.version || null,
        fromFiles,
        at: now
    };
    entry.updateHistory = (entry.updateHistory || []).concat([{
        at: now,
        action: 'update',
        fromVersion,
        toVersion: validation.version || null,
        changes
    }]);
    index.packages[packageId] = entry;
    saveRegistryIndex(registryIndexPath, index);

    let userStateNote = null;
    if (userStatePath) {
        const state = loadUserState(userStatePath);
        const ue = touchUserEntry(state, packageId);
        // Новые права не выдаются автоматически: только «запрошено».
        const newlyDeclared = changes ? changes.permissions.added : [];
        const requested = new Set(ue.permissions.requested);
        for (const p of newlyDeclared) {
            if (!ue.permissions.granted.includes(p) && !ue.permissions.denied.includes(p)) requested.add(p);
        }
        ue.permissions.requested = [...requested].sort();
        ue.updatedAt = now;
        saveUserState(userStatePath, state);
        userStateNote = {
            newlyRequestedPermissions: newlyDeclared.slice().sort(),
            grantedUnchanged: ue.permissions.granted.slice().sort(),
            enabledUnchanged: ue.enabled
        };
    }
    return {
        ok: true,
        entry,
        changes,
        validation,
        rolledBack: false,
        backupRetained: backupDir,
        disabledPreserved,
        userStateNote,
        message: `«${entry.name}» обновлён: ${fromVersion || 'без версии'} → ${validation.version || 'без версии'}. Прежняя версия сохранена (${backupDir}); пользовательские настройки сохранены.`
    };
}

// ---------------------------------------------------------------------------
// Этап 8. Откат к предыдущей рабочей версии (точка отката от updatePackage).
// Пользовательские настройки не изменяются.
// ---------------------------------------------------------------------------

function rollbackPackage({ packageId, registryIndexPath, userStatePath = null }) {
    const index = loadRegistryIndex(registryIndexPath);
    const entry = index.packages[packageId];
    if (!entry) return { ok: false, error: `Пакет не найден в реестре: ${packageId}` };
    const rb = entry.rollback;
    if (!rb || !rb.backupPath || !fs.existsSync(rb.backupPath)) {
        return {
            ok: false,
            error: `Точка отката для «${entry.name}» отсутствует: предыдущая рабочая версия не сохранена (обновлений через реестр не было)`
        };
    }
    const currentDir = path.resolve(entry.importPath);
    const parentDir = path.dirname(currentDir);
    const supersededDir = path.join(parentDir, `.superseded-${entry.name}-${process.pid}-${Date.now()}`);
    fs.renameSync(currentDir, supersededDir);
    try {
        fs.renameSync(rb.backupPath, currentDir);
    } catch (err) {
        try { fs.renameSync(supersededDir, currentDir); } catch (_) {}
        return { ok: false, error: `Откат не удался: ${err.message}; текущая версия сохранена на месте` };
    }
    // Проверка восстановленной версии по хэшам прежних файлов.
    const mismatches = [];
    for (const f of rb.fromFiles || []) {
        const abs = path.join(currentDir, ...String(f.path).split('/'));
        if (!fs.existsSync(abs)) mismatches.push(f.path);
        else if (f.sha256 && sha256Of(fs.readFileSync(abs)) !== f.sha256) mismatches.push(f.path);
    }
    if (mismatches.length > 0) {
        try {
            fs.rmSync(currentDir, { recursive: true, force: true });
            fs.renameSync(supersededDir, currentDir);
        } catch (_) {}
        return {
            ok: false,
            error: `Восстановленная версия не прошла проверку целостности (${mismatches.slice(0, 5).join(', ')}); текущая версия сохранена на месте`
        };
    }
    try { fs.rmSync(supersededDir, { recursive: true, force: true }); } catch (_) {}
    entry.version = rb.fromVersion || null;
    entry.files = rb.fromFiles || entry.files;
    entry.packageDigest = formats.packageDigestOf(entry.files || []);
    entry.updateHistory = (entry.updateHistory || []).concat([{
        at: new Date().toISOString(),
        action: 'rollback',
        fromVersion: rb.toVersion || null,
        toVersion: rb.fromVersion || null
    }]);
    delete entry.rollback; // точка отката израсходована
    index.packages[packageId] = entry;
    saveRegistryIndex(registryIndexPath, index);
    return {
        ok: true,
        entry,
        restoredVersion: rb.fromVersion || null,
        userStatePreserved: Boolean(userStatePath),
        message: `«${entry.name}»: откат к версии ${rb.fromVersion || 'без версии'} завершён. Пользовательские настройки не изменялись.`
    };
}

// ---------------------------------------------------------------------------
// Этап 8. Резервная копия: manifests/настройки/состояние БЕЗ токенов.
//
// Секреты (env/заголовки MCP, значения userConfig) не включаются никогда —
// только имена ключей и признаки авторизации. Содержимое пакетов не копируется
// (его хватает на диске; полный архив — exportOwnSet). Восстановление обязано
// явно показать, где требуется повторный вход (requiresReauth).
// ---------------------------------------------------------------------------

function createBackup({ registryIndexPath, userStatePath = null, outPath, packageIds = null, mcpConfigPath = null }) {
    const index = loadRegistryIndex(registryIndexPath);
    const userState = userStatePath ? loadUserState(userStatePath) : { version: USER_STATE_VERSION, packages: {}, recoveryLog: [] };
    const ids = packageIds || Object.keys(index.packages);
    const records = [];
    const requiresReauth = [];
    for (const id of ids) {
        const entry = index.packages[id];
        if (!entry) continue;
        const manifestFiles = [];
        for (const f of entry.files || []) {
            const isManifest = f.path === 'plugin.json' || f.path === '.claude-plugin/plugin.json' || f.path === '.codex-plugin/plugin.json';
            const isSettings = f.path === 'settings.json' || f.path === 'CLAUDE.md' || /^SKILL\.md(\.disabled)?$/i.test(f.path);
            if (!isManifest && !isSettings) continue;
            const abs = path.join(entry.importPath, ...String(f.path).split('/'));
            if (!fs.existsSync(abs)) continue;
            let text = fs.readFileSync(abs, 'utf8');
            if (/^SKILL\.md(\.disabled)?$/i.test(f.path)) {
                // SKILL.md: в копию идёт только front matter (манифест навыка);
                // тело инструкции — контент, не настройки.
                const fm = formats.parseFrontMatter(text);
                text = fm.present ? JSON.stringify(fm.data, null, 2) : '{}';
            }
            manifestFiles.push({ path: f.path, content: text });
        }
        let requiresAuth = false;
        let reauthReason = null;
        if (entry.kind === 'mcp-server') {
            requiresAuth = true;
            reauthReason = 'авторизация MCP не сохраняется в резервной копии — требуется повторный вход';
        }
        if (entry.kind === 'plugin') {
            // userConfig: значения не читаются и не сохраняются; ключи с
            // sensitive:true означают, что после восстановления их введут заново.
            try {
                const manifestRec = manifestFiles.find(m => /plugin\.json$/i.test(m.path));
                if (manifestRec) {
                    const raw = JSON.parse(manifestRec.content);
                    const norm = formats.normalizePluginManifest(raw, {});
                    const sensitiveKeys = (norm.userConfigKeys || []).filter(k => k.sensitive).map(k => k.key);
                    if (sensitiveKeys.length > 0) {
                        requiresAuth = true;
                        reauthReason = `секреты userConfig не сохраняются (${sensitiveKeys.join(', ')}) — требуется повторный ввод`;
                    }
                }
            } catch (_) { /* манифест не разобран — без выводов о секретах */ }
        }
        const record = {
            id: entry.id,
            name: entry.name,
            kind: entry.kind,
            version: entry.version || null,
            license: entry.license || null,
            packageDigest: entry.packageDigest || null,
            compositionStatus: entry.compositionStatus || null,
            importPath: entry.importPath || null,
            files: (entry.files || []).map(f => ({ path: f.path, size: f.size, sha256: f.sha256 })),
            manifestFiles,
            userState: userState.packages[entry.id] || null,
            requiresReauth: requiresAuth,
            reauthReason
        };
        if (requiresAuth) requiresReauth.push({ id: entry.id, name: entry.name, reason: reauthReason });
        records.push(record);
    }
    // MCP-серверы из конфигурации: без значений env/заголовков — только имена
    // ключей и признаки авторизации.
    if (mcpConfigPath && fs.existsSync(mcpConfigPath)) {
        try {
            const cfg = JSON.parse(fs.readFileSync(mcpConfigPath, 'utf8'));
            const servers = (cfg && cfg.mcpServers && typeof cfg.mcpServers === 'object') ? cfg.mcpServers : {};
            for (const [name, scfg] of Object.entries(servers)) {
                const parsed = formats.parseMcpServerEntry(name, scfg || {});
                const needsAuth = Boolean(parsed.sensitiveFieldsPresent) || (parsed.authType && parsed.authType !== 'none');
                const record = {
                    id: `mcp:${name}`,
                    name,
                    kind: 'mcp-server',
                    version: null,
                    license: null,
                    packageDigest: null,
                    compositionStatus: 'конфигурация MCP',
                    importPath: null,
                    files: [],
                    manifestFiles: [],
                    configKeys: Object.keys(scfg || {}),
                    transport: parsed.transport || null,
                    authType: parsed.authType || null,
                    userState: userState.packages[`mcp:${name}`] || null,
                    requiresReauth: needsAuth,
                    reauthReason: needsAuth ? 'авторизация MCP не сохраняется в резервной копии — требуется повторный вход' : null
                };
                if (needsAuth) requiresReauth.push({ id: record.id, name, reason: record.reauthReason });
                records.push(record);
            }
        } catch (_) { /* повреждённый mcp_config — честно без записей */ }
    }
    const backupManifest = {
        format: 'antigravity-extensions-backup',
        formatVersion: 1,
        exportedAt: new Date().toISOString(),
        exporter: 'antigravity-pc-bridge',
        registryIndexVersion: REGISTRY_INDEX_VERSION,
        secretsExcluded: true,
        secretsPolicy: 'env/заголовки MCP и значения userConfig никогда не сохраняются — только имена ключей',
        contentPolicy: 'содержимое пакетов не копируется: manifests/настройки/состояние + хэши файлов',
        packages: records,
        userState: { version: userState.version, updatedAt: userState.updatedAt, packages: userState.packages },
        requiresReauth
    };
    const buf = archive.writeZipEntries([
        { name: 'backup-manifest.json', data: Buffer.from(JSON.stringify(backupManifest, null, 2), 'utf8') }
    ]);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, buf);
    return {
        ok: true,
        outPath,
        bytes: buf.length,
        packages: records.length,
        requiresReauth,
        backupManifest
    };
}

// ---------------------------------------------------------------------------
// Этап 8. Восстановление из резервной копии.
//
// Восстанавливаются manifests/настройки/состояние; содержимое пакетов должно
// быть на диске (проверяется по хэшам). Секреты не восстанавливаются — список
// мест, где требуется повторный вход, возвращается явно (requiresReauth).
// Отключения и ранее выданные права сохраняются; новых прав не выдаётся.
// ---------------------------------------------------------------------------

function restoreBackup({ backupPath, registryIndexPath, userStatePath = null, targetRoots = [] }) {
    if (!fs.existsSync(backupPath)) {
        return { ok: false, error: `Резервная копия не найдена: ${backupPath}` };
    }
    const st = fs.statSync(backupPath);
    const MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    if (st.size > MAX_BACKUP_BYTES) {
        return { ok: false, error: `Резервная копия больше лимита (${st.size} > ${MAX_BACKUP_BYTES})` };
    }
    let manifest;
    try {
        const zip = archive.readZipEntries(fs.readFileSync(backupPath));
        const verified = archive.verifyArchiveEntries(zip.entries, {});
        if (verified.pathIssues.length > 0) {
            return { ok: false, error: `Резервная копия отклонена: ${verified.pathIssues.join('; ')}` };
        }
        const manifestEntry = verified.entries.find(e => e.path === 'backup-manifest.json');
        if (!manifestEntry) return { ok: false, error: 'В резервной копии нет backup-manifest.json — формат не распознан' };
        manifest = JSON.parse(manifestEntry.data.toString('utf8'));
    } catch (err) {
        return { ok: false, error: `Резервная копия не разбирается: ${err.message}` };
    }
    if (!manifest || manifest.secretsExcluded !== true) {
        return {
            ok: false,
            error: 'Резервная копия не подтверждает исключение секретов (secretsExcluded) — восстановление отклонено'
        };
    }

    const index = loadRegistryIndex(registryIndexPath, { targetRoots, userStatePath });
    const restored = [];
    const notRestored = [];
    for (const rec of manifest.packages || []) {
        // Содержимое пакетов в копии отсутствует (по политике) — файлы должны
        // быть на диске; иначе честное «требуется переустановка».
        if (rec.kind === 'mcp-server' && !rec.importPath) {
            // Конфигурация MCP восстанавливается как запись состояния.
            restored.push({ id: rec.id, name: rec.name, kind: rec.kind, requiresReauth: rec.requiresReauth, reauthReason: rec.reauthReason || null, via: 'mcp-config' });
            continue;
        }
        let dir = rec.importPath && fs.existsSync(rec.importPath) ? path.resolve(rec.importPath) : null;
        if (!dir) {
            for (const root of targetRoots) {
                const cand = path.join(root, String(rec.name || ''));
                if (fs.existsSync(cand)) { dir = cand; break; }
            }
        }
        if (!dir) {
            notRestored.push({
                id: rec.id,
                name: rec.name,
                reason: 'файлы пакета отсутствуют на диске — требуется переустановка',
                requiresReauth: rec.requiresReauth
            });
            continue;
        }
        const bad = [];
        for (const f of rec.files || []) {
            const abs = path.join(dir, ...String(f.path).split('/'));
            if (!fs.existsSync(abs)) bad.push(f.path);
            else if (f.sha256 && sha256Of(fs.readFileSync(abs)) !== f.sha256) bad.push(f.path);
        }
        if (bad.length > 0) {
            notRestored.push({
                id: rec.id,
                name: rec.name,
                reason: `файлы на диске не совпадают с резервной копией: ${bad.slice(0, 5).join(', ')}${bad.length > 5 ? '…' : ''}`,
                requiresReauth: rec.requiresReauth
            });
            continue;
        }
        if (!index.packages[rec.id]) {
            const files = (rec.files || []).map(f => ({ path: f.path, size: f.size, sha256: f.sha256, executable: Boolean(f.executable) }));
            index.packages[rec.id] = {
                id: rec.id,
                name: rec.name,
                kind: rec.kind,
                sourceProduct: 'Antigravity local',
                publisher: 'восстановлено из резервной копии',
                sourceUrl: null,
                version: rec.version || null,
                license: rec.license || null,
                importPath: dir,
                importedAt: null,
                packageDigest: formats.packageDigestOf(files),
                files,
                compositionStatus: rec.compositionStatus || 'восстановлено по резервной копии (состав — по файлам на диске)',
                skills: [],
                pluginSkillLinks: [],
                unknownComponents: [],
                executableComponents: [],
                enabledOverride: null,
                restoredFromBackup: true
            };
        }
        restored.push({ id: rec.id, name: rec.name, kind: rec.kind, requiresReauth: rec.requiresReauth, reauthReason: rec.reauthReason || null, importPath: dir });
    }

    // Пользовательское состояние: enabled/разрешения/привязки восстанавливаются
    // как есть (отключения и прежние права сохраняются; новых прав нет).
    let stateRestored = false;
    if (userStatePath && manifest.userState && manifest.userState.packages) {
        const state = loadUserState(userStatePath);
        for (const [pid, ue] of Object.entries(manifest.userState.packages)) {
            const entry = touchUserEntry(state, pid);
            if (typeof ue.enabled === 'boolean') entry.enabled = ue.enabled;
            if (ue.permissions && typeof ue.permissions === 'object') {
                if (Array.isArray(ue.permissions.granted)) entry.permissions.granted = ue.permissions.granted.map(String);
                if (Array.isArray(ue.permissions.denied)) entry.permissions.denied = ue.permissions.denied.map(String);
                if (Array.isArray(ue.permissions.requested)) entry.permissions.requested = ue.permissions.requested.map(String);
            }
            if (ue.bindings && ue.bindings.conversations && typeof ue.bindings.conversations === 'object') {
                for (const [cid, refs] of Object.entries(ue.bindings.conversations)) {
                    entry.bindings.conversations[String(cid)] = (Array.isArray(refs) ? refs : []).map(String);
                }
            }
        }
        saveUserState(userStatePath, state);
        stateRestored = true;
    }
    saveRegistryIndex(registryIndexPath, index);
    return {
        ok: true,
        restored,
        notRestored,
        stateRestored,
        requiresReauth: manifest.requiresReauth || [],
        message: `Восстановление завершено: пакетов восстановлено ${restored.length}, недоступно ${notRestored.length}. Секреты не восстанавливаются — повторный вход указан в requiresReauth.`
    };
}

// ---------------------------------------------------------------------------
// Сборка общего реестра: индекс + файловая система + конфигурационные файлы +
// наблюдения CLI + (опционально) позиции инвентаря Этапа 1.
// Для каждого пакета фиксируются все заявки источников и статус сверки.
// ---------------------------------------------------------------------------

function buildRegistry({
    registryIndexPath,
    skillRoots = [],
    pluginRoots = [],
    configPath = null,
    mcpConfigPath = null,
    settingsPath = null,
    cliPlugins = null,
    cliMcp = null,
    inventory = null,
    includeComposition = false,
    userState = null
} = {}) {
    const index = registryIndexPath ? loadRegistryIndex(registryIndexPath) : { packages: {} };
    // Этап 8: пользовательское состояние (enabled/разрешения/привязки) — заявка
    // userState участвует в сверке состояния; журнал восстановления индекса
    // показывается честно (indexRecovery).
    const userStatePackages = (userState && userState.packages && typeof userState.packages === 'object') ? userState.packages : {};
    const userStateClaimFor = (id) => {
        const ue = userStatePackages[id];
        return ue && typeof ue.enabled === 'boolean' ? Boolean(ue.enabled) : undefined;
    };
    const userStateInfoFor = (id) => {
        const ue = userStatePackages[id];
        if (!ue) return null;
        return {
            enabled: typeof ue.enabled === 'boolean' ? ue.enabled : null,
            permissions: ue.permissions || { granted: [], denied: [], requested: [] },
            bindings: ue.bindings || { conversations: {} }
        };
    };
    const entries = [];

    // --- Навыки: авторитетное хранилище — файловая система (SKILL.md ↔ .disabled)
    for (const root of skillRoots) {
        if (!root.dir || !fs.existsSync(root.dir)) continue;
        for (const name of fs.readdirSync(root.dir)) {
            const dirPath = path.join(root.dir, name);
            if (!fs.statSync(dirPath).isDirectory()) continue;
            const active = fs.existsSync(path.join(dirPath, 'SKILL.md'));
            const disabled = fs.existsSync(path.join(dirPath, 'SKILL.md.disabled'));
            if (!active && !disabled) continue;
            const id = `skill:${name}`;
            const claims = {};
            if (active && !disabled) claims.filesystem = true;
            else if (!active && disabled) claims.filesystem = false;
            else if (active && disabled) claims.filesystem = 'неоднозначно: есть и SKILL.md, и SKILL.md.disabled';
            let indexClaim = null;
            if (index.packages[id] && index.packages[id].enabledOverride !== null && index.packages[id].enabledOverride !== undefined) {
                indexClaim = Boolean(index.packages[id].enabledOverride);
                claims.index = indexClaim;
            }
            const usClaimSkill = userStateClaimFor(id);
            if (usClaimSkill !== undefined) claims.userState = usClaimSkill;
            const state = resolvePackageState(claims, { authoritativeRecorded: true });
            const composition = includeComposition ? formats.analyzeSkillPackage(dirPath) : null;
            entries.push({
                id,
                name,
                kind: 'skill',
                sourceProduct: root.sourceProduct || 'Antigravity local',
                publisher: root.publisher || 'локальная установка',
                version: composition && composition.skill && composition.skill.metadata ? (composition.skill.metadata.version || null) : (index.packages[id] ? index.packages[id].version : null),
                license: composition && composition.skill ? composition.skill.license : (index.packages[id] ? index.packages[id].license : null),
                importPath: dirPath,
                enabled: state.resolvedEnabled,
                state,
                compositionStatus: composition
                    ? (composition.parseIssues.length > 0 ? `требует проверки (${composition.parseIssues.length} замечаний)` : 'подтверждено сканированием')
                    : 'не сканировалось',
                ownerPlugin: composition && composition.skill ? (index.packages[id] ? index.packages[id].skills && index.packages[id].skills[0] && index.packages[id].skills[0].ownerPlugin : null) : null,
                source: root.source || 'filesystem',
                registryIndexRecord: Boolean(index.packages[id])
            });
        }
    }

    // --- Плагины: заявки CLI, config.json и индекса; авторитетное хранилище —
    //     config.json (запись о состоянии плагина).
    const cliPluginMap = new Map();
    for (const p of cliPlugins || []) cliPluginMap.set(p.id || p.name, p);
    let configPlugins = {};
    if (configPath && fs.existsSync(configPath)) {
        try {
            const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
            configPlugins = (cfg && cfg.plugins && typeof cfg.plugins === 'object') ? cfg.plugins : {};
        } catch (_) { /* повреждённый конфиг — заявок нет */ }
    }
    const pluginNames = new Set([...cliPluginMap.keys(), ...Object.keys(configPlugins)]);
    for (const id of Object.keys(index.packages)) {
        if (index.packages[id].kind === 'plugin') pluginNames.add(index.packages[id].name);
    }
    for (const name of pluginNames) {
        const claims = {};
        const cliEntry = cliPluginMap.get(name);
        if (cliEntry && (cliEntry.enabled === true || cliEntry.enabled === false)) claims.cli = Boolean(cliEntry.enabled);
        const configRecorded = Object.prototype.hasOwnProperty.call(configPlugins, name);
        if (configRecorded && configPlugins[name] && (configPlugins[name].enabled === true || configPlugins[name].enabled === false)) {
            claims.config = Boolean(configPlugins[name].enabled);
        }
        const id = `plugin:${name}`;
        if (index.packages[id] && index.packages[id].enabledOverride !== null && index.packages[id].enabledOverride !== undefined) {
            claims.index = Boolean(index.packages[id].enabledOverride);
        }
        const usClaimPlugin = userStateClaimFor(id);
        if (usClaimPlugin !== undefined) claims.userState = usClaimPlugin;
        const state = resolvePackageState(claims, { authoritativeRecorded: configRecorded });
        const importPath = (cliEntry && cliEntry.path) || (index.packages[id] ? index.packages[id].importPath : null) ||
            pluginRoots.map(r => path.join(r.dir || r, name)).find(p => fs.existsSync(p)) || null;
        let composition = null;
        if (includeComposition && importPath && fs.existsSync(importPath)) {
            composition = formats.analyzePluginComposition(importPath);
        }
        entries.push({
            id,
            name,
            kind: 'plugin',
            sourceProduct: (cliEntry && cliEntry.source === 'antigravity-ide') ? 'Antigravity IDE' : 'Antigravity local',
            publisher: 'локальная установка',
            version: (cliEntry && cliEntry.version) || (index.packages[id] ? index.packages[id].version : null),
            license: index.packages[id] ? index.packages[id].license : null,
            importPath,
            enabled: state.resolvedEnabled,
            state,
            compositionStatus: composition
                ? (composition.unknownComponents.length > 0 ? 'частичное: есть компоненты «требует проверки»' : 'подтверждено сканированием')
                : (index.packages[id] ? index.packages[id].compositionStatus : 'требует проверки'),
            pluginSkillLinks: composition ? composition.pluginSkillLinks : (index.packages[id] ? index.packages[id].pluginSkillLinks : []),
            unknownComponents: composition ? composition.unknownComponents : (index.packages[id] ? index.packages[id].unknownComponents : []),
            registryIndexRecord: Boolean(index.packages[id])
        });
    }

    // --- MCP-серверы: заявки mcp_config.json, settings.json, CLI, индекса;
    //     авторитетное хранилище — mcp_config.json.
    let mcpConfigServers = {};
    if (mcpConfigPath && fs.existsSync(mcpConfigPath)) {
        try {
            const cfg = JSON.parse(fs.readFileSync(mcpConfigPath, 'utf8'));
            mcpConfigServers = (cfg && cfg.mcpServers && typeof cfg.mcpServers === 'object') ? cfg.mcpServers : {};
        } catch (_) { /* повреждённый конфиг — заявок нет */ }
    }
    let settingsServers = {};
    if (settingsPath && fs.existsSync(settingsPath)) {
        try {
            const settings = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
            settingsServers = (settings && settings.mcpServers && typeof settings.mcpServers === 'object') ? settings.mcpServers : {};
        } catch (_) { /* повреждённый конфиг — заявок нет */ }
    }
    const cliMcpMap = new Map();
    for (const s of cliMcp || []) cliMcpMap.set(s.id || s.name, s);
    const mcpNames = new Set([...Object.keys(mcpConfigServers), ...Object.keys(settingsServers), ...cliMcpMap.keys()]);
    for (const name of mcpNames) {
        const claims = {};
        const cliEntry = cliMcpMap.get(name);
        if (cliEntry && (cliEntry.enabled === true || cliEntry.enabled === false)) claims.cli = Boolean(cliEntry.enabled);
        const configRecorded = Object.prototype.hasOwnProperty.call(mcpConfigServers, name);
        if (configRecorded) {
            claims.mcpConfig = !Boolean(mcpConfigServers[name] && mcpConfigServers[name].disabled);
        }
        if (Object.prototype.hasOwnProperty.call(settingsServers, name)) {
            claims.settings = !Boolean(settingsServers[name] && settingsServers[name].disabled);
        }
        const id = `mcp:${name}`;
        if (index.packages[id] && index.packages[id].enabledOverride !== null && index.packages[id].enabledOverride !== undefined) {
            claims.index = Boolean(index.packages[id].enabledOverride);
        }
        const usClaimMcp = userStateClaimFor(id);
        if (usClaimMcp !== undefined) claims.userState = usClaimMcp;
        const state = resolvePackageState(claims, { authoritativeRecorded: configRecorded });
        const cfg = mcpConfigServers[name] || settingsServers[name] || {};
        const mcpEntry = formats.parseMcpServerEntry(name, cfg);
        entries.push({
            id,
            name,
            kind: 'mcp-server',
            sourceProduct: 'Antigravity local',
            publisher: 'локальная установка',
            version: null,
            license: null,
            importPath: mcpConfigPath,
            transport: mcpEntry.transport,
            authType: mcpEntry.authType,
            sensitiveFieldsPresent: mcpEntry.sensitiveFieldsPresent,
            enabled: state.resolvedEnabled,
            state,
            compositionStatus: 'конфигурация (handshake — Этап 3)',
            registryIndexRecord: Boolean(index.packages[id])
        });
    }

    // --- Позиции инвентаря Этапа 1 (наблюдения дампов, не установки).
    if (inventory) entries.push(...mergeCatalogPositions(inventory));

    // Этап 8: к каждому пакету — его пользовательское состояние (если записано).
    for (const e of entries) {
        const us = userStateInfoFor(e.id);
        if (us) e.userState = us;
    }

    const counts = {
        total: entries.length,
        byKind: {},
        byDisplayState: {}
    };
    for (const e of entries) {
        counts.byKind[e.kind] = (counts.byKind[e.kind] || 0) + 1;
        const ds = e.state.displayState;
        counts.byDisplayState[ds] = (counts.byDisplayState[ds] || 0) + 1;
    }
    return {
        generatedAt: new Date().toISOString(),
        entries,
        counts,
        // Этап 8: журнал восстановления индекса (повреждение/отсутствие →
        // снимок в backup + пересборка по диску) — честная диагностика.
        indexRecovery: (index && Array.isArray(index.recoveryLog)) ? index.recoveryLog : [],
        userStateApplied: Object.keys(userStatePackages).length > 0,
        needsStateReconciliation: entries.filter(e => e.state.drift || e.state.registrationGap).map(e => ({
            id: e.id,
            name: e.name,
            drift: e.state.drift,
            registrationGap: e.state.registrationGap,
            claimTable: e.state.claimTable
        }))
    };
}

module.exports = {
    REGISTRY_INDEX_VERSION,
    USER_STATE_VERSION,
    loadRegistryIndex,
    saveRegistryIndex,
    validatePackageName,
    withRegistryLock,
    resolvePackageState,
    mergeCatalogPositions,
    readSourceEntries,
    inspectPackageSource,
    importPackage,
    setPackageEnabled,
    removePackage,
    exportOwnSet,
    analyzeVirtualPackage,
    stripSingleRoot,
    buildRegistry,
    // Этап 8: сохранение, обновления и восстановление.
    atomicWriteJson,
    loadUserState,
    saveUserState,
    getUserStateEntry,
    setUserState,
    grantPackagePermissions,
    setConversationBindings,
    rebuildIndexFromDisk,
    collectDeclaredPermissions,
    collectDeclaredDependencies,
    diffPackageDeclarations,
    validateInstalledUpdate,
    updatePackage,
    rollbackPackage,
    createBackup,
    restoreBackup
};
