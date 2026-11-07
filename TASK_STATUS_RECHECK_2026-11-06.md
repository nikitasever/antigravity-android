# Статусы фоновых задач — 6 ноября 2026

## Основание

Проверена связь SwarmAgentNode → SubagentPanelState → плоский индикатор задач и
BackgroundTaskSheet. В предоставленном скриншоте Claude Remote control
IMG_20261101_232725_890.jpg активная задача показана строкой над composer.
Паттерн уже принят в UX-CONTRACT; форма запуска и новая панель не добавляются.
Скриншот не раскрывает серверную реализацию субагентов.

## Найдено

BackgroundTaskSheet.taskStatus считает running/active/working выполняющимися,
но SwarmAgentNode.isInProgress распознавал только queued/running. От этого
зависели счётчик, видимость ссылки и Stop, а также продолжение опроса ПК.
effectiveCounts.running также учитывал только running. Даже значение по умолчанию
SwarmAgentNode(status=active) не считалось незавершённым.

Предположение о зависимости parseSwarmAgentNode.lowercase() от языка устройства
не подтвердилось: в текущем Kotlin lowercase() уже инвариантен, тест tr-TR
прошёл до изменения. Явный Locale.ROOT фиксирует намерение, но не является
устранением воспроизведённого дефекта. Этот случай оставлен регрессионной проверкой.

## Проверка

Добавлены проверки поддерживаемых выполняющихся статусов, терминальных и
неизвестных статусов с settling, а также реального JSON-парсера при tr-TR.
Исходная локаль тестового процесса восстанавливается в finally.
До изменения production-кода: 5 тестов ConversationTasksTest, **1 failure** —
supportedRunningAliasesKeepTaskControlsActiveAndCountsConsistent. Остальные четыре,
включая tr-TR, прошли. Это ожидаемый красный baseline, сохранён в
`uploads/task-status-baseline.log` и `uploads/task-status-baseline.xml`.

Изменение: SwarmAgentNode.isRunning — единый предикат running/active/working,
используемый isInProgress и effectiveCounts.running. queued и settling сохраняют
прежнюю семантику; completed/failed/cancelled/ready/unknown не считаются активными.
Счётчики, явно переданные мостом, по-прежнему имеют приоритет в исходном снимке.
Не менялись права, лимиты, реальное исполнение и отмена задач. Исправлена только
классификация состояния, от которой зависят UI и продолжение опроса.

Полный набор: **279 unit-тестов, 0 failures/errors/skipped**. Новый тест,
падавший до исправления, проходит. Terminal/unknown и settling проверены отдельно.
Strict design audit: **0 violations**. `git diff --check` без ошибок пробелов.

Эмулятор в этом проходе не запускался: визуальные компоненты и сетевые маршруты
не менялись. Это проверка модели состояния, реального JSON-парсера и производных
счётчиков. Фактический запуск/Stop/раскрытие/перезапуск Android ранее проверены
в SUBAGENTS_UI_RECHECK_2026-11-05.md; эти прошлые проверки не выдаются за новый
прогон данного APK. Node-код не менялся, Node-тесты не запускались повторно.

Откат: вернуть прежние выражения isInProgress и effectiveCounts.running;
миграции хранилища и изменения данных не требуются. Новые тесты при таком откате
снова выявят несогласованность поддерживаемых статусов.

`testDebugUnitTest lintDebug assembleDebug`: **BUILD SUCCESSFUL in 4m 16s**.
Lint: **0 errors, 190 warnings**. Финальный XML: `uploads/task-status-final.xml`;
логи: `uploads/task-status-final-build.log`, `uploads/task-status-design-audit.log`,
`uploads/task-status-diff-check.log`.

Локальный APK: `uploads/design-recheck/Antigravity-task-status-preview.apk`.
SHA-256: `8A6576B91887EF0BE1BE9118C6E31645325D324B46E9692678CD9E12CEF8A917`.

Это локальное исправление после опубликованного релиза 2.2.0. Новый commit/push
или релиз в этом проходе не выполнялись. Сторонний кэш моделей не изменялся
этой задачей и не включался в проверку изменений.
