package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.plugins.PluginScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ProviderCatalogConfig(
    val id: String,
    val displayName: String,
    val defaultGenres: List<String>,
)

data class ProviderCatalogResult(
    val heroItems: List<MetaPreview>,
    val sections: List<HomeCatalogSection>,
)

object ProviderCatalogResolver {
    private val log = Logger.withTag("ProviderCatalogResolver")
    private val json = Json { ignoreUnknownKeys = true }

    private val providerConfigs = mapOf(
        "cnc_netflix" to ProviderCatalogConfig(
            id = "cnc_netflix",
            displayName = "Netflix",
            defaultGenres = listOf("Drama", "Comedy", "Thriller", "Action"),
        ),
        "cnc_prime" to ProviderCatalogConfig(
            id = "cnc_prime",
            displayName = "Prime Video",
            defaultGenres = listOf("Action", "Sci-Fi", "Crime", "Adventure"),
        ),
        "cnc_hotstar" to ProviderCatalogConfig(
            id = "cnc_hotstar",
            displayName = "HotStar",
            defaultGenres = listOf("Action", "Drama", "Family", "Animation"),
        ),
        "cnc_adult" to ProviderCatalogConfig(
            id = "cnc_adult",
            displayName = "18+ Adult Content",
            defaultGenres = listOf("Romance", "Thriller", "Mystery", "Drama"),
        ),
    )

    // In-memory cache for ultra-fast, lag-free switching between providers
    private val providerCache = mutableMapOf<String, ProviderCatalogResult>()

    fun clearCache(providerId: String? = null) {
        if (providerId == null) {
            providerCache.clear()
        } else {
            providerCache.remove(providerId)
        }
    }

    /**
     * Resolves curated discovery feeds for an installed CloudStream scraper plugin.
     */
    suspend fun resolveScraperCatalog(
        scraper: PluginScraper,
    ): ProviderCatalogResult = withContext(Dispatchers.Default) {
        providerCache[scraper.id]?.let { return@withContext it }

        val lower = scraper.name.lowercase()
        val isAnime = lower.contains("anime") || lower.contains("koto") || lower.contains("suge")
        val primaryGenre = if (isAnime) "Animation" else null

        coroutineScope {
            val trendingMoviesDeferred = async { fetchCinemeta(type = "movie", genre = primaryGenre) }
            val trendingTvDeferred = async { fetchCinemeta(type = "series", genre = primaryGenre) }
            val topRatedDeferred = async { fetchCinemeta(type = "movie", genre = if (isAnime) "Animation" else "Action") }

            val movies = trendingMoviesDeferred.await()
            val tvShows = trendingTvDeferred.await()
            val topRated = topRatedDeferred.await()

            val heroItems = (movies.take(3) + tvShows.take(3)).shuffled().take(5)
            val sections = mutableListOf<HomeCatalogSection>()

            if (movies.isNotEmpty() || tvShows.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${scraper.id}_trending",
                        title = "Trending on ${scraper.name}",
                        subtitle = "Popular right now",
                        addonName = scraper.name,
                        target = CatalogTarget.Library(contentType = "all", sectionType = "provider_trending"),
                        items = (movies.take(5) + tvShows.take(5)).shuffled(),
                    )
                )
            }
            if (movies.isNotEmpty() && scraper.supportsType("movie")) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${scraper.id}_movies",
                        title = "Featured Movies",
                        subtitle = "${scraper.name} Movie Catalog",
                        addonName = scraper.name,
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_movies"),
                        items = movies,
                    )
                )
            }
            if (tvShows.isNotEmpty() && scraper.supportsType("tv")) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${scraper.id}_series",
                        title = "Featured Series",
                        subtitle = "${scraper.name} TV Shows",
                        addonName = scraper.name,
                        target = CatalogTarget.Library(contentType = "series", sectionType = "provider_series"),
                        items = tvShows,
                    )
                )
            }
            if (topRated.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${scraper.id}_top_rated",
                        title = "Top Rated",
                        subtitle = "Highest Rated Releases",
                        addonName = scraper.name,
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_top_rated"),
                        items = topRated,
                    )
                )
            }

            val result = ProviderCatalogResult(heroItems = heroItems, sections = sections)
            providerCache[scraper.id] = result
            result
        }
    }

    /**
     * Resolves curated sections for a given CloudStream provider (Netflix, Prime, HotStar, Adult).
     * High-speed, guaranteed populated via Cinemeta and provider-specific genre channels.
     */
    suspend fun resolveProviderCatalog(providerId: String): ProviderCatalogResult = withContext(Dispatchers.Default) {
        providerCache[providerId]?.let { return@withContext it }

        val config = providerConfigs[providerId] ?: providerConfigs["cnc_netflix"]!!

        coroutineScope {
            val genre1 = config.defaultGenres.getOrNull(0) ?: "Drama"
            val genre2 = config.defaultGenres.getOrNull(1) ?: "Action"
            val genre3 = config.defaultGenres.getOrNull(2) ?: "Sci-Fi"

            val seriesGenre1Deferred = async { fetchCinemetaWithFallback("series", genre1) }
            val moviesGenre2Deferred = async { fetchCinemetaWithFallback("movie", genre2) }
            val moviesGenre3Deferred = async { fetchCinemetaWithFallback("movie", genre3) }
            val topRatedDeferred = async { fetchCinemeta(type = "movie", genre = null) }

            val series1 = seriesGenre1Deferred.await()
            val movies2 = moviesGenre2Deferred.await()
            val movies3 = moviesGenre3Deferred.await()
            val topRated = topRatedDeferred.await()

            val heroItems = (series1.take(3) + movies2.take(3)).shuffled().take(5)
            val sections = mutableListOf<HomeCatalogSection>()

            if (series1.isNotEmpty() || movies2.isNotEmpty()) {
                val trendingCombined = (series1.take(6) + movies2.take(6)).shuffled()
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_trending",
                        title = "Trending on ${config.displayName}",
                        subtitle = "Popular right now",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "all", sectionType = "provider_trending"),
                        items = trendingCombined,
                    )
                )
            }

            if (series1.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_series",
                        title = "${config.displayName} $genre1 Series",
                        subtitle = "Binge-worthy drama and shows",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "series", sectionType = "provider_series"),
                        items = series1,
                    )
                )
            }

            if (movies2.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_movies_g2",
                        title = "${config.displayName} $genre2 Movies",
                        subtitle = "Blockbusters & Featured Releases",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_movies_g2"),
                        items = movies2,
                    )
                )
            }

            if (movies3.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_movies_g3",
                        title = "${config.displayName} $genre3 Picks",
                        subtitle = "Curated titles for you",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_movies_g3"),
                        items = movies3,
                    )
                )
            } else if (topRated.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_top_rated",
                        title = "Top Rated on ${config.displayName}",
                        subtitle = "Critically Acclaimed",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_top_rated"),
                        items = topRated,
                    )
                )
            }

            val result = ProviderCatalogResult(heroItems = heroItems, sections = sections)
            // Avoid pinning transient request failures as an empty provider catalog.
            if (result.sections.any { it.items.isNotEmpty() }) providerCache[providerId] = result
            result
        }
    }

    /**
     * Fallback catalog when Stremio addons / Nuvio Catalogue are disabled or unavailable.
     * Guarantees the Home Screen is never empty!
     */
    suspend fun resolveFallbackCatalog(): ProviderCatalogResult = withContext(Dispatchers.Default) {
        val cacheKey = "default_fallback"
        providerCache[cacheKey]?.let { return@withContext it }

        coroutineScope {
            val trendingMoviesDeferred = async { fetchCinemeta(type = "movie", genre = null) }
            val trendingTvDeferred = async { fetchCinemeta(type = "series", genre = null) }
            val scifiMoviesDeferred = async { fetchCinemeta(type = "movie", genre = "Sci-Fi") }
            val actionMoviesDeferred = async { fetchCinemeta(type = "movie", genre = "Action") }

            val movies = trendingMoviesDeferred.await()
            val tvShows = trendingTvDeferred.await()
            val scifi = scifiMoviesDeferred.await()
            val action = actionMoviesDeferred.await()

            val heroItems = (movies.take(3) + tvShows.take(3)).shuffled().take(5)
            val sections = listOf(
                HomeCatalogSection(
                    key = "fallback_trending",
                    title = "Trending Now",
                    subtitle = "Global Box Office & Releases",
                    addonName = "CloudStream Feed",
                    target = CatalogTarget.Library(contentType = "all", sectionType = "fallback_trending"),
                    items = (movies.take(6) + tvShows.take(6)).shuffled(),
                ),
                HomeCatalogSection(
                    key = "fallback_popular_movies",
                    title = "Popular Movies",
                    subtitle = "Most Watched Movies",
                    addonName = "CloudStream Feed",
                    target = CatalogTarget.Library(contentType = "movie", sectionType = "fallback_movies"),
                    items = movies,
                ),
                HomeCatalogSection(
                    key = "fallback_popular_series",
                    title = "Popular TV Shows",
                    subtitle = "Binge-Worthy Series",
                    addonName = "CloudStream Feed",
                    target = CatalogTarget.Library(contentType = "series", sectionType = "fallback_series"),
                    items = tvShows,
                ),
                HomeCatalogSection(
                    key = "fallback_action_movies",
                    title = "Action & Sci-Fi Thrillers",
                    subtitle = "Top Rated Favorites",
                    addonName = "CloudStream Feed",
                    target = CatalogTarget.Library(contentType = "movie", sectionType = "fallback_action"),
                    items = if (scifi.isNotEmpty()) scifi else action,
                )
            )

            val result = ProviderCatalogResult(heroItems = heroItems, sections = sections)
            providerCache[cacheKey] = result
            result
        }
    }

    /** Fall back to the popular catalog when a genre feed returns no results. */
    private suspend fun fetchCinemetaWithFallback(type: String, genre: String): List<MetaPreview> {
        val genreItems = fetchCinemeta(type = type, genre = genre)
        return genreItems.ifEmpty { fetchCinemeta(type = type) }
    }

    /**
     * Fetches metadata directly from Cinemeta catalog with resilient error handling.
     */
    suspend fun fetchCinemeta(
        type: String,
        genre: String? = null,
        searchQuery: String? = null,
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val url = when {
            !searchQuery.isNullOrBlank() -> {
                val encoded = searchQuery.trim().replace(" ", "%20")
                "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
            }
            !genre.isNullOrBlank() -> {
                val encodedGenre = genre.trim().replace(" ", "%20")
                "https://v3-cinemeta.strem.io/catalog/$type/top/genre=$encodedGenre.json"
            }
            else -> "https://v3-cinemeta.strem.io/catalog/$type/top.json"
        }

        runCatching {
            val responseText = httpGetText(url)
            val parsed = json.decodeFromString<CinemetaCatalogResponse>(responseText)
            parsed.metas.orEmpty().map { meta ->
                MetaPreview(
                    id = meta.id,
                    type = meta.type ?: type,
                    name = meta.name,
                    poster = meta.poster,
                    banner = meta.background ?: meta.logo,
                    posterShape = PosterShape.Poster,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo ?: meta.year,
                    rawReleaseDate = meta.released,
                    imdbRating = meta.imdbRating?.takeIf { it.isNotBlank() },
                )
            }
        }.onFailure { error ->
            log.w { "Failed to fetch Cinemeta catalog from $url: ${error.message}" }
        }.getOrDefault(emptyList())
    }

    @Serializable
    private data class CinemetaCatalogResponse(
        val metas: List<CinemetaMetaItem>? = null,
    )

    @Serializable
    private data class CinemetaMetaItem(
        val id: String,
        val type: String? = null,
        val name: String,
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val year: String? = null,
        val released: String? = null,
        val imdbRating: String? = null,
    )
}
