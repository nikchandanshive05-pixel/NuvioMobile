package com.nuvio.app.features.details.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails

/**
 * Top Action Bar & Floating Header for the Details Screen.
 * Renders back button, cast, notifications, watch status bookmark, share, and search.
 */
@Composable
fun DetailHeaderOverlay(
    meta: MetaDetails,
    isSaved: Boolean,
    isHeroCollapsed: State<Boolean>,
    backgroundColor: Color?,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onWatchStatusClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val headerTarget = if (isHeroCollapsed.value) 1f else 0f
    val headerProgress by animateFloatAsState(
        targetValue = headerTarget,
        animationSpec = tween(
            durationMillis = if (headerTarget > 0f) 150 else 100,
            easing = LinearOutSlowInEasing,
        ),
        label = "detail_floating_header_progress",
    )

    if (headerProgress <= 0.05f) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                )
                .zIndex(2f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioBackButton(
                onClick = onBack,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        NuvioToastController.show("Casting will be available during playback")
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = "Cast",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = {
                        NuvioToastController.show("Subscribed to release notifications for ${meta.name}")
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsNone,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onWatchStatusClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Watch Status",
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = {
                        NuvioToastController.show("Share link copied: ${meta.name}")
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    DetailFloatingHeader(
        meta = meta,
        isSaved = isSaved,
        progress = headerProgress,
        backgroundColor = backgroundColor,
        onBack = onBack,
        onToggleSaved = onToggleSaved,
        modifier = modifier.zIndex(2f),
    )
}
