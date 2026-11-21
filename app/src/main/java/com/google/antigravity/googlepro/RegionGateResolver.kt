package com.google.antigravity.googlepro

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Чем оказался ответ провайдера. */
enum class AnswerKind {
    /** Адрес отличается от эталонного — это прокси в разрешённом регионе. */
    SUBSTITUTED,

    /** Совпал с эталоном: провайдер не подменяет имя, пользы от маршрута нет. */
    PASSTHROUGH,

    /** Ответа нет. */
    NONE
}

/**
 * Классификация ответа провайдера относительно эталонного резолвера.
 *
 * Отдельная чистая функция: правило «подмена = отличие от эталона» — то самое,
 * которым анлокер оживляет упавший провайдер без релиза (src/resolvers.rs).
 * Заглушки DPI выбрасываются: иначе отравленный эталон делает подменой всё.
 */
internal fun classifyAnswer(
    providerAddresses: List<String>,
    referenceAddresses: List<String>,
    stubs: List<String> = GoogleProConfig.REFERENCE_STUBS
): AnswerKind {
    val stubs = stubs.toSet()
    val provider = providerAddresses.filter { it.isNotBlank() }.toSet() - stubs
    if (provider.isEmpty()) return AnswerKind.NONE
    val reference = referenceAddresses.filter { it.isNotBlank() }.toSet() - stubs
    // Эталона нет (или он весь из заглушек) — доказать нечего, верим провайдеру.
    if (reference.isEmpty()) return AnswerKind.SUBSTITUTED
    return if (provider == reference) AnswerKind.PASSTHROUGH else AnswerKind.SUBSTITUTED
}

/**
 * Разрешение хостов CloudCode по выбранному маршруту.
 *
 * Каждый маршрут опрашивается поодиночке, а не гонкой: гонка полезна, когда
 * маршрут выбирается сам, но леджер уже выбрал маршрут по тому, где реально
 * ответила модель, и подмена выбора внутри запроса только запутала бы счёт.
 */
class RegionGateResolver(
    private val dohClient: OkHttpClient = defaultDohClient()
) {

    data class Verdict(val addresses: List<String>, val kind: AnswerKind)

    private data class Cached(val verdict: Verdict, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val referenceCache = ConcurrentHashMap<String, Cached>()
    private val udpIds = AtomicInteger(Random.nextInt(1, 0xFFFF))
    private val dohMediaType = "application/dns-message".toMediaType()

    /** Значения, которые подставляем в OkHttp `Dns` для нужного маршрута. */
    fun dnsFor(route: GateRoute): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!isGateHost(hostname)) return Dns.SYSTEM.lookup(hostname)
            val verdict = runCatching { resolve(hostname, route) }.getOrNull()
            val addresses = verdict?.addresses?.map { InetAddress.getByName(it) }.orEmpty()
            return addresses.ifEmpty { Dns.SYSTEM.lookup(hostname) }
        }
    }

    fun resolve(host: String, route: GateRoute): Verdict? {
        if (route == GateRoute.DIRECT) {
            val addresses = runCatching { Dns.SYSTEM.lookup(host).map { it.hostAddress ?: "" } }.getOrDefault(emptyList())
            return addresses.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { Verdict(it, AnswerKind.PASSTHROUGH) }
        }
        val key = "${route.name}|$host"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < CHOICE_TTL_MS) return it.verdict }
        val provider = when (route) {
            GateRoute.DOH_DNS_AI -> queryDoh(host)
            GateRoute.UDP_GEOHIDE -> queryUdp(host, GoogleProConfig.UDP_GEOHIDE_ADDRESSES)
            GateRoute.DIRECT -> emptyList()
        }
        val kind = classifyAnswer(provider, referenceFor(host))
        val verdict = Verdict(provider, kind).takeIf { kind != AnswerKind.NONE }
        cache[key] = Cached(verdict ?: Verdict(emptyList(), AnswerKind.NONE), System.currentTimeMillis())
        return verdict
    }

    fun isGateHost(host: String): Boolean =
        GoogleProConfig.GATE_HOSTS.any { it.equals(host, ignoreCase = true) }

    /** Отпечаток сети: смена Wi-Fi/мобильной сети/VPN обнуляет доказательства леджера. */
    fun fingerprint(hint: String): String = hint

    /** Прервать открытые соединения маршрута, от которого пришёл отказ региона. */
    fun flush(route: GateRoute) = Unit

    // --- транспорт ---------------------------------------------------------

    private fun referenceFor(host: String): List<String> {
        referenceCache[host]?.let { if (System.currentTimeMillis() - it.at < REFERENCE_TTL_MS) return it.verdict.addresses }
        val answers = LinkedHashSet<String>()
        for (address in GoogleProConfig.REFERENCE_ADDRESSES) {
            answers += queryUdp(host, listOf(address))
            if (answers.isNotEmpty()) break
        }
        val verdict = Verdict(answers.toList(), AnswerKind.PASSTHROUGH)
        referenceCache[host] = Cached(verdict, System.currentTimeMillis())
        return verdict.addresses
    }

    private fun queryDoh(host: String): List<String> = runCatching {
        val id = udpIds.incrementAndGet() and 0xFFFF
        val request = Request.Builder()
            .url("https://${GoogleProConfig.DOH_HOST}${GoogleProConfig.DOH_PATH}")
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .post(DnsWire.query(id, host).toRequestBody(dohMediaType))
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.bytes() ?: return emptyList()
            DnsWire.answerAddresses(body, id)
        }
    }.getOrDefault(emptyList())

    private fun queryUdp(host: String, servers: List<String>): List<String> {
        val id = udpIds.incrementAndGet() and 0xFFFF
        val query = DnsWire.query(id, host)
        for (server in servers) {
            val answer = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = UDP_TIMEOUT_MS
                    socket.connect(InetSocketAddress(server, 53))
                    socket.send(DatagramPacket(query, query.size))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    DnsWire.answerAddresses(buffer.copyOf(packet.length), id)
                }
            }.getOrDefault(emptyList())
            if (answer.isNotEmpty()) return answer
        }
        return emptyList()
    }

    private companion object {
        const val CHOICE_TTL_MS = 5 * 60_000L
        const val REFERENCE_TTL_MS = 60_000L
        const val UDP_TIMEOUT_MS = 900

        /**
         * Клиент DoH. Адреса `dns.dns-ai.ru` прописаны, чтобы имя разрешалось до
         * любого системного DNS: сертификат всё равно доказывает имя, поэтому
         * протухший адрес ломает рукопожатие, а не превращается в посредника.
         */
        fun defaultDohClient(): OkHttpClient {
            val bootstrap = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.equals(GoogleProConfig.DOH_HOST, ignoreCase = true)) {
                        GoogleProConfig.DOH_ADDRESSES.map { InetAddress.getByName(it) }
                    } else Dns.SYSTEM.lookup(hostname)
            }
            return OkHttpClient.Builder()
                .dns(bootstrap)
                .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
