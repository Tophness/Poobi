package com.poobi.tvbrowser.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.PlayerView
import com.chaquo.python.Python
import com.poobi.tvbrowser.shared.SubtitleData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class QualityOption {
    abstract val name: String
    
    data class Native(
        override val name: String,
        val trackGroup: TrackGroup,
        val trackIndex: Int
    ) : QualityOption()

    data class DistinctUrl(
        override val name: String,
        val url: String
    ) : QualityOption()

    data class Auto(
        override val name: String = "Auto"
    ) : QualityOption()
}

class PlayerEngine(
    private val context: Context,
    val prefs: SharedPreferences,
    private val onPlaybackError: (PlaybackException, String) -> Unit,
    private val onPlaybackStarted: (String, String, JSONObject, Int?, Int?, Map<String, String>, Map<String, Map<String, String>>) -> Unit = { _, _, _, _, _, _, _ -> },
    private val onUpNextTriggered: () -> Unit,
    private val onVideoEnded: () -> Unit,
    private val onPlayerReleased: () -> Unit = {},
    private val onVideoWatched: (Int, Int) -> Unit = { _, _ -> }
) {
    private var isReleasing = false
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var exoPlayer: ExoPlayer? = null
        private set

    val audioWaveformCapturer = AudioWaveformCapturer()
    val subtitleAlignmentManager = SubtitleAlignmentManager(context)
    private val introDbManager = IntroDbManager(context, playerScope)

    @Volatile
    var subtitleOffsetUs: Long = 0L
        private set

    @OptIn(UnstableApi::class)
    private val subtitleParserFactory = object : SubtitleParser.Factory {
        private val delegate = DefaultSubtitleParserFactory()

        override fun supportsFormat(format: Format): Boolean {
            return delegate.supportsFormat(format)
        }

        override fun getCueReplacementBehavior(format: Format): Int {
            return delegate.getCueReplacementBehavior(format)
        }

        override fun create(format: Format): SubtitleParser {
            val parser = delegate.create(format)
            return object : SubtitleParser {
                override fun parse(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    outputOptions: SubtitleParser.OutputOptions,
                    output: Consumer<CuesWithTiming>
                ) {
                    val shiftingOutput = Consumer<CuesWithTiming> { cuesWithTiming ->
                        val shiftedStart = cuesWithTiming.startTimeUs + subtitleOffsetUs
                        val shiftedCuesWithTiming = CuesWithTiming(
                            cuesWithTiming.cues,
                            shiftedStart,
                            cuesWithTiming.durationUs
                        )
                        output.accept(shiftedCuesWithTiming)
                    }
                    parser.parse(data, offset, length, outputOptions, shiftingOutput)
                }

                override fun getCueReplacementBehavior(): Int {
                    return parser.cueReplacementBehavior
                }
            }
        }
    }

    private val _isPlayerActive = MutableStateFlow(false)
    val isPlayerActive: StateFlow<Boolean> = _isPlayerActive.asStateFlow()

    private val _showUpNext = MutableStateFlow(false)
    val showUpNext: StateFlow<Boolean> = _showUpNext.asStateFlow()

    private val _nextEpisodeData = MutableStateFlow<JSONObject?>(null)
    val nextEpisodeData: StateFlow<JSONObject?> = _nextEpisodeData.asStateFlow()

    private val _showQualitySelector = MutableStateFlow(false)
    val showQualitySelector: StateFlow<Boolean> = _showQualitySelector.asStateFlow()

    private val _qualityOptions = MutableStateFlow<List<QualityOption>>(emptyList())
    val qualityOptions: StateFlow<List<QualityOption>> = _qualityOptions.asStateFlow()

    private val _currentQuality = MutableStateFlow<QualityOption?>(null)
    val currentQuality: StateFlow<QualityOption?> = _currentQuality.asStateFlow()

    private val _isControllerVisible = MutableStateFlow(false)
    val isControllerVisible: StateFlow<Boolean> = _isControllerVisible.asStateFlow()

    private val _showDiskSubtitlePicker = MutableStateFlow(false)
    val showDiskSubtitlePicker: StateFlow<Boolean> = _showDiskSubtitlePicker.asStateFlow()

    var upNextFocusRequester: androidx.compose.ui.focus.FocusRequester? = null

    private var lastVideoTitle: String? = null
    private var lastVideoUrl: String? = null
    private var lastScrapedItem: JSONObject? = null
    private var lastScrapedSeason: Int? = null
    private var lastScrapedEpisode: Int? = null
    private var lastSubtitles: Map<String, Map<String, String>> = emptyMap()
    private var lastHeaders: Map<String, String> = emptyMap()
    private var lastFromStreams: Boolean = true
    private var lastAlternativeUrls: List<String> = emptyList()
    private var lastAlternativeNames: List<String> = emptyList()
    private var lastIsTrailer: Boolean = false
    val hasExternalSubtitles: Boolean get() = lastSubtitles.isNotEmpty()

    var playerView: PlayerView? = null
        set(value) {
            field = value
            value?.let {
                updateNativeSubtitleViewVisibility()
            }
        }

    private var hasReachedReady = false
    private var hasTriggeredPlaybackStarted = false

    private var lastSeekTime = 0L
    private var seekIncrement = 5000L
    private val checkUpNextHandler = Handler(Looper.getMainLooper())
    var isUpNextDismissed = false

    init {
        playerScope.launch {
            subtitleAlignmentManager.isUIVisible.collect { isVisible ->
                withContext(Dispatchers.Main) {
                    if (!isVisible) {
                        applySubtitleOffset(subtitleAlignmentManager.baselineOffsetMs)
                    }
                    updateNativeSubtitleViewVisibility()
                }
            }
        }
    }

    fun showDiskSubtitlePicker() {
        _showDiskSubtitlePicker.value = true
    }

    fun dismissDiskSubtitlePicker() {
        _showDiskSubtitlePicker.value = false
    }

    fun addSubtitlesFromDisk(files: List<File>, autoSelectSingle: Boolean = true) {
        if (files.isEmpty()) return
        val player = exoPlayer ?: return

        val newlyAddedSubData = mutableListOf<SubtitleData>()
        val newSubMap = lastSubtitles.toMutableMap()

        files.forEach { file ->
            val uri = Uri.fromFile(file).toString()
            val label = file.nameWithoutExtension
                .replace(Regex("_[0-9a-fA-F\\-]{36}"), "")
                .replace("_", " ")
            val lang = getLanguageInfo(file.name).first

            newSubMap[uri] = mapOf("label" to label, "lang" to lang)
            newlyAddedSubData.add(SubtitleData(url = uri, label = label, lang = lang))
        }
        lastSubtitles = newSubMap

        addSubtitlesBatch(newlyAddedSubData)

        if (files.size == 1 && autoSelectSingle) {
            val singleUri = Uri.fromFile(files[0]).toString()
            val label = newlyAddedSubData[0].label

            val renderingMode = prefs.getInt("subtitle_rendering_mode", 0)
            if (renderingMode == 1) {
                subtitleAlignmentManager.loadSubtitles(singleUri)
            } else {
                disableNativeSubtitles(false)
            }
            Toast.makeText(context, "Loaded & selected: $label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Added ${files.size} subtitles to captions menu", Toast.LENGTH_SHORT).show()
        }
    }

    fun applySubtitleOffset(offsetMs: Long) {
        val newOffsetUs = offsetMs * 1000L
        val renderingMode = prefs.getInt("subtitle_rendering_mode", 0)
        val offsetChanged = subtitleOffsetUs != newOffsetUs
        subtitleOffsetUs = newOffsetUs

        if (renderingMode == 0) {
            if (offsetChanged) {
                exoPlayer?.let { player ->
                    if (player.playbackState != Player.STATE_IDLE) {
                        val currentMediaItem = player.currentMediaItem
                        if (currentMediaItem != null) {
                            val currentPosition = player.currentPosition
                            val wasPlaying = player.playWhenReady
                            player.setMediaItem(currentMediaItem, false)
                            player.seekTo(currentPosition)
                            player.prepare()
                            player.playWhenReady = wasPlaying
                        }
                    }
                }
            }
        }
    }

    private fun restoreSubtitleVisibility() {
        val hasExternalSubs = lastSubtitles.isNotEmpty()
        if (hasExternalSubs) {
            disableNativeSubtitles(false)
        } else {
            val embeddedEnabled = prefs.getBoolean("embedded_subs_enabled", true)
            disableNativeSubtitles(!embeddedEnabled)
        }
    }

    fun updateNativeSubtitleViewVisibility() {
        val view = playerView ?: return
        val renderingMode = prefs.getInt("subtitle_rendering_mode", 0)
        val isSyncVisible = subtitleAlignmentManager.isUIVisible.value

        val shouldShow = when {
            isSyncVisible -> false
            renderingMode == 1 && hasExternalSubtitles -> false
            else -> true
        }
        view.subtitleView?.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun isHlsUrl(url: String): Boolean {
        val urlLower = url.lowercase()
        return urlLower.contains("m3u8") || 
               urlLower.contains("/hls/") || 
               urlLower.contains("/pl/") || 
               urlLower.contains("/playlist/")
    }

    private fun isDashUrl(url: String): Boolean {
        return url.lowercase().contains("mpd")
    }

    private fun sanitizeHeaders(videoUrl: String, headers: Map<String, String>): Map<String, String> {
        val mergedHeaders = headers.toMutableMap()
        
        if (isHlsUrl(videoUrl)) {
            val refKey = mergedHeaders.keys.find { it.equals("referer", ignoreCase = true) }
            val currentReferer = refKey?.let { mergedHeaders[it] }

            if (currentReferer.isNullOrEmpty()) {
                mergedHeaders.keys.filter { it.equals("referer", ignoreCase = true) }.forEach {
                    mergedHeaders.remove(it)
                }
                mergedHeaders["Referer"] = videoUrl
            }
        }
        return mergedHeaders
    }

    fun requestPlayPauseFocus() {
        val view = playerView ?: return
        view.post {
            view.showController()
            val playPauseId = try {
                androidx.media3.ui.R.id.exo_play_pause
            } catch (e: Throwable) {
                view.resources.getIdentifier("exo_play_pause", "id", "androidx.media3.ui")
            }
            val playPauseBtn = if (playPauseId != 0) view.findViewById<android.view.View>(playPauseId) else null
            if (playPauseBtn != null) {
                playPauseBtn.requestFocus()
            }
        }
    }

    fun disableNativeSubtitles(disable: Boolean) {
        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters?.buildUpon()
            ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
            ?.build() ?: exoPlayer!!.trackSelectionParameters
    }

    fun isTextTrackSelected(): Boolean {
        val player = exoPlayer ?: return false
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSelected) {
                return true
            }
        }
        return false
    }

    fun resetUpNextDismissed() {
        isUpNextDismissed = false
        _showUpNext.value = true
    }

    fun setControllerVisible(visible: Boolean) {
        _isControllerVisible.value = visible
    }

    fun updateQualityButtonText(textValue: String) {
        val view = playerView ?: return
        view.post {
            val basicControlsId = try {
                androidx.media3.ui.R.id.exo_basic_controls
            } catch (e: Throwable) {
                view.resources.getIdentifier("exo_basic_controls", "id", "androidx.media3.ui")
            }
            val basicControls = view.findViewById<android.widget.LinearLayout>(basicControlsId)
            val qualityBtn = basicControls?.findViewWithTag<android.widget.TextView>("exo_quality_button_tag")
            if (qualityBtn != null) {
                qualityBtn.text = textValue
            }
        }
    }

    private val checkUpNextRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                audioWaveformCapturer.currentPositionMs = player.currentPosition

                val pos = player.currentPosition
                val dur = player.duration

                val autoSkipEnabled = prefs.getBoolean("auto_skip_intros", true)
                if (autoSkipEnabled) {
                    introDbManager.checkAndSkipSegments(pos, player)
                }

                val creditsStart = introDbManager.getCreditsStartMs()
                val isAtEndZone = if (creditsStart != null) {
                    pos >= creditsStart
                } else {
                    val threshold = getUpNextThreshold()
                    val remaining = dur - pos
                    remaining <= threshold
                }

                if (dur > 0 && lastScrapedSeason != null && !isUpNextDismissed && !_showUpNext.value) {
                    if (isAtEndZone) {
                        if (_nextEpisodeData.value != null) {
                            _showUpNext.value = true
                            onUpNextTriggered()
                        }
                    }
                }
            }
            checkUpNextHandler.postDelayed(this, 100)
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

    fun showResolutionSelector() {
        _showQualitySelector.value = true
    }

    fun dismissQualitySelector() {
        _showQualitySelector.value = false
    }

    @OptIn(UnstableApi::class)
    fun launchVideo(
        videoUrl: String, 
        title: String?, 
        headers: Map<String, String>, 
        subtitles: Map<String, Map<String, String>>,
        item: JSONObject?,
        season: Int?,
        episode: Int?,
        fromStreams: Boolean = true,
        alternativeUrls: List<String> = emptyList(),
        alternativeNames: List<String> = emptyList(),
        initialPositionMs: Long = 0L,
        isTrailer: Boolean = false
    ) {
        if (initialPositionMs == 0L) {
            saveProgress()
        }

        val (cleanUrl, parsedHeaders) = com.poobi.tvbrowser.shared.parseKodiUrl(videoUrl)
        val mergedHeaders = headers.toMutableMap()
        mergedHeaders.putAll(parsedHeaders)
        val finalHeaders = sanitizeHeaders(cleanUrl, mergedHeaders)

        val uaKey = finalHeaders.keys.find { it.equals("user-agent", ignoreCase = true) }
        val activeUserAgent = uaKey?.let { finalHeaders[it] } 
            ?: prefs.getString("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

        lastVideoUrl = cleanUrl
        lastVideoTitle = title
        lastScrapedItem = item
        lastScrapedSeason = season
        lastScrapedEpisode = episode
        lastSubtitles = subtitles
        lastHeaders = finalHeaders
        lastFromStreams = fromStreams
        lastAlternativeUrls = alternativeUrls
        lastAlternativeNames = alternativeNames
        lastIsTrailer = isTrailer
        isUpNextDismissed = false
        hasReachedReady = false
        hasTriggeredPlaybackStarted = false
        _showUpNext.value = false
        _showQualitySelector.value = false
        _showDiskSubtitlePicker.value = false
        _qualityOptions.value = emptyList()
        _currentQuality.value = null
        _isPlayerActive.value = true
        isReleasing = false

        subtitleOffsetUs = 0L
        subtitleAlignmentManager.setOffset(0L)
        introDbManager.reset()

        exoPlayer?.release()

        val isLocalHost = cleanUrl.contains("localhost") || cleanUrl.contains("127.0.0.1")
        val connectTimeout = if (isLocalHost) 60000 else DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS
        val readTimeout = if (isLocalHost) 120000 else DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(activeUserAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(finalHeaders)
            .setConnectTimeoutMs(connectTimeout)
            .setReadTimeoutMs(readTimeout)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(20000000L)
            .build()

        val trackSelector = DefaultTrackSelector(context)
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(audioWaveformCapturer))
                    .build()
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setSubtitleParserFactory(subtitleParserFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (isReleasing || !_isPlayerActive.value) return
                if (state == Player.STATE_READY) {
                    hasReachedReady = true
                    checkUpNextHandler.post(checkUpNextRunnable)
                    
                    if (!introDbManager.hasFetchedTimestamps) {
                        val tmdbId = lastScrapedItem?.optInt("id") ?: 0
                        if (tmdbId > 0 && !lastIsTrailer) {
                            val mediaType = lastScrapedItem?.optString("media_type")
                                ?: if (lastScrapedItem?.has("name") == true) "tv" else "movie"
                            val isTv = mediaType == "tv" || mediaType == "tvshow"
                            introDbManager.fetchMediaTimestamps(
                                tmdbId, 
                                isTv, 
                                lastScrapedSeason, 
                                lastScrapedEpisode, 
                                exoPlayer?.duration ?: 0L
                            )
                        }
                    }
                    
                    val title = lastVideoTitle ?: "Unknown"
                    if (!hasTriggeredPlaybackStarted) {
                        hasTriggeredPlaybackStarted = true
                        if (!lastIsTrailer) {
                            lastScrapedItem?.let { item ->
                                onPlaybackStarted(videoUrl, title, item, lastScrapedSeason, lastScrapedEpisode, headers, lastSubtitles)
                            }
                        }
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (videoUrl.contains("localhost") || videoUrl.contains("127.0.0.1")) {
                        Toast.makeText(context, "Buffering torrent... Please wait", Toast.LENGTH_SHORT).show()
                    }
                } else if (state == Player.STATE_ENDED) {
                    checkUpNextHandler.removeCallbacks(checkUpNextRunnable)
                    try {
                        if (prefs.getString("up_next_popup_pref", "Ask") == "Always" && _nextEpisodeData.value != null) {
                            onVideoEnded()
                        }
                    } catch (e: Exception) {}
                } else {
                    checkUpNextHandler.removeCallbacks(checkUpNextRunnable)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (isReleasing || !_isPlayerActive.value) return
                updateQualityOptions(tracks)

                val activeQuality = _currentQuality.value
                if (activeQuality != null) {
                    val displayName = if (activeQuality is QualityOption.Native && activeQuality.name.contains(" ")) {
                        activeQuality.name.substringBefore(" ").uppercase()
                    } else {
                        activeQuality.name.uppercase()
                    }
                    updateQualityButtonText(displayName)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isReleasing || !_isPlayerActive.value) return
                Log.e("PlayerEngine", "onPlayerError occurred! Error code name: ${error.errorCodeName}, message: ${error.message}", error)
                Log.e("PlayerEngine", "Failed playing stream URL: $videoUrl")
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

            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeType)
                .setLanguage(lang)
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
        }

        if (videoUrl.contains("|")) {
            val urls = videoUrl.split("|")
            val videoUri = Uri.parse(urls[0])
            val audioUri = Uri.parse(urls[1])

            val videoMediaItemBuilder = MediaItem.Builder()
                .setUri(videoUri)
                .setSubtitleConfigurations(subtitleConfigs)

            if (isHlsUrl(urls[0])) {
                videoMediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            } else if (isDashUrl(urls[0])) {
                videoMediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            }

            val videoSource = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
                .createMediaSource(videoMediaItemBuilder.build())

            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUri))

            val mergedSource = MergingMediaSource(videoSource, audioSource)
            exoPlayer?.setMediaSource(mergedSource)
        } else {
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(videoUrl))
                .setSubtitleConfigurations(subtitleConfigs)

            if (isHlsUrl(videoUrl)) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            } else if (isDashUrl(videoUrl)) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            
            exoPlayer?.setMediaItem(mediaItemBuilder.build())
        }

        val resumeKey = if (title != null) "resume_stream_$title" else null
        val savedPos = if (initialPositionMs > 0L) {
            initialPositionMs
        } else if (resumeKey != null) {
            prefs.getLong(resumeKey, 0L)
        } else {
            0L
        }

        if (savedPos > 5000L) {
            exoPlayer?.seekTo(savedPos)
        }

        val firstSubUrl = subtitles.keys.firstOrNull() ?: ""
        if (firstSubUrl.isNotEmpty()) {
            subtitleAlignmentManager.loadSubtitles(firstSubUrl)
        } else {
            subtitleAlignmentManager.hideUI()
        }
        restoreSubtitleVisibility()

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun updateQualityOptions(tracks: Tracks) {
        val options = mutableListOf<QualityOption>()

        if (lastAlternativeUrls.isNotEmpty()) {
            for (i in lastAlternativeUrls.indices) {
                val url = lastAlternativeUrls[i]
                val name = lastAlternativeNames.getOrNull(i) ?: "QualityOption ${i + 1}"
                options.add(QualityOption.DistinctUrl(name, url))
            }
        }

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO && group.isSupported) {
                if (group.isAdaptiveSupported && options.none { it is QualityOption.Auto }) {
                    options.add(QualityOption.Auto())
                }
                
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val width = format.width
                        val height = format.height
                        val bitrate = format.bitrate
                        val label = when {
                            height > 0 -> "${height}p"
                            width > 0 -> "${width}p"
                            else -> format.label ?: "Track ${i + 1}"
                        }
                        val bitrateLabel = if (bitrate > 0) " (${bitrate / 1000} kbps)" else ""
                        options.add(
                            QualityOption.Native(
                                name = "$label$bitrateLabel",
                                trackGroup = group.mediaTrackGroup,
                                trackIndex = i
                            )
                        )
                    }
                }
            }
        }

        _qualityOptions.value = options

        val currentUrl = lastVideoUrl
        val currentDistinct = options.firstOrNull { it is QualityOption.DistinctUrl && it.url == currentUrl }
        if (currentDistinct != null) {
            _currentQuality.value = currentDistinct
        } else {
            var selectedTrackCount = 0
            var singleSelectedTrack: QualityOption? = null
            
            for (opt in options) {
                if (opt is QualityOption.Native) {
                    for (group in tracks.groups) {
                        if (group.mediaTrackGroup == opt.trackGroup && group.isTrackSelected(opt.trackIndex)) {
                            selectedTrackCount++
                            if (singleSelectedTrack == null) {
                                singleSelectedTrack = opt
                            }
                        }
                    }
                }
            }

            _currentQuality.value = if (selectedTrackCount == 1) {
                singleSelectedTrack
            } else {
                options.firstOrNull { it is QualityOption.Auto }
            }
        }
    }

    fun selectQuality(option: QualityOption) {
        val player = exoPlayer ?: return
        when (option) {
            is QualityOption.Auto -> {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
                _currentQuality.value = option
                updateQualityButtonText("AUTO")
            }
            is QualityOption.Native -> {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .addOverride(TrackSelectionOverride(option.trackGroup, option.trackIndex))
                    .build()
                _currentQuality.value = option
                val displayName = if (option.name.contains(" ")) option.name.substringBefore(" ").uppercase() else option.name.uppercase()
                updateQualityButtonText(displayName)
            }
            is QualityOption.DistinctUrl -> {
                val currentPos = player.currentPosition
                val wasPlaying = player.playWhenReady
                
                playVideoInAlternateQuality(currentPos, wasPlaying, option.url)
            }
        }
    }

    private fun playVideoInAlternateQuality(currentPos: Long, wasPlaying: Boolean, alternateUrl: String) {
        saveProgress()
        
        launchVideo(
            videoUrl = alternateUrl,
            title = lastVideoTitle,
            headers = lastHeaders,
            subtitles = lastSubtitles,
            item = lastScrapedItem,
            season = lastScrapedSeason,
            episode = lastScrapedEpisode,
            fromStreams = lastFromStreams,
            alternativeUrls = lastAlternativeUrls,
            alternativeNames = lastAlternativeNames,
            initialPositionMs = currentPos,
            isTrailer = lastIsTrailer
        )
        exoPlayer?.playWhenReady = wasPlaying
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
        playerScope.coroutineContext.cancelChildren()
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.e("PlayerEngine", "Error releasing player", e)
        }
        exoPlayer = null
        _isPlayerActive.value = false
        _isControllerVisible.value = false
        onPlayerReleased()
        isReleasing = false
    }

    private fun saveProgress() {
        val player = exoPlayer
        if (player != null && player.playerError == null && hasReachedReady && !lastIsTrailer) {
            val title = lastVideoTitle
            val resumeKey = if (title != null) "resume_stream_$title" else null

            if (resumeKey != null) {
                val pos = player.currentPosition
                val dur = player.duration
                
                val creditsStart = introDbManager.getCreditsStartMs()
                val isCompleted = if (creditsStart != null) {
                    pos >= creditsStart
                } else {
                    val threshold = getUpNextThreshold()
                    pos >= (dur - threshold)
                }

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