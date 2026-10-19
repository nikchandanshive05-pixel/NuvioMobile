package com.nuvio.app.features.plugins.cloudstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cloudstream Repository Schema (repo.json / CNC.json)
 */
@Serializable
data class CloudstreamRepository(
    val name: String,
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
    val iconUrl: String? = null,
)

/**
 * Cloudstream Provider Plugin metadata (items in plugins.json)
 */
@Serializable
data class CloudstreamSitePlugin(
    val name: String,
    val internalName: String,
    val url: String,
    val version: Int = 1,
    @SerialName("apiVersion") val apiVersion: Int = 1,
    val description: String? = null,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
    val tvTypes: List<String>? = null,
    val language: String? = null,
    val status: Int = 1,
    val fileHash: String? = null,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null,
)

/**
 * Manifest contained inside each .cs3 zip package
 */
@Serializable
data class CloudstreamCs3Manifest(
    val name: String,
    val version: Int = 1,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
)
