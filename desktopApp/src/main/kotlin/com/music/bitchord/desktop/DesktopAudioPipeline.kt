package com.music.bitchord.desktop

import kotlin.math.abs

/**
 * The playback signal chain as it stands right now — what the engine can say about each stage
 * between the stream arriving and the samples reaching the device.
 *
 * Every field is nullable because the chain is only partly built until something is playing.
 */
internal data class DesktopAudioPipeline(
    /** What the engine is actually playing, which the screen's own copy can lag behind. */
    val sourceFormat: DesktopStreamFormat? = null,
    val sourceSampleRateHz: Int? = null,
    val decoderName: String? = null,
    val decodedSampleRateHz: Int? = null,
    val decodedChannels: Int? = null,
    val decodedIsFloat: Boolean? = null,
    val decodedBytesPerSample: Int? = null,
    val outputSampleRateHz: Int? = null,
    val outputChannels: Int? = null,
    val outputIsFloat: Boolean? = null,
    val outputBytesPerSample: Int? = null,
    val deviceName: String? = null,
    val selectedDeviceName: String? = null,
    val backendName: String? = null,
    val coreAudioDeviceUid: String? = null,
    val coreAudioDeviceAvailable: Boolean? = null,
    val coreAudioNominalSampleRateHz: Double? = null,
    val coreAudioBridgeStatus: String? = null,
    val hardwareRateMatchingEnabled: Boolean = false,
    val hardwareRateMatchingOutcome: String? = null,
    val bufferBytes: Int = 0,
    val equalizerEnabled: Boolean = false,
    val skipSilence: Boolean = false,
) {
    /** The decoder under its usual name, the same one the quality badge shows. */
    val decoderLabel: String?
        get() = decoderName?.let { DesktopStreamFormat(codec = it).codecLabel }

    /** Whether the decoder's rate had to be converted to reach the device. */
    val resampling: Boolean
        get() = decodedSampleRateHz != null && outputSampleRateHz != null &&
            decodedSampleRateHz != outputSampleRateHz

    /** The remaining source-to-hardware rate difference after application output negotiation. */
    val remainingSampleRateMismatch: String?
        get() {
            val source = sourceSampleRateHz ?: return null
            val hardware = coreAudioNominalSampleRateHz ?: return null
            if (abs(source - hardware) < 0.5) return null
            return "${source / 1000.0} kHz source vs ${hardware / 1000.0} kHz hardware"
        }
}

internal fun DesktopAudioPipeline.coreAudioDiagnosticRows(): List<Pair<String, String>> = listOf(
    "CoreAudio bridge" to (coreAudioBridgeStatus ?: "Not checked"),
    "CoreAudio device UID" to (coreAudioDeviceUid ?: "Unavailable"),
    "Device availability" to (coreAudioDeviceAvailable?.let { if (it) "Available" else "Unavailable" } ?: "Unknown"),
    "Nominal hardware rate" to (coreAudioNominalSampleRateHz?.let { "${it / 1000.0} kHz" } ?: "Unavailable"),
    "Automatically match DAC sample rate" to if (hardwareRateMatchingEnabled) "Enabled" else "Disabled",
    "Hardware-rate matching outcome" to (hardwareRateMatchingOutcome ?: "No outcome reported"),
    "Remaining sample-rate mismatch" to (remainingSampleRateMismatch ?: when {
        sourceSampleRateHz == null -> "Source rate unavailable"
        coreAudioNominalSampleRateHz == null -> "Hardware rate unavailable"
        else -> "No mismatch"
    }),
)
