'use strict';
// ---------------------------------------------------------------------------
// Этап 5 (EXTENSIONS_INTEGRATION_PLAN.md): собственный исполнитель локальных
// программ и скриптов (компоненты executable/scripts пакетов расширений).
//
// Требования плана:
//  - для скриптов подготовить ЗАВИСИМОСТИ (проверка интерпретатора и
//    объявленных пакетов до запуска) и ИЗОЛЯЦИЮ рабочего каталога (cwd скрипта —
//    служебный временный каталог или явно заданный caller'ом, НЕ корень проекта);
//  - Android-совместимость/работоспособность доказывать запуском: модуль
//    реально запускает процессы (тесты гоняют настоящие скрипты), а не
//    декларирует запуск.
//
// Границы честности:
//  - интерпретатор определяется по shebang/расширению; неизвестный вид — явный
//    блокер, не молчаливый запуск через shell;
//  - для node проверяются внешние require/import против node_modules и
//    dependencies ближайшего package.json; для python — requirements.txt и
//    import-заявки (удовлетворённость без probe — «требует проверки»);
//  - окружение: allowlist базовых переменных + явные extras; DSH_* и похожие
//    на секреты имена не передаются.
// ---------------------------------------------------------------------------
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { isSecretishEnvName } = require('./hook-engine');

const NODE_BUILTINS = new Set(require('node:module').builtinModules || []);
const PYTHON_STDLIB = new Set([
    'abc', 'argparse', 'asyncio', 'base64', 'collections', 'csv', 'datetime',
    'enum', 'functools', 'glob', 'hashlib', 'io', 'itertools', 'json', 'logging',
    'math', 'os', 'pathlib', 'random', 're', 'shutil', 'socket', 'sqlite3',
    'string', 'subprocess', 'sys', 'tempfile', 'threading', 'time', 'typing',
    'unittest', 'urllib', 'uuid', 'xml', 'zipfile'
]);
const ENV_ALLOWLIST = ['PATH', 'SystemRoot', 'WINDIR', 'COMSPEC', 'PATHEXT', 'TEMP', 'TMP', 'TMPDIR', 'HOME', 'USERPROFILE', 'LANG', 'LC_ALL', 'TZ'];

function buildScriptEnv(baseEnv, extraEnv = {}) {
    const out = {};
    for (const k of ENV_ALLOWLIST) {
        if (baseEnv && baseEnv[k] != null) out[k] = baseEnv[k];
    }
    for (const [k, v] of Object.entries(extraEnv)) {
        if (!isSecretishEnvName(k) && v != null) out[k] = String(v);
    }
    return out;
}

function detectInterpreter(scriptPath, content) {
    const firstLine = String(content || '').split(/\r?\n/, 1)[0] || '';
    const shebang = firstLine.startsWith('#!') ? firstLine.slice(2).trim() : '';
    const ext = path.extname(String(scriptPath || '')).toLowerCase();
    const issues = [];
    let kind = 'unknown';
    let command = null;

    if (/node(\.exe)?\b/i.test(shebang)) { kind = 'node'; command = process.execPath; }
    else if (/(python)(\d+(\.\d+)?)?(\.exe)?\b/i.test(shebang)) { kind = 'python'; command = (shebang.match(/[\w./\\-]*python[\w.]*/i) || [null])[0]; }
    else if (/(^|[\\/])(ba)?sh\b/i.test(shebang)) { kind = 'shell'; command = process.platform === 'win32' ? null : '/bin/sh'; }
    else if (/\b(pwsh|powershell)\b/i.test(shebang)) { kind = 'powershell'; command = 'pwsh'; }
    else if (shebang) {
        kind = 'unknown';
        issues.push(`shebang "${shebang.slice(0, 60)}" не отнесён к поддерживаемым интерпретаторам`);
    }
    if (kind === 'unknown') {
        if (['.js', '.mjs', '.cjs'].includes(ext)) { kind = 'node'; command = process.execPath; }
        else if (['.py'].includes(ext)) { kind = 'python'; command = 'python'; }
        else if (['.sh'].includes(ext)) { kind = 'shell'; command = process.platform === 'win32' ? null : '/bin/sh'; }
        else if (['.ps1'].includes(ext)) { kind = 'powershell'; command = 'pwsh'; }
        else if (['.bat', '.cmd'].includes(ext)) { kind = 'cmd'; command = process.env.ComSpec || 'cmd.exe'; }
        else if (['.exe', '.com'].includes(ext)) { kind = 'binary'; command = scriptPath; }
        else issues.push(`скрипт "${path.basename(String(scriptPath || ''))}" без shebang и известного расширения — интерпретатор не определён`);
    }
    return { kind, command, shebang: shebang || null, issues };
}

function findNearestPackageJson(startDir) {
    let dir = startDir;
    for (let i = 0; i < 12; i++) {
        const candidate = path.join(dir, 'package.json');
        if (fs.existsSync(candidate)) return candidate;
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    return null;
}

function scanNodeExternals(content) {
    const names = new Set();
    const re = /require\(\s*['"]([^'"]+)['"]\s*\)|from\s+['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/g;
    let m;
    while ((m = re.exec(String(content || '')))) {
        const spec = m[1] || m[2] || m[3] || '';
        if (!spec || spec.startsWith('.') || spec.startsWith('node:') || spec.startsWith('/')) continue;
        const base = spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0];
        if (!NODE_BUILTINS.has(base) && !NODE_BUILTINS.has(spec)) names.add(base);
    }
    return [...names];
}

function scanPythonImports(content) {
    const names = new Set();
    const re = /^\s*(?:import\s+([\w.]+)|from\s+([\w.]+)\s+import)/gm;
    let m;
    while ((m = re.exec(String(content || '')))) {
        const full = m[1] || m[2] || '';
        const top = full.split('.')[0];
        if (top && !PYTHON_STDLIB.has(top)) names.add(top);
    }
    return [...names];
}

function nodeModuleSatisfied(name, fromDir) {
    let dir = fromDir;
    for (let i = 0; i < 12; i++) {
        if (fs.existsSync(path.join(dir, 'node_modules', name, 'package.json'))) return true;
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    return false;
}

// Проверка зависимостей до запуска: интерпретатор + объявленные пакеты.
function checkScriptDependencies({ scriptPath, content, packageRoot = null, probeInterpreter = null } = {}) {
    const issues = [];
    const dependencies = [];
    const missing = [];
    let text = content;
    if (text == null) {
        try {
            text = fs.readFileSync(scriptPath, 'utf8');
        } catch (err) {
            return {
                interpreter: { kind: 'unknown', available: false, version: null },
                dependencies, missing: [], issues: [`не удалось прочитать скрипт: ${err.message}`], ready: false
            };
        }
    }
    const interp = detectInterpreter(scriptPath, text);
    issues.push(...interp.issues);

    const scriptDir = packageRoot || path.dirname(path.resolve(String(scriptPath || process.cwd())));
    if (interp.kind === 'node') {
        for (const name of scanNodeExternals(text)) {
            const satisfied = nodeModuleSatisfied(name, scriptDir);
            dependencies.push({ name, source: 'import', satisfied });
            if (!satisfied) missing.push(name);
        }
        const pkgPath = findNearestPackageJson(scriptDir);
        if (pkgPath) {
            try {
                const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
                for (const name of Object.keys(pkg.dependencies || {})) {
                    const satisfied = nodeModuleSatisfied(name, path.dirname(pkgPath));
                    if (!dependencies.some(d => d.name === name)) {
                        dependencies.push({ name, source: 'package.json', satisfied });
                        if (!satisfied && !missing.includes(name)) missing.push(name);
                    }
                }
            } catch (err) {
                issues.push(`package.json не разобран: ${err.message}`);
            }
        }
    } else if (interp.kind === 'python') {
        for (const name of scanPythonImports(text)) {
            dependencies.push({ name, source: 'import', satisfied: null });
        }
        for (const reqName of ['requirements.txt']) {
            const reqPath = path.join(scriptDir, reqName);
            if (fs.existsSync(reqPath)) {
                try {
                    for (const line of fs.readFileSync(reqPath, 'utf8').split(/\r?\n/)) {
                        const name = line.trim().split(/[=<>!~\[]/)[0].trim();
                        if (name && !name.startsWith('#')) dependencies.push({ name, source: 'requirements.txt', satisfied: null });
                    }
                } catch (_) {}
            }
        }
        issues.push('удовлетворённость python-зависимостей без запуска интерпретатора — «требует проверки»');
    }

    // Наличие интерпретатора: реальный probe --version (не декларация).
    const probe = probeInterpreter || defaultProbeInterpreter;
    const probeResult = probe(interp);
    return {
        interpreter: {
            kind: interp.kind,
            command: interp.command,
            available: Boolean(probeResult.available),
            version: probeResult.version || null,
            probeError: probeResult.error || null
        },
        dependencies,
        missing,
        issues,
        ready: Boolean(probeResult.available) && missing.length === 0 && interp.kind !== 'unknown'
    };
}

function defaultProbeInterpreter(interp) {
    if (!interp.command || interp.kind === 'unknown') return { available: false, version: null, error: 'интерпретатор не определён' };
    if (interp.kind === 'node') return { available: true, version: process.version };
    if (interp.kind === 'binary') return { available: fs.existsSync(interp.command), version: null };
    if (interp.kind === 'shell' && process.platform === 'win32') return { available: false, version: null, error: 'shell-скрипты на Windows не исполняются этим runner' };
    try {
        const out = require('node:child_process').execFileSync(interp.command, ['--version'], { timeout: 5000, windowsHide: true });
        return { available: true, version: String(out).split(/\r?\n/)[0].trim() };
    } catch (err) {
        return { available: false, version: null, error: String(err && err.message || err).slice(0, 200) };
    }
}

function defaultSpawnScript({ command, args, cwd, env, timeoutMs, shell = false }) {
    return new Promise((resolve) => {
        let child;
        try {
            child = spawn(command, args || [], { cwd, env, windowsHide: true, shell: Boolean(shell) });
        } catch (err) {
            resolve({ exitCode: null, stdout: '', stderr: String(err && err.message || err), timedOut: false, spawnError: String(err && err.message || err) });
            return;
        }
        let stdout = '';
        let stderr = '';
        let timedOut = false;
        let settled = false;
        const timer = setTimeout(() => {
            timedOut = true;
            try {
                if (process.platform === 'win32') {
                    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
                } else {
                    child.kill('SIGKILL');
                }
            } catch (_) {}
        }, Math.max(1, timeoutMs));
        child.stdout.on('data', (d) => { stdout += d.toString('utf8'); });
        child.stderr.on('data', (d) => { stderr += d.toString('utf8'); });
        const finish = (r) => { if (!settled) { settled = true; clearTimeout(timer); resolve(r); } };
        child.on('error', (err) => finish({ exitCode: null, stdout, stderr, timedOut, spawnError: String(err && err.message || err) }));
        child.on('close', (code) => finish({ exitCode: code, stdout, stderr, timedOut, spawnError: null }));
    });
}

// Собственный исполнитель: реальный запуск с изолированным рабочим каталогом.
// workDir не задан → свежий временный каталог (скрипт не получает корень проекта).
async function runScriptIsolated({ scriptPath, args = [], timeoutMs = 30000, workDir = null, extraEnv = {}, env = null, runProcess = null, keepWorkDir = false } = {}) {
    const started = Date.now();
    const resolvedScript = path.resolve(String(scriptPath));
    if (!fs.existsSync(resolvedScript)) {
        return {
            ok: false, blocker: `скрипт не найден: ${resolvedScript}`,
            exitCode: null, stdout: '', stderr: '', timedOut: false, cwd: null, command: null, durationMs: Date.now() - started
        };
    }
    const content = fs.readFileSync(resolvedScript, 'utf8');
    const deps = checkScriptDependencies({ scriptPath: resolvedScript, content });
    if (!deps.ready) {
        return {
            ok: false,
            blocker: deps.interpreter.available
                ? `не хватает зависимостей: ${deps.missing.join(', ')}`
                : `интерпретатор "${deps.interpreter.kind}" недоступен (${deps.interpreter.probeError || 'probe не подтвердил'})`,
            deps,
            exitCode: null, stdout: '', stderr: '', timedOut: false, cwd: null, command: null, durationMs: Date.now() - started
        };
    }
    const interp = detectInterpreter(resolvedScript, content);
    let cwd = workDir ? path.resolve(workDir) : null;
    let createdTemp = false;
    if (!cwd) {
        cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-script-'));
        createdTemp = true;
    } else {
        fs.mkdirSync(cwd, { recursive: true });
    }
    const isWin = process.platform === 'win32';
    let command;
    let fullArgs;
    let useShell = false;
    if (interp.kind === 'binary') {
        command = resolvedScript;
        fullArgs = args.map(String);
    } else if (interp.kind === 'cmd') {
        // cmd /c «скрипт [аргументы]» — через shell: true (корректные кавычки).
        command = [resolvedScript, ...args.map(String)]
            .map(a => (/\s/.test(a) ? `"${a}"` : a))
            .join(' ');
        fullArgs = [];
        useShell = true;
    } else {
        command = interp.command;
        fullArgs = [resolvedScript, ...args.map(String)];
    }
    const run = runProcess || defaultSpawnScript;
    const proc = await run({
        command,
        args: fullArgs,
        cwd,
        env: env || buildScriptEnv(process.env, extraEnv),
        timeoutMs,
        shell: useShell
    });
    const result = {
        ok: !proc.timedOut && !proc.spawnError && proc.exitCode === 0,
        blocker: proc.timedOut ? `скрипт превысил тайм-аут (${timeoutMs} мс)` : (proc.spawnError || (proc.exitCode === 0 ? null : `exit code ${proc.exitCode}`)),
        deps,
        command: `${command} ${fullArgs.join(' ')}`,
        exitCode: proc.exitCode == null ? null : proc.exitCode,
        stdout: String(proc.stdout || ''),
        stderr: String(proc.stderr || ''),
        timedOut: Boolean(proc.timedOut),
        spawnError: proc.spawnError || null,
        cwd,
        isolated: createdTemp && !keepWorkDir ? true : Boolean(workDir),
        durationMs: Date.now() - started
    };
    if (createdTemp && !keepWorkDir) {
        try { fs.rmSync(cwd, { recursive: true, force: true }); result.cwd = `${cwd} (удалён)`; } catch (_) {}
    }
    return result;
}

module.exports = {
    ENV_ALLOWLIST,
    buildScriptEnv,
    detectInterpreter,
    scanNodeExternals,
    scanPythonImports,
    checkScriptDependencies,
    defaultProbeInterpreter,
    defaultSpawnScript,
    runScriptIsolated
};
