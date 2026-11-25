package com.walkman.tv.util

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts source-provided Chinese text to Taiwan Traditional Chinese for presentation.
 *
 * Provider values are kept untouched in the data layer. Conversion is presentation-only.
 * OpenCC4j performs the general Simplified-to-Taiwan-Traditional conversion; the correction
 * table below resolves common one-to-many characters that require word context.
 */
object ChineseText {
    private val contextCorrections = linkedMapOf(
        "頭發" to "頭髮",
        "理發" to "理髮",
        "發型" to "髮型",
        "發質" to "髮質",
        "發絲" to "髮絲",
        "發梢" to "髮梢",
        "發根" to "髮根",
        "發尾" to "髮尾",
        "發量" to "髮量",
        "發色" to "髮色",
        "發際線" to "髮際線",
        "發廊" to "髮廊",
        "發膠" to "髮膠",
        "發蠟" to "髮蠟",
        "發夾" to "髮夾",
        "發圈" to "髮圈",
        "染發" to "染髮",
        "燙發" to "燙髮",
        "剪發" to "剪髮",
        "護發" to "護髮",
        "洗發" to "洗髮",
        "吹風機吹發" to "吹風機吹髮",
    )

    fun toTaiwanTraditional(value: String): String {
        if (value.isEmpty()) return value
        val converted = ZhTwConverterUtil.toTraditional(value)
        return contextCorrections.entries.fold(converted) { text, (wrong, correct) ->
            text.replace(wrong, correct)
        }
    }
}
