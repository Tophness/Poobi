package com.poobi.tvbrowser.streams

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.RemoteImage
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvMarqueeText
import com.poobi.tvbrowser.shared.isFutureDate
import com.poobi.tvbrowser.shared.KeyTracker
import org.json.JSONArray
import org.json.JSONObject

private fun formatDateToDMY(dateStr: String?): String {
    if (dateStr.isNullOrEmpty() || dateStr == "null" || dateStr == "0000") return dateStr ?: ""
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val formatter = java.text.SimpleDateFormat("d/M/yyyy", java.util.Locale.US)
        val date = parser.parse(dateStr)
        if (date != null) formatter.format(date) else dateStr
    } catch (e: Exception) {
        dateStr
    }
}

private val MovieIcon: ImageVector = ImageVector.Builder(
    name = "Movie",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(fill = SolidColor(Color.White)) {
    moveTo(18.0f, 4.0f)
    lineToRelative(2.0f, 4.0f)
    horizontalLineToRelative(-3.0f)
    lineToRelative(-2.0f, -4.0f)
    horizontalLineToRelative(-2.0f)
    lineToRelative(2.0f, 4.0f)
    horizontalLineToRelative(-3.0f)
    lineToRelative(-2.0f, -4.0f)
    horizontalLineToRelative(-2.0f)
    lineToRelative(2.0f, 4.0f)
    horizontalLineToRelative(-3.0f)
    lineToRelative(-2.0f, -4.0f)
    horizontalLineToRelative(-2.0f)
    lineToRelative(2.0f, 4.0f)
    horizontalLineTo(5.0f)
    lineToRelative(2.0f, 4.0f)
    horizontalLineTo(4.0f)
    curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
    lineTo(2.0f, 20.0f)
    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
    horizontalLineToRelative(16.0f)
    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
    verticalLineTo(4.0f)
    horizontalLineToRelative(-4.0f)
    close()
}.build()

@Composable
fun MediaDetailsScreen(viewModel: StreamsViewModel) {
    val item by viewModel.selectedItem.collectAsState()
    val details by viewModel.itemDetails.collectAsState()
    val seasons by viewModel.itemSeasons.collectAsState()
    val episodes by viewModel.itemEpisodes.collectAsState()
    val cast by viewModel.itemCast.collectAsState()
    val recs by viewModel.itemRecommendations.collectAsState()
    val isFetchingTrailer by viewModel.isFetchingTrailer.collectAsState()
    val localEpisodes = episodes
    val lastWatchedEpisode by viewModel.lastWatchedEpisode.collectAsState()
    var autoFocusCancelled by remember(item) { mutableStateOf(false) }
    val targetEpisodeFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val seasonSelectorFocusRequester = remember { FocusRequester() }
    val firstCastFocusRequester = remember { FocusRequester() }
    val titleRaw = details?.optString("title")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("title")?.takeIf { it.isNotBlank() } 
        ?: details?.optString("name")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("name") ?: "Unknown"
    val title = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
    val overview = details?.optString("overview")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("overview") ?: "No description available."
    val hasBackdrop = (details?.optString("backdrop_path")?.isNotBlank() == true && details?.optString("backdrop_path") != "null") || 
                      (item?.optString("backdrop_path")?.isNotBlank() == true && item?.optString("backdrop_path") != "null")
    val imagePath = if (hasBackdrop) {
        details?.optString("backdrop_path")?.takeIf { it.isNotBlank() && it != "null" }
            ?: item?.optString("backdrop_path") ?: ""
    } else {
        details?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }
            ?: item?.optString("poster_path") ?: ""
    }
    val imageUrl = "https://image.tmdb.org/t/p/w780$imagePath"
    val imageAspectRatio = if (hasBackdrop) 16f/9f else 2f/3f
    val releaseDate = item?.optString("release_date").takeIf { !it.isNullOrEmpty() } ?: item?.optString("first_air_date") ?: "0000"
    val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "0000"
    val rating = details?.optDouble("vote_average", item?.optDouble("vote_average", 0.0) ?: 0.0) ?: 0.0
    val mediaType = item?.optString("media_type")?.takeIf { it.isNotEmpty() && it != "null" } ?: if (item?.has("name") == true) "tv" else "movie"
    var genresStr = ""
    val genresArr = item?.optJSONArray("genre_ids") ?: details?.optJSONArray("genres")
    if (genresArr != null && genresArr.length() > 0) {
        val genreNames = mutableListOf<String>()
        for (i in 0 until genresArr.length()) {
            val obj = genresArr.get(i)
            if (obj is JSONObject) {
                genreNames.add(obj.optString("name"))
            } else if (obj is Int) {
                viewModel.genreMap[obj]?.let { genreNames.add(it) }
            }
        }
        if (genreNames.isNotEmpty()) genresStr = genreNames.joinToString(" • ")
    }
    var selectedSeasonIndex by remember(seasons) { mutableStateOf(0) }
    var spinnerExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(localEpisodes, seasons) {
        if (seasons != null && localEpisodes != null && localEpisodes.length() > 0) {
            val currentSeasonNum = localEpisodes.getJSONObject(0).optInt("season_number", -1)
            for (i in 0 until seasons!!.length()) {
                if (seasons!!.getJSONObject(i).optInt("season_number") == currentSeasonNum) {
                    selectedSeasonIndex = i
                    break
                }
            }
        }
    }
    val leftColumnScrollState = rememberScrollState()
    val lazyListState = rememberLazyListState()

    val targetEpisodeToFocus = remember(localEpisodes, lastWatchedEpisode, viewModel.lastScrapedSeason, viewModel.lastScrapedEpisode) {
        if (localEpisodes != null && localEpisodes.length() > 0) {
            val activeSeasonNum = seasons?.optJSONObject(selectedSeasonIndex)?.optInt("season_number")

            if (viewModel.lastScrapedSeason != null && viewModel.lastScrapedEpisode != null && activeSeasonNum == viewModel.lastScrapedSeason) {
                val showTitle = details?.optString("title")?.takeIf { it.isNotBlank() } 
                    ?: item?.optString("title")?.takeIf { it.isNotBlank() } 
                    ?: details?.optString("name")?.takeIf { it.isNotBlank() } 
                    ?: item?.optString("name") ?: ""
                val displayTitle = "$showTitle S${viewModel.lastScrapedSeason}E${viewModel.lastScrapedEpisode}"
                val resumeKey = "resume_stream_$displayTitle"
                val savedPos = viewModel.prefs.getLong(resumeKey, 0L)
                if (savedPos > 0L) {
                    return@remember viewModel.lastScrapedEpisode
                }
            }

            if (lastWatchedEpisode != null) {
                return@remember lastWatchedEpisode
            }

            val focusMode = viewModel.prefs.getInt("episode_focus_mode", 0) // 0 = Next after latest watched, 1 = First unwatched
            val isWatched = { ep: JSONObject ->
                ep.optBoolean("is_watched", false)
            }
            val isEpisodeAired = { ep: JSONObject ->
                val airDate = ep.optString("air_date", "")
                airDate.isNotEmpty() && !isFutureDate(airDate)
            }
            if (focusMode == 0) {
                var maxWatchedEp = -1
                for (i in 0 until localEpisodes.length()) {
                    val ep = localEpisodes.getJSONObject(i)
                    val num = ep.optInt("episode_number")
                    if (isWatched(ep)) {
                        if (num > maxWatchedEp) {
                            maxWatchedEp = num
                        }
                    }
                }
                if (maxWatchedEp != -1) {
                    val nextEp = maxWatchedEp + 1
                    var nextExistsAndAired = false
                    for (i in 0 until localEpisodes.length()) {
                        val ep = localEpisodes.getJSONObject(i)
                        if (ep.optInt("episode_number") == nextEp) {
                            if (isEpisodeAired(ep)) {
                                nextExistsAndAired = true
                            }
                            break
                        }
                    }
                    if (nextExistsAndAired) nextEp else maxWatchedEp
                } else {
                    1
                }
            } else {
                var targetEp = 1
                var found = false
                for (i in 0 until localEpisodes.length()) {
                    val ep = localEpisodes.getJSONObject(i)
                    if (!isWatched(ep) && isEpisodeAired(ep)) {
                        targetEp = ep.optInt("episode_number", 1)
                        found = true
                        break
                    }
                }
                if (!found) {
                    var lastWatched = 1
                    for (i in 0 until localEpisodes.length()) {
                        val ep = localEpisodes.getJSONObject(i)
                        if (isWatched(ep)) {
                            lastWatched = ep.optInt("episode_number", 1)
                        }
                    }
                    targetEp = lastWatched
                }
                targetEp
            }
        } else null
    }

    LaunchedEffect(item) {
        autoFocusCancelled = false
        val startTime = System.currentTimeMillis()
        kotlinx.coroutines.delay(500)
        if (KeyTracker.lastKeyPressTime < startTime) {
            if (mediaType == "movie") {
                try { playButtonFocusRequester.requestFocus() } catch (e: Exception) {}
            } else if (mediaType == "tv") {
                try { seasonSelectorFocusRequester.requestFocus() } catch (e: Exception) {}
            }
        }
    }

    LaunchedEffect(targetEpisodeToFocus, autoFocusCancelled) {
        if (!autoFocusCancelled && mediaType == "tv" && targetEpisodeToFocus != null && localEpisodes != null) {
            var targetIndex = -1
            for (i in 0 until localEpisodes.length()) {
                if (localEpisodes.getJSONObject(i).optInt("episode_number") == targetEpisodeToFocus) {
                    targetIndex = i
                    break
                }
            }

            if (targetIndex != -1) {
                lazyListState.animateScrollToItem(targetIndex)
            }

            val startTime = System.currentTimeMillis()
            delay(800)
            try {
                if (!autoFocusCancelled && KeyTracker.lastKeyPressTime < startTime) {
                    targetEpisodeFocusRequester.requestFocus()
                    autoFocusCancelled = true
                }
            } catch (e: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .widthIn(min = 220.dp, max = 320.dp)
                    .fillMaxHeight()
                    .verticalScroll(leftColumnScrollState)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvFocusableBox(modifier = Modifier.size(40.dp), onClick = { viewModel.clearSelectedMedia() }) {
                        Icon(painter = painterResource(id = R.drawable.ic_back), contentDescription = "Back", tint = Color.White, modifier = Modifier.fillMaxSize().padding(8.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = year, color = Color(0xFFB0BEC5), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = " • ", color = Color.DarkGray, fontSize = 15.sp)
                    Text(text = mediaType.uppercase(), color = Color(0xFF90A4AE), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (rating > 0.0) {
                        Text(text = " • ", color = Color.DarkGray, fontSize = 15.sp)
                        Text(text = "⭐ %.1f".format(rating), color = Color(0xFF00BCD4), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (genresStr.isNotEmpty()) {
                    TvMarqueeText(
                        text = genresStr,
                        color = Color(0xFFFFB74D),
                        fontSize = 14.sp,
                        maxLines = 1,
                        isFocused = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (mediaType == "movie" && releaseDate != "0000" && releaseDate.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val formattedReleaseDate = remember(releaseDate) { formatDateToDMY(releaseDate) }
                    Text(
                        text = "Released: $formattedReleaseDate",
                        color = Color(0xFF90A4AE),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                RemoteImage(
                    url = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(imageAspectRatio)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(15.dp))
                TvMarqueeText(
                    text = overview,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    maxLines = 10,
                    isFocused = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(30.dp))

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (mediaType == "movie") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val playInteractionSource = remember { MutableInteractionSource() }
                            val isPlayFocused by playInteractionSource.collectIsFocusedAsState()
                            val playScale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (isPlayFocused) 1.03f else 1.0f,
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 150),
                                label = "PlayFocusScale"
                            )
                            val playBgColor = if (isPlayFocused) Color(0xFF40C4FF) else Color(0xFF4CAF50)
                            val playContentColor = if (isPlayFocused) Color.Black else Color.White

                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(55.dp)
                                    .scale(playScale)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(playBgColor)
                                    .border(if (isPlayFocused) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                                    .focusRequester(playButtonFocusRequester)
                                    .clickable(
                                        interactionSource = playInteractionSource,
                                        indication = null,
                                        onClick = { viewModel.performScrape(item!!) }
                                    )
                                    .focusable(interactionSource = playInteractionSource),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_auto_play),
                                        contentDescription = null,
                                        tint = playContentColor
                                    )
                                    Text(
                                        text = "Play Movie",
                                        color = playContentColor,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            val trailerInteractionSource = remember { MutableInteractionSource() }
                            val isTrailerFocused by trailerInteractionSource.collectIsFocusedAsState()
                            val trailerScale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (isTrailerFocused) 1.03f else 1.0f,
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 150),
                                label = "TrailerFocusScale"
                            )
                            val trailerBgColor = if (isTrailerFocused) Color(0xFF40C4FF) else Color(0xFFE53935)
                            val trailerContentColor = if (isTrailerFocused) Color.Black else Color.White

                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(55.dp)
                                    .scale(trailerScale)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(trailerBgColor)
                                    .border(if (isTrailerFocused) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = trailerInteractionSource,
                                        indication = null,
                                        onClick = { viewModel.playTrailer(item!!) }
                                    )
                                    .focusable(interactionSource = trailerInteractionSource),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = MovieIcon,
                                        contentDescription = null,
                                        tint = trailerContentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Trailer",
                                        color = trailerContentColor,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (mediaType == "tv" && seasons != null && seasons!!.length() > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Season:", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                            
                            TvFocusableBox(
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(50.dp)
                                    .focusRequester(seasonSelectorFocusRequester)
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            val code = keyEvent.nativeKeyEvent.keyCode
                                            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
                                                autoFocusCancelled = true
                                            }
                                        }
                                        false
                                    },
                                onClick = { spinnerExpanded = true }
                            ) {
                                val activeSeasonName = seasons!!.getJSONObject(selectedSeasonIndex).optString("name", "Season ${selectedSeasonIndex + 1}")
                                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                    Text(activeSeasonName, color = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            TvFocusableBox(
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(50.dp),
                                onClick = { viewModel.playTrailer(item!!) }
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_auto_play),
                                        contentDescription = "Show Trailer",
                                        tint = if (isFocused) Color.Black else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Trailer",
                                        color = if (isFocused) Color.Black else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(15.dp))
                    val favorites by viewModel.favoritesSet.collectAsState()
                    val isFav = favorites.contains(item?.optString("id")) || (item?.optString("imdb")?.isNotEmpty() == true && favorites.contains(item!!.optString("imdb")))
                    
                    TvFocusableBox(
                        modifier = Modifier.size(50.dp),
                        onClick = { item?.let { viewModel.toggleFavorite(it) } }
                    ) { isFocused ->
                        Icon(
                            painter = painterResource(id = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_empty),
                            contentDescription = "Favorite",
                            tint = if (isFav) Color(0xFFE91E63) else Color.White,
                            modifier = Modifier.fillMaxSize().padding(10.dp)
                        )
                    }

                    if (mediaType == "movie") {
                        Spacer(modifier = Modifier.width(15.dp))
                        val isMovieWatched by viewModel.isMovieWatched.collectAsState()
                        TvFocusableBox(
                            modifier = Modifier.size(50.dp),
                            onClick = { item?.let { viewModel.toggleMovieWatched(it) } }
                        ) { isFocused ->
                            Icon(
                                painter = painterResource(id = R.drawable.ic_watched),
                                contentDescription = "Trakt Watched",
                                tint = if (isMovieWatched) Color(0xFF00BCD4) else Color.White.copy(alpha = if (isFocused) 1.0f else 0.4f),
                                modifier = Modifier.fillMaxSize().padding(10.dp)
                            )
                        }
                    }
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0x0DFFFFFF), shape = RoundedCornerShape(8.dp))
                        .padding(5.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp, start = 8.dp, end = 8.dp)
                ) {
                    if (mediaType == "tv") {
                        if (localEpisodes != null) {
                            items(localEpisodes.length()) { idx ->
                                val ep = localEpisodes.getJSONObject(idx)
                                val num = ep.optInt("episode_number")
                                val isWatched = ep.optBoolean("is_watched", false)
                                val isTarget = (num == targetEpisodeToFocus)
                                var isCardFocused by remember { mutableStateOf(false) }
                                val airDate = ep.optString("air_date", "")
                                
                                val scale by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = if (isCardFocused) 1.02f else 1.0f,
                                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 150),
                                    label = "CardFocusScale"
                                )

                                val cardBgColor = if (isCardFocused) Color(0xFF40C4FF) else Color(0xFF333333)
                                val cardBorderColor = if (isCardFocused) Color.White else Color.Transparent
                                val textColor = if (isCardFocused) Color.Black else Color.White
                                val descColor = if (isCardFocused) Color(0xFF333333) else Color(0xFFAAAAAA)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .scale(scale)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cardBgColor)
                                        .border(if (isCardFocused) 2.dp else 0.dp, cardBorderColor, RoundedCornerShape(8.dp))
                                        .onFocusChanged { state: FocusState ->
                                            isCardFocused = state.hasFocus
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val playInteractionSource = remember { MutableInteractionSource() }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .let { if (isTarget) it.focusRequester(targetEpisodeFocusRequester) else it }
                                            .onPreviewKeyEvent { keyEvent ->
                                                val nativeEvent = keyEvent.nativeKeyEvent
                                                if (idx == localEpisodes.length() - 1 && 
                                                    nativeEvent.action == KeyEvent.ACTION_DOWN && 
                                                    nativeEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                                    try {
                                                        firstCastFocusRequester.requestFocus()
                                                        true
                                                    } catch (e: Exception) {
                                                        false
                                                    }
                                                } else false
                                            }
                                            .clickable(
                                                interactionSource = playInteractionSource,
                                                indication = null,
                                                onClick = { viewModel.performScrape(item!!, seasons!!.getJSONObject(selectedSeasonIndex).optInt("season_number"), num) }
                                            )
                                            .focusable(interactionSource = playInteractionSource)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RemoteImage(
                                                url = "https://image.tmdb.org/t/p/w300${ep.optString("still_path")}",
                                                contentDescription = null,
                                                modifier = Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(4.dp))
                                            )
                                            Spacer(modifier = Modifier.width(15.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "E$num: ${ep.optString("name")}",
                                                    color = textColor,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (airDate.isNotEmpty()) {
                                                    val isFuture = isFutureDate(airDate)
                                                    val formattedAirDate = remember(airDate) { formatDateToDMY(airDate) }
                                                    val prefix = if (isFuture) "Airing: " else "Aired: "
                                                    Text(
                                                        text = "$prefix$formattedAirDate",
                                                        color = if (isCardFocused) Color(0xFF2E2E35) else Color(0xFF00BCD4),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                    )
                                                }
                                                Text(
                                                    text = ep.optString("overview"),
                                                    color = descColor,
                                                    fontSize = 12.sp,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    val trailerInteractionSource = remember { MutableInteractionSource() }
                                    val isTrailerFocused by trailerInteractionSource.collectIsFocusedAsState()

                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when {
                                                    isTrailerFocused && isCardFocused -> Color.Black.copy(alpha = 0.15f)
                                                    isTrailerFocused -> Color.White.copy(alpha = 0.15f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable(
                                                interactionSource = trailerInteractionSource,
                                                indication = null,
                                                onClick = { 
                                                    viewModel.playTrailer(
                                                        item = item!!,
                                                        season = seasons!!.getJSONObject(selectedSeasonIndex).optInt("season_number"),
                                                        episode = num
                                                    )
                                                }
                                            )
                                            .focusable(interactionSource = trailerInteractionSource),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MovieIcon,
                                            contentDescription = "Trailer",
                                            tint = when {
                                                isTrailerFocused -> Color(0xFFE53935)
                                                isCardFocused -> Color.Black
                                                else -> Color(0xFF00BCD4)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    val watchedInteractionSource = remember { MutableInteractionSource() }
                                    val isWatchedFocused by watchedInteractionSource.collectIsFocusedAsState()

                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when {
                                                    isWatchedFocused && isCardFocused -> Color.Black.copy(alpha = 0.15f)
                                                    isWatchedFocused -> Color.White.copy(alpha = 0.15f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable(
                                                interactionSource = watchedInteractionSource,
                                                indication = null,
                                                onClick = {
                                                    item?.let {
                                                        viewModel.toggleEpisodeWatched(
                                                            item = it,
                                                            season = seasons!!.getJSONObject(selectedSeasonIndex).optInt("season_number"),
                                                            episode = num
                                                        )
                                                    }
                                                }
                                            )
                                            .focusable(interactionSource = watchedInteractionSource),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_watched),
                                            contentDescription = "Toggle Watched Status",
                                            tint = when {
                                                isWatched -> Color(0xFF00BCD4)
                                                isWatchedFocused -> Color.White
                                                isCardFocused -> Color.Black.copy(alpha = 0.4f)
                                                else -> Color.White.copy(alpha = 0.2f)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                        } else {
                            val expectedCount = if (seasons != null && selectedSeasonIndex in 0 until seasons!!.length()) {
                                seasons!!.getJSONObject(selectedSeasonIndex).optInt("episode_count", 5)
                            } else {
                                5
                            }
                            items(expectedCount) {
                                EpisodePlaceholderItem()
                            }
                        }
                    }

                    item {
                        Text("Top Cast", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp, 20.dp, 0.dp, 10.dp))
                        if (cast != null) {
                            if (cast!!.length() > 0) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                                ) {
                                    items(cast!!.length().coerceAtMost(15)) { idx ->
                                        val c = cast!!.getJSONObject(idx)
                                        val cPath = c.optString("profile_path")
                                        TvFocusableBox(
                                            modifier = Modifier
                                                .width(120.dp)
                                                .let { if (idx == 0) it.focusRequester(firstCastFocusRequester) else it },
                                            onClick = { viewModel.selectCastMember(c.optInt("id"), c.optString("name")) }
                                        ) { isFocused ->
                                            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                                RemoteImage(
                                                    url = "https://image.tmdb.org/t/p/w185$cPath", 
                                                    contentDescription = null, 
                                                    modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(4.dp))
                                                )
                                                Text(c.optString("name"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                                                Text(c.optString("character"), color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text("No cast details available.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(10.dp))
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                            ) {
                                items(5) { placeholderIdx ->
                                    CastPlaceholderItem(
                                        modifier = Modifier.let {
                                            if (placeholderIdx == 0) it.focusRequester(firstCastFocusRequester) else it
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("More Like This", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp, 20.dp, 0.dp, 10.dp))
                        if (recs != null) {
                            if (recs!!.length() > 0) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                                ) {
                                    items(recs!!.length().coerceAtMost(15)) { idx ->
                                        val rItem = recs!!.getJSONObject(idx)
                                        TvFocusableBox(
                                            modifier = Modifier.wrapContentSize(),
                                            onClick = { viewModel.selectMediaItem(rItem) }
                                        ) { isFocused ->
                                            RichMediaCard(
                                                item = rItem,
                                                viewModel = viewModel,
                                                isFocused = isFocused
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text("No recommendations available.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(10.dp))
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                            ) {
                                items(5) { placeholderIdx ->
                                    RecommendationPlaceholderItem()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isFetchingTrailer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00BCD4))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading Trailer...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (spinnerExpanded && seasons != null) {
        AlertDialog(
            onDismissRequest = { spinnerExpanded = false },
            title = { Text("Select Season", color = Color.White) },
            containerColor = Color(0xFF222225),
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons!!.length()) { i ->
                        val s = seasons!!.getJSONObject(i)
                        TvFocusableBox(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = {
                                selectedSeasonIndex = i
                                spinnerExpanded = false
                                viewModel.loadEpisodes(item!!, s.optInt("season_number"))
                            }
                        ) {
                            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                Text(s.optString("name", "Season ${s.optInt("season_number")}"), color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun EpisodePlaceholderItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(160.dp, 90.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222225))
        )
        Spacer(modifier = Modifier.width(15.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222225))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222225))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222225))
            )
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .padding(10.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(Color(0xFF222225))
        )
    }
}

@Composable
private fun CastPlaceholderItem(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(120.dp)
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF222225))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222225))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222225))
            )
        }
    }
}

@Composable
private fun RecommendationPlaceholderItem() {
    Column(
        modifier = Modifier
            .width(160.dp)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222225))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF222225))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF222225))
        )
    }
}