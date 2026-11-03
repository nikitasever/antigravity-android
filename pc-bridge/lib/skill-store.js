// Хранилище навыков (SKILL.md): список, деталь, создание, правка/переименование,
// включение/отключение, удаление. «Как у них» (референс Claude): SkillDetail /
// ViewSkillInstructions (полный текст), SkillEdit («Skill name», «Delete skill?»,
// «This skill and its version history will be permanently deleted.», «You already
// have a skill named /%1$s.», «This skill has settings that can only be edited on
// claude.ai.»). Правило данных: честные отказы (неизвестное действие, дубликат
// имени, встроенный навык только чтение, пустое содержимое) — успех не
// выдумывается.
'use strict';

const fs = require('node:fs');
const path = require('node:path');

function parseSkillMarkdown(rawMd, fallbackName) {
    let name = fallbackName;
    let description = '';
    let instruction = rawMd;
    const fmMatch = rawMd.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
    if (fmMatch) {
        const yamlPart = fmMatch[1];
        instruction = (fmMatch[2] || '').trim();
        const nameMatch = yamlPart.match(/^name:\s*(.+)$/m);
        if (nameMatch) name = nameMatch[1].trim().replace(/^["']|["']$/g, '');
        const descBlockMatch = yamlPart.match(/^description:\s*\|?\s*\r?\n((?:[ \t]+.+\r?\n?)+)/m);
        if (descBlockMatch) {
            description = descBlockMatch[1].replace(/^[ \t]+/gm, '').replace(/\s+/g, ' ').trim();
        } else {
            const descLineMatch = yamlPart.match(/^description:\s*(.+)$/m);
            if (descLineMatch) description = descLineMatch[1].trim().replace(/^["']|["']$/g, '');
        }
    }
    return { name, description, instruction };
}

/**
 * @param {object} opts
 * @param {Array<{dir: string, source: string}>} opts.skillRoots корни навыков
 *   (порядок приоритета дедупликации; у Claude свои источники — у нас
 *   workspace/global/builtin, подписи честные).
 * @param {string} opts.writeDir каталог, куда складываются создаваемые навыки.
 * @param {() => void} [opts.onMutate] вызывается после каждой записи/удаления.
 */
function createSkillStore({ skillRoots, writeDir, onMutate = () => {} }) {
    function listSkills() {
        const skills = [];
        const seenIds = new Set();

        for (const root of skillRoots) {
            try {
                if (!fs.existsSync(root.dir)) continue;
                for (const entry of fs.readdirSync(root.dir, { withFileTypes: true })) {
                    if (!entry.isDirectory()) continue;
                    const folderPath = path.join(root.dir, entry.name);
                    const activeFile = path.join(folderPath, 'SKILL.md');
                    const disabledFile = path.join(folderPath, 'SKILL.md.disabled');
                    let filePath = '';
                    let enabled = true;
                    if (fs.existsSync(activeFile)) {
                        filePath = activeFile;
                        enabled = true;
                    } else if (fs.existsSync(disabledFile)) {
                        filePath = disabledFile;
                        enabled = false;
                    } else {
                        continue;
                    }
                    const raw = fs.readFileSync(filePath, 'utf8');
                    const parsed = parseSkillMarkdown(raw, entry.name);
                    const skillId = parsed.name || entry.name;
                    if (seenIds.has(skillId)) continue;
                    seenIds.add(skillId);
                    skills.push({
                        id: entry.name,
                        name: skillId,
                        description: parsed.description || `Скилл Antigravity (${root.source})`,
                        instruction: parsed.instruction.slice(0, 4000),
                        instructionsPreview: parsed.instruction.slice(0, 240),
                        enabled,
                        source: root.source,
                        path: activeFile
                    });
                }
            } catch (_) {}
        }
        return skills;
    }

    function resolveSkillFile(found) {
        let filePath = found.path || '';
        if (!fs.existsSync(filePath) && filePath.endsWith('SKILL.md')) {
            const disabledPath = filePath.replace(/SKILL\.md$/, 'SKILL.md.disabled');
            if (fs.existsSync(disabledPath)) filePath = disabledPath;
        }
        return filePath;
    }

    function manageSkill(payload) {
        let action = String(payload.action || '').toLowerCase();
        if (action === 'toggle') {
            action = payload.enabled === false ? 'disable' : 'enable';
        }
        const rawName = String(payload.name || payload.id || '').trim();
        if (!rawName) {
            return { ok: false, error: 'Не указано имя скилла', skills: listSkills() };
        }

        // Деталь навыка «как у них»: полное содержимое SKILL.md (активного или
        // отключённого файла) для экрана просмотра инструкций.
        if (action === 'detail' || action === 'skill_detail' || action === 'read') {
            const all = listSkills();
            const found = all.find(s => s.id === rawName || s.name === rawName);
            if (!found) {
                return { ok: false, error: `Скилл не найден: ${rawName}`, skills: all };
            }
            const filePath = resolveSkillFile(found);
            let content = '';
            try { content = fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : ''; } catch (_) {}
            return {
                ok: true,
                skill: {
                    id: found.id,
                    name: found.name,
                    description: found.description,
                    enabled: found.enabled,
                    source: found.source,
                    path: filePath,
                    content
                },
                skills: all
            };
        }

        // Правка/переименование «как у них» (SkillEdit + «Skill name»).
        if (action === 'update' || action === 'edit' || action === 'save' || action === 'rename') {
            const all = listSkills();
            const found = all.find(s => s.id === rawName || s.name === rawName);
            if (!found) {
                return { ok: false, error: `Скилл не найден: ${rawName}`, skills: all };
            }
            if (found.source === 'builtin') {
                // Аналог их «This skill has settings that can only be edited on
                // claude.ai.»: встроенная копия — только чтение.
                return {
                    ok: false,
                    error: 'Встроенные навыки доступны только для чтения — редактирование выполняется в источнике.',
                    code: 'skill_read_only',
                    skills: all
                };
            }
            const filePath = resolveSkillFile(found);
            if (!fs.existsSync(filePath)) {
                return { ok: false, error: `Файл навыка не найден: ${found.id}`, skills: all };
            }
            const newName = String(payload.newName || '').trim();
            if (newName && newName !== found.name && all.some(s => s.id !== found.id && s.name === newName)) {
                // Аналог их «You already have a skill named /%1$s.».
                return {
                    ok: false,
                    error: `Навык с именем «${newName}» уже существует`,
                    code: 'skill_name_taken',
                    skills: all
                };
            }
            let nextContent = (payload.content !== undefined && payload.content !== null)
                ? String(payload.content)
                : (() => { try { return fs.readFileSync(filePath, 'utf8'); } catch (_) { return ''; } })();
            if (newName && newName !== found.name) {
                if (/^---\r?\n[\s\S]*?\r?\n---/.test(nextContent)) {
                    if (/^name:\s*.+$/m.test(nextContent)) {
                        nextContent = nextContent.replace(/^name:\s*.+$/m, `name: ${newName}`);
                    } else {
                        nextContent = nextContent.replace(/^---\r?\n/, `---\nname: ${newName}\n`);
                    }
                } else {
                    nextContent =
                        `---\n` +
                        `name: ${newName}\n` +
                        `description: ${JSON.stringify(found.description || '')}\n` +
                        `---\n\n` +
                        `${nextContent.trim()}\n`;
                }
            }
            if (!nextContent.trim()) {
                return { ok: false, error: 'Содержимое навыка не может быть пустым', skills: all };
            }
            fs.writeFileSync(filePath, nextContent, 'utf8');
            onMutate();
            return {
                ok: true,
                message: newName && newName !== found.name
                    ? `Навык «${found.name}» переименован в «${newName}» и сохранён`
                    : `Навык «${found.name}» сохранён`,
                skill: { id: found.id, name: newName || found.name, path: filePath },
                skills: listSkills()
            };
        }

        const slug = rawName.toLowerCase().replace(/[^a-z0-9_-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || `skill-${Date.now()}`;
        const folderPath = path.join(writeDir, fs.existsSync(path.join(writeDir, rawName)) ? rawName : slug);
        const activeFile = path.join(folderPath, 'SKILL.md');
        const disabledFile = path.join(folderPath, 'SKILL.md.disabled');

        if (action === 'add' || action === 'install' || action === 'create') {
            const description = String(payload.description || `Специализированный навык ${rawName}`).trim();
            const instruction = String(payload.instruction || payload.instructions || payload.content || `# ${rawName}\n\nИнструкция выполнения навыка ${rawName}.`).trim();
            fs.mkdirSync(folderPath, { recursive: true });
            const mdContent =
                `---\n` +
                `name: ${slug}\n` +
                `description: ${JSON.stringify(description)}\n` +
                `---\n\n` +
                `${instruction}\n`;
            fs.writeFileSync(activeFile, mdContent, 'utf8');
            if (fs.existsSync(disabledFile)) {
                try { fs.unlinkSync(disabledFile); } catch (_) {}
            }
            onMutate();
            return {
                ok: true,
                message: `Скилл "${slug}" создан в ${activeFile}`,
                skills: listSkills()
            };
        }

        if (action === 'enable') {
            if (fs.existsSync(disabledFile) && !fs.existsSync(activeFile)) {
                fs.renameSync(disabledFile, activeFile);
            }
            onMutate();
            return { ok: true, message: `Скилл "${rawName}" включён`, skills: listSkills() };
        }

        if (action === 'disable') {
            if (fs.existsSync(activeFile)) {
                fs.renameSync(activeFile, disabledFile);
            }
            onMutate();
            return { ok: true, message: `Скилл "${rawName}" отключён`, skills: listSkills() };
        }

        if (action === 'remove' || action === 'uninstall' || action === 'delete') {
            // Удаление по фактической папке навыка (workspace/global/builtin);
            // раньше путь вычислялся только в глобальном каталоге и для навыков
            // workspace мог удалять чужую папку или ничего не удалять.
            const all = listSkills();
            const found = all.find(s => s.id === rawName || s.name === rawName);
            if (found && found.source === 'builtin') {
                return {
                    ok: false,
                    error: 'Встроенные навыки удаляются только в источнике.',
                    code: 'skill_read_only',
                    skills: all
                };
            }
            let targetFolder = '';
            if (found && found.path) {
                targetFolder = path.dirname(found.path);
            } else if (fs.existsSync(folderPath)) {
                targetFolder = folderPath;
            }
            if (targetFolder && fs.existsSync(targetFolder)) {
                fs.rmSync(targetFolder, { recursive: true, force: true });
            }
            onMutate();
            return { ok: true, message: `Скилл "${rawName}" удалён`, skills: listSkills() };
        }

        return { ok: false, error: `Неизвестное действие для скилла: ${action}`, skills: listSkills() };
    }

    return { parseSkillMarkdown, listSkills, manageSkill };
}

module.exports = { createSkillStore, parseSkillMarkdown };
