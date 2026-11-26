package com.walkman.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayUrlValidatorTest {
    @Test
    fun acceptsValidHttpAndHttpsPlaybackUrls() {
        assertEquals(
            "https://cdn.example.com/song.mp3?token=abc%2F123",
            PlayUrlValidator.requireHttpUrl("  https://cdn.example.com/song.mp3?token=abc%2F123  "),
        )
        assertEquals(
            "http://audio.example.com/track",
            PlayUrlValidator.requireHttpUrl("http://audio.example.com/track"),
        )
    }

    @Test
    fun rejectsEmptyPlaceholderAndNonWebUrls() {
        listOf("", "   ", "null", "undefined", "file:///tmp/song.mp3", "ftp://audio.example.com/song.mp3")
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    PlayUrlValidator.requireHttpUrl(value)
                }
            }
    }

    @Test
    fun rejectsMalformedUrlsAndUrlsWithoutHost() {
        listOf("https://", "https:///song.mp3", "not a url", "https://?token=abc")
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    PlayUrlValidator.requireHttpUrl(value)
                }
            }
    }
}
