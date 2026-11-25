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

    @Test
    fun usesWordContextForOneToManyCharacters() {
        assertEquals("我的頭髮很長", ChineseText.toTaiwanTraditional("我的头发很长"))
        assertEquals("理髮店", ChineseText.toTaiwanTraditional("理发店"))
        assertEquals("髮型設計", ChineseText.toTaiwanTraditional("发型设计"))
        assertEquals("染髮和燙髮", ChineseText.toTaiwanTraditional("染发和烫发"))
    }

    @Test
    fun keepsTheVerbMeaningOfFaAs發() {
        assertEquals("發展與發現", ChineseText.toTaiwanTraditional("发展与发现"))
    }
}
