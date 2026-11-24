package com.walkman.tv.data.model

import com.walkman.tv.util.ChineseText

/** A leaderboard entry (iOS `BoardInfo`). [bangid] is the platform-side id. */
data class BoardInfo(
    val id: String,
    val source: SourceID,
    val bangid: String,
    val name: String,
    val picURL: String? = null,
) {
    val displayName: String get() = ChineseText.toTaiwanTraditional(name)
}
