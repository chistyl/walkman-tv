package com.walkman.tv.ui.recommend

import android.os.SystemClock
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.Songlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A hero banner card — top of the discover page. */
data class HeroItem(val songlist: SonglistInfo, val source: SourceID)

data class HomeState(
    val isLoading: Boolean = false,
    val heroes: List<HeroItem> = emptyList(),
    val recommendations: List<SonglistInfo> = emptyList(),
    val boards: List<BoardInfo> = emptyList(),
    val sources: List<SourceID> = emptyList(),
)

/**
 * Backs the discover (home) page right column. Implements the data flow in spec
 * docs/discover-page-spec-android-tv.md §3:
 *   1. Parallel fetch each enabled source's top recommended songlists (1st order, ALL tag, page 1).
 *   2. Hero pool = first songlist of each source.
 *   3. Recommendations row = drop-first interleave across sources, take 12.
 *   4. Boards row = quota-balanced across sources, target total 12.
 *
 * Results are cached in-process for five minutes. Expired entries are shown immediately while
 * the refresh runs, so returning to the home page does not wait for provider network requests.
 */
class HomeStore(
    private val songlists: Songlists,
    private val boards: Boards,
) {
    private data class CachedHome(val state: HomeState, val cachedAt: Long)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    /** Prevents a cancelled, slower request from publishing over a newer source selection. */
    private var loadGeneration: Long = 0L
    private val cache = mutableMapOf<String, CachedHome>()

    /** Identity of the last requested source set. */
    private var lastSourcesKey: String = ""

    fun loadIfNeeded(homeSources: Set<SourceID>, force: Boolean = false) {
        val srcs = SourceID.homePageOrder.filter { it in homeSources }
        val key = srcs.joinToString(",") { it.key }
        val now = SystemClock.elapsedRealtime()
        val cached = cache[key]
        val cacheFresh = cached != null && now - cached.cachedAt < CACHE_TTL_MS

        if (!force && key == lastSourcesKey && !_state.value.isLoading && cacheFresh) return
        if (!force && key == lastSourcesKey && _state.value.isLoading) return

        lastSourcesKey = key
        if (srcs.isEmpty()) {
            loadGeneration++
            loadJob?.cancel()
            _state.value = HomeState()
            return
        }

        // Serve cached content immediately, including stale content during a background refresh.
        if (!force && cached != null) {
            _state.value = cached.state.copy(isLoading = !cacheFresh, sources = srcs)
            if (cacheFresh) return
        }
        load(srcs, key)
    }

    private suspend fun <T> fetchOrEmpty(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

    private fun load(srcs: List<SourceID>, key: String) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = scope.launch {
            _state.update { current ->
                if (generation == loadGeneration) current.copy(isLoading = true, sources = srcs) else current
            }

            // Start recommendations and boards together. Publish useful home content as soon as
            // recommendations finish; do not make the hero section wait for ranking endpoints.
            coroutineScope {
                val recommendationsJob = async {
                    srcs.map { s ->
                        async sourceFetch@{
                            val svc = songlists.serviceFor(s)
                            if (svc == null) return@sourceFetch s to emptyList()
                            val order = svc.orders.firstOrNull() ?: return@sourceFetch s to emptyList()
                            val list = fetchOrEmpty { svc.fetchRecommended(order, SonglistTag.ALL, 1) }
                            s to list
                        }
                    }.awaitAll().toMap()
                }
                val boardsJob = async {
                    srcs.map { s ->
                        async sourceFetch@{
                            val svc = boards.serviceFor(s)
                            if (svc == null) return@sourceFetch s to emptyList()
                            s to fetchOrEmpty { boards.fetchBoards(s) }
                        }
                    }.awaitAll().toMap()
                }

                val groups = recommendationsJob.await()
                if (generation != loadGeneration) return@coroutineScope
                val heroes = srcs.mapNotNull { s -> groups[s]?.firstOrNull()?.let { HeroItem(it, s) } }

                val tails = srcs.associateWith { groups[it].orEmpty().drop(1).take(8) }
                val recs = mutableListOf<SonglistInfo>()
                val maxSize = tails.values.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxSize) {
                    for (s in srcs) tails[s]?.getOrNull(i)?.let { recs += it }
                }

                _state.update { current ->
                    if (generation == loadGeneration) current.copy(heroes = heroes, recommendations = recs.take(12), sources = srcs) else current
                }

                val boardResults = boardsJob.await()
                if (generation != loadGeneration) return@coroutineScope
                val boardList = balanceBoards(srcs, boardResults, totalTarget = 12)
                val completed = HomeState(
                    isLoading = false,
                    heroes = heroes,
                    recommendations = recs.take(12),
                    boards = boardList,
                    sources = srcs,
                )
                if (generation == loadGeneration) {
                    cache[key] = CachedHome(completed, SystemClock.elapsedRealtime())
                    _state.value = completed
                }
            }
        }
    }

    /**
     * Quota algorithm (spec §3): each source gets `totalTarget / n` slots; remaining slots are
     * round-robin'd to whichever source still has more to offer. Bail after 2n cursor laps.
     */
    private fun balanceBoards(
        srcs: List<SourceID>,
        bySource: Map<SourceID, List<BoardInfo>>,
        totalTarget: Int,
    ): List<BoardInfo> {
        val n = srcs.size
        if (n == 0) return emptyList()
        val perSource = (totalTarget / n).coerceAtLeast(1)
        val picked: Map<SourceID, MutableList<BoardInfo>> = srcs.associateWith { s ->
            bySource[s].orEmpty().take(perSource).toMutableList()
        }
        var remaining = totalTarget - picked.values.sumOf { it.size }
        var cursor = 0
        while (remaining > 0 && cursor <= 2 * n) {
            val s = srcs[cursor % n]
            cursor++
            val current = picked[s]!!.size
            val available = bySource[s]?.size ?: 0
            if (current < available) {
                picked[s]!!.add(bySource[s]!![current])
                remaining--
            }
        }
        return srcs.flatMap { picked[it].orEmpty() }
    }

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
