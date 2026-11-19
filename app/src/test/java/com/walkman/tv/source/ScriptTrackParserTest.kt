package com.walkman.tv.source

import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SourceID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTrackParserTest {
    private val source = ScriptSourceKey("custom_catalog")

    @Test
    fun parsesNestedSearchResultsAndKeepsProviderIdentity() {
        val raw = JSONObject().put("code", 200).put("result", JSONObject().put("data", JSONObject()
            .put("songs", JSONArray().put(JSONObject()
                .put("music_id", "m_01")
                .put("musicName", "測試歌曲")
                .put("artists", JSONArray().put(JSONObject().put("name", "歌手甲")).put("歌手乙"))
                .put("album", JSONObject().put("id", "al_1").put("name", "專輯一").put("picUrl", "https://example.com/a.jpg"))
                .put("durationMs", 185000)
                .put("types", JSONArray().put(JSONObject().put("type", "320k")).put("flac"))
            ))
        ))

        val tracks = ScriptTrackParser.parseSearch(raw, source, "自訂目錄")

        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("custom_catalog_m_01", track.id)
        assertEquals("測試歌曲", track.name)
        assertEquals("歌手甲、歌手乙", track.singer)
        assertEquals("專輯一", track.albumName)
        assertEquals("al_1", track.albumId)
        assertEquals(185, track.duration)
        assertEquals("https://example.com/a.jpg", track.picURL)
        assertEquals(listOf("320k", "flac"), track.qualityKeys)
        assertEquals("custom_catalog", track.providerKey)
        assertEquals("自訂目錄", track.providerDisplayName)
        assertNull(track.builtInSource)
        assertEquals(SourceID.LOCAL, track.source)
    }

    @Test
    fun supportsDirectArraysAndCommonAliases() {
        val raw = JSONArray().put(JSONObject()
            .put("mid", "song2")
            .put("title", "第二首")
            .put("artist", "歌手乙")
            .put("interval", 210)
            .put("coverUrl", "https://example.com/b.jpg")
            .put("qualitys", JSONObject().put("320k", JSONObject().put("size", "8M")).put("lossless_plus", true))
        )

        val track = ScriptTrackParser.parseSearch(raw, source, "自訂目錄").single()

        assertEquals("song2", track.songmid)
        assertEquals("歌手乙", track.singer)
        assertEquals(210, track.duration)
        assertEquals(listOf("320k", "lossless_plus"), track.qualityKeys)
    }

    @Test
    fun preservesProviderSpecificFieldsAndHashBasedSongIdentity() {
        val raw = JSONObject().put("list", JSONArray().put(JSONObject()
            .put("hash", "ABC123")
            .put("name", "雲端歌曲")
            .put("singer", "歌手丙")
            .put("mvId", "mv_9")
            .put("strMediaMid", "media_7")
            .put("extras", JSONObject().put("copyrightId", "copy_3"))
        ))

        val track = ScriptTrackParser.parseSearch(raw, source, "自訂目錄").single()

        assertEquals("custom_catalog_ABC123", track.id)
        assertEquals("ABC123", track.extras["hash"])
        assertEquals("mv_9", track.extras["mvId"])
        assertEquals("media_7", track.extras["strMediaMid"])
        assertEquals("copy_3", track.extras["copyrightId"])
    }

    @Test
    fun ignoresRowsWithoutStableSongIdOrNameAndDeduplicates() {
        val raw = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("name", "沒有 ID"))
            .put(JSONObject().put("id", "x1"))
            .put(JSONObject().put("id", "x2").put("name", "有效歌曲"))
            .put(JSONObject().put("id", "x2").put("name", "重複歌曲"))
        )

        val tracks = ScriptTrackParser.parseSearch(raw, source, "自訂目錄")

        assertEquals(1, tracks.size)
        assertEquals("有效歌曲", tracks.single().name)
        assertTrue(ScriptTrackParser.parseSearch(JSONObject(), source, "自訂目錄").isEmpty())
    }
}
