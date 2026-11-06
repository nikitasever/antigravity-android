package com.google.antigravity.device

import org.json.JSONObject
import java.io.Reader

data class PhoneTextReadRange(val offset: Long = 0, val limit: Int = 3000) {
    init {
        require(offset >= 0) { "offset должен быть неотрицательным целым числом" }
        require(limit in 2..3000) { "limit должен быть целым числом от 2 до 3000" }
    }
    companion object {
        fun fromJson(obj: JSONObject): PhoneTextReadRange {
            fun integer(name: String, fallback: Long): Long = if (!obj.has(name)) fallback else
                obj.opt(name)?.toString()?.toLongOrNull()
                    ?: throw IllegalArgumentException("$name должен быть целым числом")
            val limit = integer("limit", 3000)
            require(limit in 2..3000) { "limit должен быть целым числом от 2 до 3000" }
            return PhoneTextReadRange(integer("offset", 0), limit.toInt())
        }
    }
}

data class PhoneTextPage(val offset: Long, val text: String, val hasMore: Boolean) {
    val nextOffset: Long get() = Math.addExact(offset, text.length.toLong())
    fun toolOutput(): String = JSONObject().put("offset", offset).put("count", text.length)
        .put("nextOffset", nextOffset).put("hasMore", hasMore).put("unit", "UTF-16").toString() + "\n" + text
}

/** Constant-size buffers; offset/count use UTF-16 units, never byte offsets. */
fun readPhoneTextPage(reader: Reader, offset: Long, limit: Int): PhoneTextPage {
    require(offset >= 0) { "offset должен быть неотрицательным" }
    require(limit in 2..15000) { "Недопустимый размер фрагмента" }
    var remaining = offset
    var lastSkipped: Char? = null
    val skip = CharArray(4096)
    while (remaining > 0) {
        var count = reader.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt())
        if (count == 0) {
            val one = reader.read()
            if (one < 0) count = -1 else { skip[0] = one.toChar(); count = 1 }
        }
        require(count >= 0) { "offset находится за концом текста" }
        lastSkipped = skip[count - 1]
        remaining -= count
    }
    val buffer = CharArray(limit + 1)
    var size = 0
    while (size < buffer.size) {
        val count = reader.read(buffer, size, buffer.size - size)
        if (count < 0) break
        if (count == 0) {
            val one = reader.read()
            if (one < 0) break
            buffer[size++] = one.toChar()
        } else size += count
    }
    require(!(lastSkipped?.isHighSurrogate() == true && size > 0 && buffer[0].isLowSurrogate())) {
        "offset разрывает символ; используйте nextOffset предыдущего фрагмента"
    }
    var end = minOf(limit, size)
    if (end > 0 && end < size && buffer[end - 1].isHighSurrogate() && buffer[end].isLowSurrogate()) end--
    return PhoneTextPage(offset, String(buffer, 0, end), size > end)
}
