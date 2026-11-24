package com.walkman.tv.data.model

import kotlinx.serialization.Serializable
import com.walkman.tv.util.ChineseText

/**
 * A playable song, mirroring iOS `Track`. [extras] carries source-specific fields the v4 JS
 * scripts expect inside musicInfo (e.g. Kugou `hash`, NetEase `mvId`, QQ `strMediaMid`).
 */
@Serializable
data class Track(
    val id: String,
    val name: String,
    val singer: String,
    val albumName: String? = null,
    val albumId: String? = null,
    val source: SourceID,
    val songmid: String,
    val duration: Int? = null,
    val picURL: String? = null,
    val qualities: List<Quality> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
) {
    /** Taiwan Traditional Chinese display values; raw provider fields remain untouched. */
    val displayName: String get() = ChineseText.toTaiwanTraditional(name)
    val displaySinger: String get() = ChineseText.toTaiwanTraditional(singer)
    val displayAlbumName: String? get() = albumName?.let(ChineseText::toTaiwanTraditional)

    val subtitle: String
        get() = if (!displayAlbumName.isNullOrEmpty()) "$displaySinger · $displayAlbumName" else displaySinger

    /** mm:ss display of [duration]. */
    val intervalText: String
        get() = duration?.let { String.format("%02d:%02d", it / 60, it % 60) } ?: ""

    /** Whether the source carries an MV for this track (set by per-platform search/board builders). */
    val hasMv: Boolean
        get() = !extras["mvId"].isNullOrEmpty()

    companion object {
        fun makeID(source: SourceID, songmid: String): String = "${source.key}_$songmid"
    }
}
