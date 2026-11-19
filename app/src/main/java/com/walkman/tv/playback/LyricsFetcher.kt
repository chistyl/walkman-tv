package com.walkman.tv.playback

import com.walkman.tv.data.model.Track
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Fetches lyrics: user script → direct fallback → cross-source. Ported from iOS `LyricsFetcher`. */
class LyricsFetcher(
    private val sources: SourceManager,
    private val builtIn: BuiltInLyricResolver,
    private val otherSourceFinder: OtherSourceFinder,
) {
    private val cache = ConcurrentHashMap<String, List<LyricLine>>()

    suspend fun fetch(track: Track): List<LyricLine> {
        cache[track.id]?.let { return it }
        tryScript(track)?.let { cache[track.id] = it; return it }
        builtIn.fetch(track)?.let { cache[track.id] = it; return it }
        tryOtherSources(track)?.let { cache[track.id] = it; return it }
        return emptyList()
    }

    private suspend fun tryScript(track: Track): List<LyricLine>? {
        val result = runCatching { sources.requestLyric(track) }.getOrNull() ?: return null
        val lyric = result.optString("lyric")
        val tlyric = result.optString("tlyric").ifEmpty { null }
        return LyricParser.parse(lyric, tlyric).ifEmpty { null }
    }

    private suspend fun tryOtherSources(track: Track): List<LyricLine>? {
        // Keep native catalog matching, and also search every loaded custom provider that
        // explicitly declares "search". Custom providers cannot be represented by SourceID.
        val nativeCandidates = otherSourceFinder.findMatches(track, maxIntervalDiff = 10)
        val customCandidates = runCatching {
            sources.searchAll("${track.name} ${track.singer}", 1)
        }.getOrDefault(emptyList())
            .filter { it.providerKey != track.providerKey }
            .filter { matchesTrackIdentity(track, it) }
        val candidates = (nativeCandidates + customCandidates).distinctBy { it.id }

        for (alt in candidates.take(10)) {
            val d1 = track.duration
            val d2 = alt.duration
            if (d1 != null && d2 != null && d1 > 0 && d2 > 0 && abs(d1 - d2) > 10) continue
            builtIn.fetch(alt)?.let { return it }
            tryScript(alt)?.let { return it }
        }
        return null
    }

    private fun matchesTrackIdentity(target: Track, candidate: Track): Boolean {
        fun normalize(value: String): String =
            value.lowercase().replace(Regex("[\\s\\p{Punct}、，。！？（）【】《》「」『』]"), "")

        val targetName = normalize(target.name)
        val candidateName = normalize(candidate.name)
        if (targetName.isEmpty() || candidateName.isEmpty()) return false
        val sameName = targetName == candidateName ||
            targetName.contains(candidateName) || candidateName.contains(targetName)
        if (!sameName) return false

        val targetSinger = normalize(target.singer)
        val candidateSinger = normalize(candidate.singer)
        return targetSinger.isEmpty() || candidateSinger.isEmpty() ||
            targetSinger == candidateSinger ||
            targetSinger.contains(candidateSinger) || candidateSinger.contains(targetSinger)
    }

    fun clear() = cache.clear()
}
