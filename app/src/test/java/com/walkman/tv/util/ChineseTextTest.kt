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
    fun convertsHairRelatedWordsByWordContext() {
        assertEquals("我的頭髮很長", ChineseText.toTaiwanTraditional("我的头发很长"))
        assertEquals("理髮店", ChineseText.toTaiwanTraditional("理发店"))
        assertEquals("髮型設計", ChineseText.toTaiwanTraditional("发型设计"))
        assertEquals("染髮和燙髮", ChineseText.toTaiwanTraditional("染发和烫发"))
        assertEquals("吹風機吹髮", ChineseText.toTaiwanTraditional("吹风机吹发"))
        assertEquals("頭髮與髮型", ChineseText.toTaiwanTraditional("頭髮與髮型"))
        assertEquals("髮質、髮絲、髮根與髮尾", ChineseText.toTaiwanTraditional("髮質、髮絲、髮根與髮尾"))
        assertEquals("頭髮不能變成頭发", ChineseText.toTaiwanTraditional("頭髮不能變成頭发"))
        assertEquals("髮如雪", ChineseText.toTaiwanTraditional("髮如雪"))
        assertEquals("髮如雪", ChineseText.toTaiwanTraditional("发如雪"))
    }

    @Test
    fun preservesFanSurnameButConvertsFanInCommonWords() {
        assertEquals("范先生", ChineseText.toTaiwanTraditional("范先生"))
        assertEquals("范冰冰", ChineseText.toTaiwanTraditional("范冰冰"))
        assertEquals("範圍", ChineseText.toTaiwanTraditional("范围"))
        assertEquals("模範", ChineseText.toTaiwanTraditional("模范"))
        assertEquals("規範", ChineseText.toTaiwanTraditional("规范"))
        assertEquals("範例與範本", ChineseText.toTaiwanTraditional("范例与范本"))
        assertEquals("防範與示範", ChineseText.toTaiwanTraditional("防范与示范"))
    }

    @Test
    fun keepsFaInDevelopmentAndDiscoveryAs發() {
        assertEquals("發展與發現", ChineseText.toTaiwanTraditional("发展与发现"))
        assertEquals("開發", ChineseText.toTaiwanTraditional("开发"))
        assertEquals("發生", ChineseText.toTaiwanTraditional("发生"))
    }

    @Test
    fun preservesEmptyInput() {
        assertEquals("", ChineseText.toTaiwanTraditional(""))
    }
}
