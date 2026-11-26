package com.walkman.tv.source

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/**
 * lx-music "换源播放" (cross-source fallback), ported from iOS `OtherSourceFinder`.
 * Searches the same name+singer on every other platform and ranks candidates like `findMusic`.
 */
class OtherSourceFinder(private val searcher: MusicSearcher) {

    suspend fun findMatches(
        track: Track,
        maxIntervalDiff: Int = 5,
        includedSources: Set<SourceID>? = null,
        excludedSources: Set<SourceID> = setOf(track.source),
    ): List<Track> {
        val query = "${track.name} ${track.singer}".trim()
        val groups: Map<SourceID, List<Track>> = coroutineScope {
            SourceID.onlineSources
                .filter { it !in excludedSources && (includedSources == null || it in includedSources) }
                .map { src ->
                    async {
                        val results = try {
                            searcher.search(src, query, 10)
                        } catch (cancelled: CancellationException) {
                            // A track/source switch must stop all in-flight alternative searches.
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                        src to results
                    }
                }
                .awaitAll()
                .toMap()
        }
        return rank(groups, track, maxIntervalDiff)
    }

    private data class Annotated(
        val track: Track,
        val fName: String,
        val fSinger: String,
        val fAlbum: String,
        val fInterval: Int,
    )

    private fun rank(groups: Map<SourceID, List<Track>>, target: Track, maxIntervalDiff: Int): List<Track> {
        val fName = filterStr(target.name).lowercase()
        val fSinger = filterStr(sortSingle(target.singer)).lowercase()
        val fAlbum = filterStr(target.albumName ?: "").lowercase()
        val fInterval = target.duration ?: 0

        fun intervalOk(intv: Int): Boolean =
            abs((if (fInterval == 0) intv else fInterval) - (if (intv == 0) fInterval else intv)) <= maxIntervalDiff
        fun includesName(name: String) = fName.contains(name) || name.contains(fName)
        fun includesSinger(singer: String) = if (fSinger.isEmpty()) true else fSinger.contains(singer) || singer.contains(fSinger)
        fun equalsAlbum(album: String) = if (fAlbum.isEmpty()) true else fAlbum == album

        val picked = mutableListOf<Annotated>()
        for ((_, list) in groups) {
            val annotated = list.map {
                Annotated(
                    it,
                    filterStr(it.name).lowercase(),
                    filterStr(sortSingle(it.singer)).lowercase(),
                    filterStr(it.albumName ?: "").lowercase(),
                    it.duration ?: 0,
                )
            }
            val intervalFiltered = annotated.filter { intervalOk(it.fInterval) }
            // Keep several plausible matches per source. Search APIs often rank a live,
            // remix, or karaoke version first; taking only result[0] misses the studio track.
            val matches = intervalFiltered.filter { candidate ->
                val titleMatch = candidate.fName == fName || includesName(candidate.fName)
                val singerMatch = fSinger.isEmpty() || candidate.fSinger == fSinger ||
                    includesSinger(candidate.fSinger)
                val albumMatch = fAlbum.isEmpty() || equalsAlbum(candidate.fAlbum)
                (titleMatch && singerMatch) || (titleMatch && albumMatch) ||
                    (singerMatch && albumMatch)
            }
            picked.addAll(matches)
        }
        if (picked.isEmpty()) return emptyList()

        val predicates: List<(Annotated) -> Boolean> = listOf(
            { it.fSinger == fSinger && it.fName == fName && intervalOk(it.fInterval) },
            { it.fName == fName && it.fSinger == fSinger && it.fAlbum == fAlbum },
            { it.fSinger == fSinger && it.fName == fName },
            { it.fName == fName && intervalOk(it.fInterval) },
            { it.fSinger == fSinger && intervalOk(it.fInterval) },
            { intervalOk(it.fInterval) },
            { it.fName == fName },
            { it.fSinger == fSinger },
            { it.fAlbum == fAlbum },
        )
        val remaining = picked.toMutableList()
        val out = mutableListOf<Track>()
        for (pred in predicates) {
            val matched = remaining.filter(pred)
            out.addAll(matched.map { it.track })
            remaining.removeAll { item -> matched.any { it.track.id == item.track.id } }
        }
        out.addAll(remaining.map { it.track })
        return out
    }

    private companion object {
        val singersRxp = Regex("[、&;；/,，|]")
        val dropCharsRxp = Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\[\\]!！]")

        fun sortSingle(singer: String): String {
            if (!singersRxp.containsMatchIn(singer)) return singer
            return singer.replace(singersRxp, "、").split("、").sorted().joinToString("、")
        }

        fun filterStr(s: String): String = s.replace(dropCharsRxp, "")
    }
}
