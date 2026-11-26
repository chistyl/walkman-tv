package com.walkman.tv.source.js

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsScriptRuntimeCompatibilityTest {
    @Test
    fun `legacy URL string is normalized to the playback object contract`() {
        val result = JsScriptRuntime.normalizeMusicUrlResult(
            "https://cdn.example.com/qq-song.mp3",
            null,
        ) as JSONObject

        assertEquals("https://cdn.example.com/qq-song.mp3", result.getString("url"))
    }

    @Test
    fun `legacy URL string inherits resolver User-Agent`() {
        val result = JsScriptRuntime.normalizeMusicUrlResult(
            "https://cdn.example.com/kugou-song.mp3",
            "LegacySource/1.0",
        ) as JSONObject

        assertEquals("https://cdn.example.com/kugou-song.mp3", result.getString("url"))
        assertEquals("LegacySource/1.0", result.getString("userAgent"))
    }

    @Test
    fun `explicit object User-Agent is preserved`() {
        val result = JsScriptRuntime.normalizeMusicUrlResult(
            JSONObject()
                .put("url", "https://cdn.example.com/song.mp3")
                .put("userAgent", "Explicit/2.0"),
            "Inherited/1.0",
        ) as JSONObject

        assertEquals("Explicit/2.0", result.getString("userAgent"))
    }

    @Test
    fun `non music result is not fabricated`() {
        assertNull(JsScriptRuntime.normalizeMusicUrlResult(null, "Inherited/1.0"))
    }
}
