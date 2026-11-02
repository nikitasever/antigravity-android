#!/usr/bin/env node
'use strict';
// Контрольный скрипт составного плагина: внешняя зависимость (ws) и локальный
// модуль объявлены через require — проверка зависимостей идёт ДО запуска.
const format = require('./lib/format.js');
const ws = require('ws');

process.stdout.write(JSON.stringify({
    marker: 'CONTROL-COMPOSITE-SCRIPT-OK',
    formatted: format('составной плагин'),
    externalDepResolved: Boolean(ws)
}) + '\n');
