package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Linux and macOS answer to the Windows endpoint watcher (`window_jni.cpp`'s
 * `IMMNotificationClient`): notice that the set of outputs changed, and bump
 * [DesktopAudioDevices.systemChanged] so open lists re-enumerate and
 * [DesktopPlaybackEngine] reconfigures the line.
 *
 * Windows gets its event pushed by the OS. Linux offers no portable push for
 * this — the D-Bus route would mean speaking to PipeWire's private
 * `org.pipewire.PipeWire` protocol or parsing WirePlumper state, neither of
 * which a dependency-free watcher should — so this asks the kernel instead:
 * `/proc/asound/cards` names the cards ALSA (and so `default`, which PipeWire
 * occupies on a normal desktop) presents. Reading it every few seconds costs
 * almost nothing and misses nothing that matters; a Bluetooth or USB DAC
 * appearing is a card row, whether or not PipeWire was up when we started.
 *
 * macOS also includes the default output's CoreAudio UID in its snapshot because
 * BitChord pins a Java Sound line to that mixer. Linux's `default` mixer follows
 * desktop routing itself, so only its per-card `plughw` lines need reconfiguration.
 */
internal object DesktopAudioDeviceWatcher {

    private const val CARDS = "/proc/asound/cards"
    private const val POLL_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    /** Started once per process, from the engine's construction. */
    fun ensureStarted() {
        if (started || (!DesktopPlatform.isLinux && !DesktopPlatform.isMac)) return
        started = true
        scope.launch {
            var lastSelection = DesktopAudioDevices.selected.value
            var last = deviceSnapshot(lastSelection)
            while (true) {
                delay(POLL_MS)
                val selection = DesktopAudioDevices.selected.value
                val now = deviceSnapshot(selection)
                if (shouldReconfigureAudioDevices(
                        previous = last,
                        current = now,
                        selectedSystemDefault = selection == DesktopAudioDevices.SYSTEM_DEFAULT,
                        selectionChanged = selection != lastSelection,
                    )
                ) {
                    DesktopAudioDevices.systemChanged()
                    DesktopTrackLog.log("audio devices changed ($now)")
                }
                last = now
                lastSelection = selection
            }
        }
    }

    /** The cards file's text, normalised so formatting churn does not count as a change. */
    private fun readCards(): String = runCatching {
        File(CARDS).readText().trim().replace(Regex("\\s+"), " ")
    }.getOrDefault("")

    private fun deviceSnapshot(selectionId: String): DesktopAudioDeviceSnapshot = if (DesktopPlatform.isMac) {
        DesktopAudioDeviceSnapshot(
            outputs = DesktopAudioDevices.available().map { it.id }.sorted(),
            defaultOutputUid = if (selectionId == DesktopAudioDevices.SYSTEM_DEFAULT) {
                DesktopMacCoreAudioAccess.defaultOutputUid()
            } else {
                null
            },
        )
    } else {
        DesktopAudioDeviceSnapshot(listOf(readCards()))
    }
}

internal data class DesktopAudioDeviceSnapshot(
    val outputs: List<String>,
    val defaultOutputUid: String? = null,
)

internal fun shouldReconfigureAudioDevices(
    previous: DesktopAudioDeviceSnapshot,
    current: DesktopAudioDeviceSnapshot,
    selectedSystemDefault: Boolean,
    selectionChanged: Boolean,
): Boolean = previous.outputs != current.outputs ||
    (!selectionChanged && selectedSystemDefault && previous.defaultOutputUid != current.defaultOutputUid)
