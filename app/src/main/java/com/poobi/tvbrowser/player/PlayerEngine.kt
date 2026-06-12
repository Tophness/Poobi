package com.poobi.tvbrowser.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.chaquo.python.Python
import com.poobi.tvbrowser.shared.SubtitleData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class PlayerEngine(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onPlaybackError: (PlaybackException, String) -> Unit,
    private val onPlaybackStarted: (String, String, JSONObject, Int?, Int?, Map<String, String>, Map<String, Map<String, String>>) -> Unit = { _, _, _, _, _, _, _ -> },
    private val onUpNextTriggered: () -> Unit,
    private val onVideoEnded: () -> Unit,
    private val onPlayerReleased: () -> Unit = {},
    private val onVideoWatched: (Int, Int) -> Unit = { _, _ -> }
) {
    var exoPlayer: ExoPlayer? = null
        private set

    private val _isPlayerActive = MutableStateFlow(false)
    val isPlayerActive: StateFlow<Boolean> = _isPlayerActive.asStateFlow()

    private val _showUpNext = MutableStateFlow(false)
    val showUpNext: StateFlow<Boolean> = _showUpNext.asStateFlow()

    private val _nextEpisodeData = MutableStateFlow<JSONObject?>(null)
    val nextEpisodeData: StateFlow<JSONObject?> = _nextEpisodeData.asStateFlow()

    private var lastVideoTitle: String? = null
    private var lastVideoUrl: String? = null
    private var lastScrapedItem: JSONObject? = null
    private var lastScrapedSeason: Int? = null
    private var lastScrapedEpisode: Int? = null
    private var lastSubtitles: Map<String, Map<String, String>> = emptyMap()
    var playerView: PlayerView? = null
    private var hasReachedReady = false
    
    private var hasTriggeredPlaybackStarted = false

    private var lastSeekTime = 0L
    private var seekIncrement = 5000L
    private val checkUpNextHandler = Handler(Looper.getMainLooper())
    var isUpNextDismissed = false

    private var isReleasing = false

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val checkUpNextRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                val pos = player.currentPosition
                val dur = player.duration
                val threshold = getUpNextThreshold()
                val remaining = dur - pos

                if (dur > 0 && lastScrapedSeason != null && !isUpNextDismissed && !_showUpNext.value) {
                    if (remaining <= threshold) {
                        if (_nextEpisodeData.value != null) {
                            _showUpNext.value = true
                            onUpNextTriggered()
                        }
                    }
                }
            }
            checkUpNextHandler.postDelayed(this, 1000)
        }
    }

    private fun getUpNextThreshold(): Long {
        return try {
            prefs.getInt("up_next_time_pref", 20) * 1000L
        } catch (e: Exception) {
            20000L
        }
    }

    fun setNextEpisode(data: JSONObject?) {
        _nextEpisodeData.value = data
    }

    fun dismissUpNext() {
        _showUpNext.value = false
        isUpNextDismissed = true
    }

    fun launchVideo(
        videoUrl: String, 
        title: String?, 
        headers: Map<String, String>, 
        subtitles: Map<String, Map<String, String>>,
        item: JSONObject?,
        season: Int?,
        episode: Int?,
        fromStreams: Boolean = true
    ) {
        saveProgress()

        lastVideoUrl = videoUrl
        lastVideoTitle = title
        lastScrapedItem = item
        lastScrapedSeason = season
        lastScrapedEpisode = episode
        lastSubtitles = subtitles
        isUpNextDismissed = false
        hasReachedReady = false
        hasTriggeredPlaybackStarted = false
        _showUpNext.value = false
        _isPlayerActive.value = true
        isReleasing = false

        exoPlayer?.release()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(prefs.getString("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"))
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (isReleasing || !_isPlayerActive.value) return
                if (state == Player.STATE_READY) {
                    hasReachedReady = true
                    checkUpNextHandler.post(checkUpNextRunnable)
                    
                    val title = lastVideoTitle ?: "Unknown"
                    if (!hasTriggeredPlaybackStarted) {
                        hasTriggeredPlaybackStarted = true
                        lastScrapedItem?.let { item ->
                            onPlaybackStarted(videoUrl, title, item, lastScrapedSeason, lastScrapedEpisode, headers, lastSubtitles)
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    checkUpNextHandler.removeCallbacks(checkUpNextRunnable)
                    try {
                        if (prefs.getString("up_next_popup_pref", "Ask") == "Always") {
                            onVideoEnded()
                        }
                    } catch (e: Exception) {}
                } else {
                    checkUpNextHandler.removeCallbacks(checkUpNextRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isReleasing || !_isPlayerActive.value) return
                onPlaybackError(error, videoUrl + (if (fromStreams) "|from_streams" else ""))
            }
        })

        val mediaItemBuilder = MediaItem.Builder().setUri(videoUrl)
        val subtitleConfigs = subtitles.map { (subUrl, infoMap) ->
            val mimeType = when {
                subUrl.contains(".vtt") -> MimeTypes.TEXT_VTT
                subUrl.contains(".ass") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val label = infoMap["label"] ?: getLanguageInfo(subUrl).second
            val lang = if (label.isNotEmpty() && label != "Unknown") null else (infoMap["lang"] ?: getLanguageInfo(subUrl).first)

            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeType)
                .setLanguage(lang)
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()

            config
        }

        mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        exoPlayer?.setMediaItem(mediaItemBuilder.build())

        val resumeKey = if (title != null) "resume_stream_$title" else null
        if (resumeKey != null) {
            val savedPos = prefs.getLong(resumeKey, 0L)
            if (savedPos > 5000L) {
                exoPlayer?.seekTo(savedPos)
            }
        }

        if (!prefs.getBoolean("embedded_subs_enabled", true)) {
            exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters?.buildUpon()
                ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                ?.build() ?: exoPlayer!!.trackSelectionParameters
        }

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    fun seekVideo(direction: Int, repeatCount: Int) {
        val player = exoPlayer ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSeekTime < 150) return 

        lastSeekTime = currentTime
        seekIncrement = (5000L + (repeatCount * repeatCount) * 100L).coerceAtMost(300000L)

        val newPos = player.currentPosition + (direction * seekIncrement)
        val duration = if (player.duration > 0) player.duration else 0L
        player.seekTo(newPos.coerceIn(0, duration))
    }

    fun addSubtitlesBatch(newSubs: List<SubtitleData>) {
        val player = exoPlayer ?: return
        val currentMediaItem = player.currentMediaItem ?: return

        val currentConfigs = currentMediaItem.localConfiguration?.subtitleConfigurations.orEmpty().toMutableList()

        for (sub in newSubs) {
            if (currentConfigs.any { it.uri.toString() == sub.url }) {
                continue
            }

            val mimeType = when {
                sub.url.contains(".vtt") -> MimeTypes.TEXT_VTT
                sub.url.contains(".ass") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }

            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(mimeType)
                .setLanguage(if (sub.label.isEmpty()) sub.lang else null)
                .setLabel(sub.label.ifEmpty { getLanguageInfo(sub.url).second })
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
            
            currentConfigs.add(subConfig)
        }

        val newMediaItem = currentMediaItem.buildUpon()
            .setSubtitleConfigurations(currentConfigs)
            .build()
        
        val currentPos = player.currentPosition
        val wasPlaying = player.playWhenReady
        
        player.setMediaItem(newMediaItem, false)
        player.seekTo(currentPos)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    fun stopAndRelease() {
        if (!_isPlayerActive.value || isReleasing) return
        isReleasing = true
        saveProgress()
        checkUpNextHandler.removeCallbacks(checkUpNextRunnable)
        playerScope.coroutineContext.cancelChildren() // Cancel active progress tasks
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.e("PlayerEngine", "Error releasing player", e)
        }
        exoPlayer = null
        _isPlayerActive.value = false
        onPlayerReleased()
        isReleasing = false
    }

    private fun saveProgress() {
        val player = exoPlayer
        if (player != null && player.playerError == null && hasReachedReady) {
            val title = lastVideoTitle
            val resumeKey = if (title != null) "resume_stream_$title" else null

            if (resumeKey != null) {
                val pos = player.currentPosition
                val dur = player.duration
                val threshold = getUpNextThreshold()
                val isCompleted = pos >= (dur - threshold)
                if (dur > 0) {
                    if (isCompleted) {
                        prefs.edit().remove(resumeKey).apply()
                        val season = lastScrapedSeason
                        val episode = lastScrapedEpisode
                        if (season != null && episode != null) {
                            onVideoWatched(season, episode)
                        }
                        lastScrapedItem?.let { item ->
                            @Suppress("OPT_IN_USAGE")
                            playerScope.launch {
                                try {
                                    val py = Python.getInstance()
                                    val scrobbler = py.getModule("trakt.trakt_scrobble")
                                    scrobbler.callAttr("scrobble", item.toString(), lastScrapedSeason, lastScrapedEpisode, 100.0)
                                } catch (e: Exception) {
                                    Log.e("TVBrowser", "Scrobble failed: ${e.message}")
                                }
                            }
                        }
                    } else if (pos > 5000L) {
                        prefs.edit().putLong(resumeKey, pos).apply()
                    }
                }
            }
        }
    }

    private fun getLanguageInfo(url: String): Pair<String, String> {
        val uri = Uri.parse(url)
        val fileName = uri.lastPathSegment?.lowercase() ?: ""
        val langMap = mapOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "zh" to "Chinese",
            "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic", "hi" to "Hindi",
            "tr" to "Turkish", "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian"
        )
        var code = "und"
        var label = "Unknown"

        for ((c, name) in langMap) {
            if (fileName.contains(name.lowercase()) || fileName.contains("-$c.") || fileName.contains("_$c.") || fileName.contains(".$c.") || fileName.startsWith("$c.")) {
                code = c
                label = name
                if (fileName.contains("sdh")) label += " (SDH)"
                if (fileName.contains("forced")) label += " (Forced)"
                return Pair(code, label)
            }
        }
        val cleanName = fileName.substringBeforeLast(".").replace(Regex("[-_]"), " ").trim()
        if (cleanName.length > 2 && !cleanName.all { it.isDigit() || it == ' ' }) {
            return Pair(code, cleanName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
        }
        return Pair(code, label)
    }
}