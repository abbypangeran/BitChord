package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowGeometryTest {

    @Test
    fun `Cocoa window bounds ignore Retina backing scale`() {
        assertEquals(900, desktopWindowDimension(900.0, 2.0, isMac = true))
        assertEquals(1220, desktopWindowDimension(1220.0, 2.0, isMac = true))
    }

    @Test
    fun `other desktop bounds retain AWT backing scale`() {
        assertEquals(1800, desktopWindowDimension(900.0, 2.0, isMac = false))
    }
}
