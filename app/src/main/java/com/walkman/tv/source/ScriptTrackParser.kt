package com.walkman.tv.source

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Normalizes common song-search response shapes while preserving provider-specific metadata. */
object ScriptTrackParser {
    private val rowKeys = listOf("list", "songs", "tracks", "musicList", "musiclist", "data", "result")

    fun parseSearch(raw: Any?, source: ScriptSourceKey, displayName: String): List<Track> {
        val rows = findRows(raw) ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { parseTrack(it, source, displayName) }
        }.distinctBy { it.id }
    }

    private fun findRows(raw: Any?): JSONArray? {
        if (raw is JSONArray) return raw
        val root = raw as? JSONObject ?: return null
        for (key in rowKeys) root.optJSONArray(key)?.let { return it }
        for (key in listOf("data", "result", "response")) {
            val nested = root.optJSONObject(key) ?: continue
            for (arrayKey in rowKeys) nested.optJSONArray(arrayKey)?.let { return it }
            nested.optJSONObject("data")?.let { inner ->
                for (arrayKey in rowKeys) inner.optJSONArray(arrayKey)?.let { return it }
            }
        }
        return null
    }

    private fun parseTrack(item: JSONObject, source: ScriptSourceKey, displayName: String): Track? {
        val songId = firstText(item, "songmid", "id", "mid", "musicId", "music_id", "hash", "rid")
            ?: return null
        val name = firstText(item, "name", "songName", "title", "musicName") ?: return null
        val singer = firstText(item, "singer", "artist", "author", "singerName")
            ?: item.optJSONArray("artists")?.let(::joinNames)
            ?: item.optJSONArray("singers")?.let(::joinNames)
            ?: item.optJSONObject("artist")?.let { firstText(it, "name", "title") }
            ?: "未知歌手"
        val albumObject = item.optJSONObject("album")
        val album = firstText(item, "albumName", "albumname", "albumTitle")
            ?: albumObject?.let { firstText(it, "name", "title") }
            ?: item.opt("album")?.takeIf { it !is JSONObject && it !is JSONArray }?.toString()?.takeIf { it.isNotBlank() }
        val albumId = firstText(item, "albumId", "albumid", "albumMid")
            ?: albumObject?.let { firstText(it, "id", "mid") }
        val image = firstText(item, "img", "picURL", "pic", "cover", "coverUrl", "albumPic")
            ?: albumObject?.let { firstText(it, "picUrl", "cover", "img") }
        val duration = durationSeconds(item)
        val qualityKeys = parseQualityKeys(item)
        val extras = mutableMapOf<String, String>()
        item.optJSONObject("extras")?.let { obj ->
            obj.keys().forEach { key ->
                scalarText(obj.opt(key))?.let { extras[key] = it }
            }
        }
        listOf("hash", "mvId", "strMediaMid", "media_mid", "copyrightId", "albumId", "albumid")
            .forEach { key -> firstText(item, key)?.let { extras[key] = it } }

        return Track(
            id = Track.makeID(source.value, songId),
            name = name,
            singer = singer,
            albumName = album,
            albumId = albumId,
            source = source.builtIn ?: SourceID.LOCAL,
            songmid = songId,
            duration = duration,
            picURL = image,
            qualities = qualityKeys.mapNotNull(Quality::fromKey),
            extras = extras,
            sourceKey = source.value,
            sourceDisplayName = displayName,
            qualityKeys = qualityKeys,
        )
    }

    private fun durationSeconds(item: JSONObject): Int? {
        val ms = firstNumber(item, "durationMs", "duration_ms", "dt")
        if (ms != null && ms > 0) return (ms / 1000L).toInt().takeIf { it > 0 }
        val seconds = firstNumber(item, "interval", "duration", "durationSec", "seconds", "time")
            ?: return null
        return seconds.toInt().takeIf { it > 0 }
    }

    private fun firstNumber(item: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            val value = item.opt(key) ?: continue
            val number = when (value) {
                is Number -> value.toLong()
                else -> value.toString().toLongOrNull()
            }
            if (number != null && number > 0) return number
        }
        return null
    }

    private fun parseQualityKeys(item: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        for (key in listOf("types", "qualitys", "qualities", "quality")) {
            when (val raw = item.opt(key)) {
                is JSONArray -> for (i in 0 until raw.length()) {
                    val entry = raw.opt(i)
                    val value = if (entry is JSONObject) firstText(entry, "type", "key", "name", "quality") else scalarText(entry)
                    addQuality(result, value)
                }
                is JSONObject -> raw.keys().forEach { tier ->
                    addQuality(result, tier)
                    val nested = raw.opt(tier)
                    if (nested is JSONObject) addQuality(result, firstText(nested, "type", "key", "name"))
                }
                is String -> raw.split(',', '|', ';').forEach { addQuality(result, it) }
            }
        }
        return result.toList()
    }

    private fun addQuality(target: MutableSet<String>, raw: String?) {
        val key = raw?.trim()?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) } ?: return
        target += key
    }

    private fun firstText(item: JSONObject, vararg keys: String): String? {
        for (key in keys) scalarText(item.opt(key))?.let { return it }
        return null
    }

    private fun scalarText(value: Any?): String? = when (value) {
        null, JSONObject.NULL, is JSONObject, is JSONArray -> null
        else -> value.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun joinNames(array: JSONArray): String =
        (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                is JSONObject -> firstText(value, "name", "title", "artist")
                else -> scalarText(value)
            }
        }.joinToString("、").ifBlank { "未知歌手" }
}
