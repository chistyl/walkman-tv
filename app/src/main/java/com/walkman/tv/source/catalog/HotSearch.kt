package com.walkman.tv.source.catalog

import android.os.SystemClock
import com.walkman.tv.data.model.SourceID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One platform's hot-search words, in rank order. */
data class HotSearchColumn(val source: SourceID, val words: List<String>)

/**
 * Hot / trending search words for each platform, shown on the search screen (and pushed to the
 * phone page) before the user has typed anything.
 *
 * Results are cached in-process for five minutes. The mutex also coalesces simultaneous callers:
 * entering the search screen and opening the phone QR page together won't issue duplicate requests.
 *
 * - QQ / NetEase / Kugou: public endpoints, no signing.
 * - Kuwo: the /api/www endpoints are behind a Secret header derived from a cookie; we
 *   fabricate a self-consistent (cookie, Secret) pair — see [KuwoSecret].
 */
class HotSearch(private val http: CatalogHttp) {
    private val ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)"
    private val cacheMutex = Mutex()
    private var cachedColumns: List<HotSearchColumn>? = null
    private var cachedAtElapsedMs: Long = 0L

    /**
     * Fetch all four platforms concurrently. A failed platform yields an empty column so the UI
     * can still render its labeled column. Cached results remain available for five minutes.
     */
    suspend fun fetchAll(forceRefresh: Boolean = false): List<HotSearchColumn> =
        cacheMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            cachedColumns?.takeIf {
                !forceRefresh && now - cachedAtElapsedMs < CACHE_TTL_MS
            }?.let { return@withLock it }

            val startedAt = now
            val fresh = coroutineScope {
                listOf(
                    async { fetchColumn(SourceID.KW) { kuwo() } },
                    async { fetchColumn(SourceID.WY) { netease() } },
                    async { fetchColumn(SourceID.KG) { kugou() } },
                    async { fetchColumn(SourceID.TX) { qq() } },
                ).awaitAll()
            }
            cachedColumns = fresh
            cachedAtElapsedMs = SystemClock.elapsedRealtime()
            android.util.Log.d(TAG, "hot-search refresh completed in ${cachedAtElapsedMs - startedAt}ms")
            fresh
        }

    private suspend fun fetchColumn(
        source: SourceID,
        fetch: suspend () -> List<String>,
    ): HotSearchColumn = try {
        HotSearchColumn(source, fetch())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        HotSearchColumn(source, emptyList())
    }

    private suspend fun qq(): List<String> {
        // Prefer the legacy hotkey endpoint, but fall back to QQ Music's current musicu API
        // when the legacy c.y.qq.com endpoint is blocked or no longer returns hotkey data.
        val legacy = runCatching {
            val url = "https://c.y.qq.com/splcloud/fcgi-bin/gethotkey.fcg?format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1"
            val json = JSONObject(http.getText(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to ua)))
            val arr = json.optJSONObject("data")?.optJSONArray("hotkey")
            (0 until (arr?.length() ?: 0)).mapNotNull {
                arr?.optJSONObject(it)?.optString("k")?.trim()?.ifEmpty { null }
            }
        }.getOrDefault(emptyList())
        if (legacy.isNotEmpty()) return legacy

        val request = JSONObject()
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .put("req", JSONObject()
                .put("module", "music.search.SearchCgiService")
                .put("method", "GetHotkey")
                .put("param", JSONObject().put("search_type", 0).put("num", 20)))
        val text = http.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            request.toString(),
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to ua),
        )
        val data = JSONObject(text).optJSONObject("req")?.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("vec_hotkey")
            ?: data.optJSONArray("hotkey")
            ?: data.optJSONArray("hotkey_list")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val item = arr.optJSONObject(index)
            (item?.optString("k") ?: item?.optString("query") ?: item?.optString("word"))
                ?.trim()?.ifEmpty { null }
        }.distinct()
    }

    private suspend fun netease(): List<String> {
        val url = "https://music.163.com/api/search/hot?type=1111"
        val json = JSONObject(http.getText(url, mapOf("Referer" to "https://music.163.com/", "User-Agent" to ua)))
        val arr = json.optJSONObject("result")?.optJSONArray("hots") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("first")?.trim()?.ifEmpty { null } }
    }

    private suspend fun kugou(): List<String> {
        val url = "http://msearchcdn.kugou.com/api/v3/search/hot?version=9108&plat=0&clientver=9108"
        val json = JSONObject(http.getText(url, mapOf("User-Agent" to ua)))
        val arr = json.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("keyword")?.trim()?.ifEmpty { null } }
    }

    private suspend fun kuwo(): List<String> {
        // Fabricate a self-consistent cookie value + Secret. Server only checks they match.
        val hm = (System.currentTimeMillis() / 1000).let { "$it,$it" }
        val secret = KuwoSecret.compute(hm)
        if (secret.isEmpty()) return emptyList()
        val token = "kw${System.nanoTime().toString(16)}"
        val headers = mapOf(
            "Referer" to "http://www.kuwo.cn/",
            "User-Agent" to ua,
            "Secret" to secret,
            "csrf" to token,
            "Cookie" to "${KuwoSecret.COOKIE_NAME}=$hm; kw_token=$token",
        )
        // Empty key → the suggestion endpoint returns the hot-search list (newhotKeyList).
        val json = JSONObject(http.getText("http://www.kuwo.cn/api/www/search/searchKey?key=", headers))
        val arr = json.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } }
    }

    private companion object {
        const val TAG = "WalkmanPerf"
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
    }
}
