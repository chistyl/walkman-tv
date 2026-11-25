package com.walkman.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VocalRemovalProcessorTest {
    @Test
    fun cancelsCenteredSignal() {
        val (left, right) = VocalRemovalProcessor.cancelCenter(12_000, 12_000)
        assertEquals(0, left)
        assertEquals(0, right)
    }

    @Test
    fun retainsStereoDifferenceWithHeadroom() {
        val (left, right) = VocalRemovalProcessor.cancelCenter(8_000, -8_000)
        assertEquals(12_000, left)
        assertEquals(12_000, right)
    }

    @Test
    fun clampsOutputToPcm16Range() {
        val (left, right) = VocalRemovalProcessor.cancelCenter(32_000, -32_000)
        assertEquals(Short.MAX_VALUE.toInt(), left)
        assertEquals(Short.MAX_VALUE.toInt(), right)
    }
}
