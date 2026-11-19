package com.walkman.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
