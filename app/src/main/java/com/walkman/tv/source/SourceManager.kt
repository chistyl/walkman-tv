package com.walkman.tv.source

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.diagnostics.AppDiagnostics
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.js.JsScriptRuntime
import com.walkman.tv.source.js.ScriptHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns loaded user scripts and resolves play URLs, ported from iOS `SourceManager`.
 * Resolve order: user script (with quality cascade) → other-source search → built-in direct.
 */
class SourceManager(
    private val preload: String,
    private val http: ScriptHttpClient,
    private val otherSourceFinder: OtherSourceFinder,
    private val builtIn: BuiltInResolver,
) {
    data class LoadedScript(
        val script: UserScript,
        val runtime: JsScriptRuntime,
        val capabilities: ScriptCapabilities,
    )

    private val _loaded = MutableStateFlow<List<LoadedScript>>(emptyList())
    val loaded: StateFlow<List<LoadedScript>> = _loaded.asStateFlow()

    @Volatile var fallbackEnabled: Boolean = true

    class SourceException(message: String) : Exception(message)

    suspend fun load(script: UserScript): Result<ScriptCapabilities> {
        return try {
            val runtime = JsScriptRuntime(script, preload, http)
            val caps = runtime.load()
            _loaded.value = _loaded.value.filter { it.script.id != script.id } +
                LoadedScript(script, runtime, caps)
            Result.success(caps)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun unload(scriptId: String) {
        _loaded.value.firstOrNull { it.script.id == scriptId }?.runtime?.destroy()
        _loaded.value = _loaded.value.filter { it.script.id != scriptId }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun scriptsFor(source: SourceID, action: String): List<LoadedScript> =
        _loaded.value.filter { it.capabilities.sources[source]?.actions?.contains(action) == true }

    private fun scriptFor(source: SourceID, action: String): LoadedScript? =
        scriptsFor(source, action).firstOrNull()

    /** Resolve a play URL — mirrors `resolveMusicURL`. */
    suspend fun resolveMusicURL(track: Track, quality: Quality? = null): ResolvedTrack {
        val preferred = quality ?: Quality.K320

        val scripts = scriptsFor(track.source, "musicUrl")
        scripts.forEachIndexed { scriptIdx, ls ->
            val scriptQs = ls.capabilities.sources[track.source]?.qualities?.toSet() ?: emptySet()
            val first = pickPlayQuality(preferred, track.qualities.toSet(), scriptQs)
            val cascade = qualityCascade(first, track.qualities.toSet(), scriptQs)
            for ((idx, q) in cascade.withIndex()) {
                var timedOut = false
                attempt { resolveViaScript(track, q, ls) }
                    .onSuccess { play ->
                        val warn = when {
                            scriptIdx > 0 -> "已切換至音源「${ls.script.name}」"
                            idx > 0 -> "${first.displayName} 遠端不支援，已降低至 ${q.displayName}"
                            else -> null
                        }
                        return ResolvedTrack(
                            url = play.url,
                            origin = ResolveOrigin.Script(ls.script.name),
                            quality = q,
                            warning = warn,
                            headers = play.headers,
                            userAgent = play.userAgent,
                        )
                    }
                    .onFailure {
                        Log.d(TAG, "script '${ls.script.name}' @${q.key} failed: ${it.message}")
                        AppDiagnostics.record("SOURCE_SCRIPT_ERROR", "source=${track.source.key}; script=${ls.script.name}; quality=${q.key}", it)
                        timedOut = it is JsScriptRuntime.ScriptTimeoutException
                    }
                // An unresponsive script won't answer at a lower tier either; don't wait out the
                // timeout once per tier (that was up to a minute of silence per song).
                if (timedOut) break
            }
        }

        tryOtherSources(track, preferred)?.let { return it }

        if (fallbackEnabled) {
            val qForFallback = if (track.qualities.contains(preferred)) preferred else (track.qualities.firstOrNull() ?: Quality.K128)
            attempt {
                builtIn.resolve(track, qForFallback).let { result ->
                    result.copy(url = PlayUrlValidator.requireHttpUrl(result.url))
                }
            }.getOrNull()?.let { res ->
                return ResolvedTrack(res.url, ResolveOrigin.DirectFallback, qForFallback, res.warning)
            }
        }

        if (scriptFor(track.source, "musicUrl") == null) {
            throw SourceException("沒有指令碼提供 ${track.source.displayName} 音源，請前往「設定」→「自訂音源」匯入指令碼")
        }
        throw SourceException("這首 ${track.source.displayName} 歌曲無法取得播放網址，請換一首歌曲或更換可用的指令碼")
    }

    private suspend fun tryOtherSources(track: Track, preferred: Quality): ResolvedTrack? {
        val alternatives = otherSourceFinder.findMatches(track)
        if (alternatives.isEmpty()) return null
        for (alt in alternatives.take(5)) {
            val altLS = scriptFor(alt.source, "musicUrl") ?: continue
            val q = pickPlayQuality(
                preferred,
                alt.qualities.toSet(),
                altLS.capabilities.sources[alt.source]?.qualities?.toSet() ?: emptySet(),
            )
            attempt { resolveViaScript(alt, q, altLS) }.getOrNull()?.let { play ->
                val warn = "原本的 ${track.source.displayName} 音源無法取得播放網址，已切換至 ${alt.source.displayName}"
                return ResolvedTrack(
                    url = play.url,
                    origin = ResolveOrigin.OtherSource(alt.source),
                    quality = q,
                    warning = warn,
                    headers = play.headers,
                    userAgent = play.userAgent,
                )
            }
        }
        return null
    }

    private data class ScriptPlayResult(
        val url: String,
        val headers: Map<String, String>,
        val userAgent: String?,
    )

    private suspend fun resolveViaScript(track: Track, quality: Quality, ls: LoadedScript): ScriptPlayResult {
        val info = JSONObject()
            .put("type", quality.key)
            .put("musicInfo", makeOldMusicInfo(track))
        val result = ls.runtime.requestAction(track.source, "musicUrl", info)
        val obj = result as? JSONObject
        val data = obj?.optJSONObject("data")
        val url = data?.optString("url")?.ifEmpty { null }
            ?: obj?.optString("url")?.ifEmpty { null }
        val headersObject = data?.optJSONObject("headers") ?: obj?.optJSONObject("headers")
        val headers = buildMap {
            headersObject?.keys()?.forEach { key ->
                if (!headersObject.isNull(key)) put(key, headersObject.get(key).toString())
            }
        }
        val userAgent = data?.optString("userAgent")?.ifEmpty { null }
            ?: obj?.optString("userAgent")?.ifEmpty { null }
        return ScriptPlayResult(
            url = PlayUrlValidator.requireHttpUrl(url),
            headers = headers,
            userAgent = userAgent,
        )
    }

    /** Ask a user script for lyric data — raw `{lyric, tlyric, rlyric, lxlyric}` (iOS `requestLyric`). */
    suspend fun requestLyric(track: Track): JSONObject? {
        val scripts = scriptsFor(track.source, "lyric")
        if (scripts.isEmpty()) {
            throw SourceException("沒有指令碼提供 ${track.source.displayName} 歌詞")
        }
        val info = JSONObject().put("type", "").put("musicInfo", makeOldMusicInfo(track))
        for (ls in scripts) {
            val result = try {
                ls.runtime.requestAction(track.source, "lyric", info, timeoutMs = 10_000) as? JSONObject
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lyric script '${ls.script.name}' failed: ${e.message}")
                null
            } ?: continue

            // A script may advertise lyric support but return an empty payload for a
            // particular song. Continue to the next matching script instead of stopping.
            val hasLyrics = listOf("lyric", "lxlyric", "tlyric")
                .any { result.optString(it).isNotBlank() }
            if (hasLyrics) return result
            Log.d(TAG, "lyric script '${ls.script.name}' returned no lyric data")
        }
        return null
    }

    fun availableQualities(source: SourceID): List<Quality> {
        val set = mutableSetOf<Quality>()
        _loaded.value.forEach { it.capabilities.sources[source]?.qualities?.forEach(set::add) }
        return Quality.entries.filter { set.contains(it) }
    }

    fun supportedSources(): List<SourceID> {
        val set = mutableSetOf<SourceID>()
        _loaded.value.forEach { ls ->
            ls.capabilities.sources.forEach { (src, cap) -> if (cap.actions.contains("musicUrl")) set.add(src) }
        }
        return SourceID.entries.filter { set.contains(it) }
    }

    /** Build the v4 "old format" musicInfo a script expects (iOS `makeOldMusicInfo`). */
    fun makeOldMusicInfo(track: Track): JSONObject {
        val types = JSONArray()
        val typesObj = JSONObject()
        Quality.orderedHighToLow.filter { track.qualities.contains(it) }.forEach { q ->
            types.put(JSONObject().put("type", q.key).put("size", ""))
            typesObj.put(q.key, JSONObject().put("size", ""))
        }
        val info = JSONObject()
            .put("name", track.name)
            .put("singer", track.singer)
            .put("source", track.source.key)
            .put("songmid", track.songmid)
            .put("interval", track.intervalText)
            .put("albumName", track.albumName ?: "")
            .put("img", track.picURL ?: "")
            .put("typeUrl", JSONObject())
            .put("albumId", track.albumId ?: "")
            .put("types", types)
            .put("_types", typesObj)
        track.extras.forEach { (k, v) -> info.put(k, v) }
        return info
    }

    companion object {
        private const val TAG = "SourceManager"

        /**
         * Pick the effective playback tier — spec §3.
         *
         * Walks [Quality.orderedHighToLow] starting at [preferred], returns the first tier
         * that satisfies the per-tier eligibility rule. Falls through to [Quality.K128] —
         * the universal floor — when nothing higher passes.
         *
         * Eligibility:
         *  - Regular tiers (128k/320k/flac/flac24bit): script declares it AND the search
         *    metadata listed it.
         *  - Extended tiers (hires/atmos/atmos_plus/master): script declares it. Catalog
         *    metadata almost never reports these, so the trackQs check is bypassed.
         */
        fun pickPlayQuality(preferred: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): Quality {
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(preferred).coerceAtLeast(0)
            for (i in startIdx until full.size) {
                val q = full[i]
                if (q == Quality.K128) return Quality.K128 // always reachable
                if (scriptQs.contains(q) && (q.isExtendedTier || trackQs.contains(q))) return q
            }
            return Quality.K128
        }

        /**
         * Build the descending cascade from [first] to [Quality.K128] — spec §3 last paragraph.
         * Each step uses the same per-tier rule as [pickPlayQuality]; 128k is appended
         * unconditionally so we always have a final fallback to try.
         */
        fun qualityCascade(first: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): List<Quality> {
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(first).coerceAtLeast(0)
            val out = mutableListOf<Quality>()
            for (i in startIdx until full.size) {
                val q = full[i]
                val eligible = q == Quality.K128 ||
                    (scriptQs.contains(q) && (q.isExtendedTier || trackQs.contains(q)))
                if (eligible) out.add(q)
            }
            return out.ifEmpty { listOf(first) }
        }
    }
}
