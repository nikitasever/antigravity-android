package com.google.antigravity.googlepro

import org.json.JSONObject
import java.io.File

/**
 * Таблица доказательств: какой маршрут уже приносил ответ модели, а какой
 * приносил отказ региона.
 *
 * Скорость здесь только тай-брейк. Проверка региона невидима для проб (любой
 * выход отвечает 401 на неавторизованный запрос), поэтому видит её только сам
 * клиент: ответ модели — доказательство, `User location is not supported` —
 * приговор. Правило и порядок взяты из src/routes.rs анлокера.
 *
 * Доказательства привязаны к сети: за одним VPN отказавший маршрут может быть
 * ровно тем, что нужно без него. Смена сети обнуляет доказательства и сохраняет
 * замеры времени.
 */
class RouteLedger(private val file: File) {

    internal data class Entry(
        var okAt: Long = 0,
        var refusals: Int = 0,
        var lastRefusalAt: Long = 0,
        var lastMs: Long = 0,
        var fingerprints: Int = 0
    )

    private var fingerprint: String = ""
    private val entries = HashMap<GateRoute, Entry>()

    fun load(fingerprint: String) {
        entries.clear()
        this.fingerprint = fingerprint
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        if (json.optString("fingerprint") != fingerprint) return
        val routes = json.optJSONObject("routes") ?: return
        for (route in GateRoute.entries) {
            val item = routes.optJSONObject(route.name) ?: continue
            entries[route] = Entry(
                okAt = item.optLong("okAt", 0),
                refusals = item.optInt("refusals", 0),
                lastRefusalAt = item.optLong("lastRefusalAt", 0),
                lastMs = item.optLong("lastMs", 0),
                fingerprints = item.optInt("fingerprints", 0)
            )
        }
    }

    /** Порядок попыток в текущей сети. */
    fun order(fingerprint: String, default: List<GateRoute> = GoogleProConfig.DEFAULT_ROUTE_ORDER): List<GateRoute> {
        if (fingerprint != this.fingerprint) {
            // Новая сеть: прошлые приговоры больше не действуют, замеры остаются.
            this.fingerprint = fingerprint
            entries.values.forEach { it.okAt = 0; it.refusals = 0; it.lastRefusalAt = 0 }
            save()
        }
        val proven = mutableListOf<GateRoute>()
        val fresh = mutableListOf<GateRoute>()
        val refused = mutableListOf<GateRoute>()
        for (route in default.distinct()) {
            val entry = entries[route]
            when {
                entry == null -> fresh += route
                entry.okAt > entry.lastRefusalAt -> proven += route
                entry.refusals > 0 -> refused += route
                else -> fresh += route
            }
        }
        proven.sortBy { entries[it]?.lastMs ?: Long.MAX_VALUE }
        refused.sortWith(compareBy<GateRoute> { entries[it]?.refusals ?: 0 }.thenBy { entries[it]?.lastMs ?: Long.MAX_VALUE })
        return proven + fresh + refused
    }

    fun success(route: GateRoute, elapsedMs: Long) {
        val entry = entries.getOrPut(route) { Entry() }
        entry.okAt = System.currentTimeMillis()
        entry.refusals = 0
        entry.lastRefusalAt = 0
        if (elapsedMs in 1..MAX_MS) entry.lastMs = elapsedMs
        save()
    }

    fun refusal(route: GateRoute) {
        val entry = entries.getOrPut(route) { Entry() }
        entry.refusals = (entry.refusals + 1).coerceAtMost(99)
        entry.lastRefusalAt = System.currentTimeMillis()
    }

    fun unreachable(route: GateRoute) {
        val entry = entries.getOrPut(route) { Entry() }
        entry.refusals = (entry.refusals + 1).coerceAtMost(99)
    }

    fun refusedRoutes(): List<GateRoute> = entries.filterValues { it.lastRefusalAt > it.okAt && it.refusals > 0 }.keys.toList()

    private fun save() {
        val routes = JSONObject()
        for ((route, entry) in entries) {
            routes.put(route.name, JSONObject()
                .put("okAt", entry.okAt)
                .put("refusals", entry.refusals)
                .put("lastRefusalAt", entry.lastRefusalAt)
                .put("lastMs", entry.lastMs)
                .put("fingerprints", entry.fingerprints))
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject().put("fingerprint", fingerprint).put("routes", routes).toString())
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    private companion object {
        const val MAX_MS = 120_000L
    }
}
