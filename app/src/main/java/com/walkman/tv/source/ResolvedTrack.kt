package com.walkman.tv.source

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track

/** How a play URL was produced — surfaced to the UI for transparency (iOS `ResolveOrigin`). */
sealed interface ResolveOrigin {
    val label: String

    data class Script(val name: String) : ResolveOrigin {
        override val label get() = "脚本: $name"
    }
    data object DirectFallback : ResolveOrigin {
        override val label get() = "内置直连"
    }
    data class OtherSource(val source: ScriptSourceKey) : ResolveOrigin {
        override val label get() = "换源: ${source.displayName}"
    }
    data object LocalFile : ResolveOrigin {
        override val label get() = "本地"
    }
}

/** Resolved playable track (iOS `ResolvedTrack`). [warning] is a soft notice for the UI. */
data class ResolvedTrack(
    val url: String,
    val origin: ResolveOrigin,
    val quality: Quality,
    val warning: String? = null,
    /** Exact provider quality key; differs from [quality.key] for custom tiers. */
    val qualityKey: String = quality.key,
)

/** Implemented by the catalog layer so [OtherSourceFinder] can search other platforms. */
interface MusicSearcher {
    suspend fun search(source: SourceID, keyword: String, page: Int = 1): List<Track>
}
