package com.poobi.tvbrowser.ui.streams

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.ui.shared.RemoteImage
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import com.poobi.tvbrowser.ui.shared.TvMarqueeText
import org.json.JSONArray
import org.json.JSONObject

private fun findFirstUnwatchedEpisode(episodes: JSONArray): Int {
    for (i in 0 until episodes.length()) {
        val ep = episodes.getJSONObject(i)
        if (!ep.optBoolean("is_watched", false)) {
            return ep.optInt("episode_number", 1)
        }
    }
    return 1
}

@Composable
fun MediaDetailsScreen(viewModel: StreamsViewModel) {
    val item by viewModel.selectedItem.collectAsState()
    val details by viewModel.itemDetails.collectAsState()
    val seasons by viewModel.itemSeasons.collectAsState()
    val episodes by viewModel.itemEpisodes.collectAsState()
    val cast by viewModel.itemCast.collectAsState()
    val recs by viewModel.itemRecommendations.collectAsState()

    // Smart-cast workaround for Kotlin delegated properties
    val localEpisodes = episodes

    val targetEpisodeFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val firstCastFocusRequester = remember { FocusRequester() }

    val title = details?.optString("title")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("title")?.takeIf { it.isNotBlank() } 
        ?: details?.optString("name")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("name") ?: "Unknown"

    val overview = details?.optString("overview")?.takeIf { it.isNotBlank() } 
        ?: item?.optString("overview") ?: "No description available."

    // Dynamically choose between backdrop and poster to maintain exact column width alignment
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

    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var spinnerExpanded by remember { mutableStateOf(false) }

    val leftColumnScrollState = rememberScrollState()

    val targetEpisodeToFocus = remember(localEpisodes, viewModel.lastScrapedSeason, viewModel.lastScrapedEpisode) {
        if (localEpisodes != null) {
            if (viewModel.lastScrapedSeason != null && viewModel.lastScrapedEpisode != null) {
                val activeSeasonNum = seasons?.optJSONObject(selectedSeasonIndex)?.optInt("season_number")
                if (activeSeasonNum == viewModel.lastScrapedSeason) {
                    val showTitle = details?.optString("title")?.takeIf { it.isNotBlank() } 
                        ?: item?.optString("title")?.takeIf { it.isNotBlank() } 
                        ?: details?.optString("name")?.takeIf { it.isNotBlank() } 
                        ?: item?.optString("name") ?: ""
                    val displayTitle = "$showTitle S${viewModel.lastScrapedSeason}E${viewModel.lastScrapedEpisode}"
                    val resumeKey = "resume_stream_$displayTitle"
                    val savedPos = viewModel.prefs.getLong(resumeKey, 0L)
                    
                    if (savedPos > 0L) {
                        viewModel.lastScrapedEpisode
                    } else {
                        // Completed, advance to next episode if it exists in the active list
                        val nextEp = viewModel.lastScrapedEpisode!! + 1
                        var nextExists = false
                        for (i in 0 until localEpisodes.length()) {
                            if (localEpisodes.getJSONObject(i).optInt("episode_number") == nextEp) {
                                nextExists = true
                                break
                            }
                        }
                        if (nextExists) nextEp else viewModel.lastScrapedEpisode
                    }
                } else {
                    findFirstUnwatchedEpisode(localEpisodes)
                }
            } else {
                findFirstUnwatchedEpisode(localEpisodes)
            }
        } else null
    }

    LaunchedEffect(targetEpisodeToFocus, item) {
        if (mediaType == "movie") {
            try {
                playButtonFocusRequester.requestFocus()
            } catch (e: Exception) {}
        } else if (mediaType == "tv" && targetEpisodeToFocus != null) {
            try {
                targetEpisodeFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
        Column(
            modifier = Modifier
                .width(260.dp)
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
            
            Text(
                text = "$year • ${mediaType.uppercase()} • ⭐ %.1f".format(rating),
                color = Color(0xFF00BCD4),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (genresStr.isNotEmpty()) {
                TvMarqueeText(
                    text = genresStr,
                    color = Color(0xFFFFB74D),
                    fontSize = 14.sp,
                    maxLines = 1,
                    isFocused = false,
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
                    Button(
                        onClick = { viewModel.performScrape(item!!) }, 
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), 
                        modifier = Modifier
                            .width(200.dp)
                            .height(55.dp)
                            .focusRequester(playButtonFocusRequester)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painter = painterResource(id = R.drawable.ic_go), contentDescription = null, tint = Color.White)
                            Text("Play Movie", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (mediaType == "tv" && seasons != null && seasons!!.length() > 0) {
                    Text("Season:", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                    
                    TvFocusableBox(
                        modifier = Modifier.width(200.dp).height(50.dp),
                        onClick = { spinnerExpanded = true }
                    ) {
                        val activeSeasonName = seasons!!.getJSONObject(selectedSeasonIndex).optString("name", "Season ${selectedSeasonIndex + 1}")
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                            Text(activeSeasonName, color = Color.White)
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
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0x0DFFFFFF), shape = RoundedCornerShape(8.dp))
                    .padding(5.dp),
                contentPadding = PaddingValues(bottom = 50.dp)
            ) {
                if (mediaType == "tv") {
                    if (localEpisodes != null) {
                        items(localEpisodes.length()) { idx ->
                            val ep = localEpisodes.getJSONObject(idx)
                            val num = ep.optInt("episode_number")
                            val isWatched = ep.optBoolean("is_watched", false)
                            val isTarget = (num == targetEpisodeToFocus)

                            TvFocusableBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp)
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
                                    }, 
                                onClick = { viewModel.performScrape(item!!, seasons!!.getJSONObject(selectedSeasonIndex).optInt("season_number"), num) }
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RemoteImage(url = "https://image.tmdb.org/t/p/w300${ep.optString("still_path")}", contentDescription = null, modifier = Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(4.dp)))
                                    Spacer(modifier = Modifier.width(15.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("E$num: ${ep.optString("name")}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text(ep.optString("overview"), color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(painter = painterResource(id = R.drawable.ic_watched), contentDescription = "Watched", tint = Color.White.copy(alpha = if (isWatched) 1.0f else 0.2f), modifier = Modifier.size(50.dp).padding(10.dp))
                                }
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
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(cast!!.length().coerceAtMost(15)) { idx ->
                                    val c = cast!!.getJSONObject(idx)
                                    val cPath = c.optString("profile_path")
                                    TvFocusableBox(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .let { if (idx == 0) it.focusRequester(firstCastFocusRequester) else it },
                                        onClick = {}
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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(5) { placeholderIdx ->
                                RecommendationPlaceholderItem()
                            }
                        }
                    }
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
                    .height(12.dp)
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