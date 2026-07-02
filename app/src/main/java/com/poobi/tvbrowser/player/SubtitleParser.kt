package com.poobi.tvbrowser.player

import java.io.BufferedReader
import java.io.StringReader
import java.util.regex.Pattern

data class SubtitleCue(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

object SubtitleParser {
    private val TIME_PATTERN = Pattern.compile("(\\d+):(\\d+):(\\d+)[.,](\\d+)")

    fun parse(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = BufferedReader(StringReader(content))
        var line: String?
        var state = 0 
        var index = 0
        var startTimeMs = 0L
        var endTimeMs = 0L
        val textBuilder = StringBuilder()

        try {
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) {
                    if (state == 2 && textBuilder.isNotEmpty()) {
                        cues.add(SubtitleCue(index, startTimeMs, endTimeMs, textBuilder.toString().trim()))
                        textBuilder.setLength(0)
                    }
                    state = 0
                    continue
                }

                when (state) {
                    0 -> {
                        if (trimmed.contains("-->")) {
                            if (parseTimeLine(trimmed)) {
                                startTimeMs = tempStart
                                endTimeMs = tempEnd
                                state = 2
                            }
                        } else {
                            index = trimmed.toIntOrNull() ?: 0
                            state = 1
                        }
                    }
                    1 -> {
                        if (trimmed.contains("-->")) {
                            if (parseTimeLine(trimmed)) {
                                startTimeMs = tempStart
                                    endTimeMs = tempEnd
                                state = 2
                            }
                        } else {
                            state = 0
                        }
                    }
                    2 -> {
                        if (textBuilder.isNotEmpty()) {
                            textBuilder.append("\n")
                        }
                        textBuilder.append(trimmed)
                    }
                }
            }
            if (state == 2 && textBuilder.isNotEmpty()) {
                cues.add(SubtitleCue(index, startTimeMs, endTimeMs, textBuilder.toString().trim()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cues
    }

    private var tempStart = 0L
    private var tempEnd = 0L

    private fun parseTimeLine(line: String): Boolean {
        val parts = line.split("-->")
        if (parts.size != 2) return false
        tempStart = parseTimestamp(parts[0].trim()) ?: return false
        tempEnd = parseTimestamp(parts[1].trim().split(" ")[0]) ?: return false
        return true
    }

    private fun parseTimestamp(timeStr: String): Long? {
        val matcher = TIME_PATTERN.matcher(timeStr)
        if (!matcher.find()) return null
        val hours = matcher.group(1)!!.toLong()
        val minutes = matcher.group(2)!!.toLong()
        val seconds = matcher.group(3)!!.toLong()
        val millisStr = matcher.group(4)!!
        val millis = millisStr.padEnd(3, '0').substring(0, 3).toLong()
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
    }
}