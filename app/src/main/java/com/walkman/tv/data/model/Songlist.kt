package com.walkman.tv.data.model

/** A playlist/歌单 summary (iOS `SonglistInfo`). [id] is the platform-side playlist id. */
data class SonglistInfo(
    val id: String,
    /** Legacy built-in enum placeholder; custom providers keep their real key in [sourceKey]. */
    val source: SourceID,
    val name: String,
    val author: String = "",
    val picURL: String? = null,
    val trackCount: Int? = null,
    val playCount: String? = null,
    /** Actual provider key for script-backed songlists; null preserves legacy built-in behavior. */
    val sourceKey: String? = null,
    /** Display label supplied by the imported script. */
    val sourceDisplayName: String? = null,
) {
    val providerKey: String get() = sourceKey ?: source.key
    val builtInSource: SourceID? get() = if (sourceKey != null) {
        ScriptSourceKey.from(sourceKey)?.builtIn
    } else source
    val providerDisplayName: String get() = sourceDisplayName?.takeIf { it.isNotBlank() }
        ?: sourceKey?.let { ScriptSourceKey.from(it)?.displayName }
        ?: source.displayName
}

data class SonglistDetail(
    val info: SonglistInfo,
    val tracks: List<Track>,
)

/** A platform sort option (id = API value, name = label). */
data class SonglistOrder(val id: String, val name: String)

/** A single filter tag (歌單分類). id == "" ⇒ 全部. */
data class SonglistTag(val id: String, val name: String) {
    companion object {
        val ALL = SonglistTag("", "全部")
    }
}

/** A named group of tags (熱門/語種/風格/場景…). */
data class SonglistTagGroup(val name: String, val tags: List<SonglistTag>)
