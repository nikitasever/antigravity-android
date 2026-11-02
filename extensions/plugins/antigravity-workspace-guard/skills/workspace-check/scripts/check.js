'use strict';
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
function check(workspace) {
    const root = fs.realpathSync(workspace);
    const run = args => spawnSync('git', ['-C', root, ...args], { encoding: 'utf8', timeout: 10000, maxBuffer: 1024 * 1024, windowsHide: true });
    const status = run(['status', '--porcelain=v1']);
    if (status.status !== 0) return { ok: false, error: 'Git-репозиторий недоступен' };
    const diff = run(['diff', '--check']);
    const staged = run(['diff', '--cached', '--check']);
    return { ok: diff.status === 0 && staged.status === 0, workspace: root, dirty: Boolean(status.stdout.trim()), status: status.stdout, diagnostics: diff.stdout + staged.stdout };
}
function guard(input) {
    const args = input?.toolCall?.args || {};
    const command = String(args.command || args.cmd || args.script || '');
    // Deliberately bounded policy, not a shell parser or a sandbox.
    const risky = /\bgit(?:\.exe)?\s+(?:[^;&|\n]*\s)?(?:reset\s+--hard|clean\s+[^;&|\n]*-[a-z]*f|push\s+[^;&|\n]*(?:--force(?:-with-lease)?\b|(?:^|\s)-f\b)|checkout\s+--\s)/i.test(command);
    return risky ? { decision: 'deny', reason: 'Workspace guard: команда может уничтожить или перезаписать изменения Git. Выполните её отдельно после проверки.' } : {};
}
if (require.main === module) {
    try {
        if (process.argv.includes('--hook')) {
            console.log(JSON.stringify(guard(JSON.parse(fs.readFileSync(0, 'utf8')))));
        } else {
            const i = process.argv.indexOf('--workspace');
            if (i < 0 || !process.argv[i + 1]) throw new Error('Требуется --workspace');
            const result = check(path.resolve(process.argv[i + 1]));
            console.log(JSON.stringify(result)); process.exitCode = result.ok ? 0 : 1;
        }
    } catch { console.log(JSON.stringify({ ok: false, error: 'Не удалось прочитать проект или вход hook' })); process.exitCode = 1; }
}
module.exports = { check, guard };
