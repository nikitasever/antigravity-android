'use strict';
// Этап 8 плана расширений (EXTENSIONS_INTEGRATION_PLAN.md): сохранение,
// обновления и восстановление. Критерии приёмки этапа:
//   перезапуск/обновление/восстановление не запускают платные вызовы, не теряют
//   отключённые пакеты и не расширяют права без предусмотренного действия.
//
// Покрываемые сценарии (требования к Этапу 8):
//   1) сохранение состояния (enabled/разрешения/привязки) после перезапуска —
//      проверяется настоящим дочерним процессом (перезапуск моста);
//   2) успешное обновление с сохранением настроек и отключений;
//   3) сбой обновления (манифест/целостность/схема/зависимости) с автоматическим
//      откатом к предыдущей рабочей версии;
//   4) восстановление после сбоя индекса (снимок повреждённого файла в backup +
//      пересборка по диску без молчаливой потери пакетов);
//   плюс: резервная копия без токенов, явный requiresReauth, выдача прав только
//   явным действием и отсутствие сетевых вызовов в модуле реестра.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

const registry = require('../lib/extension-registry');
const archive = require('../lib/extension-archive');

const REGISTRY_LIB_PATH = path.join(__dirname, '..', 'lib', 'extension-registry.js');

function tmpDir(t, prefix = 'stage8-') {
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

function skillMarkdown({ name, version, allowedTools = '', body = 'Тело инструкции.' }) {
    return [
        '---',
        `name: ${name}`,
        `description: "Описание ${name}"`,
        'metadata:',
        `  version: "${version}"`,
        allowedTools ? `allowed-tools: "${allowedTools}"` : null,
        '---',
        '',
        body
    ].filter(l => l !== null).join('\n');
}

function makeSkillSrc(srcDir, opts) {
    write(srcDir, 'SKILL.md', skillMarkdown(opts));
    for (const [rel, content] of Object.entries(opts.files || {})) write(srcDir, rel, content);
    return srcDir;
}

function importSkill(t, { root, indexPath, srcDir, name, version, allowedTools = '', body, files }) {
    makeSkillSrc(srcDir, { name, version, allowedTools, body, files });
    const result = registry.importPackage({
        sourcePath: srcDir,
        targetRoot: root,
        registryIndexPath: indexPath,
        onCollision: 'refuse'
    });
    assert.equal(result.ok, true, `импорт ${name}: ${result.error || ''}`);
    return result;
}

// ---------------------------------------------------------------------------
// 1. Персистентность: enabled/разрешения/привязки переживают перезапуск
//    процесса (настоящий дочерний процесс = перезапуск pc-bridge).
// ---------------------------------------------------------------------------

test('Этап 8: enabled/разрешения/привязки переживают перезапуск процесса', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-persist'),
        name: 'demo-persist-skill',
        version: '1.0.0',
        allowedTools: 'Alpha Beta'
    });

    const id = 'skill:demo-persist-skill';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });
    registry.setUserState({
        statePath,
        packageId: id,
        permissions: { granted: ['allowed-tool:Alpha'] },
        bindings: { conversationId: 'chat-42', refs: ['mcp:foo', 'skill:bar'] }
    });

    // Перезапуск: новый процесс читает состояние с диска (как мост при старте).
    const childScript = [
        `const r = require(${JSON.stringify(REGISTRY_LIB_PATH)});`,
        `const us = r.loadUserState(${JSON.stringify(statePath)});`,
        `const idx = r.loadRegistryIndex(${JSON.stringify(indexPath)});`,
        'process.stdout.write(JSON.stringify({ us, idx }));'
    ].join('\n');
    const out = execFileSync(process.execPath, ['-e', childScript], { encoding: 'utf8' });
    const { us, idx } = JSON.parse(out);

    assert.equal(us.packages[id].enabled, false, 'отключение пережило перезапуск');
    assert.deepEqual(us.packages[id].permissions.granted, ['allowed-tool:Alpha'], 'разрешения пережили перезапуск');
    assert.deepEqual(us.packages[id].bindings.conversations['chat-42'], ['mcp:foo', 'skill:bar'], 'привязки пережили перезапуск');
    assert.ok(idx.packages[id], 'запись реестра пережила перезапуск');
    assert.equal(idx.packages[id].name, 'demo-persist-skill');
});

test('Этап 8: отключённый навык остаётся отключённым после перезапуска (файлы + состояние)', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-off'),
        name: 'demo-off-skill',
        version: '1.0.0'
    });
    const id = 'skill:demo-off-skill';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });

    const pkgDir = path.join(root, 'demo-off-skill');
    assert.ok(fs.existsSync(path.join(pkgDir, 'SKILL.md.disabled')), 'файловое состояние отключения записано');
    assert.ok(!fs.existsSync(path.join(pkgDir, 'SKILL.md')), 'активного SKILL.md нет');

    // «Перезапуск»: чтение реестра и сборка с пользовательским состоянием.
    const index = registry.loadRegistryIndex(indexPath, { targetRoots: [root], userStatePath: statePath });
    const userState = registry.loadUserState(statePath);
    const built = registry.buildRegistry({
        registryIndexPath: indexPath,
        skillRoots: [{ dir: root, source: 'test' }],
        userState
    });
    const entry = built.entries.find(e => e.id === id);
    assert.equal(index.packages[id].enabledOverride, false);
    assert.equal(entry.enabled, false, 'после перезапуска пакет показан отключённым');
    assert.equal(entry.state.displayState, 'отключён');
});

// ---------------------------------------------------------------------------
// 2. Успешное обновление: настройки и отключения сохраняются, новые права
//    только «запрошено» (не выдаются автоматически).
// ---------------------------------------------------------------------------

test('Этап 8: успешное обновление сохраняет отключения и настройки; новые права не выдаются', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-v1'),
        name: 'demo-update-skill',
        version: '1.0.0',
        allowedTools: 'Alpha Beta',
        body: 'Смотри references/guide.md',
        files: { 'references/guide.md': '# Гайд v1' }
    });
    const id = 'skill:demo-update-skill';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });
    registry.grantPackagePermissions({ statePath, packageId: id, grant: ['allowed-tool:Alpha'] });

    // Версия 2.0.0 объявляет новое право Gamma и новую зависимость-ссылку.
    const v2 = path.join(dir, 'src-v2');
    makeSkillSrc(v2, {
        name: 'demo-update-skill',
        version: '2.0.0',
        allowedTools: 'Alpha Beta Gamma',
        body: 'Смотри references/guide.md и references/new.md',
        files: { 'references/guide.md': '# Гайд v2', 'references/new.md': '# Новое' }
    });

    const result = registry.updatePackage({
        packageId: id,
        sourcePath: v2,
        targetRoot: root,
        registryIndexPath: indexPath,
        userStatePath: statePath
    });
    assert.equal(result.ok, true, `обновление прошло: ${result.error || ''}`);
    assert.equal(result.entry.version, '2.0.0');
    assert.equal(result.rolledBack, false);
    assert.deepEqual(result.changes.permissions.added, ['allowed-tool:Gamma'], 'новое право зафиксировано в сравнении');
    assert.equal(result.disabledPreserved, true, 'отключение сохранено при обновлении');

    // Диск: отключённый навык остаётся отключённым.
    const pkgDir = path.join(root, 'demo-update-skill');
    assert.ok(fs.existsSync(path.join(pkgDir, 'SKILL.md.disabled')), 'SKILL.md.disabled на месте');
    assert.ok(!fs.existsSync(path.join(pkgDir, 'SKILL.md')), 'обновление не включило отключённый навык');

    // Пользовательские настройки: отключение и выданные права целы; новое
    // право только «запрошено» — выдачи без действия пользователя нет.
    const us = registry.loadUserState(statePath).packages[id];
    assert.equal(us.enabled, false, 'отключение сохранено');
    assert.deepEqual(us.permissions.granted, ['allowed-tool:Alpha'], 'выданные права не изменились');
    assert.deepEqual(us.permissions.requested, ['allowed-tool:Gamma'], 'новое право только запрошено');
    assert.deepEqual(result.userStateNote.grantedUnchanged, ['allowed-tool:Alpha']);

    // Прежняя рабочая версия сохранена в backup до завершения замены.
    assert.ok(result.backupRetained && fs.existsSync(result.backupRetained), 'backup прежней версии сохранён');
    const backupMd = fs.readFileSync(path.join(result.backupRetained, 'SKILL.md.disabled'), 'utf8');
    assert.ok(backupMd.includes('1.0.0'), 'в backup — прежняя версия 1.0.0');
});

test('Этап 8: откат (rollbackPackage) восстанавливает предыдущую рабочую версию, настройки целы', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-v1'),
        name: 'demo-rollback-skill',
        version: '1.0.0',
        allowedTools: 'Alpha'
    });
    const id = 'skill:demo-rollback-skill';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });
    registry.grantPackagePermissions({ statePath, packageId: id, grant: ['allowed-tool:Alpha'] });

    const v2 = path.join(dir, 'src-v2');
    makeSkillSrc(v2, { name: 'demo-rollback-skill', version: '2.0.0', allowedTools: 'Alpha' });
    const upd = registry.updatePackage({ packageId: id, sourcePath: v2, targetRoot: root, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(upd.ok, true);

    const rb = registry.rollbackPackage({ packageId: id, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(rb.ok, true, `откат прошёл: ${rb.error || ''}`);
    assert.equal(rb.restoredVersion, '1.0.0');

    const pkgDir = path.join(root, 'demo-rollback-skill');
    const md = fs.readFileSync(path.join(pkgDir, 'SKILL.md.disabled'), 'utf8');
    assert.ok(md.includes('1.0.0'), 'на диске снова версия 1.0.0');
    const us = registry.loadUserState(statePath).packages[id];
    assert.equal(us.enabled, false, 'отключение сохранено при откате');
    assert.deepEqual(us.permissions.granted, ['allowed-tool:Alpha'], 'права сохранены при откате');

    // Точка отката одноразовая: повторный откат — честный отказ.
    const again = registry.rollbackPackage({ packageId: id, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(again.ok, false);
    assert.match(again.error, /Точка отката/);
});

// ---------------------------------------------------------------------------
// 3. Сбой обновления: автоматический откат к предыдущей рабочей версии с
//    сохранением пользовательских настроек.
// ---------------------------------------------------------------------------

test('Этап 8: сломанный манифест новой версии → автоматический откат, рабочая версия и настройки целы', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');

    const v1 = path.join(dir, 'src-v1');
    write(v1, 'plugin.json', JSON.stringify({ name: 'demo-broken-plugin', version: '1.2.0', description: 'v1' }, null, 2));
    const imp = registry.importPackage({ sourcePath: v1, targetRoot: root, registryIndexPath: indexPath, onCollision: 'refuse' });
    assert.equal(imp.ok, true);
    const id = 'plugin:demo-broken-plugin';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });
    registry.grantPackagePermissions({ statePath, packageId: id, grant: ['user-config:apiKey'] });

    // Версия 2: plugin.json не разбирается как JSON (провал проверки манифеста).
    const v2 = path.join(dir, 'src-v2');
    write(v2, 'plugin.json', '{ это не JSON');

    const result = registry.updatePackage({ packageId: id, sourcePath: v2, targetRoot: root, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(result.ok, false);
    assert.equal(result.stage, 'validation-failed');
    assert.equal(result.rolledBack, true, 'выполнен автоматический откат');
    assert.ok(result.validation.failures.manifest.length > 0, 'зафиксирована проверка манифеста');

    // На диске — прежняя рабочая версия.
    const pluginJson = fs.readFileSync(path.join(root, 'demo-broken-plugin', 'plugin.json'), 'utf8');
    assert.ok(pluginJson.includes('1.2.0'), 'восстановлена версия 1.2.0');
    assert.doesNotThrow(() => JSON.parse(pluginJson), 'восстановлен валидный JSON');

    // Пользовательские настройки сохранены, права не расширены.
    const us = registry.loadUserState(statePath).packages[id];
    assert.equal(us.enabled, false);
    assert.deepEqual(us.permissions.granted, ['user-config:apiKey']);

    // Провал зафиксирован в истории обновлений.
    const entry = registry.loadRegistryIndex(indexPath).packages[id];
    assert.equal(entry.updateHistory[entry.updateHistory.length - 1].action, 'update-failed');
});

test('Этап 8: недостающая зависимость скрипта → автоматический откат к прежней версии', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-v1'),
        name: 'demo-dep-skill',
        version: '1.0.0',
        files: { 'scripts/run.js': "const fs = require('node:fs');\nconsole.log(fs.constants);\n" }
    });
    const id = 'skill:demo-dep-skill';
    registry.setPackageEnabled({ packageId: id, enabled: false, registryIndexPath: indexPath, userStatePath: statePath });

    // Версия 2 требует внешний модуль, которого нет (провал проверки зависимостей).
    const v2 = path.join(dir, 'src-v2');
    makeSkillSrc(v2, {
        name: 'demo-dep-skill',
        version: '2.0.0',
        files: { 'scripts/run.js': "const dep = require('missing-stage8-dep-xyz');\nconsole.log(dep);\n" }
    });

    const result = registry.updatePackage({ packageId: id, sourcePath: v2, targetRoot: root, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(result.ok, false);
    assert.equal(result.stage, 'validation-failed');
    assert.equal(result.rolledBack, true, 'выполнен автоматический откат');
    assert.ok(result.validation.failures.dependencies.length > 0, 'зафиксирована проверка зависимостей');
    assert.match(result.error, /зависимост/);

    const runJs = fs.readFileSync(path.join(root, 'demo-dep-skill', 'scripts', 'run.js'), 'utf8');
    assert.ok(runJs.includes('node:fs'), 'на диске прежний рабочий скрипт');
    const us = registry.loadUserState(statePath).packages[id];
    assert.equal(us.enabled, false, 'отключение сохранено после отката');
});

test('Этап 8: смена имени в манифесте → отказ с откатом (идентичность пакета)', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-v1'),
        name: 'demo-rename-skill',
        version: '1.0.0'
    });
    const id = 'skill:demo-rename-skill';

    const v2 = path.join(dir, 'src-v2');
    makeSkillSrc(v2, { name: 'demo-other-skill', version: '2.0.0' });
    const result = registry.updatePackage({ packageId: id, sourcePath: v2, targetRoot: root, registryIndexPath: indexPath, userStatePath: statePath });
    assert.equal(result.ok, false);
    assert.equal(result.rolledBack, true);
    assert.ok(result.validation.failures.manifest.some(m => m.includes('другое имя')), 'зафиксирована смена имени');
    const md = fs.readFileSync(path.join(root, 'demo-rename-skill', 'SKILL.md'), 'utf8');
    assert.ok(md.includes('demo-rename-skill'), 'на диске прежняя версия');
});

test('Этап 8: структурная проблема источника отклоняется ДО замены, рабочая версия не тронута', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    importSkill(t, {
        root, indexPath,
        srcDir: path.join(dir, 'src-v1'),
        name: 'demo-struct-skill',
        version: '1.0.0'
    });
    const id = 'skill:demo-struct-skill';

    const result = registry.updatePackage({
        packageId: id,
        sourcePath: path.join(dir, 'no-such-directory'),
        targetRoot: root,
        registryIndexPath: indexPath
    });
    assert.equal(result.ok, false);
    assert.equal(result.stage, 'rejected-before-swap');
    assert.equal(result.currentPreserved, true, 'рабочая версия не изменялась');
    const entry = registry.loadRegistryIndex(indexPath).packages[id];
    assert.equal(entry.version, '1.0.0');
    assert.ok(fs.existsSync(path.join(root, 'demo-struct-skill', 'SKILL.md')), 'файлы прежней версии на месте');
    assert.ok(!entry.updateHistory, 'история обновлений не засорена: замены не было');
});

// ---------------------------------------------------------------------------
// 4. Восстановление после сбоя индекса: снимок в backup + пересборка по диску,
//    без молчаливой потери установленных пакетов и отключений.
// ---------------------------------------------------------------------------

test('Этап 8: повреждённый индекс → снимок в backup, пересборка по диску, отключения сохранены', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, { root, indexPath, srcDir: path.join(dir, 'src-a'), name: 'demo-rec-a', version: '1.0.0' });
    importSkill(t, { root, indexPath, srcDir: path.join(dir, 'src-b'), name: 'demo-rec-b', version: '2.0.0' });
    registry.setPackageEnabled({ packageId: 'skill:demo-rec-b', enabled: false, registryIndexPath: indexPath, userStatePath: statePath });

    // Сбой индекса: файл есть, но JSON повреждён.
    const corruptText = '{"version":1,"packages":{"broken';
    fs.writeFileSync(indexPath, corruptText, 'utf8');

    const index = registry.loadRegistryIndex(indexPath, { targetRoots: [root], userStatePath: statePath });

    // Снимок повреждённого файла сохранён в backup (не выброшен).
    const recovery = index.recoveryLog[index.recoveryLog.length - 1];
    assert.equal(recovery.reason, 'corrupt');
    assert.ok(recovery.corruptBackup && fs.existsSync(recovery.corruptBackup), 'снимок повреждённого индекса сохранён');
    assert.equal(fs.readFileSync(recovery.corruptBackup, 'utf8'), corruptText, 'снимок содержит исходное повреждённое содержимое');

    // Пересборка по диску: оба пакета на месте, потери нет.
    assert.ok(index.packages['skill:demo-rec-a'], 'пакет A восстановлен по диску');
    assert.ok(index.packages['skill:demo-rec-b'], 'пакет B восстановлен по диску');
    assert.deepEqual([...recovery.diskRebuild].sort(), ['skill:demo-rec-a', 'skill:demo-rec-b']);
    assert.equal(index.packages['skill:demo-rec-a'].restoredFromDisk, true);

    // Отключения не теряются: пользовательское состояние применено поверх.
    assert.equal(index.packages['skill:demo-rec-b'].enabledOverride, false, 'отключение пережило восстановление индекса');
    assert.equal(index.packages['skill:demo-rec-a'].enabledOverride, null, 'без записи состояния — без выдуманного включения');
});

test('Этап 8: отсутствующий индекс → установленные на диске пакеты не теряются', t => {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    importSkill(t, { root, indexPath, srcDir: path.join(dir, 'src-a'), name: 'demo-miss-a', version: '1.0.0' });
    importSkill(t, { root, indexPath, srcDir: path.join(dir, 'src-b'), name: 'demo-miss-b', version: '1.0.0' });
    registry.setPackageEnabled({ packageId: 'skill:demo-miss-b', enabled: false, registryIndexPath: indexPath, userStatePath: statePath });

    fs.rmSync(indexPath); // индекс исчез (сбой диска, ручное удаление)

    const index = registry.loadRegistryIndex(indexPath, { targetRoots: [root], userStatePath: statePath });
    assert.ok(index.packages['skill:demo-miss-a'], 'пакет A найден по диску');
    assert.ok(index.packages['skill:demo-miss-b'], 'пакет B найден по диску');
    assert.equal(index.packages['skill:demo-miss-b'].enabledOverride, false, 'отключение сохранено');
    const recovery = index.recoveryLog[index.recoveryLog.length - 1];
    assert.equal(recovery.reason, 'missing');
    assert.deepEqual([...recovery.diskRebuild].sort(), ['skill:demo-miss-a', 'skill:demo-miss-b']);
});

// ---------------------------------------------------------------------------
// Права: выдача — только явным действием; обновление/восстановление не
// расширяют права.
// ---------------------------------------------------------------------------

test('Этап 8: разрешения выдаются только явным действием (grant/deny)', t => {
    const dir = tmpDir(t);
    const statePath = path.join(dir, 'extensions-user-state.json');
    registry.setUserState({
        statePath,
        packageId: 'skill:demo-perms',
        permissions: { requested: ['p1', 'p2'] }
    });
    let us = registry.loadUserState(statePath).packages['skill:demo-perms'];
    assert.deepEqual(us.permissions.granted, [], 'без действия выдачи нет');

    registry.grantPackagePermissions({ statePath, packageId: 'skill:demo-perms', grant: ['p1'], deny: ['p2'] });
    us = registry.loadUserState(statePath).packages['skill:demo-perms'];
    assert.deepEqual(us.permissions.granted, ['p1']);
    assert.deepEqual(us.permissions.denied, ['p2']);
    assert.deepEqual(us.permissions.requested, [], 'решённые запросы убраны');
});

// ---------------------------------------------------------------------------
// Резервная копия и восстановление: без токенов, requiresReauth явно.
// ---------------------------------------------------------------------------

function backupFixture(t) {
    const dir = tmpDir(t);
    const root = path.join(dir, 'packages');
    const indexPath = path.join(dir, 'extensions-registry.json');
    const statePath = path.join(dir, 'extensions-user-state.json');
    const mcpConfigPath = path.join(dir, 'mcp_config.json');
    write(dir, 'mcp_config.json', JSON.stringify({
        mcpServers: {
            'secure-srv': {
                command: 'node',
                args: ['srv.js'],
                env: { API_TOKEN: 'super-secret-value' },
                headers: { Authorization: 'Bearer topsecret-tok' }
            }
        }
    }, null, 2));
    const v1 = path.join(dir, 'src-plugin');
    write(v1, 'plugin.json', JSON.stringify({
        name: 'demo-secret-plugin',
        version: '1.0.0',
        userConfig: { apiKey: { type: 'string', sensitive: true, required: true } }
    }, null, 2));
    const imp = registry.importPackage({ sourcePath: v1, targetRoot: root, registryIndexPath: indexPath, onCollision: 'refuse' });
    assert.equal(imp.ok, true);
    registry.setPackageEnabled({ packageId: 'plugin:demo-secret-plugin', enabled: false, registryIndexPath: indexPath, userStatePath: statePath });
    registry.grantPackagePermissions({ statePath, packageId: 'plugin:demo-secret-plugin', grant: ['user-config:apiKey'] });
    return { dir, root, indexPath, statePath, mcpConfigPath };
}

test('Этап 8: резервная копия — manifests/настройки без токенов, requiresReauth явно', t => {
    const fx = backupFixture(t);
    const outPath = path.join(fx.dir, 'backup.zip');
    const result = registry.createBackup({
        registryIndexPath: fx.indexPath,
        userStatePath: fx.statePath,
        outPath,
        mcpConfigPath: fx.mcpConfigPath
    });
    assert.equal(result.ok, true);

    // Содержимое копии: ни значений env, ни токенов заголовков.
    const zip = archive.readZipEntries(fs.readFileSync(outPath));
    const entries = archive.verifyArchiveEntries(zip.entries).entries;
    const manifestEntry = entries.find(e => e.path === 'backup-manifest.json');
    assert.ok(manifestEntry, 'манифест копии присутствует');
    const text = manifestEntry.data.toString('utf8');
    assert.ok(!text.includes('super-secret-value'), 'значение env-токена не попало в копию');
    assert.ok(!text.includes('topsecret-tok'), 'значение заголовка не попало в копию');
    const manifest = JSON.parse(text);
    assert.equal(manifest.secretsExcluded, true);
    assert.ok(manifest.secretsPolicy, 'политика секретов записана');

    const mcpRec = manifest.packages.find(p => p.id === 'mcp:secure-srv');
    assert.ok(mcpRec, 'MCP-сервер учтён в копии');
    assert.ok(mcpRec.configKeys.includes('env'), 'сохранены только имена ключей');
    assert.equal(mcpRec.requiresReauth, true);

    const pluginRec = manifest.packages.find(p => p.id === 'plugin:demo-secret-plugin');
    assert.equal(pluginRec.requiresReauth, true, 'sensitive userConfig требует повторного ввода');
    assert.match(pluginRec.reauthReason, /userConfig/);

    // requiresReauth — явный список мест, где нужен повторный вход.
    const ids = manifest.requiresReauth.map(r => r.id).sort();
    assert.deepEqual(ids, ['mcp:secure-srv', 'plugin:demo-secret-plugin']);
});

test('Этап 8: восстановление из копии — состояние сохранено, секреты не восстанавливаются, requiresReauth показан', t => {
    const fx = backupFixture(t);
    const outPath = path.join(fx.dir, 'backup.zip');
    registry.createBackup({
        registryIndexPath: fx.indexPath,
        userStatePath: fx.statePath,
        outPath,
        mcpConfigPath: fx.mcpConfigPath
    });

    // «Сбой»: индекс утрачен; пользовательское состояние тоже сброшено.
    fs.rmSync(fx.indexPath);
    fs.rmSync(fx.statePath);

    const result = registry.restoreBackup({
        backupPath: outPath,
        registryIndexPath: fx.indexPath,
        userStatePath: fx.statePath,
        targetRoots: [fx.root]
    });
    assert.equal(result.ok, true, `восстановление прошло: ${result.error || ''}`);
    assert.equal(result.stateRestored, true);

    const pluginRestored = result.restored.find(r => r.id === 'plugin:demo-secret-plugin');
    assert.ok(pluginRestored, 'плагин восстановлен');
    assert.equal(pluginRestored.requiresReauth, true);
    const mcpRestored = result.restored.find(r => r.id === 'mcp:secure-srv');
    assert.ok(mcpRestored && mcpRestored.via === 'mcp-config', 'MCP-запись восстановлена как конфигурация');

    // Повторный вход показан явно.
    assert.ok(result.requiresReauth.length >= 2, 'requiresReauth возвращён явно');

    // Состояние: отключение и выданные права сохранены, новых прав нет.
    const us = registry.loadUserState(fx.statePath).packages['plugin:demo-secret-plugin'];
    assert.equal(us.enabled, false, 'отключение пережило восстановление');
    assert.deepEqual(us.permissions.granted, ['user-config:apiKey'], 'прежние права восстановлены как были');

    // Секреты не восстановились и не появились.
    const stateRaw = fs.readFileSync(fx.statePath, 'utf8');
    assert.ok(!stateRaw.includes('super-secret-value') && !stateRaw.includes('topsecret-tok'), 'секретов в состоянии нет');
    const index = registry.loadRegistryIndex(fx.indexPath);
    assert.ok(index.packages['plugin:demo-secret-plugin'], 'запись реестра восстановлена');
});

test('Этап 8: восстановление — пакет без файлов на диске получает честное «требуется переустановка»', t => {
    const fx = backupFixture(t);
    const outPath = path.join(fx.dir, 'backup.zip');
    registry.createBackup({
        registryIndexPath: fx.indexPath,
        userStatePath: fx.statePath,
        outPath,
        mcpConfigPath: fx.mcpConfigPath
    });

    // Файлы пакета исчезли с диска (backup содержит только manifests/настройки).
    fs.rmSync(path.join(fx.root, 'demo-secret-plugin'), { recursive: true, force: true });
    fs.rmSync(fx.indexPath);

    const result = registry.restoreBackup({
        backupPath: outPath,
        registryIndexPath: fx.indexPath,
        userStatePath: fx.statePath,
        targetRoots: [fx.root]
    });
    const missing = result.notRestored.find(r => r.id === 'plugin:demo-secret-plugin');
    assert.ok(missing, 'пакет без файлов не выдаётся за восстановленный');
    assert.match(missing.reason, /переустановка/);
    assert.equal(result.stateRestored, true, 'состояние восстанавливается даже без файлов пакета');
});

// ---------------------------------------------------------------------------
// Приёмка: перезапуск/обновление/восстановление не запускают платные вызовы —
// модуль реестра не импортирует сетевых модулей и не выполняет запросов.
// ---------------------------------------------------------------------------

test('Этап 8: модуль реестра не содержит сетевых вызовов (нет платных операций)', () => {
    const src = fs.readFileSync(REGISTRY_LIB_PATH, 'utf8');
    assert.ok(!/require\(['"]node:(http|https|net|tls)['"]\)/.test(src), 'нет импортов сетевых модулей');
    assert.ok(!/require\(['"](http|https|net|tls)['"]\)/.test(src), 'нет импортов сетевых модулей (без префикса)');
    assert.ok(!/\bfetch\s*\(/.test(src), 'нет fetch-вызовов');
    assert.ok(!/XMLHttpRequest|WebSocket|axios|node-fetch/.test(src), 'нет HTTP-клиентов');
});

// ---------------------------------------------------------------------------
// Проверка целостности: подделанный/пропавший файл после установки — провал
// (используется в updatePackage как одна из четырёх проверок).
// ---------------------------------------------------------------------------

test('Этап 8: validateInstalledUpdate фиксирует подделку и пропажу файлов', t => {
    const dir = tmpDir(t);
    const installDir = path.join(dir, 'pkg');
    write(installDir, 'SKILL.md', skillMarkdown({ name: 'demo-verify-skill', version: '1.0.0' }));

    // Хэш не совпадает с записанным (файл подделан после установки).
    const badHash = registry.validateInstalledUpdate({
        installDir,
        stagedFiles: [{ path: 'SKILL.md', size: 10, sha256: 'f'.repeat(64), executable: false }],
        packageKind: 'skill',
        expectedName: 'demo-verify-skill'
    });
    assert.equal(badHash.ok, false);
    assert.ok(badHash.failures.integrity.length > 0, 'целостность: хэш не совпал');

    // Файл отсутствует после установки.
    const missing = registry.validateInstalledUpdate({
        installDir,
        stagedFiles: [{ path: 'ghost.txt', size: 1, sha256: '0'.repeat(64), executable: false }],
        packageKind: 'skill',
        expectedName: 'demo-verify-skill'
    });
    assert.equal(missing.ok, false);
    assert.ok(missing.failures.integrity.some(m => m.includes('ghost.txt')), 'целостность: файл отсутствует');
});
