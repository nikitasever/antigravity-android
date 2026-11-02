'use strict';
// Контрольный пакет 2 (Этап 7): скрипт навыка. Зависимость (control-dep)
// установлена в node_modules пакета и объявленная через require — проверка
// зависимостей (script-runner) обязана увидеть её ДО запуска; исполнение идёт
// в изолированном рабочем каталоге.
const format = require('./lib/format.js');
const dep = require('control-dep');

const arg = process.argv[2] || 'без аргумента';
process.stdout.write(JSON.stringify({
    marker: 'CONTROL-SCRIPT-SKILL-OK',
    formatted: format(`отчёт: ${arg}`),
    depResult: dep(arg),
    cwd: process.cwd()
}) + '\n');
