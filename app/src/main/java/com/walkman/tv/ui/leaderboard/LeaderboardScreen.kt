package com.walkman.tv.ui.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.util.ChineseText

private val boardSources = listOf(SourceID.KW, SourceID.WY, SourceID.KG, SourceID.TX)

// Reuse previously loaded chart tracks immediately when users revisit a leaderboard.
private val leaderboardTrackCache = LinkedHashMap<String, List<Track>>()

@Composable
fun LeaderboardScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    var source by remember { mutableStateOf(SourceID.KW) }
    var boards by remember { mutableStateOf<List<BoardInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<BoardInfo?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loadingTracks by remember { mutableStateOf(false) }

    // Preload the first chart from other providers concurrently while the current chart is shown.
    // This avoids waiting for the first network round-trip when users switch leaderboard sources.
    LaunchedEffect(Unit) {
        coroutineScope {
            boardSources.filter { it != source }.map { sourceId ->
                async {
                    val svc = appContainer.boards.serviceFor(sourceId) ?: return@async
                    val firstBoard = svc.list.firstOrNull() ?: return@async
                    val key = firstBoard.source.key + ":" + firstBoard.bangid
                    if (leaderboardTrackCache[key].isNullOrEmpty()) {
                        val result = fetchOr(emptyList()) { svc.fetchTracks(firstBoard.bangid, 1) }
                        if (result.isNotEmpty()) leaderboardTrackCache[key] = result
                    }
                }
            }.awaitAll()
        }
    }

    // Show the built-in board catalogue immediately; refresh remote names/covers in parallel.
    // The old flow waited for fetchBoards() before it even started fetching the first board's songs.
    LaunchedEffect(source) {
        val service = appContainer.boards.serviceFor(source)
        val initial = service?.list.orEmpty()
        boards = initial
        selected = initial.firstOrNull()
    }
    LaunchedEffect(source) {
        val refreshed = fetchOr(emptyList()) {
            appContainer.boards.serviceFor(source)?.fetchBoards() ?: emptyList()
        }
        if (refreshed.isNotEmpty()) boards = refreshed
    }
    // Key by source + bangid, not BoardInfo equality: refreshing display metadata must not
    // trigger a second identical song request.
    LaunchedEffect(selected?.source, selected?.bangid) {
        val board = selected ?: return@LaunchedEffect
        val cacheKey = board.source.key + ":" + board.bangid
        val cached = leaderboardTrackCache[cacheKey].orEmpty()
        tracks = cached
        loadingTracks = cached.isEmpty()
        val fresh = fetchOr(cached) {
            appContainer.boards.serviceFor(board.source)?.fetchTracks(board.bangid, 1) ?: emptyList()
        }
        if (fresh.isNotEmpty()) {
            leaderboardTrackCache[cacheKey] = fresh
            while (leaderboardTrackCache.size > 24) leaderboardTrackCache.remove(leaderboardTrackCache.keys.first())
            tracks = fresh
        }
        loadingTracks = false
    }

    Row(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        Column(modifier = Modifier.width(150.dp).fillMaxHeight()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                boardSources.chunked(2).forEach { rowChips ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChips.forEach { s ->
                            TvPill(
                                onClick = { if (source != s) source = s },
                                selected = source == s,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) { Text(ChineseText.toTaiwanTraditional(s.displayName), fontSize = 11.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            val uniqueBoards = remember(boards) { boards.distinctBy { it.id } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(uniqueBoards, key = { it.id }) { b ->
                    TvFocusable(onClick = { selected = b }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            ChineseText.toTaiwanTraditional(b.displayName),
                            color = if (selected?.id == b.id) AppColors.AccentGreen else AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(ChineseText.toTaiwanTraditional(selected?.displayName ?: "排行榜"), color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            when {
                loadingTracks -> LoadingState(Modifier.fillMaxSize())
                tracks.isNotEmpty() -> {
                    val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                    TrackList(tracks, modifier = Modifier.fillMaxWidth().weight(1f), nowPlayingId = nowId) { idx ->
                        playList(tracks, idx); onOpenPlayer()
                    }
                }
                else -> EmptyHint("目前沒有內容", Modifier.fillMaxSize())
            }
        }
    }
}
