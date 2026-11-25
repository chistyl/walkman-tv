package com.walkman.tv.playback

import com.walkman.tv.data.model.Track
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

        // A script's ordinary LRC must not short-circuit the native timed-lyrics
        // endpoint. Try every native provider first and only use untimed LRC as fallback.
        val scriptLyrics = tryScript(track)
        if (scriptLyrics.hasWordTiming()) {
            cache[track.id] = scriptLyrics.orEmpty()
            return scriptLyrics.orEmpty()
        }

        val nativeLyrics = tryBuiltIn(track)
        if (nativeLyrics.hasWordTiming()) {
            cache[track.id] = nativeLyrics.orEmpty()
            return nativeLyrics.orEmpty()
        }

        val fallback = nativeLyrics ?: scriptLyrics
        if (fallback != null) {
            cache[track.id] = fallback
            return fallback
        }

        tryOtherSources(track)?.let { cache[track.id] = it; return it }
        return emptyList()
    }

    private fun List<LyricLine>?.hasWordTiming(): Boolean =
        this?.any { it.words.isNotEmpty() } == true

    private suspend fun tryScript(track: Track): List<LyricLine>? {
        val result = runCatching { sources.requestLyric(track) }.getOrNull() ?: return null
        // Prefer the richest timing payload. Compatible sources may expose YRC/QRC as
        // yrc/qrc/lxlyric while retaining ordinary LRC in lyric.
        val translation = result.optString("tlyric").ifEmpty { null }
        val candidates = listOf("yrc", "qrc", "krc", "lxlyric", "lyric", "lrc")
            .mapNotNull { key -> result.optString(key).takeIf { it.isNotBlank() } }
            .distinct()
        val parsed = candidates.mapNotNull { payload ->
            LyricParser.parse(payload, translation).takeIf { it.isNotEmpty() }
        }
        return parsed.firstOrNull { lines -> lines.any { it.words.isNotEmpty() } }
            ?: parsed.firstOrNull()
    }

    /** Retry a transient direct-endpoint failure once before moving to cross-source search. */
    private suspend fun tryBuiltIn(track: Track): List<LyricLine>? {
        repeat(2) { attempt ->
            val lines = try {
                builtIn.fetch(track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!lines.isNullOrEmpty()) return lines
            if (attempt == 0) delay(400)
        }
        return null
    }

    private suspend fun tryOtherSources(track: Track): List<LyricLine>? {
        val candidates = try {
            withTimeout(18_000) {
                otherSourceFinder.findMatches(track, maxIntervalDiff = 10)
            }
        } catch (e: TimeoutCancellationException) {
            return null
        }
        for (alt in candidates.take(4)) {
            val d1 = track.duration; val d2 = alt.duration
            if (d1 != null && d2 != null && d1 > 0 && d2 > 0 && abs(d1 - d2) > 10) continue
            tryBuiltIn(alt)?.let { return it }
            tryScript(alt)?.let { return it }
        }
        return null
    }

    fun clear() = cache.clear()
}
