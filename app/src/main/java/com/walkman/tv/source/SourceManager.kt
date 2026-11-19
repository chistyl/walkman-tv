package com.walkman.tv.source

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.js.JsScriptRuntime
import com.walkman.tv.source.js.ScriptHttpClient
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
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun unload(scriptId: String) {
        _loaded.value.firstOrNull { it.script.id == scriptId }?.runtime?.destroy()
        _loaded.value = _loaded.value.filter { it.script.id != scriptId }
    }

    private fun scriptsFor(source: ScriptSourceKey, action: String): List<LoadedScript> =
        _loaded.value.filter { it.capabilities.sources[source]?.actions?.contains(action) == true }

    private fun scriptFor(source: ScriptSourceKey, action: String): LoadedScript? =
        scriptsFor(source, action).firstOrNull()

    /** Resolve a play URL — mirrors `resolveMusicURL`. */
    suspend fun resolveMusicURL(track: Track, quality: Quality? = null): ResolvedTrack {
        val preferred = quality ?: Quality.K320

        val scripts = scriptsFor(ScriptSourceKey(track.providerKey), "musicUrl")
        scripts.forEachIndexed { scriptIdx, ls ->
            val scriptQs = ls.capabilities.sources[ScriptSourceKey(track.providerKey)]?.qualities?.toSet() ?: emptySet()
            val first = pickPlayQuality(preferred, track.qualities.toSet(), scriptQs)
            val cascade = qualityCascade(first, track.qualities.toSet(), scriptQs)
            for ((idx, q) in cascade.withIndex()) {
                var timedOut = false
                runCatching { resolveViaScript(track, q, ls) }
                    .onSuccess { url ->
                        val warn = when {
                            scriptIdx > 0 -> "已切换到音源「${ls.script.name}」"
                            idx > 0 -> "${first.displayName} 远程不支持，已降级到 ${q.displayName}"
                            else -> null
                        }
                        return ResolvedTrack(url, ResolveOrigin.Script(ls.script.name), q, warn)
                    }
                    .onFailure {
                        Log.d(TAG, "script '${ls.script.name}' @${q.key} failed: ${it.message}")
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
            runCatching { builtIn.resolve(track, qForFallback) }.getOrNull()?.let { res ->
                return ResolvedTrack(res.url, ResolveOrigin.DirectFallback, qForFallback, res.warning)
            }
        }

        if (scriptFor(ScriptSourceKey(track.providerKey), "musicUrl") == null) {
            throw SourceException("没有脚本提供 ${track.providerDisplayName} 源，请到设置 → 自定义音源 导入脚本")
        }
        throw SourceException("这首 ${track.providerDisplayName} 歌曲无法获取播放地址，换一首或换个能用的脚本")
    }

    private suspend fun tryOtherSources(track: Track, preferred: Quality): ResolvedTrack? {
        val alternatives = otherSourceFinder.findMatches(track)
        if (alternatives.isEmpty()) return null
        for (alt in alternatives.take(5)) {
            val altLS = scriptFor(ScriptSourceKey(alt.providerKey), "musicUrl") ?: continue
            val q = pickPlayQuality(
                preferred,
                alt.qualities.toSet(),
                altLS.capabilities.sources[ScriptSourceKey(alt.providerKey)]?.qualities?.toSet() ?: emptySet(),
            )
            runCatching { resolveViaScript(alt, q, altLS) }.getOrNull()?.let { url ->
                val warn = "原 ${track.providerDisplayName} 源无法获取播放地址，已换到 ${alt.providerDisplayName}"
                return ResolvedTrack(url, ResolveOrigin.OtherSource(ScriptSourceKey(alt.providerKey)), q, warn)
            }
        }
        return null
    }

    private suspend fun resolveViaScript(track: Track, quality: Quality, ls: LoadedScript): String {
        val info = JSONObject()
            .put("type", quality.key)
            .put("musicInfo", makeOldMusicInfo(track))
        val result = ls.runtime.requestAction(ScriptSourceKey(track.providerKey), "musicUrl", info)
        val obj = result as? JSONObject
        val url = obj?.optJSONObject("data")?.optString("url")?.ifEmpty { null }
            ?: obj?.optString("url")?.ifEmpty { null }
        return url ?: throw SourceException("脚本返回了无效结果")
    }

    /** Ask a user script for lyric data — raw `{lyric, tlyric, rlyric, lxlyric}` (iOS `requestLyric`). */
    suspend fun requestLyric(track: Track): JSONObject? {
        val ls = scriptFor(ScriptSourceKey(track.providerKey), "lyric") ?: throw SourceException("没有脚本提供 ${track.providerDisplayName} 歌词")
        val info = JSONObject().put("type", "").put("musicInfo", makeOldMusicInfo(track))
        return ls.runtime.requestAction(ScriptSourceKey(track.providerKey), "lyric", info) as? JSONObject
    }

    fun availableQualities(source: SourceID): List<Quality> =
        availableQualities(ScriptSourceKey(source.key))

    /** Known enum qualities declared for any built-in or custom script platform. */
    fun availableQualities(source: ScriptSourceKey): List<Quality> {
        val set = mutableSetOf<Quality>()
        _loaded.value.forEach { it.capabilities.sources[source]?.qualities?.forEach(set::add) }
        return Quality.entries.filter { set.contains(it) }
    }

    /** Raw quality identifiers, including tiers not yet represented by the app's Quality enum. */
    fun availableQualityKeys(source: ScriptSourceKey): List<String> =
        _loaded.value.flatMap { it.capabilities.sources[source]?.qualityKeys.orEmpty() }.distinct()

    /** Search every loaded script platform that explicitly declares the search action. */
    suspend fun searchAll(keyword: String, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val providers = _loaded.value.flatMap { loaded ->
            loaded.capabilities.sources.entries
                .filter { (_, capability) -> capability.actions.contains("search") }
                .map { (key, _) -> loaded to key }
        }.distinctBy { it.first.script.id to it.second }
        return kotlinx.coroutines.coroutineScope {
            providers.map { (loaded, source) ->
                kotlinx.coroutines.async {
                    runCatching { searchWithScript(loaded, source, keyword, page) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun searchWithScript(
        loaded: LoadedScript,
        source: ScriptSourceKey,
        keyword: String,
        page: Int,
    ): List<Track> {
        val info = JSONObject().put("name", keyword).put("keyword", keyword).put("page", page)
        val raw = loaded.runtime.requestAction(source, "search", info)
        val root = raw as? JSONObject
        val rows = when (raw) {
            is JSONArray -> raw
            is JSONObject -> sequenceOf("list", "songs", "data", "result")
                .mapNotNull { key -> raw.optJSONArray(key) }
                .firstOrNull()
                ?: root?.optJSONObject("data")?.optJSONArray("list")
            else -> null
        } ?: return emptyList()

        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val songmid = item.optString("songmid").ifEmpty { item.optString("id") }
                .ifEmpty { item.optString("mid") }.ifEmpty { return@mapNotNull null }
            val name = item.optString("name").ifEmpty { item.optString("songName") }
                .ifEmpty { return@mapNotNull null }
            val singer = item.optString("singer").ifEmpty { item.optString("artist") }
                .ifEmpty { item.optString("artists") }.ifEmpty { "未知歌手" }
            val album = item.optString("albumName").ifEmpty { item.optString("album") }.ifEmpty { null }
            val image = item.optString("img").ifEmpty { item.optString("picURL") }
                .ifEmpty { item.optString("cover") }.ifEmpty { null }
            val duration = item.optInt("interval").takeIf { it > 0 }
                ?: item.optInt("duration").takeIf { it > 0 }
            val rawTypes = item.optJSONArray("types") ?: item.optJSONArray("qualitys")
            val qualityKeys = buildList {
                if (rawTypes != null) for (i in 0 until rawTypes.length()) {
                    val entry = rawTypes.opt(i)
                    val key = if (entry is JSONObject) entry.optString("type") else entry?.toString().orEmpty()
                    if (key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) add(key)
                }
            }.distinct()
            val extras = mutableMapOf<String, String>()
            val extrasObj = item.optJSONObject("extras")
            extrasObj?.keys()?.forEach { key -> extrasObj.optString(key).takeIf { it.isNotEmpty() }?.let { extras[key] = it } }
            item.optString("hash").ifEmpty { null }?.let { extras["hash"] = it }
            item.optString("mvId").ifEmpty { null }?.let { extras["mvId"] = it }
            Track(
                id = Track.makeID(source.value, songmid),
                name = name,
                singer = singer,
                albumName = album,
                albumId = item.optString("albumId").ifEmpty { null },
                source = source.builtIn ?: SourceID.LOCAL,
                songmid = songmid,
                duration = duration,
                picURL = image,
                qualities = qualityKeys.mapNotNull(Quality::fromKey),
                extras = extras,
                sourceKey = source.value,
            )
        }
    }

    /** Every platform key declared by loaded scripts, including third-party identifiers. */
    fun supportedSourceKeys(): List<ScriptSourceKey> =
        _loaded.value.flatMap { it.capabilities.sources.entries }
            .filter { (_, capability) -> capability.actions.contains("musicUrl") }
            .map { it.key }
            .distinct()

    /** Legacy catalog-facing view; dynamic keys are available through [supportedSourceKeys]. */
    fun supportedSources(): List<SourceID> =
        supportedSourceKeys().mapNotNull { it.builtIn }.distinct()

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
            .put("source", track.providerKey)
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
