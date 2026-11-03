'use strict';
// Этап 7 (EXTENSIONS_INTEGRATION_PLAN.md): перенос всех позиций по матрице.
//
// Полный проход инвентаря пакетами (в порядке плана): документы и PDF;
// таблицы/презентации; код и Git; поиск/браузер; дизайн/медиа;
// почта/календарь/диск; задачи/знания; остальные категории зафиксированных
// каталогов. Каждая позиция полного среза получает адаптацию (из матрицы
// Этапа 5) и сценарий проверки (из инвентаря). Путь переноса подтверждается
// для КАЖДОЙ позиции: название внешнего сервиса не гарантирует наличие
// переносимого сервера — без подтверждения путь остаётся неподтверждённым.
//
// Нерешённые зависимости остаются в общем backlog с владельцем и следующим
// шагом. Общий перенос НЕ объявляется завершённым, пока обязательные позиции
// (локальная установка Antigravity) заблокированы.
const fs = require('node:fs');
const path = require('node:path');

const PROJECT_ROOT = path.join(__dirname, '..', '..');
const INV_PATH = path.join(PROJECT_ROOT, 'extensions-inventory.json');
const MATRIX_PATH = path.join(PROJECT_ROOT, 'extensions-adapter-matrix.json');
const OUT_PATH = path.join(PROJECT_ROOT, 'extensions-migration-coverage.json');

// ---------------------------------------------------------------------------
// Пакеты прохода (категории из плана Этапа 7). Сопоставление позиций —
// явная таблица (не угадывание по ключевым словам): каждая позиция среза
// обязана иметь запись, иначе генерация падает (100% учёт).
// ---------------------------------------------------------------------------

const BATCHES = [
    { id: 'documents-pdf', title: 'Документы и PDF', order: 1 },
    { id: 'sheets-slides', title: 'Таблицы/презентации', order: 2 },
    { id: 'code-git', title: 'Код и Git', order: 3 },
    { id: 'search-browser', title: 'Поиск/браузер', order: 4 },
    { id: 'design-media', title: 'Дизайн/медиа', order: 5 },
    { id: 'mail-calendar-drive', title: 'Почта/календарь/диск', order: 6 },
    { id: 'tasks-knowledge', title: 'Задачи/знания', order: 7 },
    { id: 'other-catalogs', title: 'Остальные категории зафиксированных каталогов', order: 8 }
];

const POSITION_BATCH = {
    // Документы и PDF
    'claude-web-card-doc-coauthoring': 'documents-pdf',
    'claude-web-card-desktop-commander': 'documents-pdf',
    // Таблицы/презентации: позиций в зафиксированном срезе нет (проверяется явно)
    // Код и Git
    'claude-web-code-upgrade': 'code-git',
    'claude-android-github-app': 'code-git',
    'chatgpt-cat-developer-tools': 'code-git',
    'codex-remote-composer-extensions': 'code-git',
    'local-skill-autonomous-software-engineer': 'code-git',
    'local-skill-reverse-engineer-anything': 'code-git',
    'local-skill-rigorous-programming-reasoner': 'code-git',
    'local-skill-rigorous-programming-reasoner-disabled': 'code-git',
    'local-skill-workstation-diagnostics': 'code-git',
    'local-skill-builtin-permissioned-github': 'code-git',
    'local-import-uploads-skill-md': 'code-git',
    'local-plugin-antigravity-workspace-guard': 'code-git',
    'local-plugin-kotlin-compose-inspector': 'code-git',
    'local-plugin-staging-antigravity-workspace-guard': 'code-git',
    'local-plugin-staging-kotlin-compose-inspector': 'code-git',
    'local-mcp-ag-workstation-info': 'code-git',
    'local-mcp-rea': 'code-git',
    // Поиск/браузер
    'claude-web-toggle-web-search': 'search-browser',
    'claude-web-card-searchfit-seo': 'search-browser',
    'claude-web-card-bright-data': 'search-browser',
    'claude-web-card-product-tracking': 'search-browser',
    // Дизайн/медиа
    'claude-web-card-figma': 'design-media',
    'claude-web-card-web-artifacts-builder': 'design-media',
    'claude-web-entry-design-system': 'design-media',
    'claude-android-connector-figma': 'design-media',
    'chatgpt-cat-creativity-design': 'design-media',
    'codex-visualize-plugin-assets': 'design-media',
    'local-skill-builtin-generative-ui': 'design-media',
    // Почта/календарь/диск
    'claude-web-card-zoom': 'mail-calendar-drive',
    'claude-android-connector-calendar': 'mail-calendar-drive',
    'claude-android-connector-google-drive': 'mail-calendar-drive',
    'claude-android-settings-integrations': 'mail-calendar-drive',
    'chatgpt-connector-gmail': 'mail-calendar-drive',
    'chatgpt-connector-google-calendar': 'mail-calendar-drive',
    'chatgpt-connector-outlook': 'mail-calendar-drive',
    'chatgpt-file-sources': 'mail-calendar-drive',
    // Задачи/знания
    'claude-web-card-learn': 'tasks-knowledge',
    'claude-web-card-productivity': 'tasks-knowledge',
    'claude-web-toggle-memory': 'tasks-knowledge',
    'chatgpt-cat-productivity': 'tasks-knowledge',
    'chatgpt-cat-research-knowledge': 'tasks-knowledge',
    'local-skill-builtin-agy-customizations': 'tasks-knowledge',
    'local-skill-builtin-antigravity-guide': 'tasks-knowledge',
    'local-skill-builtin-automation': 'tasks-knowledge',
    'local-skill-builtin-migrate-workflows': 'tasks-knowledge',
    'local-skill-builtin-plugin': 'tasks-knowledge',
    'local-skill-builtin-run-workflow': 'tasks-knowledge',
    // Остальные категории зафиксированных каталогов
    'claude-web-catalog-connectors': 'other-catalogs',
    'claude-web-catalog-skills': 'other-catalogs',
    'claude-web-catalog-plugins': 'other-catalogs',
    'claude-web-card-marketing': 'other-catalogs',
    'claude-web-card-finance': 'other-catalogs',
    'claude-web-entry-skills': 'other-catalogs',
    'claude-web-entry-add-connector': 'other-catalogs',
    'claude-web-entry-add-plugins': 'other-catalogs',
    'claude-web-entry-get-apps-extensions': 'other-catalogs',
    'claude-web-surface-cowork': 'other-catalogs',
    'claude-android-connector-custom': 'other-catalogs',
    'claude-android-connector-catalog': 'other-catalogs',
    'claude-android-skills': 'other-catalogs',
    'claude-android-mcp-servers': 'other-catalogs',
    'claude-android-plugin-label': 'other-catalogs',
    'chatgpt-plugin-directory': 'other-catalogs',
    'chatgpt-cat-business-analytics': 'other-catalogs',
    'chatgpt-cat-messaging-social': 'other-catalogs',
    'chatgpt-plugin-directory-views': 'other-catalogs',
    'chatgpt-plugin-detail': 'other-catalogs',
    'chatgpt-plugin-permissions': 'other-catalogs',
    'chatgpt-plugin-management': 'other-catalogs',
    'chatgpt-component-app': 'other-catalogs',
    'chatgpt-component-skill': 'other-catalogs',
    'chatgpt-connectors': 'other-catalogs',
    'chatgpt-connector-1password': 'other-catalogs',
    'chatgpt-gpts': 'other-catalogs',
    'chatgpt-finance-connections': 'other-catalogs',
    'chatgpt-availability-gates': 'other-catalogs',
    'local-skill-builtin-ui-plugin-navigation': 'other-catalogs'
};

// ---------------------------------------------------------------------------
// Сквозные контрольные пакеты (проверка инфраструктуры, не конечный набор):
// один инструкционный навык; один навык со скриптом; один HTTP MCP; один
// stdio MCP; один составной плагин с зависимостями. Сценарий каждого —
// сквозной (установка → включение → проверка → вызов), тест —
// pc-bridge/test/extensions-stage7.test.js.
// ---------------------------------------------------------------------------

const CONTROL_PACKAGES = [
    {
        id: 'control-instruction-skill',
        kind: 'skill-instruction',
        fixture: 'pc-bridge/test/fixtures/stage7/control-instruction-skill',
        scenario: 'Установка пакета навыка в реестр → каталог навыков → выбор по /имя → загрузка инструкций ТОЛЬКО выбранного навыка → блок SKILL_INSTRUCTION в запросе.'
    },
    {
        id: 'control-script-skill',
        kind: 'skill-with-script',
        fixture: 'pc-bridge/test/fixtures/stage7/control-script-skill',
        scenario: 'Навык со скриптом: проверка зависимостей до запуска (интерпретатор + require) → запуск в изолированном рабочем каталоге → результат наружу; отсутствие зависимостей — видимый блокер, не молчаливый прогон.'
    },
    {
        id: 'control-http-mcp',
        kind: 'mcp-http',
        fixture: 'pc-bridge/test/fixtures/stage7/control-http-mcp',
        scenario: 'HTTP MCP: реестр подключения → настоящее рукопожатие initialize → tools/list → tools/call к живому HTTP-серверу; «подключён» только после рукопожатия.'
    },
    {
        id: 'control-stdio-mcp',
        kind: 'mcp-stdio',
        fixture: 'pc-bridge/test/fixtures/stage7/control-stdio-mcp',
        scenario: 'stdio MCP: запуск локального процесса сервера → рукопожатие → tools/list → tools/call; падение процесса — видимая ошибка.'
    },
    {
        id: 'control-composite-plugin',
        kind: 'plugin-composite',
        fixture: 'pc-bridge/test/fixtures/stage7/control-composite-plugin',
        scenario: 'Составной плагин с зависимостями: разбор состава (manifest + skills + hooks + scripts) → адаптеры компонентов → проверка зависимостей скриптов ДО запуска → hook по официальному контракту (stdin JSON → stdout JSON) с видимыми отказами.'
    }
];

// ---------------------------------------------------------------------------
// Подтверждение пути переноса. Внешние сервисы без проверенного переносимого
// сервера остаются неподтверждёнными. Локальные пути реально проверяются на
// диске; усечённые записи («…\») восстанавливаются по корню проекта.
// ---------------------------------------------------------------------------

function expandBraces(p) {
    const m = p.match(/^(.*)\{([^}]+)\}(.*)$/);
    if (!m) return [p];
    return m[2].split(',').map(v => `${m[1]}${v.trim()}${m[3]}`);
}

function resolveImportPart(raw) {
    const text = String(raw || '').trim();
    if (!text || /^n\/?a$/i.test(text)) return null;
    const candidates = [];
    if (text.startsWith('…') || text.startsWith('...')) {
        // Усечённый путь: восстанавливаем суффикс относительно известных корней.
        const tail = text.replace(/^[….]+[\\/]*/, '');
        const marker = tail.search(/(scratch[\\/]antigravity-android|antigravity-android)[\\/]/i);
        if (marker >= 0) {
            const after = tail.slice(tail.indexOf('antigravity-android', marker) + 'antigravity-android'.length).replace(/^[\\/]/, '');
            candidates.push(path.join(PROJECT_ROOT, after));
        }
        if (tail.includes('uploads') || tail.includes('plugin-staging')) {
            const upIdx = tail.lastIndexOf('uploads');
            candidates.push(path.join(PROJECT_ROOT, tail.slice(upIdx)));
        }
    } else if (/^[A-Za-z]:[\\/]/.test(text) || text.startsWith('/')) {
        candidates.push(text);
    } else {
        candidates.push(path.join(PROJECT_ROOT, text));
    }
    return { text, candidates };
}

function checkImportPath(importPath) {
    const value = String(importPath || '');
    if (!value || /^n\/?a$/i.test(value.trim())) {
        return { confirmed: false, basis: 'путь импорта не записан (n/a)', checked: [] };
    }
    const parts = value.split(/\s+\+\s+/).map(s => s.trim()).filter(Boolean);
    const checked = [];
    let allExist = parts.length > 0;
    for (const part of parts) {
        for (const expanded of expandBraces(part)) {
            const resolved = resolveImportPart(expanded);
            if (!resolved) continue;
            let exists = false;
            let used = null;
            for (const cand of resolved.candidates) {
                if (fs.existsSync(cand)) { exists = true; used = cand; break; }
            }
            if (!used && resolved.candidates.length) used = resolved.candidates[0];
            checked.push({ recorded: expanded, resolved: used, exists });
            if (!exists) allExist = false;
        }
    }
    if (!checked.length) {
        return { confirmed: false, basis: `путь не разрешён: ${value}`, checked };
    }
    return {
        confirmed: allExist,
        basis: allExist
            ? 'локальный путь подтверждён проверкой на диске (fs.existsSync)'
            : 'записанный локальный путь не найден на диске',
        checked
    };
}

// ---------------------------------------------------------------------------
// Владелец и следующий шаг для нерешённых зависимостей (общий backlog).
// Правила фиксируются в записи (rule), чтобы решение не было выдумкой.
// ---------------------------------------------------------------------------

function backlogFor(pos, matrixPos) {
    if (pos.canonicalId && pos.canonicalId.startsWith('local-plugin-') && matrixPos?.supportStatus === 'fully-supported') return null;
    const portable = require('../lib/official-mcp-catalog').descriptorForPosition(pos.id);
    if (portable) return {
        id: `bl:${pos.id}`, positionId: pos.id,
        issues: ['Официальный дескриптор и лицензия подтверждены; живой пользовательский вход и вызов пока не проверены'],
        dependencies: portable.dependencies, supportStatus: matrixPos?.supportStatus || 'partial',
        owner: 'antigravity-android + пользователь подключения',
        nextStep: `Настроить зависимости ${portable.id}, собственную авторизацию и проверить initialize → tools/list → tools/call; не менять статус на подключённый до успешного handshake.`,
        rule: 'external-runtime-check'
    };
    const issues = [];
    const blockers = (matrixPos && Array.isArray(matrixPos.blockers)) ? matrixPos.blockers.map(b => b.reason || String(b)) : [];
    const invBlocker = String(pos.blocker || '').trim();
    if (blockers.length) issues.push(...blockers);
    if (invBlocker && invBlocker !== 'не записано') issues.push(invBlocker);
    const deps = Array.isArray(pos.dependencies) ? pos.dependencies.filter(d => d && d !== 'не записано') : [];
    // Бэклог — про открытые вопросы (issues). Фактические зависимости без
    // открытого вопроса остаются записанными в позиции, но записи бэклога не создают.
    if (!issues.length) return null;

    const external = !String(pos.sourceProduct || '').startsWith('Antigravity');
    const supportStatus = matrixPos ? matrixPos.supportStatus : 'требует проверки';
    let owner;
    let nextStep;
    let rule;
    if (external) {
        owner = `внешний поставщик (${pos.sourceProduct})`;
        nextStep = 'Подтвердить у поставщика наличие официального переносимого сервера (MCP/API) и право переиспользования; результат зафиксировать в extensions-inventory.json (поля importPath/license) и перегенерировать матрицу. Название сервиса само по себе путь не подтверждает.';
        rule = 'external-unconfirmed-path';
    } else if (/лиценз/i.test(issues.join(' ')) || (matrixPos && matrixPos.licenseCheck && matrixPos.licenseCheck.rightToRedistribute === 'unknown')) {
        owner = 'antigravity-android (репозиторий)';
        nextStep = 'Зафиксировать лицензию/право распространения пакета в extensions-inventory.json (поле license); до подтверждения статус «полностью» не выдаётся.';
        rule = 'license-unconfirmed';
    } else if (/состав пуст|manifest/i.test(issues.join(' '))) {
        owner = 'antigravity-android (репозиторий)';
        nextStep = 'Восстановить состав пакета (plugin.json + реальные компоненты skills/hooks/mcp) либо снять позицию из обязательных; проверить сценарием из testScenario.';
        rule = 'composition-defect';
    } else if (/SKILL\.md\.disabled|тенев|тень|дубл|копи/i.test(issues.join(' '))) {
        owner = 'antigravity-android (репозиторий)';
        nextStep = 'Решение владельца данных: теневая/дублирующая копия SKILL.md.disabled оставлена без изменений (сохранение данных); поведение одной записи и отключение проверены extensions-stage0.test.js. Удалить дубль либо зафиксировать его каноническую роль.';
        rule = 'file-shadow';
    } else if (/disabled|отключ|выключен/i.test(issues.join(' '))) {
        owner = 'antigravity-android (репозиторий)';
        nextStep = 'Включить компонент (mcp_config.json / SKILL.md) и выполнить проверку по testScenario (рукопожатие/загрузка), затем обновить матрицу.';
        rule = 'component-disabled';
    } else if (/Android|исполнитель/i.test(issues.join(' '))) {
        owner = 'antigravity-android (репозиторий)';
        nextStep = 'Подтвердить Android-исполнение компонента запуском на устройстве (обязательная ветвь плана) либо явно указать зависимость от ПК в карточке; обновить матрицу.';
        rule = 'android-runtime-unproven';
    } else {
        owner = external ? `внешний поставщик (${pos.sourceProduct})` : 'antigravity-android (репозиторий)';
        nextStep = `Выполнить проверку по сценарию позиции (testScenario) и снять неопределённость: ${issues[0] || deps[0]}`;
        rule = 'unresolved-dependency';
    }
    return {
        id: `bl:${pos.id}`,
        positionId: pos.id,
        issues,
        dependencies: deps,
        supportStatus,
        owner,
        nextStep,
        rule
    };
}

// ---------------------------------------------------------------------------
// Основной проход
// ---------------------------------------------------------------------------

function buildCoverage() {
    const inventory = JSON.parse(fs.readFileSync(INV_PATH, 'utf8'));
    const matrix = JSON.parse(fs.readFileSync(MATRIX_PATH, 'utf8'));
    const matrixById = new Map((matrix.positions || []).map(p => [p.id, p]));

    // 100% учёт: каждая позиция среза обязана попасть в пакет прохода.
    const missingBatch = [];
    for (const p of inventory.positions) {
        if (!POSITION_BATCH[p.id]) missingBatch.push(p.id);
    }
    if (missingBatch.length) {
        throw new Error(`Позиции без пакета прохода (100% учёт сломан): ${missingBatch.join(', ')}`);
    }

    const positions = inventory.positions.map(p => {
        const m = matrixById.get(p.id);
        const portableMcp = require('../lib/official-mcp-catalog').descriptorForPosition(p.id);
        const pathCheck = portableMcp ? {
            confirmed: true,
            basis: 'официальный открытый сервер: проверены upstream-дескриптор и лицензия; живое подключение не проверялось',
            descriptorId: portableMcp.id,
            runtimeVerified: false,
            checked: [{ recorded: portableMcp.sourceUrl, resolved: path.join(PROJECT_ROOT, 'extensions/official-mcp.json'), exists: true }]
        } : checkImportPath(p.importPath);
        const external = !String(p.sourceProduct || '').startsWith('Antigravity');
        if (external && !pathCheck.confirmed) {
            pathCheck.basis = 'внешний сервис: переносимый сервер не подтверждён (название не гарантирует наличие сервера)';
        }
        const records = (m && Array.isArray(m.records)) ? m.records.map(r => ({
            componentType: r.componentType,
            strategy: r.strategy,
            status: r.status,
            executor: r.executor || null,
            blocker: r.blocker || null
        })) : [];
        return {
            id: p.id,
            canonicalId: p.canonicalId || p.id,
            name: p.name,
            sourceProduct: p.sourceProduct,
            batch: POSITION_BATCH[p.id],
            availability: p.availability,
            adaptation: {
                supportStatus: m ? m.supportStatus : 'требует проверки',
                fullSupportAllowed: m ? Boolean(m.fullSupportAllowed) : false,
                licenseRight: m && m.licenseCheck ? m.licenseCheck.rightToRedistribute : 'unknown',
                records
            },
            verificationScenario: p.testScenario || 'не записано',
            path: pathCheck,
            mandatory: external === false
        };
    });

    const backlog = [];
    for (const p of inventory.positions) {
        const entry = backlogFor(p, matrixById.get(p.id));
        if (entry) backlog.push(entry);
    }

    const byBatch = {};
    for (const b of BATCHES) {
        byBatch[b.id] = positions.filter(p => p.batch === b.id).length;
    }

    const mandatoryPositions = positions.filter(p => p.mandatory);
    const mandatoryBlocked = mandatoryPositions.filter(p => {
        const m = matrixById.get(p.id);
        return !m || (m.blockers && m.blockers.length > 0) || m.supportStatus === 'blocked';
    });

    const controlPackages = CONTROL_PACKAGES.map(c => ({
        ...c,
        fixturePresent: fs.existsSync(path.join(PROJECT_ROOT, ...c.fixture.split('/'))),
        verification: 'сквозной сценарий проверяется тестом pc-bridge/test/extensions-stage7.test.js (установка → включение → проверка → вызов)'
    }));

    return {
        schemaVersion: 1,
        title: 'Этап 7 — перенос всех позиций по матрице: полный проход инвентаря',
        generatedAt: new Date().toISOString(),
        generatedBy: 'pc-bridge/scripts/build-migration-coverage.js',
        sources: {
            inventory: 'extensions-inventory.json',
            adapterMatrix: 'extensions-adapter-matrix.json',
            plan: 'EXTENSIONS_INTEGRATION_PLAN.md (Этап 7)'
        },
        batches: BATCHES.map(b => ({
            ...b,
            positionCount: byBatch[b.id],
            positionIds: positions.filter(p => p.batch === b.id).map(p => p.id)
        })),
        controlPackages,
        positions,
        backlog,
        summary: {
            positionsTotal: positions.length,
            positionsWithAdaptation: positions.filter(p => p.adaptation.records.length > 0 || p.adaptation.supportStatus).length,
            positionsWithScenario: positions.filter(p => p.verificationScenario && p.verificationScenario !== 'не записано').length,
            byBatch,
            pathConfirmed: positions.filter(p => p.path.confirmed).length,
            pathUnconfirmed: positions.filter(p => !p.path.confirmed).length,
            mandatoryTotal: mandatoryPositions.length,
            mandatoryBlocked: mandatoryBlocked.map(p => p.id),
            backlogCount: backlog.length,
            overallMigrationStatus: mandatoryBlocked.length > 0 ? 'не завершено' : 'требует проверки',
            overallMigrationReason: mandatoryBlocked.length > 0
                ? `Общий перенос нельзя объявить завершённым: обязательные позиции заблокированы (${mandatoryBlocked.map(p => p.id).join(', ')})`
                : 'Обязательные позиции не заблокированы; статус остаётся «требует проверки» до сквозных прогонов'
        }
    };
}

if (require.main === module) {
    const coverage = buildCoverage();
    fs.writeFileSync(OUT_PATH, JSON.stringify(coverage, null, 2) + '\n', 'utf8');
    console.log(`written: ${OUT_PATH}`);
    console.log(`positions=${coverage.summary.positionsTotal} byBatch=${JSON.stringify(coverage.summary.byBatch)}`);
    console.log(`pathConfirmed=${coverage.summary.pathConfirmed} pathUnconfirmed=${coverage.summary.pathUnconfirmed}`);
    console.log(`backlog=${coverage.summary.backlogCount} mandatoryBlocked=${coverage.summary.mandatoryBlocked.length}`);
    console.log(`overallMigrationStatus=${coverage.summary.overallMigrationStatus}`);
}

module.exports = { buildCoverage, BATCHES, POSITION_BATCH, CONTROL_PACKAGES, checkImportPath, backlogFor };
