package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.tmdb.normalizeTmdbLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

data class ProviderCatalogConfig(
    val id: String,
    val displayName: String,
    val watchProviderId: String,
    val networkId: Int? = null,
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
            watchProviderId = "8",
            networkId = 213,
        ),
        "cnc_prime" to ProviderCatalogConfig(
            id = "cnc_prime",
            displayName = "Prime Video",
            watchProviderId = "9|119",
            networkId = 1024,
        ),
        "cnc_hotstar" to ProviderCatalogConfig(
            id = "cnc_hotstar",
            displayName = "HotStar",
            watchProviderId = "122|337",
            networkId = 2739,
        ),
    )

    // In-memory cache for ultra-fast, lag-free switching between providers
    private val providerCache = mutableMapOf<String, ProviderCatalogResult>()

    /**
     * Resolves curated sections for a given CloudStream provider (Netflix, Prime, HotStar).
     */
    suspend fun resolveProviderCatalog(providerId: String): ProviderCatalogResult = withContext(Dispatchers.Default) {
        providerCache[providerId]?.let { return@withContext it }

        val config = providerConfigs[providerId] ?: providerConfigs.values.first()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val language = normalizeTmdbLanguage(TmdbSettingsRepository.snapshot().language)

        coroutineScope {
            val trendingMoviesDeferred = async {
                fetchDiscover(
                    endpoint = "discover/movie",
                    apiKey = apiKey,
                    language = language,
                    watchProviderId = config.watchProviderId,
                    sortBy = "popularity.desc",
                    mediaType = "movie",
                )
            }
            val trendingTvDeferred = async {
                fetchDiscover(
                    endpoint = "discover/tv",
                    apiKey = apiKey,
                    language = language,
                    watchProviderId = config.watchProviderId,
                    sortBy = "popularity.desc",
                    mediaType = "series",
                    networkId = config.networkId,
                )
            }
            val topRatedMoviesDeferred = async {
                fetchDiscover(
                    endpoint = "discover/movie",
                    apiKey = apiKey,
                    language = language,
                    watchProviderId = config.watchProviderId,
                    sortBy = "vote_average.desc",
                    voteCountGte = 200,
                    mediaType = "movie",
                )
            }

            val movies = trendingMoviesDeferred.await()
            val tvShows = trendingTvDeferred.await()
            val topRated = topRatedMoviesDeferred.await()

            // Combine top movies and series for Featured Hero Carousel
            val heroItems = (movies.take(3) + tvShows.take(3)).shuffled().take(5)

            val sections = mutableListOf<HomeCatalogSection>()

            if (movies.isNotEmpty() || tvShows.isNotEmpty()) {
                val trendingCombined = (movies.take(5) + tvShows.take(5)).shuffled()
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

            if (movies.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_movies",
                        title = "Popular Movies",
                        subtitle = "${config.displayName} Originals & Blockbusters",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_movies"),
                        items = movies,
                    )
                )
            }

            if (tvShows.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_series",
                        title = "Popular Series",
                        subtitle = "${config.displayName} TV Shows & Drama",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "series", sectionType = "provider_series"),
                        items = tvShows,
                    )
                )
            }

            if (topRated.isNotEmpty()) {
                sections.add(
                    HomeCatalogSection(
                        key = "provider_${config.id}_top_rated",
                        title = "Top Rated",
                        subtitle = "Critically Acclaimed",
                        addonName = "CNC Verse",
                        target = CatalogTarget.Library(contentType = "movie", sectionType = "provider_top_rated"),
                        items = topRated,
                    )
                )
            }

            val result = ProviderCatalogResult(heroItems = heroItems, sections = sections)
            providerCache[providerId] = result
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

        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val language = normalizeTmdbLanguage(TmdbSettingsRepository.snapshot().language)

        coroutineScope {
            val trendingMoviesDeferred = async {
                fetchDiscover(
                    endpoint = "discover/movie",
                    apiKey = apiKey,
                    language = language,
                    sortBy = "popularity.desc",
                    mediaType = "movie",
                )
            }
            val trendingTvDeferred = async {
                fetchDiscover(
                    endpoint = "discover/tv",
                    apiKey = apiKey,
                    language = language,
                    sortBy = "popularity.desc",
                    mediaType = "series",
                )
            }
            val topRatedDeferred = async {
                fetchDiscover(
                    endpoint = "discover/movie",
                    apiKey = apiKey,
                    language = language,
                    sortBy = "vote_average.desc",
                    voteCountGte = 300,
                    mediaType = "movie",
                )
            }

            val movies = trendingMoviesDeferred.await()
            val tvShows = trendingTvDeferred.await()
            val topRated = topRatedDeferred.await()

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
                    key = "fallback_top_rated",
                    title = "Top Rated All-Time",
                    subtitle = "Audience Favorites",
                    addonName = "CloudStream Feed",
                    target = CatalogTarget.Library(contentType = "movie", sectionType = "fallback_top_rated"),
                    items = topRated,
                )
            )

            val result = ProviderCatalogResult(heroItems = heroItems, sections = sections)
            providerCache[cacheKey] = result
            result
        }
    }

    private suspend fun fetchDiscover(
        endpoint: String,
        apiKey: String,
        language: String,
        watchProviderId: String? = null,
        networkId: Int? = null,
        sortBy: String = "popularity.desc",
        voteCountGte: Int? = null,
        mediaType: String,
    ): List<MetaPreview> {
        val query = mutableMapOf(
            "language" to language,
            "sort_by" to sortBy,
            "page" to "1",
        )
        if (!watchProviderId.isNullOrBlank()) {
            query["with_watch_providers"] = watchProviderId
            query["watch_region"] = "US"
        }
        if (networkId != null && networkId > 0) {
            query["with_networks"] = networkId.toString()
        }
        if (voteCountGte != null && voteCountGte > 0) {
            query["vote_count.gte"] = voteCountGte.toString()
        }

        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            val responseText = httpGetText(url)
            val parsed = json.decodeFromString<TmdbDiscoverResult>(responseText)
            parsed.results.orEmpty().mapNotNull { item ->
                val title = item.title?.takeIf { it.isNotBlank() }
                    ?: item.name?.takeIf { it.isNotBlank() }
                    ?: item.originalTitle?.takeIf { it.isNotBlank() }
                    ?: item.originalName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                MetaPreview(
                    id = "tmdb:${item.id}",
                    type = mediaType,
                    name = title,
                    poster = imageUrl(item.posterPath, "w500") ?: imageUrl(item.backdropPath, "w780"),
                    banner = imageUrl(item.backdropPath, "w1280"),
                    posterShape = PosterShape.Poster,
                    description = item.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = (item.releaseDate ?: item.firstAirDate)?.take(4),
                    rawReleaseDate = item.releaseDate ?: item.firstAirDate,
                    popularity = item.popularity,
                    voteCount = item.voteCount,
                    imdbRating = item.voteAverage?.let { ((it * 10).roundToInt() / 10.0).toString() },
                )
            }
        }.onFailure { error ->
            log.w { "Failed to fetch discover feed from $endpoint: ${error.message}" }
        }.getOrDefault(emptyList())
    }

    private fun imageUrl(path: String?, size: String): String? {
        val clean = path?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    @Serializable
    private data class TmdbDiscoverResult(
        val page: Int? = null,
        val results: List<TmdbDiscoverItem>? = null,
    )

    @Serializable
    private data class TmdbDiscoverItem(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @SerialName("original_title") val originalTitle: String? = null,
        @SerialName("original_name") val originalName: String? = null,
        val overview: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        @SerialName("vote_average") val voteAverage: Double? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
        val popularity: Double? = null,
    )
}
