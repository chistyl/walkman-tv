package com.walkman.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import android.icu.text.Transliterator
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.MediaCard
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.util.ChineseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val settings by appContainer.settingsStore.settings.collectAsState()
    val enabledSources = settings.homeSources
    var queryValue by remember { mutableStateOf(TextFieldValue("")) }
    var searchFocused by remember { mutableStateOf(false) }
    val simplifier = remember { Transliterator.getInstance("Traditional-Simplified") }
    var filter by remember { mutableStateOf<SourceID?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var songlists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var hotColumns by remember { mutableStateOf<List<com.walkman.tv.source.catalog.HotSearchColumn>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Load hot-search words once; disabled sources are filtered from the visible columns.
    LaunchedEffect(Unit) {
        hotColumns = runCatching { appContainer.hotSearch.fetchAll() }.getOrDefault(emptyList())
    }

    fun runSearch(searchText: String = queryValue.text) {
        val currentQuery = searchText
        if (currentQuery.isBlank()) return
        // Cancel an older keyword/filter request so late responses cannot replace newer results.
        searchJob?.cancel()
        searchJob = scope.launch {
            loading = true
            searched = true
            tracks = emptyList()
            songlists = emptyList()
            val f = filter
            // Keep the entered Traditional Chinese visible and in history; normalize only
            // the provider query to Simplified Chinese for broader source-side matching.
            val q = simplifier.transliterate(currentQuery)
            // Record into history before kicking off the request so it shows up on the next
            // empty-query render even if the result list ends up empty.
            appContainer.searchHistoryStore.record(currentQuery)
            // Render each section as soon as its own providers finish instead of waiting for
            // the slower of the song and songlist requests.
            coroutineScope {
                launch {
                    val result = try {
                        if (f == null) appContainer.catalogs.searchAll(q, enabledSources = enabledSources)
                        else if (f in enabledSources) appContainer.catalogs.search(f, q) else emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    tracks = result
                }
                launch {
                    val result = try {
                        if (f == null) appContainer.songlists.searchAll(q, enabledSources = enabledSources)
                        else if (f in enabledSources) appContainer.songlists.search(f, q, 1) else emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songlists = result
                }
            }
            loading = false
        }
    }

    // Re-run search when the platform filter changes (only after the first manual search).
    LaunchedEffect(filter, enabledSources) {
        if (filter != null && filter !in enabledSources) filter = null
        if (searched) runSearch()
    }

    // Phone-to-TV: incoming search keywords from the QR-served form.
    LaunchedEffect(Unit) {
        appContainer.events.qrSearchKeyword.collect { k ->
            queryValue = TextFieldValue(k)
            showQr = false
            runSearch(k)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        // Search controls: source filters on the left, keyword field and QR action on the right.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlatformChipRow(filter, enabledSources, onSelect = { filter = it })
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = queryValue,
                onValueChange = { entered ->
                    // Preserve Traditional Chinese while composing and displaying the query.
                    queryValue = entered
                },
                modifier = Modifier
                    .width(450.dp)
                    // Let the TV remote leave the text field with directional keys.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val direction = when (event.key) {
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionRight -> FocusDirection.Right
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                else -> null
                            }
                            direction?.let { focusManager.moveFocus(it) } ?: false
                        } else false
                    }
                    .onFocusChanged { searchFocused = it.isFocused }
                    .border(
                        width = if (searchFocused) 2.dp else 1.dp,
                        color = if (searchFocused) AppColors.AccentGreen else AppColors.Card,
                        shape = RoundedCornerShape(10.dp),
                    ),
                singleLine = true,
                placeholder = { Text("輸入關鍵字", color = Color(0xFF858B98)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedBorderColor = AppColors.AccentGreen,
                    unfocusedBorderColor = AppColors.Card,
                    cursorColor = AppColors.AccentGreen,
                    focusedPlaceholderColor = Color(0xFF858B98),
                    unfocusedPlaceholderColor = Color(0xFF858B98),
                    focusedContainerColor = AppColors.BgPanel,
                    unfocusedContainerColor = AppColors.BgPanel,
                ),
            )
            TvPill(
                onClick = { showQr = true },
                shape = androidx.compose.foundation.shape.CircleShape,
                contentPadding = PaddingValues(10.dp),
            ) {
                Icon(Icons.Filled.QrCode2, contentDescription = "掃碼搜尋", modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        ResultsPane(
            modifier = Modifier.fillMaxSize(),
            loading = loading,
            searched = searched,
            songlists = songlists,
            tracks = tracks,
            hotColumns = hotColumns.filter { it.source in enabledSources },
            onOpenPlayer = onOpenPlayer,
            onPickHistory = { kw ->
                queryValue = TextFieldValue(kw)
                runSearch(kw)
            },
            onPickHot = { kw ->
                queryValue = TextFieldValue(kw)
                runSearch(kw)
            },
            onPickSonglist = { info ->
                scope.launch {
                    val svc = appContainer.songlists.serviceFor(info.source) ?: return@launch
                    val detail = runCatching { svc.fetchDetail(info) }.getOrNull()
                    val ts = detail?.tracks.orEmpty()
                    if (ts.isNotEmpty()) {
                        playList(ts, 0); onOpenPlayer()
                    }
                }
            },
        )
    }

    if (showQr) {
        val ip = remember { getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/search",
                title = "掃碼搜尋",
                subtitle = "手機掃碼或直接在電腦開啟網址皆可；輸入關鍵字後會自動返回電視搜尋頁",
                onDismiss = { showQr = false },
            )
        } else {
            QrUnavailableDialog(onDismiss = { showQr = false })
        }
    }
}

@Composable
private fun QrUnavailableDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("無法啟動掃碼") },
        text = { Text("請確認電視已連線至 Wi-Fi，且本機服務已啟動後再試。") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("好", color = AppColors.AccentGreen)
            }
        },
        containerColor = AppColors.BgPanel,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

// MARK: - Right panel --------------------------------------------------------------------------

// Migu intentionally absent — see SearchCatalog.Catalogs.services for the reason. Boards /
// songlists still surface it; only the search path is opted out.
private val PLATFORMS_LABELS: List<Pair<SourceID?, String>> = listOf(
    null to "全部",
    SourceID.KW to "酷我",
    SourceID.KG to "酷狗",
    SourceID.TX to "QQ 音樂",
    SourceID.WY to "網易雲音樂",
)

@Composable
private fun PlatformChipRow(filter: SourceID?, enabledSources: Set<SourceID>, onSelect: (SourceID?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PLATFORMS_LABELS.filter { (src, _) -> src == null || src in enabledSources }.forEach { (src, label) ->
            TvPill(
                onClick = { onSelect(src) },
                selected = filter == src,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(label, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ResultsPane(
    modifier: Modifier = Modifier,
    loading: Boolean,
    searched: Boolean,
    songlists: List<SonglistInfo>,
    tracks: List<Track>,
    hotColumns: List<com.walkman.tv.source.catalog.HotSearchColumn>,
    onOpenPlayer: () -> Unit,
    onPickSonglist: (SonglistInfo) -> Unit,
    onPickHistory: (String) -> Unit = {},
    onPickHot: (String) -> Unit = {},
) {
    val history by appContainer.searchHistoryStore.items.collectAsState()
    when {
        loading && songlists.isEmpty() && tracks.isEmpty() -> LoadingState(Modifier.fillMaxSize())
        !searched -> {
            // Before searching: recent-search chips (if any) on top, then the 4-column hot lists.
            Column(modifier = modifier.fillMaxSize().padding(top = 4.dp)) {
                if (history.isNotEmpty()) {
                    SearchHistoryPane(history = history, onPick = onPickHistory)
                    Spacer(Modifier.padding(top = 10.dp))
                }
                if (hotColumns.any { it.words.isNotEmpty() }) {
                    HotSearchGrid(columns = hotColumns, onPick = onPickHot)
                } else if (history.isEmpty()) {
                    EmptyHint("輸入關鍵字開始搜尋", Modifier.fillMaxSize())
                }
            }
        }
        songlists.isEmpty() && tracks.isEmpty() ->
            EmptyHint("找不到搜尋結果", Modifier.fillMaxSize())
        else -> Column(modifier = modifier.fillMaxSize()) {
            if (songlists.isNotEmpty()) {
                SectionHeader("歌單 (${songlists.size})")
                val uniqueSonglists = remember(songlists) { songlists.distinctBy { it.id + "_" + it.source.key } }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(uniqueSonglists, key = { it.id + "_" + it.source.key }) { sl ->
                        Box(modifier = Modifier.width(140.dp)) {
                            MediaCard(
                                title = sl.name,
                                picURL = sl.picURL,
                                subtitle = sl.playCount?.let { "▶ $it" },
                                onClick = { onPickSonglist(sl) },
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
            if (tracks.isNotEmpty()) {
                SectionHeader("歌曲 (${tracks.size})")
                val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                TrackList(
                    tracks = tracks,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    nowPlayingId = nowId,
                    onPlay = { idx -> playList(tracks, idx); onOpenPlayer() },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = AppColors.AccentGreen,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Brand tint per platform — matches the songlist/board source chips. */
private fun sourceTint(source: SourceID): androidx.compose.ui.graphics.Color = when (source) {
    SourceID.KW -> AppColors.SourceKw
    SourceID.KG -> AppColors.SourceKg
    SourceID.TX -> AppColors.SourceTx
    SourceID.WY -> AppColors.SourceWy
    else -> AppColors.TextSecondary
}

/**
 * Hot-search: four platform columns side by side, each a rank-numbered, focusable list. Picking a
 * word fills the query and searches immediately. Each column scrolls independently so a long list
 * (e.g. Kuwo's 20) doesn't force the others tall.
 */
@Composable
private fun HotSearchGrid(
    columns: List<com.walkman.tv.source.catalog.HotSearchColumn>,
    onPick: (String) -> Unit,
) {
    val orderedSources = listOf(SourceID.KW, SourceID.WY, SourceID.KG, SourceID.TX)
    // Only enabled platforms are rendered; temporarily empty providers remain represented.
    val visibleColumns = orderedSources.mapNotNull { source -> columns.firstOrNull { it.source == source } }
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        SectionHeader("熱門搜尋")
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            visibleColumns.forEach { col ->
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        ChineseText.toTaiwanTraditional(col.source.displayName),
                        color = sourceTint(col.source),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                    )
                    if (col.words.isEmpty()) {
                        Text(
                            "暫無熱門搜尋資料",
                            color = AppColors.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    } else LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Keep room around scaled TV focus surfaces so their green border is
                        // not clipped by LazyColumn's viewport at the first/last rows or edges.
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        itemsIndexed(col.words) { idx, word ->
                            com.walkman.tv.ui.components.TvFocusable(
                                onClick = { onPick(word) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        color = if (idx < 3) sourceTint(col.source) else AppColors.TextMuted,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    Text(
                                        ChineseText.toTaiwanTraditional(word),
                                        color = AppColors.TextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recent-search chips. Rendered when the user hasn't searched yet but the store has entries.
 *  Wraps onto multiple lines via FlowRow so we don't horizontally clip long histories. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryPane(history: List<String>, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // fillMaxWidth (not fillMaxSize): this pane now stacks above the hot-search grid, so it must
    // wrap its content height instead of eating the whole column.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近搜尋",
                color = AppColors.AccentGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TvPill(
                onClick = { scope.launch { appContainer.searchHistoryStore.clear() } },
                accent = AppColors.Danger,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("清除", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            history.forEach { kw ->
                TvPill(
                    onClick = { onPick(kw) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(ChineseText.toTaiwanTraditional(kw), fontSize = 13.sp)
                }
            }
        }
    }
}
