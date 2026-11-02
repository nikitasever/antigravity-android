'use strict';
// Этап 2 (EXTENSIONS_INTEGRATION_PLAN.md): архивный слой импорта пакетов.
// Минимальный ZIP-ридер/райтер (store + deflate через node:zlib) и обязательные
// проверки из плана: пути архива, размер, хэш, коллизии имён и наличие
// исполняемых компонентов. Никаких внешних зависимостей.
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const path = require('node:path');

const SIG_LOCAL = 0x04034b50;
const SIG_CENTRAL = 0x02014b50;
const SIG_EOCD = 0x06054b50;

const DEFAULT_LIMITS = {
    maxEntries: 4096,
    maxFileBytes: 16 * 1024 * 1024,
    maxTotalBytes: 64 * 1024 * 1024
};

function crc32(buf) {
    return zlib.crc32(buf) >>> 0;
}

function sha256Of(buf) {
    return crypto.createHash('sha256').update(buf).digest('hex');
}

// ---------------------------------------------------------------------------
// Чтение ZIP: центральный каталог (безопаснее local-заголовков), методы
// 0 (store) и 8 (deflate). Шифрование и остальные методы отвергаются явно.
// ---------------------------------------------------------------------------

function findEocd(buf) {
    const min = Math.max(0, buf.length - (65535 + 22));
    for (let i = buf.length - 22; i >= min; i--) {
        if (buf.readUInt32LE(i) === SIG_EOCD) return i;
    }
    return -1;
}

function readZipEntries(buf, limits = {}) {
    const lim = Object.assign({}, DEFAULT_LIMITS, limits);
    const issues = [];
    if (!Buffer.isBuffer(buf)) throw new Error('ожидается Buffer архива');
    if (buf.length < 22) throw new Error('архив слишком мал для ZIP');
    const eocd = findEocd(buf);
    if (eocd < 0) throw new Error('ZIP: сигнатура EOCD не найдена');
    const totalEntries = buf.readUInt16LE(eocd + 10);
    const cdSize = buf.readUInt32LE(eocd + 12);
    const cdOffset = buf.readUInt32LE(eocd + 16);
    if (totalEntries > lim.maxEntries) {
        throw new Error(`ZIP: слишком много записей (${totalEntries} > ${lim.maxEntries})`);
    }
    if (cdOffset + cdSize > buf.length) throw new Error('ZIP: центральный каталог выходит за пределы файла');
    const entries = [];
    let off = cdOffset;
    for (let i = 0; i < totalEntries; i++) {
        if (off + 46 > buf.length || buf.readUInt32LE(off) !== SIG_CENTRAL) {
            throw new Error('ZIP: повреждённая запись центрального каталога');
        }
        const flags = buf.readUInt16LE(off + 8);
        const method = buf.readUInt16LE(off + 10);
        const crc = buf.readUInt32LE(off + 16);
        const compressedSize = buf.readUInt32LE(off + 20);
        const uncompressedSize = buf.readUInt32LE(off + 24);
        const nameLen = buf.readUInt16LE(off + 28);
        const extraLen = buf.readUInt16LE(off + 30);
        const commentLen = buf.readUInt16LE(off + 32);
        const localOffset = buf.readUInt32LE(off + 42);
        const nameBytes = buf.slice(off + 46, off + 46 + nameLen);
        const name = nameBytes.toString('utf8');
        off += 46 + nameLen + extraLen + commentLen;

        if (flags & 0x0001) {
            issues.push(`${name}: запись зашифрована — не поддерживается`);
            continue;
        }
        if (method !== 0 && method !== 8) {
            issues.push(`${name}: метод сжатия ${method} не поддерживается (допустимы store и deflate)`);
            continue;
        }
        if (uncompressedSize > lim.maxFileBytes) {
            issues.push(`${name}: файл больше лимита (${uncompressedSize} > ${lim.maxFileBytes})`);
            continue;
        }
        if (localOffset + 30 > buf.length || buf.readUInt32LE(localOffset) !== SIG_LOCAL) {
            issues.push(`${name}: повреждён local-заголовок`);
            continue;
        }
        const lNameLen = buf.readUInt16LE(localOffset + 26);
        const lExtraLen = buf.readUInt16LE(localOffset + 28);
        const dataStart = localOffset + 30 + lNameLen + lExtraLen;
        const dataEnd = dataStart + compressedSize;
        if (dataEnd > buf.length) {
            issues.push(`${name}: данные выходят за пределы архива`);
            continue;
        }
        const raw = buf.slice(dataStart, dataEnd);
        let data;
        try {
            data = method === 0 ? Buffer.from(raw) : zlib.inflateRawSync(raw);
        } catch (err) {
            issues.push(`${name}: ошибка распаковки: ${err.message}`);
            continue;
        }
        if (data.length !== uncompressedSize) {
            issues.push(`${name}: размер после распаковки не совпал (${data.length} ≠ ${uncompressedSize})`);
            continue;
        }
        const actualCrc = crc32(data);
        if (actualCrc !== crc) {
            issues.push(`${name}: контрольная сумма CRC32 не совпала`);
            continue;
        }
        entries.push({ name, method, crc32: crc, size: data.length, compressedSize, data, sha256: sha256Of(data) });
    }
    return { entries, issues };
}

// ---------------------------------------------------------------------------
// Запись ZIP (store для пустых, deflate для остальных).
// ---------------------------------------------------------------------------

function writeZipEntries(items) {
    const locals = [];
    const centrals = [];
    let offset = 0;
    for (const item of items) {
        const nameBuf = Buffer.from(String(item.name), 'utf8');
        const data = Buffer.isBuffer(item.data) ? item.data : Buffer.from(String(item.data == null ? '' : item.data), 'utf8');
        const crc = crc32(data);
        const deflated = data.length > 0 ? zlib.deflateRawSync(data) : Buffer.alloc(0);
        const useDeflate = deflated.length > 0 && deflated.length < data.length;
        const stored = useDeflate ? deflated : data;
        const method = useDeflate ? 8 : 0;

        const local = Buffer.alloc(30);
        local.writeUInt32LE(SIG_LOCAL, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(0, 6);
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(0, 10);
        local.writeUInt16LE(0, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(stored.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(0, 28);
        locals.push(local, nameBuf, stored);

        const central = Buffer.alloc(46);
        central.writeUInt32LE(SIG_CENTRAL, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(0, 8);
        central.writeUInt16LE(method, 10);
        central.writeUInt16LE(0, 12);
        central.writeUInt16LE(0, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(stored.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(nameBuf.length, 28);
        central.writeUInt16LE(0, 30);
        central.writeUInt16LE(0, 32);
        central.writeUInt16LE(0, 34);
        central.writeUInt16LE(0, 36);
        central.writeUInt32LE(0, 38);
        central.writeUInt32LE(offset, 42);
        centrals.push(central, nameBuf);
        offset += local.length + nameBuf.length + stored.length;
    }
    const cdBuf = Buffer.concat(centrals);
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(SIG_EOCD, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(items.length, 8);
    eocd.writeUInt16LE(items.length, 10);
    eocd.writeUInt32LE(cdBuf.length, 12);
    eocd.writeUInt32LE(offset, 16);
    eocd.writeUInt16LE(0, 20);
    return Buffer.concat([...locals, cdBuf, eocd]);
}

// ---------------------------------------------------------------------------
// Проверки безопасности записей архива (план: «Проверять пути архива, размер,
// хэш, коллизии имён и наличие исполняемых компонентов»).
// ---------------------------------------------------------------------------

const EXECUTABLE_EXTENSIONS = new Set([
    '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd', '.py', '.pyw', '.js', '.mjs', '.cjs',
    '.exe', '.dll', '.so', '.dylib', '.jar', '.apk', '.msi', '.com', '.scr', '.rb', '.pl', '.php', '.lua'
]);

function inspectEntryName(name) {
    const issues = [];
    const raw = String(name);
    if (!raw.trim()) issues.push('пустое имя записи');
    if (raw.includes('\0')) issues.push('NUL в имени записи');
    if (/^[a-zA-Z]:[\\/]/.test(raw) || raw.startsWith('\\\\') || raw.startsWith('/')) {
        issues.push(`абсолютный путь запрещён: ${raw}`);
    }
    // QA/Security, Medium #4: Windows drive-relative путь "C:foo" (без слэша
    // после двоеточия) тоже запрещён — он разрешается относительно текущего
    // диска процесса и обходит проверку абсолютных путей.
    if (/^[a-zA-Z]:/.test(raw)) {
        issues.push(`drive-relative путь запрещён: ${raw}`);
    }
    const norm = raw.split('\\').join('/');
    if (raw.includes('\\')) issues.push(`обратный слэш в пути: ${raw}`);
    for (const seg of norm.split('/')) {
        if (seg === '..') { issues.push(`обход каталога (..) запрещён: ${raw}`); break; }
    }
    return { norm, issues };
}

function isExecutableEntry(norm, data) {
    const ext = path.posix.extname(norm).toLowerCase();
    if (EXECUTABLE_EXTENSIONS.has(ext)) return { executable: true, reason: `расширение ${ext}` };
    const head = data && data.length >= 2 ? data.slice(0, 2).toString('utf8') : '';
    if (head === '#!') return { executable: true, reason: 'shebang' };
    return { executable: false, reason: '' };
}

function verifyArchiveEntries(entries, limits = {}) {
    const lim = Object.assign({}, DEFAULT_LIMITS, limits);
    const pathIssues = [];
    const safeEntries = [];
    const nameCollisions = [];
    const executableComponents = [];
    const seenLower = new Map();
    let totalBytes = 0;

    if (entries.length > lim.maxEntries) {
        pathIssues.push(`слишком много записей: ${entries.length} > ${lim.maxEntries}`);
    }
    for (const entry of entries) {
        const { norm, issues } = inspectEntryName(entry.name);
        pathIssues.push(...issues.map(i => `${entry.name}: ${i}`));
        if (issues.length > 0) continue;
        if (entry.size > lim.maxFileBytes) {
            pathIssues.push(`${entry.name}: превышен лимит размера файла (${entry.size})`);
            continue;
        }
        totalBytes += entry.size;
        if (totalBytes > lim.maxTotalBytes) {
            pathIssues.push(`${entry.name}: превышен суммарный лимит размера (${totalBytes})`);
            continue;
        }
        const lower = norm.toLowerCase();
        if (seenLower.has(lower)) {
            nameCollisions.push({ first: seenLower.get(lower), second: norm, reason: 'имена совпадают без учёта регистра' });
        } else {
            seenLower.set(lower, norm);
        }
        const execInfo = isExecutableEntry(norm, entry.data);
        if (execInfo.executable) {
            executableComponents.push({ path: norm, reason: execInfo.reason, sha256: entry.sha256 });
        }
        safeEntries.push({
            path: norm,
            size: entry.size,
            sha256: entry.sha256,
            executable: execInfo.executable,
            executableReason: execInfo.reason,
            data: entry.data
        });
    }
    return {
        entries: safeEntries,
        pathIssues,
        nameCollisions,
        executableComponents,
        totalBytes,
        safe: pathIssues.length === 0 && nameCollisions.length === 0
    };
}

module.exports = {
    readZipEntries,
    writeZipEntries,
    verifyArchiveEntries,
    inspectEntryName,
    isExecutableEntry,
    crc32,
    sha256Of,
    DEFAULT_LIMITS
};
