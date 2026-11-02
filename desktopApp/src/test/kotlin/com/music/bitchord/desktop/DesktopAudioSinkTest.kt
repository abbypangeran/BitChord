package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopAudioSinkTest {
    @Test
    fun `negotiation prefers source rate and higher integer precision`() {
        val requested = DesktopPcmFormat(96_000, 2, 4, isFloat = true)
        val tried = mutableListOf<DesktopPcmFormat>()
        val (accepted, _) = negotiatePcm(requested, supports = { true }) { candidate ->
            tried += candidate
            if (candidate.isFloat) error("mixer rejected float on open")
            if (candidate.bytesPerSample != 4) error("unexpected format")
            "opened"
        }
        assertEquals(requested.copy(isFloat = false), accepted)
        assertEquals(listOf(requested, accepted), tried)
    }

    @Test
    fun `negotiation tries fallback rate only after native formats`() {
        val requested = DesktopPcmFormat(192_000, 2, 4, isFloat = true)
        val tried = mutableListOf<DesktopPcmFormat>()
        val (accepted, _) = negotiatePcm(requested, supports = { true }) { candidate ->
            tried += candidate
            if (candidate.sampleRate != 48_000 || candidate.bytesPerSample != 3) error("unsupported")
            "opened"
        }
        assertEquals(DesktopPcmFormat(48_000, 2, 3), accepted)
        assertEquals(listOf(4, 4, 3, 2), tried.take(4).map { it.bytesPerSample })
        assertEquals(listOf(192_000), tried.take(4).map { it.sampleRate }.distinct())
    }

    @Test
    fun `negotiation keeps native rate when only 16 bit is supported`() {
        val requested = DesktopPcmFormat(44_100, 2, 4, isFloat = true)
        val (accepted, _) = negotiatePcm(requested, supports = { it.bytesPerSample == 2 }) { "opened" }
        assertEquals(DesktopPcmFormat(44_100, 2, 2), accepted)
    }

    @Test
    fun `signed PCM clips and packs little endian at each precision`() {
        val samples = floatArrayOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)
        val expected = mapOf(
            2 to listOf("00 80", "00 80", "00 c0", "00 00", "00 40", "ff 7f", "ff 7f"),
            3 to listOf("00 00 80", "00 00 80", "00 00 c0", "00 00 00", "00 00 40", "ff ff 7f", "ff ff 7f"),
            4 to listOf("00 00 00 80", "00 00 00 80", "00 00 00 c0", "00 00 00 00", "00 00 00 40", "ff ff ff 7f", "ff ff ff 7f"),
        )
        for ((bytes, hex) in expected) {
            val output = ByteArray(samples.size * bytes)
            packPcm(samples, samples.size, DesktopPcmFormat(48_000, 1, bytes), 1f, output)
            assertContentEquals(hex.flatMap { it.split(' ').map { value -> value.toInt(16).toByte() } }.toByteArray(), output)
        }
    }

    @Test
    fun `float PCM packs raw IEEE little endian`() {
        val output = ByteArray(8)
        packPcm(floatArrayOf(1f, -0.5f), 2, DesktopPcmFormat(48_000, 1, 4, isFloat = true), 1f, output)
        assertContentEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, -65), output)
    }
}
