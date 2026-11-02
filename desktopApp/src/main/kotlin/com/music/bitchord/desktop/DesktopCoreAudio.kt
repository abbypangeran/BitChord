package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

internal data class CoreAudioDeviceState(
    val uid: String,
    val objectId: Int,
    val available: Boolean,
    val nominalSampleRateHz: Double?,
    val supportedSampleRateRanges: List<ClosedFloatingPointRange<Double>>,
) {
    fun supports(sampleRateHz: Int): Boolean = supports(sampleRateHz.toDouble())

    fun supports(sampleRateHz: Double): Boolean =
        supportedSampleRateRanges.any { sampleRateHz in it }
}

internal interface CoreAudioRateAccess {
    fun selectedOutput(selectionId: String): CoreAudioDeviceState?
    fun device(uid: String): CoreAudioDeviceState?
    fun observeNominalRate(uid: String, changed: () -> Unit): AutoCloseable?
    fun setNominalRate(uid: String, sampleRateHz: Double): Boolean
}

internal data class CoreAudioRateDiagnostics(
    val deviceUid: String? = null,
    val available: Boolean? = null,
    val nominalSampleRateHz: Double? = null,
    val outcome: String = "Not checked",
    val deferred: Boolean = false,
)

/** Controls only the CoreAudio device that Java Sound selected for the current output. */
internal class DesktopCoreAudioRateMatcher(
    private val access: CoreAudioRateAccess,
    private val verificationTimeoutMs: Long = 1_000,
) {
    private data class Session(
        val uid: String,
        val selectionId: String,
        val originalRateHz: Double,
        val listener: AutoCloseable,
        val events: AtomicInteger,
        var expectedEvents: Int,
        var expectedRateHz: Double,
        var ownedRateHz: Double? = null,
        var ownershipLost: Boolean = false,
    )

    private var session: Session? = null
    private var failedRequest: Pair<String, Int>? = null
    private var lastDeferred = false
    private val stateLock = ReentrantLock()
    private var lastRefreshNanos = 0L

    @Volatile
    private var latest = CoreAudioRateDiagnostics()

    fun diagnostics(): CoreAudioRateDiagnostics = latest

    fun refresh(enabled: Boolean, selectionId: String): CoreAudioRateDiagnostics {
        if (!stateLock.tryLock()) return latest
        try {
            val now = System.nanoTime()
            if (now - lastRefreshNanos < 250_000_000L) return latest
            lastRefreshNanos = now
            return refreshLocked(enabled, selectionId)
        } finally {
            stateLock.unlock()
        }
    }

    private fun refreshLocked(enabled: Boolean, selectionId: String): CoreAudioRateDiagnostics {
        val selected = access.selectedOutput(selectionId)
        if (selected == null || !selected.available) {
            return publish(selected, "Selected CoreAudio device unavailable or identity could not be mapped")
        }
        val current = access.device(selected.uid)
        if (current == null || !current.available) {
            return publish(current ?: selected, "Selected CoreAudio device unavailable")
        }
        val active = session
        if (active != null && active.uid == current.uid &&
            (active.ownershipLost || !isExpected(active, current))
        ) {
            loseOwnership(active)
            return publish(current, "External CoreAudio rate change detected; automatic changes paused")
        }
        val outcome = if (active?.uid == current.uid) latest.outcome else if (enabled) {
            "Enabled; hardware rate observed"
        } else {
            "Disabled"
        }
        return publish(current, outcome, latest.deferred)
    }

    fun reconcile(
        enabled: Boolean,
        selectionId: String,
        sourceRateHz: Int?,
        beforeRateChange: () -> Boolean,
    ): CoreAudioRateDiagnostics = stateLock.withLock {
        reconcileLocked(enabled, selectionId, sourceRateHz, beforeRateChange)
    }

    private fun reconcileLocked(
        enabled: Boolean,
        selectionId: String,
        sourceRateHz: Int?,
        beforeRateChange: () -> Boolean,
    ): CoreAudioRateDiagnostics {
        lastDeferred = false
        if (!enabled) {
            val restore = restoreOwnedRate(beforeRateChange)
            failedRequest = null
            val current = access.selectedOutput(selectionId)
            return publish(
                current,
                if (restore != null) restore else "Disabled",
                lastDeferred,
            )
        }

        var active = session
        if (active != null && active.selectionId != selectionId) {
            val restore = restoreOwnedRate(beforeRateChange)
            if (lastDeferred) {
                return publish(access.selectedOutput(selectionId), restore ?: "Hardware change deferred", deferred = true)
            }
            active = null
        }

        val selected = access.selectedOutput(selectionId)
        if (selected == null || !selected.available) {
            if (active != null) failedRequest = null
            active?.listener?.close()
            session = null
            return publish(selected, "Selected CoreAudio device unavailable or identity could not be mapped")
        }

        if (active != null && active.uid != selected.uid) {
            val restore = restoreOwnedRate(beforeRateChange)
            if (lastDeferred) return publish(selected, restore ?: "Hardware change deferred", deferred = true)
            active = null
        }

        val current = access.device(selected.uid)
        if (current == null || !current.available || current.nominalSampleRateHz == null) {
            session?.listener?.close()
            session = null
            return publish(current ?: selected, "Selected CoreAudio device unavailable")
        }

        if (active == null) {
            active = startSession(selectionId, current)
            if (active == null) {
                return publish(current, "Could not monitor CoreAudio rate changes; device left unchanged")
            }
            session = active
        }

        if (active.ownershipLost || !isExpected(active, current)) {
            loseOwnership(active)
            return publish(current, "External CoreAudio rate change detected; automatic changes paused")
        }

        val target = sourceRateHz
        if (target == null) return publish(current, "Enabled; waiting for a source rate")
        if (sameRate(current.nominalSampleRateHz, target.toDouble())) {
            active.expectedRateHz = current.nominalSampleRateHz
            return publish(current, "Hardware already matches source")
        }
        if (!current.supports(target)) {
            return publish(current, "Unsupported hardware rate: ${target} Hz")
        }
        if (failedRequest == (current.uid to target)) {
            return publish(current, "Rate change previously failed; retry after toggling this setting")
        }

        if (!beforeRateChange()) {
            lastDeferred = true
            return publish(current, "Hardware rate switch deferred until playback resumes", deferred = true)
        }
        val stillSelected = access.selectedOutput(selectionId)
        val beforeSet = access.device(active.uid)
        if (stillSelected?.uid != active.uid || beforeSet == null || !beforeSet.available || !isExpected(active, beforeSet)) {
            loseOwnership(active)
            return publish(beforeSet ?: stillSelected, "Output changed during rate switch; no rate change was made")
        }

        val priorEvents = active.expectedEvents
        val requested = runCatching { access.setNominalRate(active.uid, target.toDouble()) }.getOrDefault(false)
        val verified = if (requested) waitForReadback(active, target.toDouble()) else null
        if (verified != null && active.events.get() == priorEvents + 1) {
            active.expectedEvents++
            active.expectedRateHz = verified.nominalSampleRateHz ?: target.toDouble()
            active.ownedRateHz = active.expectedRateHz
            failedRequest = null
            return publish(verified, "Hardware matched source at ${target} Hz")
        }

        val actual = access.device(active.uid)
        val rateBeforeRequest = beforeSet.nominalSampleRateHz
        if (actual == null || !actual.available || rateBeforeRequest == null ||
            !sameRate(actual.nominalSampleRateHz, rateBeforeRequest)
        ) {
            loseOwnership(active)
        }
        failedRequest = active.uid to target
        return publish(
            actual ?: beforeSet,
            if (requested) "Rate change not verified; actual hardware rate retained" else "CoreAudio rate change failed",
        )
    }

    fun close() {
        stateLock.withLock { restoreOwnedRate { true } }
    }

    private fun startSession(selectionId: String, initial: CoreAudioDeviceState): Session? {
        val events = AtomicInteger()
        val listener = runCatching { access.observeNominalRate(initial.uid) { events.incrementAndGet() } }.getOrNull()
            ?: return null
        val confirmed = access.device(initial.uid)
        if (confirmed == null || !confirmed.available || confirmed.nominalSampleRateHz == null) {
            listener.close()
            return null
        }
        return Session(
            uid = initial.uid,
            selectionId = selectionId,
            originalRateHz = confirmed.nominalSampleRateHz,
            listener = listener,
            events = events,
            expectedEvents = events.get(),
            expectedRateHz = confirmed.nominalSampleRateHz,
        )
    }

    private fun isExpected(session: Session, current: CoreAudioDeviceState): Boolean =
        session.events.get() == session.expectedEvents &&
            sameRate(current.nominalSampleRateHz, session.expectedRateHz)

    private fun waitForReadback(session: Session, target: Double): CoreAudioDeviceState? {
        var deadline = System.nanoTime() + verificationTimeoutMs * 1_000_000
        var interruptedOnce = false
        do {
            val current = access.device(session.uid)
            if (current == null || !current.available) return null
            if (session.events.get() > session.expectedEvents + 1) return null
            if (session.events.get() == session.expectedEvents + 1 &&
                sameRate(current.nominalSampleRateHz, target)
            ) return current
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                // Release interrupts the pump to wake it; keep the listener alive until this
                // asynchronous CoreAudio request has either been verified or timed out.
                if (!interruptedOnce) {
                    deadline = System.nanoTime() + verificationTimeoutMs * 1_000_000
                    interruptedOnce = true
                }
            }
        } while (true)
    }

    /** Returns a user-facing restoration result, or null when no app-owned change exists. */
    private fun restoreOwnedRate(beforeRateChange: () -> Boolean): String? {
        val active = session ?: return null
        session = null
        var preserveSession = false
        try {
            val owned = active.ownedRateHz ?: return null
            val current = access.device(active.uid)
            if (active.ownershipLost || current == null || !current.available || !isExpected(active, current)) {
                return "Prior hardware rate not restored because ownership changed"
            }
            if (sameRate(current.nominalSampleRateHz, active.originalRateHz)) return "Prior hardware rate already restored"
            val original = active.originalRateHz
            if (!current.supports(original)) return "Prior hardware rate no longer supported; left unchanged"
            if (!beforeRateChange()) {
                session = active
                preserveSession = true
                lastDeferred = true
                return "Hardware rate restoration deferred until playback resumes"
            }
            val beforeSet = access.device(active.uid)
            if (beforeSet == null || !beforeSet.available ||
                !isExpected(active, beforeSet) || !sameRate(beforeSet.nominalSampleRateHz, owned)
            ) return "Prior hardware rate not restored because ownership changed"

            val priorEvents = active.expectedEvents
            if (!runCatching { access.setNominalRate(active.uid, original) }.getOrDefault(false)) {
                return "Prior hardware rate restoration failed"
            }
            val restored = waitForReadback(active, original)
            return if (restored != null && active.events.get() == priorEvents + 1) {
                "Prior hardware rate restored to $original Hz"
            } else {
                "Prior hardware rate restoration not verified"
            }
        } finally {
            if (!preserveSession) active.listener.close()
        }
    }

    private fun loseOwnership(active: Session) {
        active.ownershipLost = true
        session = active
    }

    private fun publish(
        state: CoreAudioDeviceState?,
        outcome: String,
        deferred: Boolean = false,
    ): CoreAudioRateDiagnostics =
        CoreAudioRateDiagnostics(
            deviceUid = state?.uid,
            available = state?.available,
            nominalSampleRateHz = state?.nominalSampleRateHz,
            outcome = outcome,
            deferred = deferred,
        ).also { latest = it }

    private fun sameRate(actual: Double?, expected: Double): Boolean =
        actual != null && abs(actual - expected) < 0.5
}

/** Java Sound exposes the selected CoreAudio AudioDeviceID; the UID, never its display name, is stable. */
internal fun resolveCoreAudioUid(
    javaSoundDeviceId: Int?,
    defaultOutputDeviceId: Int?,
    uidForObjectId: (Int) -> String?,
): String? {
    val objectId = when (javaSoundDeviceId) {
        null -> return null
        0 -> defaultOutputDeviceId ?: return null
        else -> javaSoundDeviceId
    }
    return uidForObjectId(objectId)
}

/** The opt-in is deliberately off until the listener has tested it with a physical DAC. */
internal object DesktopAudioRateMatchingSetting {
    private const val KEY = "audio_auto_match_dac_sample_rate"
    private val persistence = DesktopPersistence()
    private val mutableEnabled = MutableStateFlow(persistence.boolean(KEY, false))

    val enabled: StateFlow<Boolean> = mutableEnabled

    fun setEnabled(value: Boolean) {
        persistence.saveBoolean(KEY, value)
        mutableEnabled.value = value
    }
}

/** CoreAudio through the already packaged JNA dependency; every write is UID-scoped. */
internal object DesktopMacCoreAudioAccess : CoreAudioRateAccess {
    private const val SYSTEM_OBJECT = 1
    private val coreAudioLoad by lazy {
        runCatching {
            Native.load("/System/Library/Frameworks/CoreAudio.framework/CoreAudio", CoreAudioFunctions::class.java)
        }
    }
    private val coreAudio: CoreAudioFunctions get() = coreAudioLoad.getOrThrow()

    fun bridgeStatus(): String = coreAudioLoad.fold(
        onSuccess = { "Initialized" },
        onFailure = { failure ->
            val detail = failure.message?.replace(Regex("\\s+"), " ")?.take(120)
            "Unavailable: ${failure.javaClass.simpleName}${detail?.let { ": $it" }.orEmpty()}"
        },
    )

    override fun selectedOutput(selectionId: String): CoreAudioDeviceState? = runCatching {
        val info = if (selectionId == DesktopAudioDevices.SYSTEM_DEFAULT) {
            AudioSystem.getMixer(null).mixerInfo
        } else {
            DesktopAudioDevices.mixerFor(selectionId)?.mixerInfo
        } ?: return null
        val javaSoundId = javaSoundDeviceId(info) ?: return null
        val defaultId = defaultOutputObjectId()
        val uid = resolveCoreAudioUid(javaSoundId, defaultId, ::uidForObjectId) ?: return null
        stateForUid(uid)
    }.getOrNull()

    override fun device(uid: String): CoreAudioDeviceState? = runCatching { stateForUid(uid) }.getOrNull()

    override fun observeNominalRate(uid: String, changed: () -> Unit): AutoCloseable? = runCatching {
        val state = stateForUid(uid) ?: return null
        val callback = AudioObjectPropertyListenerProc { _, _, _, _ -> changed(); 0 }
        val address = address(PROP_NOMINAL_RATE)
        if (coreAudio.AudioObjectAddPropertyListener(state.objectId, address.pointer, callback, null) != 0) {
            return null
        }
        AutoCloseable {
            runCatching {
                coreAudio.AudioObjectRemovePropertyListener(state.objectId, address.pointer, callback, null)
            }
        }
    }.getOrNull()

    override fun setNominalRate(uid: String, sampleRateHz: Double): Boolean = runCatching {
        val state = stateForUid(uid) ?: return false
        if (!state.available || !state.supports(sampleRateHz)) return false
        val address = address(PROP_NOMINAL_RATE)
        val writable = Memory(1)
        if (coreAudio.AudioObjectIsPropertySettable(state.objectId, address.pointer, writable) != 0 ||
            writable.getByte(0).toInt() == 0
        ) return false
        val value = Memory(java.lang.Double.BYTES.toLong())
        value.setDouble(0, sampleRateHz)
        coreAudio.AudioObjectSetPropertyData(
            state.objectId,
            address.pointer,
            0,
            null,
            java.lang.Double.BYTES,
            value,
        ) == 0
    }.getOrDefault(false)

    fun uidForMixerInfo(info: Mixer.Info): String? = runCatching {
        val javaSoundId = javaSoundDeviceId(info) ?: return null
        val defaultId = defaultOutputObjectId()
        resolveCoreAudioUid(javaSoundId, defaultId, ::uidForObjectId)
    }.getOrNull()

    fun defaultOutputUid(): String? = runCatching {
        AudioSystem.getMixer(null).mixerInfo?.let(::uidForMixerInfo)
    }.getOrNull()

    private fun stateForUid(uid: String): CoreAudioDeviceState? {
        val deviceIds = propertyMemory(SYSTEM_OBJECT, address(PROP_DEVICES)) ?: return null
        val size = deviceIds.size().toInt()
        if (size == 0 || size % Int.SIZE_BYTES != 0) return null
        for (offset in 0 until size step Int.SIZE_BYTES) {
            val objectId = deviceIds.getInt(offset.toLong())
            if (uidForObjectId(objectId) == uid) return stateForObjectId(objectId, uid)
        }
        return null
    }

    private fun stateForObjectId(objectId: Int, uid: String): CoreAudioDeviceState? {
        val alive = propertyInt(objectId, address(PROP_DEVICE_IS_ALIVE)) == 1
        val rate = propertyMemory(objectId, address(PROP_NOMINAL_RATE))
            ?.takeIf { it.size() >= java.lang.Double.BYTES }?.getDouble(0)
        val ranges = propertyMemory(objectId, address(PROP_AVAILABLE_NOMINAL_RATES))?.let { data ->
            val size = data.size().toInt()
            if (size % 16 != 0) return null
            (0 until size step 16).map { offset -> data.getDouble(offset.toLong()) .. data.getDouble((offset + 8).toLong()) }
        }.orEmpty()
        return CoreAudioDeviceState(uid, objectId, alive, rate, ranges)
    }

    private fun uidForObjectId(objectId: Int): String? {
        val data = propertyMemory(objectId, address(PROP_DEVICE_UID)) ?: return null
        if (data.size() < Native.POINTER_SIZE.toLong()) return null
        val pointer = data.getPointer(0) ?: return null
        val uid = CFStringRef(pointer)
        return try {
            uid.stringValue()
        } finally {
            uid.release()
        }
    }

    private fun defaultOutputObjectId(): Int? =
        propertyInt(SYSTEM_OBJECT, address(PROP_DEFAULT_OUTPUT))

    private fun propertyInt(objectId: Int, address: CoreAudioPropertyAddress): Int? =
        propertyMemory(objectId, address)?.takeIf { it.size() >= Int.SIZE_BYTES }?.getInt(0)

    private fun propertyMemory(objectId: Int, address: CoreAudioPropertyAddress): Memory? {
        val size = IntByReference()
        if (coreAudio.AudioObjectGetPropertyDataSize(objectId, address.pointer, 0, null, size) != 0) return null
        val byteCount = size.value
        if (byteCount <= 0 || byteCount > MAX_PROPERTY_BYTES) return null
        val data = Memory(byteCount.toLong())
        val actual = IntByReference(byteCount)
        if (coreAudio.AudioObjectGetPropertyData(objectId, address.pointer, 0, null, actual, data) != 0) return null
        if (actual.value != byteCount) return null
        return data
    }

    private fun javaSoundDeviceId(info: Mixer.Info): Int? = runCatching {
        var type: Class<*>? = info.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "deviceID" }
            if (field != null) {
                if (!field.trySetAccessible()) return null
                return field.getInt(info)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private fun address(selector: Int, scope: Int = SCOPE_GLOBAL) =
        CoreAudioPropertyAddress(selector, scope, ELEMENT_MAIN).apply { write() }

    private fun fourCC(value: String): Int = value.fold(0) { result, char -> (result shl 8) or char.code }

    private const val MAX_PROPERTY_BYTES = 1 shl 20
    private const val SCOPE_GLOBAL = 0x676c6f62 // 'glob'
    private const val ELEMENT_MAIN = 0
    private val PROP_DEVICES = fourCC("dev#")
    private val PROP_DEFAULT_OUTPUT = fourCC("dOut")
    private val PROP_DEVICE_UID = fourCC("uid ")
    private val PROP_NOMINAL_RATE = fourCC("nsrt")
    private val PROP_AVAILABLE_NOMINAL_RATES = fourCC("nsr#")
    private val PROP_DEVICE_IS_ALIVE = fourCC("livn")
}

internal interface CoreAudioFunctions : Library {
    fun AudioObjectGetPropertyDataSize(objectId: Int, address: Pointer, qualifierSize: Int, qualifier: Pointer?, dataSize: IntByReference): Int
    fun AudioObjectGetPropertyData(objectId: Int, address: Pointer, qualifierSize: Int, qualifier: Pointer?, dataSize: IntByReference, data: Pointer): Int
    fun AudioObjectSetPropertyData(objectId: Int, address: Pointer, qualifierSize: Int, qualifier: Pointer?, dataSize: Int, data: Pointer): Int
    fun AudioObjectIsPropertySettable(objectId: Int, address: Pointer, settable: Pointer): Int
    fun AudioObjectAddPropertyListener(objectId: Int, address: Pointer, listener: AudioObjectPropertyListenerProc, clientData: Pointer?): Int
    fun AudioObjectRemovePropertyListener(objectId: Int, address: Pointer, listener: AudioObjectPropertyListenerProc, clientData: Pointer?): Int
}

internal fun interface AudioObjectPropertyListenerProc : Callback {
    fun invoke(objectId: Int, numberAddresses: Int, addresses: Pointer?, clientData: Pointer?): Int
}

internal class CoreAudioPropertyAddress(
    selectorValue: Int,
    scopeValue: Int,
    elementValue: Int,
) : Structure() {
    @JvmField var selector = selectorValue
    @JvmField var scope = scopeValue
    @JvmField var element = elementValue
    override fun getFieldOrder(): List<String> = listOf("selector", "scope", "element")
}
