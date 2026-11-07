# Доступ владельца и релиз 2.3.0 — 08.11.2026

## Закрываемая проблема

Ранее маршруты server.js позволяли неаутентифицированному клиенту менять конфиг,
разрешения, запускать генерацию и читать сессии/файлы. Tool permissions не
аутентифицируют HTTP-клиента. Новый createBridgeHttpAccess стоит до всех private
handlers; loopback не имеет обхода, поскольку публичные SSH-туннели тоже приходят
с loopback. Без сопряжения доступны только минимальный health, обмен PIN и
OAuth callback с собственным state.

## Реализация

- BridgeAccess: случайный 256-битный токен владельца в локальном файле вне Git,
  одноразовый PIN на 5 минут/5 попыток, browser ticket 30 секунд, cookie на час.
- Android: AES-GCM через Android Keystore с AAD точного origin; в prefs лежит
  ciphertext. Токен отправляется только совпадающему scheme/host/port, redirects
  клиентов выключены; публичный HTTP не допускается для сопряжения.
- Подключены backend-клиенты, voice HTTP и HttpURLConnection загрузок. Для
  авторизованной загрузки redirects запрещены, токен не переходит CDN/другому ПК.
- Настройки используют существующие SettingsGroup/OutlinedTextField/Button.
  PIN не сохраняется в rememberSaveable или в историю и очищается при отправке.
- Веб-консоль получает короткий одноразовый билет, после редиректа билет убирается
  из URL; постоянный токен в URL не передаётся. Cookie HttpOnly/SameSite=Strict,
  Secure при HTTPS; изменяющие cookie-запросы требуют same-origin Origin.
- Фоновый ответ открытия веб-сессии не должен заменять уже выбранный другой ПК
  или Google-вкладку. Счётчик поколения запроса отбрасывает устаревший результат.
- Google Drive callback получил одноразовый state с expiry. MCP callback
  сохраняет существующие state/PKCE проверки. Live Google OAuth в этом шаге
  не выполнялся: это ограничение проверки, не заявление о пройденном входе.
- Session ID безопасно сериализуется в inline JS веб-консоли, не закрывая script.
- Локальные watchdog и tunnel health перешли на минимальный /api/health.
- `pc-bridge/owner-access.cjs pair|web` читает секрет локально и показывает только
  PIN либо краткоживущий browser URL. Инструкция: PC_BRIDGE_PAIRING.md.

## Выполненные проверки

Node: `uploads/release-230-node-final2.log`, 262/262. Реальный HTTP fixture
проверил запрет неавторизованного доступа к конфигу, разрешениям, paid route,
статусу и медиа; одноразовый PIN/ticket, cookie и cross-origin запреты. Отдельно
проверены PKCE-потоки существующего MCP, сохранение ID видео и release validator.
Первый прогон Node выявил устаревшую release.json и отсутствие GITHUB_REF в
тестовом окружении workflow; manifest обновлён на 2.3.0, fixtures дополнены
main/tag состояниями. Проверки неправильной версии и чужого тега сохранены.

Первый полный Android build: `uploads/release-230-auth-build.log`, SUCCESS,
10m21s; Debug/Release по 342 JVM-теста и оба lint без ошибок. Второй финальный
build после правок stale web response и refresh статуса выполняется отдельно.
Native pairing/voice/download/WebView запускается только на собственном
Codex_Reference_UI, с отдельным защищённым HTTP fixture на 8819/8820.
Итоги дописываются после фактического запуска.

## Публикация

Два локально подписанных APK передаются через временный read-only transport,
который обслуживает только файлы из списка SHA, без directory listing и ключей.
CI самостоятельно собирает Debug и unsigned Release и сравнивает полный ZIP
payload с подписанными APK, затем SHA, сертификат, package/version. Четыре
текстовых ассета имели CRLF только в Windows working tree; .gitattributes
фиксирует LF, чтобы payload сравнивался одинаково в Windows и Linux.

Ключи подписи не передаются в CI. Release сохраняет signer 2.1.1, отдельный
локальный Debug сохраняет signer preview. Старый CI Debug 2.2.0 имеет другой
сертификат; обновление поверх него не обещается. Перед коммитом кандидатный
список исходников проверен на реальные значения локально настроенных секретов
и явные API/private-key шаблоны: совпадений нет. Незавершённые чужие runtime/cache
изменения, пользовательские вложения, локальные ключи и конфиги не публикуются.

Это проверка конкретного выпуска; живые коммерческие модели/голос и все сценарии
физического телефона этим не покрываются. SVG/reference image editing и
восстановление синхронной картинки после смерти процесса остаются ограничениями.

## Финальная защита исходящих запросов

BridgeAuthInterceptor отклоняет частные /api/* запросы до сети, если точный
origin не сопряжён. OpenRouter /api/v1/* сохраняет прямой маршрут без токена
моста. Это предотвращает отправку текста и ключей на прежний адрес туннеля.
JVM проверяет отсутствие вызова транспорта, а native — отказ после forget.
Старые протокольные QA fixtures получают доверие только через AndroidTest
LocalBridgeFixtureRule: фиксированные loopback-порты собственного эмулятора,
с восстановлением прежних значений в finally. Production bypass не добавлен.
Финальная повторная сборка: uploads/release-230-auth-guard-build.log.

## Адрес ПК на физическом телефоне

Убрана старая привязка LAN-маршрута к isEmulatorDevice: при выключенном
«только через интернет» явно указанный адрес локальной сети сохраняется и
на физическом телефоне. Эмуляторные 10.0.2.2/127.0.0.1 defaults на телефон
не добавляются. В режиме public-only разрешены только HTTPS адреса вне
локальных IP-диапазонов. Проверяется host URL, а не совпадения в подстроках
имени. Голосовой TTS использует тот же порядок: выбранный ПК первым.
Регрессии для emulator=false, LAN 10/172/192 и домена с похожим текстом
проверяются BridgeUrlPriorityTest. Нативная QA остаётся на AVD: физический
телефон в этом шаге не подключался. Финальный build — release-230-final-build.log.

## Итоговая проверка готовых APK

release-230-final-build.log: SUCCESS 9m16s, Debug/Release по 344 JVM, оба lint
0 ошибок, оба APK и AndroidTest APK собраны. release-230-node-publish.log:
262/262, Python release validator 2/2. release-230-native.log: OK (8 tests),
8.955s; pairing/Keystore, backend status, voice HTTP, controlled media download,
настоящий Android WebView (303 → HttpOnly cookie → JS fetch status), запрет
непарного origin и direct OpenRouter 3 image + 4 text. Контролируемые API
ответы не являются live коммерческой генерацией.

Холодный AVD показал System UI ANR; после закрытия диалога и перезапуска
приложения все проверки прошли. Установленная финальная сборка также открыта
через UI: Настройки → Подключение к ПК, screenshot и дерево элементов
uploads/release-230-pairing-settings.png / design-qa-current.xml. Сопряжение
нажатием UI-кнопки отдельно не автоматизировалось; клиент проверен native.

APK и 107 кандидатных исходников проверены на реальные локальные секреты и
явные шаблоны API/private-key material: совпадений 0. Версия/сертификаты
проверены apksigner и aapt; точные SHA записаны в RELEASE_2_3_0_GATE.md.
Независимое сравнение payload и публикация остаются обязательными CI-шагами.
