package com.walkman.tv.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptPlaybackUrlTest {

    @Test
    fun acceptsPlainUrlString() {
        assertEquals("https://cdn.example/song.mp3", extractPlayableUrl(" https://cdn.example/song.mp3 "))
    }

    @Test
    fun acceptsTopLevelAndNestedUrlObjects() {
        assertEquals(
            "https://cdn.example/a.mp3",
            extractPlayableUrl(JSONObject().put("url", "https://cdn.example/a.mp3")),
        )
        assertEquals(
            "https://cdn.example/b.mp3",
            extractPlayableUrl(JSONObject().put("data", JSONObject().put("url", "https://cdn.example/b.mp3"))),
        )
        assertEquals(
            "https://cdn.example/c.mp3",
            extractPlayableUrl(JSONObject().put("result", JSONObject().put("playUrl", "https://cdn.example/c.mp3"))),
        )
    }

    @Test
    fun acceptsDataAsUrlStringAndRejectsEmptyPayloads() {
        assertEquals(
            "https://cdn.example/d.mp3",
            extractPlayableUrl(JSONObject().put("data", "https://cdn.example/d.mp3")),
        )
        assertNull(extractPlayableUrl(JSONObject().put("data", JSONObject())))
        assertNull(extractPlayableUrl(JSONObject().put("url", "")))
        assertNull(extractPlayableUrl(null))
    }
}
