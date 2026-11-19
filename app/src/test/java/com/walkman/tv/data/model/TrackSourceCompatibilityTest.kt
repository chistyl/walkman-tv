package com.walkman.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TrackSourceCompatibilityTest {

    @Test
    fun customProviderIsNotMisidentifiedAsLocal() {
        val track = Track(
            id = "custom_song_1",
            name = "測試歌曲",
            singer = "測試歌手",
            source = SourceID.LOCAL,
            songmid = "custom_song_1",
            sourceKey = "custom_provider",
            sourceDisplayName = "自訂音源",
        )

        assertEquals("custom_provider", track.providerKey)
        assertEquals("自訂音源", track.providerDisplayName)
        assertNull(track.builtInSource)
    }

    @Test
    fun builtInProviderKeyResolvesToBuiltInSource() {
        val track = Track(
            id = "wy_song_1",
            name = "測試歌曲",
            singer = "測試歌手",
            source = SourceID.LOCAL,
            songmid = "wy_song_1",
            sourceKey = "wy",
        )

        assertEquals(SourceID.WY, track.builtInSource)
        assertEquals("网易云", track.providerDisplayName)
    }

    @Test
    fun legacyTrackWithoutProviderKeyKeepsItsOriginalSource() {
        val track = Track(
            id = "local_song_1",
            name = "本地歌曲",
            singer = "本地歌手",
            source = SourceID.LOCAL,
            songmid = "local_song_1",
        )

        assertEquals("local", track.providerKey)
        assertEquals(SourceID.LOCAL, track.builtInSource)
        assertEquals("本地", track.providerDisplayName)
    }

    @Test
    fun invalidCustomProviderKeyDoesNotBecomeAValidScriptKey() {
        assertNull(ScriptSourceKey.from("provider key"))
        assertNull(ScriptSourceKey.from(""))
        assertNull(ScriptSourceKey.from(null))
    }
    @Test
    fun customProviderIdentityAndUnknownQualitiesSurviveJsonRoundTrip() {
        val track = Track(
            id = "custom_provider_song_1",
            name = "測試歌曲",
            singer = "測試歌手",
            source = SourceID.LOCAL,
            songmid = "song_1",
            sourceKey = "custom_provider",
            sourceDisplayName = "自訂平台",
            qualityKeys = listOf("320k", "lossless_plus"),
        )
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<Track>(json.encodeToString(track))

        assertEquals(track.id, restored.id)
        assertEquals("custom_provider", restored.providerKey)
        assertEquals("自訂平台", restored.providerDisplayName)
        assertEquals(listOf("320k", "lossless_plus"), restored.qualityKeys)
        assertNull(restored.builtInSource)
    }

    @Test
    fun legacyTrackJsonWithoutCustomFieldsStillLoads() {
        val legacyJson = """
            {
              "id": "local_old_1",
              "name": "舊歌曲",
              "singer": "舊歌手",
              "source": "local",
              "songmid": "old_1"
            }
        """.trimIndent()
        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString<Track>(legacyJson)

        assertEquals("local", restored.providerKey)
        assertEquals(SourceID.LOCAL, restored.builtInSource)
        assertTrue(restored.qualityKeys.isEmpty())
        assertNull(restored.sourceKey)
    }

    @Test
    fun playlistOriginPreservesCustomProviderKey() {
        val origin = PlaylistOrigin(
            source = SourceID.LOCAL,
            id = "playlist_123",
            platformTrackIds = listOf("song_1", "song_2"),
            sourceKey = "custom_provider",
        )
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<PlaylistOrigin>(json.encodeToString(origin))

        assertEquals("custom_provider", restored.providerKey)
        assertEquals(SourceID.LOCAL, restored.source)
        assertEquals(listOf("song_1", "song_2"), restored.platformTrackIds)
    }

    @Test
    fun playlistOriginIdentifiesDynamicProviderWithoutMisclassifyingBuiltIns() {
        val custom = PlaylistOrigin(
            source = SourceID.LOCAL,
            id = "custom_list",
            sourceKey = "custom_provider",
        )
        val builtIn = PlaylistOrigin(
            source = SourceID.WY,
            id = "wy_list",
            sourceKey = "wy",
        )
        val legacy = PlaylistOrigin(
            source = SourceID.LOCAL,
            id = "local_list",
        )

        assertTrue(custom.isCustomProvider)
        assertEquals(false, builtIn.isCustomProvider)
        assertEquals(false, legacy.isCustomProvider)
    }

    @Test
    fun songlistInfoPreservesCustomProviderIdentity() {
        val custom = SonglistInfo(
            id = "list_1",
            source = SourceID.LOCAL,
            name = "自訂歌單",
            sourceKey = "custom_provider",
            sourceDisplayName = "自訂平台",
        )
        val builtIn = SonglistInfo(
            id = "list_2",
            source = SourceID.WY,
            name = "內建歌單",
        )

        assertEquals("custom_provider", custom.providerKey)
        assertEquals("自訂平台", custom.providerDisplayName)
        assertNull(custom.builtInSource)
        assertEquals("wy", builtIn.providerKey)
        assertEquals(SourceID.WY, builtIn.builtInSource)
    }

    @Test
    fun legacyPlaylistOriginWithoutSourceKeyStillLoads() {
        val legacyJson = """{"source":"wy","id":"playlist_456","platformTrackIds":["song_3"]}"""
        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString<PlaylistOrigin>(legacyJson)

        assertEquals("wy", restored.providerKey)
        assertEquals(SourceID.WY, restored.source)
    }

}
