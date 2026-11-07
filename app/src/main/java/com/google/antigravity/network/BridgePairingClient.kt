package com.google.antigravity.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class BridgePairingClient(private val credentials: BridgeCredentials) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    suspend fun pair(url: String, code: String): Boolean = withContext(Dispatchers.IO) {
        val origin = bridgeOrigin(url) ?: return@withContext false
        if (!Regex("[0-9]{6}").matches(code)) return@withContext false
        val request = Request.Builder().url("$origin/api/pair")
            .post(JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).useCancellable { response ->
            if (response.code != 200) return@useCancellable false
            val json = JSONObject(response.body?.string().orEmpty())
            val token = json.optString("bridgeToken")
            if (!json.optBoolean("ok") || !Regex("[a-f0-9]{64}").matches(token)) return@useCancellable false
            credentials.save(origin, token); true
        }
    }
    suspend fun browserUrl(url: String, session: String): String? = withContext(Dispatchers.IO) {
        val origin = bridgeOrigin(url) ?: return@withContext null
        val token = credentials.tokenFor(origin) ?: return@withContext null
        val request = Request.Builder().url("$origin/api/web-ticket").header(BRIDGE_TOKEN_HEADER, token)
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).useCancellable { response ->
            if (response.code != 200) return@useCancellable null
            val ticket = JSONObject(response.body?.string().orEmpty()).optString("ticket")
            if (!Regex("[a-f0-9]{64}").matches(ticket)) return@useCancellable null
            "$origin/web".toHttpUrl().newBuilder().addQueryParameter("ticket", ticket).addQueryParameter("session", session).build().toString()
        }
    }
}
