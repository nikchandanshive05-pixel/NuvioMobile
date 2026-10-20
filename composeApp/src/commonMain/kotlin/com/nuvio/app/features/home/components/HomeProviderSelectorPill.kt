package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.plugins.PluginScraper

data class CloudStreamProviderChoice(
    val id: String,
    val name: String,
    val subtitle: String,
    val icon: ImageVector,
    val flag: String = "",
    val category: String = "All", // "All", "Movies", "TV Series", "Anime"
    val isNsfw: Boolean = false,
)

val adultCloudstreamProvider = CloudStreamProviderChoice(
    id = "cnc_adult",
    name = "CNC Verse - 18+ Adult",
    subtitle = "Adult Releases & Erotic Thrillers",
    icon = Icons.Default.PlayArrow,
    flag = "🔞",
    category = "Movies",
    isNsfw = true,
)

val defaultCloudstreamProviders = listOf(
    CloudStreamProviderChoice(
        id = "unified",
        name = "Unified Catalog",
        subtitle = "Nuvio Aggregated (TMDB & Addons)",
        icon = Icons.Default.PlayArrow,
        flag = "🌐",
        category = "All",
    ),
    CloudStreamProviderChoice(
        id = "cnc_netflix",
        name = "CNC Verse - Netflix",
        subtitle = "Trending Netflix Releases & Originals",
        icon = Icons.Default.PlayArrow,
        flag = "🇮🇳",
        category = "All",
    ),
    CloudStreamProviderChoice(
        id = "cnc_prime",
        name = "CNC Verse - Prime Video",
        subtitle = "Amazon Prime Video Catalog & Top 10",
        icon = Icons.Default.PlayArrow,
        flag = "🇮🇳",
        category = "All",
    ),
    CloudStreamProviderChoice(
        id = "cnc_hotstar",
        name = "CNC Verse - HotStar",
        subtitle = "Disney+ HotStar & Regional Movies",
        icon = Icons.Default.PlayArrow,
        flag = "🇮🇳",
        category = "All",
    ),
)

private fun resolveScraperFlag(scraper: com.nuvio.app.features.plugins.PluginScraper): String {
    val lower = scraper.name.lowercase()
    val langs = scraper.contentLanguage.map { it.lowercase() }
    return when {
        lower.contains("anime") || lower.contains("koto") || lower.contains("suge") || "ja" in langs -> "🇯🇵"
        lower.contains("korean") || lower.contains("drama") || "ko" in langs -> "🇰🇷"
        lower.contains("hindi") || lower.contains("tamil") || lower.contains("telugu") || lower.contains("desi") ||
            lower.contains("india") || lower.contains("cricify") || lower.contains("cinevood") || "hi" in langs || "ta" in langs || "te" in langs -> "🇮🇳"
        lower.contains("arabic") || "ar" in langs -> "🇸🇦"
        lower.contains("french") || "fr" in langs -> "🇫🇷"
        lower.contains("spanish") || "es" in langs -> "🇪🇸"
        lower.contains("russian") || lower.contains("rezka") || "ru" in langs -> "🇷🇺"
        else -> "🇬🇧"
    }
}

private fun resolveScraperCategory(scraper: com.nuvio.app.features.plugins.PluginScraper): String {
    val lower = scraper.name.lowercase()
    val types = scraper.supportedTypes.map { it.lowercase() }
    return when {
        lower.contains("anime") || lower.contains("koto") || lower.contains("suge") -> "Anime"
        types.contains("tv") && !types.contains("movie") -> "TV Series"
        types.contains("movie") && !types.contains("tv") -> "Movies"
        else -> "All"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeProviderSelectorPill(
    currentProviderId: String,
    onProviderSelected: (String) -> Unit,
    enabledProviderIds: Set<String> = setOf("unified", "cnc_netflix", "cnc_prime", "cnc_hotstar"),
    nsfwEnabled: Boolean = false,
    installedScrapers: List<PluginScraper> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    val availableProviders = remember(enabledProviderIds, nsfwEnabled, installedScrapers) {
        val base = mutableListOf<CloudStreamProviderChoice>()
        // 1. Default built-ins
        defaultCloudstreamProviders.forEach { p ->
            if (p.id in enabledProviderIds || p.id == "unified") {
                base.add(p)
            }
        }

        // 2. Installed CloudStream repository providers
        installedScrapers.forEach { scraper ->
            val isAdultScraper = scraper.name.contains("adult", ignoreCase = true) ||
                scraper.name.contains("18+", ignoreCase = true) ||
                scraper.description.contains("18+", ignoreCase = true) ||
                scraper.description.contains("nsfw", ignoreCase = true)

            // If it's adult and NSFW is off, skip completely
            if (isAdultScraper && !nsfwEnabled) return@forEach

            // Check if provider is enabled
            val isEnabled = scraper.enabled && (scraper.id in enabledProviderIds || enabledProviderIds.isEmpty() || scraper.id !in enabledProviderIds.filter { it.startsWith("http") })
            if (isEnabled) {
                base.add(
                    CloudStreamProviderChoice(
                        id = scraper.id,
                        name = scraper.name,
                        subtitle = scraper.description.ifBlank { "CloudStream Community Provider" },
                        icon = Icons.Default.PlayArrow,
                        flag = resolveScraperFlag(scraper),
                        category = resolveScraperCategory(scraper),
                        isNsfw = isAdultScraper,
                    )
                )
            }
        }

        // 3. Built-in Adult provider if NSFW is enabled
        if (nsfwEnabled) {
            base.add(adultCloudstreamProvider)
        }

        base
    }

    // Safe choice: NEVER fall back to adult or disabled provider when not available
    val activeChoice = remember(currentProviderId, availableProviders) {
        availableProviders.find { it.id == currentProviderId }
            ?: availableProviders.firstOrNull { it.id == "unified" }
            ?: availableProviders.firstOrNull()
            ?: defaultCloudstreamProviders.first()
    }

    // Auto-heal current selection if it was disabled (e.g. NSFW toggled off)
    LaunchedEffect(activeChoice.id, currentProviderId) {
        if (activeChoice.id != currentProviderId && availableProviders.none { it.id == currentProviderId }) {
            onProviderSelected(activeChoice.id)
        }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { showSheet = true },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activeChoice.flag.isNotBlank()) {
                Text(text = activeChoice.flag, fontSize = 14.sp)
            } else {
                Icon(
                    imageVector = activeChoice.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = activeChoice.name,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "Select Streaming Provider",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp, top = 4.dp),
                )

                // Category Tabs (All, Movies, TV Series, Anime)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("All", "Movies", "TV Series", "Anime").forEach { cat ->
                        val isCatSelected = selectedCategoryFilter == cat
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedCategoryFilter = cat },
                            color = if (isCatSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (isCatSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                val displayedProviders = availableProviders.filter { provider ->
                    when (selectedCategoryFilter) {
                        "All" -> true
                        "Anime" -> provider.category == "Anime"
                        "Movies" -> provider.category == "Movies" || provider.category == "All"
                        "TV Series" -> provider.category == "TV Series" || provider.category == "All"
                        else -> true
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(displayedProviders, key = { it.id }) { provider ->
                        val isSelected = provider.id == currentProviderId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onProviderSelected(provider.id)
                                    showSheet = false
                                }
                                .padding(vertical = 10.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else Color.White.copy(alpha = 0.06f),
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (provider.flag.isNotBlank()) {
                                        Text(text = provider.flag, fontSize = 16.sp)
                                    } else {
                                        Icon(
                                            imageVector = provider.icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = provider.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
