package com.walkman.tv.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class ScriptTextDecoderTest {

    @Test
    fun stripsUtf8BomAndNormalizesWindowsLineEndings() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "/* @name Test */\r\nconst value = 1;\r".toByteArray(Charsets.UTF_8)

        assertEquals("/* @name Test */\nconst value = 1;\n", ScriptTextDecoder.decode(bytes))
    }

    @Test
    fun decodesUtf16LittleEndianAndBigEndianBomFiles() {
        val text = "/* @name 音源 */\r\nconst value = '音樂';"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)

        assertEquals(text.replace("\r\n", "\n"), ScriptTextDecoder.decode(le))
        assertEquals(text.replace("\r\n", "\n"), ScriptTextDecoder.decode(be))
    }

    @Test
    fun fallsBackToGb18030ForLegacyChineseScriptFiles() {
        val text = "/* @name 中文音源 */\nconst name = '歌曲';"
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(text, ScriptTextDecoder.decode(bytes))
    }

    @Test
    fun normalizesPastedOrDownloadedTextToo() {
        assertEquals(
            "/* @name Test */\nconst value = 1;",
            ScriptTextDecoder.normalize("\uFEFF/* @name Test */\r\nconst value = 1;"),
        )
    }

    @Test
    fun rejectsEmptyAndBinaryLikeInput() {
        assertNull(ScriptTextDecoder.decode(byteArrayOf()))
        assertNull(ScriptTextDecoder.decode(byteArrayOf(0x00, 0x01, 0x02)))
    }
}
