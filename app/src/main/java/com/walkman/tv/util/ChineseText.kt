package com.walkman.tv.util

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts source-provided Chinese text to Taiwan Traditional Chinese for presentation.
 * Raw provider values remain unchanged in the data layer and identifiers/search requests.
 */
object ChineseText {
    fun toTaiwanTraditional(value: String): String =
        if (value.isEmpty()) value else ZhTwConverterUtil.toTraditional(value)
}
