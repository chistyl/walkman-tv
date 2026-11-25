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

    @Test
    fun parsesYrcWordOffsetsIntoAbsolutePlaybackTimes() {
        val lines = LyricParser.parse(
            "[1000,1200]<0,300,0>你<300,250,0>好",
        )

        assertEquals(1, lines.size)
        assertEquals("你好", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("你", lines[0].words[0].text)
        assertEquals(1000L, lines[0].words[0].startMs)
        assertEquals(300L, lines[0].words[0].durationMs)
        assertEquals("好", lines[0].words[1].text)
        assertEquals(1300L, lines[0].words[1].startMs)
        assertEquals(250L, lines[0].words[1].durationMs)
    }

    @Test
    fun parsesParenthesizedWordTimingAndKeepsPlainLrcAsLineLyrics() {
        val timed = LyricParser.parse("[2000,500](0,200,0)A(200,300,0)B")
        assertEquals("AB", timed.single().text)
        assertEquals(2000L, timed.single().words[0].startMs)
        assertEquals(2200L, timed.single().words[1].startMs)

        val plain = LyricParser.parse("[00:02.00]AB")
        assertTrue(plain.single().words.isEmpty())
        assertEquals("AB", plain.single().text)
    }
}
