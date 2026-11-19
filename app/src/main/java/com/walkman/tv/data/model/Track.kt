package com.walkman.tv.data.model

import kotlinx.serialization.Serializable

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
    /** Dynamic custom-provider key; null means this is a legacy built-in track. */
    val sourceKey: String? = null,
    /** Display label from the imported script; null keeps legacy built-in naming. */
    val sourceDisplayName: String? = null,
    /** Raw provider quality identifiers, including tiers unknown to this app version. */
    val qualityKeys: List<String> = emptyList(),
) {
    val subtitle: String
        get() = if (!albumName.isNullOrEmpty()) "$singer · $albumName" else singer

    /** mm:ss display of [duration]. */
    val intervalText: String
        get() = duration?.let { String.format("%02d:%02d", it / 60, it % 60) } ?: ""

    /** Whether the source carries an MV for this track (set by per-platform search/board builders). */
    val providerKey: String get() = sourceKey ?: source.key

    /** Actual built-in provider, null for custom script platforms even when legacy source is LOCAL. */
    val builtInSource: SourceID? get() = if (sourceKey != null) {
        ScriptSourceKey.from(sourceKey)?.builtIn
    } else {
        source
    }

    /** Human-readable provider label; custom providers fall back to their declared key. */
    val providerDisplayName: String get() = sourceDisplayName?.takeIf { it.isNotBlank() }
        ?: sourceKey?.let { ScriptSourceKey.from(it)?.displayName }
        ?: source.displayName

    val hasMv: Boolean
        get() = !extras["mvId"].isNullOrEmpty()

    companion object {
        fun makeID(source: SourceID, songmid: String): String = "${source.key}_$songmid"
        fun makeID(sourceKey: String, songmid: String): String = "${sourceKey}_$songmid"
    }
}
