package com.walkman.tv.source.builtin

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Direct platform endpoints used as a last-resort fallback (iOS `BuiltInResolver`).
 * Only kw / wy are feasible without complex signing.
 */
class BuiltInResolver(client: OkHttpClient) {

    data class ResolvedURL(val url: String, val warning: String? = null)
    class ResolveException(message: String) : Exception(message)

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun resolve(track: Track, quality: Quality): ResolvedURL = withContext(Dispatchers.IO) {
        when (track.source) {
            SourceID.KW -> resolveKuwo(track.songmid, quality)
            SourceID.WY -> ResolvedURL(resolveNetEase(track.songmid))
            else -> throw ResolveException("內建直連暫不支援 ${track.source.displayName}")
        }
    }

    // MARK: Kuwo

    private fun kuwoBR(q: Quality) = when (q) {
        Quality.K128 -> "128kmp3"
        Quality.K320 -> "320kmp3"
        Quality.FLAC -> "2000kflac"
        // Extended tiers (hires/atmos/atmos_plus/master) — Kuwo's anti.s convert_url path
        // only knows up to 4000khires. Anything fancier maps to the same Hi-Res request and
        // gets de-facto downgraded to 4000khires by the backend.
        Quality.FLAC24, Quality.HIRES, Quality.ATMOS, Quality.ATMOS_PLUS, Quality.MASTER -> "4000khires"
    }

    private fun resolveKuwo(songmid: String, quality: Quality): ResolvedURL {
        val preferred = kuwoBR(quality)
        var lastDRM: String? = null
        for (br in orderedKuwoBRs(preferred)) {
            runCatching { kuwoConvertURL3(songmid, br) }.getOrNull()?.let { u ->
                if (isKuwoDRMPlaceholder(u)) lastDRM = u else return ResolvedURL(u)
            }
        }
        runCatching { kuwoConvertURLLegacy(songmid, preferred) }.getOrNull()?.let { u ->
            if (isKuwoDRMPlaceholder(u)) lastDRM = u else return ResolvedURL(u)
        }
        lastDRM?.let {
            return ResolvedURL(it, "酷我對未授權的請求只會回傳 DRM 佔位音訊。需要使用可解析真實 URL 的 v4 指令碼，才能播放原曲。")
        }
        throw ResolveException("此曲目可能是 VIP 或受限制的歌曲")
    }

    private fun orderedKuwoBRs(br: String): List<String> {
        val cascade = listOf("4000khires", "2000kflac", "320kmp3", "128kmp3")
        val idx = cascade.indexOf(br)
        return if (idx < 0) listOf(br) + cascade else cascade.subList(idx, cascade.size) + cascade.subList(0, idx)
    }

    private fun kuwoConvertURL3(songmid: String, br: String): String? {
        val url = "http://antiserver.kuwo.cn/anti.s?type=convert_url3&format=mp3&response=url&rid=$songmid&br=$br"
        val body = get(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", 200) != 200) return null
        return json.optString("url").ifEmpty { null }
    }

    private fun kuwoConvertURLLegacy(songmid: String, br: String): String? {
        val url = "http://antiserver.kuwo.cn/anti.s?format=mp3&rid=$songmid&type=convert_url&response=url&br=$br"
        val text = get(url)?.trim() ?: return null
        return if (text.startsWith("http")) text else null
    }

    private fun isKuwoDRMPlaceholder(url: String): Boolean =
        url.substringAfterLast('/').substringBefore('?') in KUWO_DRM_PLACEHOLDERS

    // MARK: NetEase

    private fun resolveNetEase(songmid: String): String {
        val req = Request.Builder()
            .url("https://music.163.com/song/media/outer/url?id=$songmid.mp3")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in listOf(301, 302, 303)) throw ResolveException("HTTP ${resp.code}")
            val location = resp.header("Location") ?: throw ResolveException("未回傳播放網址")
            if (location.contains("/404")) throw ResolveException("此曲目可能是 VIP 或受限制的歌曲")
            return location
        }
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)"
        val KUWO_DRM_PLACEHOLDERS = setOf("588957081.mp3")
    }
}
