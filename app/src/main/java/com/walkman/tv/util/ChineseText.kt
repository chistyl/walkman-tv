package com.walkman.tv.util

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts source-provided Chinese text to Taiwan Traditional Chinese for presentation.
 *
 * Provider values are kept untouched in the data layer. Conversion is presentation-only.
 * Context-sensitive source phrases are normalized before OpenCC4j so ambiguous characters
 * such as simplified "发" and "范" are resolved by the whole word where possible.
 */
object ChineseText {
    private val sourceContextCorrections = linkedMapOf(
        "头发" to "頭髮",
        "理发" to "理髮",
        "发型" to "髮型",
        "发质" to "髮質",
        "发丝" to "髮絲",
        "发梢" to "髮梢",
        "发根" to "髮根",
        "发尾" to "髮尾",
        "发量" to "髮量",
        "发色" to "髮色",
        "发际线" to "髮際線",
        "发廊" to "髮廊",
        "发胶" to "髮膠",
        "发蜡" to "髮蠟",
        "发夹" to "髮夾",
        "发圈" to "髮圈",
        "染发" to "染髮",
        "烫发" to "燙髮",
        "剪发" to "剪髮",
        "护发" to "護髮",
        "洗发" to "洗髮",
        "吹风机吹发" to "吹風機吹髮",
        "范围" to "範圍",
        "模范" to "模範",
        "规范" to "規範",
        "范例" to "範例",
        "范本" to "範本",
        "范式" to "範式",
        "范畴" to "範疇",
        "范文" to "範文",
        "防范" to "防範",
        "示范" to "示範",
        "典范" to "典範",
        "风范" to "風範"
    )

    // Protect remaining 范, including surnames and personal names, from character-level conversion.
    private const val PRESERVE_FAN = "\uE000"

    fun toTaiwanTraditional(value: String): String {
        if (value.isEmpty()) return value
        val contextNormalized = sourceContextCorrections.entries.fold(value) { text, (source, target) ->
            text.replace(source, target)
        }
        val protected = contextNormalized.replace("范", PRESERVE_FAN)
        return ZhTwConverterUtil.toTraditional(protected).replace(PRESERVE_FAN, "范")
    }
}
