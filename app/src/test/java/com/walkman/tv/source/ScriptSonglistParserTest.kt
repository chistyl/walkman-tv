package com.walkman.tv.source

import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SourceID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSonglistParserTest {
    private val source = ScriptSourceKey("custom_provider")

    @Test
    fun parsesPlaylistAliasesAndPreservesDynamicProvider() {
        val raw = JSONObject().put("data", JSONObject().put("playlists", JSONArray().put(
            JSONObject()
                .put("playlistId", "pl_01")
                .put("title", "測試歌單")
                .put("creator", JSONObject().put("nickname", "測試作者"))
                .put("coverImgUrl", "https://example.com/cover.jpg")
                .put("songCount", 12)
                .put("playCount", "1.2萬")
        )))
        val result = ScriptSonglistParser.parseLists(raw, source, "自訂平台")

        assertEquals(1, result.size)
        assertEquals("pl_01", result.first().id)
        assertEquals("測試歌單", result.first().name)
        assertEquals("測試作者", result.first().author)
        assertEquals("custom_provider", result.first().providerKey)
        assertEquals("自訂平台", result.first().providerDisplayName)
        assertNull(result.first().builtInSource)
        assertEquals(SourceID.LOCAL, result.first().source)
    }

    @Test
    fun parsesDetailTracksAndCustomQualityKeys() {
        val requested = com.walkman.tv.data.model.SonglistInfo(
            id = "pl_02",
            source = SourceID.LOCAL,
            name = "原始名稱",
            sourceKey = source.value,
        )
        val raw = JSONObject().put("result", JSONObject()
            .put("name", "詳情歌單")
            .put("id", "pl_02")
            .put("songs", JSONArray().put(
                JSONObject()
                    .put("id", "song_01")
                    .put("songName", "測試歌曲")
                    .put("artists", JSONArray().put("歌手甲").put("歌手乙"))
                    .put("durationMs", 185000)
                    .put("types", JSONArray().put("320k").put("lossless_plus"))
                    .put("cover", "https://example.com/song.jpg")
                    .put("hash", "abc123")
            ))
        )

        val detail = ScriptSonglistParser.parseDetail(raw, requested, source, "自訂平台")

        assertTrue(detail != null)
        assertEquals("詳情歌單", detail!!.info.name)
        assertEquals("custom_provider", detail.info.providerKey)
        assertEquals(1, detail.tracks.size)
        val track = detail.tracks.first()
        assertEquals("custom_provider_song_01", track.id)
        assertEquals("歌手甲、歌手乙", track.singer)
        assertEquals(185, track.duration)
        assertEquals(listOf("320k", "lossless_plus"), track.qualityKeys)
        assertEquals("abc123", track.extras["hash"])
        assertNull(track.builtInSource)
    }

    @Test
    fun rejectsRowsWithoutStableIds() {
        val raw = JSONArray().put(JSONObject().put("name", "沒有 ID"))
        assertTrue(ScriptSonglistParser.parseLists(raw, source, "自訂平台").isEmpty())
    }
}
