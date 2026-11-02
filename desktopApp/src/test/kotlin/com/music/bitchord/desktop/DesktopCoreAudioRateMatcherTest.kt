package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCoreAudioRateMatcherTest {
    @Test
    fun `JNA can reflect the CoreAudio property address structure`() {
        val address = CoreAudioPropertyAddress(0x6e737274, 0x676c6f62, 0)
        address.write()

        assertEquals(12, address.size())
        assertEquals(0x6e737274, address.pointer.getInt(0))
        assertEquals(0x676c6f62, address.pointer.getInt(4))
        assertEquals(0, address.pointer.getInt(8))
    }

    @Test
    fun `CoreAudio bridge status is visible on macOS`() {
        if (DesktopPlatform.isMac) assertEquals("Initialized", DesktopMacCoreAudioAccess.bridgeStatus())
    }

    @Test
    fun `pipeline keeps CoreAudio fields visible when device state is unavailable`() {
        val rows = DesktopAudioPipeline(
            sourceSampleRateHz = 44_100,
            hardwareRateMatchingEnabled = true,
            hardwareRateMatchingOutcome = "CoreAudio mapping unavailable",
        ).coreAudioDiagnosticRows().toMap()

        assertEquals("Not checked", rows["CoreAudio bridge"])
        assertEquals("Unavailable", rows["CoreAudio device UID"])
        assertEquals("Unknown", rows["Device availability"])
        assertEquals("Unavailable", rows["Nominal hardware rate"])
        assertEquals("Enabled", rows["Automatically match DAC sample rate"])
        assertEquals("CoreAudio mapping unavailable", rows["Hardware-rate matching outcome"])
        assertEquals("Hardware rate unavailable", rows["Remaining sample-rate mismatch"])
    }

    @Test
    fun `selection mode changes alone do not trigger a second device reconfigure`() {
        val namedOutput = DesktopAudioDeviceSnapshot(listOf("dac"))
        val systemDefaultOutput = namedOutput.copy(defaultOutputUid = "speaker-uid")

        assertFalse(
            shouldReconfigureAudioDevices(
                namedOutput,
                systemDefaultOutput,
                selectedSystemDefault = true,
                selectionChanged = true,
            ),
        )
    }

    @Test
    fun `default route and output list changes are detected independently`() {
        val before = DesktopAudioDeviceSnapshot(listOf("dac"), "speaker-uid")

        assertTrue(
            shouldReconfigureAudioDevices(
                before,
                before.copy(defaultOutputUid = "usb-dac-uid"),
                selectedSystemDefault = true,
                selectionChanged = false,
            ),
        )
        assertFalse(
            shouldReconfigureAudioDevices(
                before,
                before.copy(defaultOutputUid = "usb-dac-uid"),
                selectedSystemDefault = false,
                selectionChanged = false,
            ),
        )
        assertTrue(
            shouldReconfigureAudioDevices(
                before,
                before.copy(outputs = listOf("dac", "headphones")),
                selectedSystemDefault = false,
                selectionChanged = true,
            ),
        )
    }

    @Test
    fun `pipeline reports any remaining source to hardware mismatch`() {
        val mismatch = DesktopAudioPipeline(
            sourceSampleRateHz = 96_000,
            coreAudioNominalSampleRateHz = 768_000.0,
        )
        val matched = mismatch.copy(coreAudioNominalSampleRateHz = 96_000.0)

        assertEquals("96.0 kHz source vs 768.0 kHz hardware", mismatch.remainingSampleRateMismatch)
        assertNull(matched.remainingSampleRateMismatch)

        val fractionalMismatch = DesktopAudioPipeline(
            sourceSampleRateHz = 44_100,
            coreAudioNominalSampleRateHz = 44_100.75,
        )
        val withinMatcherTolerance = fractionalMismatch.copy(coreAudioNominalSampleRateHz = 44_100.25)

        assertEquals("44.1 kHz source vs 44.10075 kHz hardware", fractionalMismatch.remainingSampleRateMismatch)
        assertEquals("44.10075 kHz", fractionalMismatch.coreAudioDiagnosticRows().toMap()["Nominal hardware rate"])
        assertNull(withinMatcherTolerance.remainingSampleRateMismatch)
    }

    @Test
    fun `selected Java Sound mixer maps through its native device id not its display name`() {
        val uid = resolveCoreAudioUid(27, 9) { id -> mapOf(9 to "default-uid", 27 to "dac-uid")[id] }
        assertEquals("dac-uid", uid)
    }

    @Test
    fun `system default resolves its actual CoreAudio output and missing identity fails closed`() {
        assertEquals("default-uid", resolveCoreAudioUid(0, 9) { "default-uid" })
        assertNull(resolveCoreAudioUid(null, 9) { "must-not-be-used" })
    }

    @Test
    fun `supported rate is requested and confirmed by readback`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)

        val result = matcher.reconcile(true, "dac", 44_100) { true }

        assertEquals(listOf(44_100.0), access.setRates)
        assertEquals("dac-uid", result.deviceUid)
        assertTrue(result.available == true)
        assertEquals(44_100.0, result.nominalSampleRateHz)
        assertTrue(result.outcome.contains("matched", ignoreCase = true))
    }

    @Test
    fun `unsupported rate is reported without requesting it`() {
        val access = FakeCoreAudioAccess()
        val result = DesktopCoreAudioRateMatcher(access).reconcile(true, "dac", 88_200) { true }

        assertTrue(access.setRates.isEmpty())
        assertEquals(768_000.0, result.nominalSampleRateHz)
        assertTrue(result.outcome.contains("unsupported", ignoreCase = true))
    }

    @Test
    fun `192 kHz is applied when the hardware advertises it`() {
        val access = FakeCoreAudioAccess()
        val result = DesktopCoreAudioRateMatcher(access).reconcile(true, "dac", 192_000) { true }

        assertEquals(listOf(192_000.0), access.setRates)
        assertEquals(192_000.0, result.nominalSampleRateHz)
    }

    @Test
    fun `successful API request without matching readback is not reported as success`() {
        val access = FakeCoreAudioAccess().apply { applyRequestedRate = false }
        val result = DesktopCoreAudioRateMatcher(access).reconcile(true, "dac", 96_000) { true }

        assertEquals(768_000.0, result.nominalSampleRateHz)
        assertTrue(result.outcome.contains("not verified", ignoreCase = true))
    }

    @Test
    fun `consecutive tracks at the same rate do not request a second hardware change`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)
        var closeCount = 0

        matcher.reconcile(true, "dac", 44_100) { closeCount++; true }
        matcher.reconcile(true, "dac", 44_100) { closeCount++; true }

        assertEquals(listOf(44_100.0), access.setRates)
        assertEquals(1, closeCount)
    }

    @Test
    fun `track rate transitions follow source order`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)

        listOf(44_100, 96_000, 48_000, 44_100).forEach { rate ->
            matcher.reconcile(true, "dac", rate) { true }
        }

        assertEquals(listOf(44_100.0, 96_000.0, 48_000.0, 44_100.0), access.setRates)
        assertEquals(44_100.0, access.state.nominalSampleRateHz)
    }

    @Test
    fun `disconnect drops ownership and reconnect starts from the device current rate`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)
        matcher.reconcile(true, "dac", 44_100) { true }
        access.state = access.state.copy(available = false)

        val disconnected = matcher.reconcile(true, "dac", 96_000) { true }
        assertFalse(disconnected.available == true)
        assertTrue(disconnected.outcome.contains("unavailable", ignoreCase = true))

        access.state = access.state.copy(available = true, nominalSampleRateHz = 48_000.0)
        val reconnected = matcher.reconcile(true, "dac", 96_000) { true }

        assertEquals(listOf(44_100.0, 96_000.0), access.setRates)
        assertEquals(96_000.0, reconnected.nominalSampleRateHz)
    }

    @Test
    fun `failed requests preserve actual rate and are not repeated for same target`() {
        val access = FakeCoreAudioAccess().apply { acceptRequest = false }
        val matcher = DesktopCoreAudioRateMatcher(access)

        val first = matcher.reconcile(true, "dac", 96_000) { true }
        val second = matcher.reconcile(true, "dac", 96_000) { true }

        assertEquals(listOf(96_000.0), access.setRates)
        assertEquals(768_000.0, first.nominalSampleRateHz)
        assertEquals(768_000.0, second.nominalSampleRateHz)
        assertTrue(second.outcome.contains("failed", ignoreCase = true))
    }

    @Test
    fun `external rate change revokes ownership and is never overwritten during restore`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)
        matcher.reconcile(true, "dac", 44_100) { true }
        access.changeExternally(96_000.0)

        val afterExternalChange = matcher.refresh(true, "dac")
        matcher.reconcile(true, "dac", 48_000) { true }
        val disabled = matcher.reconcile(false, "dac", null) { true }

        assertEquals(listOf(44_100.0), access.setRates)
        assertEquals(96_000.0, access.state.nominalSampleRateHz)
        assertTrue(afterExternalChange.outcome.contains("external", ignoreCase = true))
        assertEquals(96_000.0, disabled.nominalSampleRateHz)
    }

    @Test
    fun `disabling restores original rate only while the app still owns the last change`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)
        matcher.reconcile(true, "dac", 96_000) { true }

        val disabled = matcher.reconcile(false, "dac", null) { true }

        assertEquals(listOf(96_000.0, 768_000.0), access.setRates)
        assertEquals(768_000.0, disabled.nominalSampleRateHz)
        assertTrue(disabled.outcome.contains("restored", ignoreCase = true))
    }

    @Test
    fun `default output change still restores the previously owned device by UID`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)
        matcher.reconcile(true, "system-default", 44_100) { true }
        access.selectedState = CoreAudioDeviceState(
            uid = "new-default-uid",
            objectId = 99,
            available = true,
            nominalSampleRateHz = 48_000.0,
            supportedSampleRateRanges = listOf(48_000.0..48_000.0),
        )

        matcher.reconcile(false, "system-default", null) { true }

        assertEquals(listOf(44_100.0, 768_000.0), access.setRates)
        assertEquals(768_000.0, access.state.nominalSampleRateHz)
    }

    @Test
    fun `restoration preserves the original fractional nominal rate`() {
        val access = FakeCoreAudioAccess().apply {
            state = state.copy(
                nominalSampleRateHz = 768_000.25,
                supportedSampleRateRanges = state.supportedSampleRateRanges + (768_000.25..768_000.25),
            )
        }
        val matcher = DesktopCoreAudioRateMatcher(access)
        matcher.reconcile(true, "dac", 44_100) { true }

        matcher.reconcile(false, "dac", null) { true }

        assertEquals(listOf(44_100.0, 768_000.25), access.setRates)
        assertEquals(768_000.25, access.state.nominalSampleRateHz)
    }

    @Test
    fun `hardware change waits until paused playback resumes`() {
        val access = FakeCoreAudioAccess()
        val matcher = DesktopCoreAudioRateMatcher(access)

        val deferred = matcher.reconcile(true, "dac", 44_100) { false }
        assertTrue(deferred.deferred)
        assertTrue(access.setRates.isEmpty())

        val resumed = matcher.reconcile(true, "dac", 44_100) { true }
        assertFalse(resumed.deferred)
        assertEquals(listOf(44_100.0), access.setRates)
    }
}

private class FakeCoreAudioAccess : CoreAudioRateAccess {
    var state = CoreAudioDeviceState(
        uid = "dac-uid",
        objectId = 27,
        available = true,
        nominalSampleRateHz = 768_000.0,
        supportedSampleRateRanges = listOf(44_100, 48_000, 96_000, 192_000, 768_000).map {
            it.toDouble()..it.toDouble()
        },
    )
    var acceptRequest = true
    var applyRequestedRate = true
    var selectedState: CoreAudioDeviceState? = null
    val setRates = mutableListOf<Double>()
    private val listeners = mutableListOf<() -> Unit>()

    override fun selectedOutput(selectionId: String): CoreAudioDeviceState? = selectedState ?: state

    override fun device(uid: String): CoreAudioDeviceState? = state.takeIf { it.uid == uid }

    override fun observeNominalRate(uid: String, changed: () -> Unit): AutoCloseable {
        listeners += changed
        return AutoCloseable { listeners -= changed }
    }

    override fun setNominalRate(uid: String, sampleRateHz: Double): Boolean {
        setRates += sampleRateHz
        if (!acceptRequest || uid != state.uid) return false
        if (applyRequestedRate) {
            state = state.copy(nominalSampleRateHz = sampleRateHz)
            listeners.toList().forEach { it() }
        }
        return true
    }

    fun changeExternally(rate: Double) {
        state = state.copy(nominalSampleRateHz = rate)
        listeners.toList().forEach { it() }
    }
}
