# Сверка кода референсов Claude и ChatGPT с реализацией Antigravity

Дата: 3 ноября 2026. Правило данных: факты — только из локальных артефактов
(APK, декомпиляция, строки ресурсов, таблицы классов, код проекта); где данных
нет — «требует проверки»/«не записано». Декомпиляция (JADX) — восстановленный
код, не исходники; имена классов обфусцированы.

## 1. Входные артефакты

| Артефакт | Идентичность | Статус |
|---|---|---|
| Claude 1.261029.20 (base APK, 55 МБ) | sha256 `9d3c3459b3b64aa7043acc5a568615cd2064b632baa8d5b5ece18d4f7d888ad2` | сверен с `REFERENCE_EVIDENCE.md` |
| ChatGPT 1.2026.293 (base.apk, 86 МБ) | sha256 `53d6bb925d7185292ca53cb644deafe7ce9b786df8519424fa37a310c5952297` | сверен с `REFERENCE_EVIDENCE.md` |
| Полная декомпиляция (JADX 1.5.6, `--no-res`) | `~/.cache/codex-tools/reference-analysis/full-claude`, `full-chatgpt` | **частичная**: Claude 20521/33486 классов (61%), ChatGPT 28962/52213 (55%) — обе упёрлись в память JVM; extension-пакеты в обеих частях **присутствуют и проверены поимённо** (§2) |
| Таблицы классов | `scratch/targets/claude_classes.txt`, `chatgpt_classes.txt` | полные |
| Строки ресурсов/DEX | `claude-resources.txt`, `claude_resources/res/values/strings.xml`, `chatgpt_base_strings.txt`, `chatgpt_ru_strings.txt` | полные срезы строк |
| Ранее (1 ноября) | частичные декомпилирования (`claude-rename.java`, `claude-bottomsheet/*`, `claude-dispatch-hint.java`), живые проверки claude.ai web (`CLAUDE_WEB_REFERENCE_CHECK.md`), живой старт Claude APK (welcome-экран) | см. `REFERENCE_EVIDENCE.md` |

## 2. Кодовая карта поверхностей расширений

### Claude (`com.anthropic.claude.*`, декомпиляция)

| Поверхность | Классы-доказательства |
|---|---|
| Каталог/подключения | `SettingsAppScreen$ConnectorDirectory`, `$ConnectorsScreen`, `$ConnectorDirectoryDetail`, `$ConnectDirectoryEntryWebScreen`, `$AddCustomConnectorWebScreen`, `$ConnectorServerSettingsScreen(String, String)`; `ChatOptionsBottomSheetDestination$ConnectorDirectory/$ConnectorDirectoryDetail/$Connectors` |
| Инструменты MCP | `ChatOptionsBottomSheetDestination$McpServerTools(String serverId)`, `$McpToolPermission(String serverId, String toolName)`, `$McpSetAllToolsPermission(String serverId)`, `$McpPromptTemplate(String, String)`; `SettingsAppScreen$McpServerToolsScreen(String serverId)` |
| Навыки | `ChatOptionsBottomSheetDestination$SelectSkill`, `$SkillDetail(String)`, `$SkillEdit(String)`; `ChatScreenModalBottomSheetDestination$ViewSkillInstructions` |
| MCP-авторизация | `connector/auth/McpAuthException$StartFailed/$ExchangeFailed/$Cancelled/$Denied/$MissingCallbackParameters`, `AdditionalAccountsOffException` (запрет нескольких аккаунтов) |
| MCP Apps | `mcpapps/McpAppApi$ShttpException/$McpAppFetchException/$McpAppBootstrapMissException/$HandoffReadTimeoutException`, `HandoffReadLimitExceeded`, `ModelContextTooLargeException`, `DomainValidationException`, `usercontent/mcpapp/McpAppHostCapability` |

Строки ресурсов (подтверждают UI-тексты, не алгоритм): «Change tool permission»,
«Change permission for all tools», «Ask before sending to Claude», «Always
allow» (+ скоупы «for this project / session / task / website»), «Deny», «Deny
with comment», «Always allowed», описание Auto-режима («Claude checks each tool
call for risky actions and prompt injection before executing, runs the ones it
assesses as lower-risk, and blocks the rest»).

### ChatGPT (`com.openai.*`, `protobuf_analytics_events/v1`)

| Поверхность | Классы/события-доказательства |
|---|---|
| Навыки | `feature/skills/impl/data/a`, `RedactedDownloadException` — навыки **скачиваются** с удалённой стороны (модель отличается от наших файловых SKILL.md) |
| GPTs | `feature/gizmoshome` |
| Маркетплейс плагинов | `ChatgptPluginMarketplaceSurface`, `ChatgptPluginActionResult/ActionType/ClickTarget`, `ChatgptPluginConnectorInstallAction`, `ChatgptEcosystemPluginSamplePrompt*` |
| Коннекторы | `ChatgptConnectorAuth{CallbackResult, FailureStage, FlowIntent, Origin, Reason, Stage, StartKind, Surface, Type}`, `ChatgptConnectorsLinkModal{Action, MfaState}`, `valdi/chatgpt/finance/connections/FinanceConnectorAuthorizationHost/Outcome` |
| Composer-механики плагинов | `ChatgptComposerPlugin{AdditionActionType, AdditionTrigger, MentionMechanism, TokenActionType, TokenType}` — упоминания/токены плагинов в композиторе |
| Прочее | `apps` (3 класса), `localfunctions` |

## 3. Сверка и действия «сделать как у них»

| # | Вопрос | Референс (факт кода/строк) | Было у нас | Действие | Статус |
|---|---|---|---|---|---|
| 1 | Права инструментов MCP | Claude: `McpToolPermission(server, tool)` + `McpSetAllToolsPermission(server)`; значения Ask/Always allow/Deny (+ Deny with comment) | только грубая политика `toolExecutionPolicy` (preset AUTOPILOT) без поштучных прав | **Сделать как у них**: поштучные права с приоритетом tool > «все инструменты сервера» > политика; отказы deny/ask честные, проверка до соединения; UI «Права инструментов» + «Изменить права всех инструментов» | **реализовано в этом проходе** (§4) |
| 2 | Просмотр инструкций навыка | Claude: `ViewSkillInstructions`, `SkillDetail(id)`, `SkillEdit(id)` | превью 240 символов + обрезка 4000, без полного просмотра и редактирования | **Сделать как у них**: деталь навыка с полным текстом SKILL.md; `SkillEdit` — отложить | просмотр — **реализовано**; edit — «требует проверки» (отложено) |
| 3 | Деталь карточки каталога | Claude: `ConnectorDirectoryDetail`, `ConnectDirectoryEntryWebScreen`, `AddCustomConnectorWebScreen` | список каталога + «Установить», без detail-sheet | **Сделать как у них**: sheet-деталь позиции (издатель/источник/лицензия/блокер/сценарий) + «Установить» | **реализовано в этом проходе** (§4) |
| 4 | Жизненный цикл MCP-входа | Claude: `McpAuthException$ StartFailed/ExchangeFailed/Cancelled/Denied/MissingCallbackParameters` + `AdditionalAccountsOffException` | наши статусы pending/authorized/denied/failed/expired/revoked/superseded (+safe-тексты ошибок) | расхождение тонкое: `MissingCallbackParameters` у нас — ветка `failed` с текстом; `AdditionalAccountsOff` (многоаккаунтность) — аналога нет | зафиксировано; код не менялся («требует проверки» продукта — многоаккаунтность) |
| 5 | Composer-механики плагинов | ChatGPT: `ComposerPluginMention/Token*` (упоминания/токены) | `/slash`-команды + «Вставить в чат» | @-упоминания/токены плагинов — отложить | «требует проверки» (отложено) |
| 6 | Auto-режим оценки рисков tool calls | Claude: Auto mode («checks each tool call for risky actions and prompt injection… blocks the rest») | нет аналога | большая фича — отложить | «требует проверки» (отложено) |
| 7 | MCP Apps (встраиваемые приложения, handoff, лимиты контекста) | Claude: `mcpapps/*`, `McpAppHostCapability` | нет аналога | отдельная фича продукта — отложить | «требует проверки» (отложено) |
| 8 | Модель навыков | ChatGPT: навыки скачиваются (`RedactedDownloadException`) | файловые SKILL.md локально | разная модель продуктов — не дефект; фиксируется | зафиксировано |
| 9 | Правка сообщения → версии внутри разговора | claude.ai web (живая проверка 1 ноября): Edit/Cancel/Save + переключение версий | правка создаёт отдельный chat ID | «как у них» — версии внутри разговора | **реализовано** (сверка 3 ноября обновила статус): `ChatMessageVersions.kt` (`forkMessageVersion`/`selectMessageVersion` — архивация хвоста в ветки версий со стабильным id якоря, `conversationExecutionId` сохраняет видимый id разговора), `editAndResendUserMessage`/`selectUserMessageVersion`, UI «Предыдущая/Следующая версия» (`MainScreen.kt:2770–2779`) |

## 4. Реализация этого прохода

- **Мост (готов, Node-коллекция 183/183)**: `pc-bridge/lib/tool-permissions.js`
  (семантика allow/ask/deny, три уровня приоритета, сохранение прочих ключей
  `permissions-config.json`); enforcement в `lib/mcp-client.js`
  `McpClientManager.callTool` ДО соединения (`tool_denied` / `tool_needs_approval`
  — честные отказы, успех не выдумывается; `userInitiated` пропускает ask);
  действия `/api/mcp`: `tool_permissions`, `set_tool_permission`,
  `set_all_tools_permissions`; `userInitiated: true` для явных вызовов из UI;
  `/api/skills` `detail` — полный текст SKILL.md. Тесты:
  `pc-bridge/test/tool-permissions.test.js` (6 сценариев, включая раннюю
  проверку до соединения и сохранение ключей конфига).
- **Android (в этом проходе)**: секция «Права инструментов» в детали
  подключения (построчно «Всегда разрешать/Спрашивать/Запретить», «• по
  политике», «Изменить права всех инструментов», честные состояния
  загрузки/ошибки/«Список инструментов доступен после «Проверить»»), деталь
  навыка с полным SKILL.md (загрузка/ошибка/«превью (полный текст не
  загружен)»), деталь карточки каталога (Издатель/Источник/Сценарий проверки с
  «не записано») — `ExtensionsScreen.kt` / `AntigravityBackendClient.kt` /
  `AntigravityViewModel.kt` / `AntigravityModels.kt` / `MainScreen.kt` +
  `McpToolPermissionsTest.kt` (18 тестов; всего 27 suites / 139 тестов / 0
  падений). Проброс паспортных полей позиций (`publisher`/`sourceUrl`/
  `testScenario`) добавлен в `lib/component-adapters.js` и подтверждён живьём
  через `/api/extensions/adapters` (значения из инвентаря; отсутствие — честное
  «не записано на карточке»).
- **Волна 2 (панель субагентов «как у них» + уведомления, 3 ноября)**:
  `SubagentPanelState.kt` (плюрализмы «задача/задачи/задач», `counterLabel` —
  null при отсутствии полей снимка без выдуманных значений, `effectiveBusyMessage`,
  `hintFor` режима каталога, тексты уведомлений только из фактических счётчиков),
  `SubagentNotifier.kt` (канал «Фоновые задачи и субагенты», запуск →
  «Выполняется/Выполняются N задач…» + действие «Отменить», завершение →
  «Задачи завершены [с ошибками]» + «Открыть», отмена; POST_NOTIFICATIONS —
  soft: без разрешения тихий no-op), `SubagentActionReceiver.kt` (действие из
  шторки → отмена пачки), `SwarmSnapshot`/`SwarmCounts` с nullable-фолбэками,
  панель: счётчик, «Нет фоновых задач», busy-строка (скрыта для своей пачки —
  «другими сессиями» было бы неверно), подсказки shared/isolated. Тесты
  `SubagentPanelStateTest.kt` (10); итог батареи **28 suites / 149 tests / 0
  падений**.
- **Находки полной сверки и фиксы приёмки (3 ноября)**:
  1. **Запуск субагентов был недостижим из UI**: колбэки `onDispatchSwarmTask`/
     `onDispatchCodeTask` были разведены по композаблам, но ни один их не
     вызывал. Добавлен ввод задания + кнопки «Запустить анализ»/«Запустить
     код-задачу» в `BackgroundTasksDetailContent` (вверху панели — при
     длинном списке агентов низ уезжал за экран).
  2. **`/swarm` отсутствовал в палитре `/`-подсказок** при поддержке в
     send-handler — добавлен в `builtInSlash`.
  3. **`ChatMessageVersions.kt`** — пункт №9 таблицы §3 устарел: версии правок
     внутри разговора уже реализованы (см. §3, строка 9).
  4. Зафиксировано: `Round5QuickHubBar` (пилюля «Задачи (N)») объявлен, но не
     вызывается ниоткуда (входы — `/swarm` и debug-роутинг); `swarm-state.json`
     персистит историю батчей моста между перезапусками.
- **Живая приёмка (эмулятор Pixel_8a, 3 ноября)** — скриншоты в
  `verification_screenshots/` (не в коммите):
  `s_accept_panel_empty.png` («Нет фоновых задач» + ввод задания),
  `s_accept_panel_running.png`/`s_accept_panel_terminal.png`/`
  s_accept_panel_cancelled.png` (счётчик «N задача/задачи выполняются»,
  статусы «Выполняется/Завершено/Ошибка/Отменено», подсказки «Работа в общем
  каталоге проекта (только чтение)…» для shared-режима),
  `s_accept_notif_started.png` («Выполняются 2 задачи…» + «Отменить»),
  `s_accept_notif_cancelled.png` («Задачи отменены. Отменено: 2.» — действие
  из шторки отработало до отмены пачки), `s_accept_notif_finished.png`
  («Задачи завершены с ошибками · Завершено: 1, ошибок: 1 · Открыть»).
  POST_NOTIFICATIONS выдан через `pm grant` (на API 33+ без него уведомления
  мягко пропускаются — так и задумано). Честный факт: пачки завершились
  ошибкой среды AGY CLI (gRPC «proto: … contains invalid UTF-8», exit 3);
  панель и уведомления отразили статусы и счётчики верно — эквивалентность
  движка не заявляется.

## 5. Ограничения

- Декомпиляция **частичная** (см. §1): выводы о классах привязаны к проверенным
  файлам; поведение за их пределами — «требует проверки». Повторная полная
  декомпиляция требует больше памяти JVM (обе задачи упёрлись в pagefile/heap).
- Восстановленный код ≠ исходники; часть имён обфусцирована (например,
  `connector/auth/b.java` — 152 КБ обфусцированного кода авторизации; его
  внутренняя логика не разбиралась).
- Строки ресурсов доказывают наличие UI-текстов, не алгоритм; точная семантика
  скоупов «Always allow for this project/session/task/website» по коду не
  разобрана — «требует проверки».
- Живое поведение обоих приложений в части новых экранов вживую не прогонялось
  (нет входов в аккаунты референсов) — статические находки не выдаются за
  пользовательские сценарии.

## 6. Субагенты: детальное сравнение (3 ноября 2026)

### 6.1 Как у них (Claude 1.261029.20 — код и строки ресурсов)

- **Dispatch** — центральный механизм делегирования: «Dispatch tasks to Claude
  and check in from your phone or computer — all in one seamless conversation»,
  «Go to the Dispatch tab on desktop and complete setup», «Set up Dispatch on
  desktop first», «Keep both apps online when dispatching work to Claude»,
  «Dispatch is set up in Claude Desktop», гейт по аккаунту («Dispatch isn't
  available for your account»), уведомления («Get notified when Claude messages
  you in Dispatch», «Dispatch messages»). Инициатор — десктоп; облачный тракт;
  клиент-класс `agentchat/navigation/DispatchSessionHint` (модель String+boolean)
  — **подсказка**, не доказательство создания субагента (оговорка от 1 ноября
  сохраняется).
- **Категории сессий**: `Subagents` / `Teammates` / `Other sessions` (группировка
  bottomsheet, `t70.java:154–207` — выбор ресурсов по категории и заголовок с
  числовой сводкой).
- **Межсессионная переписка («baton»)**: «Messaged teammate», «Messaging
  teammate», «subagent», «a teammate»; классы `baton/ui/ThreadsListPage`,
  `baton/hub/rows/{NotTheChannelsSession, NotTheRequestDrawn, OwnRequestNotRead,
  RecordNamedNobody}` — протокол запросов/перехвата задач между сессиями.
- **Фоновые задачи и шеллы**: «Background tasks», «No background tasks»,
  «%1$d running task(s)» (счётчик), `ChatScreenModalBottomSheetDestination$BackgroundTasks`,
  `CodeRemoteBottomSheetDestination$BackgroundTasksDetail` (данные берутся из
  серверного состояния `kkb.o1()`, не из пользовательской формы); «Background
  shell stopped», «Your computer is busy with other sessions. Ask Claude again
  in a few minutes», изоляция рабочих каталогов: «Running in the shared
  directory ^1. Other sessions may conflict — consider worktree mode to isolate
  changes».
- **Remote-control действия из уведомлений**: `code/remote/notification/`
  `SessionAnswerActionWorker`, `SessionReplyActionWorker(+$DroppedEntryWorker)`,
  `CCRPermissionActionWorker` — ответ/разрешение без входа в приложение
  (подтверждается скриншотами Remote control: «1 running task», «Queue
  feedback…», «Stop», «Background shell failed»).
- `claude-subagent.java` (частичная декомпиляция `c2h0`, 381 КБ) —
  UI-композиции отрисовки списков и утилиты, не движок делегирования.

ChatGPT: субагентов нет; есть делегирование в Codex-сессии
(`StreamHandoffException`, `CodexChatgptHandoffLifecycleAction`,
`feature/codexremote`) — иная продуктовая модель.

### 6.2 Как у нас

- **`SubagentRuntime`** (`pc-bridge/lib/subagent-runtime.js`): на каждую задачу —
  **фиксированная пара исполнителей**: «Разработчик»/«Исследователь» (по mode
  `code`/`analysis`) + «Рецензент» (независимая проверка рисков); стратегия
  `PARALLEL_ANALYSIS`; лимит 2 одновременных, тайм-аут 180 с, очередь (`pump`),
  отмена через AbortController, результат ≤64 КБ (пустой результат = ошибка),
  «Предыдущая задача ещё выполняется» (одна пачка за раз), история батчей ≤8,
  поздние рецензии не подшиваются к отменённым задачам.
- **API моста**: `dispatch_code_task` / `dispatch_task` / `cancel_task` +
  снимки состояния (`fetchMultiAgentSwarm`); запуск исполнителя — CLI-процесс
  (`lib/subagent-cli.js`), `cwd: workspace.directory | repository` — **общий
  рабочий каталог, worktree-изоляции нет** (`subagent-cli.js:63`).
- **Android**: панель «фоновые задачи и субагенты» над полем ввода (внешний вид
  навеян скриншотами Remote control — см. `SUBAGENTS_RECHECK_AND_FIX.md`),
  @-палитра «Субагенты роя, Google Диск, файлы и MCP (@)», бейджи
  «ЛИДЕР»/«Субагент», роли «Субагент анализа»; ошибки — Toast; телефонный
  движок (`PhoneAgentEngine`): дочерний исполнитель только чтение, без
  делегирования; субагенты недоступны во временном чате и в PHONE-режиме.

### 6.3 Сравнение и действия

| Аспект | Claude (доказательства) | У нас | Действие |
|---|---|---|---|
| Инициатор и жизненный цикл | Dispatch: настройка на десктопе, обе стороны онлайн, облачный тракт, гейт аккаунта | ручной запуск из Android через ПК-мост | Движок не воспроизводить вслепую (решение от 1 ноября: сначала установить вход, инициатора и жизненный цикл) — различие зафиксировано |
| Состав исполнителей | сессии: Subagents / Teammates / Other sessions | фиксированная пара «исполнитель + рецензент» | подписи честные (без заявления «как у них»); расширение состава — отдельное решение |
| Счётчик и пустые состояния | «%1$d running task(s)», «No background tasks» | панель без счётчика и пустого состояния | **сделать как у них** (мелкая UI-семантика) — **реализовано и проверено живьём** («2 задачи выполняются», «Нет фоновых задач»; §4 «Волна 2») |
| Занятость | «Your computer is busy with other sessions…» | «Предыдущая задача ещё выполняется» (Toast) | **сделать как у них**: статус в панели — **реализовано** (busy-строка моста; скрыта для своей пачки, чтобы «другими сессиями» не было неверно) |
| Изоляция каталогов | worktree mode («shared directory… may conflict») | общий каталог (`subagent-cli.js:63`) | **частично реализовано**: `workspaceMode`/`sharedDirectoryWarning` в панели (живая проверка: «Работа в общем каталоге проекта (только чтение)…»), изолированная копия — для code-задач; реальные сценарии конфликтов — «требует проверки» |
| Межсессионная переписка | baton: «Messaged teammate», запросы/перехваты | нет | отложено (нужна сессионная модель) |
| Уведомления с действиями | SessionAnswer/SessionReply/CCRPermission воркеры | Toast без действий | **реализовано и проверено живьём**: «Выполняются 2 задачи…»+«Отменить» (действие отменяет пачку), «Задачи завершены [с ошибками]»+«Открыть», «Задачи отменены. Отменено: 2.»; аналог Reply/Answer к сессиям — честно отложено (нет сессионной переписки) |
| Отмена и лимиты | «running tasks», «computer busy» | cancel + limit=2 + 180 с | частично совпадает; лимит 2 фиксирован (у них — число задач свободно) |

### 6.4 Ограничения раздела

- Декомпиляция частичная (§5); движок Dispatch — серверный/облачный, его кода
  нет в APK: выводы только по клиентским классам и строкам.
- `DispatchSessionHint` (String+boolean) не доказывает создание/запуск
  субагента; вывод «межсессионный мессенджер» для категорий — гипотеза без
  прослеженной навигации (уточнение SUBAGENTS_RECHECK_AND_FIX.md §4.0
  сохраняется).
- Скриншоты Remote control относятся к Claude Code Remote, не к обычному чату.
