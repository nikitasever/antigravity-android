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

const REGISTRY_INDEX_VERSION = 1;

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
// ---------------------------------------------------------------------------

function loadRegistryIndex(indexPath) {
    try {
        if (fs.existsSync(indexPath)) {
            const raw = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
            if (raw && raw.version === REGISTRY_INDEX_VERSION && raw.packages && typeof raw.packages === 'object') {
                return raw;
            }
        }
    } catch (_) { /* повреждённый индекс не выдаётся за валидный */ }
    return { version: REGISTRY_INDEX_VERSION, updatedAt: null, packages: {} };
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
    return positions.map(p => {
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
    const index = loadRegistryIndex(registryIndexPath);
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
        version: preview.manifest && preview.manifest.version ? preview.manifest.version : null,
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

function setPackageEnabled({ packageId, enabled, registryIndexPath, configPath = null, mcpConfigPath = null, settingsPath = null, cli = null }) {
    const index = loadRegistryIndex(registryIndexPath);
    const entry = index.packages[packageId];
    if (!entry) return { ok: false, error: `Пакет не найден в реестре: ${packageId}` };
    entry.enabledOverride = Boolean(enabled);
    index.packages[packageId] = entry;
    saveRegistryIndex(registryIndexPath, index);

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
    includeComposition = false
} = {}) {
    const index = registryIndexPath ? loadRegistryIndex(registryIndexPath) : { packages: {} };
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
    buildRegistry
};
