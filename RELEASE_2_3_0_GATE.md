# Release 2.3.0 — проверка публикации

Состояние 08.11.2026 перед отправкой коммита. Пользователь разрешил публикацию.
Локальные блокеры закрыты; публикация GitHub Release выполняется только после
независимой проверки подписанных APK в CI.

| Проверка | Статус | Основание |
|---|---|---|
| main/remote перед коммитом | PASS | 0a364a9b27817671247042c804842babdd64fc1f; проверено API и SSH |
| Версия | PASS | 2.3.0 / 20261109 / com.google.antigravity, aapt на Release |
| Debug/Release JVM | PASS | по 344 теста, failures/errors 0 |
| lintDebug / lintRelease | PASS | оба XML-отчёта: 0 Error |
| assembleDebug / assembleRelease / assembleDebugAndroidTest | PASS | uploads/release-230-final-build.log, SUCCESS 9m16s |
| Node | PASS | uploads/release-230-node-publish.log, 262/262 |
| Проверка release validator | PASS | scripts/test_release_artifacts.py, 2/2 |
| Доступ владельца к PC-Bridge | PASS | guard первым в server.js; реальные HTTP тесты отказа, PIN, ticket, cookie/CSRF; без loopback bypass |
| Android pairing + backend + voice HTTP + media + WebView | PASS | BridgePairingIntegrationTest на Codex_Reference_UI |
| Прямой OpenRouter на Android | PASS | 3 image + 4 text native теста; вместе с pairing OK (8 tests), 8.955s |
| LAN на физическом телефоне | CODE/JVM PASS | emulator=false сохраняет выбранный LAN, не добавляет emulator defaults; физический телефон не подключался |
| Экран подключения | INSPECTED | UI dump + uploads/release-230-pairing-settings.png; поля, кнопка и пояснения без перекрытий; UI-нажатие pairing отдельно не автоматизировано |
| Подписи APK | PASS | Release 90709e19…e011f совпадает с Release 2.1.1; Debug 8860fb88…4e06 совпадает с локальными preview |
| Секреты | PASS | 107 исходных файлов + распакованное содержимое обоих APK: реальных значений конфигурации, API-ключей и PEM private material не найдено |
| Транспорт | PASS | только два APK на 127.0.0.1:8818; HTTPS HEAD возвращает APK Content-Type и точный размер; временный туннель закрывается после CI |
| Сравнение с независимым CI | REQUIRED AUTOMATIC GATE | весь ZIP payload кроме v1 signature metadata, SHA256, сертификат, пакет, версия; до этого Publish step не выполняется |

## Подписанные файлы

- Release SHA256: `62c189e64102584ed2b07a9d3b9107aed669209c3d57880bc1f0f20dbec20bb0`
- Debug SHA256: `c02c610f2bbb31114cfda879ae8b20de19934fec8df17c0bc2a4d8c3d4d8d891`

## Ограничения и сохранность данных

GitHub Actions Debug 2.2.0 имел другой ключ: обновление поверх него этими APK
Android не разрешит. Удаление приложения очищает данные; бесшовная смена подписи
не обещается. Ключи подписи на GitHub не передаются.

На холодной загрузке AVD повторился ANR процесса System UI. Диалог закрыт,
приложение перезапущено; native-проверки затем прошли. Это не доказательство
поведения физического телефона. Live коммерческие генерации, качество голоса,
полный Google OAuth и все сценарии интерфейса в этот шаг не проверялись.
Voice-тест проверяет авторизацию HTTP и получение fixture-байтов, не качество MP3.

Чужой изменённый pc-bridge/openrouter_models_cache.json, runtime state, локальные
конфиги/ключи и пользовательские reference-вложения исключены из коммита.
Подробности: PC_BRIDGE_PAIRING.md, BRIDGE_ACCESS_RELEASE_RECHECK_2026-11-08.md и
остальные RECHECK-документы. Полная сверка проекта с референсами не завершена.
