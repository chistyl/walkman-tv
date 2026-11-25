package com.walkman.tv.playback

import com.walkman.tv.util.ChineseText

/** Word-level timing in milliseconds on the media timeline. */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
)

/** A single lyric line. [time] is seconds; -1 means untimed plain text. */
data class LyricLine(
    val id: Int,
    val time: Double,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
)

/** Parses standard LRC and native YRC, KRC and QRC word-timing records. */
object LyricParser {
    private val timeTag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val enhancedLine = Regex("""^\[([0-9]{1,12})[,，]([0-9]{1,12})](.*)$""")
    // NetEase YRC and Kugou KRC: (or <)absoluteStart,duration,flag( or >)text
    private val yrcKrcWord = Regex("""[<(]([0-9]{1,12})[,，]([0-9]{1,12})[,，]-?[0-9]{1,12}[>)]([^<(]*)""")
    // QQ QRC: text(absoluteStart,duration); the tuple timestamps the text before it.
    private val qrcWord = Regex("""([^()]*)\(([0-9]{1,12})[,，]([0-9]{1,12})\)""")

    fun parse(raw: String, translation: String? = null): List<LyricLine> {
        val enhanced = parseEnhanced(raw)
        if (enhanced.isNotEmpty()) {
            val trans = translation?.let { parseLines(it) }.orEmpty()
            val transMap = trans.associate { (it.time * 100).toInt() to it.text }
            return enhanced.mapIndexed { index, line ->
                line.copy(id = index, translation = transMap[(line.time * 100).toInt()])
            }
        }

        val main = parseLines(raw)
        val trans = translation?.let { parseLines(it) } ?: emptyList()
        val transMap = trans.associate { (it.time * 100).toInt() to it.text }
        val lines = main.mapIndexed { index, line ->
            LyricLine(index, line.time, line.text, transMap[(line.time * 100).toInt()])
        }.toMutableList()

        if (lines.isEmpty()) {
            raw.replace("\r\n", "\n").replace("\r", "\n")
                .split("\n").filter { it.isNotBlank() }
                .forEach { lines.add(LyricLine(lines.size, -1.0, ChineseText.toTaiwanTraditional(it.trim()))) }
        }
        return lines
    }

    private fun parseEnhanced(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val normalised = raw.replace("\r\n", "\n").replace("\r", "\n")
        for (rawLine in normalised.split("\n")) {
            val header = enhancedLine.find(rawLine.trim()) ?: continue
            val lineStart = header.groupValues[1].toLongOrNull() ?: continue
            val body = header.groupValues[3]

            val yrcMatches = yrcKrcWord.findAll(body).toList()
            val words = if (yrcMatches.isNotEmpty()) {
                // YRC/KRC word starts are already absolute media timestamps; do not add lineStart.
                yrcMatches.mapNotNull { match ->
                    val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val text = match.groupValues[3]
                    if (text.isEmpty()) null else LyricWord(
                        ChineseText.toTaiwanTraditional(text),
                        start,
                        duration.coerceAtLeast(1),
                    )
                }
            } else {
                // QRC stores each word's absolute timing after the word itself.
                qrcWord.findAll(body).mapNotNull { match ->
                    val text = match.groupValues[1]
                    val start = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val duration = match.groupValues[3].toLongOrNull() ?: return@mapNotNull null
                    if (text.isEmpty()) null else LyricWord(
                        ChineseText.toTaiwanTraditional(text),
                        start,
                        duration.coerceAtLeast(1),
                    )
                }.toList()
            }

            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }
            result.add(LyricLine(-1, lineStart / 1000.0, text, words = words))
        }
        return result.sortedBy { it.time }
    }

    private data class Parsed(val time: Double, val text: String)

    private fun parseLines(raw: String): List<Parsed> {
        val out = mutableListOf<Parsed>()
        val normalised = raw.replace("\r\n", "\n").replace("\r", "\n")
        for (rawLine in normalised.split("\n")) {
            if (rawLine.isBlank()) continue
            val matches = timeTag.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val last = matches.last()
            val text = rawLine.substring(last.range.last + 1).trim()
            for (m in matches) {
                val mins = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val secs = m.groupValues[2].toDoubleOrNull() ?: 0.0
                val fraction = m.groupValues.getOrNull(3).orEmpty()
                val millis = when (fraction.length) {
                    1 -> fraction.toIntOrNull()?.times(100) ?: 0
                    2 -> fraction.toIntOrNull()?.times(10) ?: 0
                    else -> fraction.take(3).toIntOrNull() ?: 0
                }
                out.add(Parsed(mins * 60 + secs + millis / 1000.0, ChineseText.toTaiwanTraditional(text)))
            }
        }
        return out.sortedBy { it.time }
    }

    fun activeIndex(time: Double, lines: List<LyricLine>): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].time <= time) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
