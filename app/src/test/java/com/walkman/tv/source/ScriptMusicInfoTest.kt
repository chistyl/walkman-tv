package com.walkman.tv.source

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMusicInfoTest {

    @Test
    fun canonicalFieldsCannotBeOverwrittenByProviderExtras() {
        val track = Track(
            id = "provider_song-1",
            name = "Correct title",
            singer = "Correct artist",
            albumName = "Correct album",
            albumId = "album-1",
            source = SourceID.LOCAL,
            songmid = "song-1",
            duration = 185,
            sourceKey = "provider",
            sourceDisplayName = "Provider",
            qualities = listOf(Quality.K320),
            qualityKeys = listOf("lossless_plus"),
            extras = mapOf(
                "name" to "Injected title",
                "source" to "wrong-provider",
                "songmid" to "wrong-song",
                "types" to "wrong-types",
                "_types" to "wrong-types-object",
                "hash" to "provider-hash",
                "strMediaMid" to "provider-media-mid",
                "mvId" to "mv-1",
            ),
        )

        val info = buildScriptMusicInfo(track)

        assertEquals("Correct title", info.getString("name"))
        assertEquals("Correct artist", info.getString("singer"))
        assertEquals("provider", info.getString("source"))
        assertEquals("song-1", info.getString("songmid"))
        assertEquals("03:05", info.getString("interval"))
        assertEquals("Correct album", info.getString("albumName"))
        assertTrue(info.get("types") is org.json.JSONArray)
        assertTrue(info.get("_types") is org.json.JSONObject)
        assertEquals("provider-hash", info.getString("hash"))
        assertEquals("provider-media-mid", info.getString("strMediaMid"))
        assertEquals("mv-1", info.getString("mvId"))
    }

    @Test
    fun retainsProviderExtrasAndBuildsBothQualityRepresentations() {
        val track = Track(
            id = "kw_123",
            name = "Song",
            singer = "Artist",
            source = SourceID.KW,
            songmid = "123",
            qualities = listOf(Quality.K320),
            qualityKeys = listOf("spatial"),
            extras = mapOf("copyrightId" to "copyright-7"),
        )

        val info = buildScriptMusicInfo(track)
        val types = info.getJSONArray("types")
        val typeKeys = (0 until types.length()).map { types.getJSONObject(it).getString("type") }

        assertEquals(listOf("320k", "spatial"), typeKeys)
        assertTrue(info.getJSONObject("_types").has("320k"))
        assertTrue(info.getJSONObject("_types").has("spatial"))
        assertEquals("copyright-7", info.getString("copyrightId"))
        assertFalse(info.has("not-present"))
    }
}
