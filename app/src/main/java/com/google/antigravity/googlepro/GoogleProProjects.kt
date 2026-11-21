package com.google.antigravity.googlepro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Выбор проекта Google Cloud для тарифа.
 *
 * Сервер отвечает `userDefinedCloudaicompanionProject: true` — проект обязан
 * назвать сам клиент. Настольный клиент показывает список; мы выбираем сами,
 * чтобы вход не превращался в анкету, и предпочитаем «дефолтный» проект
 * клиента Gemini: он уже создан под эти цели.
 */
internal fun rankProjects(json: JSONObject): List<String> {
    val projects = json.optJSONArray("projects") ?: return emptyList()
    val alive = mutableListOf<String>()
    for (i in 0 until projects.length()) {
        val item = projects.optJSONObject(i) ?: continue
        if (item.optString("lifecycleState") != "ACTIVE") continue
        val id = item.optString("projectId")
        if (id.isNotBlank()) alive += id
    }
    // Дефолтный проект Gemini — тот, который Google сам завёл под клиента.
    return alive.sortedBy { projectRank(it) }
}

/** Меньше — предпочтительнее: `gen-lang-client-*` и «Default Gemini Project». */
internal fun projectRank(projectId: String): Int = when {
    projectId.startsWith("gen-lang-client-") -> 0
    projectId.contains("gemini") -> 1
    else -> 2
}

/** Первый пригодный проект аккаунта; пусто — если проектов нет. */
class GoogleProProjects(private val http: OkHttpClient) {

    suspend fun firstProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GoogleProConfig.PROJECTS_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext ""
            response.body?.string().orEmpty()
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext ""
        rankProjects(json).firstOrNull().orEmpty()
    }
}
