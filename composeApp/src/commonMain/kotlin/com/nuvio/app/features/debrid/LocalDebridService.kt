package com.nuvio.app.features.debrid

import com.nuvio.app.features.addons.httpRequestRaw

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?,
)

internal object LocalDebridService {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            DebridProviders.REAL_DEBRID_ID -> checkRealDebridCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            if (!response.isSuccessful || response.body?.success == false) {
                null
            } else {
                response.body?.data.orEmpty().mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
                    LocalDebridCachedItem(
                        name = value.name,
                        size = value.size,
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val sources = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            val response = PremiumizeApiClient.checkCache(apiKey = apiKey, items = sources)
            val body = response.body
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)?.asLongOrNull(),
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkRealDebridCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val path = normalizedHashes.joinToString("/")
            val response = httpRequestRaw(
                method = "GET",
                url = "https://api.real-debrid.com/rest/1.0/torrents/instantAvailability/$path",
                headers = listOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
                body = "",
            )
            if (response.status !in 200..299) {
                null
            } else {
                val element = DebridApiJson.json.parseToJsonElement(response.body)
                val obj = element as? JsonObject ?: return null
                val result = mutableMapOf<String, LocalDebridCachedItem>()
                for ((hash, valElem) in obj) {
                    val hostObj = valElem as? JsonObject ?: continue
                    val rdVariant = hostObj["rd"] as? JsonArray
                    if (rdVariant != null && rdVariant.isNotEmpty()) {
                        result[hash.lowercase()] = LocalDebridCachedItem(name = null, size = null)
                    }
                }
                result
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
}


private fun JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}
