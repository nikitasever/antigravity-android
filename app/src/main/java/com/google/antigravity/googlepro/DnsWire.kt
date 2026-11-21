package com.google.antigravity.googlepro

/**
 * Минимальный кодек DNS-сообщений: только A/AAAA-вопрос и разбор ответа.
 *
 * Полный парсер с CNAME, EDNS и DNSSEC здесь не нужен: у нас два вопроса —
 * «какой адрес у хоста» к провайдеру и к эталонному резолверу, — и оба
 * укладываются в одну секцию ответа. Всё, что не уложилось, считается
 * «нет ответа», а не выдуманным адресом.
 */
internal object DnsWire {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5

    /** Вопрос с рекурсией (RD=1) и случайным идентификатором вызывающего. */
    fun query(id: Int, host: String, type: Int = TYPE_A): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // RD
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QDCOUNT
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write((type ushr 8) and 0xFF); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01) // IN
        return out.toByteArray()
    }

    /**
     * Адреса из секции ответа. Ответ с чужим идентификатором и ответ без единой
     * A/AAAA-записи одинаково дают пустой список.
     */
    fun answerAddresses(packet: ByteArray, expectedId: Int): List<String> {
        if (packet.size < 12) return emptyList()
        if (readU16(packet, 0) != expectedId) return emptyList()
        val flags = readU16(packet, 2)
        if (flags and 0x8000 == 0) return emptyList() // не ответ
        val questions = readU16(packet, 4)
        val answers = readU16(packet, 6)
        var offset = 12
        repeat(questions) {
            offset = skipName(packet, offset)
            offset += 4
        }
        val addresses = ArrayList<String>(answers)
        repeat(answers) {
            offset = skipName(packet, offset)
            if (offset + 10 > packet.size) return addresses
            val type = readU16(packet, offset)
            val length = readU16(packet, offset + 8)
            val data = offset + 10
            if (data + length > packet.size) return addresses
            when {
                type == TYPE_A && length == 4 -> addresses.add(
                    (0 until 4).joinToString(".") { (packet[data + it].toInt() and 0xFF).toString() }
                )
                type == TYPE_AAAA && length == 16 -> {
                    val hex = (0 until 16 step 2).joinToString(":") {
                        "%02x%02x".format(packet[data + it].toInt() and 0xFF, packet[data + it + 1].toInt() and 0xFF)
                    }
                    addresses.add(hex)
                }
            }
            offset = data + length
        }
        return addresses
    }

    private fun skipName(packet: ByteArray, from: Int): Int {
        var offset = from
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += length + 1
        }
        return offset
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        if (offset + 1 >= bytes.size) -1
        else ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
