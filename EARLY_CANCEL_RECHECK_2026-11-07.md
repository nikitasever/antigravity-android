# Отмена до принятия задачи мостом — 7 ноября 2026

## Проверяемая гонка

Android отправляет dispatch A. Стенд принимает HTTP-запрос, но удерживает его до
вызова SubagentRuntime.dispatch. Пользователь нажимает отмену: runtime ещё не
знает ID A, поэтому отмена возвращает пустой снимок. Затем пользователь открывает
другой чат и запускает B на другом мосту. После освобождения задержки A не должна
остаться работающей, а B должна продолжить выполнение.

Тест: `SubagentCancellationIntegrationTest#cancellationBeforeDispatchAcceptanceIsReplayedForItsOriginalBatch`.
Стенд: `node pc-bridge/test/fixtures/notification-cancel-bridge.cjs --hold-dispatch-a`.
Перед каждым запуском нужен свежий процесс стенда. Остальная подготовка отдельного
эмулятора и восстановление его настроек — как в NOTIFICATION_CANCEL_E2E_RECHECK_2026-11-07.md.

```powershell
adb -s emulator-5560 shell am instrument -w -r -e class 'com.google.antigravity.SubagentCancellationIntegrationTest#cancellationBeforeDispatchAcceptanceIsReplayedForItsOriginalBatch' com.google.antigravity.test/androidx.test.runner.AndroidJUnitRunner
```

Оба метода класса требуют разных свежих процессов стенда. Не запускать весь
класс одной командой или как обычный автономный connectedAndroidTest без подготовки.

Это тест настоящих ViewModel, HTTP-клиента и SubagentRuntime с контролируемыми
работниками, без коммерческой модели и без полного server.js. Он не проверяет
восстановление после смерти Android-процесса или доставку отмены при недоступной
сети.

Первый вызов Gradle не начал компиляцию: PowerShell разобрал не заключённый в
кавычки аргумент -Pkotlin.compiler.execution.strategy как имя задачи. Аргумент
исправлен; исходный лог: uploads/early-cancel-baseline-build.log.

## Воспроизведение до исправления

Нативный Android-тест упал на ожидании повторной отмены A: 1 тест, 1 ошибка,
13.148 секунды. До cleanup стенд зафиксировал A: started=2, aborted=0, active=2,
два ранних cancel с ID A. B: started=2, aborted=0, active=2, cancel отсутствует.
Это подтверждает именно продолжение A после уже отправленной отмены.
Логи: uploads/early-cancel-baseline-instrumentation.log и
uploads/early-cancel-before-fix-state.json. Finally теста остановил оба стенда;
снимок cleanup сохранён отдельно.

## Исправление

В SwarmRunOrigin добавлен сохраняющийся в памяти флаг cancellationRequested.
Отмена выставляет его синхронно перед HTTP-запросом. Когда dispatch возвращается,
корутина один раз повторяет cancel по адресу и ID исходного origin, даже если
текущий чат или запуск уже изменён. Применение снимка к UI по-прежнему требует
совпадения текущего origin. Терминальный снимок не создаёт уведомление старта.

Повтор ограничен одним запросом после dispatch, не запускает модель повторно и
не отменяет задачи без ID. При ошибке сети это не гарантия доставки; флаг не
сохраняется после смерти процесса. Для отката убрать флаг/его установку и блок
повторной отмены после dispatch в ViewModel; маршрутизацию по origin сохранить.

## Проверка после исправления

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
  BUILD SUCCESSFUL за 5m 41s. Выполнены 286 JVM-тестов, ошибок нет.
  Lint: 0 errors, 190 warnings.
- Тот же тест ранней отмены: `OK (1 test)`, 5.256 секунды. A получила три cancel
  со своим ID (кнопка, переход в новый чат, повтор после dispatch), оба работника
  получили abort и освободили слоты. B оставалась активной без cancel до отдельной
  команды в конце сценария; её список и уведомление не заменялись ответами A.
- Прежний сквозной сценарий уведомления и смены адреса: `OK (1 test)`, 5.362 секунды.
- Два нативных теста PendingIntent/receiver: `OK (2 tests)`, 0.935 секунды.
- Crash buffer пуст; тестового уведомления 4204 нет.

Доказательства: `uploads/early-cancel-final-build.log`,
`uploads/early-cancel-final-instrumentation.log`,
`uploads/early-cancel-final-state.json`,
`uploads/early-cancel-notification-regression.log`,
`uploads/early-cancel-native-regression.log`, `uploads/early-cancel-crash.log`.
На обеих холодных загрузках QA-эмулятора пришлось закрыть зависший System UI до
тестов. Само приложение в crash buffer не отмечено.

Cleanup завершён: исходный URL и переключатель публичного подключения сверены
через UI, разрешение уведомлений совпадает с исходным (granted=true), уведомления
4204 нет. Оба runtime имеют active=0. Стендовый Node-процесс и принадлежащий
задаче AVD остановлены, пробросы 8810/8811 сняты. `git diff --check` и
`node --check pc-bridge/test/fixtures/notification-cancel-bridge.cjs` прошли.

APK: `uploads/design-recheck/Antigravity-early-cancel-preview.apk`, SHA-256:
`CBCF4D6935114B17D8E566763458850E39D683F586DB01F931A96C9E8FC1EBC9`.
Изменения пока локальные, коммит/push/новый релиз в этом проходе не выполнялись.
