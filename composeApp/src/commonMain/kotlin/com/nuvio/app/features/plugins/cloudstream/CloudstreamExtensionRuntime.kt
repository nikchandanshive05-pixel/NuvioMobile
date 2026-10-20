package com.nuvio.app.features.plugins.cloudstream

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper

data class CloudstreamSearchHit(
    val scraperId: String,
    val scraperName: String,
    val title: String,
    val url: String,
    val poster: String? = null,
    val mediaType: String = "movie",
    val year: Int? = null,
)

data class CloudstreamEpisode(
    val title: String,
    val dataUrl: String,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
)

data class CloudstreamHomeFeed(
    val title: String,
    val horizontalImages: Boolean,
    val items: List<CloudstreamSearchHit>,
)

data class CloudstreamHomeCatalog(val feeds: List<CloudstreamHomeFeed>)

data class CloudstreamLoadedItem(
    val title: String,
    val poster: String? = null,
    val description: String? = null,
    val mediaType: String = "movie",
    val year: Int? = null,
    val dataUrl: String,
    val episodes: List<CloudstreamEpisode> = emptyList(),
)

expect object CloudstreamExtensionRuntime {
    fun initialize(context: Any)
    suspend fun search(query: String, scrapers: List<PluginScraper>): List<CloudstreamSearchHit>
    suspend fun homeCatalog(scraper: PluginScraper): CloudstreamHomeCatalog?
    suspend fun load(scraper: PluginScraper, itemUrl: String): CloudstreamLoadedItem?
    suspend fun loadLinks(scraper: PluginScraper, dataUrl: String): List<PluginRuntimeResult>
}
