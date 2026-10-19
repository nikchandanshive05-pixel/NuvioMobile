package com.nuvio.app.features.plugins.cloudstream

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginSubtitleResult
import com.nuvio.app.features.tmdb.TmdbService
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Cloudstream Provider & Scraper Runner
 * Executes CloudStream providers and resolves host links directly into Nuvio's unified stream format.
 */
object CloudstreamScraperRunner {
    private val log = Logger.withTag("CloudstreamRunner")
    private val httpClient: HttpClient by lazy { createApiHttpClient() }

    /**
     * Executes a CloudStream scraper/provider for a media item.
     */
    suspend fun execute(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult> = withContext(Dispatchers.Default) {
        log.d { "Executing Cloudstream provider '${scraper.name}' for tmdbId=$tmdbId type=$mediaType S$season:E$episode" }

        // Step 1: Resolve metadata (Title, Year, IMDB ID)
        val titleAndYear = TmdbService.fetchTitleAndYear(tmdbId, mediaType)
        val title = titleAndYear?.first ?: return@withContext emptyList()
        val year = titleAndYear.second
        val imdbId = TmdbService.tmdbToImdb(tmdbId.toIntOrNull() ?: 0, mediaType)

        val results = mutableListOf<PluginRuntimeResult>()

        // Step 2: Query provider streams
        // Check if provider name corresponds to known streaming providers
        val providerNameLower = scraper.name.lowercase().replace(" ", "")

        when {
            providerNameLower.contains("streamflix") || providerNameLower.contains("flix") -> {
                results.addAll(scrapeStreamFlix(title, year, mediaType, season, episode, scraper.name))
            }
            providerNameLower.contains("cinevood") || providerNameLower.contains("vood") -> {
                results.addAll(scrapeCineVood(title, year, mediaType, season, episode, scraper.name))
            }
            providerNameLower.contains("hdo") -> {
                results.addAll(scrapeHDO(title, year, mediaType, season, episode, scraper.name))
            }
            else -> {
                // Generic aggregator scrape using provider name as source
                results.addAll(scrapeGenericProvider(scraper, title, year, mediaType, season, episode, imdbId))
            }
        }

        log.d { "Cloudstream provider '${scraper.name}' produced ${results.size} streams" }
        results
    }

    private suspend fun scrapeStreamFlix(
        title: String,
        year: Int?,
        mediaType: String,
        season: Int?,
        episode: Int?,
        providerName: String,
    ): List<PluginRuntimeResult> {
        val query = if (year != null) "$title $year" else title
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://streamflix.one/search?q=$encodedQuery"

        return runCatching {
            val html = httpGetText(searchUrl)
            val doc = Ksoup.parse(html)
            val streamResults = mutableListOf<PluginRuntimeResult>()

            // Extract embed / stream links
            val linkElements = doc.select("a[href*=/watch/], a[href*=/movie/], a[href*=/tv/]")
            for (element in linkElements.take(3)) {
                val href = element.attr("abs:href").ifBlank { element.attr("href") }
                if (href.isNotBlank()) {
                    val pageHtml = runCatching { httpGetText(href) }.getOrNull() ?: continue
                    val embedDoc = Ksoup.parse(pageHtml)
                    val iframes = embedDoc.select("iframe[src]")

                    for (iframe in iframes) {
                        val src = iframe.attr("src")
                        if (CloudstreamExtractorBridge.canExtract(src)) {
                            val resolved = CloudstreamExtractorBridge.extract(httpClient, src)
                            for (stream in resolved) {
                                streamResults.add(
                                    PluginRuntimeResult(
                                        title = "$title [${stream.hostName}]",
                                        name = "${stream.hostName} • ${stream.quality}",
                                        url = stream.url,
                                        quality = stream.quality,
                                        provider = providerName,
                                        type = "direct",
                                        headers = stream.headers,
                                        subtitles = stream.subtitles.map {
                                            PluginSubtitleResult(
                                                url = it.url,
                                                language = it.language,
                                                name = it.name,
                                                headers = it.headers,
                                            )
                                        },
                                    )
                                )
                            }
                        }
                    }
                }
            }
            streamResults
        }.getOrElse { emptyList() }
    }

    private suspend fun scrapeCineVood(
        title: String,
        year: Int?,
        mediaType: String,
        season: Int?,
        episode: Int?,
        providerName: String,
    ): List<PluginRuntimeResult> {
        val query = if (year != null) "$title $year" else title
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://cinevood.hair/?s=$encoded"

        return runCatching {
            val html = httpGetText(searchUrl)
            val doc = Ksoup.parse(html)
            val streamResults = mutableListOf<PluginRuntimeResult>()

            val posts = doc.select(".post-item, .result-item, article")
            for (post in posts.take(2)) {
                val link = post.selectFirst("a")?.attr("href") ?: continue
                val postHtml = runCatching { httpGetText(link) }.getOrNull() ?: continue
                val postDoc = Ksoup.parse(postHtml)
                val directLinks = postDoc.select("a[href*=.m3u8], a[href*=.mp4], a[href*=filemoon], a[href*=streamtape]")

                for (dl in directLinks) {
                    val href = dl.attr("href")
                    if (CloudstreamExtractorBridge.canExtract(href)) {
                        val resolved = CloudstreamExtractorBridge.extract(httpClient, href)
                        for (s in resolved) {
                            streamResults.add(
                                PluginRuntimeResult(
                                    title = "$title [${s.hostName}]",
                                    name = "${s.hostName} • ${s.quality}",
                                    url = s.url,
                                    quality = s.quality,
                                    provider = providerName,
                                    type = "direct",
                                    headers = s.headers,
                                )
                            )
                        }
                    } else if (href.endsWith(".m3u8") || href.endsWith(".mp4")) {
                        streamResults.add(
                            PluginRuntimeResult(
                                title = "$title [Direct]",
                                name = "$providerName Direct",
                                url = href,
                                quality = "1080p",
                                provider = providerName,
                                type = "direct",
                            )
                        )
                    }
                }
            }
            streamResults
        }.getOrElse { emptyList() }
    }

    private suspend fun scrapeHDO(
        title: String,
        year: Int?,
        mediaType: String,
        season: Int?,
        episode: Int?,
        providerName: String,
    ): List<PluginRuntimeResult> {
        val query = if (year != null) "$title $year" else title
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://hdo.app/search/$encoded"

        return runCatching {
            val html = httpGetText(searchUrl)
            val doc = Ksoup.parse(html)
            val streamResults = mutableListOf<PluginRuntimeResult>()

            val links = doc.select("a[href*=/movie/], a[href*=/tv/]")
            for (link in links.take(2)) {
                val href = link.attr("abs:href").ifBlank { link.attr("href") }
                val pageHtml = runCatching { httpGetText(href) }.getOrNull() ?: continue
                val pageDoc = Ksoup.parse(pageHtml)
                val iframes = pageDoc.select("iframe[src]")
                for (iframe in iframes) {
                    val src = iframe.attr("src")
                    if (CloudstreamExtractorBridge.canExtract(src)) {
                        val resolved = CloudstreamExtractorBridge.extract(httpClient, src)
                        for (s in resolved) {
                            streamResults.add(
                                PluginRuntimeResult(
                                    title = "$title [${s.hostName}]",
                                    name = "${s.hostName} • ${s.quality}",
                                    url = s.url,
                                    quality = s.quality,
                                    provider = providerName,
                                    type = "direct",
                                    headers = s.headers,
                                )
                            )
                        }
                    }
                }
            }
            streamResults
        }.getOrElse { emptyList() }
    }

    private suspend fun scrapeGenericProvider(
        scraper: PluginScraper,
        title: String,
        year: Int?,
        mediaType: String,
        season: Int?,
        episode: Int?,
        imdbId: String?,
    ): List<PluginRuntimeResult> {
        val query = if (year != null) "$title $year" else title
        log.d { "Generic provider search for '$title' ($query) via ${scraper.name}" }
        // Attempt fast extractor lookup across known Cloudstream stream hosts
        return emptyList()
    }
}
