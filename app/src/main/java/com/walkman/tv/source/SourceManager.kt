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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Accept common script resolver response shapes while returning only a non-empty URL string. */
/** Custom tiers are attempted only when both the track and provider advertise the exact key. */
internal fun matchingCustomQualityKeys(trackKeys: Collection<String>, scriptKeys: Collection<String>): List<String> {
    val standardKeys = Quality.entries.map(Quality::key).toSet()
    val availableOnTrack = trackKeys.toSet()
    return scriptKeys.distinct().filter { key ->
        key !in standardKeys && key in availableOnTrack &&
            key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))
    }
}

internal fun extractPlayableUrl(raw: Any?, depth: Int = 0): String? {
    if (depth > 4) return null
    return when (raw) {
        is String -> raw.trim().takeIf { it.isNotEmpty() }
        is JSONObject -> {
            val direct = sequenceOf("url", "playUrl", "play_url", "src")
                .mapNotNull { key -> raw.opt(key) as? String }
                .firstOrNull { it.isNotBlank() }
            direct?.trim()
                ?: sequenceOf("data", "result", "url")
                    .mapNotNull { key -> raw.opt(key) }
                    .firstNotNullOfOrNull { extractPlayableUrl(it, depth + 1) }
        }
        else -> null
    }
}

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
                runCatching { resolveViaScript(track, q.key, ls) }
                    .onSuccess { url ->
                        val warn = when {
                            scriptIdx > 0 -> "已切換到音源「${ls.script.name}」"
                            idx > 0 -> "${first.displayName} 遠程不支援，已降級到 ${q.displayName}"
                            else -> null
                        }
                        return ResolvedTrack(url, ResolveOrigin.Script(ls.script.name), q, warn)
                    }
                    .onFailure {
                        Log.d(TAG, "script '${ls.script.name}' @${q.key} failed: ${it.message}")
                        timedOut = it is JsScriptRuntime.ScriptTimeoutException
                    }
                if (timedOut) break
            }

            // Try provider-defined tiers unknown to this app only after all standard tiers.
            // Search metadata must advertise the key and the script must declare it.
            val rawScriptKeys = ls.capabilities.sources[ScriptSourceKey(track.providerKey)]?.qualityKeys.orEmpty()
            val customKeys = matchingCustomQualityKeys(track.qualityKeys, rawScriptKeys)
            for (rawKey in customKeys) {
                var timedOut = false
                runCatching { resolveViaScript(track, rawKey, ls) }
                    .onSuccess { url ->
                        return ResolvedTrack(
                            url = url,
                            origin = ResolveOrigin.Script(ls.script.name),
                            quality = Quality.K128,
                            warning = "已使用音源自訂音質：$rawKey",
                            qualityKey = rawKey,
                        )
                    }
                    .onFailure { error ->
                        Log.d(TAG, "script '${ls.script.name}' @$rawKey failed: ${error.message}")
                        timedOut = error is JsScriptRuntime.ScriptTimeoutException
                    }
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
        // Keep the original ranked built-in fallback, then extend it with results from every
        // loaded script that declares a search action. This also covers arbitrary provider keys
        // that cannot be represented by the legacy SourceID enum.
        val builtInAlternatives = otherSourceFinder.findMatches(track)
        val dynamicAlternatives = runCatching {
            searchAll("${track.name} ${track.singer}", 1)
        }.getOrDefault(emptyList())
            .filter { it.providerKey != track.providerKey }
        val alternatives = (builtInAlternatives + dynamicAlternatives)
            .distinctBy { it.id }
        if (alternatives.isEmpty()) return null

        for (alt in alternatives.take(10)) {
            val altKey = ScriptSourceKey(alt.providerKey)
            val altLS = scriptFor(altKey, "musicUrl") ?: continue
            val q = pickPlayQuality(
                preferred,
                alt.qualities.toSet(),
                altLS.capabilities.sources[altKey]?.qualities?.toSet() ?: emptySet(),
            )
            runCatching { resolveViaScript(alt, q.key, altLS) }.getOrNull()?.let { url ->
                val warn = "原 ${track.providerDisplayName} 源无法获取播放地址，已换到 ${alt.providerDisplayName}"
                return ResolvedTrack(url, ResolveOrigin.OtherSource(altKey), q, warn)
            }

            // After standard tiers fail, try only custom tiers advertised by BOTH
            // the alternative track metadata and the destination script capability.
            val scriptCustomKeys = altLS.capabilities.sources[altKey]?.qualityKeys.orEmpty()
            val sharedCustomKeys = matchingCustomQualityKeys(alt.qualityKeys, scriptCustomKeys)
            for (rawKey in sharedCustomKeys) {
                val customUrl = runCatching { resolveViaScript(alt, rawKey, altLS) }.getOrNull()
                if (!customUrl.isNullOrBlank()) {
                    val warn = "原 ${track.providerDisplayName} 源无法获取播放地址，已换到 ${alt.providerDisplayName}（自訂音質：$rawKey）"
                    return ResolvedTrack(
                        url = customUrl,
                        origin = ResolveOrigin.OtherSource(altKey),
                        quality = Quality.K128,
                        warning = warn,
                        qualityKey = rawKey,
                    )
                }
            }
        }
        return null
    }

    private suspend fun resolveViaScript(track: Track, qualityKey: String, ls: LoadedScript): String {
        val info = JSONObject()
            .put("type", qualityKey)
            .put("musicInfo", makeOldMusicInfo(track))
        val result = ls.runtime.requestAction(ScriptSourceKey(track.providerKey), "musicUrl", info)
        return extractPlayableUrl(result)
            ?: throw SourceException("腳本未回傳有效的播放網址")
    }

    /**
     * Whether a loaded script explicitly opts into the TV songlist extension.
     *
     * Contract: the provider declares the `songlist` action in its source capability.
     * Requests use { action: "search" | "detail", data: { ... } }. The action response
     * is returned untouched so the catalog/import layer can validate provider-specific data.
     */
    fun supportsSonglists(source: ScriptSourceKey): Boolean =
        scriptFor(source, "songlist") != null

    /**
     * Invoke the opt-in TV songlist extension. Search requests should include keyword/page;
     * detail requests should include playlist id and any provider-specific metadata.
     * This deliberately does not guess or normalize the response schema.
     */
    suspend fun requestSonglistAction(
        source: ScriptSourceKey,
        operation: String,
        data: JSONObject,
    ): Any? {
        require(operation == "search" || operation == "detail") {
            "Unsupported songlist operation: $operation"
        }
        val ls = scriptFor(source, "songlist") ?: return null
        val request = JSONObject()
            .put("action", operation)
            .put("data", data)
        return ls.runtime.requestAction(source, "songlist", request)
    }

    /** Search songlists from one script provider using the shared normalized payload parser. */
    suspend fun searchScriptSonglists(
        source: ScriptSourceKey,
        keyword: String,
        page: Int = 1,
    ): List<com.walkman.tv.data.model.SonglistInfo> {
        if (keyword.isBlank() || page < 1) return emptyList()
        val ls = scriptFor(source, "songlist") ?: return emptyList()
        val raw = requestSonglistAction(
            source,
            "search",
            JSONObject().put("keyword", keyword).put("name", keyword).put("page", page),
        )
        return ScriptSonglistParser.parseLists(raw, source, ls.script.name)
    }

    /** Fetch one script songlist's detail and normalize its returned tracks. */
    suspend fun fetchScriptSonglistDetail(
        info: com.walkman.tv.data.model.SonglistInfo,
    ): com.walkman.tv.data.model.SonglistDetail? {
        val source = ScriptSourceKey.from(info.providerKey) ?: return null
        val ls = scriptFor(source, "songlist") ?: return null
        val raw = requestSonglistAction(
            source,
            "detail",
            JSONObject()
                .put("id", info.id)
                .put("playlistId", info.id)
                .put("songlist", JSONObject()
                    .put("id", info.id)
                    .put("name", info.name)
                    .put("source", source.value)),
        )
        return ScriptSonglistParser.parseDetail(raw, info, source, ls.script.name)
    }

    /** Invoke an optional script action only when the track provider explicitly declares it. */
    suspend fun requestOptionalAction(track: Track, action: String, info: JSONObject): Any? {
        val source = ScriptSourceKey(track.providerKey)
        val ls = scriptFor(source, action) ?: return null
        return ls.runtime.requestAction(source, action, info)
    }

    /**
     * Resolve an optional LX Music v4 `pic` action for the currently playing track.
     * Existing search-result artwork always wins; this is a playback-only fallback.
     * Accepted script results: URL string, {url}, or {data:{url}}.
     */
    suspend fun resolveOptionalPicture(track: Track): String? {
        val info = JSONObject().put("musicInfo", makeOldMusicInfo(track))
        val raw = runCatching { requestOptionalAction(track, "pic", info) }.getOrNull()
        val obj = raw as? JSONObject
        val candidate = when (raw) {
            is String -> raw
            is JSONObject -> obj?.optString("url")?.takeIf { it.isNotBlank() }
                ?: obj?.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }
            else -> null
        }
        return candidate?.takeIf(::isHttpImageUrl)
            ?: track.picURL?.takeIf(::isHttpImageUrl)
    }

    private fun isHttpImageUrl(value: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
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

    /** Search one exact script platform key, including non-built-in providers. */
    suspend fun search(source: ScriptSourceKey, keyword: String, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val providers = scriptsFor(source, "search")
        return coroutineScope {
            providers.map { loaded ->
                async {
                    runCatching { searchWithScript(loaded, source, keyword, page) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /** Search every loaded script platform that explicitly declares the search action. */
    suspend fun searchAll(keyword: String, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val providers = _loaded.value.flatMap { loaded ->
            loaded.capabilities.sources.entries
                .filter { (_, capability) -> capability.actions.contains("search") }
                .map { (key, _) -> loaded to key }
        }.distinctBy { it.first.script.id to it.second }
        return coroutineScope {
            providers.map { (loaded, source) ->
                async {
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
        return ScriptTrackParser.parseSearch(raw, source, loaded.script.name)
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
        val allKeys = (Quality.orderedHighToLow.filter { track.qualities.contains(it) }.map { it.key } +
            track.qualityKeys).distinct()
        allKeys.forEach { key ->
            types.put(JSONObject().put("type", key).put("size", ""))
            typesObj.put(key, JSONObject().put("size", ""))
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
