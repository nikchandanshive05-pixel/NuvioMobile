package com.nuvio.app.features.plugins

internal fun pluginContentId(
    videoId: String,
    season: Int?,
    episode: Int?,
): String {
    val trimmed = videoId.trim()
    if (trimmed.isBlank() || trimmed.startsWith("cloudstream|")) return videoId

    val withoutPrefix = when {
        trimmed.startsWith("tmdb:") -> trimmed.removePrefix("tmdb:")
        trimmed.startsWith("tmdb/") -> trimmed.removePrefix("tmdb/")
        else -> trimmed
    }

    val withoutEpisodeSuffix = if (season != null && episode != null) {
        withoutPrefix.removeSuffix(":$season:$episode")
    } else {
        withoutPrefix
    }

    return withoutEpisodeSuffix.substringBefore('/').ifBlank { trimmed }
}

internal data class CloudstreamContentId(val scraperId: String, val url: String) {
    companion object {
        private const val PREFIX = "cloudstream|"

        fun create(scraperId: String, url: String): String =
            PREFIX + scraperId.replace("|", "%7C") + "|" + url.replace("|", "%7C")

        fun parse(value: String): CloudstreamContentId? {
            if (!value.startsWith(PREFIX)) return null
            val rest = value.removePrefix(PREFIX)
            val separator = rest.indexOf('|')
            if (separator <= 0 || separator == rest.lastIndex) return null
            return CloudstreamContentId(
                scraperId = rest.substring(0, separator).replace("%7C", "|"),
                url = rest.substring(separator + 1).replace("%7C", "|"),
            )
        }
    }
}
