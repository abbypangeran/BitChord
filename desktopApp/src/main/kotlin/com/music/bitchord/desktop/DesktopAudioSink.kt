package com.music.bitchord.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** The speakers, and the negotiation to reach them. */
internal class DesktopAudioSink {

    private var line: SourceDataLine? = null

    /** True while the Windows shared-mode sink owns the output instead of Java Sound. */
    private var windowsOutput = false

    private var windowsBufferBytes = 0

    /** The selection for which [line] was opened, including the blank system-default choice. */
    private var openedForSelection: String? = null

    /** CoreAudio UID for the exact Java Sound mixer used by the current line, on macOS. */
    var coreAudioDeviceUid: String? = null
        private set

    /** Reused conversion buffer; only the playback thread touches it. */
    private var staging = ByteArray(0)

    /** Kept short: enough to distinguish real samples from a pipeline producing silence. */
    private var diagnosticBlocks = 0

    /** The format the mixer accepted, valid once [open] has succeeded. */
    var format: DesktopPcmFormat = DesktopPcmFormat(44_100, 2, 2)
        private set

    /** Playback gain, applied to the samples rather than to a mixer control. */
    @Volatile
    var gain: Float = 1f

    val isOpen: Boolean get() = windowsOutput || line != null

    val backendName: String? get() = when {
        windowsOutput -> "WASAPI shared"
        line != null -> "Java Sound"
        else -> null
    }

    /** Whether the live line already belongs to the choice currently shown in the player. */
    fun isUsingSelection(id: String): Boolean = isOpen && openedForSelection == id

    /** Opens the best line the mixer will give for [requested]. */
    fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat> = runCatching {
        val selectedDevice = DesktopAudioDevices.selected.value
        if (DesktopPlatform.isWindows) {
            val native = DesktopWindowsAudio.open(selectedDevice, requested)
            if (native.isSuccess) {
                val previous = line
                line = null
                windowsOutput = true
                windowsBufferBytes = native.getOrThrow()
                // Read live from the native side instead: it moves the stream itself when the
                // Windows default changes or a chosen device is unplugged.
                openedOn = null
                openedForSelection = selectedDevice
                coreAudioDeviceUid = null
                format = requested
                diagnosticBlocks = 0
                closeLine(previous)
                DesktopTrackLog.log(
                    "audio output opened: ${deviceName ?: "Windows system default"} · " +
                        "${requested.sampleRate} Hz · ${requested.channels} ch · WASAPI shared",
                )
                return@runCatching requested
            }
            DesktopTrackLog.log("WASAPI output unavailable; trying Java Sound: ${native.exceptionOrNull()?.message}")
        }

        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
        }
        // The chosen device, when there is one and it is still plugged in; otherwise whatever the
        // system calls the default.
        val mixer = DesktopAudioDevices.mixerFor(selectedDevice)
            ?: if (DesktopPlatform.isMac) runCatching { AudioSystem.getMixer(null) }.getOrNull() else null
        val supports: (DesktopPcmFormat) -> Boolean = { candidate ->
            if (mixer == null) {
                AudioSystem.isLineSupported(infoFor(candidate))
            } else {
                runCatching { mixer.isLineSupported(infoFor(candidate)) }.getOrDefault(false)
            }
        }
        val (accepted, opened) = negotiatePcm(requested, supports) { candidate ->
            val candidateLine = if (mixer == null) {
                AudioSystem.getLine(infoFor(candidate)) as SourceDataLine
            } else {
                mixer.getLine(infoFor(candidate)) as SourceDataLine
            }
            try {
                // Roughly a fifth of a second in the device's hands.
                candidateLine.open(candidate.toAudioFormat(), candidate.byteRate / 5)
                candidateLine.start()
                candidateLine
            } catch (failure: Throwable) {
                runCatching { candidateLine.close() }
                throw failure
            }
        }

        // Do not tear down the working route until its replacement is known to have opened. A
        // Bluetooth device can disappear between enumeration and this call; that must not turn a
        // failed switch into silent playback on every other device too.
        val previous = line
        line = opened
        openedOn = mixer?.mixerInfo?.name
        openedForSelection = selectedDevice
        coreAudioDeviceUid = if (DesktopPlatform.isMac) {
            mixer?.mixerInfo?.let(DesktopMacCoreAudioAccess::uidForMixerInfo)
        } else {
            null
        }
        format = accepted
        diagnosticBlocks = 0
        closeLine(previous)
        DesktopTrackLog.log(
            "audio output opened: ${deviceName ?: "system default"} · " +
                "${accepted.sampleRate} Hz · ${accepted.channels} ch · " +
                (if (accepted.isFloat) "32-bit float" else "${accepted.bytesPerSample * 8}-bit PCM"),
        )
        accepted
    }

    /**
     * What the samples are actually being written to.
     *
     * The mixer's own name, not `lineInfo`, which describes the line's capabilities — "interface
     * SourceDataLine supporting 36 audio formats" is not a device.
     */
    val deviceName: String?
        get() = runCatching {
            if (windowsOutput) {
                DesktopWindowsAudio.deviceName()
            } else {
                line?.let { openedOn ?: AudioSystem.getMixer(null).mixerInfo?.name?.takeIf(String::isNotBlank) }
            }
        }.getOrNull()

    /** The mixer this line was actually opened on, when it was not the system default. */
    private var openedOn: String? = null

    /** How much audio the device is holding, in bytes; 0 before the line is open. */
    val bufferBytes: Int
        get() = if (windowsOutput) windowsBufferBytes else runCatching { line?.bufferSize ?: 0 }.getOrDefault(0)

    /** Writes [count] interleaved samples, blocking until the device takes them. */
    fun write(samples: FloatArray, count: Int): Int {
        if (windowsOutput) {
            val written = DesktopWindowsAudio.write(samples, count, gain)
            diagnose(samples, count, written)
            return written
        }
        val target = line ?: return 0
        if (count <= 0) return 0
        val bytes = convert(samples, count)
        val written = target.write(bytes, 0, count * format.bytesPerSample)
        diagnose(samples, count, written)
        return written
    }

    private fun diagnose(samples: FloatArray, count: Int, written: Int) {
        if (diagnosticBlocks < DIAGNOSTIC_BLOCK_LIMIT) {
            diagnosticBlocks++
            val peak = (0 until minOf(count, samples.size)).maxOfOrNull { kotlin.math.abs(samples[it]) } ?: 0f
            if (peak > AUDIBLE_SIGNAL_FLOOR || diagnosticBlocks == DIAGNOSTIC_BLOCK_LIMIT) {
                DesktopTrackLog.log(
                    "audio output signal: peak=${"%.4f".format(java.util.Locale.ROOT, peak)} " +
                        "gain=${"%.2f".format(java.util.Locale.ROOT, gain)} wrote=$written bytes",
                )
                diagnosticBlocks = DIAGNOSTIC_BLOCK_LIMIT
            }
        }
    }

    /** Floats to whatever the device accepted, scaled by [gain]. */
    private fun convert(samples: FloatArray, count: Int): ByteArray {
        val current = format
        val volume = gain
        val bytes = count * current.bytesPerSample
        if (staging.size < bytes) staging = ByteArray(bytes)
        val out = staging

        packPcm(samples, count, current, volume, out)
        return out
    }

    /** Frames the device has actually rendered — the honest playback position. */
    fun framesPlayed(): Long =
        if (windowsOutput) DesktopWindowsAudio.framesPlayed() else line?.longFramePosition ?: 0L

    fun pause() {
        if (windowsOutput) DesktopWindowsAudio.pause() else line?.stop()
    }

    fun resume() {
        if (windowsOutput) DesktopWindowsAudio.resume() else line?.start()
    }

    /** Throws away what is queued, so a seek is heard at once. */
    fun flush() {
        if (windowsOutput) DesktopWindowsAudio.flush() else line?.flush()
    }

    /** Waits for the queue to finish, so the end of a track is not clipped. */
    fun drain() {
        if (windowsOutput) DesktopWindowsAudio.drain() else runCatching { line?.drain() }
    }

    fun close() {
        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
            openedOn = null
            openedForSelection = null
            coreAudioDeviceUid = null
            diagnosticBlocks = 0
            return
        }
        val target = line ?: return
        line = null
        openedOn = null
        openedForSelection = null
        coreAudioDeviceUid = null
        diagnosticBlocks = 0
        closeLine(target)
    }

    private fun closeLine(target: SourceDataLine?) {
        if (target == null) return
        runCatching {
            target.stop()
            target.flush()
            target.close()
        }
    }

    private fun infoFor(candidate: DesktopPcmFormat) =
        DataLine.Info(SourceDataLine::class.java, candidate.toAudioFormat())

    private companion object {
        const val DIAGNOSTIC_BLOCK_LIMIT = 50
        const val AUDIBLE_SIGNAL_FLOOR = 0.001f
    }
}

/** Try advertised formats in order; some mixers reject a format only when opening its line. */
internal fun <T> negotiatePcm(
    requested: DesktopPcmFormat,
    supports: (DesktopPcmFormat) -> Boolean,
    open: (DesktopPcmFormat) -> T,
): Pair<DesktopPcmFormat, T> {
    val formats = buildList {
        add(requested)
        if (requested.isFloat) add(requested.copy(bytesPerSample = 4, isFloat = false))
        if (requested.isFloat || requested.bytesPerSample == 4) add(requested.copy(bytesPerSample = 3, isFloat = false))
        if (requested.bytesPerSample != 2) add(requested.copy(bytesPerSample = 2, isFloat = false))
    }.distinct()
    var lastFailure: Throwable? = null
    for (rate in listOf(requested.sampleRate, 48_000, 44_100).distinct()) {
        for (channels in listOf(requested.channels, 2).distinct()) {
            for (format in formats) {
                val candidate = format.copy(sampleRate = rate, channels = channels)
                if (!runCatching { supports(candidate) }.getOrDefault(false)) continue
                val result = runCatching { open(candidate) }
                if (result.isSuccess) return candidate to result.getOrThrow()
                lastFailure = result.exceptionOrNull()
            }
        }
    }
    throw IllegalStateException("no audio line for any supported format", lastFailure)
}

/** Pack interleaved float samples into the signed little-endian format accepted by Java Sound. */
internal fun packPcm(samples: FloatArray, count: Int, format: DesktopPcmFormat, gain: Float, out: ByteArray) {
    require(count in 0..samples.size && out.size >= count * format.bytesPerSample)
    require(format.bytesPerSample in 2..4 && (!format.isFloat || format.bytesPerSample == 4))
    val limit = 1L shl (format.bytesPerSample * 8 - 1)
    for (index in 0 until count) {
        val sample = samples[index] * gain
        val value = if (format.isFloat) sample.toRawBits().toLong() else {
            val clipped = sample.coerceIn(-1f, 1f)
            when {
                clipped <= -1f -> -limit
                clipped >= 1f -> limit - 1
                else -> (clipped.toDouble() * limit).toLong()
            }
        }
        for (byte in 0 until format.bytesPerSample) {
            out[index * format.bytesPerSample + byte] = (value shr (byte * 8)).toByte()
        }
    }
}

/** The same format in JavaSound's vocabulary. */
internal fun DesktopPcmFormat.toAudioFormat(): AudioFormat = AudioFormat(
    if (isFloat) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    bytesPerSample * 8,
    channels,
    frameBytes,
    sampleRate.toFloat(),
    false,
)
