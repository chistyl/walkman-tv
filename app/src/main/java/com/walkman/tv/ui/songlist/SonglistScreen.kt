package com.walkman.tv.ui.songlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistOrder
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.util.ChineseText
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.MediaCard
import com.walkman.tv.ui.components.TvPill
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val songlistSources = listOf(SourceID.KW, SourceID.WY, SourceID.KG, SourceID.TX)

// Small in-memory cache for previously visited songlist pages.
private val songlistPageCache = LinkedHashMap<String, List<SonglistInfo>>()

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SonglistScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val settings by appContainer.settingsStore.settings.collectAsState()
    val enabledSources = settings.homeSources
    val visibleSonglistSources = songlistSources.filter { it in enabledSources }
    var source by remember { mutableStateOf(visibleSonglistSources.firstOrNull() ?: SourceID.KW) }
    LaunchedEffect(visibleSonglistSources) {
        if (source !in visibleSonglistSources) {
            source = visibleSonglistSources.firstOrNull() ?: SourceID.KW
            detail = null
            lists = emptyList()
        }
    }
    // Seed with the default source's first order (not null): the sibling effect below re-writes
    // it with an *equal* value on first run, so mutableStateOf's structural equality skips the
    // invalidation — the list effect runs exactly once on entry instead of fetch → cancel →
    // refetch (the cancelled first fetch was the root of the empty-on-first-entry bug).
    var order by remember {
        mutableStateOf(appContainer.songlists.serviceFor(SourceID.KW)?.orders?.firstOrNull())
    }
    var tags by remember { mutableStateOf<List<SonglistTag>>(listOf(SonglistTag.ALL)) }
    var tag by remember { mutableStateOf(SonglistTag.ALL) }
    var lists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<SonglistInfo, List<Track>>?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }

    val service = appContainer.songlists.serviceFor(source)

    // Warm the first page from the other providers in parallel so switching source feels instant.
    // The active provider is handled by the normal screen effect and is intentionally excluded.
    LaunchedEffect(Unit) {
        coroutineScope {
            visibleSonglistSources.filter { it != source }.map { sourceId ->
                async {
                    val svc = appContainer.songlists.serviceFor(sourceId) ?: return@async
                    val order = svc.orders.firstOrNull() ?: return@async
                    val key = sourceId.key + ":" + order.id + ":" + SonglistTag.ALL.id + ":" + SonglistTag.ALL.name
                    if (songlistPageCache[key].isNullOrEmpty()) {
                        val result = fetchOr(emptyList()) { svc.fetchRecommended(order, SonglistTag.ALL, 1) }
                        if (result.isNotEmpty()) songlistPageCache[key] = result
                    }
                }
            }.awaitAll()
        }
    }

    LaunchedEffect(source) {
        order = service?.orders?.firstOrNull()
        tag = SonglistTag.ALL
        val groups = fetchOr(emptyList()) { service?.fetchTags() ?: emptyList() }
        tags = listOf(SonglistTag.ALL) + groups.flatMap { it.tags }
    }
    LaunchedEffect(source, order, tag) {
        val o = order ?: return@LaunchedEffect
        // When switching platforms, the previous platform's order can briefly remain in state.
        // Skip that transient request instead of starting a fetch that will immediately be cancelled.
        if (service?.orders?.none { it.id == o.id } == true) return@LaunchedEffect
        val cacheKey = source.key + ":" + o.id + ":" + tag.id + ":" + tag.name
        val cached = songlistPageCache[cacheKey].orEmpty()
        lists = cached
        loading = cached.isEmpty()
        val fresh = fetchOr(emptyList()) { service?.fetchRecommended(o, tag, 1) ?: emptyList() }
        if (fresh.isNotEmpty()) {
            songlistPageCache[cacheKey] = fresh
            while (songlistPageCache.size > 32) songlistPageCache.remove(songlistPageCache.keys.first())
            lists = fresh
        }
        loading = false
    }

    // IMPORTANT: keep the grid UI in the composition even when detail is open. If we early-return
    // here, the LaunchedEffect(source, order, tag) above leaves the composition; on the way back
    // (detail=null) it re-fires as a fresh effect and re-fetches lists, blowing away the user's
    // grid scroll position and the previously-focused card. Rendering detail as an overlay keeps
    // the grid alive (state, focus, fetched lists), so back-navigation is instant and preserves
    // the selection.
    if (visibleSonglistSources.isEmpty()) {
        EmptyHint("目前沒有啟用的音源，請至設定開啟音源", Modifier.fillMaxSize())
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 25.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                visibleSonglistSources.forEach { s ->
                    TvPill(onClick = { source = s }, selected = source == s) { Text(s.displayName, fontSize = 12.sp) }
                }
                Spacer(Modifier.padding(start = 8.dp))
                service?.orders?.forEach { o ->
                    TvPill(onClick = { order = o }, selected = order?.id == o.id) { Text(ChineseText.toTaiwanTraditional(o.name), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tags) { t ->
                    TvPill(onClick = { tag = t }, selected = tag.id == t.id && tag.name == t.name) { Text(ChineseText.toTaiwanTraditional(t.name), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            val uniqueLists = remember(lists) { lists.distinctBy { it.id } }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> LoadingState(Modifier.fillMaxSize())
                lists.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(top = 12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .focusGroup(), // keep D-pad traversal grouped without stale focus restoration
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uniqueLists, key = { it.id }) { sl ->
                        MediaCard(title = sl.displayName, picURL = sl.picURL, subtitle = sl.playCount?.let { "▶ $it" }) {
                            scope.launch {
                                loadingDetail = true
                                val d = fetchOr(null) { service?.fetchDetail(sl) }
                                detail = sl to (d?.tracks ?: emptyList())
                                loadingDetail = false
                            }
                        }
                    }
                }
                else -> EmptyHint("目前沒有歌單", Modifier.fillMaxSize())
            }
        }

        // Detail overlay — visually replaces the grid but the grid remains in composition,
        // preserving scroll position, fetched lists, and focus.
        if (detail != null) {
            val (info, tracks) = detail!!
            com.walkman.tv.ui.components.TracksDetailOverlay(
                title = info.displayName,
                subtitle = info.displayAuthor.takeIf { it.isNotBlank() },
                tracks = tracks,
                onBack = { detail = null },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

