package com.google.antigravity.model

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun readAttachmentBytes(input: InputStream, limit: Int = 16 * 1024 * 1024): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "Файл превышает допустимый размер 16 МБ" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
