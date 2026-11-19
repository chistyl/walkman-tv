package com.walkman.tv.playback.imports

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPlaylistMergeTest {

    private fun track(id: String, source: SourceID = SourceID.LOCAL) = Track(
        id = id,
        name = id,
        singer = "測試歌手",
        source = source,
        songmid = id,
    )

    @Test
    fun refreshReplacesPlatformTracksAndKeepsManualTracks() {
        val merged = mergeRefreshedTracks(
            existing = listOf(track("provider_old"), track("manual_added")),
            previousPlatformIds = setOf("provider_old"),
            fallbackSource = SourceID.LOCAL,
            incoming = listOf(track("provider_new")),
        )

        assertEquals(listOf("provider_new", "manual_added"), merged.tracks.map { it.id })
        assertEquals(listOf("provider_new"), merged.platformIds)
        assertEquals(1, merged.result.added)
        assertEquals(1, merged.result.removed)
        assertEquals(1, merged.result.kept)
        assertEquals(2, merged.result.total)
    }

    @Test
    fun refreshDoesNotKeepManualTrackWhenProviderNowContainsIt() {
        val merged = mergeRefreshedTracks(
            existing = listOf(track("provider_old"), track("manual_added")),
            previousPlatformIds = setOf("provider_old"),
            fallbackSource = SourceID.LOCAL,
            incoming = listOf(track("manual_added"), track("provider_new")),
        )

        assertEquals(listOf("manual_added", "provider_new"), merged.tracks.map { it.id })
        assertEquals(0, merged.result.kept)
        assertEquals(1, merged.result.added)
        assertEquals(1, merged.result.removed)
    }

    @Test
    fun refreshDeduplicatesIncomingAndManualTracks() {
        val merged = mergeRefreshedTracks(
            existing = listOf(track("old"), track("manual"), track("manual")),
            previousPlatformIds = setOf("old"),
            fallbackSource = SourceID.LOCAL,
            incoming = listOf(track("new"), track("new")),
        )

        assertEquals(listOf("new", "manual"), merged.tracks.map { it.id })
        assertEquals(listOf("new"), merged.platformIds)
        assertEquals(1, merged.result.total)
        assertEquals(1, merged.result.kept)
    }

    @Test
    fun legacyRefreshUsesFallbackSourceWhenSnapshotIsMissing() {
        val merged = mergeRefreshedTracks(
            existing = listOf(track("old_platform", SourceID.KG), track("manual", SourceID.LOCAL)),
            previousPlatformIds = null,
            fallbackSource = SourceID.KG,
            incoming = listOf(track("new_platform", SourceID.KG)),
        )

        assertEquals(listOf("new_platform", "manual"), merged.tracks.map { it.id })
        assertEquals(1, merged.result.kept)
    }
}
