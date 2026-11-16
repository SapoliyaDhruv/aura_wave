package com.aurawave.player.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun `a higher number in any part is newer`() {
        assertTrue(isNewerVersion("3.0.1", "3.0.0"))
        assertTrue(isNewerVersion("v3.1.0", "3.0.9"))
        assertTrue(isNewerVersion("4.0", "3.9.9"))
        assertTrue(isNewerVersion("3.0.10", "3.0.9")) // compared as numbers, not as text
    }

    @Test
    fun `the same or an older version is not offered`() {
        assertFalse(isNewerVersion("3.0.0", "3.0.0"))
        assertFalse(isNewerVersion("v3.0.0", "3.0.0"))
        assertFalse(isNewerVersion("2.9.9", "3.0.0"))
        assertFalse(isNewerVersion("3.0", "3.0.0"))
    }

    @Test
    fun `a finished version is newer than a preview of it`() {
        assertTrue(isNewerVersion("3.0.0", "3.0.0-beta1"))
        assertFalse(isNewerVersion("3.0.0-beta2", "3.0.0"))
    }
}
