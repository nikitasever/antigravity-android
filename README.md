# Google Antigravity Android (`com.google.antigravity`)

Мобильный клиент **Google Antigravity** для Android (Jetpack Compose, Material 3) с прямой синхронизацией рабочих сессий ПК (`agy.exe`), автономным агентским циклом на смартфоне и голосовым режимом реального времени (Gemini 4 Argon + Neural TTS).

## Архитектура и структура проекта

- `app/src/main/java/com/google/antigravity/`
  - `MainActivity.kt` — точка входа Android-приложения, инициализация разрешений и фоновых сервисов.
  - `model/AntigravityModels.kt` — модели данных сессий, сообщений, вызовов инструментов, плагинов, скиллов, MCP-серверов и каталог моделей (`gemini-4-argon-high`, `gemini-4-argon-medium`, `gemini-4-argon-low`, `gemini-3.8-flash-*`, `gemini-2.5-*`).
  - `viewmodel/AntigravityViewModel.kt` — управление состоянием чата, переключением режимов (`PC_REMOTE_SESSION`, `PHONE_LOCAL_RESOURCES`), плагинами/скиллами/MCP и голосовым движком.
  - `network/AntigravityBackendClient.kt` — сетевой клиент с автообнаружением публичных HTTPS-туннелей (`ntfy.sh`), потоковым чтением NDJSON (`/api/chat/stream`), многошаговым циклом смартфона и прямым резервным циклом через Gemini REST API.
  - `device/PhoneResourceController.kt` — локальные инструменты смартфона (`search_files`, `search_media`, `read_file` с поддержкой PDF/DOCX/TXT/MD, `write_file`, `patch_file`, `list_dir`, `contacts`, `open_app`, `share`).
  - `voice/RealtimeVoiceEngine.kt` и `ui/components/OrbitVoiceOverlay.kt` — голосовой режим реального времени с потоковой озвучкой по предложениям (`/api/tts` `MsEdgeTTS`), непрерывным циклом диалога (Hands-Free) и мгновенным перебиванием голосом (Barge-In).
  - `ui/MainScreen.kt` и `ui/components/MarkdownRenderer.kt` — интерфейс чата, карточек вызовов инструментов, живого блока рассуждений («Думаю… N с» / «Думал N с») и рендеринга Markdown/Diff.
- `app/src/main/assets/agent_system_prompt.md` — единая русскоязычная системная инструкция агента для мобильного режима.
- `pc-bridge/`
  - `server.js` — локальный мост синхронизации с `agy.exe` (`--input-format stream-json --output-format stream-json`), пулом прогретых воркеров, потоковым Neural TTS (`MsEdgeTTS`), управлением плагинами/скиллами/MCP и менеджером публичных HTTPS-туннелей.
  - `agent_system_prompt.md` — единая русскоязычная системная инструкция для ПК-моста.
  - `start-bridge-watchdog.ps1` и `ensure-bridge.ps1` — сторожевой процесс (Watchdog) и регистрация автозапуска в Планировщике заданий Windows (`AntigravityPcBridgeWatchdog`).
- `scripts/build.ps1` — скрипт изолированной сборки APK (`assembleDebug`) и синхронизации `Antigravity-Android-2.0.0.apk`.
