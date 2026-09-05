package com.poobi.tvbrowser.player

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.io.BufferedReader
import java.io.StringReader

data class SubtitleCue(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val rawText: String = text,
    val annotatedString: AnnotatedString = SubtitleFormatter.format(rawText).second
)

object SubtitleFormatter {

    fun format(rawText: String): Pair<String, AnnotatedString> {
        if (rawText.isBlank()) {
            return Pair("", AnnotatedString(""))
        }

        var normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")

        val plainBuilder = StringBuilder()
        val italicRanges = mutableListOf<Pair<Int, Int>>()
        val boldRanges = mutableListOf<Pair<Int, Int>>()
        val underlineRanges = mutableListOf<Pair<Int, Int>>()
        val strikeRanges = mutableListOf<Pair<Int, Int>>()
        val colorRanges = mutableListOf<Triple<Int, Int, Color>>()

        val italicStack = mutableListOf<Int>()
        val boldStack = mutableListOf<Int>()
        val underlineStack = mutableListOf<Int>()
        val strikeStack = mutableListOf<Int>()
        val colorStack = mutableListOf<Pair<Int, Color>>()

        var i = 0
        val len = normalized.length

        while (i < len) {
            val c = normalized[i]

            if (c == '<') {
                val closeIdx = normalized.indexOf('>', i)
                if (closeIdx != -1) {
                    val tagContent = normalized.substring(i + 1, closeIdx).trim()
                    i = closeIdx + 1
                    processHtmlTag(
                        tag = tagContent,
                        currentPos = plainBuilder.length,
                        italicStack = italicStack,
                        boldStack = boldStack,
                        underlineStack = underlineStack,
                        strikeStack = strikeStack,
                        colorStack = colorStack,
                        italicRanges = italicRanges,
                        boldRanges = boldRanges,
                        underlineRanges = underlineRanges,
                        strikeRanges = strikeRanges,
                        colorRanges = colorRanges
                    )
                    continue
                } else {
                    plainBuilder.append(c)
                    i++
                }
            } else if (c == '{') {
                val closeIdx = normalized.indexOf('}', i)
                if (closeIdx != -1) {
                    val tagContent = normalized.substring(i + 1, closeIdx).trim()
                    i = closeIdx + 1
                    processAssTag(
                        tag = tagContent,
                        currentPos = plainBuilder.length,
                        italicStack = italicStack,
                        boldStack = boldStack,
                        underlineStack = underlineStack,
                        strikeStack = strikeStack,
                        colorStack = colorStack,
                        italicRanges = italicRanges,
                        boldRanges = boldRanges,
                        underlineRanges = underlineRanges,
                        strikeRanges = strikeRanges,
                        colorRanges = colorRanges
                    )
                    continue
                } else {
                    plainBuilder.append(c)
                    i++
                }
            } else if (c == '&') {
                val semiIdx = normalized.indexOf(';', i)
                if (semiIdx != -1 && semiIdx - i <= 10) {
                    val entity = normalized.substring(i, semiIdx + 1)
                    val decoded = decodeHtmlEntity(entity)
                    if (decoded != null) {
                        plainBuilder.append(decoded)
                        i = semiIdx + 1
                        continue
                    }
                }
                plainBuilder.append(c)
                i++
            } else {
                plainBuilder.append(c)
                i++
            }
        }

        val totalLength = plainBuilder.length
        while (italicStack.isNotEmpty()) italicRanges.add(Pair(italicStack.removeAt(italicStack.lastIndex), totalLength))
        while (boldStack.isNotEmpty()) boldRanges.add(Pair(boldStack.removeAt(boldStack.lastIndex), totalLength))
        while (underlineStack.isNotEmpty()) underlineRanges.add(Pair(underlineStack.removeAt(underlineStack.lastIndex), totalLength))
        while (strikeStack.isNotEmpty()) strikeRanges.add(Pair(strikeStack.removeAt(strikeStack.lastIndex), totalLength))
        while (colorStack.isNotEmpty()) {
            val top = colorStack.removeAt(colorStack.lastIndex)
            colorRanges.add(Triple(top.first, totalLength, top.second))
        }

        val plainText = plainBuilder.toString()
        if (plainText.isEmpty()) {
            return Pair("", AnnotatedString(""))
        }

        val charItalic = BooleanArray(plainText.length)
        val charBold = BooleanArray(plainText.length)
        val charUnderline = BooleanArray(plainText.length)
        val charStrike = BooleanArray(plainText.length)
        val charColor = arrayOfNulls<Color>(plainText.length)

        for ((start, end) in italicRanges) {
            val s = start.coerceIn(0, plainText.length)
            val e = end.coerceIn(0, plainText.length)
            for (idx in s until e) charItalic[idx] = true
        }

        for ((start, end) in boldRanges) {
            val s = start.coerceIn(0, plainText.length)
            val e = end.coerceIn(0, plainText.length)
            for (idx in s until e) charBold[idx] = true
        }

        for ((start, end) in underlineRanges) {
            val s = start.coerceIn(0, plainText.length)
            val e = end.coerceIn(0, plainText.length)
            for (idx in s until e) charUnderline[idx] = true
        }

        for ((start, end) in strikeRanges) {
            val s = start.coerceIn(0, plainText.length)
            val e = end.coerceIn(0, plainText.length)
            for (idx in s until e) charStrike[idx] = true
        }

        for ((start, end, color) in colorRanges) {
            val s = start.coerceIn(0, plainText.length)
            val e = end.coerceIn(0, plainText.length)
            for (idx in s until e) charColor[idx] = color
        }

        val builder = AnnotatedString.Builder(plainText)

        var runStart = 0
        while (runStart < plainText.length) {
            val it = charItalic[runStart]
            val b = charBold[runStart]
            val u = charUnderline[runStart]
            val s = charStrike[runStart]
            val c = charColor[runStart]

            var runEnd = runStart + 1
            while (runEnd < plainText.length &&
                charItalic[runEnd] == it &&
                charBold[runEnd] == b &&
                charUnderline[runEnd] == u &&
                charStrike[runEnd] == s &&
                charColor[runEnd] == c
            ) {
                runEnd++
            }

            val decorations = mutableListOf<TextDecoration>()
            if (u) decorations.add(TextDecoration.Underline)
            if (s) decorations.add(TextDecoration.LineThrough)

            val textDecoration = when {
                decorations.size > 1 -> TextDecoration.combine(decorations)
                decorations.size == 1 -> decorations[0]
                else -> null
            }

            val spanStyle = SpanStyle(
                fontStyle = if (it) FontStyle.Italic else null,
                fontWeight = if (b) FontWeight.Bold else null,
                textDecoration = textDecoration,
                color = c ?: Color.Unspecified
            )

            if (it || b || textDecoration != null || c != null) {
                builder.addStyle(spanStyle, runStart, runEnd)
            }

            runStart = runEnd
        }

        return Pair(plainText, builder.toAnnotatedString())
    }

    private fun processHtmlTag(
        tag: String,
        currentPos: Int,
        italicStack: MutableList<Int>,
        boldStack: MutableList<Int>,
        underlineStack: MutableList<Int>,
        strikeStack: MutableList<Int>,
        colorStack: MutableList<Pair<Int, Color>>,
        italicRanges: MutableList<Pair<Int, Int>>,
        boldRanges: MutableList<Pair<Int, Int>>,
        underlineRanges: MutableList<Pair<Int, Int>>,
        strikeRanges: MutableList<Pair<Int, Int>>,
        colorRanges: MutableList<Triple<Int, Int, Color>>
    ) {
        if (tag.startsWith("/")) {
            when (tag.substring(1).trim().lowercase()) {
                "i", "em" -> if (italicStack.isNotEmpty()) italicRanges.add(Pair(italicStack.removeAt(italicStack.lastIndex), currentPos))
                "b", "strong" -> if (boldStack.isNotEmpty()) boldRanges.add(Pair(boldStack.removeAt(boldStack.lastIndex), currentPos))
                "u", "ins" -> if (underlineStack.isNotEmpty()) underlineRanges.add(Pair(underlineStack.removeAt(underlineStack.lastIndex), currentPos))
                "s", "strike", "del" -> if (strikeStack.isNotEmpty()) strikeRanges.add(Pair(strikeStack.removeAt(strikeStack.lastIndex), currentPos))
                "font" -> if (colorStack.isNotEmpty()) {
                    val top = colorStack.removeAt(colorStack.lastIndex)
                    colorRanges.add(Triple(top.first, currentPos, top.second))
                }
                "c" -> {
                    if (colorStack.isNotEmpty()) {
                        val top = colorStack.removeAt(colorStack.lastIndex)
                        colorRanges.add(Triple(top.first, currentPos, top.second))
                    }
                    if (boldStack.isNotEmpty()) boldRanges.add(Pair(boldStack.removeAt(boldStack.lastIndex), currentPos))
                    if (italicStack.isNotEmpty()) italicRanges.add(Pair(italicStack.removeAt(italicStack.lastIndex), currentPos))
                    if (underlineStack.isNotEmpty()) underlineRanges.add(Pair(underlineStack.removeAt(underlineStack.lastIndex), currentPos))
                }
            }
        } else {
            val lower = tag.lowercase()
            when {
                lower == "i" || lower.startsWith("i ") || lower == "em" || lower.startsWith("em ") -> {
                    italicStack.add(currentPos)
                }
                lower == "b" || lower.startsWith("b ") || lower == "strong" || lower.startsWith("strong ") -> {
                    boldStack.add(currentPos)
                }
                lower == "u" || lower.startsWith("u ") || lower == "ins" || lower.startsWith("ins ") -> {
                    underlineStack.add(currentPos)
                }
                lower == "s" || lower.startsWith("s ") || lower == "strike" || lower.startsWith("strike ") || lower == "del" || lower.startsWith("del ") -> {
                    strikeStack.add(currentPos)
                }
                lower.startsWith("font") -> {
                    val colorMatch = Regex("color=[\"']?([^\"'\\s>]+)[\"']?", RegexOption.IGNORE_CASE).find(tag)
                    if (colorMatch != null) {
                        val parsed = parseColorString(colorMatch.groupValues[1])
                        if (parsed != null) {
                            colorStack.add(Pair(currentPos, parsed))
                        }
                    }
                }
                lower.startsWith("c.") || lower == "c" -> {
                    val classes = lower.removePrefix("c.").split(".")
                    for (cls in classes) {
                        when (cls) {
                            "b", "bold" -> boldStack.add(currentPos)
                            "i", "italic" -> italicStack.add(currentPos)
                            "u", "underline" -> underlineStack.add(currentPos)
                            else -> {
                                val parsed = parseColorString(cls)
                                if (parsed != null) {
                                    colorStack.add(Pair(currentPos, parsed))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processAssTag(
        tag: String,
        currentPos: Int,
        italicStack: MutableList<Int>,
        boldStack: MutableList<Int>,
        underlineStack: MutableList<Int>,
        strikeStack: MutableList<Int>,
        colorStack: MutableList<Pair<Int, Color>>,
        italicRanges: MutableList<Pair<Int, Int>>,
        boldRanges: MutableList<Pair<Int, Int>>,
        underlineRanges: MutableList<Pair<Int, Int>>,
        strikeRanges: MutableList<Pair<Int, Int>>,
        colorRanges: MutableList<Triple<Int, Int, Color>>
    ) {
        val clean = tag.replace("\\", " \\")
        val parts = clean.split("\\s+".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }

        for (part in parts) {
            when {
                part.startsWith("\\i1") -> italicStack.add(currentPos)
                part.startsWith("\\i0") -> if (italicStack.isNotEmpty()) italicRanges.add(Pair(italicStack.removeAt(italicStack.lastIndex), currentPos))
                part.matches(Regex("\\\\b[1-9]\\d*")) -> boldStack.add(currentPos)
                part.startsWith("\\b0") -> if (boldStack.isNotEmpty()) boldRanges.add(Pair(boldStack.removeAt(boldStack.lastIndex), currentPos))
                part.startsWith("\\u1") -> underlineStack.add(currentPos)
                part.startsWith("\\u0") -> if (underlineStack.isNotEmpty()) underlineRanges.add(Pair(underlineStack.removeAt(underlineStack.lastIndex), currentPos))
                part.startsWith("\\s1") -> strikeStack.add(currentPos)
                part.startsWith("\\s0") -> if (strikeStack.isNotEmpty()) strikeRanges.add(Pair(strikeStack.removeAt(strikeStack.lastIndex), currentPos))
                part.startsWith("\\c&H") || part.startsWith("\\1c&H") -> {
                    val colorHex = part.substringAfter("&H").substringBefore("&")
                    val parsed = parseAssColor(colorHex)
                    if (parsed != null) {
                        colorStack.add(Pair(currentPos, parsed))
                    }
                }
                part.startsWith("\\r") -> {
                    if (italicStack.isNotEmpty()) italicRanges.add(Pair(italicStack.removeAt(italicStack.lastIndex), currentPos))
                    if (boldStack.isNotEmpty()) boldRanges.add(Pair(boldStack.removeAt(boldStack.lastIndex), currentPos))
                    if (underlineStack.isNotEmpty()) underlineRanges.add(Pair(underlineStack.removeAt(underlineStack.lastIndex), currentPos))
                    if (strikeStack.isNotEmpty()) strikeRanges.add(Pair(strikeStack.removeAt(strikeStack.lastIndex), currentPos))
                    if (colorStack.isNotEmpty()) {
                        val top = colorStack.removeAt(colorStack.lastIndex)
                        colorRanges.add(Triple(top.first, currentPos, top.second))
                    }
                }
            }
        }
    }

    private fun parseColorString(raw: String): Color? {
        val clean = raw.trim().lowercase().removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
        if (clean.isEmpty()) return null

        val namedColors = mapOf(
            "yellow" to Color(0xFFFFFF00),
            "cyan" to Color(0xFF00FFFF),
            "aqua" to Color(0xFF00FFFF),
            "white" to Color(0xFFFFFFFF),
            "black" to Color(0xFF000000),
            "red" to Color(0xFFFF2222),
            "green" to Color(0xFF4CAF50),
            "lime" to Color(0xFF00FF00),
            "blue" to Color(0xFF2196F3),
            "magenta" to Color(0xFFFF00FF),
            "fuchsia" to Color(0xFFFF00FF),
            "orange" to Color(0xFFFF9800),
            "gold" to Color(0xFFFFD700),
            "pink" to Color(0xFFFF4081),
            "gray" to Color(0xFF9E9E9E),
            "grey" to Color(0xFF9E9E9E),
            "silver" to Color(0xFFC0C0C0),
            "purple" to Color(0xFF9C27B0),
            "teal" to Color(0xFF009688),
            "navy" to Color(0xFF000080),
            "maroon" to Color(0xFF800000),
            "olive" to Color(0xFF808000),
            "brown" to Color(0xFF795548)
        )

        namedColors[clean]?.let { return it }

        try {
            val hex = clean.removePrefix("#")
            when (hex.length) {
                3 -> {
                    val r = hex[0]
                    val g = hex[1]
                    val b = hex[2]
                    return Color("FF$r$r$g$g$b$b".toLong(16))
                }
                6 -> return Color("FF$hex".toLong(16))
                8 -> return Color(hex.toLong(16))
            }
        } catch (_: Exception) {}

        if (clean.startsWith("rgb")) {
            try {
                val parts = clean.substringAfter("(").substringBefore(")").split(",")
                if (parts.size >= 3) {
                    val r = parts[0].trim().toInt()
                    val g = parts[1].trim().toInt()
                    val b = parts[2].trim().toInt()
                    val a = if (parts.size >= 4) (parts[3].trim().toFloat() * 255).toInt() else 255
                    return Color(r, g, b, a)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun parseAssColor(raw: String): Color? {
        try {
            var clean = raw.trim().uppercase()
                .removePrefix("&H").removePrefix("&h")
                .removeSuffix("&").removeSuffix("&H").removeSuffix("&h")
            clean = clean.padStart(6, '0')
            if (clean.length == 6) {
                val bb = clean.substring(0, 2).toInt(16)
                val gg = clean.substring(2, 4).toInt(16)
                val rr = clean.substring(4, 6).toInt(16)
                return Color(rr, gg, bb, 255)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decodeHtmlEntity(entity: String): String? {
        val clean = entity.lowercase()
        return when (clean) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&apos;", "&#39;", "&#039;" -> "'"
            "&nbsp;", "&#160;" -> " "
            "&copy;" -> "©"
            "&reg;" -> "®"
            "&trade;" -> "™"
            "&hellip;" -> "…"
            "&ndash;" -> "–"
            "&mdash;" -> "—"
            "&lsquo;" -> "‘"
            "&rsquo;" -> "’"
            "&ldquo;" -> "“"
            "&rdquo;" -> "”"
            "&bull;" -> "•"
            "&iexcl;" -> "¡"
            "&iquest;" -> "¿"
            "&deg;" -> "°"
            "&plusmn;" -> "±"
            "&times;" -> "×"
            "&divide;" -> "÷"
            "&cent;" -> "¢"
            "&pound;" -> "£"
            "&yen;" -> "¥"
            "&euro;" -> "€"
            else -> {
                if (clean.startsWith("&#x") && clean.endsWith(";")) {
                    try {
                        val code = clean.substring(3, clean.length - 1).toInt(16)
                        code.toChar().toString()
                    } catch (_: Exception) { null }
                } else if (clean.startsWith("&#") && clean.endsWith(";")) {
                    try {
                        val code = clean.substring(2, clean.length - 1).toInt()
                        code.toChar().toString()
                    } catch (_: Exception) { null }
                } else null
            }
        }
    }
}

object SubtitleParser {

    fun parse(content: String): List<SubtitleCue> {
        val cleanContent = content.removePrefix("\uFEFF")
        val cues = mutableListOf<SubtitleCue>()
        val reader = BufferedReader(StringReader(cleanContent))
        var line: String?
        var index = 0
        var startTimeMs = 0L
        var endTimeMs = 0L
        val textBuilder = StringBuilder()
        var state = 0

        try {
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line!!
                val trimmed = rawLine.trim()

                if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                    if (state == 2 && textBuilder.isNotEmpty()) {
                        val rawCueText = textBuilder.toString().trim()
                        if (rawCueText.isNotEmpty()) {
                            val (plain, annotated) = SubtitleFormatter.format(rawCueText)
                            cues.add(SubtitleCue(index, startTimeMs, endTimeMs, plain, rawCueText, annotated))
                        }
                        textBuilder.setLength(0)
                        state = 0
                    }

                    val parts = trimmed.substringAfter(":").trim().split(",", limit = 10)
                    if (parts.size >= 10) {
                        val start = parseTimestamp(parts[1].trim())
                        val end = parseTimestamp(parts[2].trim())
                        val text = parts[9].trim()
                        if (start != null && end != null && text.isNotEmpty()) {
                            index++
                            val (plain, annotated) = SubtitleFormatter.format(text)
                            cues.add(SubtitleCue(index, start, end, plain, text, annotated))
                        }
                    }
                    continue
                }

                if (trimmed.startsWith("WEBVTT", ignoreCase = true) ||
                    trimmed.startsWith("NOTE", ignoreCase = true) ||
                    trimmed.startsWith("STYLE", ignoreCase = true) ||
                    trimmed.startsWith("REGION", ignoreCase = true)) {
                    continue
                }

                if (trimmed.isEmpty()) {
                    if (state == 2 && textBuilder.isNotEmpty()) {
                        val rawCueText = textBuilder.toString().trim()
                        if (rawCueText.isNotEmpty()) {
                            val (plain, annotated) = SubtitleFormatter.format(rawCueText)
                            cues.add(SubtitleCue(index, startTimeMs, endTimeMs, plain, rawCueText, annotated))
                        }
                        textBuilder.setLength(0)
                    }
                    state = 0
                    continue
                }

                when (state) {
                    0 -> {
                        if (trimmed.contains("-->")) {
                            val times = parseTimeLine(trimmed)
                            if (times != null) {
                                index++
                                startTimeMs = times.first
                                endTimeMs = times.second
                                state = 2
                            }
                        } else if (trimmed.all { it.isDigit() }) {
                            index = trimmed.toIntOrNull() ?: (index + 1)
                            state = 1
                        }
                    }
                    1 -> {
                        if (trimmed.contains("-->")) {
                            val times = parseTimeLine(trimmed)
                            if (times != null) {
                                startTimeMs = times.first
                                endTimeMs = times.second
                                state = 2
                            } else {
                                state = 0
                            }
                        } else {
                            state = 0
                        }
                    }
                    2 -> {
                        if (textBuilder.isNotEmpty()) {
                            textBuilder.append("\n")
                        }
                        textBuilder.append(rawLine)
                    }
                }
            }

            if (state == 2 && textBuilder.isNotEmpty()) {
                val rawCueText = textBuilder.toString().trim()
                if (rawCueText.isNotEmpty()) {
                    val (plain, annotated) = SubtitleFormatter.format(rawCueText)
                    cues.add(SubtitleCue(index, startTimeMs, endTimeMs, plain, rawCueText, annotated))
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleParser", "Error parsing subtitle content", e)
        }

        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseTimeLine(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val startStr = parts[0].trim().split("\\s+".toRegex())[0]
        val endStr = parts[1].trim().split("\\s+".toRegex())[0]
        val start = parseTimestamp(startStr) ?: return null
        val end = parseTimestamp(endStr) ?: return null
        return Pair(start, end)
    }

    private fun parseTimestamp(timeStr: String): Long? {
        val clean = timeStr.trim().replace(',', '.')
        val parts = clean.split(":")
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val secParts = parts[2].split(".")
                val seconds = secParts[0].toLongOrNull() ?: return null
                val millis = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val secParts = parts[1].split(".")
                val seconds = secParts[0].toLongOrNull() ?: return null
                val millis = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                (minutes * 60 + seconds) * 1000 + millis
            }
            else -> null
        }
    }
}