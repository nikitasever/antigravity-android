'use strict';
// Этап 2 плана расширений (EXTENSIONS_INTEGRATION_PLAN.md): парсеры форматов,
// архивный слой и общий реестр. Проверяются блокеры из EXTENSIONS_COMPATIBILITY.md
// §11 и критерии готовности Этапа 2:
//   1) парсер формата навыков ChatGPT (честное «требует проверки»);
//   2) связь «плагин → навыки» сохраняется при разборе пакета;
//   3) разделение Codex/ChatGPT;
//   4) единый источник состояния плагинов и MCP (расхождения не скрываются);
//   5) честная карточка «требует проверки» для manifest без состава;
//   + импорт → просмотр → установка → перезапуск → отключение → удаление
//     без потери соседних пакетов; проверки архива (пути, размер, хэш,
//     коллизии имён, исполняемые компоненты).
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const formats = require('../lib/extension-formats');
const archive = require('../lib/extension-archive');
const registry = require('../lib/extension-registry');

function tmpDir(t, prefix = 'stage2-') {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    return dir;
}

function write(root, rel, content) {
    const abs = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
    return abs;
}

// ---------------------------------------------------------------------------
// 1. Парсер SKILL.md (Agent Skills): полные поля и валидация по спеке.
// ---------------------------------------------------------------------------

test('parseSkillMarkdown разбирает полный набор полей Agent Skills', () => {
    const md = [
        '---',
        'name: full-skill',
        'description: "Описание навыка"',
        'license: MIT',
        'compatibility: требуется сеть',
        'allowed-tools: "Bash(git:*) Edit"',
        'metadata:',
        '  version: "49"',
        '  tool_count: 229',
        '---',
        '',
        'Тело инструкции.'
    ].join('\n');
    const parsed = formats.parseSkillMarkdown(md, 'fallback');
    assert.equal(parsed.name, 'full-skill');
    assert.equal(parsed.description, 'Описание навыка');
    assert.equal(parsed.license, 'MIT');
    assert.equal(parsed.compatibility, 'требуется сеть');
    assert.deepEqual(parsed.allowedTools, ['Bash(git:*)', 'Edit']);
    assert.equal(parsed.metadata.version, '49');
    assert.equal(parsed.metadata.tool_count, 229);
    assert.equal(parsed.instruction, 'Тело инструкции.');
    assert.equal(parsed.specValidation.valid, true, 'валидация спеки проходит');
    assert.deepEqual(parsed.parseIssues, []);
});

test('parseSkillMarkdown фиксирует нарушения спеки Agent Skills, не «чинит» их', () => {
    const md = '---\nname: Bad_Name\ndescription: \n---\nТекст';
    const parsed = formats.parseSkillMarkdown(md, 'fallback');
    const fields = parsed.specValidation.issues.map(i => i.field);
    assert.ok(fields.includes('name'), 'имя с подчёркиванием/заглавными не проходит спеку');
    assert.ok(fields.includes('description'), 'пустое описание не проходит');
    assert.equal(parsed.specValidation.valid, false);
});

// ---------------------------------------------------------------------------
// 2. Полная структура пакета навыка: scripts/references/assets, хэши,
//    исполняемые компоненты, проверка ссылок.
// ---------------------------------------------------------------------------

test('analyzeSkillPackage собирает полную структуру пакета с хэшами и ссылками', t => {
    const dir = tmpDir(t);
    const pkg = path.join(dir, 'demo-pkg');
    write(pkg, 'SKILL.md', '---\nname: demo-pkg\ndescription: Демо\n---\nСмотри references/guide.md и scripts/run.py');
    write(pkg, 'references/guide.md', '# Гайд');
    write(pkg, 'scripts/run.py', '#!/usr/bin/env python\nprint(1)');
    write(pkg, 'assets/tpl.txt', 'шаблон');
    write(pkg, 'stray.bin', 'прочее');

    const analysis = formats.analyzeSkillPackage(pkg);
    assert.equal(analysis.enabled, true);
    assert.equal(analysis.skill.name, 'demo-pkg');
    assert.equal(analysis.fileCount, 5);
    assert.ok(analysis.packageDigest && analysis.packageDigest.length === 64, 'packageDigest — sha256');
    assert.deepEqual(analysis.categories.scripts, ['scripts/run.py']);
    assert.deepEqual(analysis.categories.references, ['references/guide.md']);
    assert.deepEqual(analysis.categories.assets, ['assets/tpl.txt']);
    assert.deepEqual(analysis.categories.other, ['stray.bin']);
    assert.equal(analysis.executableComponents.length, 1);
    assert.equal(analysis.executableComponents[0].path, 'scripts/run.py');
    assert.deepEqual(analysis.missingReferences, [], 'все ссылки тела существуют');
    assert.deepEqual(analysis.scriptDependencies.modules, [], 'py-скрипт без внешних импортов');
    assert.deepEqual(analysis.parseIssues, []);
});

test('analyzeSkillPackage честно показывает битые ссылки и .disabled', t => {
    const dir = tmpDir(t);
    const pkg = path.join(dir, 'off-pkg');
    write(pkg, 'SKILL.md.disabled', '---\nname: off-pkg\ndescription: Отключён\n---\nНужен references/lost.md');
    const analysis = formats.analyzeSkillPackage(pkg);
    assert.equal(analysis.enabled, false);
    assert.deepEqual(analysis.missingReferences, ['references/lost.md']);
});

// ---------------------------------------------------------------------------
// 3. Manifest-форматы Claude/OpenAI: вкусы, состав, зависимости, userConfig.
// ---------------------------------------------------------------------------

test('normalizePluginManifest: формат Claude Code с компонентами и зависимостями', () => {
    const raw = {
        name: 'deploy-tools',
        version: '1.2.0',
        description: 'Deployment commands',
        author: { name: 'Example Team', email: 'dev@example.com' },
        license: 'MIT',
        dependencies: ['secrets-vault', { name: 'other', marketplace: 'mp', version: '2.0' }],
        userConfig: { api_token: { type: 'string', title: 'API token', sensitive: true } },
        skills: ['./extra-skills/'],
        commands: { status: { source: './commands/status.md', description: 'Статус' } },
        hooks: './config/extra-hooks.json',
        mcpServers: { 'deploy-api': { command: 'node', args: ['${CLAUDE_PLUGIN_ROOT}/server.js'] } },
        outputStyles: './styles/',
        unknowntop: 1
    };
    const m = formats.normalizePluginManifest(raw, { manifestPath: '.claude-plugin/plugin.json', compositionScanned: true });
    assert.equal(m.manifestFlavor, 'claude-code');
    assert.deepEqual(m.dependencies, [
        { name: 'secrets-vault', marketplace: null, version: null },
        { name: 'other', marketplace: 'mp', version: '2.0' }
    ]);
    assert.equal(m.userConfigKeys.length, 1);
    assert.equal(m.userConfigKeys[0].key, 'api_token');
    assert.equal(m.userConfigKeys[0].sensitive, true, 'sensitive-флаг сохраняется');
    assert.ok(m.userConfigKeys.every(k => !('value' in k) && !('default' in k)), 'значения userConfig не читаются');
    assert.deepEqual(m.declaredKinds.sort(), ['commands', 'hooks', 'mcpServers', 'outputStyles', 'skills']);
    assert.ok(m.unknownTopLevelKeys.includes('unknowntop'), 'незнакомый ключ не замалчивается');
    assert.equal(m.openaiTransfer.hooks, 'удаляется для публичного каталога; ручная установка в Codex desktop');
});

test('normalizePluginManifest: portable Agent Plugins и честный статус manifest без состава', () => {
    const portable = formats.normalizePluginManifest({
        $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json',
        name: 'my-first-plugin',
        version: '1.0.0',
        description: 'Reusable greeting workflow'
    }, { manifestPath: 'plugin.json', compositionScanned: true });
    assert.equal(portable.manifestFlavor, 'agent-plugins');

    const minimal = formats.normalizePluginManifest({
        name: 'kotlin-compose-inspector',
        version: '2.0.0',
        description: 'Анализ UI-дерева Jetpack Compose.'
    }, { manifestPath: 'plugin.json', compositionScanned: true });
    assert.equal(minimal.manifestFlavor, 'minimal');
    assert.equal(minimal.compositionStatus, 'требует проверки',
        'manifest без состава получает «требует проверки», а не «пустой/поддержан»');
});

test('classifyComponent всегда даёт явный статус; неизвестный вид — «требует проверки»', () => {
    assert.equal(formats.classifyComponent('skill').support, 'supported');
    assert.equal(formats.classifyComponent('command').status, 'требует преобразования в навык');
    assert.equal(formats.classifyComponent('hook').support, 'unsupported-public');
    const unknown = formats.classifyComponent('какой-то-новый-вид');
    assert.equal(unknown.support, 'unknown');
    assert.equal(unknown.status, 'требует проверки');
    for (const kind of ['skill', 'command', 'agent', 'hook', 'mcp-server', 'unknown']) {
        const c = formats.classifyComponent(kind);
        assert.ok(c.status && c.note, `у ${kind} есть статус и пояснение`);
    }
});

// ---------------------------------------------------------------------------
// 4. Блокер chatgpt-component-skill: формат навыков ChatGPT не подтверждён.
// ---------------------------------------------------------------------------

test('parseChatgptSkillCard не отождествляет навык ChatGPT с Agent Skills SKILL.md', () => {
    const card = formats.parseChatgptSkillCard({
        sourceId: 'chatgpt-android:string:#3676',
        name: 'Skill',
        includedFiles: ['Included files'],
        usedBy: ['Used by']
    }, ['chatgpt_base_strings.txt:5675-5679']);
    assert.equal(card.format, 'unknown');
    assert.equal(card.formatStatus, 'требует проверки');
    assert.equal(card.assumedSkillMd, false, 'никакого молчаливого предположения о SKILL.md');
    assert.ok(card.blocker.includes('не подтверждён'));
    assert.equal(card.sourceProduct, 'ChatGPT for Android');
});

// ---------------------------------------------------------------------------
// 5. Блокер chatgpt-plugin-management: связь «плагин → навыки».
// ---------------------------------------------------------------------------

test('analyzePluginComposition сохраняет состав «плагин → навыки»', t => {
    const dir = tmpDir(t);
    const pkg = path.join(dir, 'combo-plugin');
    write(pkg, '.claude-plugin/plugin.json', JSON.stringify({
        name: 'combo-plugin', version: '2.0.0', description: 'Плагин с навыками'
    }));
    write(pkg, 'skills/alpha/SKILL.md', '---\nname: alpha\ndescription: Первый\n---\nТекст');
    write(pkg, 'skills/beta/SKILL.md', '---\nname: beta\ndescription: Второй\n---\nТекст');
    write(pkg, 'commands/status.md', '# Статус');
    write(pkg, 'hooks/hooks.json', JSON.stringify({ hooks: {} }));

    const composition = formats.analyzePluginComposition(pkg);
    assert.equal(composition.pluginName, 'combo-plugin');
    assert.equal(composition.pluginSkillLinks.length, 2);
    assert.ok(composition.pluginSkillLinks.every(l => l.plugin === 'combo-plugin' && l.owned === true),
        'оба навыка принадлежат плагину');
    assert.deepEqual(composition.skills.map(s => s.relativePath).sort(), ['skills/alpha', 'skills/beta']);
    assert.deepEqual(composition.skills.map(s => s.ownerPlugin), ['combo-plugin', 'combo-plugin']);
    const byKind = {};
    for (const c of composition.components) byKind[c.kind] = (byKind[c.kind] || 0) + 1;
    assert.equal(byKind.skill, 2, 'два SKILL.md в составе');
    assert.ok(byKind.command >= 1);
    assert.ok(byKind.hook >= 1);
    assert.deepEqual(composition.unknownComponents, []);
});

// ---------------------------------------------------------------------------
// 6. Блокер codex-remote-composer-extensions: разделение Codex/ChatGPT.
// ---------------------------------------------------------------------------

test('classifySourceProduct не смешивает Codex Remote с ChatGPT Android', () => {
    const remote = formats.classifySourceProduct({
        sourceId: 'chatgpt-android:remote:#4972',
        name: 'Remote-композер: skills / MCP servers / plugins',
        declaredProduct: 'ChatGPT for Android 1.2026.293'
    });
    assert.equal(remote.hintedProduct, 'Codex (Remote)');
    assert.equal(remote.conflict, true);
    assert.equal(remote.resolvedProduct, 'Codex (Remote)', 'объявленный ChatGPT не перекрывает подсказку Codex');
    assert.ok(remote.note.includes('продукты не смешиваются'));

    const chatgpt = formats.classifySourceProduct({
        sourceId: 'chatgpt-android:string:#3676',
        name: 'Компонент Skill',
        declaredProduct: 'ChatGPT for Android 1.2026.293'
    });
    assert.equal(chatgpt.conflict, false);
});

// ---------------------------------------------------------------------------
// 7. Блокер local-plugin-kotlin-compose-inspector / local-mcp-rea: единый
//    источник состояния; расхождения не превращаются в ложное «включено».
// ---------------------------------------------------------------------------

test('resolvePackageState: расхождение заявок даёт «требует сверки», не «включено»', () => {
    // rea: mcp_config disabled=true, settings.json без disabled (= включён),
    // CLI disabled → заявки true/false расходятся.
    const rea = registry.resolvePackageState({ mcpConfig: false, settings: true, cli: false }, { authoritativeRecorded: true });
    assert.equal(rea.drift, true);
    assert.equal(rea.resolvedEnabled, null);
    assert.equal(rea.displayState, 'требует сверки состояния');

    // kotlin-compose-inspector: CLI enabled, config.json без записи → разрыв.
    const kci = registry.resolvePackageState({ cli: true }, { authoritativeRecorded: false });
    assert.equal(kci.registrationGap, true);
    assert.equal(kci.resolvedEnabled, null);
    assert.equal(kci.displayState, 'требует сверки состояния');

    // Согласованные источники разрешаются однозначно.
    const ok = registry.resolvePackageState({ config: true, cli: true }, { authoritativeRecorded: true });
    assert.equal(ok.resolvedEnabled, true);
    assert.equal(ok.displayState, 'включён');
});

test('buildRegistry сводит CLI + конфиги в одну картину с claim-таблицей', t => {
    const dir = tmpDir(t);
    const configPath = write(dir, 'config/config.json', JSON.stringify({
        plugins: { 'workspace-guard': { enabled: true } }
    }));
    const mcpConfigPath = write(dir, 'config/mcp_config.json', JSON.stringify({
        mcpServers: {
            'ag-workstation-info': { command: 'node', args: ['mcp_sysinfo_server.js'] },
            rea: { command: 'node', args: ['rea.mjs'], disabled: true }
        }
    }));
    const settingsPath = write(dir, 'settings.json', JSON.stringify({
        mcpServers: {
            'ag-workstation-info': { command: 'node', args: ['mcp_sysinfo_server.js'] },
            rea: { command: 'node', args: ['rea.mjs'] }
        }
    }));
    const reg = registry.buildRegistry({
        configPath,
        mcpConfigPath,
        settingsPath,
        cliPlugins: [
            { id: 'workspace-guard', enabled: true, version: '1.2.0' },
            { id: 'kotlin-compose-inspector', enabled: true, version: '2.0.0' }
        ],
        cliMcp: [
            { id: 'ag-workstation-info', enabled: true },
            { id: 'rea', enabled: false }
        ]
    });
    const byId = new Map(reg.entries.map(e => [e.id, e]));
    const wg = byId.get('plugin:workspace-guard');
    assert.equal(wg.enabled, true);
    assert.equal(wg.state.displayState, 'включён');

    const kci = byId.get('plugin:kotlin-compose-inspector');
    assert.equal(kci.state.registrationGap, true, 'нет записи в config.json — разрыв регистрации');
    assert.equal(kci.enabled, null, 'ложное «включено» не показывается');

    const rea = byId.get('mcp:rea');
    assert.equal(rea.state.drift, true, 'mcp_config/settings/CLI расходятся');
    assert.equal(rea.enabled, null);
    assert.equal(rea.state.displayState, 'требует сверки состояния');
    const claimSources = rea.state.claimTable.map(c => `${c.source}=${c.claim}`).sort();
    assert.deepEqual(claimSources, ['cli=false', 'mcpConfig=false', 'settings=true']);

    const sys = byId.get('mcp:ag-workstation-info');
    assert.equal(sys.enabled, true);
    assert.equal(sys.state.displayState, 'включён');
    assert.ok(reg.needsStateReconciliation.some(x => x.id === 'mcp:rea'));
    assert.ok(reg.needsStateReconciliation.some(x => x.id === 'plugin:kotlin-compose-inspector'));
});

// ---------------------------------------------------------------------------
// 8. Архив: пути, размер, хэш, коллизии имён, исполняемые компоненты.
// ---------------------------------------------------------------------------

test('ZIP round-trip: write → read → CRC и sha256 сходятся', () => {
    const buf = archive.writeZipEntries([
        { name: 'pkg/SKILL.md', data: '---\nname: demo\ndescription: Test\n---\nBody' },
        { name: 'pkg/scripts/run.py', data: '#!/usr/bin/env python\nprint(1)' }
    ]);
    const read = archive.readZipEntries(buf);
    assert.deepEqual(read.issues, []);
    assert.deepEqual(read.entries.map(e => e.name), ['pkg/SKILL.md', 'pkg/scripts/run.py']);
    const verified = archive.verifyArchiveEntries(read.entries);
    assert.equal(verified.safe, true);
    assert.equal(verified.executableComponents.length, 1);
    assert.equal(verified.executableComponents[0].path, 'pkg/scripts/run.py');
});

test('архив отвергает обход каталога (zip-slip) и абсолютные пути', () => {
    const evil = archive.writeZipEntries([
        { name: 'pkg/ok.txt', data: 'ok' },
        { name: 'pkg/../../escape.txt', data: 'evil' },
        { name: '/abs.txt', data: 'evil' },
        { name: 'pkg\\win-escape.txt', data: 'evil' }
    ]);
    const read = archive.readZipEntries(evil);
    const verified = archive.verifyArchiveEntries(read.entries);
    assert.equal(verified.safe, false);
    assert.equal(verified.entries.map(e => e.path).join(','), 'pkg/ok.txt', 'только безопасная запись проходит');
    assert.equal(verified.pathIssues.length, 3);
    assert.ok(verified.pathIssues.some(i => i.includes('обход каталога')));
    assert.ok(verified.pathIssues.some(i => i.includes('абсолютный путь')));
    assert.ok(verified.pathIssues.some(i => i.includes('обратный слэш')));
});

test('архив фиксирует коллизии имён без учёта регистра и лимиты размера', () => {
    const dup = archive.writeZipEntries([
        { name: 'pkg/File.txt', data: 'one' },
        { name: 'pkg/file.TXT', data: 'two' }
    ]);
    const verified = archive.verifyArchiveEntries(archive.readZipEntries(dup).entries);
    assert.equal(verified.nameCollisions.length, 1);
    assert.equal(verified.safe, false, 'коллизия имён блокирует установку');

    const big = archive.writeZipEntries([{ name: 'big.bin', data: Buffer.alloc(2048, 1) }]);
    const limited = archive.verifyArchiveEntries(archive.readZipEntries(big, { maxFileBytes: 1024 }).entries, { maxFileBytes: 1024 });
    // readZipEntries уже отсёк файл по лимиту → в entries его нет, лимит зафиксирован
    assert.equal(limited.entries.length, 0);
});

// ---------------------------------------------------------------------------
// 9. Просмотр состава до установки + импорт → перезапуск (индекс) →
//    отключение → удаление без потери соседних пакетов.
// ---------------------------------------------------------------------------

test('inspectPackageSource показывает состав ZIP до установки, включая «требует проверки»', () => {
    const buf = archive.writeZipEntries([
        { name: 'combo/plugin.json', data: JSON.stringify({ name: 'combo', version: '1.0.0', description: 'Пакет' }) },
        { name: 'combo/skills/alpha/SKILL.md', data: '---\nname: alpha\ndescription: Навык\n---\nТекст' },
        { name: 'combo/mystery.dat', data: 'странные данные' }
    ]);
    const preview = registry.inspectPackageSource({ buffer: buf, kind: 'zip' });
    assert.equal(preview.ok, true);
    assert.equal(preview.rootName, 'combo');
    assert.equal(preview.packageKind, 'plugin');
    assert.equal(preview.manifest.name, 'combo');
    assert.equal(preview.skills.length, 1);
    assert.equal(preview.skills[0].ownerPlugin, 'combo', 'связь «плагин → навык» видна до установки');
    assert.ok(preview.unknownComponents.some(u => u.path === 'mystery.dat' && u.status === 'требует проверки'));
    assert.equal(preview.verdict.readyForInstall, true);
    assert.equal(preview.fileCount, 3);
    assert.ok(preview.packageDigest.length === 64);
});

test('полный цикл: импорт → перезапуск (reload индекса) → отключение → удаление, соседи целы', t => {
    const dir = tmpDir(t);
    const targetRoot = path.join(dir, 'packages');
    const registryIndexPath = path.join(dir, 'extensions-registry.json');

    // Два независимых пакета: первый импортируем, второй кладём «соседом» вручную.
    const pkgA = archive.writeZipEntries([
        { name: 'alpha/plugin.json', data: JSON.stringify({ name: 'alpha', version: '1.0.0', description: 'A' }) },
        { name: 'alpha/skills/one/SKILL.md', data: '---\nname: one\ndescription: Первый\n---\nТекст' }
    ]);
    const impA = registry.importPackage({ buffer: pkgA, kind: 'zip', targetRoot, registryIndexPath });
    assert.equal(impA.ok, true);
    assert.equal(impA.entry.kind, 'plugin');
    assert.deepEqual(impA.entry.pluginSkillLinks, [{ plugin: 'alpha', skillPath: 'skills/one', owned: true }],
        'состав «плагин → навык» сохранён в реестре');

    const impA2 = registry.importPackage({ buffer: pkgA, kind: 'zip', targetRoot, registryIndexPath });
    assert.equal(impA2.ok, false, 'повторный импорт того же имени отклонён');
    assert.ok(impA2.error.includes('Коллизия имён'));

    const pkgB = archive.writeZipEntries([
        { name: 'beta/plugin.json', data: JSON.stringify({ name: 'beta', version: '1.0.0', description: 'B' }) }
    ]);
    const impB = registry.importPackage({ buffer: pkgB, kind: 'zip', targetRoot, registryIndexPath });
    assert.equal(impB.ok, true, 'соседний пакет импортируется независимо');

    // «Перезапуск»: индекс читается заново с диска.
    const reloaded = registry.loadRegistryIndex(registryIndexPath);
    assert.deepEqual(Object.keys(reloaded.packages).sort(), ['plugin:alpha', 'plugin:beta']);

    // Отключение alpha (skill-состояние записывается в файлы).
    const off = registry.setPackageEnabled({ packageId: 'plugin:alpha', enabled: false, registryIndexPath });
    assert.equal(off.ok, true);
    assert.equal(reloaded.packages['plugin:alpha'].enabledOverride, null,
        'снимок до действия не изменился');
    assert.equal(registry.loadRegistryIndex(registryIndexPath).packages['plugin:alpha'].enabledOverride, false,
        'после действия индекс фиксирует отключение');

    // Удаление alpha; beta не страдает.
    const rm = registry.removePackage({ packageId: 'plugin:alpha', registryIndexPath });
    assert.equal(rm.ok, true);
    assert.equal(rm.removedFiles.length, 2, 'файлы alpha удалены');
    assert.equal(fs.existsSync(path.join(targetRoot, 'alpha')), false, 'каталог alpha удалён');
    assert.equal(fs.existsSync(path.join(targetRoot, 'beta', 'plugin.json')), true, 'beta цел');
    const after = registry.loadRegistryIndex(registryIndexPath);
    assert.deepEqual(Object.keys(after.packages), ['plugin:beta']);
});

test('setPackageEnabled для навыка переключает SKILL.md ↔ SKILL.md.disabled', t => {
    const dir = tmpDir(t);
    const targetRoot = path.join(dir, 'skills');
    const registryIndexPath = path.join(dir, 'idx.json');
    const src = path.join(dir, 'src-skill');
    write(src, 'SKILL.md', '---\nname: toggler\ndescription: Переключаемый\n---\nТекст');
    const imp = registry.importPackage({ sourcePath: src, kind: 'dir', targetRoot, registryIndexPath });
    assert.equal(imp.ok, true);
    assert.equal(imp.entry.kind, 'skill');

    const off = registry.setPackageEnabled({ packageId: 'skill:toggler', enabled: false, registryIndexPath });
    assert.equal(off.ok, true);
    assert.equal(fs.existsSync(path.join(targetRoot, 'toggler', 'SKILL.md')), false);
    assert.equal(fs.existsSync(path.join(targetRoot, 'toggler', 'SKILL.md.disabled')), true);

    const on = registry.setPackageEnabled({ packageId: 'skill:toggler', enabled: true, registryIndexPath });
    assert.equal(on.ok, true);
    assert.equal(fs.existsSync(path.join(targetRoot, 'toggler', 'SKILL.md')), true);
    assert.equal(fs.existsSync(path.join(targetRoot, 'toggler', 'SKILL.md.disabled')), false);
});

// ---------------------------------------------------------------------------
// 10. Экспорт собственного набора: хэши сходятся, секреты исключены.
// ---------------------------------------------------------------------------

test('exportOwnSet экспортирует пакеты с хэшами и манифестом без секретов', t => {
    const dir = tmpDir(t);
    const targetRoot = path.join(dir, 'packages');
    const registryIndexPath = path.join(dir, 'idx.json');
    const pkg = archive.writeZipEntries([
        { name: 'alpha/plugin.json', data: JSON.stringify({ name: 'alpha', version: '1.0.0', description: 'A' }) },
        { name: 'alpha/skills/one/SKILL.md', data: '---\nname: one\ndescription: Навык\n---\nТекст' }
    ]);
    registry.importPackage({ buffer: pkg, kind: 'zip', targetRoot, registryIndexPath });
    const outPath = path.join(dir, 'export.zip');
    const exp = registry.exportOwnSet({ registryIndexPath, outPath });
    assert.equal(exp.ok, true);
    assert.equal(exp.packages, 1);
    assert.equal(exp.exportManifest.secretsExcluded, true);

    const read = archive.readZipEntries(fs.readFileSync(outPath));
    const names = read.entries.map(e => e.name);
    assert.ok(names.includes('export-manifest.json'));
    assert.ok(names.includes('plugins/alpha/plugin.json'));
    assert.ok(names.includes('plugins/alpha/skills/one/SKILL.md'));
    const manifest = JSON.parse(read.entries.find(e => e.name === 'export-manifest.json').data.toString('utf8'));
    const files = manifest.packages[0].files;
    for (const f of files) {
        const zipEntry = read.entries.find(e => e.name === `plugins/alpha/${f.path}`);
        assert.equal(archive.sha256Of(zipEntry.data), f.sha256, `хэш ${f.path} совпадает`);
    }
    const rawText = JSON.stringify(manifest);
    // Секреты — значения env/токены/заголовки: они не попадают в manifest экспорта.
    assert.ok(!rawText.includes('"env"') && !rawText.includes('Authorization') && !rawText.includes('API_TOKEN'),
        'конфигурационные секреты не выгружаются');
    const pkgRecord = manifest.packages[0];
    assert.deepEqual(Object.keys(pkgRecord).sort(), [
        'compositionStatus', 'files', 'id', 'kind', 'license', 'name',
        'packageDigest', 'pluginSkillLinks', 'publisher', 'sourceProduct', 'version'
    ], 'запись пакета содержит только переносимые поля');
});

// ---------------------------------------------------------------------------
// 11. MCP-конфигурация: транспорты и признаки секретов без самих секретов.
// ---------------------------------------------------------------------------

test('parseMcpServerEntry различает stdio/streamable-http и не извлекает секреты', () => {
    const stdio = formats.parseMcpServerEntry('rea', {
        command: 'node', args: ['rea.mjs', 'mcp'], disabled: true,
        env: { API_TOKEN: 'секрет-которого-нет-в-выводе' }
    });
    assert.equal(stdio.transport, 'stdio');
    assert.equal(stdio.disabled, true);
    assert.deepEqual(stdio.envKeys, ['API_TOKEN'], 'только имена ключей');
    assert.equal(stdio.sensitiveFieldsPresent, true);
    assert.ok(!JSON.stringify(stdio).includes('секрет-которого-нет-в-выводе'), 'значение секрета не сохраняется');

    const remote = formats.parseMcpServerEntry('docs', {
        type: 'streamable-http',
        url: 'https://example.com/mcp',
        extensions: { 'com.openai': { auth: { type: 'oauth' } } }
    });
    assert.equal(remote.transport, 'streamable-http');
    assert.equal(remote.authType, 'oauth');
});

// ---------------------------------------------------------------------------
// 12. Позиции инвентаря в реестре: наблюдения дампов остаются наблюдениями,
//     разделение продуктов сохраняется.
// ---------------------------------------------------------------------------

test('mergeCatalogPositions переносит позиции инвентаря без превращения в установки', () => {
    const inventory = {
        positions: [
            {
                id: 'chatgpt-component-skill',
                name: 'Компонент Skill (навыки ChatGPT)',
                sourceProduct: 'ChatGPT for Android 1.2026.293',
                sourceId: 'chatgpt-android:string:#3676',
                publisher: 'OpenAI',
                version: '1.2026.293',
                availability: 'unknown',
                observationType: 'apk-string',
                blocker: 'формат не подтверждён',
                evidence: ['x']
            },
            {
                id: 'codex-remote-composer-extensions',
                name: 'Remote-композер',
                sourceProduct: 'ChatGPT for Android 1.2026.293',
                sourceId: 'chatgpt-android:remote:#4972',
                publisher: 'OpenAI',
                version: '1.2026.293',
                availability: 'unknown',
                observationType: 'apk-string',
                blocker: 'атрибуция не разделена',
                evidence: ['y']
            }
        ]
    };
    const entries = registry.mergeCatalogPositions(inventory);
    assert.equal(entries.length, 2);
    for (const e of entries) {
        assert.equal(e.kind, 'catalog-position');
        assert.equal(e.state.displayState, 'каталог (не установлено)');
        assert.equal(e.state.resolvedEnabled, null);
    }
    const skillCard = entries.find(e => e.id === 'catalog:chatgpt-component-skill');
    assert.equal(skillCard.sourceProduct.startsWith('ChatGPT'), true);
    const codex = entries.find(e => e.id === 'catalog:codex-remote-composer-extensions');
    assert.equal(codex.productSeparation.conflict, true);
    assert.equal(codex.sourceProduct, 'Codex (Remote)', 'позиция Codex не числится как ChatGPT');
});
