# Протокол сквозной верификации Раунда 4 — Google Antigravity Android (`com.google.antigravity`)

## 1. Сборка, APK и проверка целостности (MD5)

- **Команда сборки**: `powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Task assembleDebug` (`BUILD SUCCESSFUL in 1m 11s`, 40 actionable tasks)
- **Путь к APK**: [`app-debug.apk`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/app/build/outputs/apk/debug/app-debug.apk) / [`Antigravity-Android-2.0.0.apk`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/Antigravity-Android-2.0.0.apk)
- **Размер APK**: `152 181 007` байт (`145.13 МБ`)
- **MD5 локального APK**: `0465CB866B7B0FF031205339A697A5FE`
- **Публичные ссылки на скачивание и независимая проверка целостности**:
  1. **Зеркало 1 (`temp.sh` — `Antigravity-Android-2.0.0.apk`)**: `https://temp.sh/mseAy/Antigravity-Android-2.0.0.apk`
     - Скачано обратно (`curl.exe -s -L -X POST`): `152 181 007` байт
     - MD5 после скачивания: `0465CB866B7B0FF031205339A697A5FE` (`MATCH=True`)
  2. **Зеркало 2 (`temp.sh` — `app-debug.apk`)**: `https://temp.sh/wmFjm/app-debug.apk`
     - Скачано обратно (`curl.exe -s -L -X POST`): `152 181 007` байт
     - MD5 после скачивания: `0465CB866B7B0FF031205339A697A5FE` (`MATCH=True`)
  3. **Зеркало 3 (`gofile.io` — два независимых контейнера)**:
     - Страница загрузки 1: `https://gofile.io/d/SZ3KTo0h` (код `1hsHlKZP`, сервер `store-eu-par-12`, проверенный сервером `gofile.io` размер `152181007` байт, MD5 `0465cb866b7b0ff031205339a697a5fe`)
     - Страница загрузки 2: `https://gofile.io/d/yfskLjHm`
  4. **Зеркало 4 (публичный HTTPS-туннель ПК-моста)**: `https://1a24ed9e2872b6ce-79-137-206-221.serveousercontent.com/download/apk`

---

## 2. Приоритет №1 — Gemini 4 (`gemini-4-argon-high` и `gemini-4-argon-low`) без скрытого даунгрейда

- В [`AntigravityBackendClient.kt`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/app/src/main/java/com/google/antigravity/network/AntigravityBackendClient.kt), [`AntigravityViewModel.kt`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/app/src/main/java/com/google/antigravity/viewmodel/AntigravityViewModel.kt) и [`pc-bridge/server.js`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/pc-bridge/server.js) модель по умолчанию — `gemini-4-argon-high` (**Gemini 4 Argon High**), быстрая модель — `gemini-4-argon-low` (**Gemini 4 Argon Fast**).
- Полностью исключён любой скрытый даунгрейд `gemini-4-argon-* -> gemini-3.8-flash-low` во всех режимах (`PC_REMOTE`, `PHONE_ON_DEVICE`, `voiceMode`).
- В [`pc-bridge/server.js`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/pc-bridge/server.js) реализован предварительный прогрев пула фоновых воркеров `agy.exe` (`WARM_POOL_MODELS = ['gemini-4-argon-high', 'gemini-4-argon-low']`) со ступенчатым запуском (предотвращающим блокировки SQLite в `~/.gemini/antigravity-cli`) и корректным завершением отработавших воркеров (`taskkill /PID <pid> /T /F`), а также немедленной отправкой строки в `stdin` без холостого ожидания `waitForWorkerReady`.
- Подтверждение из [`pc-bridge/bridge_requests.log`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/pc-bridge/bridge_requests.log):
  ```json
  {"timestamp":"2026-11-01T01:50:49.550Z","mode":"PC_REMOTE","voiceMode":false,"requestedModel":"gemini-4-argon-low","effectiveCliModel":"gemini-4-argon-low","conversationId":"--es","cwd":"C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android\\uploads\\chat-fast","agyArgs":["--input-format","stream-json","--output-format","stream-json","--model","gemini-4-argon-low","--dangerously-skip-permissions","-p="],"systemPromptInjected":true,"systemPromptChars":2459,"ttfbMs":4247,"totalMs":10844,"toolCallsCount":0,"phoneActionsCount":0,"thinkingChars":502,"replyChars":1617}
  {"timestamp":"2026-11-01T01:57:40.309Z","mode":"PC_REMOTE","voiceMode":false,"requestedModel":"gemini-4-argon-high","effectiveCliModel":"gemini-4-argon-high","conversationId":"5a9a22b5-5a80-4c62-af90-7f88acee48b7","cwd":"C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android","agyArgs":["--conversation","5a9a22b5-5a80-4c62-af90-7f88acee48b7","--input-format","stream-json","--output-format","stream-json","--model","gemini-4-argon-high","--dangerously-skip-permissions","-p="],"systemPromptInjected":true,"systemPromptChars":2459,"ttfbMs":7330,"totalMs":31135,"toolCallsCount":2,"phoneActionsCount":0,"thinkingChars":765,"replyChars":4761}
  {"timestamp":"2026-11-01T01:59:24.654Z","mode":"PC_REMOTE","voiceMode":false,"requestedModel":"gemini-4-argon-high","effectiveCliModel":"gemini-4-argon-high","conversationId":"5a9a22b5-5a80-4c62-af90-7f88acee48b7","cwd":"C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android","agyArgs":["--conversation","5a9a22b5-5a80-4c62-af90-7f88acee48b7","--input-format","stream-json","--output-format","stream-json","--model","gemini-4-argon-high","--dangerously-skip-permissions","-p="],"systemPromptInjected":true,"systemPromptChars":2459,"ttfbMs":5784,"totalMs":22942,"toolCallsCount":2,"phoneActionsCount":0,"thinkingChars":950,"replyChars":505}
  {"timestamp":"2026-11-01T02:11:02.777Z","mode":"PHONE_ON_DEVICE","voiceMode":true,"requestedModel":"gemini-4-argon-low","effectiveCliModel":"gemini-4-argon-low","conversationId":"phone-local-storage","cwd":"C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android\\uploads\\chat-fast","agyArgs":["--input-format","stream-json","--output-format","stream-json","--model","gemini-4-argon-low","--dangerously-skip-permissions","-p="],"systemPromptInjected":true,"systemPromptChars":2459,"ttfbMs":3641,"totalMs":4520,"toolCallsCount":0,"phoneActionsCount":0,"thinkingChars":92,"replyChars":291}
  ```

---

## 3. Раздел 2 — Холодный/тёплый старт, отсутствие ANR и сторожевой демон ПК-моста

### 3.1. Холодный и тёплый запуск на `emulator-5554`
- **Cold Start** (`adb shell pm clear com.google.antigravity && adb shell am start -W -n com.google.antigravity/.MainActivity`):
  - `TotalTime: 6429 ms`
  - Открывается экран входа Google ([`r4_01_cold_start_login.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_01_cold_start_login.png)), после входа открывается главный экран чата ([`r4_02_main_chat.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_02_main_chat.png)).
- **Warm Start** (`adb shell am force-stop com.google.antigravity && adb shell am start -W -n com.google.antigravity/.MainActivity` с сохранённой авторизацией):
  - `TotalTime: 1452 ms` (**строго `< 2000 ms`**)
  - Приложение мгновенно открывается на главном экране чата без повторного экрана входа ([`r4_02_main_chat.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_02_main_chat.png)).
  - Селектор моделей показывает **Gemini 4 Argon High** и **Gemini 4 Argon Fast** ([`r4_03_model_picker_gemini4.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_03_model_picker_gemini4.png)).
  - Боковая шторка показывает статус моста `ОНЛАЙН`, список реальных сессий ПК и память телефона ([`r4_04_drawer.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_04_drawer.png)).
  - Экран настроек показывает активный публичный HTTPS-туннель, включённый тумблер `Только публичный HTTPS-туннель (4G+)` и однострочную кнопку `Веб-синхронизация` ([`r4_05_settings_public_tunnel.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_05_settings_public_tunnel.png)).
  - Количество `ANR in com.google.antigravity` и `FATAL EXCEPTION` в `logcat`: **`0`**.

### 3.2. Автоматический перезапуск ПК-моста (`start-bridge-watchdog.ps1` / `AntigravityPcBridgeWatchdog`)
- При принудительном убийстве `node` (`Stop-Process -Name node -Force`) сторожевой демон [`pc-bridge/start-bridge-watchdog.ps1`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/pc-bridge/start-bridge-watchdog.ps1) в течение **3–6 секунд** автоматически перезапускает `server.js` на порту `8787`, поднимает 3 независимых HTTPS-туннеля (`serveo.net`, `pinggy.io`, `localhost.run`) и публикует подписанный JSON-пакет обнаружения в `https://ntfy.sh/ag_bridge_cr05318_st811210`.

---

## 4. Сквозные сценарии A–I (строго через внешний публичный HTTPS-туннель, `forcePublicTunnelOnly = true`)

### Сценарий A — Продолжение реальной сессии ПК: чтение `README.md` и анализ проекта (`PC_REMOTE_SESSION`)
- **Выбранная сессия ПК**: `5a9a22b5-5a80-4c62-af90-7f88acee48b7` («Продолжение Работы С Файлами»)
- **Модель**: `gemini-4-argon-high` (`ttfbMs: 7330`, `totalMs: 31135`, `toolCallsCount: 2`)
- **Запрос**: *«Прочитай файл README.md в текущей рабочей папке и кратко перечисли основные возможности проекта.»*
- **Выполненные инструменты ПК**:
  - `✓ Чтение файла: README.md`
  - `✓ Выполнение команды: Get-ChildItem -Force`
- **Состояние `transcript.jsonl`**: выросло с `49` до `55` строк (`69 175` байт).
- **Скриншот**: [`r4_sA_pc_readme.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sA_pc_readme.png) — блок `Думал 30 с`, 2 карточки реальных инструментов ПК и полный структурированный обзор возможностей из обновлённого [`README.md`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/README.md).

### Сценарий B — Реальная сессия ПК: создание `scratch\hello.txt` с текущей датой и верификация (`PC_REMOTE_SESSION`)
- **Выбранная сессия ПК**: `5a9a22b5-5a80-4c62-af90-7f88acee48b7`
- **Модель**: `gemini-4-argon-high` (`ttfbMs: 5784`, `totalMs: 22942`, `toolCallsCount: 2`)
- **Состояние файла ДО запроса**: `BEFORE_EXISTS=False` (`C:\Users\st811210\.gemini\antigravity\scratch\hello.txt` удалён перед тестом).
- **Запрос**: *«Создай тестовый файл hello.txt в папке C:\Users\st811210\.gemini\antigravity\scratch с текущей датой и проверь его содержимое.»*
- **Выполненные инструменты ПК**:
  - `✓ Запись файла: hello.txt`
  - `✓ Чтение файла: hello.txt`
- **Проверка физического файла на ПК ПОСЛЕ запроса**:
  - `AFTER_EXISTS=True`
  - Содержимое `C:\Users\st811210\.gemini\antigravity\scratch\hello.txt`: `2026-11-01 04:59:01 +03:00 (01.11.2026)`
- **Скриншот**: [`r4_sB_pc_hellotxt.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sB_pc_hellotxt.png) — блок `Думал 22 с`, карточки `✓ Запись файла: hello.txt` и `✓ Чтение файла: hello.txt`, отчёт с путём, размером и содержимым файла.

### Сценарий C — Режим смартфона: поиск и чтение PDF-договора (`PHONE_LOCAL_RESOURCES`)
- На устройстве в `/sdcard/Download/` размещены реальные PDF-документы (`Договор_аренды_офиса.pdf`, `Договор_обслуживания_2026.pdf`).
- **Модель**: `gemini-4-argon-high` (многошаговый цикл из 4 шагов в [`PhoneResourceController.kt`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/app/src/main/java/com/google/antigravity/device/PhoneResourceController.kt))
- **Запрос**: *«Найди в памяти телефона PDF-файл с договором, прочитай его и напиши краткое резюме.»*
- **Выполненные локальные инструменты телефона**:
  - `✓ Поиск файлов: Поиск на телефоне: «договор» (тип: pdf)`
  - `✓ Чтение файла: Прочитан файл телефона Договор_аренды_офиса.pdf`
  - `✓ Чтение файла: Прочитан файл телефона Договор_обслуживания_2026.pdf`
- **Скриншот**: [`r4_sC_phone_pdf.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sC_phone_pdf.png) — карточки локальных инструментов и подробное резюме обоих PDF-договоров (номер договора, стороны, предмет, сумма, срок действия).

### Сценарий D — Режим смартфона: создание и точечное редактирование `Download/plan.md` (`write_file` + `patch_file`)
- **Модель**: `gemini-4-argon-high` (многошаговый цикл из 3 шагов)
- **Запрос**: *«Создай в папке Download файл plan.md со списком из 3 пунктов, а затем добавь 4-й пункт в конец файла.»*
- **Выполненные локальные инструменты телефона**:
  - `✓ Создание файла: Создан файл на телефоне: plan.md` (`86 Б`)
  - `✓ Точечное изменение файла: Обновлён файл на телефоне: plan.md` (`118 Б`)
- **Проверка физического файла через `adb -s emulator-5554 shell cat /sdcard/Download/plan.md`**:
  ```markdown
  1. Первый пункт
  2. Второй пункт
  3. Третий пункт
  4. Четвёртый пункт
  ```
- **Скриншот**: [`r4_sD_phone_plan_patch.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sD_phone_plan_patch.png).

### Сценарий E — Режим смартфона: поиск сегодняшних фотографий через `MediaStore` (`search_media`)
- **Модель**: `gemini-4-argon-high` (`ttfbMs: 4632` на шаге 1, `ttfbMs: 4618` на шаге 2)
- **Запрос**: *«Какие фотографии я делал сегодня на телефон? Покажи список с именами и размерами.»*
- **Выполненный локальный инструмент телефона**:
  - `✓ Поиск фото и медиа: Поиск в медиатеке телефона...`
- **Скриншот**: [`r4_sE_phone_photos_today.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sE_phone_photos_today.png) — список сегодняшних (`01.11.2026`) изображений из `MediaStore` с точными именами, размерами (`225.8 КБ` — `320.6 КБ`), разрешениями и путями.

### Сценарий F — Общий вопрос без инструментов (`TTFB < 5 с` на Gemini 4, стриминг `Думаю…` -> `Думал N с`)
- **Модель**: `gemini-4-argon-low` (**Gemini 4 Argon Fast**)
- **Метрики из `bridge_requests.log`**: **`ttfbMs: 4247` (`< 5000 мс`)**, `totalMs: 10844`, `toolCallsCount: 0`, `thinkingChars: 502`, `replyChars: 1617`.
- **Запрос**: *«Объясни простыми словами, чем отличается корутина от потока в Kotlin.»*
- **Скриншоты**:
  - [`r4_sF_1_streaming.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sF_1_streaming.png) — живой стриминг ответа и таймера `Думаю…`.
  - [`r4_sF_2_done_expanded.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sF_2_done_expanded.png) — завершённый ответ с развёрнутым по тапу блоком `Думал 11 с` (живое русское рассуждение от первого лица без шаблонных фраз) и горизонтально прокручиваемым блоком кода без переноса по символам.

### Сценарий G — Офлайн / недоступный мост (честная ошибка без шаблонных ответов)
- При установке недоступного URL моста (`https://127.0.0.1:9999`) и отправке запроса *«Какая погода на Марсе?»* приложение выводит честное диагностическое сообщение:
  *«Не удалось подключиться к мосту ПК. Проверьте соединение с рабочей станцией или укажите ключ Gemini API в настройках.»* — `0` фейковых шаблонных фраз.
- **Скриншот**: [`r4_sG_offline_honest.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sG_offline_honest.png).

### Сценарий H — Плагины, Скиллы и MCP-серверы: сквозное управление (добавление, переключение, удаление) и вызов в чате
- Проверены операции `add`, `toggle` (`enable`/`disable`) и `delete` для вкладки «Плагины» (`/api/plugins`), «Скиллы» (`/api/skills` -> `~/.gemini/config/skills/`) и «MCP-серверы» (`/api/mcp` -> `agy.exe mcp` + двусторонняя синхронизация `mcp_config.json` и `settings.json`).
- В чате на модели `gemini-4-argon-high` отправлен запрос: *«Используй скилл workstation-diagnostics и вызови MCP-инструмент get_workstation_pulse из сервера ag-workstation-info, сообщи токен проверки и аптайм.»*
- Агент прочитал [`SKILL.md`](file:///C:/Users/st811210/.gemini/config/skills/workstation-diagnostics/SKILL.md), вызвал `get_workstation_pulse` и вернул проверочный токен `ANTIGRAVITY-MCP-VERIFIED-2026`, имя хоста `cr05318` и аптайм рабочей станции.
- **Скриншоты**:
  - [`r4_sH_1_plugins.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sH_1_plugins.png) — вкладка «Плагины» (однострочные бейджи версий `v1.2.0` / `v1.0.4`).
  - [`r4_sH_2_skills.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sH_2_skills.png) — вкладка «Скиллы» (`reverse-engineer-anything`, `workstation-diagnostics`).
  - [`r4_sH_3_mcp.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sH_3_mcp.png) — вкладка «MCP-серверы» (`ag-workstation-info`, `rea` со статусом `connected`).
  - [`r4_sH_4_mcp_chat.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sH_4_mcp_chat.png) — вызов скилла `workstation-diagnostics` и MCP-сервера `ag-workstation-info` в чате с токеном `ANTIGRAVITY-MCP-VERIFIED-2026`.

### Сценарий I — Живой голосовой режим (Live Voice Mode) и перебивание (Barge-In) на Gemini 4
- **Модель**: `gemini-4-argon-low` (`voiceMode: true`)
- **Метрики из `bridge_requests.log`**: **`ttfbMs: 3641` (`< 5000 мс`)**, `totalMs: 4520`, `thinkingChars: 92`, `replyChars: 291`.
- Первое предложение синтезируется через `/api/tts` (`MsEdgeTTS` `ru-RU-SvetlanaNeural`) и начинает озвучиваться на 4-й секунде параллельно с генерацией следующих предложений.
- При перебивании (`Barge-In`) воспроизведение `MediaPlayer` мгновенно останавливается, буфер предложений очищается, и оверлей переходит в состояние `Слушаю... Говорите свободно`.
- **Скриншоты**:
  - [`r4_sI_1_voice_speaking.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sI_1_voice_speaking.png) — живая озвучка ответа с пульсирующей 3D-сферой и субтитрами на русском языке.
  - [`r4_sI_2_voice_bargein.png`](file:///C:/Users/st811210/.gemini/antigravity/scratch/antigravity-android/screenshots/r4_sI_2_voice_bargein.png) — мгновенный переход в `Слушаю... Говорите свободно` после `Barge-In`.
