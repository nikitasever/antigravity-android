# Аудит Round 4: Google Antigravity Android & PC Bridge

Дата аудита: 2026-11-01
Статус: Полный технический аудит кодовой базы (`pc-bridge/server.js` и Android-приложения `com.google.antigravity`) перед внесением исправлений Round 4.

---

## 0. Проверка поддержки Gemini 4 (`gemini-4-argon-*`)

- **CLI `agy.exe models`**: Подтверждена нативная поддержка моделей `gemini-4-argon-high`, `gemini-4-argon-medium`, `gemini-4-argon-low` (а также `gemini-3.8-flash-*`) в установленном `C:\Users\st811210\AppData\Local\agy\bin\agy.exe`.
- **Критическая ошибка в `pc-bridge/server.js` (строки 811–813)**:
  ```js
  const effectiveCliModel = isRepoAction
    ? (selectedModel || 'gemini-3.8-flash-high')
    : 'gemini-3.8-flash-low';
  ```
  Мост **принудительно подменял** выбранную пользователем модель `gemini-4-argon-high` на `gemini-3.8-flash-low` для всех чатов и телефонных запросов!
- **План исправления**: Полностью убрать принудительный даунгрейд. Передавать выбранную пользователем модель (`gemini-4-argon-high` по умолчанию) напрямую в `agy.exe --model <selectedModel>`.

---

## 1. Аудит по разделу 2: Запуск и подключение к ПК

### Что работает:
- Экран входа (`GoogleSignInScreen`) с анимацией и сохранением сессии (`isGoogleSignedIn`, `googleAccountEmail`) в `SharedPreferences`.
- Фоновый `startAutoReconnectLoop()` и `discoverRelaysFromCloudRegistry()` через `ntfy.sh/ag_bridge_cr05318_st811210`.
- В `pc-bridge/server.js` есть автозапуск туннелей (`localtunnel` + `localhost.run` SSH) и публикация `bridge_Sync` в `ntfy.sh`.

### Что сломано / не соответствует требованиям:
1. **Автозапуск моста на ПК (Watchdog)**:
   - В `pc-bridge/start-bridge-background.ps1` и `ensure-bridge.ps1` нет зарегистрированной задачи Windows Task Scheduler / фонового сторожевого цикла (watchdog), который автоматически поднимает `node server.js` в течение нескольких секунд после `Stop-Process -Name node -Force`.
2. **Принудительный режим только публичного туннеля для проверки**:
   - В `AntigravityBackendClient.kt` (`getCandidateUrls()`) локальные адреса `10.0.2.2:8787` и `127.0.0.1:8787` всегда добавляются в список кандидатов на эмуляторе. Для чистой проверки по разделу 1 (`без 10.0.2.2 и других локальных адресов — на время проверки отключи их флагом`) необходим явный флаг `forcePublicTunnelOnly` (в `SharedPreferences` / настройках и через intent/broadcast), полностью исключающий `10.0.2.2`, `127.0.0.1`, `localhost` и приватные LAN IP.

---

## 2. Аудит по разделу 3: Запросы, агентский цикл и работа с файлами

### 3.1 Режим сессии ПК (`PC_REMOTE_SESSION`)
- **Что сломано**:
  1. `runAgyPrompt` в `pc-bridge/server.js` (строки 850–866) **никогда не передавал `--conversation <id>`** в `agy.exe`! Вместо продолжения выбранной сессии ПК он запускал одноразовый процесс в пустой папке `uploads/chat-fast` с флагом `--disable-slash-commands`.
  2. В промпт жестко дописывалось: `"Ответь сразу напрямую пользователю... НЕ вызывай никакие инструменты, терминал и под-агентов"` (`server.js:856`). Из-за этого агент в режиме ПК вообще не мог читать файлы проекта, выполнять команды или создавать файлы!
  3. Таймаут `timeoutMs = 21000` убивал `agy.exe` через 21 секунду.
  4. Вызов `appendTurnToSession` просто дописывал фейковые шаги в `transcript.jsonl`, не синхронизируя `.db` / `.pbtxt` сессии для `agy.exe --conversation <id>`.
- **Как должно быть**:
  - При передаче существующего `sessionId` синхронизировать сессию (`syncConversationBeforeCli(sessionId)`), вызывать `agy.exe --conversation <sessionId> --model <model> --output-format stream-json --dangerously-skip-permissions` в рабочем каталоге проекта (`DEFAULT_WORKSPACE`), стримить реальные шаги инструментов (`step_type: "tool"`) и дельты текста/мышления, а после завершения синхронизировать `syncConversationAfterCli(sessionId)`.

### 3.2 Режим смартфона (`PHONE_LOCAL_RESOURCES`)
- **Что сломано**:
  1. В `AntigravityBackendClient.kt` (строки 510–604) перед каждым запросом вызывался `phoneController.scanQuestionAndSearchPhone(prompt)`, который по захардкоженным ключевым словам искал файлы на телефоне, добавлял фейковую карточку `phone_search` еще до обращения к модели, и либо возвращал шаблонный список файлов без участия модели, либо сваливал дамп имен файлов в промпт.
  2. Отсутствовал многошаговый агентский цикл (`up to N=6 steps`), в котором модель **сама** решает, какой инструмент вызвать (`search_files`, `search_media`, `read_file`, `write_file`, `patch_file`, `list_dir`, `open_app`, `contacts`, `share`), получает результат выполнения инструмента с телефона и делает следующий шаг (например: шаг 1 — `search_files` для поиска PDF договора, шаг 2 — `read_file` для чтения найденного PDF, шаг 3 — итоговый пересказ содержимого).
  3. В `PhoneResourceController.kt`:
     - `executeDynamicAction("read_file")` читал файл через `file.readText()`, что на бинарном PDF возвращало сырые байты PDF-заголовка вместо использования `extractCleanReadableText(file)` (который умеет извлекать текст из PDF и DOCX).
     - `executeDynamicAction("patch_file")` отсутствовал как отдельное действие (были только `write_file` / `edit_file`).
     - `searchMediaStore` не поддерживал фильтрацию по дате (например, `dateFilter: "today"` или `sinceDays` для вопроса «Какие фото я сделал сегодня?»).

### 3.3 Стриминг и сетевой транспорт
- **Что сломано**:
  - Мост запускал `agy --output-format json`, ждал полного завершения и возвращал один JSON-ответ. На длинных запросах публичный туннель рвал соединение по таймауту, а пользователь 30+ секунд смотрел на пустой экран.
- **Как должно быть**:
  - Использовать `agy --output-format stream-json` и стримить NDJSON/SSE чанки (`POST /api/chat/stream` + поддержка стриминга в `/api/chat`) с регулярными heartbeat-сообщениями каждые 5 секунд и немедленной передачей `thinking_delta`, `tool_start`, `tool_done`, `text_delta`, `phone_action`, `done`.

### 3.4 Поведение без моста (Offline / Bridge Unreachable)
- **Что сломано**:
  - В `server.js` (`buildSmartServerFallbackReply`) и в `AntigravityBackendClient.kt` (`buildSmartContextualResponse`, `callPublicCloudAiFallback`) генерировались фейковые шаблонные ответы и фейковые цепочки рассуждений.
- **Как должно быть**:
  - Полностью удалить шаблонные генераторы ответов. Если мост недоступен и задан Gemini API ключ — выполнять реальный запрос (включая агентский цикл с инструментами телефона) через Gemini API. Если ключа нет — сразу возвращать короткое честное сообщение о том, что мост ПК недоступен, без зависаний и без выдуманных ответов.

---

## 3. Аудит по разделу 4: Естественное мышление в стиле Claude

### Найденные шаблонные строки в коде (подлежат полному удалению):
1. **`pc-bridge/server.js`**:
   - Функция `buildRichReasoningTrace` (строки 672–688):
     - ``• Анализ намерения: «${cleanPrompt}»``
     - ``• Подключение к контексту рабочей станции (${os.hostname()}, ${ DEFAULT_WORKSPACE })``
     - ``• Вызов инструмента ПК [${a.tool}]: ${a.summary}``
     - ``• Запланировано действие на смартфоне [${a.type}]``
     - ``• Синтез структурированного ответа модели ${model || 'gemini-4-argon-high'}``
   - Функция `buildSmartServerFallbackReply` (строки 754–798).
   - Fallback-строки в `/api/chat` и `/api/session/prompt` (строки 1538, 1609):
     - ``• Анализ запроса: "${prompt.slice(0, 80)}"\n• Маршрутизация через локальный мост ПК...``
     - ``• Сессия: ${sessionId}\n• Модель: ${selectedModel}\n• Обработка запроса на рабочей станции...``
2. **`AntigravityViewModel.kt`**:
   - Строка 970: `thinking = "• Анализ намерения: «${text.take(65)}»\n• Синхронизация с рабочей станцией ПК и контекстом устройства…"`
3. **`AntigravityBackendClient.kt`**:
   - Строка 513: `"• Сканирование вопроса и поиск релевантных данных в памяти смартфона…"`
   - Строка 596: `"• Найдено совпадений на устройстве: ${preScan.matchedCount} (контекст прикреплён к запросу)"`
   - Строки 606–624: `"• Анализ намерения: «$cleanPrompt»"`, `"• Режим выполнения: Сессия ПК / Локальные ресурсы смартфона"`, `"• Выполнение цепочки рассуждений модели ($selectedModel)…"`
   - Строки 687–697, 738–745, 779–783, 808–812: аналогичные шаблонные буллиты.
4. **`MainScreen.kt`**:
   - Строки 2256–2258: подсчёт `~${wordCount} токенов рассуждения` и отображение `"Думаю…"` когда рассуждений нет.
5. **Сворачивание блока размышлений**:
   - В `AntigravityViewModel.kt:1062` после завершения ответа выставлялось `isThinkingExpanded = true` вместо сворачивания в `"Думал N с"` (`isThinkingExpanded = false`), которое раскрывается по нажатию пользователя.

---

## 4. Аудит по разделу 5: Системная инструкция

- **Что сломано**:
  - Отсутствует единый файл `pc-bridge/agent_system_prompt.md` (и его копия в `app/src/main/assets/agent_system_prompt.md`).
  - В `server.js` вместо полноценной системной инструкции использовался урезанный промпт с запретом вызова инструментов.
- **План исправления**:
  - Создать `pc-bridge/agent_system_prompt.md` и `app/src/main/assets/agent_system_prompt.md` с правилами на русском языке:
    1. Живое пошаговое рассуждение от первого лица внутри `<thinking>...</thinking>` перед каждым действием и ответом (объясняя, *почему* выбран именно этот шаг).
    2. Доведение любой задачи до готового, проверенного результата (самопроверка через чтение/сборку/запуск и исправление ошибок перед финальным ответом).
    3. Использование только реальных данных из выводов инструментов (запрет на выдумывание имен файлов, дат, содержимого).
    4. Краткое уточнение у пользователя при неоднозначности и краткий итог проверки в конце.
  - Логировать аргументы запуска `agy.exe` и метрики (`ttfbMs`, `totalMs`, наличие системной инструкции) в `pc-bridge/bridge_requests.log`.

---

## 5. Аудит по разделу 6: Плагины, скиллы и MCP-серверы

- **Что сломано**:
  1. В `pc-bridge/server.js` **полностью отсутствуют** эндпоинты `/api/plugins`, `/api/skills`, `/api/mcp`.
  2. В `AntigravityViewModel.kt` (строки 176–208) и `MainScreen.kt` (`ChatGptPluginsScreen`, строки 1942–2079) используется захардкоженный список `installedPlugins` в памяти без связи с ПК, без разделения на вкладки/секции «Плагины», «Скиллы», «MCP-серверы» и без модалки «+ Добавить».
- **Как должно быть**:
  - На мосту реализовать:
    - `GET /api/plugins` и `POST /api/plugins` (`install`, `enable`, `disable`, `uninstall`) через `agy.exe plugin ...` + реестр в `C:\Users\st811210\.gemini\extensions` / `settings.json`.
    - `GET /api/skills` и `POST /api/skills` (`add`, `enable`, `disable`, `remove`) через реальные папки `C:\Users\st811210\.gemini\config\skills\<name>\SKILL.md` (с поддержкой отключения через `.disabled` / метаданные).
    - `GET /api/mcp` и `POST /api/mcp` (`add`, `enable`, `disable`, `remove`) через `agy.exe mcp add/remove/enable/disable/list` и синхронизацию с `C:\Users\st811210\.gemini\settings.json` и `C:\Users\st811210\.gemini\config\mcp_config.json`.
  - В Android-приложении (`DrawerSection.PLUGINS`) отображать реальные списки с ПК по трем вкладкам («Плагины», «Скиллы», «MCP-серверы»), переключатели включения/выключения, удаление и кнопку «+ Добавить» с диалогом для каждого типа.
