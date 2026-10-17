package com.aiagent.android

import com.aiagent.android.model.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatBytesTest {

    @Test
    fun testFormatBytesZero() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-100))
    }

    @Test
    fun testFormatBytesKilo() {
        val result = formatBytes(1024)
        assertEquals("1 KB", result)
    }

    @Test
    fun testFormatBytesMega() {
        val result = formatBytes(1048576) // 1 MB
        assertEquals("1 MB", result)
    }

    @Test
    fun testFormatBytes500Mega() {
        val bytes = 500L * 1024 * 1024 // 500 MB
        val result = formatBytes(bytes)
        assertEquals("500 MB", result)
    }

    @Test
    fun testFormatBytesGiga() {
        val bytes = 1073741824L * 2 // 2 GB
        val result = formatBytes(bytes)
        assertEquals("2 GB", result)
    }
}
