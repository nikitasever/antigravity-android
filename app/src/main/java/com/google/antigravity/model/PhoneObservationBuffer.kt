package com.google.antigravity.model

/** Bounded per-executor context. Stored ToolCallEvent results remain independent. */
internal class PhoneObservationBuffer {
    private data class Entry(val receipt: String, val output: String, val truncated: Boolean)
    private val entries = ArrayDeque<Entry>()
    private val notice = "\n[Вывод сокращён; сохранённая версия доступна в действиях.]"

    fun add(action: String, success: Boolean, output: String) {
        // Normal root/child loops produce at most 24/12 entries; bound defensive reuse too.
        if (entries.size == 64) entries.removeFirst()
        entries.addLast(Entry("${action.take(120)}: ${if (success) "успешно" else "ошибка"}\n",
            output.take(4_000), output.length > 4_000))
    }

    fun isNotEmpty() = entries.isNotEmpty()

    fun render(): String {
        val records = entries.toList()
        // Reserve every receipt and truncation notice before allocating space to output.
        var available = 16_000 - records.sumOf { it.receipt.length + notice.length + 1 }
        val bodies = Array(records.size) { "" }
        for (index in records.indices.reversed()) {
            val entry = records[index]
            val excerpt = entry.output.take(available.coerceAtLeast(0))
            available -= excerpt.length
            bodies[index] = excerpt + if (entry.truncated || excerpt.length < entry.output.length) notice else ""
        }
        return records.indices.joinToString("\n") { records[it].receipt + bodies[it] }
    }
}
