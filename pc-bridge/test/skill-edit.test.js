// Правка и удаление навыков «как у них» (референс Claude: SkillEdit, «Skill
// name», «Delete skill?», «You already have a skill named /%1$s.», «This skill
// has settings that can only be edited on claude.ai.»). Проверяются: правка
// содержимого, переименование со стабильным id (папкой), отказ при дубликате
// имени, отказ для встроенных навыков (только чтение), работа с отключённым
// файлом, удаление по фактической папке источника.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createSkillStore } = require('../lib/skill-store');

function writeSkill(root, folder, { name, description = 'тест', body = '# Тело' }, file = 'SKILL.md') {
    const dir = path.join(root, folder);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(
        path.join(dir, file),
        `---\nname: ${name}\ndescription: ${JSON.stringify(description)}\n---\n\n${body}\n`,
        'utf8'
    );
    return dir;
}

function tmpStore(t, { builtin = false } = {}) {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-skilledit-'));
    t.after(() => fs.rmSync(base, { recursive: true, force: true }));
    const workspace = path.join(base, 'workspace');
    const globalDir = path.join(base, 'global');
    const builtinDir = path.join(base, 'builtin');
    fs.mkdirSync(workspace, { recursive: true });
    fs.mkdirSync(globalDir, { recursive: true });
    fs.mkdirSync(builtinDir, { recursive: true });
    const mutations = [];
    const store = createSkillStore({
        skillRoots: [
            { dir: workspace, source: 'workspace' },
            { dir: globalDir, source: 'global' },
            { dir: builtinDir, source: 'builtin' }
        ],
        writeDir: globalDir,
        onMutate: () => mutations.push(1)
    });
    return { store, workspace, globalDir, builtinDir, mutations };
}

test('skill-edit: правка содержимого сохраняется и видна в detail', t => {
    const { store, globalDir, mutations } = tmpStore(t);
    writeSkill(globalDir, 'demo', { name: 'demo', body: '# Старое' });
    const upd = store.manageSkill({ action: 'update', name: 'demo', content: '---\nname: demo\ndescription: "тест"\n---\n\n# Новое тело\n' });
    assert.equal(upd.ok, true);
    assert.equal(mutations.length, 1);
    const det = store.manageSkill({ action: 'detail', name: 'demo' });
    assert.equal(det.ok, true);
    assert.match(det.skill.content, /# Новое тело/);
});

test('skill-edit: переименование меняет имя в frontmatter, id (папка) стабилен', t => {
    const { store, globalDir } = tmpStore(t);
    writeSkill(globalDir, 'demo', { name: 'demo' });
    const ren = store.manageSkill({ action: 'update', name: 'demo', newName: 'renamed' });
    assert.equal(ren.ok, true);
    assert.equal(ren.skill.id, 'demo', 'id остаётся именем папки');
    assert.equal(ren.skill.name, 'renamed');
    const det = store.manageSkill({ action: 'detail', name: 'renamed' });
    assert.equal(det.ok, true);
    assert.equal(det.skill.id, 'demo');
    assert.match(det.skill.content, /^name: renamed$/m);
});

test('skill-edit: дубликат имени отклоняется (аналог «You already have a skill named»)', t => {
    const { store, globalDir } = tmpStore(t);
    writeSkill(globalDir, 'one', { name: 'one' });
    writeSkill(globalDir, 'two', { name: 'two' });
    const dup = store.manageSkill({ action: 'update', name: 'one', newName: 'two' });
    assert.equal(dup.ok, false);
    assert.equal(dup.code, 'skill_name_taken');
    assert.match(dup.error, /уже существует/);
    const det = store.manageSkill({ action: 'detail', name: 'one' });
    assert.equal(det.skill.name, 'one', 'имя не изменилось после отказа');
});

test('skill-edit: встроенный навык — только чтение (аналог «can only be edited on claude.ai»)', t => {
    const { store, builtinDir } = tmpStore(t);
    writeSkill(builtinDir, 'core', { name: 'core' });
    const upd = store.manageSkill({ action: 'update', name: 'core', content: '# Взлом' });
    assert.equal(upd.ok, false);
    assert.equal(upd.code, 'skill_read_only');
    const del = store.manageSkill({ action: 'remove', name: 'core' });
    assert.equal(del.ok, false);
    assert.equal(del.code, 'skill_read_only');
});

test('skill-edit: пустое содержимое отклоняется, изменения не пишутся', t => {
    const { store, globalDir } = tmpStore(t);
    writeSkill(globalDir, 'demo', { name: 'demo', body: '# Тело' });
    const upd = store.manageSkill({ action: 'update', name: 'demo', content: '   \n  ' });
    assert.equal(upd.ok, false);
    assert.match(upd.error, /не может быть пустым/);
    const det = store.manageSkill({ action: 'detail', name: 'demo' });
    assert.match(det.skill.content, /# Тело/, 'исходное содержимое цело');
});

test('skill-edit: отключённый навык (SKILL.md.disabled) редактируется и включается', t => {
    const { store, globalDir } = tmpStore(t);
    writeSkill(globalDir, 'demo', { name: 'demo', body: '# Тело' }, 'SKILL.md.disabled');
    const upd = store.manageSkill({ action: 'update', name: 'demo', content: '---\nname: demo\ndescription: "тест"\n---\n\n# После правки\n' });
    assert.equal(upd.ok, true);
    const en = store.manageSkill({ action: 'enable', name: 'demo' });
    assert.equal(en.ok, true);
    const det = store.manageSkill({ action: 'detail', name: 'demo' });
    assert.equal(det.skill.enabled, true);
    assert.match(det.skill.content, /# После правки/);
});

test('skill-edit: удаление идёт по фактической папке источника (workspace)', t => {
    const { store, workspace } = tmpStore(t);
    const dir = writeSkill(workspace, 'wskill', { name: 'wskill' });
    assert.equal(fs.existsSync(dir), true);
    const del = store.manageSkill({ action: 'remove', name: 'wskill' });
    assert.equal(del.ok, true);
    assert.equal(fs.existsSync(dir), false, 'папка workspace-навыка удалена');
});

test('skill-edit: неизвестное действие — честный отказ', t => {
    const { store } = tmpStore(t);
    const r = store.manageSkill({ action: 'explode', name: 'any' });
    assert.equal(r.ok, false);
    assert.match(r.error, /Неизвестное действие/);
});
