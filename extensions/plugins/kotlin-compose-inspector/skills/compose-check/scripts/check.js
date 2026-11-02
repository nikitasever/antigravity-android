'use strict';
const fs = require('node:fs');
const path = require('node:path');
function inspect(workspace) {
    const root = fs.realpathSync(workspace), diagnostics = [];
    let files = 0, visited = 0;
    function walk(dir, depth) {
        if (depth > 30) throw new Error('Глубина проекта превышена');
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            if (++visited > 50000) throw new Error('Слишком большой проект');
            if (entry.isSymbolicLink() || ['.git', '.gradle', '.kotlin', 'build', 'node_modules'].includes(entry.name)) continue;
            const file = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(file, depth + 1);
            else if (entry.isFile() && /\.kts?$/.test(entry.name)) {
                if (fs.statSync(file).size > 2 * 1024 * 1024) throw new Error('Слишком большой исходник');
                files++;
                const rel = path.relative(root, file);
                fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
                    if (/^(<{7}|={7}|>{7})(\s|$)/.test(line)) diagnostics.push({ file: rel, line: i + 1, severity: 'error', message: 'Маркер конфликта слияния' });
                    else if (/\b(TODO|FIXME)\b/.test(line)) diagnostics.push({ file: rel, line: i + 1, severity: 'warning', message: 'Незавершённая реализация' });
                    if (diagnostics.length > 2000) throw new Error('Слишком много замечаний');
                });
            }
        }
    }
    walk(root, 0);
    return { ok: !diagnostics.some(d => d.severity === 'error'), files, diagnostics };
}
if (require.main === module) {
    try {
        const hook = process.argv.includes('--hook');
        const input = hook ? JSON.parse(fs.readFileSync(0, 'utf8')) : null;
        if (hook && !/\.kts?$/i.test(String(input?.toolCall?.args?.path || input?.toolCall?.args?.file_path || ''))) {
            console.log('{}'); process.exit(0);
        }
        const index = process.argv.indexOf('--workspace');
        const root = hook ? input.workspacePaths?.[0] : index >= 0 ? process.argv[index + 1] : null;
        // Missing cwd is a visible refusal; never scan an arbitrary current dir.
        if (!root || !path.isAbsolute(root)) throw new Error('Требуется абсолютный путь проекта');
        const result = inspect(root);
        console.log(JSON.stringify(hook ? { injectSteps: [{ ephemeralMessage: 'Kotlin inspector: ' + JSON.stringify(result) }] } : result));
        if (!hook) process.exitCode = result.ok ? 0 : 1;
    } catch { console.log(JSON.stringify({ ok: false, error: 'Kotlin inspector: проект недоступен или превышен лимит проверки' })); process.exitCode = 1; }
}
module.exports = { inspect };
