'use strict';
// Локальный модуль контрольного пакета 5 (не внешняя зависимость).
module.exports = function format(text) {
    return `[control] ${String(text)}`;
};
