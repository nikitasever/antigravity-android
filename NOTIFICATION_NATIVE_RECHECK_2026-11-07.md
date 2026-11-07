# Системные уведомления субагентов — 7 ноября 2026

## Что проверяется

После привязки запросов к исходному запуску оставалась непроверенной системная
часть Android. SubagentNotificationTest использует реальные NotificationManager,
PendingIntent и зарегистрированный SubagentActionReceiver, без Activity,
ViewModel, моделей или запросов к ПК.

1. Опубликовать уведомление A и сохранить его кнопку отмены. Опубликовать B;
   PendingIntent должны отличаться. Выполнить старую кнопку A: receiver должен
   получить ID A, а уведомление B остаться видимым. Затем новая кнопка должна
   передать ID B. Receiver не скрывает уведомление до проверки владельцем.
2. Отправить зарегистрированному receiver настоящее действие с пустым ID:
   событие не поступает, текущее уведомление сохраняется.

Тесты ограничены временем, создают случайные QA-ID, отменяют собственные
PendingIntent и уведомление в finally. Запуск разрешён на принадлежащем задаче
AVD Codex_Reference_UI. Разрешение уведомлений фиксируется до проверки и
восстанавливается снаружи после завершения instrumentation, поскольку отзыв
разрешения внутри теста может завершить процесс тестируемого приложения.

## Границы

Это системная проверка доставки PendingIntent/receiver, а не выполнение отмены
реальной модели или задачи ПК. Проверка совпадения ID и запрета HTTP-редиректа
описана в SWARM_ORIGIN_RECHECK_2026-11-07.md. Полный Android ViewModel и
восстановление после уничтожения процесса здесь не исполняются.
Физический телефон этой проверкой не заменяется. Внешний вид панели не менялся.

## Результат

Сборка `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
**BUILD SUCCESSFUL in 1m 10s**. 285 unit-тестов остаются зелёными (задача
UP-TO-DATE, production-код после предыдущей сборки не менялся). Собран новый
Android test APK; зависимости проекта не добавлялись.

На Codex_Reference_UI установлен текущий APK и тестовый пакет. Команда:

```
adb -s emulator-5560 shell am instrument -w -r -e class com.google.antigravity.SubagentNotificationTest com.google.antigravity.test/androidx.test.runner.AndroidJUnitRunner
```

**OK (2 tests), 1.499 s**. Оба INSTRUMENTATION_STATUS_CODE=0; итоговый
INSTRUMENTATION_CODE=-1 — штатный RESULT_OK runner, не ошибка. Старый и новый
PendingIntent передали свои ID, уведомление B осталось зарегистрированным в
NotificationManager после обоих действий receiver. Пустой ID не создал события
за 800 мс и не удалил текущее уведомление.

Выполнялся настоящий PendingIntent.send через Android; жест на экране шторки,
полный ViewModel и реальный cancel HTTP не тестировались. Обработка самим
receiver подтверждена, полноценная сквозная отмена остаётся отдельным сценарием.

При холодном старте AVD показал известное «System UI isn't responding». Окно
закрыто до instrumentation. Crash buffer после тестов пуст. Исходное разрешение
уведомлений восстановлено и сверено; cmd notification list не содержит QA-4204.
AVD остановлен после проверки имени. Устройство пользователя не использовалось.

Логи: `uploads/notification-native-build.log`,
`uploads/notification-native-instrumentation.log`,
`uploads/notification-native-cleanup.json`, `uploads/notification-native-crash.log`.
Разрешения до/после: `uploads/notification-native-permissions-{before,after}.txt`.

SHA-256 проверенного APK:
`4BCD2F55748017C3CE9D25C54FAEEDD5EE7C025535E627F0D63B4F7D86ECF2B5` —
совпадает с предыдущей локальной сборкой swarm-origin, приложение в этом проходе
не менялось. Сохранён test APK `uploads/design-recheck/Antigravity-notification-tests.apk`,
SHA-256 `3A68F6A5DB03C0DF028CBBDF015C1E4DDF15DCD65A8624F31250C182F24EA8A1`.
Это тестовый пакет, не самостоятельная пользовательская версия приложения.

Новые файлы — Android-тест и этот отчёт; обновлена ссылка на проверку в UX-CONTRACT.
Новый commit/push/релиз в этом проходе не выполнялись. Node-код не менялся,
Node-тесты повторно не запускались.
