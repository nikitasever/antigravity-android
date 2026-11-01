'use strict';
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const exec = promisify(execFile);

// Preparation layer only: callers must explicitly select a clean Git project.
// Worktrees separate files; they are not a security sandbox for an agent process.
class AgentWorkspace {
    constructor(repository, storage, { keep = 10 } = {}) {
        this.repository = path.resolve(repository);
        this.storage = path.resolve(storage);
        this.keep = keep;
    }
    async git(args, cwd = this.repository) {
        return (await exec('git', args, { cwd, windowsHide: true, maxBuffer: 8 * 1024 * 1024 })).stdout;
    }
    async create() {
        const root = path.resolve((await this.git(['rev-parse', '--show-toplevel'])).trim());
        if (root !== this.repository) throw new Error('Select the repository root');
        const relative = path.relative(root, this.storage);
        if (!relative || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative))) {
            throw new Error('Agent storage must be outside the project');
        }
        if ((await this.git(['status', '--porcelain', '--untracked-files=all'])).trim()) {
            throw new Error('Commit or preserve project changes before creating an agent workspace');
        }
        const base = (await this.git(['rev-parse', 'HEAD'])).trim();
        const id = randomUUID();
        const directory = path.join(this.storage, id);
        await fs.mkdir(this.storage, { recursive: true });
        await this.git(['worktree', 'add', '--detach', directory, base]);
        await this.prune();
        return { id, directory, base };
    }
    // Age does not establish that a proposal is disposable. Until persisted
    // ownership/discard decisions exist, automatic pruning must preserve copies,
    // including dirty or locked worktrees and proposals awaiting review.
    async prune() { return []; }
    async review(workspace) {
        // Resolve through Git's registered worktrees, never trust a client-provided path.
        if (!/^[0-9a-f-]{36}$/.test(workspace.id)) throw new Error('Invalid workspace id');
        const directory = path.join(this.storage, workspace.id);
        const entries = (await this.git(['worktree', 'list', '--porcelain', '-z'])).split('\0');
        if (!entries.includes('worktree ' + directory.replaceAll('\\', '/')) &&
            !entries.includes('worktree ' + directory)) throw new Error('Unknown agent workspace');
        if (!/^[0-9a-f]{40,64}$/.test(workspace.base)) throw new Error('Invalid base revision');
        const diff = await this.git(['diff', '--no-ext-diff', '--no-textconv', workspace.base, '--'], directory);
        const untracked = (await this.git(['ls-files', '--others', '--exclude-standard', '-z'], directory))
            .split('\0').filter(Boolean);
        const current = (await this.git(['rev-parse', 'HEAD'])).trim();
        const dirty = Boolean((await this.git(['status', '--porcelain', '--untracked-files=all'])).trim());
        const newFiles = [];
        let remaining = 1024 * 1024;
        for (const name of untracked.slice(0, 100)) {
            const filename = path.resolve(directory, name);
            const rel = path.relative(directory, filename);
            if (rel.startsWith('..') || path.isAbsolute(rel)) throw new Error('Invalid new file path');
            const info = await fs.lstat(filename);
            const real = await fs.realpath(filename);
            const realRel = path.relative(await fs.realpath(directory), real);
            if (!info.isFile() || realRel.startsWith('..') || path.isAbsolute(realRel)) {
                newFiles.push({ name, omitted: 'Not a regular file inside the workspace' }); continue;
            }
            if (info.size > Math.min(256 * 1024, remaining)) {
                newFiles.push({ name, omitted: 'File exceeds preview limit' }); continue;
            }
            const bytes = await fs.readFile(filename);
            remaining -= bytes.length;
            newFiles.push(bytes.includes(0) ? { name, omitted: 'Binary file' } : { name, content: bytes.toString('utf8') });
        }
        return { base: workspace.base, diff, untracked, newFiles, sourceChanged: dirty || current !== workspace.base };
    }
}
module.exports = { AgentWorkspace };
