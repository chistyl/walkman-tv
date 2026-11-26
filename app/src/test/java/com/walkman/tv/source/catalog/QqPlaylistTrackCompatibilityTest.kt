package com.walkman.tv.source.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QqPlaylistTrackCompatibilityTest {
    @Test
    fun legacyPlaylistSongmidAndSongnameAreAccepted() {
        val json = JSONObject(
            """{
              "songmid":"003legacyMid",
              "songname":"Legacy playlist song",
              "singer":[{"name":"Test Singer"}],
              "album":{"mid":"albumMid","id":123,"name":"Test Album"},
              "file":{"size_128mp3":1024,"size_320mp3":4096},
              "interval":215
            }"""
        )

        val track = buildQqTrack(json)

        assertNotNull(track)
        assertEquals("003legacyMid", track!!.songmid)
        assertEquals("Legacy playlist song", track.name)
        assertEquals("Test Singer", track.singer)
        assertEquals(215, track.duration)
    }

    @Test
    fun currentLeaderboardMidAndNameRemainSupported() {
        val json = JSONObject(
            """{
              "mid":"003currentMid",
              "name":"Current leaderboard song",
              "singer":[{"name":"Current Singer"}],
              "file":{"size_128mp3":1024},
              "interval":180
            }"""
        )

        val track = buildQqTrack(json)

        assertNotNull(track)
        assertEquals("003currentMid", track!!.songmid)
        assertEquals("Current leaderboard song", track.name)
    }
}
