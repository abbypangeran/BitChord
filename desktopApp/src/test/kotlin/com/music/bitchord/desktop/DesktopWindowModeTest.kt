package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowModeTest {

    @Test
    fun `adopting native full screen keeps exit state synchronized`() {
        DesktopWindowMode.adopt(WindowPlacement.Floating)
        DesktopWindowMode.adopt(WindowPlacement.Fullscreen)

        assertTrue(DesktopWindowMode.fullScreen.value)
        DesktopWindowMode.exit()
        assertEquals(WindowPlacement.Floating, DesktopWindowMode.placement.value)
        assertFalse(DesktopWindowMode.fullScreen.value)
    }
}
