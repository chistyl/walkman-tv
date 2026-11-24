package com.walkman.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseTextTest {
    @Test
    fun convertsSimplifiedChineseToTaiwanTraditional() {
        assertEquals("網際網路", ChineseText.toTaiwanTraditional("互联网"))
        assertEquals("軟體", ChineseText.toTaiwanTraditional("软件"))
        assertEquals("音樂", ChineseText.toTaiwanTraditional("音乐"))
    }
}
