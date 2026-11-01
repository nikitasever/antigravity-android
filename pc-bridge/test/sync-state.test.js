const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

function extractMerge() {
    const start = source.indexOf('function mergeSyncState(');
    const end = source.indexOf('\n}', start) + 2;
    assert.ok(start > 0 && end > start, 'mergeSyncState найден в server.js');
    return vm.runInNewContext(`(${source.slice(start, end).trim()})`, {});
}

test('mergeSyncState: более новый updatedAtMs побеждает, старые записи сохраняются', () => {
    const merge = extractMerge();
    const stored = {
        chatPrefs: {
            chat1: { title: 'старое имя', pinned: false, updatedAtMs: 100 },
            chat2: { title: 'только на ПК', pinned: true, updatedAtMs: 50 }
        },
        savedArtifacts: [
            { id: 'saved_message_a', content: 'старый текст', updatedAtMs: 100 }
        ],
        deletedIds: {}
    };
    const incoming = {
        chatPrefs: {
            chat1: { title: 'новое имя', pinned: true, updatedAtMs: 200 },
            chat3: { title: 'только на телефоне', updatedAtMs: 10 }
        },
        savedArtifacts: [
            { id: 'saved_message_a', content: 'новый текст', updatedAtMs: 300 },
            { id: 'saved_message_b', content: 'новый ответ', updatedAtMs: 400 }
        ],
        deletedIds: {}
    };
    const merged = merge(stored, incoming);
    assert.equal(merged.chatPrefs.chat1.title, 'новое имя');
    assert.equal(merged.chatPrefs.chat2.title, 'только на ПК');
    assert.equal(merged.chatPrefs.chat3.title, 'только на телефоне');
    const artifactA = merged.savedArtifacts.find(a => a.id === 'saved_message_a');
    assert.equal(artifactA.content, 'новый текст');
    assert.ok(merged.savedArtifacts.some(a => a.id === 'saved_message_b'));
});

test('mergeSyncState: входящая запись старше локальной не перетирает её', () => {
    const merge = extractMerge();
    const stored = {
        chatPrefs: { chat1: { title: 'актуальное', updatedAtMs: 500 } },
        savedArtifacts: [{ id: 'saved_message_a', content: 'актуальный', updatedAtMs: 500 }],
        deletedIds: {}
    };
    const incoming = {
        chatPrefs: { chat1: { title: 'устаревшее', updatedAtMs: 100 } },
        savedArtifacts: [{ id: 'saved_message_a', content: 'устаревший', updatedAtMs: 100 }],
        deletedIds: {}
    };
    const merged = merge(stored, incoming);
    assert.equal(merged.chatPrefs.chat1.title, 'актуальное');
    assert.equal(merged.savedArtifacts.find(a => a.id === 'saved_message_a').content, 'актуальный');
});

test('mergeSyncState: надгробие удаляет запись и блокирует более старые', () => {
    const merge = extractMerge();
    const stored = {
        chatPrefs: { chat1: { title: 'будет удалён', updatedAtMs: 100 } },
        savedArtifacts: [{ id: 'saved_message_a', content: 'будет удалён', updatedAtMs: 100 }],
        deletedIds: {}
    };
    const incoming = {
        chatPrefs: {},
        savedArtifacts: [{ id: 'saved_message_b', content: 'старое воскрешение', updatedAtMs: 50 }],
        deletedIds: { chat1: 300, 'saved_message_a': 300, 'saved_message_b': 100 }
    };
    const merged = merge(stored, incoming);
    assert.equal(merged.chatPrefs.chat1, undefined);
    assert.ok(!merged.savedArtifacts.some(a => a.id === 'saved_message_a'));
    assert.ok(!merged.savedArtifacts.some(a => a.id === 'saved_message_b'), 'запись старее надгробия не воскресает');
    assert.equal(merged.deletedIds.chat1, 300);
    assert.equal(merged.deletedIds['saved_message_b'], 100);
});

test('mergeSyncState: запись новее надгробия воскрешает и очищает надгробие', () => {
    const merge = extractMerge();
    const stored = { chatPrefs: {}, savedArtifacts: [], deletedIds: { chat1: 100, 'saved_message_a': 100 } };
    const incoming = {
        chatPrefs: { chat1: { title: 'восстановлено', updatedAtMs: 200 } },
        savedArtifacts: [{ id: 'saved_message_a', content: 'восстановлено', updatedAtMs: 200 }],
        deletedIds: {}
    };
    const merged = merge(stored, incoming);
    assert.equal(merged.chatPrefs.chat1.title, 'восстановлено');
    assert.ok(merged.savedArtifacts.some(a => a.id === 'saved_message_a'));
    assert.equal(merged.deletedIds.chat1, undefined);
    assert.equal(merged.deletedIds['saved_message_a'], undefined);
});

test('mergeSyncState: повреждённые записи игнорируются, порядок артефактов — по убыванию свежести', () => {
    const merge = extractMerge();
    const merged = merge({ chatPrefs: {}, savedArtifacts: [], deletedIds: {} }, {
        chatPrefs: { bad: null, good: { title: 'ok', updatedAtMs: 1 } },
        savedArtifacts: [null, { content: 'без id' }, { id: 'x', updatedAtMs: 10 }, { id: 'y', updatedAtMs: 20 }],
        deletedIds: {}
    });
    assert.equal(merged.chatPrefs.good.title, 'ok');
    assert.equal(merged.chatPrefs.bad, undefined);
    assert.deepEqual(merged.savedArtifacts.map(a => a.id), ['y', 'x']);
});

test('mergeSyncState: рабочие области проектов сливаются по свежести и подчиняются надгробиям', () => {
    const merge = extractMerge();
    const stored = {
        chatPrefs: {},
        savedArtifacts: [],
        projectWorkspaces: [
            { id: 'project_a', name: 'A', instructions: 'старые', updatedAtMs: 100 },
            { id: 'project_b', name: 'B', instructions: 'будет удалена', updatedAtMs: 100 }
        ],
        deletedIds: {}
    };
    const incoming = {
        chatPrefs: {},
        savedArtifacts: [],
        projectWorkspaces: [
            { id: 'project_a', name: 'A', instructions: 'новые', updatedAtMs: 200 },
            { id: 'project_c', name: 'C', instructions: 'с телефона', updatedAtMs: 50 }
        ],
        deletedIds: { project_b: 300, project_c: 100 }
    };
    const merged = merge(stored, incoming);
    const byId = Object.fromEntries(merged.projectWorkspaces.map(w => [w.id, w]));
    assert.equal(byId.project_a.instructions, 'новые');
    assert.equal(byId.project_b, undefined, 'надгробие удаляет рабочую область');
    assert.equal(byId.project_c, undefined, 'запись старее надгробия не воскресает');
    assert.equal(merged.deletedIds.project_b, 300);
});

test('deletion wins over equal-timestamp retries for every synced entity', () => {
    const merge = extractMerge();
    const stored = { chatPrefs: {}, savedArtifacts: [], projectWorkspaces: [], deletedIds: { pref: 100, doc: 100, project: 100 } };
    const incoming = { chatPrefs: { pref: { title: 'stale', updatedAtMs: 100 } },
        savedArtifacts: [{ id: 'doc', content: 'stale', updatedAtMs: 100 }],
        projectWorkspaces: [{ id: 'project', name: 'stale', updatedAtMs: 100 }] };
    const merged = merge(stored, incoming);
    assert.equal(Object.keys(merged.chatPrefs).length, 0);
    assert.equal(merged.savedArtifacts.length, 0);
    assert.equal(merged.projectWorkspaces.length, 0);
    assert.equal(merged.deletedIds.doc, 100);
});

test('sync persistence replaces a complete file and reports failed replacement without losing old data', () => {
    const directory = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'antigravity-sync-'));
    const file = path.join(directory, 'state.json');
    const start = source.indexOf('function saveSyncState(');
    const end = source.indexOf('\n}', start) + 2;
    const create = fsImpl => vm.runInNewContext(`(${source.slice(start, end)})`, { fs: fsImpl, SYNC_STATE_FILE: file });
    try {
        create(fs)({ savedArtifacts: [{ id: 'doc', content: 'initial' }] });
        const original = fs.readFileSync(file, 'utf8');
        const failedSave = create({ ...fs, renameSync() { throw new Error('DISK_UNAVAILABLE'); } });
        assert.throws(() => failedSave({ savedArtifacts: [] }), /DISK_UNAVAILABLE/);
        assert.equal(fs.readFileSync(file, 'utf8'), original);
        assert.equal(fs.existsSync(file + '.tmp'), false);
        create(fs)({ savedArtifacts: [{ id: 'doc', content: 'updated' }] });
        assert.equal(JSON.parse(fs.readFileSync(file, 'utf8')).savedArtifacts[0].content, 'updated');
    } finally {
        fs.rmSync(file, { force: true });
        fs.rmSync(file + '.tmp', { force: true });
        fs.rmdirSync(directory);
    }
});
