package com.walkman.tv.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.walkman.tv.data.store.PlaybackSnapshot
import com.walkman.tv.diagnostics.AppDiagnostics
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.ResolveOrigin
import com.walkman.tv.source.ResolvedTrack
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.js.ScriptHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val resolving: Boolean = false,
    val warning: String? = null,
    val originLabel: String? = null,
    val quality: Quality? = null,
    val error: String? = null,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val shuffle: Boolean = false,
    val isMv: Boolean = false,
) {
    val currentTrack: Track? get() = queue.getOrNull(index)
}

/**
 * Single source of truth for playback (iOS `PlaybackEngine`). Owns one ExoPlayer; resolves each
 * track's URL lazily via [SourceManager] on switch (URLs expire), records history, loads lyrics,
 * and reuses the same player for MV video.
 */
class PlaybackController(
    context: Context,
    private val sources: SourceManager,
    private val lyricsFetcher: LyricsFetcher,
    private val audioSpecProbe: AudioSpecProbe? = null,
) {
    /**
     * Real-time audio level (0..1), smoothed by the audio thread inside [AudioLevelProcessor].
     * Drives the reactive waveform in the player + recommend NowPlayingPanel. Stays at 0 when
     * the player is paused or when ExoPlayer chooses audio offload (DSP path bypasses the
     * processor chain — the consuming Waveform then falls back to its synthetic animation).
     */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** Last quality the cascade settled on for the current track — used by [onPlayerError]
     *  to decide which tier to step down from on format-reject errors. */
    private var lastAttemptedQuality: Quality? = null

    /** Number of consecutive network-error retries on the current track. Reset to 0 on a
     *  successful play, on a fresh playAt(), or once we've given up and skipped to next. */
    private var networkRetryCount: Int = 0

    /** Number of consecutive *tracks* that have failed back-to-back (resolve failed or an
     *  unrecoverable player error). Auto-skip to next on each failure; stop only once this hits
     *  [MAX_CONSECUTIVE_FAILURES]. Reset to 0 when any track actually reaches READY, and on a
     *  fresh user-initiated queue. Distinct from [networkRetryCount], which retries one track. */
    private var consecutiveFailures: Int = 0

    /**
     * Optional local-first URL resolver. When set, [playAt] checks it before going to
     * [SourceManager]. Wired by [com.walkman.tv.di.AppContainer] to point at
     * DownloadStore.localFile + LocalMusicStore.fileUri so already-downloaded songs and
     * SAF-imported tracks skip the online resolve cascade entirely.
     *
     * Returns a uri string (`file://...`, `content://...`) or null if the track isn't local.
     */
    var localUrlResolver: ((Track) -> String?)? = null

    /**
     * Measured audio spec for the currently-playing URL (FLAC/MP3 codec params from the
     * file header). Null while probing or when the format isn't recognised. Drives the
     * badge ceiling-clamp in spec §6 and the small "FLAC 24bit/192kHz" caption next to it.
     */
    private val _audioSpec = MutableStateFlow<AudioSpec?>(null)
    val audioSpec: StateFlow<AudioSpec?> = _audioSpec.asStateFlow()

    /** Url currently being probed — used to discard late results when the user has switched
     *  tracks before the probe returned. */
    @Volatile private var probingUrl: String? = null
    private var probeJob: Job? = null

    private val audioLevelProcessor = AudioLevelProcessor { level ->
        _audioLevel.value = level
    }

    /** 强制软件解码：只用系统自带的软件解码器（c2.android.* / OMX.google.*），绕开部分电视、盒子
     *  厂商硬件解码器的闪退或无声问题。解码器在每次准备新歌时挑选，所以切换后从下一首开始生效。 */
    @Volatile var forceSoftwareDecoding: Boolean = false

    val player: ExoPlayer = buildPlayer(context.applicationContext, audioLevelProcessor, { forceSoftwareDecoding })

    private val appContext = context.applicationContext

    private companion object {
        /** ExoPlayer error codes that indicate the URL was fetched fine but the bytes can't be
         *  decoded — i.e. a format-level rejection. Spec §3 calls for one-tier cascade here. */
        val FORMAT_REJECT_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        /** ExoPlayer error codes that point at transient network issues — connection drops,
         *  flaky CDN, expired URL on a paused track, etc. Worth retrying a few times before
         *  giving up on the track. */
        val NETWORK_RETRY_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        const val MAX_NETWORK_RETRIES = 3

        /** Stop auto-skipping once this many tracks fail back-to-back (a whole playlist of dead
         *  links shouldn't spin forever). */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * Build ExoPlayer using Media3's standard audio sink. Avoid custom PCM processor,
         * float output and AudioTrack playback-parameter paths because several Android TV
         * vendor audio HAL implementations are unstable with those optional paths.
         * Audio offload remains disabled for consistent PCM output on Android 9+.
         */
        fun buildPlayer(
            appContext: android.content.Context,
            levelProcessor: AudioLevelProcessor,
            softwareOnly: () -> Boolean,
        ): ExoPlayer {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // Use Media3's stock audio sink and PCM conversion path. Keep decoder fallback
            // enabled so a device-specific hardware decoder rejection can fall back safely.
            val renderersFactory = DefaultRenderersFactory(appContext)
                // LE Music's APK does not package libffmpegJNI.so; use Media3's built-in platform renderer path.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector { mimeType, secure, tunneling ->
                    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    if (!softwareOnly() || !androidx.media3.common.MimeTypes.isAudio(mimeType)) all
                    else all.filter { it.softwareOnly }.ifEmpty { all }
                }
                .setEnableAudioFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            val player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                // Keep the CPU awake while audio is active, including with the TV display off.
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                        )
                        .build(),
                )
                .build()
            return player
        }
    }
    @Volatile private var serviceStarted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    /** Set by the app to record played tracks into history. */
    var onTrackStarted: ((Track) -> Unit)? = null
    /** Preferred play quality, fed from settings. */
    var preferredQuality: Quality = Quality.K320

    private var resolveJob: Job? = null
    private var lyricsJob: Job? = null
    private var retryJob: Job? = null
    private var autoSkipJob: Job? = null
    private var tickerJob: Job? = null
    @Volatile private var activeMediaId: String? = null

    /** Invalidates late resolver results after a newer track/source selection. */
    @Volatile private var resolveGeneration: Long = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AppDiagnostics.record("PLAYER_STATE", "isPlaying=$isPlaying; state=${player.playbackState}; source=${_state.value.currentTrack?.source?.key}")
                _state.value = _state.value.copy(isPlaying = isPlaying)
                // Pause: drop the audio level immediately so the waveform settles instead
                // of holding whatever amplitude the last decoded chunk reported.
                if (!isPlaying) _audioLevel.value = 0f
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
                AppDiagnostics.record("PLAYER_STATE", "playbackState=$stateName; isPlaying=${player.isPlaying}; playWhenReady=${player.playWhenReady}; source=${_state.value.currentTrack?.source?.key}")
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.isMv) exitMv() else handleEnded()
                }
                if (playbackState == Player.STATE_READY) {
                    // A track actually started — clear the consecutive-failure streak.
                    consecutiveFailures = 0
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val eventMediaId = player.currentMediaItem?.mediaId
                if (eventMediaId.isNullOrBlank() || eventMediaId != activeMediaId) {
                    AppDiagnostics.record("PLAYER_ERROR_STALE", "code=${error.errorCode} ${error.errorCodeName}; eventMediaId=${eventMediaId ?: "none"}; activeMediaId=${activeMediaId ?: "none"}", error)
                    return
                }
                val errorGeneration = resolveGeneration
                if (_state.value.isMv) {
                    AppDiagnostics.record("MV_PLAYER_ERROR", "generation=$errorGeneration; code=${error.errorCode} ${error.errorCodeName}", error)
                    _state.value = _state.value.copy(error = "MV 播放失敗", resolving = false, isPlaying = false)
                    return
                }
                AppDiagnostics.record("PLAYER_ERROR", "generation=$errorGeneration; mediaId=$eventMediaId; code=${error.errorCode} ${error.errorCodeName}; source=${_state.value.currentTrack?.source?.key}; quality=${lastAttemptedQuality?.key}", error)
                // Format/codec rejections are the "encrypted .mgg" / "actually MP3 not FLAC" case
                // from spec §3 — re-cascade from one tier below the current attempt. Network /
                // unauthorized errors get surfaced as-is.
                val isFormatReject = error.errorCode in FORMAT_REJECT_CODES
                val current = lastAttemptedQuality
                val track = _state.value.currentTrack
                if (isFormatReject && track != null && current != null && current != Quality.K128) {
                    val full = Quality.orderedHighToLow
                    val idx = full.indexOf(current)
                    val below = if (idx >= 0 && idx + 1 < full.size) full[idx + 1] else Quality.K128
                    android.util.Log.w(
                        "PlaybackController",
                        "format reject @ ${current.key} (${error.errorCodeName}); re-cascading from ${below.key}",
                    )
                    _state.value = _state.value.copy(
                        warning = "${current.displayName} 格式不支援，已降低音質至 ${below.displayName}",
                    )
                    networkRetryCount = 0 // format error path — separate from the network counter
                    if (errorGeneration == resolveGeneration) playAt(_state.value.index, qualityCap = below)
                    return
                }
                // Transient network — exponential backoff retry up to MAX_NETWORK_RETRIES.
                // After that, advance to next so playback doesn't dead-stop the queue.
                if (error.errorCode in NETWORK_RETRY_CODES && track != null) {
                    if (networkRetryCount < MAX_NETWORK_RETRIES) {
                        networkRetryCount++
                        val delayMs = 500L shl (networkRetryCount - 1) // 500ms / 1s / 2s
                        android.util.Log.w(
                            "PlaybackController",
                            "network error (${error.errorCodeName}); retry ${networkRetryCount}/$MAX_NETWORK_RETRIES in ${delayMs}ms",
                        )
                        _state.value = _state.value.copy(
                            warning = "網路不穩定，正在重試 ($networkRetryCount/$MAX_NETWORK_RETRIES)…",
                            resolving = true,
                            error = null,
                        )
                        val trackIdAtRetry = track.id
                        val mediaIdAtRetry = eventMediaId
                        retryJob?.cancel()
                        retryJob = scope.launch {
                            kotlinx.coroutines.delay(delayMs)
                            // Track ID alone is insufficient: the same song can be selected again.
                            if (errorGeneration == resolveGeneration &&
                                _state.value.currentTrack?.id == trackIdAtRetry &&
                                player.currentMediaItem?.mediaId == mediaIdAtRetry &&
                                activeMediaId == mediaIdAtRetry
                            ) {
                                playAt(_state.value.index, qualityCap = lastAttemptedQuality)
                            }
                        }
                        return
                    }
                    // Retries exhausted — this track has failed; auto-skip (counts toward the
                    // consecutive-failure limit).
                    android.util.Log.w(
                        "PlaybackController",
                        "network retries exhausted on ${track.name}; skipping to next",
                    )
                    failAndAdvance("網路不穩定", errorGeneration)
                    return
                }
                // Any other error — this track failed; auto-skip (or stop after too many in a row).
                failAndAdvance(error.errorCodeName, errorGeneration)
            }
        })
        startPositionTicker()
    }

    // MARK: - queue control

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (tracks.isEmpty()) return
        if (!autoPlay) {
            resolveGeneration++
            activeMediaId = null
            retryJob?.cancel()
            autoSkipJob?.cancel()
            resolveJob?.cancel()
        }
        consecutiveFailures = 0 // fresh user-initiated queue — start the failure streak over
        _state.value = _state.value.copy(queue = tracks, index = startIndex.coerceIn(0, tracks.size - 1))
        if (autoPlay) playAt(_state.value.index)
    }

    /** Capture the parts of state we persist across launches. */
    fun snapshot(): PlaybackSnapshot {
        val s = _state.value
        return PlaybackSnapshot(
            queue = s.queue,
            currentIndex = s.index,
            wasPlaying = s.isPlaying,
            repeatModeOrdinal = s.repeatMode.ordinal,
            shuffle = s.shuffle,
        )
    }

    /** Restore queue + index from a snapshot. Resumes playback if the previous session was
     *  playing; URLs are resolved fresh (they expire). Called once from AppContainer.bootstrap. */
    fun restoreSnapshot(s: PlaybackSnapshot) {
        val mode = RepeatMode.values().getOrNull(s.repeatModeOrdinal) ?: RepeatMode.ALL
        _state.value = _state.value.copy(repeatMode = mode, shuffle = s.shuffle)
        if (s.queue.isEmpty() || s.currentIndex !in s.queue.indices) return
        // Stage the queue without auto-play first, so the UI shows the track immediately.
        _state.value = _state.value.copy(queue = s.queue, index = s.currentIndex, isPlaying = false)
        if (s.wasPlaying) playAt(s.currentIndex)
    }

    fun playTrack(track: Track) = setQueue(listOf(track), 0, true)

    fun playAt(index: Int, qualityCap: Quality? = null) {
        val track = _state.value.queue.getOrNull(index) ?: return
        val generation = ++resolveGeneration
        probeJob?.cancel()
        probeJob = null
        probingUrl = null
        _audioSpec.value = null
        retryJob?.cancel()
        retryJob = null
        autoSkipJob?.cancel()
        autoSkipJob = null
        activeMediaId = "$generation:${track.id}"
        AppDiagnostics.record("PLAY_REQUEST", "generation=$generation; source=${track.source.key}; index=$index; queueSize=${_state.value.queue.size}; qualityCap=${qualityCap?.key ?: "none"}")
        // Fresh attempt (no cap) clears the last-attempted memory + the network retry counter;
        // cascade retries (cap != null) keep them so [onPlayerError] can keep stepping down or
        // continue counting retries within the same logical attempt.
        if (qualityCap == null) {
            lastAttemptedQuality = null
            networkRetryCount = 0
            // Silence the previous song right away. Resolving can take a long time with a slow or
            // dead source (every quality tier × 15s script timeout, then other-platform lookups),
            // and the old track playing on under the new title looked like "切換歌曲未成功".
            runCatching { player.stop() }
            _state.value = _state.value.copy(positionMs = 0)
        }
        val effectivePreferred = qualityCap ?: preferredQuality
        _state.value = _state.value.copy(index = index, resolving = true, error = null, warning = null, isMv = false)
        // Switch lyrics immediately with the metadata — lyrics depend only on the track, not the
        // resolved play URL, so don't make them wait for (or hinge on) a successful audio resolve.
        // Guarded to fresh attempts so a quality-cascade retry of the same track doesn't refetch.
        if (qualityCap == null) loadLyrics(track)
        if (resolveJob?.isActive == true) {
            AppDiagnostics.record("RESOLVE_CANCEL", "generation=${generation - 1}; replacedBy=$generation")
        }
        resolveJob?.cancel()
        resolveJob = scope.launch {
            val resolveStartedAt = android.os.SystemClock.elapsedRealtime()
            AppDiagnostics.record("RESOLVE_BEGIN", "generation=$generation; source=${track.source.key}; quality=${effectivePreferred.key}")
            // Local fast-path: downloaded tracks + SAF-imported tracks skip the online cascade.
            // localUrlResolver is set up at AppContainer init time.
            val localUrl = runCatching { localUrlResolver?.invoke(track) }.getOrNull()
            val resolved = if (localUrl != null) {
                Result.success(
                    ResolvedTrack(
                        url = localUrl,
                        origin = ResolveOrigin.LocalFile,
                        quality = track.qualities.firstOrNull() ?: effectivePreferred,
                        warning = null,
                    ),
                )
            } else {
                try {
                    Result.success(sources.resolveMusicURL(track, effectivePreferred))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            if (generation != resolveGeneration || _state.value.currentTrack?.id != track.id) {
                AppDiagnostics.record("RESOLVE_STALE", "generation=$generation; currentGeneration=$resolveGeneration; source=${track.source.key}")
                return@launch
            }
            AppDiagnostics.record("RESOLVE_FINISH", "generation=$generation; source=${track.source.key}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - resolveStartedAt}; success=${resolved.isSuccess}")
            resolved.exceptionOrNull()?.let {
                AppDiagnostics.record("RESOLVE_ERROR", "source=${track.source.key}; quality=${effectivePreferred.key}", it)
            }
            runCatching { playResolvedOrFail(track, resolved, generation) }
                .onFailure { e ->
                    android.util.Log.e("PlaybackController", "playAt failed", e)
                    if (generation == resolveGeneration && _state.value.currentTrack?.id == track.id) {
                        failAndAdvance(e.message ?: e::class.simpleName ?: "播放時發生錯誤", generation)
                    }
                }
        }
    }

    private fun playResolvedOrFail(track: Track, resolved: Result<ResolvedTrack>, generation: Long) {
        if (generation != resolveGeneration || _state.value.currentTrack?.id != track.id) return
        resolved.onSuccess { r ->
                val mediaId = "$generation:${track.id}"
                activeMediaId = mediaId
                val item = MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(r.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.displayName)
                            .setArtist(track.displaySinger)
                            .setAlbumTitle(track.displayAlbumName)
                            .apply { track.picURL?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                            .build(),
                    )
                    .build()
                // Remember the tier so onPlayerError can re-cascade from one tier below if
                // ExoPlayer rejects the file format.
                lastAttemptedQuality = r.quality
                val host = runCatching { android.net.Uri.parse(r.url).host }.getOrNull() ?: "(local-or-unknown)"
                AppDiagnostics.record("PLAY_RESOLVED", "source=${track.source.key}; quality=${r.quality.key}; host=$host; headerNames=${r.headers.keys.joinToString(",")}; customUA=${!r.userAgent.isNullOrBlank()}")
                // Successful resolve + setMediaItem → reset the network retry counter so the
                // next track gets a fresh budget.
                networkRetryCount = 0
                if (r.headers.isEmpty() && r.userAgent.isNullOrBlank()) {
                    // Preserve the existing default pipeline for ordinary sources.
                    player.setMediaItem(item)
                } else {
                    // Source-specific request properties belong to this track only. Do not
                    // mutate the shared ExoPlayer data-source defaults.
                    val requestHeaders = LinkedHashMap(r.headers)
                    val headerUserAgent = requestHeaders.entries
                        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                        ?.value
                    val userAgent = r.userAgent?.takeIf { it.isNotBlank() }
                        ?: headerUserAgent
                        ?: ScriptHttpClient.DEFAULT_UA
                    // Normalize to one authoritative User-Agent. A script-level userAgent
                    // must not conflict with a differently-cased value inside headers.
                    requestHeaders.keys
                        .filter { it.equals("User-Agent", ignoreCase = true) }
                        .forEach(requestHeaders::remove)
                    requestHeaders["User-Agent"] = userAgent
                    val httpFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(requestHeaders)
                    val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
                    val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                        .createMediaSource(item)
                    player.setMediaSource(mediaSource)
                }
                player.prepare()
                AppDiagnostics.record("PLAYER_PREPARE", "source=${track.source.key}; mediaItemCount=${player.mediaItemCount}; customHeaders=${r.headers.isNotEmpty() || !r.userAgent.isNullOrBlank()}")
                // Fire the file-header probe so the badge can later clamp the claimed tier
                // down if the bytes turn out to be lower-grade than the script reported.
                // Spec §5.4: clear current spec first, run async, validate URL is still
                // current before publishing.
                _audioSpec.value = null
                probingUrl = r.url
                probeJob?.cancel()
                if (audioSpecProbe != null) {
                    probeJob = scope.launch {
                        val targetUrl = r.url
                        val spec = audioSpecProbe.probe(targetUrl)
                        if (spec != null && probingUrl == targetUrl) {
                            _audioSpec.value = spec
                        }
                    }
                }
                // Start the MediaSessionService only after URL resolution and media preparation.
                // Media3 owns the media notification and promotes the service when playback starts.
                // Start the service before the player transitions to READY/PLAYING.
                // MediaSessionService observes that transition to publish its media notification
                // and promote itself. Starting the FGS after playback has already begun can miss
                // the transition on Android 9 and trigger RemoteServiceException.
                ensureService()
                player.playWhenReady = true
                _state.value = _state.value.copy(
                    resolving = false,
                    warning = r.warning,
                    originLabel = r.origin.label,
                    quality = r.quality,
                )
                onTrackStarted?.invoke(track)
                // (lyrics already loaded in playAt when the track switched)
            }.onFailure { e ->
                // Couldn't get a play URL for this track — auto-skip (or stop after too many).
                if (generation == resolveGeneration && _state.value.currentTrack?.id == track.id) {
                    failAndAdvance(e.message ?: "無法播放", generation)
                }
            }
    }

    /**
     * A track has definitively failed (couldn't resolve a URL, or an unrecoverable player error).
     * Auto-skip to the next track so the queue keeps going; only stop once
     * [MAX_CONSECUTIVE_FAILURES] tracks fail back-to-back. The streak resets when any track
     * reaches READY (see the player listener). Guarded so a user skipping mid-delay wins.
     */
    private fun failAndAdvance(reason: String, expectedGeneration: Long) {
        if (expectedGeneration != resolveGeneration) {
            AppDiagnostics.record("AUTO_SKIP_STALE", "generation=$expectedGeneration; currentGeneration=$resolveGeneration; reason=$reason")
            return
        }
        // Stop whatever is still loaded (e.g. a cascade retry's half-played attempt) so a failed
        // track actually goes silent.
        runCatching { player.stop() }
        networkRetryCount = 0
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            _state.value = _state.value.copy(
                error = "連續 $MAX_CONSECUTIVE_FAILURES 首歌曲播放失敗，已停止播放（$reason）",
                resolving = false,
                warning = null,
            )
            return
        }
        _state.value = _state.value.copy(
            warning = "播放失敗，將自動播放下一首（$reason）",
            resolving = false,
            error = null,
        )
        val failed = _state.value.currentTrack
        val failedId = failed?.id
        AppDiagnostics.record("AUTO_SKIP", "generation=$expectedGeneration; source=${failed?.source?.key}; trackId=${failedId ?: "none"}; index=${_state.value.index}; queueSize=${_state.value.queue.size}; reason=$reason")
        autoSkipJob?.cancel()
        autoSkipJob = scope.launch {
            delay(400)
            if (expectedGeneration == resolveGeneration && _state.value.currentTrack?.id == failedId) next()
        }
    }

    /**
     * Toggle audio offload (DSP direct path). Some vendor audio HALs (小米/鸿蒙/坚果 etc.)
     * advertise offload support but crash natively when fed FLAC/Hi-Res through it, so this
     * is exposed as a settings switch. Takes effect on the next format change / track switch.
     */
    fun setAudioOffloadEnabled(enabled: Boolean) {
        val mode = if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .build(),
            )
            .build()
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        // Cold-start path: restoreSnapshot() stages the queue + index in [_state] but only
        // calls playAt() when the previous session was actively playing (wasPlaying=true).
        // If the user left paused, ExoPlayer is empty on relaunch — `player.play()` is a
        // no-op. Lazy-resolve the current track now so the play button actually starts the
        // song that the UI is already showing.
        val s = _state.value
        if (player.mediaItemCount == 0 && s.queue.isNotEmpty() && s.index in s.queue.indices) {
            playAt(s.index)
        } else {
            // Attach the MediaSessionService before changing the player state.
            ensureService()
            player.play()
        }
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIdx = when {
            s.shuffle -> if (s.queue.size <= 1) s.index else randomOther(s.index, s.queue.size)
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeatMode != RepeatMode.OFF -> 0
            else -> { player.pause(); return }
        }
        playAt(nextIdx)
    }

    fun prev() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val prevIdx = when {
            s.shuffle -> if (s.queue.size <= 1) s.index else randomOther(s.index, s.queue.size)
            s.index - 1 >= 0 -> s.index - 1
            else -> s.queue.size - 1
        }
        playAt(prevIdx)
    }

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    fun setRepeatMode(mode: RepeatMode) { _state.value = _state.value.copy(repeatMode = mode) }

    fun toggleShuffle() { _state.value = _state.value.copy(shuffle = !_state.value.shuffle) }

    fun stop() {
        resolveGeneration++
        probeJob?.cancel()
        probeJob = null
        probingUrl = null
        _audioSpec.value = null
        activeMediaId = null
        retryJob?.cancel()
        autoSkipJob?.cancel()
        resolveJob?.cancel()
        lyricsJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
        _lyrics.value = emptyList()
    }

    // MARK: - MV

    /** Play an MV video url on the same player (UI attaches a PlayerView for output). */
    fun playMvUrl(url: String) {
        val generation = ++resolveGeneration
        probeJob?.cancel()
        probeJob = null
        probingUrl = null
        _audioSpec.value = null
        retryJob?.cancel()
        autoSkipJob?.cancel()
        val mediaId = "mv:$generation"
        activeMediaId = mediaId
        resolveJob?.cancel()
        player.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(url).build())
        player.prepare()
        ensureService()
        player.playWhenReady = true
        _state.value = _state.value.copy(isMv = true, resolving = false, error = null)
    }

    /** Exit MV and resume the current audio track. */
    fun exitMv() {
        if (!_state.value.isMv) return
        _state.value = _state.value.copy(isMv = false)
        playAt(_state.value.index)
    }

    // MARK: - internals

    private fun handleEnded() {
        if (_state.value.repeatMode == RepeatMode.ONE) {
            player.seekTo(0); player.play(); return
        }
        next()
    }

    private fun loadLyrics(track: Track) {
        // A previous track's slow lyric fallback must not keep consuming network capacity.
        lyricsJob?.cancel()
        _lyrics.value = emptyList()
        lyricsJob = scope.launch {
            val lines = try {
                lyricsFetcher.fetch(track).orEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (_state.value.currentTrack?.id == track.id) _lyrics.value = lines
        }
    }

    /**
     * Attach MediaSessionService before playback state changes.
     *
     * Do not use startForegroundService() here: track resolution can take several seconds,
     * while Media3 only promotes the service after playback becomes active. Android 9
     * requires a service started with startForegroundService() to call startForeground()
     * within a short deadline, so starting it while the player is still idle can crash with
     * RemoteServiceException. A normal service start is sufficient while this foreground
     * app is preparing playback; Media3 will promote it when playback actually starts.
     */
    private fun ensureService() {
        if (serviceStarted) return
        runCatching {
            appContext.startService(Intent(appContext, PlaybackService::class.java))
            serviceStarted = true
        }.onFailure {
            serviceStarted = false
            android.util.Log.e("PlaybackController", "Unable to start playback session service", it)
        }
    }

    /** Called by PlaybackService.onDestroy so a later resume can start a fresh service instance. */
    fun onPlaybackServiceDestroyed() {
        serviceStarted = false
    }

    private fun randomOther(current: Int, size: Int): Int {
        var i = current
        while (i == current) i = Random.nextInt(size)
        return i
    }

    private fun startPositionTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                if (player.isPlaying || _state.value.isMv) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                delay(1000)
            }
        }
    }

    fun release() {
        resolveGeneration++
        tickerJob?.cancel()
        probeJob?.cancel()
        probingUrl = null
        activeMediaId = null
        retryJob?.cancel()
        autoSkipJob?.cancel()
        resolveJob?.cancel()
        lyricsJob?.cancel()
        probeJob?.cancel()
        scope.cancel()
        player.release()
    }

    /** Current ExoPlayer audio session id — what an `Equalizer` etc must bind to. */
    val audioSessionId: Int get() = player.audioSessionId
}