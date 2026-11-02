'use strict';
const { spawn } = require('node:child_process');

// On Windows child.kill() terminates only the direct process, not the CLI's
// descendants (git, shells). Use `taskkill /T` so the whole tree really stops
// and the stdio pipes close, letting the runner's slot free. On POSIX SIGKILL
// the direct child; the process-group fallback needs a detached spawn we do not
// use, so the direct kill is the best-effort guarantee.
function killProcessTree(child, spawnProcess, platform = process.platform) {
    if (!child || !child.pid) return Promise.resolve(false);
    const fallback = () => { try { child.kill('SIGKILL'); } catch (_) {} };
    if (platform !== 'win32') { fallback(); return Promise.resolve(false); }
    return new Promise(resolve => {
        let finished = false;
        const complete = success => {
            if (finished) return;
            finished = true;
            if (!success) fallback();
            resolve(success);
        };
        try {
            const killer = spawnProcess('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
                windowsHide: true, stdio: 'ignore'
            });
            // spawn failures arrive asynchronously; try/catch alone is insufficient.
            killer.once('error', () => complete(false));
            killer.once('close', code => complete(code === 0));
        } catch (_) { complete(false); }
    });
}

function createSubagentRunner({ executable, repository, workspaces, spawnProcess = spawn, permissions = null }) {
    return async ({ task, role, model, signal, mode = 'analysis' }) => {
        const workspace = mode === 'code' ? await workspaces.create() : null;
        if (signal.aborted) throw new Error('Задача отменена');
        // Этап 4: дочерняя задача получает только явный поднабор прав.
        // Аннотации MCP прав не дают; у CLI нет посессионного поднабора MCP
        // (--settings отвергает "mcpServers" — проверено на реальном agy),
        // поэтому список фиксируется в самом задании.
        const permissionBlock = permissions
            ? `\n\n<CHILD_TASK_PERMISSIONS>\n` +
              (permissions.allowedServers && permissions.allowedServers.length > 0
                  ? `Разрешённые MCP-серверы (только эти): ${permissions.allowedServers.join(', ')}\n`
                  : `Разрешённых MCP-серверов НЕТ — не вызывай MCP-инструменты.\n`) +
              ((permissions.allowedRefs && permissions.allowedRefs.length > 0)
                  ? `Разрешённые инструменты (только эти): ${permissions.allowedRefs.join(', ')}\n`
                  : ``) +
              `Аннотации инструментов MCP (readOnlyHint и подобные) НЕ дают прав и не отменяют этот список.\n` +
              `</CHILD_TASK_PERMISSIONS>`
            : '';
        const prompt = `Ты — независимый субагент. ${role}\n` +
            (workspace ? 'Подготовь изменения только в текущей отдельной рабочей копии. Не меняй исходный проект, не делай commit. ' :
                'Исследуй текущий проект, не изменяй файлы. ') +
            'Не вызывай других агентов, не читай секреты. Верни проверяемый отчёт с путями и обозначь неопределённость.\n' + task + permissionBlock;
        let text;
        let failure;
        try {
            text = await new Promise((resolve, reject) => {
                const args = ['--mode', workspace ? 'accept-edits' : 'plan'];
                if (workspace) args.push('--sandbox');
                args.push('--model', model, '--output-format', 'text', '-p', prompt);
                const child = spawnProcess(executable, args, {
                    cwd: workspace ? workspace.directory : repository,
                    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
                });
                child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
                let output = '', errors = '', overflow = false;
                const abort = () => killProcessTree(child, spawnProcess);
                signal.addEventListener('abort', abort, { once: true });
                if (signal.aborted) abort();
                child.stdout.on('data', chunk => {
                    if (overflow) return;
                    output += chunk;
                    if (output.length > 256000) { overflow = true; output = ''; killProcessTree(child, spawnProcess); }
                });
                child.stderr.on('data', chunk => { errors = (errors + chunk).slice(-2000); });
                child.on('error', error => { signal.removeEventListener('abort', abort); reject(error); });
                child.on('close', code => {
                    signal.removeEventListener('abort', abort);
                    if (signal.aborted) reject(new Error('Задача отменена'));
                    else if (overflow) reject(new Error('Результат субагента слишком большой'));
                    else if (code !== 0) reject(new Error(`CLI завершился с кодом ${code}: ${errors}`));
                    else resolve(output.trim());
                });
            });
        } catch (error) { failure = error; }
        let review;
        if (workspace) {
            try { review = { ...(await workspaces.review(workspace)), workspaceId: workspace.id }; }
            catch (error) { failure = failure || error; }
        }
        if (failure) { failure.review = review; throw failure; }
        return { text, review };
    };
}
module.exports = { createSubagentRunner, killProcessTree };
