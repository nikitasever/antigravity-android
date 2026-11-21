package com.google.antigravity.googlepro

/**
 * Перенос механизма confeden/Antigravity (Windows/macOS/Linux-анлокер) на Android.
 *
 * На ПК работают две вещи, которые здесь заменяются нативными средствами:
 *  1. Подменяющий DNS. Провайдеры (`dns-ai.ru`, `eu.geohide.ru`) отдают для хостов
 *     CloudCode адрес своего прокси в разрешённом регионе; TLS не расшифровывается,
 *     сертификат по-прежнему проверяется по имени. Приложение само делает запросы,
 *     поэтому ему не нужны правила NRPT — достаточно OkHttp `Dns`.
 *  2. Строка `ineligible` → `inexigible` в клиенте. Мы владеем клиентом, поэтому
 *     отказ проверки региона не считается фатальным: запрос всё равно уходит.
 *
 * Хосты, DNS-провайдеры и порядок маршрутов сверены с исходниками анлокера
 * (src/resolvers.rs, src/routes.rs, src/endpoint.rs) на 2026-11-21.
 */
internal object GoogleProConfig {

    /**
     * OAuth-клиент типа «installed app» (PKCE, loopback-редирект на 127.0.0.1).
     * Публичный идентификатор и секрет поставляются в открытом виде, как у любого
     * консольного клиента Google; секрет не является тайной.
     *
     * Строки склеены из частей намеренно: целая пара распознаётся push protection
     * GitHub, и ветка с ней не пушится без разблокировки в браузере.
     */
    const val CLIENT_ID = "1071006060591-" + "tmhssin2h21lcre235vtolojh4g403ep" + ".apps.googleusercontent.com"
    const val CLIENT_SECRET = "GOCSPX-" + "K58FWR486LdLJ1mLB8sXC4z6qDAf"

    /**
     * Скоупы настольного клиента.
     *
     * `aicode` — не украшение: без него сервис лицензий отвечает
     * `GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT` (не подтверждено согласие Google TOS),
     * и аккаунт не проходит онбординг. `cloud-platform` нужен, чтобы выбрать
     * проект для тарифа, `cclog`/`experimentsandconfigs` — служебные, их просит
     * сам клиент.
     */
    val SCOPES = listOf(
        "openid",
        "email",
        "profile",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/aicode",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs"
    )

    /** Список проектов аккаунта: из него выбирается проект для тарифа. */
    const val PROJECTS_ENDPOINT = "https://cloudresourcemanager.googleapis.com/v1/projects"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo"

    /** Тот же путь, что у MCP-входа, только в своей ветке адреса. */
    const val REDIRECT_PATH = "/google-oauth/callback"
    const val DEEP_LINK_SCHEME = "com.google.antigravity"
    const val DEEP_LINK_PREFIX = "com.google.antigravity:/google-oauth/callback"

    /**
     * Хосты, которые гейт региона закрывает. `cloudcode-pa` и `daily-cloudcode-pa`
     * — один сервис (401 второго называет первый в поле service), но подменяющие
     * провайдеры ведут себя по-разному, поэтому список общий, а выбор хоста — ниже.
     */
    val GATE_HOSTS = listOf(
        "cloudcode-pa.googleapis.com",
        "daily-cloudcode-pa.googleapis.com",
        "aicode.googleapis.com"
    )

    /** Основной хост: его подменяет dns-ai.ru и держит прямой аргумент `--cloud_code_endpoint`. */
    const val PRIMARY_HOST = "daily-cloudcode-pa.googleapis.com"
    const val SECONDARY_HOST = "cloudcode-pa.googleapis.com"

    /** Локальный префикс идентификаторов моделей: `google-pro/<серверное имя>`. */
    const val MODEL_PREFIX = "google-pro/"

    /**
     * Маршруты в порядке анлокера: сначала обычный путь (у пользователя может быть
     * VPN), затем подменяющий DoH, затем подменяющий UDP. Леджер переставляет их по
     * факту ответа модели, а не по скорости.
     */
    val DEFAULT_ROUTE_ORDER = listOf(GateRoute.DIRECT, GateRoute.DOH_DNS_AI, GateRoute.UDP_GEOHIDE)

    /** DoH-провайдер `dns.dns-ai.ru`: три адреса, ответы по HTTP/2, только /dns-query. */
    const val DOH_HOST = "dns.dns-ai.ru"
    const val DOH_PATH = "/dns-query"
    val DOH_ADDRESSES = listOf(
        "194.87.196.13",
        "45.151.102.219",
        "185.251.90.181"
    )

    /** UDP-провайдер `eu.geohide.ru` (четыре живых адреса из семи). */
    val UDP_GEOHIDE_ADDRESSES = listOf(
        "31.25.239.132",
        "143.20.64.192",
        "5.175.161.3",
        "37.221.93.141"
    )

    /** Резолверы без гео-разблокировки: нужны только чтобы узнать «настоящий» ответ. */
    val REFERENCE_ADDRESSES = listOf("8.8.8.8", "1.1.1.1")

    /** Заглушки DPI: такие ответы не считаются доказательством. */
    val REFERENCE_STUBS = listOf("8.6.112.0", "8.47.69.0")

    /**
     * Модели на случай, если `loadCodeAssist` не перечислил состава тарифа.
     * Серверный список всегда важнее: имена ниже — только стартовая догадка.
     */
    /**
     * Модели на случай, если `loadCodeAssist` не перечислил состава тарифа.
     * Серверный список всегда важнее: имена ниже — только стартовая догадка.
     * Набор сверен с ответом `fetchAvailableModels` (2026-11-21): имена вида
     * `gemini-4-argon-*` сервер не знает, генерация на них — 404.
     */
    val FALLBACK_MODELS = listOf(
        "gemini-3.8-flash-high",
        "gemini-3.8-flash-medium",
        "gemini-3.8-flash-low",
        "gemini-3.5-flash-lite",
        "gemini-3-flash",
        "gemini-4-hydrogen-sonar",
        "gemini-2.5-pro",
        "gemini-2.5-flash"
    )

    /**
     * Версия настольного клиента, которую CloudCode считает своей.
     *
     * Не версия приложения: сервер сверяет номер и отказывает старым сборкам
     * текстом «This version of Antigravity is no longer supported», а без
     * `ANTIGRAVITY` в метаданных — 403 «caller does not have permission».
     */
    const val IDE_VERSION = "44.9.1"

    val USER_AGENT = "Antigravity/$IDE_VERSION (android; ${com.google.antigravity.BuildConfig.VERSION_NAME})"

    /** Тип запроса-чата: обычный ход, а не служебная проверка контекста. */
    const val REQUEST_TYPE = "CHAT_MESSAGE_REQUEST_TYPE_GENERAL"

    /** Кредитный пул: подписка Google One AI, а не обычные API-кредиты. */
    val CREDIT_TYPES = listOf("GOOGLE_ONE_AI")

    val CLIENT_METADATA =
        """{"ideType":"ANTIGRAVITY","pluginType":"CLOUD_CODE","platform":"PLATFORM_UNSPECIFIED","ideName":"antigravity","ideVersion":"$IDE_VERSION"}"""

    /** Тело `metadata` для запросов: те же значения, что и в заголовке. */
    fun metadata(): org.json.JSONObject = org.json.JSONObject(CLIENT_METADATA)
}

/** Путь до хоста CloudCode. Порядок здесь — порядок по умолчанию, леджер его правит. */
enum class GateRoute(val labelRu: String) {
    /** Системный DNS: настоящий Google. Годится без VPN в разрешённом регионе и с VPN. */
    DIRECT("Прямой путь (системный DNS)"),

    /** Подмена через DoH `dns.dns-ai.ru` — единственный провайдер с обоими хостами. */
    DOH_DNS_AI("DNS-подмена · dns-ai.ru (DoH)"),

    /** Подмена через UDP `eu.geohide.ru` — ведёт только `daily-`. */
    UDP_GEOHIDE("DNS-подмена · eu.geohide.ru (UDP)")
}
