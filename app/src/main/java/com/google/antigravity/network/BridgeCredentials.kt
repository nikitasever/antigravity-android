package com.google.antigravity.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Exact-origin scope; publicly routed plaintext HTTP never carries owner credentials. */
internal fun bridgeOrigin(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    val host = parsed.host
    val parts = host.split('.').map { it.toIntOrNull() }
    val lan = host == "localhost" || host == "::1" || (parts.size == 4 && parts.all { it != null && it in 0..255 } &&
        (parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1]!! in 16..31)))
    if (!parsed.isHttps && !lan) return null
    return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
}

internal const val BRIDGE_TOKEN_HEADER = "X-Antigravity-Bridge-Token"

internal class BridgeAuthInterceptor(private val tokenFor: (String) -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val token = bridgeOrigin(request.url.toString())?.let(tokenFor)?.takeIf { Regex("[a-f0-9]{64}").matches(it) }
        // Provider APIs use /api/v1; private bridge routes must never send prompts,
        // provider keys or voice text to an old, unpaired tunnel.
        val path = request.url.encodedPath
        if (path.startsWith("/api/") && !path.startsWith("/api/v1/") && token == null) {
            throw java.io.IOException("Сначала выполните сопряжение с ПК в настройках подключения.")
        }
        val outgoing = request.newBuilder().removeHeader(BRIDGE_TOKEN_HEADER).apply {
            if (token != null) header(BRIDGE_TOKEN_HEADER, token)
        }.build()
        return chain.proceed(outgoing)
    }
}

/** Non-exportable Android Keystore key; only encrypted, origin-bound credentials in app prefs. */
internal class BridgeCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bridge_credentials_v1", Context.MODE_PRIVATE)
    private val alias = "antigravity_bridge_owner_v1"
    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun name(origin: String) = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
    fun tokenFor(url: String): String? = runCatching {
        val origin = bridgeOrigin(url) ?: return null
        val value = prefs.getString(name(origin), null) ?: return null
        val parts = value.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        cipher.updateAAD(origin.toByteArray())
        String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8).takeIf { Regex("[a-f0-9]{64}").matches(it) }
    }.getOrNull()
    fun save(url: String, token: String) {
        val origin = requireNotNull(bridgeOrigin(url))
        require(Regex("[a-f0-9]{64}").matches(token))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(origin.toByteArray()) }
        val bytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name(origin), Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(bytes)).commit())
    }
    fun forget(url: String) { bridgeOrigin(url)?.let { prefs.edit().remove(name(it)).commit() } }
    fun interceptor() = BridgeAuthInterceptor(::tokenFor)
    companion object { private val lock = Any() }
}
