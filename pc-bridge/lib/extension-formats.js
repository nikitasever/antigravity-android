'use strict';
// Этап 2 (EXTENSIONS_INTEGRATION_PLAN.md): парсеры форматов расширений.
// Источники фактов о форматах — только реальные файлы проекта и официальные
// спеки из раздела «Официальные источники» плана:
//  - Agent Skills: SKILL.md (agentskills.io/specification);
//  - Claude Code: .claude-plugin/plugin.json (code.claude.com/docs/en/plugins/manifest-reference);
//  - OpenAI: plugin.json / .codex-plugin/plugin.json, skills/, mcp.json
//    (developers.openai.com/plugins/guides/submit-claude-plugin, /build/plugins).
// Принцип: неизвестный компонент или неподтверждённый формат получает явный
// статус «требует проверки», а не отождествляется с известным форматом.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// ---------------------------------------------------------------------------
// YAML-подмножество для front matter SKILL.md. Разбираются только те
// конструкции, которые встречаются в реальных SKILL.md проекта и спеке:
// скаляры, кавычки, блочные скаляры (| и >), вложенные карты и простые списки.
// Нераспознанные конструкции фиксируются в parseIssues, а не подгоняются.
// ---------------------------------------------------------------------------

function stripQuotes(value) {
    const v = value.trim();
    if ((v.startsWith('"') && v.endsWith('"') && v.length >= 2) ||
        (v.startsWith("'") && v.endsWith("'") && v.length >= 2)) {
        return v.slice(1, -1);
    }
    return v;
}

function parseInlineScalar(raw) {
    const v = raw.trim();
    if (v === '' || v === '~' || v === 'null') return null;
    if (/^-?\d+$/.test(v)) return Number(v);
    if (v === 'true') return true;
    if (v === 'false') return false;
    return stripQuotes(v);
}

function parseInlineArray(raw) {
    const inner = raw.trim().slice(1, -1).trim();
    if (!inner) return [];
    const items = [];
    let cur = '';
    let quote = null;
    for (const ch of inner) {
        if (quote) {
            cur += ch;
            if (ch === quote) quote = null;
        } else if (ch === '"' || ch === "'") {
            quote = ch;
            cur += ch;
        } else if (ch === ',') {
            items.push(parseInlineScalar(cur));
            cur = '';
        } else {
            cur += ch;
        }
    }
    if (cur.trim() !== '') items.push(parseInlineScalar(cur));
    return items;
}

function parseYamlSubset(text, issues, indentBase = 0) {
    const result = {};
    const lines = String(text).split(/\r?\n/);
    let i = 0;
    while (i < lines.length) {
        const line = lines[i];
        if (!line.trim() || /^\s*#/.test(line)) { i++; continue; }
        const indent = line.match(/^[ \t]*/)[0].length;
        if (indent < indentBase) break;
        if (indent > indentBase) {
            issues.push(`лишний отступ в строке: ${line.trim().slice(0, 40)}`);
            i++;
            continue;
        }
        if (/\t/.test(line.slice(0, indent + 1))) {
            issues.push(`табуляция в отступе: ${line.trim().slice(0, 40)}`);
            i++;
            continue;
        }
        const kv = line.slice(indentBase).match(/^([^:#\s][^:]*):(?:\s+(.*))?$/);
        if (!kv) {
            issues.push(`нераспознанная строка YAML: ${line.trim().slice(0, 40)}`);
            i++;
            continue;
        }
        const key = kv[1].trim().replace(/^["']|["']$/g, '');
        const rest = (kv[2] !== undefined ? kv[2] : '').trim();
        if (rest === '' ) {
            // Вложенный блок: карта или список с отступом.
            let j = i + 1;
            const childLines = [];
            while (j < lines.length && (!lines[j].trim() || /^[ \t]/.test(lines[j]))) {
                childLines.push(lines[j]);
                j++;
            }
            const meaningful = childLines.filter(l => l.trim() && !/^\s*#/.test(l));
            if (meaningful.length === 0) {
                result[key] = null;
            } else if (/^\s*- /.test(meaningful[0])) {
                const arr = [];
                for (const l of meaningful) {
                    const m = l.match(/^\s*-\s+(.*)$/);
                    if (m) arr.push(parseInlineScalar(m[1]));
                    else issues.push(`нераспознанный элемент списка: ${l.trim().slice(0, 40)}`);
                }
                result[key] = arr;
            } else {
                const childIndent = meaningful[0].match(/^[ \t]*/)[0].length;
                if (childIndent > indentBase) {
                    result[key] = parseYamlSubset(childLines.join('\n'), issues, childIndent);
                } else {
                    result[key] = null;
                    issues.push(`вложенный блок без отступа у ключа ${key}`);
                }
            }
            i = j;
            continue;
        }
        const blockScalar = rest.match(/^([|>])[+-]?\s*$/);
        if (blockScalar) {
            let j = i + 1;
            const collected = [];
            while (j < lines.length && (!lines[j].trim() || /^[ \t]/.test(lines[j]))) {
                collected.push(lines[j].replace(/^[ \t]+/, ''));
                j++;
            }
            result[key] = blockScalar[1] === '|'
                ? collected.join('\n').replace(/\s+$/, '')
                : collected.join(' ').replace(/\s+/g, ' ').trim();
            i = j;
            continue;
        }
        if (rest.startsWith('[')) {
            result[key] = parseInlineArray(rest);
        } else if (rest.startsWith('{')) {
            issues.push(`инлайновая карта не поддержана у ключа ${key}`);
            result[key] = rest;
        } else {
            result[key] = parseInlineScalar(rest);
        }
        i++;
    }
    return result;
}

function parseFrontMatter(rawMd) {
    const issues = [];
    const text = String(rawMd == null ? '' : rawMd);
    const m = text.match(/^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)([\s\S]*)$/);
    if (!m) {
        return { present: false, data: {}, body: text.trim(), raw: '', issues: ['front matter не найден'] };
    }
    const data = parseYamlSubset(m[1], issues, 0);
    return { present: true, data, body: (m[2] || '').trim(), raw: m[1], issues };
}

// ---------------------------------------------------------------------------
// SKILL.md (Agent Skills). Обязательные поля и ограничения — по спеке:
// name ≤64, [a-z0-9-], без дефисов по краям; description ≤1024 и непустое;
// compatibility ≤500; allowed-tools — строка через пробел; metadata — карта.
// ---------------------------------------------------------------------------

const SKILL_NAME_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;

function validateSkillFields(fields) {
    const issues = [];
    const name = fields.name;
    if (typeof name !== 'string' || !name.trim()) {
        issues.push({ field: 'name', problem: 'обязательное поле отсутствует' });
    } else {
        if (name.length > 64) issues.push({ field: 'name', problem: 'больше 64 символов' });
        if (!SKILL_NAME_RE.test(name)) {
            issues.push({ field: 'name', problem: 'разрешены только строчные буквы, цифры и дефисы без дефисов по краям' });
        }
    }
    const desc = fields.description;
    if (typeof desc !== 'string' || !desc.trim()) {
        issues.push({ field: 'description', problem: 'обязательное поле отсутствует или пустое' });
    } else if (desc.length > 1024) {
        issues.push({ field: 'description', problem: 'больше 1024 символов' });
    }
    if (fields.compatibility != null && String(fields.compatibility).length > 500) {
        issues.push({ field: 'compatibility', problem: 'больше 500 символов' });
    }
    if (fields.metadata != null && (typeof fields.metadata !== 'object' || Array.isArray(fields.metadata))) {
        issues.push({ field: 'metadata', problem: 'ожидается карта ключ-значение' });
    }
    return { valid: issues.length === 0, issues };
}

function parseSkillMarkdown(rawMd, fallbackName) {
    const fm = parseFrontMatter(rawMd);
    const data = fm.data || {};
    const metadata = (data.metadata && typeof data.metadata === 'object' && !Array.isArray(data.metadata))
        ? data.metadata
        : (data.metadata == null ? {} : null);
    const allowedTools = typeof data['allowed-tools'] === 'string'
        ? data['allowed-tools'].split(/\s+/).filter(Boolean)
        : (Array.isArray(data['allowed-tools']) ? data['allowed-tools'].map(String) : null);
    const fields = {
        name: typeof data.name === 'string' ? data.name.trim().replace(/^["']|["']$/g, '') : (typeof data.name === 'number' ? String(data.name) : null),
        description: typeof data.description === 'string' ? data.description.trim() : (data.description == null ? '' : String(data.description)),
        license: data.license == null ? null : String(data.license),
        compatibility: data.compatibility == null ? null : String(data.compatibility),
        metadata,
        allowedTools
    };
    const specValidation = validateSkillFields(fields);
    return {
        name: fields.name || fallbackName || null,
        nameSource: fields.name ? 'frontmatter' : 'fallback',
        description: fields.description || '',
        license: fields.license,
        compatibility: fields.compatibility,
        metadata,
        allowedTools,
        instruction: fm.body,
        frontmatter: data,
        hasFrontmatter: fm.present,
        specValidation,
        parseIssues: fm.issues.slice()
    };
}

// ---------------------------------------------------------------------------
// Структура полного пакета навыка: SKILL.md + scripts/ + references/ + assets/
// (спека Agent Skills). Пути, размеры, sha256 каждого файла, исполняемые
// компоненты, ссылки тела на файлы пакета и их наличие.
// ---------------------------------------------------------------------------

const EXECUTABLE_EXTENSIONS = new Set([
    '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd', '.py', '.pyw', '.js', '.mjs', '.cjs',
    '.exe', '.dll', '.so', '.dylib', '.jar', '.apk', '.msi', '.com', '.scr', '.rb', '.pl', '.php', '.lua'
]);

function sha256Of(buf) {
    return crypto.createHash('sha256').update(buf).digest('hex');
}

function isExecutableFile(relPath, content) {
    const ext = path.posix.extname(relPath).toLowerCase();
    if (EXECUTABLE_EXTENSIONS.has(ext)) return { executable: true, reason: `расширение ${ext}` };
    const head = content && content.length >= 2 ? content.slice(0, 2).toString('utf8') : '';
    if (head === '#!') return { executable: true, reason: 'shebang' };
    return { executable: false, reason: '' };
}

function categorizeSkillFile(relPath) {
    const top = relPath.split('/')[0];
    if (/^SKILL\.md(\.disabled)?$/i.test(relPath)) return 'skill-md';
    if (top === 'scripts') return 'script';
    if (top === 'references') return 'reference';
    if (top === 'assets') return 'asset';
    return 'other';
}

function walkFiles(rootDir, { maxFiles = 4096, maxTotalBytes = 64 * 1024 * 1024 } = {}) {
    const files = [];
    const issues = [];
    let total = 0;
    const stack = [{ dir: rootDir, rel: '' }];
    while (stack.length) {
        const { dir, rel } = stack.pop();
        let entries;
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); }
        catch (err) { issues.push(`не удалось прочитать каталог ${rel || '.'}: ${err.message}`); continue; }
        for (const entry of entries) {
            const relPath = rel ? `${rel}/${entry.name}` : entry.name;
            const abs = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                stack.push({ dir: abs, rel: relPath });
            } else if (entry.isFile()) {
                if (files.length >= maxFiles) {
                    issues.push(`превышен лимит файлов (${maxFiles})`);
                    return { files, issues, totalBytes: total };
                }
                let content;
                try { content = fs.readFileSync(abs); }
                catch (err) { issues.push(`не удалось прочитать файл ${relPath}: ${err.message}`); continue; }
                total += content.length;
                if (total > maxTotalBytes) {
                    issues.push(`превышен лимит суммарного размера (${maxTotalBytes} байт)`);
                    return { files, issues, totalBytes: total };
                }
                const execInfo = isExecutableFile(relPath, content);
                files.push({
                    path: relPath.split(path.sep).join('/'),
                    size: content.length,
                    sha256: sha256Of(content),
                    executable: execInfo.executable,
                    executableReason: execInfo.reason,
                    category: categorizeSkillFile(relPath.split(path.sep).join('/'))
                });
            }
        }
    }
    files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
    return { files, issues, totalBytes: total };
}

function packageDigestOf(files) {
    const lines = files.map(f => `${f.path}:${f.sha256}`).sort();
    return sha256Of(Buffer.from(lines.join('\n'), 'utf8'));
}

// Извлекает ссылки вида references/…, scripts/…, assets/… из тела инструкции
// (проверка ссылок и зависимостей из §2 плана: «Импорт всего пакета; проверка
// ссылок и зависимостей»).
function findFileReferences(instruction) {
    const refs = new Set();
    const re = /(?:^|[\s`"'(])((?:references|scripts|assets)\/[A-Za-z0-9._\-/]+)/g;
    let m;
    while ((m = re.exec(String(instruction || ''))) !== null) refs.add(m[1]);
    return [...refs];
}

// Честный эвристический поиск зависимостей скриптов: только явные импорты
// модулей JS/Python. Помечается inferred — это не доказательство окружения.
function findScriptDependencies(files, rootDir) {
    const deps = new Set();
    for (const f of files) {
        if (f.category !== 'script') continue;
        const ext = path.posix.extname(f.path).toLowerCase();
        if (!['.js', '.mjs', '.cjs', '.py'].includes(ext)) continue;
        let text = '';
        try { text = fs.readFileSync(path.join(rootDir, ...f.path.split('/')), 'utf8').slice(0, 262144); }
        catch (_) { continue; }
        if (ext === '.py') {
            for (const m of text.matchAll(/^\s*(?:from|import)\s+([A-Za-z_][A-Za-z0-9_]*)/gm)) deps.add(m[1]);
        } else {
            for (const m of text.matchAll(/require\(\s*['"]([^'"./][^'"]*)['"]\s*\)|from\s+['"]([^'"./][^'"]*)['"]/g)) {
                const mod = m[1] || m[2];
                if (mod) deps.add(mod.split('/')[0].startsWith('@') ? mod.split('/').slice(0, 2).join('/') : mod.split('/')[0]);
            }
        }
    }
    return [...deps].sort();
}

function analyzeSkillPackage(dirPath, opts = {}) {
    const { files, issues, totalBytes } = walkFiles(dirPath, opts);
    const activeFile = files.find(f => f.path === 'SKILL.md');
    const disabledFile = files.find(f => f.path === 'SKILL.md.disabled');
    const parseIssues = issues.slice();
    if (!activeFile && !disabledFile) {
        parseIssues.push('SKILL.md не найден — пакет не является навыком Agent Skills');
    }
    if (activeFile && disabledFile) {
        parseIssues.push('найдены и SKILL.md, и SKILL.md.disabled — состояние активности неоднозначно');
    }
    let parsed = null;
    const mdPath = activeFile ? path.join(dirPath, 'SKILL.md')
        : (disabledFile ? path.join(dirPath, 'SKILL.md.disabled') : null);
    if (mdPath) {
        try {
            parsed = parseSkillMarkdown(fs.readFileSync(mdPath, 'utf8'), path.basename(dirPath));
        } catch (err) {
            parseIssues.push(`не удалось прочитать SKILL.md: ${err.message}`);
        }
    }
    const body = parsed ? parsed.instruction : '';
    const referenced = findFileReferences(body);
    const missingReferences = referenced.filter(ref =>
        !files.some(f => f.path === ref || f.path === `${ref}.disabled`));
    return {
        kind: 'skill-package',
        rootName: path.basename(dirPath),
        enabled: Boolean(activeFile),
        skill: parsed,
        files,
        fileCount: files.length,
        totalBytes,
        packageDigest: packageDigestOf(files),
        executableComponents: files.filter(f => f.executable),
        categories: {
            skillMd: files.filter(f => f.category === 'skill-md').map(f => f.path),
            scripts: files.filter(f => f.category === 'script').map(f => f.path),
            references: files.filter(f => f.category === 'reference').map(f => f.path),
            assets: files.filter(f => f.category === 'asset').map(f => f.path),
            other: files.filter(f => f.category === 'other').map(f => f.path)
        },
        fileReferences: referenced,
        missingReferences,
        scriptDependencies: {
            inferred: true,
            modules: findScriptDependencies(files, dirPath)
        },
        parseIssues
    };
}

// ---------------------------------------------------------------------------
// Manifest-форматы плагинов. Формы (по официальным источникам):
//  - Claude Code:  .claude-plugin/plugin.json (много компонентов);
//  - OpenAI:       корневой plugin.json (Agent Plugins, $schema agent-plugins.org)
//                  и/или .codex-plugin/plugin.json (совместимость);
//  - наш локальный: plugin.json с verifiedTools (создан managePlugin create);
//  - минимальный:  plugin.json c name/version/description без состава.
// ---------------------------------------------------------------------------

const CLAUDE_MANIFEST_KEYS = new Set([
    '$schema', 'name', 'displayName', 'version', 'description', 'author', 'homepage',
    'repository', 'license', 'keywords', 'metadata', 'icon', 'documentationUrl',
    'supportUrl', 'privacyPolicyUrl', 'termsOfServiceUrl', 'defaultEnabled',
    'dependencies', 'settings', 'userConfig', 'types', 'channels', 'skills',
    'commands', 'agents', 'hooks', 'mcpServers', 'lspServers', 'outputStyles',
    'workflows', 'experimental'
]);

const COMPONENT_KINDS = ['skills', 'commands', 'agents', 'hooks', 'mcpServers', 'lspServers', 'outputStyles', 'workflows'];

// Поддержка компонентов при переносе — по таблице OpenAI
// (submit-claude-plugin): что оставить, что преобразовать, что убрать.
// Неизвестный вид компонента получает явный статус, а не «поддержан».
const COMPONENT_SUPPORT = {
    skill: {
        support: 'supported',
        status: 'поддержан',
        note: 'SKILL.md + scripts/references/assets переносятся как есть (Agent Skills)'
    },
    rule: {
        support: 'supported',
        status: 'поддержан',
        note: 'rules/AGENTS.md (или GEMINI.md) — markdown без frontmatter, всегда активен при включённом плагине (docs/rules.md)'
    },
    command: {
        support: 'convert',
        status: 'требует преобразования в навык',
        note: 'OpenAI требует превратить markdown-команду в навык (submit-claude-plugin)'
    },
    agent: {
        support: 'convert',
        status: 'требует преобразования в навык',
        note: 'процедуры агента переносятся в навыки (submit-claude-plugin)'
    },
    hook: {
        support: 'unsupported-public',
        status: 'не переносится автоматически',
        note: 'жизненные циклы hooks удаляются для публичного каталога; допустимы только при ручной установке'
    },
    'mcp-server': {
        support: 'partial',
        status: 'требует MCP-клиента (Этап 3)',
        note: 'удалённый HTTPS Streamable HTTP — публичный путь; локальный stdio — только desktop/PC-Bridge'
    },
    'lsp-server': {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'lspServers не переносится (submit-claude-plugin)'
    },
    'output-style': {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'outputStyles не переносится; суть — в навык'
    },
    workflow: {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'workflows — расширение Claude Code, не переносится'
    },
    theme: {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'themes — продуктовый UI-компонент Claude'
    },
    monitor: {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'monitors — расширение Claude Code'
    },
    executable: {
        support: 'partial',
        status: 'требует проверки исполнителя',
        note: 'исполняемые файлы (bin/, скрипты) требуют проверенного исполнителя и зависимостей (Этап 5)'
    },
    settings: {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'settings/CLAUDE.md — настройки Claude Code, не переносятся'
    },
    userConfig: {
        support: 'unsupported',
        status: 'не поддерживается',
        note: 'userConfig не исполняется OpenAI; значения заменяются входами/OAuth (submit-claude-plugin)'
    },
    app: {
        support: 'partial',
        status: 'требует подключения сервиса',
        note: '.app.json/apps — зарегистрированные MCP-маппинги; путь через подключение сервиса'
    },
    asset: {
        support: 'supported',
        status: 'поддержан',
        note: 'статические ресурсы переносятся вместе с пакетом'
    },
    unknown: {
        support: 'unknown',
        status: 'требует проверки',
        note: 'вид компонента не отнесён к известным; состав подтверждён не полностью'
    }
};

function classifyComponent(kind) {
    return COMPONENT_SUPPORT[kind] || COMPONENT_SUPPORT.unknown;
}

function normalizeComponentRef(value) {
    if (value == null) return { paths: [], inline: [] };
    if (typeof value === 'string') return { paths: [value], inline: [] };
    if (Array.isArray(value)) {
        const paths = [];
        const inline = [];
        for (const v of value) {
            if (typeof v === 'string') paths.push(v);
            else if (v && typeof v === 'object') inline.push(v);
        }
        return { paths, inline };
    }
    if (typeof value === 'object') return { paths: [], inline: [value] };
    return { paths: [], inline: [], issues: [`неподдерживаемая форма объявления компонента: ${typeof value}`] };
}

function normalizeDependencies(rawDeps) {
    if (!Array.isArray(rawDeps)) return { items: [], issues: rawDeps == null ? [] : ['dependencies: ожидается список'] };
    const items = [];
    const issues = [];
    for (const d of rawDeps) {
        if (typeof d === 'string') {
            const [name, marketplace] = d.split('@');
            items.push({ name, marketplace: marketplace || null, version: null });
        } else if (d && typeof d === 'object') {
            items.push({
                name: String(d.name || ''),
                marketplace: d.marketplace ? String(d.marketplace) : null,
                version: d.version ? String(d.version) : null
            });
        } else {
            issues.push(`нераспознанная запись зависимости: ${String(d).slice(0, 40)}`);
        }
    }
    return { items, issues };
}

function normalizeUserConfigKeys(userConfig) {
    if (!userConfig || typeof userConfig !== 'object') return { keys: [], issues: userConfig == null ? [] : ['userConfig: ожидается объект'] };
    const keys = [];
    const issues = [];
    for (const [key, spec] of Object.entries(userConfig)) {
        if (spec && typeof spec === 'object') {
            // Значения не читаются и не сохраняются никогда — только схема ключа.
            keys.push({
                key,
                type: spec.type ? String(spec.type) : null,
                sensitive: Boolean(spec.sensitive),
                required: Boolean(spec.required),
                title: spec.title ? String(spec.title) : null
            });
        } else {
            issues.push(`userConfig.${key}: ожидается объект схемы`);
        }
    }
    return { keys, issues };
}

function detectManifestFlavor(manifestPath, raw) {
    const rel = String(manifestPath || '').split(path.sep).join('/');
    if (rel.endsWith('.claude-plugin/plugin.json')) return 'claude-code';
    if (rel.endsWith('.codex-plugin/plugin.json')) return 'codex-compat';
    if (raw && typeof raw.$schema === 'string' && raw.$schema.includes('agent-plugins.org')) return 'agent-plugins';
    if (raw && Array.isArray(raw.verifiedTools)) return 'antigravity-local';
    if (rel.endsWith('plugin.json')) return 'minimal';
    return 'unknown';
}

function normalizePluginManifest(rawManifest, ctx = {}) {
    const issues = [];
    const raw = rawManifest && typeof rawManifest === 'object' ? rawManifest : {};
    if (!rawManifest || typeof rawManifest !== 'object') issues.push('manifest не является JSON-объектом');
    const flavor = detectManifestFlavor(ctx.manifestPath, raw);
    const unknownTopLevelKeys = Object.keys(raw).filter(k => !CLAUDE_MANIFEST_KEYS.has(k) && k !== 'verifiedTools' && k !== 'created');
    const deps = normalizeDependencies(raw.dependencies);
    const userConfig = normalizeUserConfigKeys(raw.userConfig);
    const declaredComponents = {};
    for (const kind of COMPONENT_KINDS) {
        if (raw[kind] != null) declaredComponents[kind] = normalizeComponentRef(raw[kind]);
    }
    // Состав: manifest объявил компоненты → частично подтверждён; минимальный
    // manifest без состава → честное «требует проверки» (не «пустой»).
    const declaredKinds = Object.keys(declaredComponents).filter(k => {
        const c = declaredComponents[k];
        return (c.paths && c.paths.length) || (c.inline && c.inline.length);
    });
    const hasSkillsDir = Boolean(ctx.hasSkillsDir);
    const compositionStatus = (declaredKinds.length > 0 || hasSkillsDir)
        ? (ctx.compositionScanned ? 'подтверждено сканированием' : 'объявлено в manifest, требуется просмотр состава')
        : 'требует проверки';
    return {
        manifestFlavor: flavor,
        manifestPath: ctx.manifestPath ? String(ctx.manifestPath).split(path.sep).join('/') : null,
        name: raw.name ? String(raw.name) : null,
        displayName: raw.displayName ? String(raw.displayName) : null,
        version: raw.version ? String(raw.version) : null,
        description: raw.description ? String(raw.description) : (raw.description === '' ? '' : null),
        author: raw.author && typeof raw.author === 'object'
            ? { name: raw.author.name ? String(raw.author.name) : null, email: raw.author.email ? String(raw.author.email) : null, url: raw.author.url ? String(raw.author.url) : null }
            : (raw.author ? { name: String(raw.author), email: null, url: null } : null),
        homepage: raw.homepage ? String(raw.homepage) : null,
        repository: raw.repository ? String(raw.repository) : null,
        license: raw.license ? String(raw.license) : null,
        keywords: Array.isArray(raw.keywords) ? raw.keywords.map(String) : [],
        metadata: raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : null,
        defaultEnabled: raw.defaultEnabled == null ? null : Boolean(raw.defaultEnabled),
        dependencies: deps.items,
        userConfigKeys: userConfig.keys,
        declaredComponents,
        declaredKinds,
        unknownTopLevelKeys,
        compositionStatus,
        openaiTransfer: {
            skills: 'поддержан (skills/<name>/SKILL.md + scripts/references/assets)',
            commands: 'требует преобразования в навыки',
            agents: 'требует преобразования в навыки',
            hooks: 'удаляется для публичного каталога; ручная установка в Codex desktop',
            userConfig: 'не исполняется — заменить входами/OAuth',
            mcpServers: 'удалённый HTTPS Streamable HTTP — да; локальный stdio — desktop-only',
            otherDeclarations: 'outputStyles/lspServers/experimental/channels/dependencies не переносятся'
        },
        parseIssues: issues.concat(deps.issues, userConfig.issues)
    };
}

// ---------------------------------------------------------------------------
// Состав пакета плагина: сканирование фактических файлов + связь
// «плагин → навыки» (блокер chatgpt-plugin-management: удаление/перенос
// плагина должен сохранять состав, а не только manifest).
// ---------------------------------------------------------------------------

const DEFAULT_LAYOUT = [
    { dir: '.claude-plugin', kind: 'manifest' },
    { dir: '.codex-plugin', kind: 'manifest' },
    { dir: 'skills', kind: 'skill' },
    { dir: 'commands', kind: 'command' },
    { dir: 'agents', kind: 'agent' },
    { dir: 'hooks', kind: 'hook' },
    { dir: 'output-styles', kind: 'output-style' },
    { dir: 'workflows', kind: 'workflow' },
    { dir: 'themes', kind: 'theme' },
    { dir: 'monitors', kind: 'monitor' },
    { dir: 'bin', kind: 'executable' },
    { dir: 'assets', kind: 'asset' },
    { dir: 'scripts', kind: 'executable' }
];

function classifyLayoutPath(relPath) {
    const parts = relPath.split('/');
    const top = parts[0];
    if (relPath === 'plugin.json' || top === '.claude-plugin' || top === '.codex-plugin') return 'manifest';
    if (relPath === '.mcp.json' || relPath === 'mcp.json' || relPath === 'mcp_config.json' || relPath === '.mcpb') return 'mcp-server';
    if (relPath === '.lsp.json') return 'lsp-server';
    if (relPath === 'hooks.json' || top === 'hooks') return 'hook';
    if (top === 'rules') return 'rule';
    if (relPath === '.app.json' || relPath === 'apps.json') return 'app';
    if (relPath === 'settings.json' || relPath === 'CLAUDE.md') return 'settings';
    if (top === 'skills') return 'skill';
    if (top === 'commands') return 'command';
    if (top === 'agents') return 'agent';
    if (top === 'output-styles') return 'output-style';
    if (top === 'workflows') return 'workflow';
    if (top === 'themes') return 'theme';
    if (top === 'monitors') return 'monitor';
    if (top === 'bin') return 'executable';
    if (top === 'assets') return 'asset';
    if (relPath === 'README.md') return 'asset';
    if (top === 'scripts') return 'executable';
    return 'unknown';
}

function analyzePluginComposition(rootDir, opts = {}) {
    const { files, issues, totalBytes } = walkFiles(rootDir, opts);
    const parseIssues = issues.slice();
    const manifests = [];
    for (const candidate of ['.claude-plugin/plugin.json', 'plugin.json', '.codex-plugin/plugin.json']) {
        const f = files.find(x => x.path === candidate);
        if (!f) continue;
        try {
            const raw = JSON.parse(fs.readFileSync(path.join(rootDir, ...candidate.split('/')), 'utf8'));
            manifests.push(normalizePluginManifest(raw, {
                manifestPath: candidate,
                hasSkillsDir: files.some(x => x.path.startsWith('skills/') && x.category !== 'other'),
                compositionScanned: true
            }));
        } catch (err) {
            parseIssues.push(`не удалось разобрать ${candidate}: ${err.message}`);
        }
    }
    // Скан manifest-объявлённых ссылок: существование путей (согласно правилам
    // путей Claude: пути должны существовать и не выходить за корень).
    for (const manifest of manifests) {
        for (const kind of Object.keys(manifest.declaredComponents || {})) {
            const comp = manifest.declaredComponents[kind];
            for (const p of comp.paths || []) {
                const clean = String(p).replace(/^\.\//, '');
                if (clean.includes('..')) {
                    parseIssues.push(`${kind}: путь выходит за корень пакета: ${p}`);
                    continue;
                }
                const exists = clean === '.' || files.some(f => f.path === clean || f.path.startsWith(clean.endsWith('/') ? clean : `${clean}/`));
                if (!exists) parseIssues.push(`${kind}: путь не найден: ${p}`);
            }
        }
    }
    const components = [];
    const pluginSkillLinks = [];
    const pluginName = (manifests[0] && manifests[0].name) || path.basename(rootDir);
    for (const f of files) {
        const kind = classifyLayoutPath(f.path);
        const support = classifyComponent(kind);
        components.push({
            path: f.path,
            kind,
            size: f.size,
            sha256: f.sha256,
            executable: f.executable,
            support: support.support,
            status: support.status,
            note: support.note
        });
        if (kind === 'skill' && /(^|\/)SKILL\.md(\.disabled)?$/i.test(f.path)) {
            const skillDirRel = f.path.split('/').slice(0, -1).join('/');
            if (!pluginSkillLinks.some(l => l.skillPath === skillDirRel)) {
                pluginSkillLinks.push({ plugin: pluginName, skillPath: skillDirRel, owned: true });
            }
        }
    }
    const unknownComponents = components.filter(c => c.kind === 'unknown');
    const skills = [];
    for (const link of pluginSkillLinks) {
        const skillDir = path.join(rootDir, ...link.skillPath.split('/'));
        skills.push(Object.assign(analyzeSkillPackage(skillDir, opts), {
            ownerPlugin: pluginName,
            relativePath: link.skillPath
        }));
    }
    return {
        kind: 'plugin-package',
        rootName: path.basename(rootDir),
        pluginName,
        manifests,
        files,
        fileCount: files.length,
        totalBytes,
        packageDigest: packageDigestOf(files),
        components,
        executableComponents: components.filter(c => c.executable),
        pluginSkillLinks,
        skills,
        unknownComponents: unknownComponents.map(c => ({
            path: c.path,
            status: 'требует проверки',
            reason: 'файл не отнесён к известным видам компонентов'
        })),
        parseIssues
    };
}

// ---------------------------------------------------------------------------
// MCP-конфигурация: stdio (command/args/env), http/sse/streamable-http (url),
// объявления авторизации OpenAI (mcp.json extensions.com.openai.auth).
// Секреты (env-значения, заголовки) не извлекаются — только признак наличия.
// ---------------------------------------------------------------------------

function parseMcpServerEntry(name, cfg) {
    const issues = [];
    const c = cfg && typeof cfg === 'object' ? cfg : {};
    let transport = 'stdio';
    if (c.type === 'streamable-http' || c.type === 'http') transport = 'streamable-http';
    else if (c.type === 'sse') transport = 'sse';
    else if (c.type === 'stdio') transport = 'stdio';
    else if (c.url) transport = /^https?:/i.test(String(c.url)) ? (c.type || 'http') : 'stdio';
    const authDecl = c.extensions && c.extensions['com.openai'] && c.extensions['com.openai'].auth;
    const authType = authDecl && authDecl.type ? String(authDecl.type) : null;
    const envKeys = c.env && typeof c.env === 'object' ? Object.keys(c.env) : [];
    const headerKeys = c.headers && typeof c.headers === 'object' ? Object.keys(c.headers) : [];
    if (!c.command && !c.url) issues.push('нет ни command, ни url');
    return {
        id: String(name),
        transport,
        command: c.command ? String(c.command) : null,
        args: Array.isArray(c.args) ? c.args.map(String) : [],
        url: c.url ? String(c.url) : null,
        authType,
        envKeys,
        headerKeys,
        sensitiveFieldsPresent: envKeys.length > 0 || headerKeys.length > 0 || Boolean(c.headersHelper),
        disabled: Boolean(c.disabled),
        issues
    };
}

function parseMcpConfig(rawConfig) {
    const map = (rawConfig && rawConfig.mcpServers && typeof rawConfig.mcpServers === 'object') ? rawConfig.mcpServers : {};
    const servers = [];
    const issues = [];
    for (const [name, cfg] of Object.entries(map)) {
        const entry = parseMcpServerEntry(name, cfg);
        issues.push(...entry.issues.map(i => `${name}: ${i}`));
        servers.push(entry);
    }
    return { servers, issues };
}

// ---------------------------------------------------------------------------
// Разделение продуктов (блокер codex-remote-composer-extensions): плагин
// Codex Remote внутри APK ChatGPT нельзя считать плагином Android ChatGPT.
// ---------------------------------------------------------------------------

const PRODUCT_RULES = [
    { re: /chatgpt-android:remote:/i, product: 'Codex (Remote)' },
    { re: /remote[- ]composer/i, product: 'Codex (Remote)' },
    { re: /\bcodex\b/i, product: 'Codex' },
    { re: /chatgpt/i, product: 'ChatGPT' },
    { re: /claude/i, product: 'Claude' },
    { re: /antigravity|gemini-cli/i, product: 'Antigravity' }
];

function classifySourceProduct({ sourceId = '', name = '', description = '', declaredProduct = '' } = {}) {
    const haystack = `${sourceId} ${name} ${description}`;
    let hinted = null;
    for (const rule of PRODUCT_RULES) {
        if (rule.re.test(haystack)) { hinted = rule.product; break; }
    }
    const conflict = Boolean(hinted && declaredProduct && hinted !== declaredProduct &&
        !(declaredProduct.startsWith(hinted) || hinted.startsWith(declaredProduct)));
    return {
        declaredProduct: declaredProduct || null,
        hintedProduct: hinted,
        conflict,
        resolvedProduct: conflict ? hinted : (declaredProduct || hinted || 'не определено'),
        note: conflict
            ? `объявленный продукт «${declaredProduct}» расходится с подсказкой «${hinted}» — по §2 плана продукты не смешиваются`
            : null
    };
}

// ---------------------------------------------------------------------------
// Навыки ChatGPT (блокер chatgpt-component-skill): формат файлов навыков
// ChatGPT для Android не подтверждён ни дампом, ни спекой для этого приложения.
// Парсер НЕ отождествляет его с Agent Skills SKILL.md.
// ---------------------------------------------------------------------------

function parseChatgptSkillCard(raw = {}, evidence = []) {
    return {
        sourceProduct: 'ChatGPT for Android',
        sourceId: raw.sourceId ? String(raw.sourceId) : null,
        name: raw.name ? String(raw.name) : null,
        includedFiles: Array.isArray(raw.includedFiles) ? raw.includedFiles.map(String) : [],
        usedBy: Array.isArray(raw.usedBy) ? raw.usedBy.map(String) : [],
        accessNotes: raw.accessNotes ? String(raw.accessNotes) : null,
        format: 'unknown',
        formatStatus: 'требует проверки',
        assumedSkillMd: false,
        blocker: 'Формат файлов навыков ChatGPT для Android не подтверждён: дамп показывает карточку (Skill details / Included files / Used by), но не структуру файлов. Парсер не отождествляет навык ChatGPT с Agent Skills SKILL.md без проверки формата живьём.',
        evidence: evidence.slice(),
        parseIssues: []
    };
}

module.exports = {
    parseFrontMatter,
    parseSkillMarkdown,
    validateSkillFields,
    analyzeSkillPackage,
    normalizePluginManifest,
    analyzePluginComposition,
    detectManifestFlavor,
    classifyComponent,
    classifySourceProduct,
    parseChatgptSkillCard,
    parseMcpServerEntry,
    parseMcpConfig,
    walkFiles,
    packageDigestOf,
    findFileReferences,
    sha256Of,
    isExecutableFile,
    COMPONENT_SUPPORT,
    CLAUDE_MANIFEST_KEYS,
    EXECUTABLE_EXTENSIONS,
    SKILL_NAME_RE
};
