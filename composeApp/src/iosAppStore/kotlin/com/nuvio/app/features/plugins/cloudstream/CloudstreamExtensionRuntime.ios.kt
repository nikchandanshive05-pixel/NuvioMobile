package com.nuvio.app.features.plugins.cloudstream

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper

actual object CloudstreamExtensionRuntime {
    actual fun initialize(context: Any) = Unit
    actual suspend fun search(query: String, scrapers: List<PluginScraper>): List<CloudstreamSearchHit> = emptyList()
    actual suspend fun load(scraper: PluginScraper, itemUrl: String): CloudstreamLoadedItem? = null
    actual suspend fun loadLinks(scraper: PluginScraper, dataUrl: String): List<PluginRuntimeResult> = emptyList()
}
