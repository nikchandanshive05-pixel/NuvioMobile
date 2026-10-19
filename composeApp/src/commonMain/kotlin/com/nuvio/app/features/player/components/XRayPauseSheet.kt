package com.nuvio.app.features.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.features.details.MetaPerson

/**
 * Prime Video style X-Ray Pause Sheet.
 * Displays on-screen / featured cast avatars with actor name and character role,
 * alongside quick movie/series contextual info when playback is paused.
 */
@Composable
fun XRayPauseSheet(
    title: String,
    episodeTitle: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    cast: List<MetaPerson>,
    genres: List<String>,
    horizontalSafePadding: Dp,
    onCastMemberClick: (MetaPerson) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.60f),
                        Color.Black.copy(alpha = 0.92f),
                    ),
                ),
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        val compactHeight = maxHeight < 420.dp
        val bottomPadding = if (compactHeight) 16.dp else 24.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalSafePadding + 20.dp,
                    end = horizontalSafePadding + 20.dp,
                    bottom = bottomPadding,
                ),
        ) {
            // X-Ray Header Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "X-RAY",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                if (genres.isNotEmpty()) {
                    Text(
                        text = genres.take(3).joinToString(" • "),
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Episode / Content Subtitle
            val subtitleText = when {
                seasonNumber != null && episodeNumber != null && !episodeTitle.isNullOrBlank() ->
                    "S$seasonNumber:E$episodeNumber · $episodeTitle"
                seasonNumber != null && episodeNumber != null ->
                    "Season $seasonNumber, Episode $episodeNumber"
                else -> title
            }

            Text(
                text = subtitleText,
                color = Color.White,
                fontSize = if (compactHeight) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = if (compactHeight) 8.dp else 12.dp),
            )

            // Cast Rail (Prime Video Circular Avatars)
            if (cast.isNotEmpty()) {
                Text(
                    text = "IN THIS SCENE / CAST",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    items(cast.take(12), key = { it.tmdbId ?: it.name }) { person ->
                        XRayCastAvatarItem(
                            person = person,
                            compact = compactHeight,
                            onClick = { onCastMemberClick(person) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XRayCastAvatarItem(
    person: MetaPerson,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val avatarSize = if (compact) 44.dp else 52.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(if (compact) 72.dp else 80.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(Color(0xFF2C2C2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (!person.photo.isNullOrBlank()) {
                AsyncImage(
                    model = person.photo,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = person.name,
            color = Color.White,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (!person.role.isNullOrBlank()) {
            Text(
                text = person.role,
                color = Color(0xFF8E8E93),
                fontSize = if (compact) 10.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
