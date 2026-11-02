# Закрытие блокеров расширений — проверка 2 ноября 2026

## Выполнено

1. Два канонических MIT-плагина находятся в `extensions/plugins/`:
   `antigravity-workspace-guard` и `kotlin-compose-inspector`. В каждом — валидный
   manifest, лицензия, SKILL.md, Node-скрипт и hook по контракту проекта.
   Git-проверка читает status/diff --check и отклоняет ограниченный набор опасных
   Git-команд; Kotlin-проверка находит конфликты и TODO/FIXME, не заменяя компилятор.
   Локальные установленные и staging-копии обновлены явным sync-скриптом.
   Две staging-записи остаются в учёте происхождения, но скрываются как алиасы
   в каталоге Android. Состояния config.json и разрешения не менялись.
2. HTTP MCP OAuth: discovery RFC 9728 и issuer metadata, preregistered public
   client или dynamic registration, Authorization Code + PKCE S256, state,
   привязка к resource/server, одноразовый ответ, refresh/rotation и revocation.
   Android открывает системный браузер; callback приходит на IPv4 loopback
   самого телефона (RFC 8252), после чего ссылка возвращает в приложение.
   Обмен кода требует дополнительного одноразового ключа приложения.
   Доступный OAuth-вход и успешный MCP handshake — отдельные состояния.
3. Access/refresh tokens хранятся в зашифрованном vault профиля ПК, вне пакетов,
   экспорта и APK. Discovery/token HTTP redirects запрещены, DNS закрепляется
   после проверки публичного адреса. SSE не пересылает Bearer на другой origin.
   При 401 handshake возможен один refresh; tools/call автоматически не повторяется.
4. `extensions/official-mcp.json` содержит проверенные официальные источники,
   лицензии, версии и дескрипторы Desktop Commander, Bright Data и GitHub.
   Наличие npm-версий и MIT подтверждено также registry.npmjs.org.
   Desktop Commander требует Node >=22.12.0 по опубликованному engines.
   Windows npx/npm запускаются через JS CLI Node без shell-интерполяции.
5. Матрица и coverage пересобраны: 79 учтённых позиций, 24 подтверждённых пути
   (включая три официальных дескриптора), 55 неподтверждённых, 0 обязательных
   заблокированных записей, 75 позиций backlog. Дескриптор не выдаётся за
   проверенный пользовательский вход или работающий внешний tools/call.

## Проверки

| Проверка | Результат |
|---|---|
| Полный Node-прогон точного снимка коммита | **154/154**, 0 ошибок |
| Android testDebugUnitTest | **98 тестов / 24 suites**, 0 failures/errors |
| Android lintDebug | успешно |
| Android assembleDebug | успешно, BUILD SUCCESSFUL |
| Проверка синтаксиса server.js | успешно |
| Статический UI audit, strict | 0 ошибок/предупреждений |
| git diff --check для изолированного состава | успешно |

Node-команда: `node --test --test-concurrency=1 'test/*.test.js'` из pc-bridge.
Изолированный снимок использовал те же установленные node_modules проекта;
параллельные изменения этапа 8 в него не включались. Отдельно сверено, что
Android-файлы сборки совпадают с Android-файлами изолированного коммита.

Android-команда: `gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
-Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8"
-Pkotlin.compiler.execution.strategy=in-process --console=plain --max-workers=1`.
APK: `app/build/outputs/apk/debug/app-debug.apk` (сборочный артефакт, вне Git).

Сквозные OAuth-тесты используют настоящий локальный HTTP OAuth/MCP-сервер:
registration, browser authorization request, обмен с проверкой S256, живой
MCP handshake/call, refresh, invalid_grant, повторный callback, неверный
state/resource/issuer и отзыв. Android unit-тест выполняет настоящий HTTP-запрос
к loopback-слушателю, проверяет отказ чужому state и безопасную ссылку возврата.

## Границы проверки

- Реальные аккаунты внешних поставщиков и физический телефон не использовались.
  Для итоговой проверки входа нужны пользовательские учётные данные и согласие
  на стороне поставщика. Если DCR отсутствует, требуется предварительно
  зарегистрированный native public client и oauth.clientId в приватном MCP-конфиге.
- OAuth и HTTP MCP в этой реализации обслуживает мост ПК. Скрипты двух плагинов
  также выполняются на ПК с Node/Git. Автономный от ПК Android MCP-транспорт
  не добавлялся и в матрице не заявляется.
- После выгрузки приложения ОС во время ожидания loopback требуется начать вход
  заново. Краткоживущие данные возврата находятся в noBackupFilesDir.
- Ключ vault находится в том же защищённом профиле ОС отдельно от ciphertext;
  аппаратный keystore/DPAPI не используется. Необходимо ограничение доступа к
  профилю владельцем. Секреты не включены в коммит.
- Figma/Zoom hosted MCP и открытый переносимый сервер различаются; неизвестные
  реализации и сторонние аналоги не подставлялись. Google Workspace CLI не
  объявляет MCP mode; 1Password SaaS Manager не отождествлялся с APK-коннектором.

## Git и параллельная работа

Коммит создан отдельно в `codex/extensions-oauth-blockers`, без переключения
общего checkout и без изменения его индекса. В общих server.js и
extension-registry.js отобраны только изменения этой задачи. Незавершённые
изменения другой модели, кэш моделей и скриншоты сохранены вне этого коммита.

Официальные ссылки и происхождение дескрипторов — в
`EXTENSIONS_COMPATIBILITY.md` и `extensions/official-mcp.json`.
