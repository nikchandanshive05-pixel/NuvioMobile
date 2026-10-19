package com.nuvio.app.features.plugins.cloudstream

import com.fleeksoft.ksoup.Ksoup
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloudstream Extractor Bridge
 * Bridges Cloudstream's high-efficiency web extractors into Nuvio's Compose Multiplatform stream pipeline.
 */
data class CloudstreamResolvedStream(
    val url: String,
    val quality: String = "1080p",
    val hostName: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList(),
)

object CloudstreamExtractorBridge {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * Checks if the given URL is supported by Cloudstream's built-in extractors.
     */
    fun canExtract(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("streamtape") ||
            lower.contains("shavetape") ||
            lower.contains("watchadsontape") ||
            lower.contains("filemoon") ||
            lower.contains("dood") ||
            lower.contains("ds2play") ||
            lower.contains("mixdrop") ||
            lower.contains("voe.sx")
    }

    /**
     * Resolves an embed/host URL to direct playable video streams using Cloudstream's extraction patterns.
     */
    suspend fun extract(
        client: HttpClient,
        url: String,
    ): List<CloudstreamResolvedStream> = withContext(Dispatchers.Default) {
        val lower = url.lowercase()
        try {
            when {
                lower.contains("streamtape") || lower.contains("shavetape") || lower.contains("watchadsontape") -> {
                    extractStreamTape(client, url)
                }
                lower.contains("filemoon") -> {
                    extractFilemoon(client, url)
                }
                lower.contains("mixdrop") -> {
                    extractMixdrop(client, url)
                }
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Extractor for StreamTape (and mirrors like ShaveTape, WatchAdsOnTape)
     * Ported from Cloudstream's Streamtape.kt
     */
    private suspend fun extractStreamTape(
        client: HttpClient,
        url: String,
    ): List<CloudstreamResolvedStream> {
        val responseText = client.get(url) {
            headers {
                append("User-Agent", DEFAULT_USER_AGENT)
                append("Referer", url)
            }
        }.bodyAsText()

        val doc = Ksoup.parse(responseText)
        val scriptLines = doc.select("script")
            .firstOrNull { it.html().contains("botlink').innerHTML") }
            ?.html()
            ?.lines() ?: return emptyList()

        val tokenLine = scriptLines.firstOrNull { it.contains("botlink').innerHTML") } ?: return emptyList()
        val token = tokenLine.substringAfter("=").trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")

        if (token.isNotBlank()) {
            val directUrl = if (token.startsWith("//")) "https:$token&stream=1" else "$token&stream=1"
            return listOf(
                CloudstreamResolvedStream(
                    url = directUrl,
                    quality = "1080p",
                    hostName = "StreamTape",
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to DEFAULT_USER_AGENT,
                    )
                )
            )
        }
        return emptyList()
    }

    /**
     * Extractor for FileMoon
     * Ported from Cloudstream's FileMoon.kt
     */
    private suspend fun extractFilemoon(
        client: HttpClient,
        url: String,
    ): List<CloudstreamResolvedStream> {
        val defaultHeaders = mapOf(
            "Referer" to url,
            "User-Agent" to DEFAULT_USER_AGENT,
        )

        val responseText = client.get(url) {
            headers {
                defaultHeaders.forEach { (k, v) -> append(k, v) }
            }
        }.bodyAsText()

        val doc = Ksoup.parse(responseText)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")

        val targetHtml = if (!iframeSrc.isNullOrBlank()) {
            val targetUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            client.get(targetUrl) {
                headers {
                    append("Referer", url)
                    append("User-Agent", DEFAULT_USER_AGENT)
                }
            }.bodyAsText()
        } else {
            responseText
        }

        // Regex for HLS master playlist URL
        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
        val match = m3u8Regex.find(targetHtml)

        return if (match != null) {
            listOf(
                CloudstreamResolvedStream(
                    url = match.value,
                    quality = "Auto (HLS)",
                    hostName = "FileMoon",
                    headers = defaultHeaders
                )
            )
        } else {
            emptyList()
        }
    }

    /**
     * Extractor for Mixdrop
     * Ported from Cloudstream's Mixdrop.kt
     */
    private suspend fun extractMixdrop(
        client: HttpClient,
        url: String,
    ): List<CloudstreamResolvedStream> {
        val responseText = client.get(url) {
            headers {
                append("User-Agent", DEFAULT_USER_AGENT)
                append("Referer", url)
            }
        }.bodyAsText()

        val mp4Regex = Regex("""(?:vs|wurl|surl)\s*=\s*["']([^"']+\.mp4[^"']*)["']""")
        val match = mp4Regex.find(responseText)

        return if (match != null) {
            val streamUrl = if (match.groupValues[1].startsWith("//")) "https:${match.groupValues[1]}" else match.groupValues[1]
            listOf(
                CloudstreamResolvedStream(
                    url = streamUrl,
                    quality = "1080p",
                    hostName = "Mixdrop",
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to DEFAULT_USER_AGENT,
                    )
                )
            )
        } else {
            emptyList()
        }
    }

    /**
     * Converts a Cloudstream resolved stream into Nuvio's unified StreamItem.
     */
    fun CloudstreamResolvedStream.toStreamItem(
        parentTitle: String? = null,
    ): StreamItem {
        return StreamItem(
            name = "$hostName ($quality)",
            title = parentTitle ?: hostName,
            description = "Direct Web Stream • $hostName",
            url = this.url,
            addonName = "Cloudstream: $hostName",
            addonId = "cloudstream:${hostName.lowercase()}",
            addonLogo = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/drawable/ic_launcher.png",
            streamType = "direct",
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = this.headers),
                notWebReady = false,
            ),
            externalSubtitles = this.subtitles,
            badges = listOf(
                StreamBadge(name = quality),
                StreamBadge(name = "Cloudstream"),
            )
        )
    }
}
