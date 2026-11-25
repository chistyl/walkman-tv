package com.walkman.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {
    @Test
    fun parsesLrcFractionalSecondsAtAllSupportedPrecisions() {
        val lines = LyricParser.parse(
            "[00:01.5]one\n[00:02.05]two\n[00:03.005]three",
        )

        assertEquals(3, lines.size)
        assertEquals(1.5, lines[0].time, 0.0001)
        assertEquals(2.05, lines[1].time, 0.0001)
        assertEquals(3.005, lines[2].time, 0.0001)
    }

    @Test
    fun parsesMultipleTimestampsOnOneLine() {
        val lines = LyricParser.parse("[00:01.00][00:02.00]same line")

        assertEquals(2, lines.size)
        assertEquals(1.0, lines[0].time, 0.0001)
        assertEquals(2.0, lines[1].time, 0.0001)
        assertEquals("same line", lines[0].text)
        assertEquals("same line", lines[1].text)
    }

    @Test
    fun keepsPlainTextLyricsWhenNoTimeTagsExist() {
        val lines = LyricParser.parse("first line\nsecond line")

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.time == -1.0 })
        assertEquals("first line", lines[0].text)
        assertEquals("second line", lines[1].text)
    }
}
