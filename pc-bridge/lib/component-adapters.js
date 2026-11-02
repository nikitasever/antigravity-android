'use strict';
// ---------------------------------------------------------------------------
// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): адаптеры компонентов плагинов и
// Android-runtime. Для КАЖДОГО компонента из инвентаря определяется одна из
// трёх стратегий плана:
//   preserve     — сохранение исходника (как есть, без исполнения);
//   convert      — преобразование (команда/агент/output-style → навык и т.п.);
//   own-executor — собственный исполнитель (hook-engine, script-runner,
//                  MCP-клиент Этапа 3);
// плюс конкретный блокер там, где проверенного пути исполнения нет.
//
// Статусы («у каждого компонента есть проверенный путь исполнения либо
// конкретный блокер»):
//   verified        — путь проверен запуском/тестами (см. verification);
//   blocked         — конкретный блокер (см. blocker);
//   requires-check  — «требует проверки» (ничего не выдумывается);
//   not-applicable  — исполнение не применяется (сохранение и есть путь):
//                     продуктовые UI-компоненты, статические ресурсы.
//
// Статус «плагин поддержан полностью» (fully-supported) разрешён ТОЛЬКО после
// проверки всего нужного состава + проверенного права переиспользования/
// распространения оригинала (checkRedistributionRight).
// ---------------------------------------------------------------------------
const fs = require('node:fs');
const path = require('node:path');
const formats = require('./extension-formats');

const STRATEGIES = {
    PRESERVE: 'preserve',
    CONVERT: 'convert',
    OWN_EXECUTOR: 'own-executor'
};
const STATUSES = {
    VERIFIED: 'verified',
    BLOCKED: 'blocked',
    REQUIRES_CHECK: 'requires-check',
    NOT_APPLICABLE: 'not-applicable'
};

// Исполнители: pc-bridge — собственные исполнители моста (ПК);
// agy-cli — собственный механизм CLI (мост в его цикл не вмешивается);
// android — Android-runtime (совместимость доказывается запуском);
// cloud — внешний сервис; null — исполнения нет.
const UI_COMPONENT_TYPES = new Set([
    'catalog-tab', 'catalog-card', 'catalog-page', 'catalog-category',
    'catalog-view', 'catalog-detail', 'entry-point', 'builtin-toggle',
    'surface', 'management', 'availability-facet', 'settings-facet'
]);

const UI_NOTE = 'продуктовый UI-компонент источника; в Antigravity не исполняется; сохранён как наблюдение (сохранение исходника)';

// Таблица адаптеров: оба диалекта форматов (Antigravity-плагины по
// builtin/skills/agy-customizations/docs/plugins.md и Claude/Codex-совместимые
// по COMPONENT_KINDS extension-formats.js) + типы продуктового UI из инвентаря.
const KIND_ADAPTERS = {
    // --- Antigravity-нативные компоненты плагина -----------------------------
    skill: {
        strategy: STRATEGIES.PRESERVE, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'инструкции навыка: выбор /имя и загрузка только выбранного (Этап 4); скрипты навыка: script-runner с зависимостями и изоляцией рабочего каталога (Этап 5, запуск подтверждён тестами)',
        note: 'SKILL.md + scripts/references/assets переносятся как есть (Agent Skills)'
    },
    agent: {
        strategy: STRATEGIES.CONVERT, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'markdown агента преобразуется в инструкции навыка/дочерней задачи (convertAgentToSkill; поднабор прав — buildChildTaskPermissionBlock Этапа 4); проверено тестами преобразования',
        note: 'процедуры агента переносятся как навык/дочерняя задача (submit-claude-plugin)'
    },
    rule: {
        strategy: STRATEGIES.PRESERVE, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'правила вставляются постоянным контекстом (composeRulesBlock); проверено тестом композиции',
        note: 'rules/AGENTS.md — markdown без frontmatter, всегда активен при включённом плагине (docs/rules.md)'
    },
    hook: {
        strategy: STRATEGIES.OWN_EXECUTOR, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'hook-engine (Этап 5) по официальному контракту docs/hooks.md: реальный запуск type:"command", stdin JSON → stdout JSON; подтверждено тестами с настоящими процессами',
        note: 'неподдерживаемые типы/события/выключенные hooks — ВИДИМЫЙ отказ, никогда не запускаются молча; право запуска: pcPluginsMcpSkillsAccess + pcPowerShellExecution'
    },
    'mcp-server': {
        strategy: STRATEGIES.OWN_EXECUTOR, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'живой MCP-handshake и tools/call (Этап 3: initialize → tools/list → вызов, все транспорты)',
        note: 'stdio — только ПК (pc-bridge); удалённый Streamable HTTP — через реестр подключений'
    },
    'config-var': {
        strategy: STRATEGIES.CONVERT, executor: null, status: STATUSES.VERIFIED,
        verification: 'сохраняется только схема ключа (тип/sensitive/required); значения не записываются и не исполняются; sensitive редактируется — проверено тестами Этапов 1/5',
        note: 'значения конфигурации — входы времени выполнения (заменяются входами/OAuth, submit-claude-plugin)'
    },
    asset: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.NOT_APPLICABLE,
        verification: 'файлы сохраняются с пакетом (sha256 в составе, Этап 1)',
        note: 'статические ресурсы/документация; исполнение не применяется'
    },
    executable: {
        strategy: STRATEGIES.OWN_EXECUTOR, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'script-runner (Этап 5): проверка интерпретатора и зависимостей ДО запуска, изолированный рабочий каталог, реальный запуск подтверждён тестами',
        note: 'локальные программы и скрипты; при нехватке зависимостей/интерпретатора — конкретный блокер вместо молчаливого прогона'
    },

    // --- Claude/Codex-совместимые виды (COMPONENT_KINDS) --------------------
    command: {
        strategy: STRATEGIES.CONVERT, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'markdown-команда → навык (convertCommandToSkill); проверено тестами преобразования и сборки инструкции',
        note: 'OpenAI требует превратить markdown-команду в навык (submit-claude-plugin)'
    },
    'lsp-server': {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.BLOCKED,
        blocker: 'lspServers не переносится (submit-claude-plugin); LSP-серверы в Antigravity не запускаются — исходник только сохраняется',
        note: 'LSP-серверы источника'
    },
    'output-style': {
        strategy: STRATEGIES.CONVERT, executor: 'pc-bridge', status: STATUSES.VERIFIED,
        verification: 'содержимое преобразуется в навык (convertOutputStyleToSkill); проверено тестом',
        note: 'как outputStyle не переносится; суть переносится в навык (submit-claude-plugin)'
    },
    workflow: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.BLOCKED,
        blocker: 'workflows — расширение Claude Code; собственного исполнителя в Antigravity нет — исходник только сохраняется',
        note: 'workflows источника'
    },
    theme: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.NOT_APPLICABLE,
        verification: 'наблюдение сохранено; исполнения нет и не предусмотрено',
        note: 'themes — продуктовый UI-компонент Claude; в Antigravity не исполняется'
    },
    monitor: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.BLOCKED,
        blocker: 'monitors — расширение Claude Code; собственного исполнителя в Antigravity нет',
        note: 'monitors источника'
    },
    settings: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.BLOCKED,
        blocker: 'settings.json/CLAUDE.md — настройки Claude Code, не переносятся; схемы конфигурации обрабатываются как config-var',
        note: 'настройки источника'
    },
    app: {
        strategy: STRATEGIES.CONVERT, executor: 'cloud', status: STATUSES.BLOCKED,
        blocker: 'путь — подключение сервиса через MCP-реестр (Этап 3); формат .app.json/apps и переносимый сервер для Antigravity не подтверждены',
        note: 'зарегистрированные MCP-маппинги источника'
    },

    // --- Составные и продуктовые типы из инвентаря --------------------------
    plugin: {
        strategy: STRATEGIES.OWN_EXECUTOR, executor: 'pc-bridge', status: STATUSES.REQUIRES_CHECK,
        verification: 'составной: разложение по компонентам (analyzePluginAdapters); «поддержан полностью» — только после проверки всего состава',
        note: 'плагин-пакет (состав проверяется по фактическим файлам)'
    },
    'skill-assets': {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.REQUIRES_CHECK,
        verification: 'файлы сохранены (sha256 в инвентаре); подключение к экрану и внешние зависимости — отдельная проверка',
        note: 'ассеты навыка/плагина источника'
    },
    connector: {
        strategy: STRATEGIES.CONVERT, executor: 'cloud', status: STATUSES.BLOCKED,
        blocker: 'внешний сервис; полный OAuth-цикл (discovery/PKCE/refresh/revoke) вне объёма Этапов 3–5; путь — MCP-подключение при подтверждённом переносимом сервере (название сервиса не гарантирует его наличие)',
        note: 'коннектор источника'
    },
    gpt: {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.BLOCKED,
        blocker: 'GPT ≠ плагин и ≠ MCP; переносимого сервера нет без доступного API',
        note: 'GPT источника'
    },
    'permission-model': {
        strategy: STRATEGIES.PRESERVE, executor: null, status: STATUSES.NOT_APPLICABLE,
        verification: 'наблюдение сохранено; права Antigravity задаются собственным списком (Этап 4)',
        note: 'модель разрешений источника НЕ копируется вслепую: разрешение установки не расширяет доступ инструментов Antigravity'
    }
};

// Псевдонимы диалектов: множественные формы manifest'ов Claude → наши виды.
const KIND_ALIASES = {
    skills: 'skill',
    commands: 'command',
    agents: 'agent',
    hooks: 'hook',
    mcpServers: 'mcp-server',
    lspServers: 'lsp-server',
    outputStyles: 'output-style',
    workflows: 'workflow',
    userConfig: 'config-var',
    rules: 'rule',
    executables: 'executable'
};

function normalizeKind(type) {
    const t = String(type == null ? 'unknown' : type);
    return KIND_ALIASES[t] || t;
}

function adaptComponentType(type, ctx = {}) {
    const kind = normalizeKind(type);
    let base = KIND_ADAPTERS[kind];
    if (!base && UI_COMPONENT_TYPES.has(kind)) {
        base = {
            strategy: STRATEGIES.PRESERVE,
            executor: null,
            status: STATUSES.NOT_APPLICABLE,
            verification: 'наблюдение сохранено в инвентаре (Этап 0)',
            note: UI_NOTE
        };
    }
    if (!base) {
        base = {
            strategy: STRATEGIES.PRESERVE,
            executor: null,
            status: STATUSES.REQUIRES_CHECK,
            blocker: `вид компонента "${kind}" не отнесён к известным — требуется проверка`,
            note: 'состав подтверждён не полностью'
        };
    }
    const record = {
        componentType: type,
        normalizedKind: kind,
        strategy: ctx.strategy || base.strategy,
        executor: ctx.executor !== undefined ? ctx.executor : base.executor,
        status: ctx.status || base.status,
        verification: ctx.verification || base.verification || null,
        blocker: ctx.blocker !== undefined ? ctx.blocker : (base.blocker || null),
        note: ctx.note || base.note || null
    };
    return record;
}

// ---------------------------------------------------------------------------
// Право переиспользования/распространения оригинала (требование плана:
// «Проверять право переиспользования/распространения оригинала»).
// ---------------------------------------------------------------------------
function checkRedistributionRight(licenseRaw) {
    const raw = licenseRaw == null ? '' : String(licenseRaw).trim();
    if (!raw) {
        return { raw, classification: 'unknown', rightToRedistribute: 'unknown', note: 'лицензия не указана — право переиспользования/распространения не подтверждено' };
    }
    if (/собственн/i.test(raw) && /код/i.test(raw)) {
        return { raw, classification: 'own-code', rightToRedistribute: 'granted', note: 'собственный код проекта — распространение разрешено' };
    }
    if (/^n\/a$/i.test(raw) || /не применим/i.test(raw)) {
        return { raw, classification: 'not-applicable', rightToRedistribute: 'not-applicable', note: 'лицензия неприменима (встроенная функция/модель данных)' };
    }
    const spdx = raw.match(/\b(MIT|Apache[-\s]?2(\.0)?|BSD[-\s]?[23]-Clause|BSD|GPL[-\s]?\d(\.\d)?|LGPL[-\s]?\d(\.\d)?|AGPL[-\s]?\d(\.\d)?|ISC|MPL[-\s]?2(\.0)?|Unlicense|CC0[-\s]?1(\.0)?|CC[-\s]BY[-\s]?[A-Z0-9.]+)\b/i);
    if (spdx) {
        return { raw, classification: 'declared', rightToRedistribute: 'granted', note: `лицензия объявлена (${spdx[0]}) — права по её условиям` };
    }
    if (/не указано|не указана|не зафиксировано/i.test(raw)) {
        return { raw, classification: 'unknown', rightToRedistribute: 'unknown', note: 'лицензия не зафиксирована — право переиспользования/распространения не подтверждено' };
    }
    return { raw, classification: 'unknown', rightToRedistribute: 'unknown', note: `лицензия "${raw.slice(0, 60)}" не распознана — право не подтверждено` };
}

// «Статус «плагин поддержан полностью» разрешён только после проверки всего
// нужного ему состава» + проверенного права распространения.
function evaluatePluginSupport({ records = [], licenseCheck = null, compositionVerified = false } = {}) {
    const blockers = [];
    for (const r of records) {
        if (r.status === STATUSES.BLOCKED) blockers.push({ componentType: r.componentType, reason: r.blocker || 'заблокировано' });
        else if (r.status === STATUSES.REQUIRES_CHECK) blockers.push({ componentType: r.componentType, reason: r.blocker || 'требует проверки' });
    }
    const licenseOk = !licenseCheck || licenseCheck.rightToRedistribute === 'granted' || licenseCheck.rightToRedistribute === 'not-applicable';
    const allNotApplicable = records.length > 0 && records.every(r => r.status === STATUSES.NOT_APPLICABLE);

    let supportStatus;
    if (allNotApplicable) supportStatus = 'observation';
    else if (blockers.length === 0 && compositionVerified && licenseOk) supportStatus = 'fully-supported';
    else if (blockers.length === 0 && !compositionVerified) supportStatus = 'partial';
    else if (records.some(r => r.status === STATUSES.VERIFIED)) supportStatus = 'partial';
    else supportStatus = 'blocked';

    return {
        supportStatus,
        blockers,
        compositionVerified: Boolean(compositionVerified),
        licenseOk,
        fullSupportAllowed: supportStatus === 'fully-supported'
    };
}

// ---------------------------------------------------------------------------
// Проверенные пути преобразования (convert) и композиции (preserve).
// ---------------------------------------------------------------------------

// markdown-команда (Claude/Codex) → навык (требование submit-claude-plugin).
function convertCommandToSkill(rawMd, fallbackName = 'command') {
    const fm = formats.parseFrontMatter(rawMd);
    const data = fm.data || {};
    const name = String(data.name || fallbackName || 'command').trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'command';
    const description = String(data.description || '').trim() || `Команда ${fallbackName}, преобразованная в навык`;
    const body = String(fm.body || '').replace(/\$ARGUMENTS\b/g, '[аргументы запроса]');
    return {
        name,
        description,
        instruction: body,
        convertedFrom: 'command',
        preservedFrontmatter: data,
        skillMarkdown: `---\nname: ${name}\ndescription: ${JSON.stringify(description)}\n---\n\n${body}\n`
    };
}

// markdown агента → навык/дочерняя задача (процедуры агента).
function convertAgentToSkill(rawMd, fallbackName = 'agent') {
    const fm = formats.parseFrontMatter(rawMd);
    const data = fm.data || {};
    const name = String(data.name || fallbackName || 'agent').trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'agent';
    const description = String(data.description || '').trim() || `Агент ${fallbackName}, преобразованный в навык`;
    const toolsLine = (data.tools != null) ? `\n\nИнструменты агента (ограничение поднабора прав): ${Array.isArray(data.tools) ? data.tools.join(', ') : String(data.tools)}` : '';
    return {
        name,
        description,
        instruction: String(fm.body || '') + toolsLine,
        convertedFrom: 'agent',
        preservedFrontmatter: data,
        skillMarkdown: `---\nname: ${name}\ndescription: ${JSON.stringify(description)}\n---\n\n${String(fm.body || '')}${toolsLine}\n`
    };
}

// output-style → навык («как outputStyle не переносится; суть — в навык»).
function convertOutputStyleToSkill(rawMd, fallbackName = 'output-style') {
    const fm = formats.parseFrontMatter(rawMd);
    const data = fm.data || {};
    const name = String(data.name || fallbackName || 'output-style').trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'output-style';
    const description = String(data.description || '').trim() || `Стиль вывода ${fallbackName}, преобразованный в навык`;
    return {
        name,
        description,
        instruction: String(fm.body || ''),
        convertedFrom: 'output-style',
        preservedFrontmatter: data,
        skillMarkdown: `---\nname: ${name}\ndescription: ${JSON.stringify(description)}\n---\n\n${String(fm.body || '')}\n`
    };
}

// rules/AGENTS.md → постоянный контекст (всегда активен при включённом плагине).
function composeRulesBlock(rulesText, { pluginName = null, sourcePath = null } = {}) {
    const text = String(rulesText == null ? '' : rulesText).trim();
    if (!text) return null;
    const origin = pluginName ? `плагин "${pluginName}"` : (sourcePath || 'правила рабочего каталога');
    return `<RULES_CONTEXT origin="${origin}">\nПравила всегда активны (docs/rules.md):\n${text}\n</RULES_CONTEXT>`;
}

// ---------------------------------------------------------------------------
// Живой разбор состава плагина: у каждого компонента — стратегия и путь либо
// блокер. Реальные файлы на диске (плагины Antigravity: plugin.json,
// hooks.json, mcp_config.json, rules/, skills/, agents/, assets/).
// ---------------------------------------------------------------------------
function analyzePluginAdapters(pluginDir, { name = null, enabled = true, licenseRaw = null } = {}) {
    const resolved = path.resolve(String(pluginDir || ''));
    if (!fs.existsSync(resolved)) {
        return {
            pluginDir: resolved,
            pluginName: name || path.basename(resolved),
            exists: false,
            records: [],
            licenseCheck: checkRedistributionRight(licenseRaw),
            support: evaluatePluginSupport({ records: [], licenseCheck: checkRedistributionRight(licenseRaw), compositionVerified: false }),
            blockers: [{ componentType: 'plugin', reason: `каталог плагина не найден: ${resolved}` }]
        };
    }
    const composition = formats.analyzePluginComposition(resolved, { maxFiles: 512, maxTotalBytes: 16 * 1024 * 1024 });
    const manifest = (composition.manifests && composition.manifests[0]) || null;
    const pluginName = name || composition.pluginName || path.basename(resolved);

    // Группировка фактических файлов по видам компонентов. Manifest — маркер
    // каталога, не компонент состава: пустой manifest-only плагин остаётся
    // пустым составом (defect №1 Этапа 0).
    const byKind = new Map();
    for (const c of composition.components || []) {
        if (c.kind === 'manifest') continue;
        if (!byKind.has(c.kind)) byKind.set(c.kind, []);
        byKind.get(c.kind).push(c.path);
    }

    const records = [];
    if (byKind.size === 0) {
        records.push(adaptComponentType('plugin', {
            status: STATUSES.BLOCKED,
            blocker: 'состав пуст: только manifest (plugin.json); заявленные возможности не обеспечены компонентами (skills/agents/rules/hooks/mcp) — пути исполнения нет',
            verification: 'фактический состав просмотрен (analyzePluginComposition)'
        }));
    }
    for (const [kind, paths] of byKind) {
        const extra = {};
        if (kind === 'executable') {
            // Локальные программы: зависимости и изоляция проверяются при
            // подготовке запуска; здесь фиксируем состав и стратегию.
            extra.verification = `файлы: ${paths.slice(0, 5).join(', ')}${paths.length > 5 ? '…' : ''}; подготовка запуска — script-runner (зависимости + изоляция)`;
        } else if (kind === 'hook') {
            extra.verification = `hooks.json: ${paths.join(', ')}; исполнение — hook-engine с видимыми отказами`;
        } else if (kind === 'skill') {
            extra.verification = `файлы навыков: ${paths.length}`;
        }
        records.push(adaptComponentType(kind, extra));
    }

    // Право распространения: manifest.license, иначе — не зафиксировано.
    const licenseVal = licenseRaw != null ? licenseRaw : ((manifest && manifest.license) || null);
    const licenseCheck = checkRedistributionRight(licenseVal);
    const compositionVerified = byKind.size > 0 && (composition.unknownComponents || []).length === 0;
    const support = evaluatePluginSupport({ records, licenseCheck, compositionVerified });
    if (!enabled && support.supportStatus === 'fully-supported') {
        support.supportStatus = 'partial';
        support.blockers.push({ componentType: 'plugin', reason: 'плагин отключён — состав не активен' });
    }
    return {
        pluginDir: resolved,
        pluginName,
        exists: true,
        manifestFlavor: manifest ? manifest.manifestFlavor : null,
        manifestLicense: licenseVal,
        fileCount: composition.fileCount,
        records,
        licenseCheck,
        support,
        unknownComponents: (composition.unknownComponents || []).map(u => u.path)
    };
}

// ---------------------------------------------------------------------------
// Матрица адаптеров по инвентарю Этапа 0: для всех компонентов всех позиций.
// ---------------------------------------------------------------------------
function buildAdapterMatrix(inventory, { pluginAnalyses = [] } = {}) {
    const positions = (inventory && Array.isArray(inventory.positions)) ? inventory.positions : [];
    const analyzedByPath = new Map();
    for (const a of pluginAnalyses) {
        if (a && a.pluginDir) analyzedByPath.set(path.resolve(a.pluginDir), a);
    }

    const outPositions = positions.map(p => {
        const componentTypes = Array.isArray(p.componentTypes) ? p.componentTypes : [];
        let records = componentTypes.map(t => adaptComponentType(t));
        const licenseCheck = checkRedistributionRight(p.license);

        // Плагины с реальным путём на диске: состав проверяется по файлам.
        let live = null;
        if (componentTypes.includes('plugin') && p.importPath && /^([A-Za-z]:|\/|\\\\)/.test(p.importPath) && fs.existsSync(p.importPath)) {
            live = analyzedByPath.get(path.resolve(p.importPath)) || analyzePluginAdapters(p.importPath, {
                name: (p.id || '').replace(/^local-plugin-(staging-)?/, '') || null,
                enabled: true,
                licenseRaw: p.license
            });
            // Записи живого состава заменяют декларацию «plugin».
            records = [
                adaptComponentType('plugin', {
                    status: live.records.some(r => r.status === STATUSES.VERIFIED) ? live.support.supportStatus === 'fully-supported' ? STATUSES.VERIFIED : STATUSES.REQUIRES_CHECK : live.support.supportStatus === 'blocked' ? STATUSES.BLOCKED : STATUSES.REQUIRES_CHECK,
                    verification: `состав просмотрен по файлам: ${live.fileCount} файл(ов), записей ${live.records.length}`,
                    blocker: live.support.blockers.length > 0 ? live.support.blockers.map(b => b.reason).join('; ') : null
                }),
                ...live.records
            ];
        } else if (componentTypes.includes('plugin') && p.blocker) {
            // Путь импорта не разрешается в реальный каталог — честно пробрасываем
            // блокер инвентаря, ничего не выдумываем.
            records = records.map(r => r.componentType === 'plugin'
                ? adaptComponentType('plugin', { status: STATUSES.REQUIRES_CHECK, blocker: p.blocker })
                : r);
        }
        if (componentTypes.includes('skill-assets')) {
            const depNote = (p.blocker || '') || null;
            records = records.map(r => r.componentType === 'skill-assets'
                ? adaptComponentType('skill-assets', {
                    status: /unpkg|не подключено|сетевая/i.test(String(depNote)) ? STATUSES.BLOCKED : r.status,
                    blocker: depNote,
                    verification: `importPath: ${p.importPath || 'не записано'}`
                })
                : r);
        }

        // Состав подтверждён для локально проверенных наблюдений (файлы/CLI/код
        // с availability installed/builtin); облачные каталоги и APK-строки —
        // состав не проверялся, «полностью» не выдаётся.
        const obsParts = String(p.observationType || '').split('+');
        const localVerified = obsParts.some(x => ['filesystem', 'cli', 'code'].includes(x))
            && ['installed', 'builtin'].includes(p.availability);
        const compositionVerified = live ? live.support.compositionVerified : localVerified;
        const support = evaluatePluginSupport({ records, licenseCheck, compositionVerified });
        return {
            id: p.id,
            name: p.name,
            sourceProduct: p.sourceProduct,
            availability: p.availability,
            executionTargets: p.executionTargets || null,
            componentTypes,
            records,
            licenseCheck,
            supportStatus: support.supportStatus,
            blockers: support.blockers,
            fullSupportAllowed: support.fullSupportAllowed
        };
    });

    // «Отдельно проверить commands, agents, hooks, LSP, локальные программы,
    // переменные конфигурации и продуктовые UI-компоненты» — сводная таблица
    // видов.
    const kindTable = [];
    const seenKinds = new Set();
    for (const [kind, adapter] of Object.entries(KIND_ADAPTERS)) {
        seenKinds.add(kind);
        kindTable.push({ kind, ...adaptComponentType(kind) });
    }
    for (const kind of UI_COMPONENT_TYPES) {
        if (seenKinds.has(kind)) continue;
        kindTable.push({ kind, ...adaptComponentType(kind) });
    }

    const summary = {
        positionsTotal: outPositions.length,
        recordsTotal: outPositions.reduce((n, p) => n + p.records.length, 0),
        bySupportStatus: {},
        byStrategy: {},
        fullySupported: outPositions.filter(p => p.fullSupportAllowed).map(p => p.id),
        licenseUnknown: outPositions.filter(p => p.licenseCheck.rightToRedistribute === 'unknown').map(p => p.id)
    };
    for (const p of outPositions) {
        summary.bySupportStatus[p.supportStatus] = (summary.bySupportStatus[p.supportStatus] || 0) + 1;
        for (const r of p.records) {
            summary.byStrategy[r.strategy] = (summary.byStrategy[r.strategy] || 0) + 1;
        }
    }
    return {
        schemaVersion: 1,
        title: 'Матрица адаптеров компонентов (Этап 5)',
        generatedAt: new Date().toISOString(),
        strategies: STRATEGIES,
        statuses: STATUSES,
        kindTable,
        positions: outPositions,
        summary
    };
}

module.exports = {
    STRATEGIES,
    STATUSES,
    UI_COMPONENT_TYPES,
    KIND_ADAPTERS,
    KIND_ALIASES,
    normalizeKind,
    adaptComponentType,
    checkRedistributionRight,
    evaluatePluginSupport,
    convertCommandToSkill,
    convertAgentToSkill,
    convertOutputStyleToSkill,
    composeRulesBlock,
    analyzePluginAdapters,
    buildAdapterMatrix
};
