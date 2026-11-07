# Прямые изображения OpenRouter — 08.11.2026

## Реализация

`DirectOpenRouterImageClient` выполняет генерацию на телефоне без PC-Bridge.
Режим ПК сохраняет существующий bridge pipeline. Ключ телефона и модель
фиксируются при старте запроса; отсутствие ключа не вызывает поиск моста.

Discovery: GET канонического `/api/v1/images/models`, точное совпадение ID.
Для модели Image API отправляется один POST `/api/v1/images` с model/prompt/n=1.
Для известной модели с image output, отсутствующей в отдельном каталоге,
используется один POST `/api/v1/chat/completions` с modalities image/text.
Смена endpoint после платного запроса, подмена модели и автоматический повтор
не выполняются. Используются [официальный Image API](https://openrouter.ai/docs/guides/overview/multimodal/image-generation)
и [описание двух путей генерации](https://openrouter.ai/collections/image-models/).

Ответы: data[0].b64_json либо message.images[0].image_url.url с data URI.
Внешние URL из ответа не загружаются. Заголовок Authorization передаётся только
каноническим HTTPS endpoint; redirects выключены. HTTP/error-envelope дают
собственные сообщения приложения без сырого ответа провайдера или ключа.

Файл проверяется по сигнатуре и bounds-декодеру Android. Поддерживаются PNG,
JPEG и WebP, максимум 20 МБ, стороны не больше 32768 и не больше 100 млн пикселей.
JSON ограничен 4 МБ для каталога и 32 МБ для результата, timeout 180 секунд.
Проверенные байты пишутся через .part/fsync/atomic move в filesDir/generated_media.
Формат, расширение, размеры и размер файла соответствуют реальным данным.
mediaUrl пустой, localPath/localPhonePath указывают на локальный файл; выдуманной
общедоступной ссылки нет. Существующая карточка GeneratedMediaCard показывает
локальное изображение и использует FileProvider для открытия.

После получения полного результата запись файла и callback привязки к исходному
чату завершаются в NonCancellable. VM сохраняет результат через commitMediaMessage,
по ID чата, захваченному при запуске. Временный чат не попадает в ChatSessionStore.

## Проверки

Добавлены 8 JVM-тестов: выделенный Image API, chat-image API, отсутствие модели,
HTTP 402 с редактированием ошибки, redirects/206/429, error envelope/битый base64,
отказ от внешнего URL и потеря ответа без нового POST. Bounds-декодер в JVM
подменяется; настоящий Android-декодер проверяется отдельно на AVD.

Первый JVM-прогон: 340 тестов, 0 failures/errors. Сборка и Android-проверки ещё
выполняются; итоги будут дописаны после исполнения.

Добавлены native-сценарии полного AntigravityViewModel: сохранение PNG в истории
телефона с недоступным ПК, временный чат без записи истории и смена чата до
получения результата. API-транспорт использует тестовый PNG 1×1, сгенерированный
Pillow; проверяются декодирование BitmapFactory, реальные байты файла и наличие
в ChatSessionStore. Платная генерация и качество коммерческой модели этим
не проверяются. Пользовательский телефон и реальные ключи не используются.

## Границы и продолжение

- Это генерация одного растрового изображения по тексту. Редактирование по
  reference-вложениям, SVG/GIF и расширенные параметры качества не реализованы.
- Изображения, самопроизвольно возвращённые обычной text+image чат-моделью в
  текстовом ходе, этим отдельным pipeline пока не обрабатываются.
- При смерти процесса до получения синхронного ответа продолжить запрос по ID
  нельзя: автоматически покупать новую генерацию приложение не пытается.
- Хранение — приватная память приложения, не автоматическая публикация в галерею.
- Для отключения прямого клиента выберите режим ПК; он использует прежний путь.
  Не включайте скрытый fallback на ПК в режиме телефона.
- GitHub release по-прежнему закрыт незавершённой авторизацией PC-Bridge:
  `RELEASE_2_3_0_GATE.md`. Этот шаг не означает завершение общего аудита проекта.

## Первый Android-прогон и исправление теста

`uploads/direct-image-native.log`: 7 сценариев, 4 text regression PASS, 3 image
сценария остановились на неверном сравнении raw localPhonePath с canonicalPath.
Android использует алиасы /data/user/0 и /data/data для одного файла. Исправлено
сравнение File(localPhonePath).canonicalFile с canonicalFile результата;
проверка принадлежности папке generated_media оставлена. Production-код не менялся.
`uploads/direct-image-native-rebuild.log`: assembleDebugAndroidTest SUCCESS, 40 с.
Полная сборка: `uploads/direct-image-build.log`, SUCCESS, 5m29s; lint 0 ошибок,
190 warnings и 1 information. Повторный native-прогон выполняется отдельно.

## Итог

- `uploads/direct-image-native-settled.log`: OK (7 tests), 1.775 с; 3 image
  сценария и 4 text regression. Использован настоящий Android bounds/full decoder,
  файл PNG на устройстве и ChatSessionStore, API-ответы были тестовыми.
- После холодной загрузки AVD одна попытка старта instrumentation завершилась
  `Process crashed` до тестов. `dumpsys activity exit-info` зафиксировал
  BIND_APPLICATION_ANR; одновременно наблюдался System UI ANR. Отдельный cold
  запуск MainActivity затем прошёл за 4003 мс. После закрытия системного диалога
  повторный прогон прошёл. Это не доказательство отсутствия проблем холодного
  старта на настоящем телефоне; наблюдение оставлено для дальнейшей проверки.
  Логи: `direct-image-native-final.log`, `direct-image-native-logcat.log`,
  `direct-image-exit-info.log`; crash buffer был пуст.
- Dummy-ключ очищен публичным setter и проверкой prefs; созданные тестовые
  изображения удалены с проверкой canonical parent. Пользовательские устройства
  не затрагивались. AVD Codex_Reference_UI остановлен.
- APK: `uploads/design-recheck/Antigravity-direct-images-preview.apk`.
  SHA256: `18752C9AE5231909120F5AFBB36E78B489F42F4C448E3E3952E1761B08D8061D`.
  Version 2.3.0 / 20261109; Debug signer SHA256:
  `8860fb8826b18b18128e90ae754e7571175e6cb4f4471fda639c0789eea04e06`.
- GitHub публикация не выполнялась; критический backend gate остаётся открытым.
