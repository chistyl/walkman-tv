package com.walkman.tv.util

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts source-provided Chinese text to Taiwan Traditional Chinese for presentation.
 *
 * Provider values are kept untouched in the data layer. Conversion is presentation-only.
 * Context-sensitive source phrases are normalized before OpenCC4j so one-to-many characters
 * such as simplified "发" are resolved by the whole word rather than by a character mapping.
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
        "吹风机吹发" to "吹風機吹髮"
    )

    fun toTaiwanTraditional(value: String): String {
        if (value.isEmpty()) return value
        val contextNormalized = sourceContextCorrections.entries.fold(value) { text, (source, target) ->
            text.replace(source, target)
        }
        return ZhTwConverterUtil.toTraditional(contextNormalized)
    }
}
