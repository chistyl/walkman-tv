package com.walkman.tv.source

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptSourceKey
import com.walkman.tv.data.model.SonglistDetail
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes common script-provider songlist payloads without imposing one provider's API shape.
 * The script contract remains: songlist action + { action, data }; this parser accepts common
 * aliases used by music source scripts and preserves the dynamic provider key on every result.
 */
object ScriptSonglistParser {
    private val listArrayKeys = listOf("playlists", "songlists", "lists", "list", "data", "result")
    private val trackArrayKeys = listOf("tracks", "songs", "musicList", "musiclist", "list", "data", "result")

    fun parseLists(raw: Any?, source: ScriptSourceKey, displayName: String): List<SonglistInfo> {
        val rows = findArray(raw, listArrayKeys) ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            parseInfo(item, source, displayName)
        }.distinctBy { it.id }
    }

    fun parseDetail(
        raw: Any?,
        requested: SonglistInfo,
        source: ScriptSourceKey,
        displayName: String,
    ): SonglistDetail? {
        val root = unwrapObject(raw) ?: return null
        val infoObject = sequenceOf("playlist", "songlist", "detail", "data", "result")
            .mapNotNull { root.optJSONObject(it) }
            .firstOrNull() ?: root
        val info = parseInfo(infoObject, source, displayName)
            ?: requested.copy(sourceKey = source.value, sourceDisplayName = displayName)
        val trackArray = findArray(root, trackArrayKeys)
            ?: findArray(infoObject, trackArrayKeys)
            ?: return null
        val tracks = (0 until trackArray.length()).mapNotNull { index ->
            val item = trackArray.optJSONObject(index) ?: return@mapNotNull null
            parseTrack(item, source, displayName)
        }.distinctBy { it.id }
        return SonglistDetail(info, tracks)
    }

    private fun findArray(raw: Any?, keys: List<String>): JSONArray? {
        if (raw is JSONArray) return raw
        val root = raw as? JSONObject ?: return null
        for (key in keys) root.optJSONArray(key)?.let { return it }
        for (key in listOf("data", "result", "playlist", "songlist", "detail")) {
            val nested = root.optJSONObject(key) ?: continue
            for (arrayKey in keys) nested.optJSONArray(arrayKey)?.let { return it }
        }
        return null
    }

    private fun unwrapObject(raw: Any?): JSONObject? {
        val root = raw as? JSONObject ?: return null
        return sequenceOf("data", "result", "playlist", "songlist", "detail")
            .mapNotNull { root.optJSONObject(it) }
            .firstOrNull() ?: root
    }

    private fun parseInfo(
        item: JSONObject,
        source: ScriptSourceKey,
        displayName: String,
    ): SonglistInfo? {
        val id = firstText(item, "id", "playlistId", "listId", "dissid", "pid", "songlistId")
            ?: return null
        val name = firstText(item, "name", "title", "playlistName", "dissname") ?: "未命名歌單"
        val author = firstText(item, "author", "nickname", "creatorName", "username")
            ?: item.optJSONObject("creator")?.let { firstText(it, "nickname", "name") }.orEmpty()
        val image = firstText(item, "picURL", "pic", "img", "cover", "coverImgUrl", "coverUrl")
        val count = firstText(item, "trackCount", "songCount", "total", "count")?.toIntOrNull()
        val playCount = firstText(item, "playCount", "playcnt", "play_count")
        return SonglistInfo(
            id = id,
            source = source.builtIn ?: SourceID.LOCAL,
            name = name,
            author = author,
            picURL = image,
            trackCount = count,
            playCount = playCount,
            sourceKey = source.value,
            sourceDisplayName = displayName,
        )
    }

    private fun parseTrack(
        item: JSONObject,
        source: ScriptSourceKey,
        displayName: String,
    ): Track? {
        val songId = firstText(item, "songmid", "id", "mid", "musicId", "hash") ?: return null
        val name = firstText(item, "name", "songName", "title") ?: return null
        val singer = firstText(item, "singer", "artist", "artists", "author")
            ?: item.optJSONArray("singers")?.let(::joinNames)
            ?: "未知歌手"
        val album = firstText(item, "albumName", "album", "albumname")
        val albumId = firstText(item, "albumId", "albumid")
        val image = firstText(item, "picURL", "img", "pic", "cover", "coverUrl")
        val durationRaw = firstText(item, "interval", "duration", "durationSec", "durationMs")
            ?.toLongOrNull()
        val duration = durationRaw?.let { if (it > 100_000) (it / 1000).toInt() else it.toInt() }
            ?.takeIf { it > 0 }
        val qualityArray = item.optJSONArray("types") ?: item.optJSONArray("qualitys")
            ?: item.optJSONArray("qualities")
        val qualityKeys = buildList {
            if (qualityArray != null) for (i in 0 until qualityArray.length()) {
                val value = qualityArray.opt(i)
                val key = if (value is JSONObject) firstText(value, "type", "key", "name") else value?.toString()
                if (!key.isNullOrBlank() && key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) add(key)
            }
            val qualityObject = item.optJSONObject("types") ?: item.optJSONObject("qualitys")
            qualityObject?.keys()?.forEach { key ->
                if (key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) add(key)
            }
        }.distinct()
        val extras = mutableMapOf<String, String>()
        item.optJSONObject("extras")?.let { obj ->
            obj.keys().forEach { key -> obj.opt(key)?.toString()?.takeIf { it.isNotBlank() }?.let { extras[key] = it } }
        }
        listOf("hash", "mvId", "strMediaMid", "media_mid", "copyrightId").forEach { key ->
            firstText(item, key)?.let { extras[key] = it }
        }
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

    private fun firstText(item: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = item.opt(key) ?: continue
            val text = when (value) {
                is JSONObject, is JSONArray, JSONObject.NULL -> ""
                else -> value.toString()
            }.trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return null
    }

    private fun joinNames(array: JSONArray): String =
        (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is JSONObject -> firstText(item, "name", "title", "artist")
                null, JSONObject.NULL -> null
                else -> item.toString().takeIf { it.isNotBlank() }
            }
        }.joinToString("、").ifBlank { "未知歌手" }
}
