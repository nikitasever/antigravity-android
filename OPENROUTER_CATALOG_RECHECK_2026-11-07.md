# Полный каталог OpenRouter — 7 ноября 2026

## Исправление

Оба пути discovery — PC-Bridge и прямой Android — объединяют три публичных
источника: `/api/v1/models`, `/api/v1/images/models`, `/api/v1/videos/models`.
Общие ID не дублируются. Общий каталог сохраняет контекст и текстовые возможности;
dedicated endpoints добавляют возможности генерации и исходные ограничения.
В `generationCapabilities` сохраняются параметры изображений, длительности,
разрешения, aspect ratio и pricing SKUs видео, если они есть в ответе API.

У standalone-генератора не придумывается контекст 128K: при отсутствии токенного
контекста записывается 0. Нулевые токенные цены общей модели не делают её
генерацию изображений/видео бесплатной. Для медиа с неизвестным тарифом выводится
«Тариф OpenRouter»; точные условия сохранены в generationCapabilities.

В Android исправлен путь через мост: заполняются isImageGen/isVideoGen/isVision/
isAudio/isReasoning, priceLabel и generationCapabilities. Ранее часть полей
оставалась значениями по умолчанию. Локальный кэш сохраняет эти поля, включая
модальность и аудио, и заменяется атомарно через временный файл.

## Частичные ответы и кэш

- PC-Bridge хранит исходные ответы по каждому источнику отдельно в новом
  `pc-bridge/openrouter-catalog-v2.json` (локальный, исключён из git).
- Параллельные обновления делят один набор из трёх запросов. Полный свежий кэш
  используется до 10 минут, затем запрашивается API.
- Отказ одного источника сохраняет его последний успешный ответ, не мешая
  обновлению других. Результат помечается partial/failedSources/fromCache.
- Старые классифицированные JSON без новой схемы не считаются полным актуальным
  discovery. Если сеть полностью недоступна, сервер ещё может вернуть старый
  список как legacy-cache с catalogVersion=1 и partial=true.
- При частичном ответе Android сохраняет ранее известные модели и возможности
  в памяти и не перезаписывает последний полный дисковый кэш. Подпись указывает
  частичную загрузку; принудительное обновление не снимает fromCache ошибочно.
- На телефоне убраны фиксированные 452+ и выдуманное время синхронизации из
  обновлённых подписей/подсказки команды; фактический счётчик берётся из списка.

Исходный `pc-bridge/openrouter_models_cache.json`, содержавший чужие локальные
правки, этим проходом не перезаписывался. При rollback вернуть прежнюю реализацию
fetchLiveOpenRouterModels и прямого discovery Android; старые кэши сохранены.
Удаление нового v2-кэша заставит повторить discovery, не запускает генерацию.

## Проверка живого API на ПК

Тот же новый модуль и настоящая classifyOpenRouterModel из server.js выполнили
три публичных GET без ключа и без платных запросов. На момент проверки:

- общий источник: 460 моделей;
- dedicated images: 63;
- dedicated videos: 33;
- после объединения: 544 уникальных ID, image output у 66, video output у 33;
- повторное чтение и создание нового экземпляра клиента восстановили список
  и возможности из кэша без дополнительных HTTP-запросов (всего 3).

Различие 66/63 объясняется объединением общего и dedicated каталогов; проверка
не ограничивает общий набор только списком dedicated API. Числа не зашиты в код
или тесты и могут меняться. Доказательства: uploads/openrouter-live-catalog-v2.json
и uploads/openrouter-live-catalog-summary.json.

## Границы

Это работа с каталогом и контрактами, не реализация video job flow. Видео в
приложении по-прежнему требует отдельной интеграции POST → job ID → poll → download.
Ранее живой запрос изображения получил 402 от OpenRouter; этот проход не повторяет
платную генерацию. Тесты каталога не доказывают работу SSE, инструментов, аудио,
сохранения готового медиа или восстановления фоновых задач после смерти процесса.

Прямой Android discovery при холодном старте и частично недоступной сети использует
последний сохранённый список; отдельно исходные ответы каждого API на диске Android
не хранятся. Консервативное объединение частичного списка может временно сохранять
устаревшую возможность до следующего полного обновления и помечено как partial.

Официальные описания discovery:
[Images API](https://openrouter.ai/docs/guides/overview/multimodal/image-generation),
[Videos API](https://openrouter.ai/docs/guides/overview/multimodal/video-generation).

## Итоговые проверки

- `testDebugUnitTest lintDebug assembleDebug`: BUILD SUCCESSFUL, 5m 15s;
  300 JVM-тестов, 0 ошибок; lint: 0 errors, 190 warnings.
- `npm --prefix pc-bridge test`: 237/237 прошли, 34.252 секунды.
- 14 целевых Node-проверок merge/cache/обработчиков также прошли. Проверяются
  дубликаты и порядок источников, сохранение параметров, отказ одного/всех API,
  перезапуск кэша, старая схема и совместное выполнение конкурентных обновлений.
- Android-тест прямого discovery: OK (1 test), 11.644 секунды на Codex_Reference_UI.
  В лог попало `models=544, imageApi=63, videoApi=33, partial=false`.
  Проверены уникальность ID, флаги генерации, сохранение supported_durations и
  отсутствие выдуманного контекста у dedicated video-only моделей.
- Для выбора прямого Android-пути локальный QA-мост вернул пустой models[].
  После этого настоящий Android HTTP-клиент обратился к трём публичным API.
  Это не тест недоступного/старого моста, не UI-клик и не генерация медиа.
- В тесте не создаётся ViewModel, не меняются ключи/настройки, не запускается
  платная модель. На обоих QA runtime started=0; crash buffer пуст.
- Стендовый Node-процесс и принадлежащий задаче AVD остановлены, reverse 8810 снят.
- `node --check pc-bridge/server.js` и `git diff --check` прошли.

До запуска теста исправлена его сигнатура: выражение runBlocking первоначально
вывело Int из Log.i, что не подходит JUnit. После явного Unit байткод проверен
через javap (public final void). Окончательные assembleDebugAndroidTest/lintDebug
прошли за 59s. На холодной загрузке AVD закрыт знакомый ANR System UI до теста;
само приложение в crash buffer не отмечено.

Логи в uploads: openrouter-catalog-android-build.log,
openrouter-catalog-native-final-build.log, openrouter-catalog-node-tests.log,
openrouter-catalog-native-test.log, openrouter-catalog-native-summary.log,
openrouter-catalog-native-crash.log.

Для повторения Android-проверки на отдельном AVD:

```powershell
node pc-bridge/test/fixtures/notification-cancel-bridge.cjs
adb -s emulator-5560 reverse tcp:8810 tcp:8810
adb -s emulator-5560 shell am instrument -w -r -e class com.google.antigravity.OpenRouterCatalogIntegrationTest com.google.antigravity.test/androidx.test.runner.AndroidJUnitRunner
```

Нужны debug/androidTest APK, свободные порты 8810/8811 и публичный интернет.
Ключ OpenRouter не нужен. После теста остановить собственный fixture-процесс,
снять reverse и остановить QA-эмулятор. На личном телефоне тест не запускается.

APK: uploads/design-recheck/Antigravity-catalog-preview.apk, SHA-256:
`5D6733B42D692000349BE18C826F608BD0F74F9E750B1BB5B75A4B79BEC286CF`.
Изменения пока локальные, новый коммит, push и релиз не выполнялись.

Остаются отдельные проверки старого неполного моста и поведения при разных
комбинациях сетевых отказов на Android, round-trip кэша через настоящий ViewModel,
отображения контекста 0 и начальных подписей источника в UI. Полноценная визуальная
сверка с ChatGPT/Claude этим сетевым тестом не заменяется.
