package com.walkman.tv.di

import android.content.Context
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.PlaybackSnapshotStore
import com.walkman.tv.data.store.ScriptStore
import com.walkman.tv.data.store.SearchHistoryStore
import com.walkman.tv.data.store.SettingsStore
import com.walkman.tv.playback.EqualizerManager
import com.walkman.tv.playback.LyricsFetcher
import com.walkman.tv.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.CatalogHttp
import com.walkman.tv.source.catalog.Catalogs
import com.walkman.tv.source.catalog.MvResolver
import com.walkman.tv.source.catalog.Songlists
import com.walkman.tv.source.js.ScriptHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container (no Hilt). Holds app-wide singletons; created once in [App].
 */
class AppContainer(val appContext: Context) {

    /** Process-wide event bus for hardware-key events (e.g. KEYCODE_MENU). */
    val events: AppEvents = AppEvents()

    /** In-app HTTP server backing the phone-to-TV QR flow. null when start failed. */
    @Volatile var localServer: LocalServer? = null
        private set

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val preload: String by lazy {
        appContext.assets.open("script/user-api-preload.js").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val catalogHttp by lazy { CatalogHttp(httpClient) }

    val catalogs by lazy { Catalogs(catalogHttp) }
    val hotSearch by lazy { com.walkman.tv.source.catalog.HotSearch(catalogHttp) }
    val boards by lazy { Boards(catalogHttp) }
    val songlists by lazy { Songlists(catalogHttp) }
    /** Backs the recommend (discover) right column — heroes / recommendations / boards. */
    val homeStore by lazy { com.walkman.tv.ui.recommend.HomeStore(songlists, boards) }
    val mvResolver by lazy { MvResolver(catalogHttp) }

    private val scriptHttp by lazy { ScriptHttpClient(httpClient) }
    private val builtInResolver by lazy { BuiltInResolver(httpClient) }
    private val builtInLyricResolver by lazy { BuiltInLyricResolver(catalogHttp) }
    private val otherSourceFinder by lazy { OtherSourceFinder(catalogs) }

    val sourceManager: SourceManager by lazy {
        SourceManager(preload, scriptHttp, otherSourceFinder, builtInResolver)
    }

    private val lyricsFetcher by lazy {
        LyricsFetcher(sourceManager, builtInLyricResolver, otherSourceFinder)
    }

    /** Created eagerly on the main thread in [App] (ExoPlayer needs a consistent looper). */
    lateinit var playbackController: PlaybackController
        private set

    val equalizerManager: EqualizerManager by lazy {
        EqualizerManager { playbackController.audioSessionId }
    }

    /** In-app updater — checks GitHub Releases, downloads the ABI-matched APK, launches install. */
    val updateManager: com.walkman.tv.playback.update.UpdateManager by lazy {
        com.walkman.tv.playback.update.UpdateManager(appContext, httpClient)
    }

    val scriptStore: ScriptStore by lazy { ScriptStore(appContext, sourceManager) }
    val libraryStore: LibraryStore by lazy { LibraryStore(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val playbackSnapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(appContext) }
    val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }
    val coverCache: com.walkman.tv.data.store.CoverCache by lazy {
        com.walkman.tv.data.store.CoverCache(appContext)
    }
    val downloadStore: com.walkman.tv.data.store.DownloadStore by lazy {
        com.walkman.tv.data.store.DownloadStore(appContext)
    }
    val localFolderStore: com.walkman.tv.data.store.LocalFolderStore by lazy {
        com.walkman.tv.data.store.LocalFolderStore(appContext)
    }
    val downloadCoordinator: com.walkman.tv.playback.download.DownloadCoordinator by lazy {
        com.walkman.tv.playback.download.DownloadCoordinator(
            store = downloadStore,
            sources = sourceManager,
            coverCache = coverCache,
            http = httpClient,
            lyricsFetcher = lyricsFetcher,
            catalogHttp = catalogHttp,
        )
    }
    val localMusicStore: com.walkman.tv.playback.local.LocalMusicStore by lazy {
        com.walkman.tv.playback.local.LocalMusicStore(
            context = appContext,
            localFolderStore = localFolderStore,
            coverCache = coverCache,
        )
    }

    /** Process-lived scope used for operations that mustn't be cancelled by a UI navigation
     *  (e.g. settings → script delete). Public so screens can opt into it explicitly. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initPlayback() {
        if (!::playbackController.isInitialized) {
            playbackController = PlaybackController(
                appContext,
                sourceManager,
                lyricsFetcher,
                com.walkman.tv.playback.AudioSpecProbe(httpClient),
            )
            playbackController.updateEnabledSources(settingsStore.settings.value.homeSources)
            playbackController.onTrackStarted = { track ->
                appScope.launch { libraryStore.recordHistory(track) }
            }
            // Local-first URL resolver — downloads + SAF imports skip the online cascade.
            // Doubles as the play-time file check: if a COMPLETED download's file is gone we
            // flag it missing (badge disappears, 已下载 tab shows 文件缺失) and fall through to
            // the online cascade; if it's there we clear any stale missing flag.
            playbackController.localUrlResolver = { track ->
                // Resolves both File downloads and SAF-exported downloads to a playable Uri.
                val playable = downloadStore.localPlayableUri(track.id)
                when {
                    playable != null -> {
                        downloadStore.markPresent(track.id)
                        playable
                    }
                    else -> {
                        if (downloadStore.isDownloaded(track.id)) downloadStore.markMissing(track.id)
                        localMusicStore.fileUri(track)?.toString()
                    }
                }
            }
        }
    }

    /** Blocking hot-search JSON for the phone page (/api/hotsearch).
     *  HotSearch owns the five-minute network cache; apply the current global source switches
     *  on every request so a setting change is reflected immediately without waiting for TTL.
     */
    @Synchronized
    private fun hotSearchJsonBlocking(): String = runCatching {
        kotlinx.coroutines.runBlocking {
            val enabledSources = settingsStore.settings.value.homeSources
            val cols = hotSearch.fetchAll().filter { it.source in enabledSources }
            buildString {
                append('[')
                cols.forEachIndexed { i, col ->
                    if (i > 0) append(',')
                    append('{')
                    append('"').append("source").append('"').append(':').append('"').append(jsonEscape(col.source.key)).append('"')
                    append(',').append('"').append("name").append('"').append(':').append('"').append(jsonEscape(col.source.displayName)).append('"')
                    append(',').append('"').append("words").append('"').append(':').append('[')
                    col.words.forEachIndexed { j, word ->
                        if (j > 0) append(',')
                        append('"').append(jsonEscape(word)).append('"')
                    }
                    append("]}")
                }
                append(']')
            }
        }
    }.getOrDefault("[]")

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** Fetch a remote text resource (used to import a custom-source script from a URL). */
    suspend fun fetchText(url: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        okhttp3.Request.Builder().url(url).build().let { req ->
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("空响应")
            }
        }
    }

    /** Load persisted data and wire settings → playback. Called once at startup. */
    fun bootstrap() {
        // Bring up the LAN HTTP server (best-effort; QR features just won't work if it fails).
        if (localServer == null) {
            localServer = LocalServer.start(events, hotSearchJson = { hotSearchJsonBlocking() })
        }
        appScope.launch {
            settingsStore.loadAll()

            // Apply the loaded settings synchronously before starting dependent store reads.
            // In particular, DownloadStore.loadAll() must see the user's configured root/tree
            // URI; relying only on launchIn(StateFlow) can race the parallel startup jobs below.
            applySettings(settingsStore.settings.value)
            settingsStore.settings.onEach { settings ->
                applySettings(settings)
                if (::playbackController.isInitialized) {
                    playbackController.updateEnabledSources(settings.homeSources)
                }
            }.launchIn(appScope)

            // These stores are independent at startup. Load them concurrently instead of
            // serially waiting for each disk read; custom scripts still finish before snapshot
            // restoration because the saved queue may need those sources to resolve.
            coroutineScope {
                val scripts = async { scriptStore.loadAll(installBundled = false) }
                val library = async { libraryStore.loadAll() }
                val history = async { searchHistoryStore.load() }
                val covers = async { coverCache.loadAll() }
                val downloads = async { downloadStore.loadAll() }
                val localFolders = async { localFolderStore.loadAll() }

                scripts.await()
                library.await()
                history.await()
                covers.await()
                downloads.await()
                localFolders.await()
            }

            // Restore last session's queue + index + playing state. Run after scripts load so
            // any custom-source needed to resolve URLs is ready.
            val snapshot = playbackSnapshotStore.load()
            playbackController.restoreSnapshot(snapshot)

            // The bundled third-party source is optional for restoring the previous session.
            // Initialize it in the background so its network startup timeout cannot delay first
            // interaction or playback restoration.
            appScope.launch { scriptStore.installBundledIfMissing() }

            // Auto-save the snapshot whenever queue/index/isPlaying changes. distinctUntilChanged
            // already coalesces the high-frequency position ticks since we only project the three
            // fields that matter here.
            playbackController.state
                .map { Triple(it.queue.map { t -> t.id }, it.index, it.isPlaying) }
                .distinctUntilChanged()
                .onEach { playbackSnapshotStore.save(playbackController.snapshot()) }
                .launchIn(appScope)
        }
    }

    private fun applySettings(s: com.walkman.tv.data.store.Settings) {
        playbackController.preferredQuality = s.preferredQuality
        // 硬件直通会把未解码的数据直接交给音频硬件，绕过解码器；强制软解时一并关掉。
        playbackController.setAudioOffloadEnabled(s.audioOffloadEnabled && !s.forceSoftwareDecoding)
        playbackController.forceSoftwareDecoding = s.forceSoftwareDecoding
        sourceManager.fallbackEnabled = s.fallbackEnabled
        downloadStore.configuredRoot = s.customDownloadDir?.let { java.io.File(it) }
        downloadStore.downloadTreeUri = s.customDownloadTreeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        downloadCoordinator.setMaxConcurrent(s.maxConcurrentDownloads)
    }

    /** Synchronous final save — called right before the process is killed on user-confirmed exit. */
    fun saveSnapshotNow() {
        if (::playbackController.isInitialized) {
            runCatching { playbackSnapshotStore.saveBlocking(playbackController.snapshot()) }
        }
    }
}
