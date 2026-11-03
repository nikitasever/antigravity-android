# OAuth MCP на ПК-мосту

Изменения 3 ноября 2026 ограничены бэкендом, каталогами локальных плагинов,
генерируемыми отчётами и документацией. Android-код не менялся; Gradle, ADB и
Git-коммиты не запускались.

## Контракт `/api/mcp`

Запросы POST с JSON. Новые действия сохраняют совместимость со snake_case API.

```json
{"action":"oauthStart","name":"my-mcp"}
```

Ответ содержит `ok`, `id`, `authUrl` (также `authorizationUrl`), `sessionId`,
`state`, `redirectUri`. `sessionId` — краткоживущий ключ опроса данной попытки,
не access_token/refresh_token. Его и весь ответ старта нельзя писать в логи.
Клиент открывает `authUrl` в браузере по явному действию пользователя.

По умолчанию redirect URI —
`http://127.0.0.1:8787/api/mcp/oauth/callback`. Этот адрес относится к ПК:
браузер должен иметь доступ к loopback ПК. Для браузера на другом устройстве
нужен явно настроенный HTTPS адрес моста в приватном конфиге MCP:

```json
{"oauth":{"clientId":"registered-public-client-id","bridgeRedirectUri":"https://your-bridge.example/api/mcp/oauth/callback"}}
```

Произвольный URL из тела запроса или Host-заголовка не используется как callback.
HTTPS endpoint должен маршрутизироваться на мост. При наличии DCR clientId
получается от сервера; иначе пользователь регистрирует public client у поставщика.
Поставщик должен разрешать точный callback URI и PKCE S256.

```json
{"action":"oauthStatus","name":"my-mcp","sessionId":"value-from-start-response"}
```

Ответ: `ok`, `id`, `status`, `authorized`, при наличии сессии — `expiresAt`.
Состояния попытки: `pending`, `authorized`, `denied`, `failed`, `superseded`,
`revoked`, `expired`; без sessionId — состояние сохранённой авторизации.
Опрос статуса не вызывает модель, MCP-инструменты или refresh. После перезапуска
моста ожидающую попытку нужно начать заново, сохранённые токены остаются в vault.
`authorized` не означает успешный MCP handshake: для этого есть `probe`.

```json
{"action":"oauthRevoke","name":"my-mcp"}
```

Токены локально удаляются, ожидающие callback инвалидируются, MCP-сессия
отключается. При наличии revocation_endpoint выполняется отзыв на стороне
поставщика. Если поставщик недоступен, API возвращает ошибку, но локальный
выход всё равно выполнен; повторный oauthStatus показывает authorized=false.

Сохранены `oauth_start`, `oauth_status`, `oauth_revoke`, `oauth_complete`.
Прежний `oauth_start` использует native loopback callback клиента; явный
`callbackMode:"bridge"` переключает его на callback моста. Внешние браузерные
Origin-запросы к управлению OAuth запрещены. GET callback провайдера разрешён
только для одноразового действующего state с режимом bridge.

## Безопасность и выполнение

- RFC 9728 discovery, проверка issuer и S256, Authorization Code + PKCE,
  resource binding, одноразовый state, refresh с сериализацией и revoke.
- Секреты хранятся в зашифрованном vault профиля ПК, вне manifests/APK/экспорта.
  API статуса и callback HTML не возвращают access/refresh tokens и code_verifier.
  Ошибки публичного API не отражают текст провайдера; используется redactSecrets.
  Handshake-метаданные MCP также проходят рекурсивное скрытие известных секретов.
- Discovery и token endpoints требуют HTTPS и публичных адресов; DNS закреплён,
  HTTP redirects запрещены. Исключение localhost включается только тестовой опцией.
- Обновление токена происходит при MCP-исполнении. При 401 handshake возможна
  одна попытка refresh; tools/call автоматически не повторяется.
- Действие oauthStart само не запускает MCP-инструменты и не вызывает модель.

## Плагины и матрица

Канонические MIT-пакеты находятся в `extensions/plugins/`. Скрипт
`node pc-bridge/scripts/sync-local-plugins.js` обновляет установленные и staging
копии, сохраняя config.json и manifest.disabled. Все четыре локальные записи
проверяются по фактическим файлам; staging-записи — алиасы двух пакетов.

```powershell
node pc-bridge/scripts/build-adapter-matrix.js
node pc-bridge/scripts/build-migration-coverage.js
npm --prefix pc-bridge test
```

Каталог `extensions/official-mcp.json` связывает Desktop Commander, Bright Data
и GitHub с официальными дескрипторами. Генератор проверяет принадлежность их
positionIds инвентарю. Наличие дескриптора не выдаётся за установленную зависимость,
пользовательский вход или проверенный tools/call внешнего сервиса.

Coverage находится в корне проекта: 79 позиций, 24 подтверждённых пути,
55 неподтверждённых, 0 обязательных заблокированных записей. Внешние учётные
записи и физический телефон в автоматических проверках не используются.

## Результат проверки 3 ноября 2026

- `npm --prefix pc-bridge test`: **174 теста, 174 passed, 0 failed**.
- Синтаксис server.js и mcp-oauth-api.js проверен Node.
- Все четыре фактических каталога (две установки и две staging-копии)
  проверены analyzePluginAdapters: `fully-supported`, пустой список blockers.
- Проверены HTTP-поток callback моста, опрос статуса, отказ чужому/повторному
  state, отмена пользователем, смена попытки, refresh, revoke, реальные MCP
  handshake/tools/call на тестовом сервере и маскирование access/refresh tokens
  в публичных метаданных рукопожатия.
- Отдельный тест подтверждает сохранение manifest.disabled и config.json при
  синхронизации локальных пакетов.
- `git diff --check` без ошибок; изменения app/src отсутствуют.
- Коммит, Gradle и ADB не выполнялись. Исходный кэш моделей и скриншоты не менялись.
