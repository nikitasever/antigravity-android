'use strict';
// Explicit repair of the two local packages and staging copies. Does not enable
// a plugin or change user permissions/config; canonical sources are versioned.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const ROOT = path.resolve(__dirname, '../..');
function syncLocalPlugins({ home = os.homedir(), staging = path.join(ROOT, 'uploads/plugin-staging') } = {}) {
    for (const name of ['antigravity-workspace-guard', 'kotlin-compose-inspector']) {
        const source = path.join(ROOT, 'extensions/plugins', name);
        for (const target of [path.join(home, '.gemini/config/plugins', name), path.join(staging, name)]) {
            let disabled;
            const manifestPath = path.join(target, 'plugin.json');
            if (fs.existsSync(manifestPath)) {
                const previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
                if (typeof previous.disabled === 'boolean') disabled = previous.disabled;
            }
            fs.mkdirSync(target, { recursive: true });
            fs.cpSync(source, target, { recursive: true, dereference: false });
            if (disabled !== undefined) {
                const updated = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
                updated.disabled = disabled;
                fs.writeFileSync(manifestPath, JSON.stringify(updated, null, 2) + '\n');
            }
        }
    }
}
if (require.main === module) { syncLocalPlugins(); console.log('Repaired two plugins and staging copies; enabled states unchanged.'); }
module.exports = { syncLocalPlugins };
