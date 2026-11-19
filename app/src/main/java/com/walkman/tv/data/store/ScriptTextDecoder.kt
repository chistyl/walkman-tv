package com.walkman.tv.data.store

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Decodes imported source files without leaving a BOM or inconsistent line endings for QuickJS. */
object ScriptTextDecoder {
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null

        val decoded = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                decodeStrict(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)

            else -> decodeStrict(bytes, StandardCharsets.UTF_8)
                ?: runCatching { String(bytes, CharsetHolder.GB18030) }.getOrNull()
        } ?: return null

        return normalize(decoded).takeIf { it.isNotBlank() && '\u0000' !in it }
    }

    /** Normalize text received from paste boxes and remote URLs as well as decoded files. */
    fun normalize(text: String): String = text
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String? =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private object CharsetHolder {
        val GB18030: java.nio.charset.Charset = java.nio.charset.Charset.forName("GB18030")
    }
}
