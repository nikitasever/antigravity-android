'use strict';
// Этап 4 (EXTENSIONS_INTEGRATION_PLAN.md): подключение реестра к моделям и
// дочерним задачам. Проверяется: экономия (метаданные → инструкции только
// выбранного навыка), выбор навыка, запрет доступа, реальные схемы живого
// tools/list, несмешение одноимённых инструментов разных серверов, сторона
// исполнения (ПК/смартфон), цикл инструментов с возвратом результатов и Stop,
// поднабор прав дочерних задач без доверия к аннотациям MCP.

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');

const runtime = require('../lib/extension-runtime');

function makeSkillFixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stage4-skills-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const write = (dir, file, content) => {
        fs.mkdirSync(path.join(root, dir), { recursive: true });
        fs.writeFileSync(path.join(root, dir, file), content, 'utf8');
    };
    write('skill-a', 'SKILL.md',
        '---\nname: skill-a\ndescription: "Навык А для теста"\n---\n\nИНСТРУКЦИЯ-А: делай вещь А. См. references/deep.md.\n');
    write('skill-a/references', 'deep.md', 'ГЛУБОКИЙ-РЕСУРС-А');
    write('skill-b', 'SKILL.md',
        '---\nname: skill-b\ndescription: "Навык Б для теста"\n---\n\nИНСТРУКЦИЯ-Б: делай вещь Б.\n');
    write('skill-off', 'SKILL.md.disabled',
        '---\nname: skill-off\ndescription: "Отключённый навык"\n---\n\nИНСТРУКЦИЯ-ОТКЛ.\n');
    const roots = [{ dir: root, source: 'test' }];
    return { root, roots };
}

function makeMcpServersFixture() {
    // Два сервера с одноимённым инструментом whoami — результаты различаются.
    return [
        {
            id: 'srv-a',
            tools: [{
                name: 'whoami',
                description: 'кто я на сервере А',
                inputSchema: { type: 'object', properties: {} },
                annotations: { readOnlyHint: true }
            }]
        },
        {
            id: 'srv-b',
            tools: [{
                name: 'whoami',
                description: 'кто я на сервере Б',
                inputSchema: { type: 'object', properties: {} },
                annotations: { readOnlyHint: true, destructiveHint: true }
            }]
        }
    ];
}

// ---------------------------------------------------------------------------
// Навыки: метаданные, выбор, загрузка только выбранного, запрет доступа
// ---------------------------------------------------------------------------

test('каталог навыков — только метаданные без инструкций', (t) => {
    const { roots } = makeSkillFixture(t);
    const catalog = runtime.buildSkillCatalog(roots);
    const names = catalog.map(s => s.name).sort();
    assert.deepEqual(names, ['skill-a', 'skill-b', 'skill-off']);
    const a = catalog.find(s => s.name === 'skill-a');
    assert.equal(a.enabled, true);
    assert.equal(a.description, 'Навык А для теста');
    const off = catalog.find(s => s.name === 'skill-off');
    assert.equal(off.enabled, false, 'отключённый навык виден в каталоге как отключённый');
    const dumped = JSON.stringify(catalog);
    assert.ok(!dumped.includes('ИНСТРУКЦИЯ'), 'полные инструкции не попадают в каталог');
});

test('выбор навыка по /имя; неизвестный слэш не считается навыком', (t) => {
    const { roots } = makeSkillFixture(t);
    const catalog = runtime.buildSkillCatalog(roots);
    const hit = runtime.detectSkillInvocation('/skill-a сделай вещь', catalog);
    assert.equal(hit.skillId, 'skill-a');
    assert.equal(hit.restPrompt, 'сделай вещь');
    const unknown = runtime.detectSkillInvocation('/no-such-skill привет', catalog);
    assert.equal(unknown.skillId, null, 'неизвестный слэш остаётся текстом запроса');
    assert.equal(unknown.restPrompt, '/no-such-skill привет');
    const plain = runtime.detectSkillInvocation('обычный вопрос', catalog);
    assert.equal(plain.skillId, null);
});

test('инструкции загружаются только выбранного навыка', (t) => {
    const { roots } = makeSkillFixture(t);
    const sel = runtime.loadSelectedSkill('skill-a', { skillRoots: roots });
    assert.equal(sel.status, 'ok');
    assert.match(sel.skill.instructions, /ИНСТРУКЦИЯ-А/);
    assert.ok(!sel.skill.instructions.includes('ИНСТРУКЦИЯ-Б'),
        'инструкции другого навыка не загружены');
    assert.ok(sel.skill.resourcePaths.some(p => p.includes('deep.md')),
        'ресурсы перечислены путями для загрузки по необходимости');
    const block = runtime.composeSkillInstructionBlock(sel.skill);
    assert.match(block, /<SKILL_INSTRUCTION name="skill-a">/);
    assert.ok(!block.includes('ИНСТРУКЦИЯ-Б'));
});

test('отключённый и отсутствующий навыки — явный отказ без инструкций', (t) => {
    const { roots } = makeSkillFixture(t);
    const off = runtime.loadSelectedSkill('skill-off', { skillRoots: roots });
    assert.equal(off.status, 'disabled');
    assert.equal(off.skill, null);
    assert.match(off.refusal, /запрещён|отключён/i, 'запрет доступа действует явно');
    const missing = runtime.loadSelectedSkill('ghost', { skillRoots: roots });
    assert.equal(missing.status, 'not_found');
    assert.ok(missing.refusal.includes('не найден'));
});

// ---------------------------------------------------------------------------
// Инструменты: реальные схемы, одноимённые не смешиваются, стороны
// ---------------------------------------------------------------------------

test('одноимённые инструменты разных серверов не взаимозаменяемы', () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const refs = catalog.map(t => t.ref).sort();
    assert.deepEqual(refs, ['srv-a:whoami', 'srv-b:whoami'], 'две разные ссылки на одно имя');
    const { tools, nameToRef } = runtime.toOpenAiToolSchemas(catalog);
    assert.equal(tools.length, 2);
    const names = tools.map(t => t.function.name);
    assert.equal(new Set(names).size, 2, 'имена функций различаются');
    // Обратное соответствие ведёт на правильный сервер.
    for (const tool of tools) {
        const ref = nameToRef.get(tool.function.name);
        assert.ok(['srv-a:whoami', 'srv-b:whoami'].includes(ref));
        assert.match(tool.function.description, /\[srv-[ab]\]/);
    }
    // Реальная схема живого tools/list дошла до параметров.
    assert.equal(tools[0].function.parameters.type, 'object');
});

test('разрешение доступа только по явному списку; аннотации MCP прав не дают', () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const { allowed, denied } = runtime.filterAllowedTools(catalog, { allowRefs: ['srv-a:whoami'] });
    assert.deepEqual(allowed.map(t => t.ref), ['srv-a:whoami']);
    assert.deepEqual(denied.map(t => t.ref), ['srv-b:whoami']);
    // У srv-b:whoami аннотация readOnlyHint=true — она НЕ основание для доступа.
    const deniedB = denied.find(t => t.ref === 'srv-b:whoami');
    assert.equal(deniedB.annotations.readOnlyHint, true);
    assert.equal(deniedB.denyReason, 'not-in-allowlist');
});

test('телефонные инструменты — сторона смартфона, в PC-набор не попадают', () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture(), includePhoneTools: true });
    const phone = catalog.filter(t => t.side === 'phone');
    assert.ok(phone.length >= 9, 'все phone_action инструменты в каталоге');
    const { allowed } = runtime.filterAllowedTools(catalog, { allowAllPc: true });
    assert.ok(allowed.every(t => t.side === 'pc'), 'телефонные инструменты не в наборе исполнения на ПК');
    const { tools } = runtime.toOpenAiToolSchemas(catalog.filter(t => t.side === 'phone'));
    assert.equal(tools.length, 0, 'телефонные инструменты не уходят как function-вызовы (иначе исполнение ушло бы на ПК)');
});

test('вызов инструмента идёт строго на свой сервер; чужой/чужой стороны — отказ', async () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const calls = [];
    const callPcTool = async (serverId, toolName, args) => {
        calls.push({ serverId, toolName, args });
        return { ok: true, result: { content: [{ type: 'text', text: `ответ-${serverId}` }] } };
    };
    const okB = await runtime.executeBrokerToolCall('srv-b:whoami', {}, { catalog, callPcTool });
    assert.equal(okB.ok, true);
    assert.match(okB.result.content[0].text, /ответ-srv-b/);
    assert.equal(calls[0].serverId, 'srv-b', 'вызов ушёл именно на сервер Б');

    const missing = await runtime.executeBrokerToolCall('srv-c:whoami', {}, { catalog, callPcTool });
    assert.equal(missing.ok, false);
    assert.equal(missing.code, 'tool_not_found');
    assert.match(missing.error, /не взаимозаменяемы/);

    const wrongSide = await runtime.executeBrokerToolCall('phone:read_file', { path: '/x' }, {
        catalog: runtime.buildToolCatalog({ mcpServers: [], includePhoneTools: true }),
        callPcTool
    });
    assert.equal(wrongSide.ok, false);
    assert.equal(wrongSide.code, 'wrong_side', 'запрос телефона не перенаправляется на ПК');

    const denied = await runtime.executeBrokerToolCall('srv-a:whoami', {}, {
        catalog,
        callPcTool,
        allowedRefs: new Set(['srv-b:whoami'])
    });
    assert.equal(denied.ok, false);
    assert.equal(denied.code, 'denied', 'запрет доступа действует и на этапе вызова');
    assert.equal(calls.length, 1, 'запрещённый вызов не дошёл до исполнителя');
});

// ---------------------------------------------------------------------------
// Цикл инструментов: результаты возвращаются в тот же цикл; Stop
// ---------------------------------------------------------------------------

test('цикл инструментов: схема → вызов → результат в тот же цикл → ответ', async () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const { allowed } = runtime.filterAllowedTools(catalog, { allowRefs: ['srv-a:whoami'] });
    const { tools, nameToRef } = runtime.toOpenAiToolSchemas(allowed);
    assert.equal(tools.length, 1);
    const fnName = tools[0].function.name;

    const received = [];
    let round = 0;
    const chat = async (messages, toolSchemas) => {
        received.push({ messages: messages.map(m => ({ ...m })), tools: toolSchemas });
        round++;
        if (round === 1) {
            // Модель просит инструмент по реальной схеме из tools.
            assert.equal(toolSchemas.length, 1);
            return {
                message: {
                    role: 'assistant',
                    content: '',
                    tool_calls: [{ id: 'call_1', type: 'function', function: { name: fnName, arguments: '{}' } }]
                }
            };
        }
        // Второй раунд: финальный ответ опирается на результат инструмента.
        const toolMsg = messages.find(m => m.role === 'tool');
        assert.ok(toolMsg && toolMsg.content.includes('ответ-srv-a'),
            'результат инструмента доступен модели');
        return { message: { role: 'assistant', content: 'ИТОГ: ' + toolMsg.content } };
    };
    const executed = [];
    const execute = async (ref, args, { signal } = {}) => {
        executed.push({ ref, args, aborted: Boolean(signal && signal.aborted) });
        return runtime.executeBrokerToolCall(ref, args, {
            catalog,
            allowedRefs: new Set(allowed.map(t => t.ref)),
            callPcTool: async (serverId) => ({ ok: true, result: { content: [{ type: 'text', text: `ответ-${serverId}` }] } }),
            signal
        });
    };

    const result = await runtime.runToolCallLoop({
        messages: [{ role: 'user', content: 'кто я?' }],
        tools, nameToRef, chat, execute, maxRounds: 4
    });
    assert.equal(result.cancelled, false);
    assert.match(result.finalMessage.content, /ИТОГ: ответ-srv-a/, 'ответ опирается на результат');
    assert.equal(executed.length, 1);
    assert.equal(executed[0].ref, 'srv-a:whoami');
    assert.equal(result.toolCalls.length, 1);
    assert.equal(result.toolCalls[0].ok, true);
    // Второй запрос содержит и assistant с tool_calls, и tool-результат.
    assert.ok(received[1].messages.some(m => m.role === 'tool'));
});

test('Stop отменяет цикл инструментов', async () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const { allowed } = runtime.filterAllowedTools(catalog, { allowAllPc: true });
    const { tools, nameToRef } = runtime.toOpenAiToolSchemas(allowed);
    const ac = new AbortController();
    let round = 0;
    const chat = async () => {
        round++;
        return {
            message: {
                role: 'assistant',
                content: '',
                tool_calls: [{ id: 'call_x', type: 'function', function: { name: tools[0].function.name, arguments: '{}' } }]
            }
        };
    };
    const execute = async () => {
        ac.abort(); // Stop посреди вызова
        return { ok: false, code: 'cancelled', error: 'отменено' };
    };
    const result = await runtime.runToolCallLoop({
        messages: [{ role: 'user', content: 'x' }],
        tools, nameToRef, chat, execute, maxRounds: 5, signal: ac.signal
    });
    assert.equal(result.cancelled, true, 'Stop останавливает цикл');
    assert.equal(round, 1, 'второй запрос модели не отправлен');
});

test('исчерпание раундов честно помечается, без выдуманного ответа', async () => {
    const catalog = runtime.buildToolCatalog({ mcpServers: makeMcpServersFixture() });
    const { allowed } = runtime.filterAllowedTools(catalog, { allowRefs: ['srv-a:whoami'] });
    const { tools, nameToRef } = runtime.toOpenAiToolSchemas(allowed);
    const chat = async () => ({
        message: {
            role: 'assistant', content: '',
            tool_calls: [{ id: 'c', type: 'function', function: { name: tools[0].function.name, arguments: '{}' } }]
        }
    });
    const execute = async () => ({ ok: true, result: { content: [{ type: 'text', text: 'ok' }] } });
    const result = await runtime.runToolCallLoop({
        messages: [{ role: 'user', content: 'x' }],
        tools, nameToRef, chat, execute, maxRounds: 2
    });
    assert.equal(result.exhausted, true);
    assert.equal(result.finalMessage, null);
    assert.equal(result.rounds, 2);
});

// ---------------------------------------------------------------------------
// Дочерние задачи: поднабор прав, аннотации не дают прав
// ---------------------------------------------------------------------------

test('дочерняя задача получает только нужный поднабор прав', () => {
    const block = runtime.buildChildTaskPermissionBlock({
        allowedRefs: ['srv-a:whoami'],
        allowedServers: ['srv-a']
    });
    assert.match(block, /srv-a:whoami/);
    assert.match(block, /MCP-серверы \(только эти\): srv-a/);
    assert.ok(!block.includes('srv-b'), 'вне поднабора — ничего');
    assert.match(block, /НЕ дают прав/, 'аннотации MCP не являются основанием доступа');

    const empty = runtime.buildChildTaskPermissionBlock({ allowedRefs: [] });
    assert.match(empty, /разрешённых инструментов НЕТ/i, 'пустой поднабор — явный запрет');
});

// ---------------------------------------------------------------------------
// Интеграция с адаптером OpenRouter: реальный executeOpenRouterStreamingTurn
// из server.js (vm-извлечение, как в chat-integrity) с управляемым HTTP-слоем.
// ---------------------------------------------------------------------------

test('адаптер OpenRouter: реальные схемы → вызов инструмента → ответ из результата', async () => {
    const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
    const start = source.indexOf('async function executeOpenRouterStreamingTurn(');
    const end = source.indexOf('function loadAiPermissionsConfig(', start);
    assert.ok(start > 0 && end > start, 'executeOpenRouterStreamingTurn найден');

    // Реальный набор инструментов живого tools/list через модуль Этапа 4.
    const catalog = runtime.buildToolCatalog({
        mcpServers: [{ id: 'srvX', tools: [{ name: 'whoami', description: 'кто я', inputSchema: { type: 'object', properties: {} } }] }]
    });
    const { allowed } = runtime.filterAllowedTools(catalog, { allowAllPc: true });
    const { tools, nameToRef } = runtime.toOpenAiToolSchemas(allowed);
    assert.equal(tools.length, 1);

    const capturedBodies = [];
    let callIdx = 0;
    const toolEvents = [];
    const mkResp = (obj) => {
        const handlers = {};
        const bodyStr = JSON.stringify(obj);
        setImmediate(() => {
            if (handlers.data) handlers.data(Buffer.from(bodyStr));
            if (handlers.end) handlers.end();
        });
        return { statusCode: 200, on: (ev, fn) => { handlers[ev] = fn; } };
    };
    const fakeHttps = {
        request: (url, opts, cb) => {
            const r = {
                on: () => r,
                destroy: () => {},
                write: (s) => { capturedBodies.push(JSON.parse(s)); },
                end: () => {
                    callIdx += 1;
                    if (callIdx === 1) {
                        cb(mkResp({
                            choices: [{ message: { role: 'assistant', content: '', tool_calls: [{
                                id: 'c1', type: 'function',
                                function: { name: tools[0].function.name, arguments: '{}' }
                            }] } }],
                            usage: { completion_tokens: 10 }
                        }));
                    } else {
                        cb(mkResp({
                            choices: [{ message: { role: 'assistant', content: 'ИТОГ: ответ-srvX' } }],
                            usage: { completion_tokens: 20 }
                        }));
                    }
                }
            };
            return r;
        }
    };

    const context = {
        require, Date, AbortController, Math, JSON,
        INSTANCE_NAME: 'test',
        turnCancellations: new Map(),
        liveOpenRouterModelsMemoryCache: [],
        loadOpenRouterConfig: () => ({ apiKey: 'test-key-1234567890' }),
        classifyOpenRouterModel: () => ({}),
        recordActiveTurnSnapshot: () => {},
        loadAgentSystemPrompt: () => 'СИСТЕМА',
        extractThinkingAndCleanReply: (t) => ({ thinking: '', reply: t }),
        extractPhoneActionsFromText: () => [],
        ensureTranscriptUpdated: () => {},
        formatToolSummary: (n) => n,
        extensionRuntime: runtime,
        buildOpenRouterToolSet: async () => ({ tools, nameToRef, catalog, allowedRefs: new Set(allowed.map(t => t.ref)) }),
        callMcpToolViaRegistry: async (serverId, toolName, args) => ({
            ok: true,
            result: { content: [{ type: 'text', text: `ответ-${serverId}` }], isError: false }
        }),
        https: fakeHttps
    };
    const execute = vm.runInNewContext(
        `(${source.slice(start, source.lastIndexOf('\n}', end) + 2).trim()})`,
        context
    );

    const events = [];
    const result = await execute(
        { turnId: 't4', model: 'vendor/model-x', cleanUserPrompt: 'кто я на сервере?', conversationId: 'conv-1' },
        (e) => {
            events.push(e);
            if (e.type === 'tool') toolEvents.push(e);
        }
    );

    // Реальные схемы ушли в первый запрос.
    assert.equal(capturedBodies.length, 2, 'цикл: запрос → вызов → повторный запрос');
    assert.equal(capturedBodies[0].tools.length, 1);
    assert.equal(capturedBodies[0].tools[0].function.name, tools[0].function.name);
    // Результат инструмента вернулся в тот же цикл.
    const toolMsg = capturedBodies[1].messages.find(m => m.role === 'tool');
    assert.ok(toolMsg, 'сообщение role:tool присутствует во втором запросе');
    assert.match(toolMsg.content, /ответ-srvX/);
    // Ответ опирается на результат; вызов зафиксирован в done.
    assert.equal(result.ok, true);
    assert.match(result.reply, /ИТОГ: ответ-srvX/);
    assert.equal(result.toolCalls.length, 1);
    assert.equal(result.toolCalls[0].name, 'srvX:whoami');
    assert.equal(toolEvents.length, 1);
    assert.equal(toolEvents[0].status, 'COMPLETED');
    assert.equal(context.turnCancellations.size, 0, 'turnCancellations очищен после хода');
});
