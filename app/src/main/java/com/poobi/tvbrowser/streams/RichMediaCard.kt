package com.poobi.tvbrowser.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.poobi.tvbrowser.browser.HoldToDeleteCloseButton
import com.poobi.tvbrowser.shared.RemoteImage
import com.poobi.tvbrowser.shared.TvMarqueeText
import org.json.JSONObject

@Composable
fun RichMediaCard(
    item: JSONObject,
    viewModel: StreamsViewModel,
    unwatchedCount: Int = 0,
    newerCount: Int = 0,
    season: Int? = null,
    episode: Int? = null,
    isFocused: Boolean = false,
    progress: Float = 0f,
    isDeletable: Boolean = false,
    isLandscape: Boolean = false
) {
    val model = remember(item, season, episode) { MediaItem.fromJson(item, season, episode) }
    RichMediaCard(
        model = model,
        viewModel = viewModel,
        unwatchedCount = unwatchedCount,
        newerCount = newerCount,
        season = season,
        episode = episode,
        isFocused = isFocused,
        progress = progress,
        isDeletable = isDeletable,
        isLandscape = isLandscape
    )
}

@Composable
fun RichMediaCard(
    model: MediaItem,
    viewModel: StreamsViewModel,
    unwatchedCount: Int = 0,
    newerCount: Int = 0,
    season: Int? = null,
    episode: Int? = null,
    isFocused: Boolean = false,
    progress: Float = 0f,
    isDeletable: Boolean = false,
    isLandscape: Boolean = false
) {
    val favorites by viewModel.favoritesSet.collectAsState()
    val isFav = favorites.contains(model.id) || (model.imdb.isNotEmpty() && favorites.contains(model.imdb))

    val cardWidth = if (isLandscape) 240.dp else 160.dp
    val imageHeight = if (isLandscape) 135.dp else 200.dp
    val imageUrl = model.getImageUrl(isLandscape)

    Column(
        modifier = Modifier.width(cardWidth).padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF222222))
        ) {
            RemoteImage(url = imageUrl, contentDescription = model.title, modifier = Modifier.fillMaxSize())

            if (model.rating > 0.0) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(32.dp)
                        .background(Color(0xCC000000), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⭐", fontSize = 16.sp, modifier = Modifier.offset(y = (-2).dp))
                    Text(
                        text = "%.1f".format(model.rating),
                        color = Color(0xFF40C4FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = 6.dp)
                    )
                }
            }

            if (!model.spotlightActorProfile.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000))
                        .border(1.5.dp, Color(0xFF00BCD4), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    RemoteImage(
                        url = "https://image.tmdb.org/t/p/w185${model.spotlightActorProfile}",
                        contentDescription = model.spotlightActorName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (unwatchedCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFF2E2E35), shape = CircleShape)
                            .border(1.dp, Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = unwatchedCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (newerCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFFFFB74D), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+$newerCount",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (isFav) {
                    Box(modifier = Modifier.size(24.dp).background(Color(0xCC000000), shape = CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(id = R.drawable.ic_heart_filled), contentDescription = null, tint = Color(0xFF40C4FF), modifier = Modifier.size(14.dp))
                    }
                }
            }

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
                text = model.title,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (season != null && episode != null) {
                Text(
                    text = "S$season E$episode", 
                    color = if (isFocused) Color(0xFF004D40) else Color(0xFF40C4FF), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold
                )
            } else if (!model.character.isNullOrBlank()) {
                Text(
                    text = "as ${model.character}", 
                    color = if (isFocused) Color(0xFF004D40) else Color(0xFF40C4FF), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (model.overview.isNotEmpty()) {
                TvMarqueeText(
                    text = model.overview,
                    color = if (isFocused) Color(0xFF222222) else Color.LightGray,
                    fontSize = 10.sp,
                    maxLines = 3,
                    isFocused = isFocused,
                    modifier = Modifier.fillMaxWidth().height(38.dp).padding(bottom = 2.dp)
                )
            }

            if (model.genresStr.isNotEmpty()) {
                TvMarqueeText(
                    text = model.genresStr,
                    color = if (isFocused) Color(0xFFBF360C) else Color(0xFFFFB74D),
                    fontSize = 11.sp,
                    maxLines = 1,
                    isFocused = isFocused,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (model.mediaType == "person") "ACTOR" else model.mediaType.uppercase(), 
                    color = if (isFocused) Color(0xFF1B5E20) else Color(0xFF4CAF50), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold
                )
                if (model.year.isNotEmpty() && model.year != "0000") {
                    Text(
                        text = " • ${model.year}", 
                        color = if (isFocused) Color(0xFF37474F) else Color(0xFFB0BEC5), 
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}