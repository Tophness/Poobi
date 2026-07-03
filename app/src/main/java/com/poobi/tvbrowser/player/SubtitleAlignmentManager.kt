package com.poobi.tvbrowser.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SubtitleAlignmentManager(private val context: Context) {
    private val _cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val cues: StateFlow<List<SubtitleCue>> = _cues.asStateFlow()

    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private val _activeCue = MutableStateFlow<SubtitleCue?>(null)
    val activeCue: StateFlow<SubtitleCue?> = _activeCue.asStateFlow()

    private val _isUIVisible = MutableStateFlow(false)
    val isUIVisible: StateFlow<Boolean> = _isUIVisible.asStateFlow()

    private val _isLooping = MutableStateFlow(false)
    val isLooping: StateFlow<Boolean> = _isLooping.asStateFlow()

    var loopStartMs = 0L
    var loopEndMs = 0L

    var baselineOffsetMs = 0L
    var originalUnalignedCueTimeMs = 0L
    var showDottedFromLine = false
    var lastAdjustmentMsg = ""

    private var activeSubtitleUrl: String? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loopMonitoringJob: Job? = null

    fun loadSubtitles(url: String) {
        if (url == activeSubtitleUrl) return
        activeSubtitleUrl = url
        _cues.value = emptyList()
        _offsetMs.value = 0L
        lastAdjustmentMsg = ""
        showDottedFromLine = false

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val content = if (url.startsWith("file://")) {
                    val path = Uri.parse(url).path ?: url.removePrefix("file://")
                    File(path).readText()
                } else {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                val parsed = SubtitleParser.parse(content)
                withContext(Dispatchers.Main) {
                    _cues.value = parsed
                }
            } catch (e: Exception) {
                Log.e("SubtitleAlignment", "Failed to load subtitle file: ${e.message}")
            }
        }
    }

    fun updateActiveCue(positionMs: Long) {
        val adjustedPos = positionMs - _offsetMs.value
        val currentCues = _cues.value
        val match = currentCues.find { adjustedPos in it.startTimeMs..it.endTimeMs }
        _activeCue.value = match
    }

    fun findAndJumpToCue(player: ExoPlayer, query: String) {
        val currentPos = player.currentPosition
        val currentCues = _cues.value
        if (currentCues.isEmpty() || query.isBlank()) return

        var bestCue: SubtitleCue? = null
        var bestDistance = Long.MAX_VALUE

        for (cue in currentCues) {
            if (cue.text.contains(query, ignoreCase = true)) {
                val distance = Math.abs(cue.startTimeMs - currentPos)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestCue = cue
                }
            }
        }

        bestCue?.let { cue ->
            val nativeStart = cue.startTimeMs
            val deltaOffset = currentPos - nativeStart
            _offsetMs.value = deltaOffset

            originalUnalignedCueTimeMs = nativeStart + baselineOffsetMs
            showDottedFromLine = true

            val secondsDiff = deltaOffset / 1000f
            lastAdjustmentMsg = String.format("Auto-aligned offset by %.2fs (%+dms)", secondsDiff, deltaOffset)

            player.seekTo(nativeStart + deltaOffset)
            startLoop(player, cue)
        }
    }

    fun startLoop(player: ExoPlayer, targetCue: SubtitleCue) {
        loopStartMs = targetCue.startTimeMs + _offsetMs.value
        
        val nextCue = _cues.value.find { it.startTimeMs > targetCue.endTimeMs }
        val dynamicEnd = if (nextCue != null && (nextCue.startTimeMs - targetCue.endTimeMs) > 1000) {
            nextCue.startTimeMs
        } else {
            targetCue.endTimeMs + 1500
        }

        val loopLength = (dynamicEnd - targetCue.startTimeMs).coerceIn(2000L, 8000L)
        loopEndMs = loopStartMs + loopLength
        player.playWhenReady = true
        _isLooping.value = true
        loopMonitoringJob?.cancel()
        loopMonitoringJob = coroutineScope.launch(Dispatchers.Main) {
            while (_isLooping.value) {
                val pos = player.currentPosition
                updateActiveCue(pos)
                if (pos >= loopEndMs) {
                    player.seekTo(loopStartMs)
                }
                delay(20)
            }
        }
    }

    fun stopLoop() {
        _isLooping.value = false
        loopMonitoringJob?.cancel()
        loopMonitoringJob = null
    }

    fun shiftOffset(deltaMs: Long) {
        _offsetMs.value += deltaMs
    }

    fun setOffset(offset: Long) {
        _offsetMs.value = offset
        lastAdjustmentMsg = ""
        showDottedFromLine = false
    }

    fun confirmChanges() {
        baselineOffsetMs = _offsetMs.value
        hideUI()
    }

    fun cancelChanges() {
        _offsetMs.value = baselineOffsetMs
        hideUI()
    }

    fun showUI() {
        baselineOffsetMs = _offsetMs.value
        _isUIVisible.value = true
    }

    fun hideUI() {
        _isUIVisible.value = false
        stopLoop()
    }
}