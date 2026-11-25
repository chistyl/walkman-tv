package com.walkman.tv.source.builtin

import android.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.source.catalog.CatalogHttp
import org.json.JSONObject

/**
 * Direct lyric fetchers per platform, ported from iOS `BuiltInLyricResolver`.
 * Uses the simplest endpoints (no krc decryption).
 */
class BuiltInLyricResolver(private val http: CatalogHttp) {

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")

    suspend fun fetch(track: Track): List<LyricLine>? = when (track.source) {
        SourceID.KW -> kuwo(track.songmid)
        SourceID.TX -> qq(track.songmid)
        SourceID.WY -> netease(track.songmid)
        SourceID.KG -> kugou(track.name, track.extras["hash"] ?: track.songmid, (track.duration ?: 0) * 1000)
        else -> null
    }

    private suspend fun kuwo(songmid: String): List<LyricLine>? {
        val json = runCatching {
            JSONObject(http.getText("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songmid", ua))
        }.getOrNull() ?: return null
        val lrclist = json.optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        val rebuilt = StringBuilder()
        for (i in 0 until lrclist.length()) {
            val item = lrclist.optJSONObject(i) ?: continue
            val secs = item.optString("time").toDoubleOrNull() ?: continue
            val text = item.optString("lineLyric")
            val m = secs.toInt() / 60
            val s = secs.toInt() % 60
            val ms = ((secs - secs.toInt()) * 100).toInt()
            rebuilt.append(String.format("[%02d:%02d.%02d]%s\n", m, s, ms, text))
        }
        return LyricParser.parse(rebuilt.toString()).ifEmpty { null }
    }

    private suspend fun qq(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf("Referer" to "https://y.qq.com/portal/player.html")
        val json = runCatching {
            JSONObject(http.getText("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq&qrc=1", headers))
        }.getOrNull() ?: return null
        if (json.optInt("code") != 0) return null
        val trans = json.optString("trans").ifEmpty { null }?.let { decodeB64(it) }
        // Prefer native QRC XML when the upstream endpoint exposes it.
        val qrcRaw = json.optString("qrc").ifBlank { json.optString("lyric") }
        val qrcText = decodeB64(qrcRaw) ?: qrcRaw
        val qrcLines = LyricParser.parse(qrcText, trans)
        if (qrcLines.any { it.words.isNotEmpty() }) return qrcLines
        val lrc = decodeB64(json.optString("lyric")) ?: return null
        return LyricParser.parse(lrc, trans).ifEmpty { null }
    }

    private suspend fun netease(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf("Referer" to "https://music.163.com/")
        // /lyric/new returns native YRC word timing when the track provides it.
        val enhanced = runCatching {
            JSONObject(http.getText("https://music.163.com/api/song/lyric/new?id=$songmid", headers))
        }.getOrNull()
        val yrc = enhanced?.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val enhancedLines = if (yrc.isNotBlank()) LyricParser.parse(yrc) else emptyList()
        if (enhancedLines.any { it.words.isNotEmpty() }) return enhancedLines

        val json = runCatching {
            JSONObject(http.getText("https://music.163.com/api/song/lyric?id=$songmid&lv=1&kv=1&tv=-1", headers))
        }.getOrNull() ?: return null
        val lyric = json.optJSONObject("lrc")?.optString("lyric") ?: ""
        if (lyric.isEmpty()) return null
        val tlyric = json.optJSONObject("tlyric")?.optString("lyric")?.ifEmpty { null }
        return LyricParser.parse(lyric, tlyric).ifEmpty { null }
    }

    private suspend fun kugou(name: String, hash: String, durationMs: Int): List<LyricLine>? {
        val headers = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        )
        val nameEnc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val searchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$nameEnc&duration=$durationMs&hash=$hash&album_audio_id="
        val legacySearchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$nameEnc&hash=$hash&timelength=$durationMs&lrctxt=1"
        val first = runCatching {
            JSONObject(http.getText(searchUrl, headers)).optJSONArray("candidates")?.optJSONObject(0)
        }.getOrNull() ?: runCatching {
            JSONObject(http.getText(legacySearchUrl, headers)).optJSONArray("candidates")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val id = first.opt("id")?.toString() ?: return null
        val accessKey = first.optString("accesskey").ifEmpty { return null }

        // KRC is the native word-timed format; decode it before falling back to LRC.
        val krcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=krc&charset=utf8"
        val krcJson = runCatching { JSONObject(http.getText(krcUrl, headers)) }.getOrNull()
        if (krcJson?.optInt("status") == 200) {
            val decoded = decodeKrc(krcJson.optString("content"))
            val timed = decoded?.let { LyricParser.parse(it) }.orEmpty()
            if (timed.any { it.words.isNotEmpty() }) return timed
        }

        val lrcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val lrcJson = runCatching { JSONObject(http.getText(lrcUrl, headers)) }.getOrNull() ?: return null
        if (lrcJson.optInt("status") != 200) return null
        val lrc = decodeB64(lrcJson.optString("content")) ?: return null
        return LyricParser.parse(lrc).ifEmpty { null }
    }

    private fun decodeKrc(b64: String): String? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        require(bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "krc1")
        val key = byteArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206.toByte(), 210.toByte(), 110, 105)
        val encrypted = bytes.copyOfRange(4, bytes.size)
        val xor = ByteArray(encrypted.size) { i ->
            (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        InflaterInputStream(ByteArrayInputStream(xor)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()?.ifEmpty { null }

    private fun decodeB64(b64: String): String? =
        runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.ifEmpty { null }
}
