package com.walkman.tv.source

import com.walkman.tv.data.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceManagerQualityTest {

    @Test
    fun picksHighestTierAdvertisedByBothTrackAndScript() {
        val track = setOf(Quality.K320, Quality.FLAC)
        val script = setOf(Quality.K320, Quality.FLAC, Quality.FLAC24)

        assertEquals(Quality.FLAC, SourceManager.pickPlayQuality(Quality.MASTER, track, script))
    }

    @Test
    fun extendedTiersCanBeUsedWhenScriptDeclaresThemEvenIfTrackMetadataOmitsThem() {
        val track = setOf(Quality.K320)
        val script = setOf(Quality.K320, Quality.HIRES, Quality.MASTER)

        assertEquals(Quality.MASTER, SourceManager.pickPlayQuality(Quality.MASTER, track, script))
        assertEquals(
            listOf(Quality.MASTER, Quality.HIRES, Quality.K128),
            SourceManager.qualityCascade(Quality.MASTER, track, script),
        )
    }

    @Test
    fun cascadeFallsBackThroughAdvertisedTiersToUniversal128Floor() {
        val track = setOf(Quality.FLAC, Quality.K320)
        val script = setOf(Quality.FLAC, Quality.K320)

        assertEquals(
            listOf(Quality.FLAC, Quality.K320, Quality.K128),
            SourceManager.qualityCascade(Quality.FLAC, track, script),
        )
    }

    @Test
    fun customTiersRequireExactIntersectionAndPreserveProviderOrder() {
        val matched = matchingCustomQualityKeys(
            trackKeys = listOf("lossless_plus", "320k", "spatial", "lossless_plus", "bad key"),
            scriptKeys = listOf("spatial", "320k", "lossless_plus", "master", "spatial", "bad key"),
        )

        assertEquals(listOf("spatial", "lossless_plus"), matched)
    }

    @Test
    fun customTierIsNotTriedWhenTrackDoesNotAdvertiseIt() {
        assertEquals(
            emptyList<String>(),
            matchingCustomQualityKeys(
                trackKeys = listOf("320k"),
                scriptKeys = listOf("lossless_plus"),
            ),
        )
    }
}
