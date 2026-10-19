package com.nuvio.app.features.plugins.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.plugins.PluginManifest
import com.nuvio.app.features.plugins.PluginManifestScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CloudstreamRepositoryResolver {
    private val log = Logger.withTag("CloudstreamResolver")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Determines if a raw URL or shortcode looks like a CloudStream repository.
     */
    fun isCloudstreamRepositoryUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim().lowercase()
        return trimmed.endsWith(".json") ||
            trimmed.contains("raw.githubusercontent.com") ||
            trimmed.contains("github.com/") ||
            trimmed.startsWith("cloudstreamrepo://") ||
            trimmed.startsWith("cs.repo/") ||
            trimmed.startsWith("https://cs.repo") ||
            trimmed.startsWith("http://cs.repo") ||
            trimmed.startsWith("!") ||
            trimmed.contains("cutt.ly/") ||
            trimmed.contains("py.md/")
    }

    /**
     * Resolves user input into direct repository JSON URL.
     */
    suspend fun resolveRepositoryUrl(rawInput: String): String = withContext(Dispatchers.Default) {
        val trimmed = rawInput.trim()

        // Handle custom protocol prefix
        if (trimmed.startsWith("cloudstreamrepo://", ignoreCase = true)) {
            val stripped = trimmed.removePrefix("cloudstreamrepo://")
            return@withContext if (!stripped.startsWith("http")) "https://$stripped" else stripped
        }
        if (trimmed.startsWith("cs.repo/", ignoreCase = true) || trimmed.startsWith("https://cs.repo", ignoreCase = true)) {
            val stripped = trimmed.replace(Regex("""^(https?://)?cs\.repo/\??"""), "")
            return@withContext if (!stripped.startsWith("http")) "https://$stripped" else stripped
        }

        // Handle shortlinks (e.g. !code or cutt.ly)
        if (trimmed.startsWith("!") || (!trimmed.contains("/") && !trimmed.contains("."))) {
            val code = trimmed.removePrefix("!")
            return@withContext "https://py.md/$code"
        }

        // Handle GitHub repo root URLs (e.g. https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension)
        val ghRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/?$""")
        val ghMatch = ghRegex.find(trimmed)
        if (ghMatch != null) {
            val user = ghMatch.groupValues[1]
            val repo = ghMatch.groupValues[2]
            return@withContext "https://raw.githubusercontent.com/$user/$repo/builds/repo.json"
        }

        // Handle standard web URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return@withContext trimmed
        }

        "https://$trimmed"
    }

    /**
     * Attempts to parse a Cloudstream repository into Nuvio's unified PluginManifest.
     * Supports:
     * 1. repo.json / CNC.json (with pluginLists -> plugins.json)
     * 2. Direct plugins.json array
     */
    suspend fun parseAsPluginManifest(url: String): PluginManifest? = withContext(Dispatchers.Default) {
        runCatching {
            val resolvedUrl = resolveRepositoryUrl(url)
            log.d { "Fetching repository payload from $resolvedUrl" }
            val payload = httpGetText(resolvedUrl).trim()

            // Case A: Payload is directly a JSON array of plugins [ {...}, {...} ]
            if (payload.startsWith("[")) {
                val plugins = json.decodeFromString<List<CloudstreamSitePlugin>>(payload)
                return@withContext convertPluginsToManifest(
                    repoName = url.substringBeforeLast("/").substringAfterLast("/").ifBlank { "CloudStream Repository" },
                    description = "CloudStream Provider Extensions",
                    plugins = plugins,
                    repoUrl = resolvedUrl,
                )
            }

            // Case B: Payload is a repo.json object
            if (payload.startsWith("{")) {
                val jsonObject = json.parseToJsonElement(payload).jsonObject
                
                // If it has "pluginLists" (Standard Cloudstream repo.json schema)
                if (jsonObject.containsKey("pluginLists")) {
                    val repo = json.decodeFromString<CloudstreamRepository>(payload)
                    val allPlugins = mutableListOf<CloudstreamSitePlugin>()
                    
                    for (pluginListUrl in repo.pluginLists) {
                        runCatching {
                            val listPayload = httpGetText(pluginListUrl).trim()
                            if (listPayload.startsWith("[")) {
                                val list = json.decodeFromString<List<CloudstreamSitePlugin>>(listPayload)
                                allPlugins.addAll(list)
                            }
                        }.onFailure { err ->
                            log.w(err) { "Failed to fetch plugins from $pluginListUrl" }
                        }
                    }

                    if (allPlugins.isNotEmpty()) {
                        return@withContext convertPluginsToManifest(
                            repoName = repo.name,
                            description = repo.description ?: "CloudStream Extension Repository",
                            plugins = allPlugins,
                            repoUrl = resolvedUrl,
                        )
                    }
                }

                // If it is a 404 response on builds/repo.json, check if repo has another name like CNC.json or plugins.json
                if (resolvedUrl.endsWith("/builds/repo.json")) {
                    val base = resolvedUrl.removeSuffix("/repo.json")
                    val fallbackPluginsUrl = "$base/plugins.json"
                    runCatching {
                        val fbPayload = httpGetText(fallbackPluginsUrl).trim()
                        if (fbPayload.startsWith("[")) {
                            val plugins = json.decodeFromString<List<CloudstreamSitePlugin>>(fbPayload)
                            return@withContext convertPluginsToManifest(
                                repoName = "CloudStream Providers",
                                description = "Direct CloudStream Extension Providers",
                                plugins = plugins,
                                repoUrl = fallbackPluginsUrl,
                            )
                        }
                    }
                }
            }

            null
        }.getOrNull()
    }

    private fun convertPluginsToManifest(
        repoName: String,
        description: String,
        plugins: List<CloudstreamSitePlugin>,
        repoUrl: String,
    ): PluginManifest {
        val scrapers = plugins.map { plugin ->
            // Map Cloudstream tvTypes to Nuvio media types
            val supported = mutableListOf<String>()
            val types = plugin.tvTypes ?: emptyList()
            if (types.any { it.equals("Movie", ignoreCase = true) || it.equals("AnimeMovie", ignoreCase = true) }) {
                supported.add("movie")
            }
            if (types.any { it.equals("TvSeries", ignoreCase = true) || it.equals("Anime", ignoreCase = true) || it.equals("OVA", ignoreCase = true) }) {
                supported.add("tv")
            }
            if (supported.isEmpty()) {
                supported.add("movie")
                supported.add("tv")
            }

            PluginManifestScraper(
                id = plugin.internalName.ifBlank { plugin.name }.replace(" ", ""),
                name = plugin.name,
                description = plugin.description ?: "CloudStream Provider • ${plugin.authors.joinToString(", ").ifBlank { "Community" }}",
                version = plugin.version.toString(),
                filename = plugin.url, // points to .cs3 file
                supportedTypes = supported.distinct(),
                enabled = plugin.status == 1,
                hasSettings = false,
                logo = plugin.iconUrl,
                contentLanguage = plugin.language?.let { listOf(it) } ?: listOf("en"),
                supportedPlatforms = listOf("android"),
            )
        }

        return PluginManifest(
            name = repoName,
            version = "1.0",
            description = description,
            author = "CloudStream Community",
            scrapers = scrapers,
        )
    }
}
