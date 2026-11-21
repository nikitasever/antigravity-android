package com.google.antigravity.googlepro

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.network.ProviderReply
import java.io.File

/**
 * Сборка подписочного маршрута в одном месте: токены, DNS-обход и леджер
 * доказательств делят один каталог файлов и один отпечаток сети.
 *
 * Отпечаток — тип сети и имя интерфейса. Смена Wi-Fi/мобильной сети/VPN меняет
 * его, и леджер перестаёт верить прошлым приговорам (src/routes.rs): за одним
 * VPN отказавший маршрут может быть ровно тем, что нужно без него.
 */
class GoogleProProvider(context: Context) {

    private val app = context.applicationContext
    private val dir: File = app.noBackupFilesDir

    private val tokenHttp = defaultTokenHttp()

    val tokens = GoogleProTokenStore(dir, tokenHttp)
    val resolver = RegionGateResolver()
    private val ledger = RouteLedger(File(dir, "google-pro-routes.json"))
    /**
     * Обход региона: выключение оставляет только прямой путь (системный DNS) —
     * так же, как переключатели окна анлокера по отдельности откатывают правку.
     */
    @Volatile
    var regionBypass: Boolean = true

    private val client = CloudCodeClient(tokens, resolver, ledger, { fingerprint() }, { regionBypass })
    private val projects = GoogleProProjects(tokenHttp)

    val signedIn: Boolean get() = tokens.signedIn
    val email: String get() = tokens.email

    fun routeSummary(): String = client.routeSummary()

    fun diagnostics(): String = buildString {
        appendLine("Сеть: ${fingerprint()}")
        appendLine("Маршруты: ${client.routeSummary()}")
        if (tokens.project.isNotBlank()) appendLine("Проект тарифа: ${tokens.project}")
        lastState?.let { state ->
            appendLine("Тариф: ${state.tierName.ifBlank { "не назван" }}")
            if (state.ineligibleReasons.isNotEmpty()) appendLine("Проверка региона: ${state.ineligibleReasons.joinToString("; ")}")
            if (state.models.isNotEmpty()) appendLine("Модели сервера: ${state.models.joinToString(", ")}")
        }
    }

    val lastState: ProState? get() = client.lastState

    /**
     * Проверка входа. Если сервер требует назвать проект, онбординг выполняется
     * здесь же — иначе каждая генерация упиралась бы в «нет лицензии».
     */
    suspend fun check(): ProState {
        var state = client.loadCodeAssist()
        if (state.needsProject) {
            val project = tokens.project.ifBlank { projects.firstProject(tokens.accessToken()) }
            if (project.isNotBlank()) {
                client.onboard(project, state.defaultTierId.ifBlank { "standard-tier" })
                state = client.loadCodeAssist()
            }
        }
        return state
    }

    suspend fun reply(
        modelId: String,
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onThinking: suspend (String) -> Unit
    ): ProviderReply = client.generate(remoteProModel(modelId), prompt, systemPrompt, history, onThinking).first

    private fun fingerprint(): String {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "нет-сети"
        val active = manager.activeNetwork ?: return "нет-сети"
        val capabilities = manager.getNetworkCapabilities(active) ?: return "нет-сети"
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        // netId в отпечаток не идёт: он меняется при каждом переподключении.
        return kind
    }
}
