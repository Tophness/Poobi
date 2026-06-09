package com.poobi.tvbrowser.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.ui.browser.HoldToDeleteCloseButton
import com.poobi.tvbrowser.ui.shared.RemoteImage
import com.poobi.tvbrowser.ui.shared.TvMarqueeText
import org.json.JSONObject

@Composable
fun RichMediaCard(
    item: JSONObject,
    viewModel: StreamsViewModel,
    newCount: Int = 0,
    season: Int? = null,
    episode: Int? = null,
    isFocused: Boolean = false,
    progress: Float = 0f,
    isDeletable: Boolean = false // Only show the "✕" button on deletable lists!
) {
    val favorites by viewModel.favoritesSet.collectAsState()
    val id = item.optString("id")
    val imdb = item.optString("imdb")
    val isFav = favorites.contains(id) || (imdb.isNotEmpty() && favorites.contains(imdb))

    val title = (item.optString("title").takeIf { it.isNotEmpty() } ?: item.optString("name", ""))
        .replace(Regex("\\s\\(\\d{4}\\)$"), "")

    val releaseDate = item.optString("release_date").takeIf { !it.isNullOrEmpty() }
        ?: item.optString("first_air_date").takeIf { !it.isNullOrEmpty() }
        ?: "0000"
    val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "0000"

    val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
        ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

    val rating = item.optDouble("vote_average", 0.0)
    val posterPath = item.optString("poster_path")
    val posterUrl = "https://image.tmdb.org/t/p/w342$posterPath"
    val overview = item.optString("overview")

    var genresStr = ""
    val genresArr = item.optJSONArray("genre_ids") ?: item.optJSONArray("genres")
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

    Column(
        modifier = Modifier.width(160.dp).padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222222))
        ) {
            RemoteImage(url = posterUrl, contentDescription = title, modifier = Modifier.fillMaxSize())

            if (rating > 0.0) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(32.dp)
                        .background(Color(0xCC000000), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⭐", fontSize = 16.sp, modifier = Modifier.offset(y = (-2).dp))
                    Text(
                        text = "%.1f".format(rating),
                        color = Color(0xFF40C4FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (newCount > 0) {
                    Box(modifier = Modifier.size(28.dp).background(Color(0xCC000000), shape = CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                    }
                }
                if (isFav) {
                    Box(modifier = Modifier.size(28.dp).background(Color(0xCC000000), shape = CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(id = R.drawable.ic_heart_filled), contentDescription = null, tint = Color(0xFF40C4FF), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Close button with circular delete arc only shown on lists configured as deletable (Favs / Recents)
            if (isDeletable && (isFocused || progress > 0f)) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    HoldToDeleteCloseButton(progress = progress)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().height(115.dp).padding(top = 6.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (season != null && episode != null) {
                Text("S$season E$episode", color = Color(0xFF40C4FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.weight(1f))

            if (overview.isNotEmpty()) {
                TvMarqueeText(
                    text = overview,
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    maxLines = 3,
                    isFocused = isFocused,
                    modifier = Modifier.fillMaxWidth().height(38.dp).padding(bottom = 2.dp)
                )
            }

            if (genresStr.isNotEmpty()) {
                TvMarqueeText(
                    text = genresStr,
                    color = Color(0xFFFFB74D),
                    fontSize = 11.sp,
                    maxLines = 1,
                    isFocused = isFocused,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mediaType.uppercase(), color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(" • $year", color = Color(0xFFB0BEC5), fontSize = 11.sp)
            }
        }
    }
}