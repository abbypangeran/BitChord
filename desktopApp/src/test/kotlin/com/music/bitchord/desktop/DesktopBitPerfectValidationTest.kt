package com.music.bitchord.desktop

import com.music.bitchord.playback.EqCurve
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bit-accuracy check of the production PCM path, captured at the exact bytes handed to
 * `SourceDataLine.write()`.
 *
 * Test instrumentation only. Runs when `BITCHORD_BITPERFECT_DIR` points at fixtures made by
 * `tools/bitperfect/generate.py`; see `tools/bitperfect/run.sh`. No audio device is opened: a
 * recording [SourceDataLine] proxy is put where [DesktopAudioSink] keeps its real line, so the
 * production `write -> convert -> packPcm -> line.write` code runs unchanged. This proves nothing
 * about CoreAudio, USB or the DAC beyond that boundary.
 */
class DesktopBitPerfectValidationTest {

    private data class Output(val label: String, val bytesPerSample: Int, val isFloat: Boolean, val gain: Float = 1f)

    private val outputs = listOf(
        Output("PCM_S24LE", 3, false),
        Output("PCM_S32LE", 4, false),
        Output("FLOAT32LE", 4, true),
        Output("PCM_S16LE", 2, false),
    )

    /** Shows what the in-app volume slider does below 100 %; recorded, never asserted. */
    private val attenuated = Output("PCM_S24LE@gain0.5", 3, false, gain = 0.5f)

    @Test
    fun `production PCM path reproduces FLAC samples at the SourceDataLine write boundary`() {
        val root = System.getenv("BITCHORD_BITPERFECT_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        if (root == null) {
            println("BITCHORD_BITPERFECT_DIR unset; bit-perfect validation skipped (run tools/bitperfect/run.sh)")
            return
        }
        val fixtures = File(root, "fixtures")
        val captures = File(root, "captures").apply { deleteRecursively(); mkdirs() }
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(File(fixtures, "manifest.json").readText())
        val entries = manifest.jsonObjectValue("fixtures").let { it as kotlinx.serialization.json.JsonArray }

        val records = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (entry in entries) {
            val name = entry.str("name")
            val rate = entry.str("sampleRate").toInt()
            val bits = entry.str("bitsPerSample").toInt()
            val reference = readS32(File(fixtures, entry.str("reference")))
            val targets = outputs + if (name == "random_24b_44100") listOf(attenuated) else emptyList()
            for (output in targets) {
                val result = capture(File(fixtures, entry.str("flac")), output)
                val file = File(captures, "$name.${output.label}.bin").apply { writeBytes(result.bytes) }
                val compare = compare(reference, result.bytes, output, bits)
                val expectedLossless = output.gain == 1f && output.bytesPerSample * 8 >= bits
                if (expectedLossless && (result.decodedRate != rate || result.outputRate != rate || compare.mismatches != 0L || compare.frameDelta != 0)) {
                    failures += "$name -> ${output.label}: ${compare.mismatches} mismatches, frameDelta=${compare.frameDelta}, " +
                        "decodedRate=${result.decodedRate}, outputRate=${result.outputRate}"
                }
                records += """{"fixture":"$name","output":"${output.label}","capture":"${file.name}",""" +
                    """"gain":${output.gain},"sinkFormat":{"sampleRate":${result.outputRate},"channels":${result.channels},""" +
                    """"bytesPerSample":${output.bytesPerSample},"isFloat":${output.isFloat},"littleEndian":true,"signed":true},""" +
                    """"decoderFormat":"${result.decoderFormat}","measured":"${result.measured}",""" +
                    """"bytesWritten":${result.bytes.size},"writeCalls":${result.writeCalls},""" +
                    """"kotlinMismatches":${compare.mismatches},"frameDelta":${compare.frameDelta},"expectedLossless":$expectedLossless}"""
                println("bitperfect  $name -> ${output.label}: mismatches=${compare.mismatches} frameDelta=${compare.frameDelta}")
            }
        }
        File(captures, "capture.json").writeText(
            """{"boundary":"DesktopAudioSink.write -> packPcm -> SourceDataLine.write(byte[],int,int)",""" +
                """"hostJavaSoundNegotiation":${hostNegotiation()},"captures":[${records.joinToString(",\n")}]}""",
        )
        assertTrue(failures.isEmpty(), "unexpected sample differences:\n" + failures.joinToString("\n"))
    }

    private class Captured(
        val bytes: ByteArray,
        val writeCalls: Int,
        val decodedRate: Int,
        val outputRate: Int,
        val channels: Int,
        val decoderFormat: String,
        val measured: String,
    )

    /** The engine's stage order for a single, unblended track with every optional stage off. */
    private fun capture(flac: File, output: Output): Captured {
        val decoder = DesktopAudioDecoder()
        // The request DesktopPlaybackEngine.openTrack makes.
        decoder.open(flac.absolutePath, requested = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true)).getOrThrow()
        val decoded = decoder.outputFormat
        val measured = decoder.measuredFormat.toString().replace("\"", "'")
        val ahead = DesktopDecodeAhead(decoder, decoded, decoder.durationUs, decoder.measuredFormat)
        // Hardware-rate matching opens the line at the decoder's own rate, as on the ONIX.
        val format = decoded.copy(bytesPerSample = output.bytesPerSample, isFloat = output.isFloat)
        val recorder = ByteArrayOutputStream()
        var calls = 0
        val sink = DesktopAudioSink()
        inject(sink, recordingLine { bytes, offset, length -> calls++; recorder.write(bytes, offset, length) }, format)
        sink.gain = output.gain

        val channels = format.channels
        val spatial = DesktopSpatialAudio(channels, format.sampleRate).apply { enabled = false }
        val equalizer = DesktopEqualizer().apply {
            configure(channels, format.sampleRate)
            setTuning(false, EqCurve.FLAT, 0f)
        }
        val silence = DesktopSilenceSkipper(channels, format.sampleRate).apply { enabled = false }
        val speed = DesktopAudioSpeed(channels, format.sampleRate).apply { this.speed = 1f }
        check(!needsOutputLimit(equalizer = false, spatial = false, speed = 1f, blending = false))
        check(decoded.sampleRate == format.sampleRate) { "engine would insert DesktopRateConverter" }
        try {
            while (true) {
                val block = ahead.readSamples() ?: break
                val count = ahead.sampleCount
                spatial.process(block, count)
                equalizer.process(block, count)
                val trimmed = silence.process(block, count)
                val stretched = speed.process(trimmed, silence.outputCount)
                val written = sink.write(stretched, speed.outputCount)
                assertEquals(speed.outputCount * format.bytesPerSample, written)
            }
        } finally {
            ahead.close()
        }
        return Captured(recorder.toByteArray(), calls, decoded.sampleRate, format.sampleRate, channels, decoded.toString(), measured)
    }

    private fun recordingLine(onWrite: (ByteArray, Int, Int) -> Unit): SourceDataLine =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            if (method.name == "write" && args?.size == 3) {
                onWrite(args[0] as ByteArray, args[1] as Int, args[2] as Int)
                return@newProxyInstance args[2]
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as SourceDataLine

    /** Puts [line] and [format] where a successful `open` would have, without opening a device. */
    private fun inject(sink: DesktopAudioSink, line: SourceDataLine, format: DesktopPcmFormat) {
        DesktopAudioSink::class.java.getDeclaredField("line").apply { isAccessible = true }.set(sink, line)
        DesktopAudioSink::class.java.getDeclaredField("format").apply { isAccessible = true }.set(sink, format)
    }

    private class Comparison(val mismatches: Long, val frameDelta: Int)

    /** Output bytes back to source-depth integers; float must land on an exact integer to count. */
    private fun compare(reference: IntArray, bytes: ByteArray, output: Output, bits: Int): Comparison {
        val width = output.bytesPerSample
        val count = bytes.size / width
        var mismatches = 0L
        for (index in 0 until minOf(count, reference.size)) {
            var raw = 0L
            for (byte in 0 until width) raw = raw or ((bytes[index * width + byte].toLong() and 0xFF) shl (8 * byte))
            val expected = reference[index].toLong()
            val exact = if (output.isFloat) {
                val scaled = Float.fromBits(raw.toInt()).toDouble() * (1L shl (bits - 1))
                scaled == expected.toDouble()
            } else {
                val signed = (raw shl (64 - 8 * width)) shr (64 - 8 * width)
                val shift = 8 * width - bits
                if (shift >= 0) signed == expected shl shift else signed == expected shr -shift
            }
            if (!exact) mismatches++
        }
        mismatches += kotlin.math.abs(count - reference.size)
        return Comparison(mismatches, (count - reference.size) / 2)
    }

    /** What Java Sound's default mixer would accept on this host; queried only, nothing opened. */
    private fun hostNegotiation(): String = listOf(44_100, 48_000, 96_000).joinToString(",", "[", "]") { rate ->
        val picked = runCatching {
            negotiatePcm(DesktopPcmFormat(rate, 2, 4, isFloat = true), { candidate ->
                AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, candidate.toAudioFormat()))
            }) { }.first.toString()
        }.getOrElse { "unavailable: ${it.message}" }
        """{"requestedRate":$rate,"wouldOpen":"$picked"}"""
    }

    private fun readS32(file: File): IntArray {
        val buffer = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also(buffer::get)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectValue(key: String) =
        (this as kotlinx.serialization.json.JsonObject).getValue(key)

    private fun kotlinx.serialization.json.JsonElement.str(key: String) =
        (jsonObjectValue(key) as kotlinx.serialization.json.JsonPrimitive).content
}
