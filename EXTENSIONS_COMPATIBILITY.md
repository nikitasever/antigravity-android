# Совместимость расширений Claude и ChatGPT — Этап 1

Дата: 2 ноября 2026. Статус: **инвентаризация завершена (Этап 1)**, адаптация не выполнялась.
Машинный источник: [`extensions-inventory.json`](extensions-inventory.json) — 79 позиций, 16 обязательных полей у каждой (проверено формально: пропусков полей и дублей id нет).
План: [`EXTENSIONS_INTEGRATION_PLAN.md`](EXTENSIONS_INTEGRATION_PLAN.md), раздел «Этап 1».

**Правило данных:** все значения ниже взяты только из реальных файлов проекта и дампов (перечень источников S1–S14 в §2). Где данных нет — стоит «не записано»/«не зафиксировано»/«не проверено». Домыслов и переноса чужих описаний в инвентарь нет.

## 1. Поля каждой позиции

| Поле | Значение в этом документе |
|---|---|
| sourceProduct | источник: claude.ai (web), Claude for Android 1.261029.20, ChatGPT for Android 1.2026.293, Codex (Remote/bundled), Antigravity локально — продукты не смешиваются (план §2) |
| sourceId | стабильный идентификатор в пределах источника (вкладка/карточка/строка ресурса/путь) |
| publisher | издатель, если записан; иначе «не записано» |
| sourceUrl | URL или путь-происхождение; «n/a» для локальных файлов |
| version | версия пакета/строки-контейнера; «не записано», если каталог её не показывал |
| observedAt | дата наблюдения |
| componentTypes | типы: catalog-tab/card/page/category/view, connector, skill, plugin, mcp-server, app, gpt, entry-point, builtin-toggle и др. |
| dependencies | зависимости, прямо указанные в файле/строке |
| auth | требование входа/разрешений по свидетельству |
| executionTargets | где выполняется по свидетельству; «не проверено» = в Этапе 1 вызовов не было |
| importPath | реальный путь импорта либо n/a |
| license | лицензия, если зафиксирована в файле/дампе |
| implementationStatus | инвентаризация (не перенесено) / локально установлено / импортировано / заблокировано тарифом / n/a (встроенное) |
| blocker | конкретная причина, почему позиция ещё не может считаться перенесённой |
| evidence | файл + строка/идентификатор ресурса/хэш; в этом документе сгруппировано по разделам строками «evidence (общее для §N)», построчно — в JSON |
| testScenario | что проверить при переносе |

Поле `evidence` у всех 79 позиций есть построчно в `extensions-inventory.json` и сгруппировано ниже строками «evidence (общее для §N)»; поле `testScenario` продублировано построчно в таблицах §5–§9 и в JSON.

Дополнительно: `availability` (visible / installed / imported / subscription-gated / builtin / unknown) и `observationType` (live-observation / accessibility-dump / apk-string / filesystem / cli / code).

## 2. Источники данных (S1–S14)

| ID | Источник | Дата | Что покрывает | Границы |
|---|---|---|---|---|
| S1 | `EXTENSIONS_INTEGRATION_PLAN.md:24-34` | 2026-11-02 | живой просмотр Claude: Customize → Connectors/Skills/Plugins, Yours/Discover, поиск, фильтры, сортировка, категории, 12 названных карточек | полного списка нет («и других пакетов»); вкладка каждой карточки и установка не фиксировались |
| S2 | `verification_screenshots/claude_web_*.json` (13 дампов) + PNG | 2026-11-01 | навигация, меню плюса, Chat/Cowork, Code Upgrade → тарифы | дампы меню, не списки карточек каталога |
| S3 | `CLAUDE_WEB_REFERENCE_CHECK.md` | 2026-11-01 | протокол живого прогона (Free, Haiku 5.5 Medium) | вход/настройки не менялись |
| S4 | `…/reference-analysis/claude/resources/res/values/strings.xml` (673 098 байт) | 2026-11-01 | коннекторы, MCP, навыки, единственная метка Plugin | строки доказывают UI, не каталог и не поведение |
| S5 | `scratch/targets/chatgpt_base_strings.txt` (10 111 строк) | 2026-10-31 | каталог плагинов, категории, разрешения, коннекторы, навыки, GPTs, блок Remote/Codex | нет ни одной карточки с именем/версией |
| S6 | `chatgpt_badging.txt`, `claude_badging.txt` | 2026-10-31 | versionName/versionCode, разрешения | только контейнер приложения |
| S7 | `REFERENCE_EVIDENCE.md` | 2026-11-01 | SHA-256 APK: Claude `9d3c3459…`, ChatGPT `53d6bb92…` | JADX как провайдер не настроен |
| S8 | `~/.gemini/config` (skills, plugins, `mcp_config.json`, `import_manifest.json`, `config.json`) | 2026-11-02 | установленные навыки/плагины, состояние включённости | локальная установка Antigravity, не счёт источников |
| S9 | `~/.gemini/antigravity/builtin/skills` (9 навыков) | 2026-11-02 | встроенные навыки | лицензия в файлах не указана |
| S10 | `uploads/SKILL.md`, `uploads/plugin-staging/*/plugin.json` | 2026-11-02 | пользовательские импорты | корень `.agents/skills` объявлен в коде, но на диске отсутствует |
| S11 | `pc-bridge/server.js`, `mcp_sysinfo_server.js`, `test/extensions-stage0.test.js` | 2026-11-02 | какие корни и CLI читаются после Этапа 0 | код описывает механизм, а не содержимое каталогов |
| S12 | `agy plugin list --json`, `agy mcp list` (запуск) | 2026-11-02 | фактически установленные плагины и MCP | handshake MCP не выполнялся |
| S13 | `app/src/main/assets/spaces/*` | 2026-11-02 | ассеты bundled-plugin Codex «visualize» | копия ассетов, не установленное расширение |
| S14 | `AntigravityBackendClient.kt`, `AntigravityModels.kt` | 2026-11-02 | поверхность `/api/plugins`, `/api/skills`, `/api/mcp` | модели не содержат полей совместимости/состава |

## 3. Охват и ограничения

- **Полный доступный срез учтён: 79 позиций.** Распределение по источникам: claude.ai (web) — 24, Claude Android — 10, ChatGPT Android — 22, Codex (Remote) — 1, Codex bundled-ассеты — 1, локальная установка Antigravity — 21.
- **Различение статусов:** visible — 20, installed — 18, imported — 4, subscription-gated — 1, builtin — 2, unknown — 34.
- **unknown = 34** — это позиции, чье существование доказано дампом APK, но которые не открывались вживую: это честный ответ на ограничение плана («каталог ChatGPT вживую не просмотрен»).
- Неполнота первичных каталогов не скрыта: у Claude названо 12 карточек из «…и других пакетов»; у ChatGPT нет ни одной карточки с названием и версией. Это ограничение охвата, а не пропуск позиции — обе ситуации записаны в `coverageLimitations` и в блокерах соответствующих позиций.
- Версии пакетов каталогов, лицензии и право распространения в дампах отсутствуют → поля `version`/`license` честно «не записано/не зафиксировано».
- Ни один MCP-сервер и ни один навык источников в Этапе 1 не запускались: `executionTargets` для них = «не проверено».
- Исключённые с объяснением записи: Notion (только имя ресурса), Slack/Teams (десктопный/Work-контекст), Astra/Daybreak/Codex-лимиты (внутренние функции), маскот `reference-characters.json: id "claude"`, скриншоты интерфейса, состояние Connected в аккаунтах (дампа нет), содержимое официальных docs (сохранены только ссылки плана).

## 4. Дубли (связаны по хэшу/идентификатору, происхождение сохранено)

| Позиции | Основание | Следствие |
|---|---|---|
| `claude-web-card-figma` ↔ `claude-android-connector-figma` | только имя «Figma»; хэшей нет | одна тема, два источника; идентичность не доказана |
| `local-skill-rigorous-programming-reasoner-disabled` ↔ `local-import-uploads-skill-md` | sha256 `5910a64f…`, 22 531 байт, байт-в-байт | один навык, два пути; uploads/ не является отдельным навыком |
| `local-plugin-antigravity-workspace-guard` ↔ `…-staging-…` | sha256 `e354b9ec…`, 245 байт | один пакет, два пути |
| `local-plugin-kotlin-compose-inspector` ↔ `…-staging-…` | sha256 `1efc79cf…`, 235 байт | один пакет, два пути |

## 5. Позиции: claude.ai (web) — 24

### 5.1 Идентичность

| id | Имя | sourceId | publisher | sourceUrl | version | observedAt | componentTypes |
|---|---|---|---|---|---|---|---|
| claude-web-catalog-connectors | Каталог: вкладка Connectors | claude-web:tab:connectors | Anthropic | не записано (Настройки → Customize) | не записано | 2026-11-02 | catalog-tab |
| claude-web-catalog-skills | Каталог: вкладка Skills | claude-web:tab:skills | Anthropic | не записано | не записано | 2026-11-02 | catalog-tab |
| claude-web-catalog-plugins | Каталог: вкладка Plugins | claude-web:tab:plugins | Anthropic | не записано | не записано | 2026-11-02 | catalog-tab |
| claude-web-card-figma | Figma | claude-web:card:figma | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-desktop-commander | Desktop Commander | claude-web:card:desktop-commander | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-searchfit-seo | SearchFit SEO | claude-web:card:searchfit-seo | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-bright-data | Bright Data | claude-web:card:bright-data | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-zoom | Zoom | claude-web:card:zoom | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-product-tracking | Product Tracking | claude-web:card:product-tracking | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-learn | learn | claude-web:card:learn | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-doc-coauthoring | doc-coauthoring | claude-web:card:doc-coauthoring | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-web-artifacts-builder | web-artifacts-builder | claude-web:card:web-artifacts-builder | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-productivity | Productivity | claude-web:card:productivity | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-marketing | Marketing | claude-web:card:marketing | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-card-finance | Finance | claude-web:card:finance | не записано на карточке | не записано | не записано | 2026-11-02 | catalog-card |
| claude-web-entry-skills | Меню плюса: Skills | claude-web:menu:skills | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | entry-point |
| claude-web-entry-add-connector | Меню плюса: Add connector | claude-web:menu:add-connector | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | entry-point |
| claude-web-entry-add-plugins | Меню плюса: Add plugins | claude-web:menu:add-plugins | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | entry-point |
| claude-web-entry-design-system | Меню плюса: Design system | claude-web:menu:design-system | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | entry-point |
| claude-web-entry-get-apps-extensions | Get apps and extensions | claude-web:link:get-apps-and-extensions | Anthropic | claude.ai (боковая навигация) | n/a | 2026-11-01 | entry-point |
| claude-web-toggle-web-search | Web search (переключатель) | claude-web:toggle:web-search | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | builtin-toggle |
| claude-web-toggle-memory | Memory (переключатель) | claude-web:toggle:memory | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | builtin-toggle |
| claude-web-surface-cowork | Поверхность Cowork | claude-web:surface:cowork | Anthropic | claude.ai (композер) | n/a | 2026-11-01 | surface |
| claude-web-code-upgrade | Code (Code Upgrade) | claude-web:link:code-upgrade | Anthropic | страница тарифов Claude | n/a | 2026-11-01 | entry-point |

### 5.2 Совместимость и статус

| id | availability / installed | dependencies | auth | executionTargets | importPath | license | implementationStatus | blocker | testScenario |
|---|---|---|---|---|---|---|---|---|---|
| claude-web-catalog-connectors | visible / unknown | аккаунт claude.ai (Organization disabled) | сессия claude.ai, вход в коннектор не выполнялся | облако Anthropic; в Antigravity не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | полный список карточек не выгружен; присутствуют Yours/Discover, поиск, фильтры, добавление | открыть вкладку, сверить число и состав карточек, записать каждую с вкладкой и издателем |
| claude-web-catalog-skills | visible / unknown | аккаунт claude.ai | та же сессия | облако Anthropic; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | карточки Skills и Plugins частично совпадают — состав пакета не проверен, дубли не разведены | снять Discover, записать издателя/состав, сверить пересечение с Plugins |
| claude-web-catalog-plugins | visible / unknown | аккаунт claude.ai | та же сессия | облако Anthropic; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | совпадение со Skills не доказывает одинаковый формат пакета | открыть карточку, записать manifest/состав/версию и сравнить со Skills |
| claude-web-card-figma | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | вкладка, категория и состав не зафиксированы; установка не выполнялась | открыть карточку, записать вкладку/издателя/состав, затем импортировать без выдачи новых прав |
| claude-web-card-desktop-commander | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | вкладка/состав не зафиксированы; вероятен скриптовый пакет — Android-исполнитель не проверен | проверить состав (scripts/references) и определить исполнителя до установки |
| claude-web-card-searchfit-seo | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | внешний сервис — наличие переносимого сервера не подтверждено | определить, есть ли публичный endpoint/API, до обещания переноса |
| claude-web-card-bright-data | visible / unknown | не записано | не проверялось (у сервиса ожидается ключ) | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | собственная авторизация поставщика — подключение не проверялось | проверить публичный endpoint и свой вход Antigravity, не перенося ключи поставщика |
| claude-web-card-zoom | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | карточка видима; подключение не менялось | открыть карточку, зафиксировать тип и требуемую авторизацию |
| claude-web-card-product-tracking | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | вкладка/состав/издатель не зафиксированы | открыть карточку и записать состав до импорта |
| claude-web-card-learn | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | короткое имя совпадает с типичным названием категории/навыка — тип не подтверждён | уточнить на карточке: пакет, категория или навык |
| claude-web-card-doc-coauthoring | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | вкладка/состав не зафиксированы; вероятен инструкционный навык | проверить формат (SKILL.md vs manifest) и полный пакет файлов |
| claude-web-card-web-artifacts-builder | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | вкладка/состав не зафиксированы; возможна привязка к артефактам Claude | записать зависимости от продуктовых UI-функций Claude |
| claude-web-card-productivity | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | в плане отнесена к карточкам; вкладка и тип (карточка/категория) не зафиксированы | при повторном проходе уточнить тип |
| claude-web-card-marketing | visible / unknown | не записано | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | в плане отнесена к карточкам; вкладка и тип не зафиксированы | при повторном проходе уточнить тип и состав |
| claude-web-card-finance | visible / unknown | не записано | не проверялось (финансовые данные требуют отдельного входа) | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | в плане отнесена к карточкам; вкладка и тип не зафиксированы | при повторном проходе уточнить тип и требования к доступу |
| claude-web-entry-skills | visible / unknown | меню «Add files, connectors, and more» | сессия claude.ai (Free) | облако Anthropic | n/a | n/a | инвентаризация (не перенесено) | вход наблюдался, содержимое раздела не открывалось | открыть Skills из композера и сверить со вкладкой настроек |
| claude-web-entry-add-connector | visible / unknown | меню композера | сессия claude.ai | облако Anthropic | n/a | n/a | инвентаризация (не перенесено) | форма не открывалась: поля URL/входа не сняты | открыть форму и записать поля для сравнения со строками 0x7f14003f/0x7f14005f |
| claude-web-entry-add-plugins | visible / unknown | меню композера | сессия claude.ai | облако Anthropic | n/a | n/a | инвентаризация (не перенесено) | список за входом не снимался — отсюда неполнота карточек Plugins | выгрузить Discover + Yours полностью и сравнить с вкладкой Plugins |
| claude-web-entry-design-system | visible / unknown | внутренняя функция артефактов Claude | сессия claude.ai | облако Anthropic | n/a | n/a | инвентаризация (не перенесено) | похоже на продуктовую функцию, а не расширение; связь с Figma не проверена | определить, является ли Design system носителем Figma-коннектора |
| claude-web-entry-get-apps-extensions | visible / unknown | боковая навигация Claude | сессия claude.ai | не проверено | n/a | n/a | инвентаризация (не перенесено) | целевая страница не открывалась — состав «apps and extensions» неизвестен | перейти и зафиксировать список приложений/расширений |
| claude-web-toggle-web-search | builtin / n/a | внутренний поиск Anthropic | сессия claude.ai | облако Anthropic | n/a | встроенная функция, не применимо | n/a (встроенная функция, не расширение) | не переносится как расширение; учитывается только как возможность | проверять в Antigravity отдельно, не выдавать за импортированное расширение |
| claude-web-toggle-memory | builtin / n/a | хранилище памяти Anthropic | сессия claude.ai | облако Anthropic | n/a | встроенная функция, не применимо | n/a (встроенная функция, не расширение) | серверная память без переносимого исходника → отдельная реализация через API либо честная зависимость | зафиксировать как встроенную функцию, не как установленное расширение |
| claude-web-surface-cowork | unknown / unknown | отдельный продукт (план §2) | та же сессия claude.ai | облако Anthropic | n/a | не зафиксировано | инвентаризация (не перенесено) | переключатель видим, содержимое Cowork не исследовалось — считать отдельным источником | открыть Cowork и снять отдельный срез, не смешивая с claude.ai |
| claude-web-code-upgrade | subscription-gated / false | платный тариф | аккаунт Claude Free | облако Anthropic | n/a | не зафиксировано | заблокировано тарифом (покупка не выполнялась) | Code Upgrade открыл страницу тарифов; состав Code недоступен на наблюдаемом аккаунте | на платном доступе снять отдельный срез; до этого позиция остаётся gated |

**evidence (общее для §5; построчно — в JSON):** `EXTENSIONS_INTEGRATION_PLAN.md:24-34`; `verification_screenshots/claude_web_tools_ax.json` (menuitem Skills / Add connector / Design system / Add plugins, menuitemcheckbox Web search / Memory, radio Chat/Cowork, link Code Upgrade, link Get apps and extensions); `claude_web_code_ax.json`, `claude_web_code_gate.png`; `CLAUDE_WEB_REFERENCE_CHECK.md:18-20`.

## 6. Позиции: Claude for Android 1.261029.20 — 10

Контейнер: `com.anthropic.claude`, versionName 1.261029.20, versionCode 26102920, SHA-256 `9d3c3459b3b64aa7043acc5a568615cd2064b632baa8d5b5ece18d4f7d888ad2` (S6, S7). Наблюдение: строки ресурсов, 2026-11-01.

| id | Имя / sourceId | publisher | sourceUrl | version | componentTypes | availability / installed |
|---|---|---|---|---|---|---|
| claude-android-connector-custom | Add custom connector (MCP URL) / `0x7f14003f` | Anthropic | APK com.anthropic.claude 1.261029.20 | 1.261029.20 | connector | unknown / false |
| claude-android-connector-catalog | Browse/Search/Discovery / `0x7f140beb` | Anthropic | APK com.anthropic.claude | 1.261029.20 | catalog-page | unknown / unknown |
| claude-android-skills | Навыки Claude / `0x7f14090c` | Anthropic | APK com.anthropic.claude | 1.261029.20 | skill | unknown / unknown |
| claude-android-mcp-servers | MCP-серверы и MCP tools / `0x7f140331` | Anthropic | APK com.anthropic.claude | 1.261029.20 | mcp-server | unknown / unknown |
| claude-android-plugin-label | Plugin (метка) / `0x7f140d64` | Anthropic | APK com.anthropic.claude | 1.261029.20 | plugin | unknown / unknown |
| claude-android-connector-figma | Figma-коннектор / `0x7f140c0e` | Figma (упомянут) | APK com.anthropic.claude | 1.261029.20 | connector | unknown / unknown |
| claude-android-github-app | Claude GitHub App / `0x7f14046c` | Anthropic / GitHub | claude.ai/connect-github | 1.261029.20 | connector | unknown / unknown |
| claude-android-connector-calendar | Calendar / `0x7f1402b5` | Anthropic | APK com.anthropic.claude | 1.261029.20 | connector | unknown / unknown |
| claude-android-connector-google-drive | Google Drive / `0x7f140a2c` | Anthropic / Google | APK com.anthropic.claude | 1.261029.20 | connector | unknown / unknown |
| claude-android-settings-integrations | integrations/mcp/tools/… / `0x7f140e05` | Anthropic | APK com.anthropic.claude | 1.261029.20 | settings-facet | unknown / unknown |

| id | dependencies | auth | executionTargets | importPath | license | implementationStatus | blocker | testScenario |
|---|---|---|---|---|---|---|---|---|
| claude-android-connector-custom | MCP-совместимый сервер на URL; request headers (API key) либо вход на сервер | URL MCP + опциональный sign-in; имена заголовков требуют одобрения Anthropic (`0x7f14004e`) | Android-клиент Claude ↔ сервер поставщика; не подключено | n/a | не зафиксировано в ресурсах APK | инвентаризация (не перенесено) | строка `0x7f14003b` «Adding custom connectors isn't available for your account» — ограничение аккаунта; форма без входа не проверена | с доступным аккаунтом добавить свой MCP, проверить tools/list; отдельно — отказ на ограниченном аккаунте |
| claude-android-connector-catalog | список каталога Anthropic | аккаунт Claude | облако Anthropic | n/a | не зафиксировано | инвентаризация (не перенесено) | карточки каталога в дампе отсутствуют — только элементы интерфейса | открыть Browse connectors и выгрузить карточки с издателями и версиями |
| claude-android-skills | claude.ai как место создания навыков | аккаунт Claude; часть настроек правится только на claude.ai; организация может запретить создание (`0x7f14074c`) | Android-клиент/облако; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | навыки создаются на claude.ai — импорт пакетов SKILL.md в клиенте не подтверждён | создать навык на claude.ai, увидеть в Android, вызвать по /name и проверить ссылки на ресурсы |
| claude-android-mcp-servers | MCP-совместимый сервер; домены/разрешения окружения (`0x7f1403e3`) | через коннектор (URL/вход); отдельный OAuth-флоу MCP не зафиксирован | Android-клиент ↔ удалённый сервер; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | handshake и tools/list не выполнялись; есть выключатели Allow/Turn off MCP, но списка серверов в дампе нет | подключить тестовый MCP: initialize/tools/list/tools/call, проверить «MCP servers not allowed» |
| claude-android-plugin-label | неизвестно | неизвестно | неизвестно | n/a | не зафиксировано | инвентаризация (не перенесено) | только одиночная метка «Plugin»; экран каталога плагинов не найден — нельзя утверждать наличие | найти место показа метки и снять состав вкладки Plugins |
| claude-android-connector-figma | аккаунт Figma для Design/Slides/design system | approval Figma (судя по строке) | Android-клиент ↔ Figma | n/a | не зафиксировано | инвентаризация (не перенесено) | строка говорит об отказе Figma; связь с веб-карточкой только по имени (дубль) | проверить одобрение на вебе и в Android, объединить после подтверждения |
| claude-android-github-app | установка Claude GitHub App в репозитории; доступ к репозиториям/веткам | GitHub sign-in / установка приложения; SSO-сценарии описаны строками | облако Claude + GitHub, облачные сессии | n/a | не зафиксировано | инвентаризация (не перенесено) | привязка к облачным сессиям и репозиториям; в каталоге Connectors не наблюдалась | установить App на тестовый репозиторий, запустить сессию, отдельно зафиксировать место в UI |
| claude-android-connector-calendar | разрешение READ/WRITE_CALENDAR; выбор конкретных календарей | системное разрешение Android + выбор календарей (`0x7f1402ae`) | Android-клиент (локальные календари) | n/a | не зафиксировано | инвентаризация (не перенесено) | поставщик календаря не назван; работа именно на телефоне не проверялась | разрешить один календарь, попросить найти событие, проверить границы доступа |
| claude-android-connector-google-drive | аккаунт Google Drive | не проверялось | не проверено | n/a | не зафиксировано | инвентаризация (не перенесено) | подтверждение косвенное (строка ошибки конвертации) | подключить Drive и открыть файл — проверить конвертацию и доступ |
| claude-android-settings-integrations | экран настроек Claude Android | аккаунт Claude | Android-клиент | n/a | не зафиксировано | инвентаризация (не перенесено) | это ключевые слова поиска по настройкам, а не список установленного; Gmail отдельной строкой не подтверждён | поиском по настройкам открыть каждый раздел и записать, что реально есть |

**evidence (общее для §6; построчно — в JSON):** `…/claude/resources/res/values/strings.xml:43,47,62,79,91,92,95,583-617,722-723,738,916,941-943,1049-1053,1788,1799,1959,1969,2131,2217-2230,2342-2367,2945-3001,3314,3464,3917-3950,5104-5107,5457-5461,5766-5768,6294-6304,6366-6368` + `claude_badging.txt`.

## 7. Позиции: ChatGPT for Android 1.2026.293 — 22

Контейнер: `com.openai.chatgpt`, versionName 1.2026.293, versionCode 2629310, SHA-256 `53d6bb925d7185292ca53cb644deafe7ce9b786df8519424fa37a310c5952297` (S6, S7). Наблюдение: строки ресурсов, 2026-10-31. Живой проход каталога не выполнялся (план §1) → все позиции `availability: unknown`.

| id | Имя / sourceId | publisher | sourceUrl | version | componentTypes |
|---|---|---|---|---|---|
| chatgpt-plugin-directory | Каталог плагинов (Directory) / `#5550` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-page |
| chatgpt-cat-business-analytics | Категория: Business and Analytics / `#5553` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-cat-creativity-design | Категория: Creativity and Design / `#5554` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-cat-developer-tools | Категория: Developer Tools / `#5555` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-cat-messaging-social | Категория: Messaging and Social / `#5556` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-cat-productivity | Категория: Productivity / `#5557` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-cat-research-knowledge | Категория: Research and Knowledge / `#5558` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-category |
| chatgpt-plugin-directory-views | Installed / Created by me / Shared / Workspace / Popular / `#7701` | OpenAI | каталог ChatGPT | 1.2026.293 | catalog-view |
| chatgpt-plugin-detail | Version / Developer / Capabilities / Privacy Policy / `#7682` | OpenAI | карточка плагина | 1.2026.293 | catalog-detail |
| chatgpt-plugin-permissions | Always ask → Allow all, Read/Write actions / `#5527` | OpenAI | настройки плагина (help.openai.com/…/11487775) | 1.2026.293 | permission-model |
| chatgpt-plugin-management | Установка/удаление/счётчики / `#1312` | OpenAI | каталог ChatGPT | 1.2026.293 | management |
| chatgpt-component-app | Компонент App / `#3674` | OpenAI | каталог ChatGPT | 1.2026.293 | app |
| chatgpt-component-skill | Компонент Skill / `#3676` | OpenAI | каталог ChatGPT | 1.2026.293 | skill |
| chatgpt-connectors | Connectors (подключённые аккаунты) / `#5608` | OpenAI | https://help.openai.com/en/articles/11487775-connectors-in-chatgpt | 1.2026.293 | connector |
| chatgpt-connector-gmail | Gmail / `#5547` | OpenAI / Google | каталог коннекторов ChatGPT | 1.2026.293 | connector |
| chatgpt-connector-google-calendar | Google Calendar / `#5548` | OpenAI / Google | каталог коннекторов ChatGPT | 1.2026.293 | connector |
| chatgpt-connector-outlook | Outlook / `#5549` | OpenAI / Microsoft | каталог коннекторов ChatGPT | 1.2026.293 | connector |
| chatgpt-connector-1password | 1Password / `#7547` | OpenAI / 1Password | настройки браузера ChatGPT | 1.2026.293 | connector |
| chatgpt-file-sources | Box / Dropbox / Google Drive / SharePoint / `#4130` | OpenAI | библиотека/поиск ChatGPT | 1.2026.293 | connector |
| chatgpt-gpts | GPTs (My GPTs / Public GPTs) / `#5569` | OpenAI | раздел GPTs ChatGPT | 1.2026.293 | gpt |
| chatgpt-finance-connections | Финансовые подключения / `#6413` | OpenAI | раздел Finance ChatGPT | 1.2026.293 | connector |
| chatgpt-availability-gates | Гейты: план, админ, офлайн / `#7656` | OpenAI | каталог ChatGPT | 1.2026.293 | availability-facet |

| id | dependencies | auth | executionTargets | importPath | license | implementationStatus | blocker | testScenario |
|---|---|---|---|---|---|---|---|---|
| chatgpt-plugin-directory | список каталога OpenAI | аккаунт ChatGPT (гостевой режим не проверялся) | облако OpenAI; не подключено | n/a | не зафиксировано в APK | инвентаризация (не перенесено) | живой проход не выполнялся — ни одной карточки с именем/версией | открыть Browse plugins, выгрузить страницы/категории/карточки с версиями и издателями |
| chatgpt-cat-business-analytics | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | содержимое не выгружено; допустима пустая категория (`#7659`) | открыть категорию, зафиксировать число карточек и пустоту |
| chatgpt-cat-creativity-design | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | содержимое не выгружено | открыть категорию и выгрузить карточки |
| chatgpt-cat-developer-tools | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | содержимое не выгружено | открыть категорию и выгрузить карточки |
| chatgpt-cat-messaging-social | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | содержимое не выгружено | открыть категорию и выгрузить карточки |
| chatgpt-cat-productivity | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | имя совпадает с меткой Claude «Productivity» — совпадение названий не доказывает одинаковый пакет (план §2) | сравнить составы только после выгрузки обоих |
| chatgpt-cat-research-knowledge | фильтр категории | аккаунт ChatGPT | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | содержимое не выгружено | открыть категорию и выгрузить карточки |
| chatgpt-plugin-directory-views | рабочее пространство для раздела workspace | аккаунт ChatGPT; членство в workspace | облако OpenAI | n/a | не зафиксировано | инвентаризация (не перенесено) | значений списков нет; «Switch to a workspace…» (`#7689`) делает раздел контекстным | пройти каждый раздел, зафиксировать счётчики и пустоту, workspace — под нужным аккаунтом |
| chatgpt-plugin-detail | описание, скриншоты, навыки и действия | аккаунт ChatGPT | облако OpenAI | n/a | есть Privacy Policy/Terms (`#7680`), лицензия пакета не показывается | инвентаризация (не перенесено) | поле Version есть в UI, значений каталога в дампе нет | открыть карточку, перенести версию/издателя/лицензионные ссылки/состав |
| chatgpt-plugin-permissions | общий профиль + переопределение на плагин | аккаунт ChatGPT; для коннекторов требуется MFA (`#5584-5585`) | облако OpenAI | n/a | n/a | инвентаризация (не перенесено) | нельзя копировать чужую модель разрешений: разрешение установки не расширяет доступ Antigravity (план) | сменить все четыре уровня и проверить, что Antigravity применяет свою политику |
| chatgpt-plugin-management | «плагин содержит навыки»: удаление плагина удаляет навыки (`#5634`) | аккаунт ChatGPT | облако OpenAI | n/a | n/a | инвентаризация (не перенесено) | зависимость плагин→навыки обязательна для разбора в Этапе 2 | установить тестовый плагин, проверить счётчик, удалить и убедиться, что наш импорт учитывает потерю навыков |
| chatgpt-component-app | плагин как контейнер (по `#7845`) | авторизация внешнего сервиса при подключении | облако OpenAI ↔ внешние сервисы | n/a | не зафиксировано | инвентаризация (не перенесено) | формат App не описан; сопоставление с MCP/коннектором не выполнено | открыть App внутри плагина, записать контракт и выбрать адаптер |
| chatgpt-component-skill | плагин-владелец; включённые файлы (`#5619`) | аккаунт ChatGPT; контроль доступа и блокировки по безопасности | облако OpenAI; формат не подтверждён как SKILL.md | n/a | не зафиксировано | инвентаризация (не перенесено) | дамп не говорит, что навыки ChatGPT — Agent Skills; парсер Этапа 2 нельзя подключать без проверки формата | открыть Skill details, записать структуру Included files и сверить с форматом Agent Skills |
| chatgpt-connectors | connected accounts; MFA; админ-политика (`#5611`) | подключение аккаунта третьей стороны, требование MFA, reconnect при истечении | облако OpenAI ↔ поставщики; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | список доступных коннекторов в дампе не выведен | открыть Connectors, выгрузить список, подключить один тестовый, проверить отключение |
| chatgpt-connector-gmail | аккаунт Google; разрешение на почту (`#5560`) | подключение аккаунта Google | облако OpenAI ↔ Gmail; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | публичного MCP/API-конца нет — только официальный доступ и свой вход Antigravity | подключить тестовый Gmail, прочитать письмо, отключить и проверить отзыв |
| chatgpt-connector-google-calendar | аккаунт Google; разрешение на календарь | подключение аккаунта Google | облако OpenAI ↔ Calendar; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | переиспользовать чужой OAuth нельзя — нужен собственный вход Antigravity (план §2) | подключить календарь, создать событие, отключить, проверить отзыв |
| chatgpt-connector-outlook | аккаунт Microsoft | подключение аккаунта Outlook | облако OpenAI ↔ Outlook; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | публичный endpoint не зафиксирован; карточки без живого прохода нет | подключить Outlook, прочитать почту, отключить, проверить отзыв |
| chatgpt-connector-1password | приложение 1Password из Google Play (`#7551`); выбранные логины (`#7552`) | подключение аккаунта 1Password; выбор отдаваемых логинов | облако OpenAI ↔ облачный браузер; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | привязано к облачному браузеру продукта — иначе честная зависимость | подключить, выполнить вход на сайт, отключить, проверить отзыв |
| chatgpt-file-sources | подключённые файловые аккаунты; фильтры поиска (`#6565-6568`) | подключение каждого хранилища отдельно | облако OpenAI ↔ хранилища; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | строки дают только «Open in …» и скрытие результатов — не карточки каталога | подключить хранилище, найти файл, открыть, отключить |
| chatgpt-gpts | одобренное workspace (`#6544`); connected accounts внутри GPT (`#6545`) | аккаунт ChatGPT; ограничения владельца workspace | облако OpenAI; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | GPT ≠ плагин ≠ MCP: отдельный тип, переносимого сервера нет без доступного API | открыть Public GPTs, выбрать один, записать состав и ограничения; отдельно My GPTs |
| chatgpt-finance-connections | финансовый поставщик (в строках не назван) | подключение финансового аккаунта; синхронизация после подключения (`#6424`) | облако OpenAI ↔ поставщик; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | поставщик и метод не названы; секреты в инвентарь не входят (требование плана) | определить поставщика на устройстве, зафиксировать тип доступа и срок действия |
| chatgpt-availability-gates | тариф (Pro), политика администратора, сеть | аккаунт ChatGPT + тариф | облако OpenAI | n/a | n/a | инвентаризация (не перенесено) | обязаны различаться: видим ≠ доступен ≠ установлен; без живого прохода статусы карточек неизвестны | проверить карточку на Free и Pro, офлайн и под админом workspace — три разных статуса |

**evidence (общее для §7; построчно — в JSON):** `chatgpt_base_strings.txt:5585-5643,5665-5701,5756-5821,6511-6525,6644-6666,7649-7655,7756-7845,7965,8801-8832,3706-3711,3653-3654,1344-1347,4181-4191,651-654` (номера строк указаны в JSON); `chatgpt_badging.txt`.

## 8. Позиции Codex — 2

| id | Имя | sourceProduct | sourceId | publisher | sourceUrl | version | observedAt | componentTypes | availability / installed |
|---|---|---|---|---|---|---|---|---|---|
| codex-remote-composer-extensions | Remote-композер: skills / MCP / plugins ($ и @) | Codex (Remote), в составе APK ChatGPT | `#4972` | OpenAI | APK com.openai.chatgpt 1.2026.293 | 1.2026.293 | 2026-10-31 | plugin | unknown / unknown |
| codex-visualize-plugin-assets | Ассеты bundled-plugin «visualize» | Codex (bundled plugin), ассеты в проекте | project:assets:spaces:visualize | OpenAI (monorepo codex/codex-apps) | путь-источник: codex/codex-apps/bundled-plugins/visualize/skills/visualize/assets | не указана | 2026-11-02 | skill-assets | imported / false |

| id | dependencies | auth | executionTargets | importPath | license | implementationStatus | blocker | testScenario |
|---|---|---|---|---|---|---|---|---|
| codex-remote-composer-extensions | Remote/Codex-сессия; команды /help /status /goal /plan /fast (`#4973`) | аккаунт с доступом к Codex Remote | удалённая Codex-сессия; не подключено | n/a | не зафиксировано | инвентаризация (не перенесено) | блок строк относится к Remote-композеру: по плану §2 плагин Codex нельзя считать плагином Android ChatGPT; атрибуция внутри APK дампом не разделена | в отдельном прогоне Codex Remote ввести $ и @, записать списки и определить продукт |
| codex-visualize-plugin-assets | внешний CDN lucide@1.50.0 (`visualize.html:233`); глобальный объект `openai` (visualizationTheme/StyleVariables) | n/a | Android host грузит файлы и рендерит в opaque iframe (по README) | `app/src/main/assets/spaces/{README.md,visualize.html,visualize.css}` | не указана; копирование из monorepo задокументировано, право распространения не проверено | импортировано, не подключено | сетевая зависимость от unpkg.com и отсутствие лицензии; сквозной запуск не выполнялся | открыть визуализацию без сети (проверить отказ CDN) и с сетью (рендер в iframe) |

**evidence (общее для §8; построчно — в JSON):** `chatgpt_base_strings.txt:5030,5032,5033,5034,5036,5039`; `app/src/main/assets/spaces/README.md:3-13`, `visualize.html:6,233`, sha256 `visualize.html 74a06bcf…`, `visualize.css 399418d6…`.

## 9. Позиции: локальная установка Antigravity — 21

Состояние подтверждено одновременно файлами и CLI (`agy plugin list --json`, `agy mcp list`, 2026-11-02).

| id | Имя | sourceId | publisher | sourceUrl | version | observedAt | componentTypes | availability / installed |
|---|---|---|---|---|---|---|---|---|
| local-skill-autonomous-software-engineer | autonomous-software-engineer | local:skill:autonomous-software-engineer | локальный импорт | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-reverse-engineer-anything | reverse-engineer-anything | local:skill:reverse-engineer-anything | локальный импорт | n/a | 49 (metadata.version) | 2026-11-02 | skill | installed / true |
| local-skill-rigorous-programming-reasoner | rigorous-programming-reasoner (активный) | local:skill:rigorous-programming-reasoner | локальный импорт | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-rigorous-programming-reasoner-disabled | rigorous-programming-reasoner (отключённая копия) | local:skill:rigorous-programming-reasoner#disabled | локальный импорт | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-workstation-diagnostics | workstation-diagnostics | local:skill:workstation-diagnostics | локальный импорт | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-agy-customizations | agy-customizations | local:builtin-skill:agy-customizations | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-antigravity-guide | antigravity-guide | local:builtin-skill:antigravity_guide | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-automation | automation | local:builtin-skill:automation | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-generative-ui | generative_ui | local:builtin-skill:generative_ui | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-migrate-workflows | migrate-workflows | local:builtin-skill:migrate-workflows | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-permissioned-github | permissioned-github | local:builtin-skill:permissioned-github | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-plugin | plugin (справочник по плагинам) | local:builtin-skill:plugin | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-run-workflow | run-workflow | local:builtin-skill:run-workflow | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-skill-builtin-ui-plugin-navigation | ui-plugin-navigation | local:builtin-skill:ui-plugin-navigation | Google (builtin) | n/a | не указана | 2026-11-02 | skill | installed / true |
| local-import-uploads-skill-md | uploads/SKILL.md | local:import:uploads/SKILL.md | локальный импорт | n/a | не указана | 2026-11-02 | skill | imported / false |
| local-plugin-antigravity-workspace-guard | antigravity-workspace-guard | local:plugin:antigravity-workspace-guard | локальный импорт | n/a | 1.2.0 | 2026-11-02 | plugin | installed / true |
| local-plugin-kotlin-compose-inspector | kotlin-compose-inspector | local:plugin:kotlin-compose-inspector | локальный импорт | n/a | 2.0.0 | 2026-11-02 | plugin | installed / true |
| local-plugin-staging-antigravity-workspace-guard | staging-копия antigravity-workspace-guard | local:import:uploads/plugin-staging/… | локальный импорт | n/a | 1.2.0 | 2026-11-02 | plugin | imported / false |
| local-plugin-staging-kotlin-compose-inspector | staging-копия kotlin-compose-inspector | local:import:uploads/plugin-staging/… | локальный импорт | n/a | 2.0.0 | 2026-11-02 | plugin | imported / false |
| local-mcp-ag-workstation-info | ag-workstation-info (stdio) | local:mcp:ag-workstation-info | собственный код проекта | n/a | 1.0.0 (serverInfo) | 2026-11-02 | mcp-server | installed / true |
| local-mcp-rea | rea (stdio) | local:mcp:rea | rea-agents (npm) | n/a | не записана | 2026-11-02 | mcp-server | installed / true |

| id | dependencies | auth | executionTargets | importPath | license | implementationStatus | blocker | testScenario |
|---|---|---|---|---|---|---|---|---|
| local-skill-autonomous-software-engineer | без внешних скриптов (только SKILL.md) | n/a | PC (`~/.gemini/config/skills`); телефон не подтверждён | `C:\Users\st811210\.gemini\config\skills\autonomous-software-engineer` | не указана | локально установлено, состав не проверен | SKILL.md и SKILL.md.disabled идентичны (sha256 `83620ade…`); активный приоритетен → при чтении списка навык выглядит включённым, копия отключения игнорируется | оставить только `.disabled`, вызвать listSkills и убедиться в `enabled=false`; проверить отсутствие двух записей |
| local-skill-reverse-engineer-anything | `references/*.md` (7 файлов); MCP-сервер rea | n/a для навыка; инструменты требуют сервер rea | PC; телефон не подтверждён | `C:\Users\st811210\.gemini\config\skills\reverse-engineer-anything` | не указана | локально установлено, состав не проверен | зависит от MCP rea, который в `mcp_config.json` помечен `disabled:true` — без включения навык неработоспособен | включить rea, выбрать навык, выполнить один инструмент REA, проверить разрешение ссылок |
| local-skill-rigorous-programming-reasoner | без внешних файлов | n/a | PC | `C:\Users\st811210\.gemini\config\skills\rigorous-programming-reasoner` | не указана | локально установлено, состав не проверен | активный SKILL.md (3 215 байт, `e558f3ef…`) и `.disabled` (22 531 байт, `5910a64f…`) различаются; активный приоритетен | убедиться, что запись одна и что удаление активного файла не удаляет отключённую копию |
| local-skill-rigorous-programming-reasoner-disabled | активная копия того же навыка | n/a | PC | `…\rigorous-programming-reasoner\SKILL.md.disabled` | не указана | локально установлено (отключено), состав не проверен | при существовании активного файла не влияет на статус; байт-в-байт совпадает с `uploads/SKILL.md` | при отсутствии активного файла навык должен отображаться отключённым и переживать вызовы списка (Этап 0) |
| local-skill-workstation-diagnostics | MCP `ag-workstation-info` (`get_workstation_pulse`) | n/a (stdio, локально) | PC — сквозной сценарий уже выполнялся | `C:\Users\st811210\.gemini\config\skills\workstation-diagnostics` | не указана | локально установлено; единственный навык с подтверждённым сквозным вызовом | результат телеметрии нельзя выдавать за телефонный | повторить вызов после любого изменения MCP-конфигурации |
| local-skill-builtin-agy-customizations | `docs/*.md` (6 файлов) | n/a | PC (builtin-корень) | `C:\Users\st811210\.gemini\antigravity\builtin\skills\agy-customizations` | не указана | встроено; состав не проверялся | builtin-корень смешивается с пользовательскими навыками в одном списке — разделение источников обязано сохраниться | проверить, что карточка показывает источник builtin |
| local-skill-builtin-antigravity-guide | `references/{app,cli,ide,sdk}.md` | n/a | PC | `…\builtin\skills\antigravity_guide` | не указана | встроено; состав не проверялся | описывает Antigravity, а не экосистемы Claude/ChatGPT — контекст, не переносимое расширение | убедиться, что не попадает в категорию «перенесённые из Claude/ChatGPT» |
| local-skill-builtin-automation | slash-команда /automation; планировщик продукта | n/a | PC | `…\builtin\skills\automation` | не указана | встроено; состав не проверялся | зависит от продуктового планировщика — на телефоне не подтверждено | создать расписание на ПК; отдельно — доступность на телефоне |
| local-skill-builtin-generative-ui | рендер HTML-виджетов хостом | n/a | PC | `…\builtin\skills\generative_ui` | не указана | встроено; состав не проверялся | телефонный рендер не подтверждён | сгенерировать виджет и проверить на ПК и на телефоне |
| local-skill-builtin-migrate-workflows | глобальные и workspace-корни конфигурации | n/a | PC — пишет в конфигурацию | `…\builtin\skills\migrate-workflows` | не указана | встроено; состав не проверялся | пишет в конфигурацию — нельзя запускать как часть просмотра списка | запускать только по явному действию; проверить, что отключённые навыки не активируются |
| local-skill-builtin-permissioned-github | доступ к GitHub в среде выполнения | запрос разрешений у пользователя при отказах | PC | `…\builtin\skills\permissioned-github` | не указана | встроено; состав не проверялся | тематически пересекается с Claude GitHub App — не объединять, это разные продукты | проверить пометку источником Antigravity, а не Claude |
| local-skill-builtin-plugin | slash-команда /plugin; CLI `agy plugin` | n/a | PC | `…\builtin\skills\plugin` | не указана | встроено; состав не проверялся | описывает формат плагинов Antigravity, а не форматы Claude/OpenAI — Этапу 2 нужны отдельные парсеры | сверить структуру «плагин Antigravity» с требованиями Этапа 2 и зафиксировать различия |
| local-skill-builtin-run-workflow | инструмент run_workflow; агенты продукта | n/a | PC | `…\builtin\skills\run-workflow` | не указана | встроено; состав не проверялся | телефонный цикл не подтверждён | запустить малый workflow; проверить, что дочерние задачи получают только нужный поднабор прав |
| local-skill-builtin-ui-plugin-navigation | UI-панели плагинов продукта; `disable-slash-command: true` | n/a | PC | `…\builtin\skills\ui-plugin-navigation` | не указана | встроено; состав не проверялся | привязано к десктопным UI-панелям — на телефоне не применимо без проверки | убедиться в отсутствии кнопки на телефоне и не рисовать фиктивный UI |
| local-import-uploads-skill-md | вне корней listSkills | n/a | не подключено (файл не читается как навык) | `…\antigravity-android\uploads\SKILL.md` | не указана | импортировано, не подключено | корни listSkills — `.agents/skills`, `~/.gemini/config/skills`, builtin (`server.js:2364-2368`); uploads/ среди них нет; sha256 `5910a64f…` = отключённая копия rigorous-programming-reasoner | перенести в один из корней и убедиться в одной записи без раздвоения |
| local-plugin-antigravity-workspace-guard | компоненты в manifest отсутствуют (только name/version/description) | n/a | PC (`agy plugin`); телефон через `/api/plugins` | `C:\Users\st811210\.gemini\config\plugins\antigravity-workspace-guard` | не указана в manifest | локально установлено, состав не проверен | manifest без `verifiedTools`: файл не подтверждает обещанные проверки сборки/защиты Git (дефект №1, Этап 0); включён и по CLI, и по `config.json` | проверить, делает ли плагин что-либо при включении; иначе карточка показывает «требует проверки» |
| local-plugin-kotlin-compose-inspector | компоненты в manifest не перечислены | n/a | PC (`agy plugin`) | `C:\Users\st811210\.gemini\config\plugins\kotlin-compose-inspector` | не указана в manifest | локально установлено, состав не проверен | `enabled=true` по CLI, но записи в `config.json` нет — два источника состояния расходятся (Единый реестр, Этап 2) | отключить через agy и убедиться, что источники сходятся, а карточка не показывает ложное «включено» |
| local-plugin-staging-antigravity-workspace-guard | дубль установленного пакета | n/a | PC (staging-каталог для действия `create`) | `…\uploads\plugin-staging\antigravity-workspace-guard` | 1.2.0 | импортировано, не подключено (дубль) | sha256 `e354b9ec…` совпадает с установленным — один пакет, два пути | при установке из staging проверить, что реестр не создаёт вторую карточку |
| local-plugin-staging-kotlin-compose-inspector | дубль установленного пакета | n/a | PC (staging) | `…\uploads\plugin-staging\kotlin-compose-inspector` | 2.0.0 | импортировано, не подключено (дубль) | sha256 `1efc79cf…` совпадает с установленным — один пакет, два пути | проверить коллизию имён при установке из staging (Этап 2) |
| local-mcp-ag-workstation-info | node.exe; `pc-bridge/mcp_sysinfo_server.js` | n/a (stdio, локальный процесс, без токенов) | PC (Node stdio); телефон через мост ПК | `~/.gemini/config/mcp_config.json` + `pc-bridge/mcp_sysinfo_server.js` | собственный код проекта | локально установлено; протокольный handshake в Этапе 1 не выполнялся | tools известны только из декларации самого сервера, а не из прочитанного `tools/list`; fallback `protocolVersion 2024-11-05` не проверялся (Этап 3) | initialize → tools/list → tools/call `get_workstation_pulse` → проверить `ANTIGRAVITY-MCP-VERIFIED-2026` |
| local-mcp-rea | node.exe; `node_modules/rea-agents/scripts/rea.mjs` | n/a для stdio; часть операций требует прав среды | PC (Node stdio) | `C:\Users\st811210\.gemini\config\mcp_config.json` | лицензия пакета npm в проекте не зафиксирована | установлен, отключён | расхождение: `mcp_config.json` → `disabled:true`, `~/.gemini/settings.json` → без ключа, `agy mcp list` → disabled; источник истины определить до Этапа 3 | включить rea, свести три конфигурации в один источник, выполнить один вызов инструмента REA |

**evidence (общее для §9; построчно — в JSON):** `~/.gemini/config/skills/*/SKILL.md*`, `~/.gemini/config/plugins/*/plugin.json`, `~/.gemini/config/{mcp_config.json,import_manifest.json,config.json}`, `~/.gemini/settings.json`, `~/.gemini/antigravity/builtin/skills/*/SKILL.md`, `uploads/SKILL.md`, `uploads/plugin-staging/*/plugin.json`, `pc-bridge/server.js:2164-2661`, `pc-bridge/mcp_sysinfo_server.js:35-70`, `audit_matrix.md:15`, `verification.md:127-133`, вывод `agy plugin list --json` и `agy mcp list` от 2026-11-02; sha256 — в §4 и в JSON.

## 10. Соответствие критериям готовности Этапа 1

| Критерий плана | Статус | Основание |
|---|---|---|
| Весь зафиксированный доступный срез учтён, нет необъяснимых пропусков | выполнено | 79 позиций из источников S1–S14; исключения перечислены в §3 с причинами |
| Видимые, установленные, доступные по подписке и недоступные позиции различаются | выполнено | поля `availability` + `implementationStatus`: visible 20, installed 18, imported 4, subscription-gated 1, builtin 2, unknown 34 |
| Поля sourceProduct, sourceId, publisher, sourceUrl, version, observedAt, componentTypes, dependencies, auth, executionTargets, importPath, license, implementationStatus, blocker, evidence, testScenario | выполнено | формальная проверка JSON: 0 пропусков полей, 0 дублей id |
| Дубли связаны по источнику/идентификатору/хэшу с сохранением происхождения | выполнено | §4, поле `duplicates` в JSON |
| Секреты не входят | выполнено | токенов/ключей в инвентаре нет; у `local-mcp-rea` зафиксированы только разрешения `mcp(rea/…)` как факт конфигурации |
| Полный экспорт каталогов отсутствует → зафиксирован охват и ограничения | выполнено | §2 (границы источников) и §3 (`coverageLimitations` в JSON) |
| APK как дополнительное свидетельство | выполнено | S4, S5, S6 — APK используются только как источник строк, не как доказательство выполнения |

Чего **нет** (и это не сокрыто): полного списка карточек Claude, хотя бы одной карточки ChatGPT с именем и версией, лицензий, версий пакетов каталогов, подтверждённого места выполнения для позиций источников, состояния установленного в аккаунтах Claude/ChatGPT. Это вынесено в блокеры позиций и в ограничения охвата, а не исключено из инвентаря.

## 11. Статус следующего шага

Этап 2 (импорт и общий реестр) выполнен 2 ноября 2026. Все блокеры этого шага
закрыты на уровне парсеров и реестра (`pc-bridge/lib/extension-formats.js`,
`extension-archive.js`, `extension-registry.js`; тесты
`pc-bridge/test/extensions-stage2.test.js`, 21 сценарий):

- парсер формата навыков ChatGPT (`chatgpt-component-skill`) — карточка
  честно получает «требует проверки», формат не отождествляется с Agent Skills
  SKILL.md; живой экспорт навыка для подтверждения формата остаётся за
  тест-сценарием позиции;
- связь «плагин → навыки» (`chatgpt-plugin-management`) — состав пакета
  сохраняется при просмотре до установки, при импорте и в индексе реестра;
- разделение Codex/ChatGPT (`codex-remote-composer-extensions`) —
  `classifySourceProduct` не смешивает продукты; конфликт для позиций
  инвентаря фиксируется явно (проверено на `chatgpt-android:remote:*`);
- единый источник состояния (`local-plugin-kotlin-compose-inspector`,
  `local-mcp-rea`) — реестр сводит заявки agy CLI, config.json, mcp_config.json,
  settings.json и файловой системы; расхождения показывают «требует сверки
  состояния» с claim-таблицей, ложное «включено» не показывается (оба реальных
  расхождения подтверждены read-only прогоном);
- честная карточка «требует проверки» для manifest без состава —
  `compositionStatus`.

Следующий шаг — Этап 3 (реальные MCP-клиенты): initialize/tools/list и
настоящий вызов для `local-mcp-rea` и `ag-workstation-info`; живой проход
каталога ChatGPT и проверка формата его навыков — по тест-сценариям позиций.

**Этап 3 выполнен 2 ноября 2026 (ядро транспортов и жизненного цикла).**
`pc-bridge/lib/mcp-client.js` реализует настоящие транспорты stdio, Streamable
HTTP и устаревший HTTP+SSE с полным циклом initialize → initialized →
tools/list и вызовом tools/call; подключён к `server.js` (`POST /api/mcp`:
probe/check, call, disconnect) и Android-клиенту (AntigravityBackendClient /
AntigravityViewModel / карточка MCP в MainScreen). Обе целевые позиции проверены
живым рукопожатием на реальной конфигурации: `ag-workstation-info` и `local-mcp-rea`
подключились, их инструменты получены только из живого tools/list (у `rea` —
200 имён), реальный tools/call выполнен на обоих. Статус «connected» выставляется
исключительно после успешного рукопожатия; при отказе — «проверка не пройдена»
с причиной и без выдуманных инструментов. Заграница шага: полный цикл OAuth
(discovery, PKCE, refresh/revoke, изолированное хранение токенов) не реализован —
авторизация пока через проброс параметров из конфига без утечек в логи и API;
живой проход каталога ChatGPT остаётся за тест-сценариями позиций (Этап 7).

**Этап 4 выполнен 2 ноября 2026** (реестр → модели и дочерние задачи):
`pc-bridge/lib/extension-runtime.js` — выбор навыка (`/имя`) с загрузкой
инструкций только выбранного и явным отказом для отключённых; реальные схемы
инструментов только из живого tools/list (ссылки `serverId:toolName`,
одноимённые инструменты разных серверов не взаимозаменяемы); адаптер OpenRouter
получил нативный tool-calling с циклом вызовов и возвратом результатов в тот же
цикл, Stop отменяет вызовы; телефонные инструменты остаются phone_action на
телефоне и на ПК не перенаправляются; права выдаются только явным списком,
аннотации MCP прав не дают; дочерние задачи получают поднабор прав в задании.
Механизм CLI проверен на реальном `agy`: инструменты — через зарегистрированные
MCP-серверы, инъекции схем нет (и не выдумывается); `--settings` отвергает
`mcpServers` — посессионный поднабор MCP у CLI невозможен (зафиксировано).

## 12. Этап 5 — адаптеры компонентов и Android-runtime (2 ноября 2026)

- Матрица адаптеров `extensions-adapter-matrix.json` (генератор
  `pc-bridge/scripts/build-adapter-matrix.js`, отдаётся
  `GET /api/extensions/adapters`): у каждой из 79 позиций (81 запись
  компонентов) — стратегия preserve/convert/own-executor и статус
  verified/blocked/requires-check/not-applicable с конкретным блокером; сводная
  таблица отдельно проверенных видов: commands, agents, hooks, LSP, локальные
  программы, переменные конфигурации, продуктовые UI-компоненты.
- Итоги: fully-supported — 1 (`local-mcp-ag-workstation-info`: собственный код +
  живое MCP-рукопожатие); blocked — 21; partial — 20 (`local-mcp-rea` — лицензия
  npm-пакета не подтверждена; `local-skill-*` — лицензии в файлах не указаны);
  observation — 38 (продуктовые UI-компоненты источников). «Полностью» не
  выдаётся без проверенного состава И права распространения (68 позиций с
  неподтверждённой лицензией — право переиспользования/распространения проверяется
  отдельно, `checkRedistributionRight`).
- Установленные плагины `antigravity-workspace-guard` и `kotlin-compose-inspector`:
  состав просмотрен по реальным файлам — blocked: только plugin.json,
  заявленные возможности не обеспечены компонентами (skills/agents/rules/hooks/mcp
  отсутствуют) — defect №1 подтверждён на уровне адаптеров.
- Hooks: собственный исполнитель `pc-bridge/lib/hook-engine.js` по проверенному
  официальному контракту (`builtin/skills/agy-customizations/docs/hooks.md`).
  Неподдерживаемое не запускается молча: тип ≠ "command", неизвестное событие,
  enabled:false, запрет прав (pcPluginsMcpSkillsAccess + pcPowerShellExecution),
  тайм-аут, не-JSON выход — видимые отказы. «ask»/«force_ask» → честный отказ;
  отмена пользователя hook'ами не продлевается. Deny/overwrite/PostToolUse/
  Pre-PostInvocation (injectSteps, terminationBehavior)/Stop/SessionStart
  подключены к циклу инструментов (`runToolCallLoop`).
- Скрипты/локальные программы: `pc-bridge/lib/script-runner.js` — зависимости
  проверяются до запуска (реальный probe интерпретатора; node-импорты против
  node_modules/package.json), рабочий каталог изолирован (служебный временный
  каталог, allowlist окружения), нехватка зависимостей — блокер, не прогон.
- Android-runtime: компоненты расширений исполняются на ПК; телефонная сторона —
  phone_action приложения (Этап 4, не перенаправляются на ПК). Доказательство
  запуском: эмулятор Pixel_8a → install app-debug.apk → COLD-запуск
  `com.google.antigravity/.MainActivity` (12.5 с) → работоспособный интерфейс
  чата (`verification_screenshots/stage5_android_launch.png`); Android-тесты
  прогнаны принудительно: 84 теста, 21 набор, 0 падений.
- Проверено: 18 тестов `pc-bridge/test/extensions-stage5.test.js` (hook-engine и
  script-runner на настоящих процессах, матрица по реальному инвентарю,
  vm-склейка server.js; тест поймал и исправил дефект `loadExtensionHooks`);
  полная коллекция Node-тестов зелёная.

## 13. Этап 6 — единый интерфейс расширений (2 ноября 2026)

- Экран «Расширения» (`ui/components/ExtensionsScreen.kt`): Подключения /
  Навыки / Плагины; Мои / Каталог с поиском. Каталог — по матрице адаптеров
  (`/api/extensions/adapters`): категория, источник, совместимость и блокер на
  карточке; неподключённые позиции не показываются работающими («Установка
  недоступна без проверенного переносимого источника»).
- Карточки: состав (адаптеры Этапа 5), инструменты только из живого tools/list,
  права, исполнитель, последняя успешная проверка (probedAt); технические
  MCP-параметры — в раскрываемых подробностях. Лимиты/оплата не показываются,
  пока не подтверждены (в этой сборке нигде не подтверждены).
- Действия: установить / войти / включить / проверить / обновить / отключить /
  удалить. «Войти» — пробой с параметрами авторизации из конфига (полный OAuth
  — граница Этапа 3); «обновить» — только для пакетов с источником URL, для
  локальных честное «обновление недоступно».
- Разговор: выбор навыка живыми `/имя`-подсказками; выбор подключения —
  упоминание `@имя` включает сервер в allowlist хода (`mcpConnections` в
  `/api/chat`, фильтрация `filterAllowedTools(allowServers)` на мосту), чипы
  выбранных подключений над вводом. Хронология событий инструментов, ошибки и
  Stop сохранены; панель ручного создания субагентов не добавлялась.
- Проверено: Android — 22 набора / 88 тестов / 0 падений (новый
  `ExtensionsDataParsersTest` на реальном разборе состава и каталога),
  BUILD SUCCESSFUL (testDebugUnitTest lintDebug assembleDebug). UI-проверки на
  эмуляторе Pixel_8a (`verification_screenshots/stage6_extensions_*.png`):
  живые данные моста с честными статусами (после перезапуска моста — «настроен
  (не проверен)» и «инструменты не получены» без выдуманных имён), Back,
  клавиатура, длинные имена, крупный шрифт (font_scale 1.3).

## 14. Этап 7 — перенос всех позиций по матрице (2 ноября 2026)

- Сквозные контрольные пакеты (проверка инфраструктуры, не конечный набор) —
  `pc-bridge/test/fixtures/stage7/`: инструкционный навык, навык со скриптом
  (зависимость node_modules/control-dep), HTTP MCP (настоящий Streamable-HTTP
  сервер), stdio MCP (настоящий процесс), составной плагин с зависимостями
  (manifest + навык + hook + скрипты). Каждый пройден сквозным сценарием в
  `pc-bridge/test/extensions-stage7.test.js`: установка (реестр) → включение →
  проверка (рукопожатие/зависимости) → вызов (tools/call / скрипт / hook по
  контракту stdin JSON → stdout JSON). Статусы не противоречат исполнению:
  «подключён» — только после рукопожатия; отсутствующая зависимость — видимый
  блокер до запуска; падение процесса — видимая ошибка.
- Полный проход инвентаря пакетами плана —
  `pc-bridge/scripts/build-migration-coverage.js` →
  `extensions-migration-coverage.json`: 79/79 позиций среза разбиты по пакетам
  (документы/PDF — 2; таблицы/презентации — 0 позиций в срезе записано явно;
  код/Git — 17; поиск/браузер — 4; дизайн/медиа — 7; почта/календарь/диск — 8;
  задачи/знания — 11; остальные категории каталогов — 30). У каждой позиции —
  адаптация (матрица Этапа 5), сценарий проверки (testScenario инвентаря) и
  подтверждение пути: локальные пути проверены на диске (21), внешние без
  подтверждённого переносимого сервера — неподтверждены (58).
- Общий backlog: у каждой нерешённой зависимости — владелец (внешний поставщик
  либо antigravity-android) и следующий шаг; правило зафиксировано в записи.
  Общий перенос — честно «не завершено»: обязательные позиции локальной
  установки заблокированы (4, включая defect №1 пустого состава плагинов).
- Проверено: `extensions-stage7.test.js` — 7 сквозных тестов; коллекция
  Node-тестов 127/127; Android testDebugUnitTest lintDebug assembleDebug —
  BUILD SUCCESSFUL. Границы: перенос внешних сервисов остаётся в backlog до
  подтверждения переносимого сервера каждой позиции; платные прогони модели
  не выполнялись.

## 15. Этап 8 — сохранение, обновления и восстановление (2 ноября 2026)

- Пользовательское состояние расширений (включённость, разрешения, привязки
  разговоров) — `uploads/extensions-user-state.json`: атомарная запись (tmp +
  rename), отдельно от индекса реестра (переживает его пересборку), без
  секретов. Перезапуск pc-bridge проверен настоящим дочерним процессом;
  перезапуск Android-клиента — выбор подключений хранится в SharedPreferences
  (`ExtensionBindings`), включённость приходит с моста.
- Сбой индекса (повреждение/отсутствие) не теряет установленные пакеты: снимок
  повреждённого файла — в `extensions-registry.json.corrupt-*.backup`, индекс
  пересобирается по диску (`rebuildIndexFromDisk`), отключения применяются из
  пользовательского состояния; событие — в `recoveryLog` и в
  `GET /api/extensions/registry` (indexRecovery).
- Обновление (`updatePackage`, `POST /api/extensions/update`): прежняя версия —
  в backup до полного завершения валидации и замены; манифест/целостность/схема/
  зависимости проверяются после замены (`validateInstalledUpdate`), провал →
  автоматический откат с сохранением настроек; backup — точка отката
  (`rollbackPackage`, `POST /api/extensions/rollback`). Новые права только
  «запрошено» — выдача лишь явным действием (grant,
  `POST /api/extensions/user-state`).
- Резервная копия (`createBackup`, `POST /api/extensions/backup`) —
  manifests/настройки/состояние без токенов (env/заголовки MCP и значения
  userConfig не сохраняются никогда); восстановление (`restoreBackup`,
  `POST /api/extensions/restore`) проверяет файлы по хэшам, показывает
  requiresReauth явно, не расширяет права; отсутствующие файлы — честное
  «требуется переустановка».
- Проверено: 16 тестов `extensions-stage8.test.js`; коллекция Node-тестов
  170/170; Android (testDebugUnitTest lintDebug assembleDebug) — BUILD
  SUCCESSFUL, 25 наборов / 103 теста / 0 падений, включая `ExtensionBindingsTest`.


## 16. OAuth 2.1 в Android-клиенте — итоговая стыковка (3 ноября 2026)

Клиентская часть OAuth 2.1 для MCP-подключений (к серверному модулю
`pc-bridge/lib/mcp-oauth-api.js`, контракт в `PC_BRIDGE_OAUTH_API.md`):

- Клиент говорит действиями `oauthStart` / `oauthStatus` / `oauthRevoke`
  (`AntigravityBackendClient.mcpOAuthStart/mcpOAuthStatus/mcpOAuthRevoke`).
  «Войти» → `oauthStart` → сервер возвращает `authUrl` → он открывается через
  системный браузер / Custom Tabs (`Intent.ACTION_VIEW`; https либо http только
  для loopback-адресов разработки). «Выйти» → `oauthRevoke` (отзыв токенов).
- Статус обновляется при возврате в приложение (`onResume` в MainActivity) и по
  кнопке «Проверить»: `oauthStatus` (с `sessionId` попытки) + живое
  рукопожатие за инструментами после успешного входа. Один и тот же authUrl
  из фонового обновления повторно не открывается. Native loopback-вариант
  (RFC 8252, `oauth_start` + deep link `com.google.antigravity:/mcp-oauth/callback`)
  сохранён на сервере и в клиенте как legacy-путь; основным клиентским потоком
  является bridge-режим (возврат → onResume → `oauthStatus`).
- Парсинг OAuth-полей: `authUrl` (fallback `authorizationUrl`) и `authStatus`
  (fallback `status` + `authorized`) — `parseMcpOAuthReply`; в списке
  `mcpServers` — `parseMcpServerList`; `mergeMcpAuthFields` не даёт статусу
  входа «потеряться» при обновлении списка без OAuth-полей.
- UI (`ExtensionsScreen.kt`): в деталях HTTP-подключения карточка
  «Авторизация (OAuth 2.1)» со статусом «Требуется вход» / «Авторизован» /
  «Ошибка токена» (плюс pending/denied/expired/revoked/superseded) и действием
  «Войти» / «Обновить статус входа» / «Выйти (отозвать доступ)».
- Переходы состояния (`McpAuthState`): «Войти»→pending(authUrl)→по `oauthStatus`
  authorized | failed («Ошибка токена») | expired | denied | superseded;
  revoke → «Требуется вход». «authorized» не выдаётся за MCP-рукопожатие —
  для него есть «Проверить».
- Проверено: `McpOAuthStateTest.kt` — 18 тестов (парсинг authUrl/authStatus и
  переходы состояния); Android testDebugUnitTest lintDebug assembleDebug —
  BUILD SUCCESSFUL, 26 наборов / 121 тест / 0 падений; Node-коллекция моста
  174/174. Живые скриншоты: `verification_screenshots/s8_plugins_my.png`
  (оба локальных плагина «✓ Готов», без «заблокирован»),
  `s8_auth_card.png` («Требуется вход» + «Войти»).
- Границы (не заявляется выполненным): полный цикл реального входа у внешнего
  провайдера не прогонялся (нет тестового аккаунта; временное подключение
  demo-oauth-mcp удалено после кадра). Bridge-callback по умолчанию — loopback
  ПК (`http://127.0.0.1:8787/api/mcp/oauth/callback`); для браузера на телефоне
  провайдеру нужен явно настроенный HTTPS `bridgeRedirectUri` в приватном конфиге
  MCP (см. `PC_BRIDGE_OAUTH_API.md`). Опрос `oauthStatus` не выполняет refresh и
  не запускает инструменты.


## 17. Бэклог antigravity-android и сверка состояний (3 ноября 2026)

- **Сверка состояний закрыта**: было 4 записи «требует сверки состояния»
  (kotlin-compose-inspector — разрыв регистрации в config.json; rea — расхождение
  mcp_config.json/settings.json/CLI; autonomous-software-engineer и
  rigorous-programming-reasoner — неоднозначность SKILL.md ↔ SKILL.md.disabled).
  Выполнены явные действия: enable плагина (регистрация записи), disable rea
  (источник истины — mcp_config.json + agy mcp list, оба disabled), заявки
  userState enabled=true для навыков (соответствует поведению загрузчика: активный
  SKILL.md приоритетен). Реестр: `byDisplayState {включён:16, отключён:1}`,
  `needsStateReconciliation: 0`.
- **ag-workstation-info: сценарий позиции выполнен живьём** (pc-bridge
  probe/call): tools/list → `get_workstation_pulse`, protocolVersion согласован
  `2025-06-18` (не fallback 2024-11-05), serverInfo `antigravity-sysinfo-mcp
  1.0.0`, ответ содержит `mcpVerificationBadge=ANTIGRAVITY-MCP-VERIFIED-2026`.
  Запись вышла из бэклога.
- **Лицензии зафиксированы по доказательствам** (правило `license-unconfirmed`
  закрыто полностью): rea и reverse-engineer-anything — MIT (пакет rea-agents
  6.8.0, LICENSE/package.json; SKILL.md байт-в-байт сверен, sha256 8818b645…);
  workstation-diagnostics, rigorous-programming-reasoner (+копии) и
  autonomous-software-engineer — собственный код проекта (методология
  «Этапы A–I» в ассетах `agent_system_prompt.md`, токен
  ANTIGRAVITY-MCP-VERIFIED-2026, slash-команды приложения); 9 builtin-навыков —
  «встроенный контент продукта Antigravity (Google); в состав проекта не
  включается и не распространяется — право распространения не применима».
  Если импорт навыков велся из внешнего источника — запись требует уточнения
  владельцем (не выдаётся за подтверждённую).
- **Бэклог 75 → 73**; состав: 55 external-unconfirmed-path (внешние поставщики),
  3 external-runtime-check (нужен пользователь/вход), 4 file-shadow (теневые
  копии SKILL.md.disabled — решение владельца данных; удаление не выполнялось),
  1 component-disabled (reverse-engineer-anything требует включённого rea),
  10 unresolved-dependency (телефонные/продуктовые сценарии builtin-навыков и
  телеметрия workstation-diagnostics — не выдаются за телефонные).
  Уточнение генератора: бэклог фиксирует открытые вопросы (issues); фактические
  зависимости без открытого вопроса записи бэклога не создают.
- Проверено: Node-коллекция 174/174 после регенерации матрицы и coverage
  (`build-adapter-matrix.js`, `build-migration-coverage.js`); license-unknown в
  матрице 52 → 48.


## Уточнение после закрытия блокеров (2 ноября 2026)

Исходные таблицы выше — исторический срез наблюдений этапа 1. Текущее состояние
задаётся `extensions-adapter-matrix.json` и `extensions-migration-coverage.json`.

- Четыре локальные записи теперь ссылаются на два канонических MIT-пакета в
  `extensions/plugins/`. В составе каждого есть SKILL.md, Node-проверка и hook
  по реальному stdin JSON → stdout JSON контракту проекта. Staging сохраняется
  как происхождение/алиас, повторная карточка в Android не показывается.
- Проверки предназначены для ПК с Node.js (guard также требует Git). Работа
  этих скриптов непосредственно на Android не заявляется. Исправление файлов
  не включает отключённые пользователем плагины и не выдаёт новых прав.
- `extensions/official-mcp.json` связывает Desktop Commander, Bright Data и
  GitHub с официальными открытыми реализациями и дескрипторами. Это адаптеры
  Antigravity, не перенос учётной записи Claude/ChatGPT. Для npm-команд версии
  закреплены; GitHub использует официальный удалённый endpoint.
- Источники: [Desktop Commander](https://github.com/wonderwhy-er/DesktopCommanderMCP),
  [Bright Data](https://github.com/brightdata/brightdata-mcp),
  [GitHub MCP](https://github.com/github/github-mcp-server). Лицензии реализации
  не означают бесплатное использование облачного сервиса.
- У Figma и Zoom подтверждены официальные hosted endpoints, но в этой проверке
  не установлена открытая переносимая реализация; сторонние аналоги не подставлялись.
- HTTP MCP OAuth реализован по [спецификации MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization):
  RFC 9728 discovery, метаданные issuer, public client (заданный clientId либо
  dynamic registration), Authorization Code + S256, state, одноразовый callback,
  resource binding, refresh и revocation. В Android — системный браузер и возврат
  через HTTP loopback `http://127.0.0.1:<port>/mcp-oauth/callback` телефона
  (RFC 8252), затем явная ссылка `com.google.antigravity:/mcp-oauth/callback`
  возвращает в приложение; «Выйти» очищает вход отдельно от
  включения подключения. Stdio использует конфигурацию окружения, не OAuth.
- Токены хранятся в отдельном зашифрованном vault профиля ПК
  `~/.antigravity-private/mcp-oauth`, вне Git/APK/экспорта пакетов. Ключ vault
  хранится отдельно от ciphertext в том же защищённом профиле: это не системный
  аппаратный keystore; доступ к профилю ОС должен быть ограничен владельцем.
- Поставщики без dynamic registration требуют `oauth.clientId` в приватной
  записи MCP, зарегистрированный для native loopback redirect URI. Если поставщик
  не допускает этот public client/redirect, вход честно недоступен до регистрации.
- Автотест использует настоящий локальный HTTP OAuth/MCP-сервер; localhost
  разрешён только явной тестовой опцией. В production discovery/обмен требуют
  HTTPS, публичного адреса и DNS pinning, HTTP redirects запрещены.

Дескриптор подтверждает путь подключения, не успешный вход конкретного аккаунта.
Реальный вход и tools/call к трём внешним поставщикам требуют пользовательских
учётных данных; без них позиции остаются «требует проверки».

## 18. Внешние официальные адаптеры — живые проверки (3 ноября 2026)

Пункт «внешние официальные адаптеры» плана: реальные initialize → tools/list →
tools/call к официальным дескрипторам `extensions/official-mcp.json`.

- **Desktop Commander 0.3.1 — проверен живьём**
  (`runtimeVerification: verified-live-2026-11-03`). Node v24.22.2 ≥ engines
  `>=22.12.0`; запуск `node dist/index.js` пакета, установленного в изолированный
  scratch-каталог (эквивалентно дескриптору `npx -y
  @wonderwhy-er/desktop-commander@0.3.1`). Рукопожатие через мост
  (`POST /api/mcp {"action":"probe"}`): status `connected`, protocolVersion
  `2025-06-18`, serverInfo `desktop-commander 0.3.1`, tools/list — 25
  инструментов (read_file, list_directory, start_process, …). Реальные
  tools/call: `list_directory` — фактический листинг каталога;
  `read_file C:\pagefile.sys` — честный отказ `isError:true` («EBUSY: resource
  busy or locked») без выдуманного успеха. Регистрация в мосту была временной и
  удалена после проверки (`action:"remove"`); базовое состояние конфигурации
  восстановлено (`ag-workstation-info, rea`). Постоянная установка файлового/
  Shell-адаптера в конфигурацию пользователя не выполнялась — это его явное
  решение (одна команда `agy mcp add`). Постинсталл-скрипты пакета
  (track-installation, verify-ripgrep) выполнялись только при установке в
  scratch-каталог, вне рабочего окружения.
- **GitHub MCP — граница авторизации подтверждена, tools/call «требует
  проверки»**. `initialize` к официальному endpoint
  `https://api.githubcopilot.com/mcp/` без токена → **HTTP 401** (пустое тело):
  рукопожатие без учётных данных не проходит. `gh` установлен, но входа нет
  (`gh auth status` — not logged in). Для живого tools/call нужен токен/вход
  пользователя — не выполнялось.
- **Bright Data MCP 4.1.1 — дескриптор подтверждён, запуск «требует проверки»**.
  `npm view @brightdata/mcp@4.1.1` подтверждает версию пакета в npm-registry.
  Запуск и tools/call не выполнялись: требуется `API_TOKEN` аккаунта пользователя
  (в приватном MCP-конфиге, никогда в каталоге); использование провайдера может
  тарифицироваться.

Артефакты: `extensions/official-mcp.json` — desktop-commander
`verified-live-2026-11-03`, bright-data/github остаются `not-run` (запуск без
учётных данных не выполнялся), заметки содержат фактические наблюдения;
`pc-bridge/test/extensions-blockers.test.js` — ожидание runtimeVerification
закреплено по серверам; `extensions-adapter-matrix.json` и
`extensions-migration-coverage.json` пересобраны.

Границы: проверка шла через ПК-мост (executionTarget дескрипторов — pc-bridge);
телефонный UI-маршрут внешних адаптеров отдельно не прогонялся. Ответ 401 GitHub
— публичное наблюдение границы авторизации, не попытка обхода. Каталожная истина
сохраняется: дескриптор ≠ установленный пакет ≠ вход ≠ живой tools/call; каждый
уровень помечен своим наблюдением.

## 19. Матрица приёмки — сквозные прогоны (3 ноября 2026)

Живые прогоны сценариев §7 плана на эмуляторе Pixel_8a + production-мост.
Доказательства: `verification_screenshots/m2_*.png` (вне Git) и записи
`pc-bridge/bridge_requests.log`. Дисциплина стоимости: по одному короткому
промпту на ячейку (по два на ячейках восстановления — второй это «Повторить»).

| Ячейка | Статус | Наблюдение (живьём) |
|---|---|---|
| Телефон+CLI-инференс | пройдено | UI-чат, Gemini 4 Argon (High), «2+2» → «2 + 2 = 4.» (`m2_phone_cli.png`; log `turnId turn_1793689054297_5786`, `effectiveCliModel=gemini-4-argon-high`) |
| Телефон+API | требует проверки (нет ключа Gemini API) | Переключение модели доступно и выполнено («Gemini 2.5 Pro — Прямой Gemini API», «Выбрано»); на «3+3» — честное сообщение «Для выбранной модели укажите ключ Gemini API. Режим телефона не требует PC-Bridge.»; вызов модели не выполнялся (`m2_phone_api.png`) |
| ПК+CLI | пройдено | `POST /api/chat` (PC_REMOTE, gemini-4-argon-high) → HTTP 200, ok:true, reply «10» (`turnId …snooi`, 13825 мс; запись в bridge_requests.log) |
| Отключённый ПК | пройдено | Мост убит (PID 15896) → «Мост ПК недоступен — запрос не отправлен. Переписка сохранена на телефоне; после восстановления связи нажмите «Повторить»» — без имитации успеха; мост перезапущен (health 200), «Повторить» → «4 + 4 = 8.» (`m2_pc_down.png`, `m2_pc_recovered.png`) |
| Нет сети | пройдено | Авиарежим on → та же честная ошибка «запрос не отправлен»; авиарежим off → «Повторить» → «5 + 5 = 10.» (`m2_no_network.png`, `m2_network_recovered.png`) |
| Длинный ответ | пройдено | 20 пунктов + «Итог» (a11y-текст 2536 символов) без обрезки; прокрутка до хвоста работает (`m2_long_answer.png`, `m2_long_answer_scrolled.png`) |
| Stop и перезапуск | пройдено | Остановка генерации через ~3 с → «Генерация ответа остановлена.»; `am force-stop` + перезапуск: история, prompt и маркер остановки сохранены, модель сохранена, контекст 2.0K→2.4K/1M консистентен (`m2_stopped_bottom.png`, `m2_after_restart.png`) |
| Недостаточные права | пройдено | Живой tools/call desktop-commander `read_file C:\pagefile.sys` → `isError:true` (EBUSY) без маскировки успеха (см. §18) |
| Истёкший OAuth | требует проверки (внешний аккаунт) | Не прогонялся: нужен внешний OAuth-аккаунт провайдера; отказы refresh/invalid_grant покрыты локальными сквозными OAuth-тестами |

Итог: 7 из 9 ячеек закрыто живыми наблюдениями (шесть прогонами + одно
доказательством внешнего адаптера); две остаются «требует проверки» с
конкретной причиной (ключ Gemini API; внешний аккаунт). Это ещё не полная
приёмка плана: платные сквозные прогоны моделей и проверка физического телефона
остаются отдельными пунктами.

Побочное наблюдение (устаревшее состояние карточек). При старте прогонов экран
«Плагины → Мои» показал antigravity-workspace-guard v1.2.0 и
kotlin-compose-inspector v2.0.0 со статусом «⚠ заблокирован» — значения 2 ноября
до sync-обновления до v1.3.0/v2.1.0 (эпоха defect №1). Текущие данные бэкенда
(`/api/plugins`, plugin.json на диске, матрица) — v1.3.0/v2.1.0, enabled, без
блокировок; немедленная повторная проверка живого экрана — «✓ Готов»,
«Установленные плагины (2)» (`s9_plugins_my_recheck.png`). Регрессии в данных
нет: ветка наблюдала устаревшее представление карточек при старте (вероятно,
кэш/восстановленное состояние приложения). Механизм устаревания не установлен —
«требует проверки» (прямого кэша каталога в SharedPreferences-ключах не
найдено). Критерий «не висят заблокированными» на текущем живом экране
выполняется. Артефакты автоматизации ввода (дублирование символов `adb input
text`) к приложению не относятся.
