package com.google.antigravity

import com.google.antigravity.model.readAttachmentBytes
import org.junit.Assert.*
import org.junit.Test

class AttachmentReaderTest {
    @Test fun filesLargerThanOldPreviewLimitAreNotTruncated() {
        val bytes = ByteArray(600 * 1024) { (it % 251).toByte() }
        assertArrayEquals(bytes, readAttachmentBytes(bytes.inputStream()))
    }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedFilesAreRejectedInsteadOfSilentlyTruncated() {
        readAttachmentBytes(ByteArray(101).inputStream(), 100)
    }
}
