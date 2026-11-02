#!/usr/bin/env node
'use strict';
// QA/Security-аудит, High #2: родительский процесс порождает внучатый
// процесс с унаследованным stdout и сам спит. При таймауте убийство дерева
// должно завершить промис гарантированно, даже если внучатий держит pipe.
const { spawn } = require('node:child_process');

spawn(process.execPath, ['-e', 'setTimeout(function(){}, 60000)'], { stdio: 'inherit' });
setTimeout(function () {}, 60000);
