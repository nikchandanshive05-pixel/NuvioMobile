package com.nuvio.app.features.plugins.cloudstream

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginSubtitleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Executes installed .cs3 providers through CloudStream's shared Android API. */
actual object CloudstreamExtensionRuntime {
    private const val TAG = "CloudstreamRuntime"
    @Volatile private var appContext: Context? = null
    private val providers = ConcurrentHashMap<String, Any>()
    private val pluginInstances = ConcurrentHashMap<String, Any>()
    private val loadLock = Any()

    actual fun initialize(context: Any) {
        appContext = context as? Context
    }

    actual suspend fun search(query: String, scrapers: List<PluginScraper>): List<CloudstreamSearchHit> {
        if (query.isBlank()) return emptyList()
        val candidates = scrapers.filter { it.filename.substringBefore('?').endsWith(".cs3", true) }
        val gate = Semaphore(4)
        val results = Collections.synchronizedList(mutableListOf<CloudstreamSearchHit>())
        withContext(Dispatchers.IO) {
            coroutineScope {
                candidates.map { scraper ->
                    async {
                        gate.withPermit {
                            runCatching {
                                val provider = providerFor(scraper) ?: return@runCatching
                                val method = suspendMethod(provider.javaClass, "search", 1) ?: return@runCatching
                                val found = withTimeoutOrNull(18_000) {
                                    invokeSuspend(provider, method, arrayOf(query)) as? Iterable<*>
                                } ?: return@runCatching
                                found.filterNotNull().forEach { item ->
                                    val url = property(item, "url")?.toString().orEmpty()
                                    val title = property(item, "name")?.toString().orEmpty()
                                    if (url.isBlank() || title.isBlank()) return@forEach
                                    val type = property(item, "type")?.toString().orEmpty()
                                    results += CloudstreamSearchHit(
                                        scraperId = scraper.id,
                                        scraperName = scraper.name,
                                        title = title,
                                        url = url,
                                        poster = property(item, "posterUrl")?.toString(),
                                        mediaType = if (
                                            type.contains("series", true) ||
                                            type.contains("anime", true) ||
                                            type.contains("episode", true)
                                        ) "tv" else "movie",
                                        year = property(item, "year")?.toString()?.toIntOrNull(),
                                    )
                                }
                            }.onFailure { error ->
                                Log.w(TAG, "Provider search failed: ${scraper.name}", error)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return results.distinctBy { "${it.scraperId}|${it.url}" }
    }


    actual suspend fun homeCatalog(scraper: PluginScraper): CloudstreamHomeCatalog? =
        withContext(Dispatchers.IO) {
            val provider = providerFor(scraper) ?: return@withContext null
            val pages = property(provider, "mainPage") as? Iterable<*>
            if (pages == null || pages.none()) {
                return@withContext searchHomeFeed(provider, scraper)
            }
            val requestClass = Class.forName("com.lagradost.cloudstream3.MainPageRequest", true, appContext?.classLoader)
            val constructor = requestClass.getConstructor(String::class.java, String::class.java, java.lang.Boolean.TYPE)
            val getMainPage = provider.javaClass.methods.firstOrNull {
                it.name == "getMainPage" && it.parameterTypes.size == 3 &&
                    it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
            } ?: return@withContext null
            val feeds = pages.filterNotNull().take(8).mapNotNull { page ->
                val title = property(page, "name")?.toString().orEmpty()
                val data = property(page, "data")?.toString().orEmpty()
                if (title.isBlank() || data.isBlank()) return@mapNotNull null
                val horizontal = property(page, "horizontalImages") as? Boolean ?: false
                runCatching {
                    val request = constructor.newInstance(title, data, horizontal)
                    val response = withTimeoutOrNull(12_000) {
                        invokeSuspend(provider, getMainPage, arrayOf(1, request))
                    } ?: return@runCatching null
                    val lists = property(response, "items") as? Iterable<*> ?: return@runCatching null
                    val items: List<CloudstreamSearchHit> = lists.filterNotNull().flatMap { homeList: Any ->
                        val feedItems: List<Any?> =
                            (property(homeList, "list") as? Iterable<*>)?.toList() ?: emptyList()
                        feedItems.filterNotNull().mapNotNull { item: Any ->
                            val url = property(item, "url")?.toString().orEmpty()
                            val name = property(item, "name")?.toString().orEmpty()
                            if (url.isBlank() || name.isBlank()) return@mapNotNull null
                            val itemType = property(item, "type")?.toString().orEmpty()
                            CloudstreamSearchHit(
                                scraperId = scraper.id,
                                scraperName = scraper.name,
                                title = name,
                                url = url,
                                poster = property(item, "posterUrl")?.toString(),
                                mediaType = if (itemType.contains("series", true) || itemType.contains("anime", true)) "tv" else "movie",
                                year = property(item, "year")?.toString()?.toIntOrNull(),
                            )
                        }
                    }
                    CloudstreamHomeFeed(title, horizontal, items)
                }.getOrNull()?.takeIf { it.items.isNotEmpty() }
            }
            if (feeds.isNotEmpty()) {
                CloudstreamHomeCatalog(feeds)
            } else {
                searchHomeFeed(provider, scraper)
            }
        }

    private suspend fun searchHomeFeed(provider: Any, scraper: PluginScraper): CloudstreamHomeCatalog? {
        val method = suspendMethod(provider.javaClass, "search", 1) ?: return null
        val results = withTimeoutOrNull(15_000) {
            invokeSuspend(provider, method, arrayOf("latest")) as? Iterable<*>
        } ?: return null
        val items = results.filterNotNull().mapNotNull { item ->
            val url = property(item, "url")?.toString().orEmpty()
            val title = property(item, "name")?.toString().orEmpty()
            if (url.isBlank() || title.isBlank()) return@mapNotNull null
            val type = property(item, "type")?.toString().orEmpty()
            CloudstreamSearchHit(
                scraperId = scraper.id,
                scraperName = scraper.name,
                title = title,
                url = url,
                poster = property(item, "posterUrl")?.toString(),
                mediaType = if (type.contains("series", true) || type.contains("anime", true)) "tv" else "movie",
                year = property(item, "year")?.toString()?.toIntOrNull(),
            )
        }
        return CloudstreamHomeCatalog(
            listOf(CloudstreamHomeFeed("Latest from ${scraper.name}", false, items)),
        ).takeIf { items.isNotEmpty() }
    }

    actual suspend fun load(scraper: PluginScraper, itemUrl: String): CloudstreamLoadedItem? =
        withContext(Dispatchers.IO) {
            val provider = providerFor(scraper) ?: return@withContext null
            val method = suspendMethod(provider.javaClass, "load", 1) ?: return@withContext null
            val response = withTimeoutOrNull(25_000) {
                invokeSuspend(provider, method, arrayOf(itemUrl))
            } ?: return@withContext null
            if (response == null) return@withContext null
            val responseClass = response.javaClass.simpleName
            val rawEpisodes: List<Any?> =
                (property(response, "episodes") as? Iterable<*>)?.toList() ?: emptyList()
            val episodes: List<CloudstreamEpisode> = rawEpisodes.filterNotNull().mapNotNull { episode: Any ->
                val dataUrl = property(episode, "data")?.toString()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                CloudstreamEpisode(
                    title = property(episode, "name")?.toString().orEmpty().ifBlank { "Episode" },
                    dataUrl = dataUrl,
                    season = property(episode, "season")?.toString()?.toIntOrNull(),
                    episode = property(episode, "episode")?.toString()?.toIntOrNull(),
                    overview = property(episode, "description")?.toString(),
                )
            }
            CloudstreamLoadedItem(
                title = property(response, "name")?.toString().orEmpty().ifBlank { "Untitled" },
                poster = property(response, "posterUrl")?.toString(),
                description = property(response, "plot")?.toString(),
                mediaType = if (responseClass.contains("series", true) || responseClass.contains("anime", true)) "tv" else "movie",
                year = property(response, "year")?.toString()?.toIntOrNull(),
                dataUrl = property(response, "dataUrl")?.toString().orEmpty().ifBlank { itemUrl },
                episodes = episodes,
            )
        }

    actual suspend fun loadLinks(scraper: PluginScraper, dataUrl: String): List<PluginRuntimeResult> =
        withContext(Dispatchers.IO) {
            val provider = providerFor(scraper) ?: return@withContext emptyList()
            val method = provider.javaClass.methods.firstOrNull {
                it.name == "loadLinks" &&
                    it.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation" &&
                    it.parameterTypes.size >= 5
            } ?: return@withContext emptyList()
            val links = Collections.synchronizedList(mutableListOf<PluginRuntimeResult>())
            val subtitles = Collections.synchronizedList(mutableListOf<PluginSubtitleResult>())
            val callbacks: List<(Any?) -> Unit> = listOf(
                { subtitle: Any? ->
                    val url = property(subtitle, "url")?.toString().orEmpty()
                    if (url.isNotBlank()) subtitles += PluginSubtitleResult(
                        url = url,
                        language = property(subtitle, "lang")?.toString().orEmpty().ifBlank { "Unknown" },
                        name = property(subtitle, "label")?.toString(),
                    )
                },
                { link: Any? ->
                    val url = property(link, "url")?.toString().orEmpty()
                    if (url.isNotBlank()) links += PluginRuntimeResult(
                        title = property(link, "name")?.toString().orEmpty().ifBlank { "Stream" },
                        url = url,
                        quality = property(link, "quality")?.toString(),
                        provider = property(link, "source")?.toString().orEmpty().ifBlank { scraper.id.substringAfterLast(':') },
                        type = property(link, "type")?.toString(),
                        headers = property(link, "headers") as? Map<String, String>,
                    )
                },
            )
            var callbackIndex = 0
            val args = method.parameterTypes.dropLast(1).mapIndexed { index, parameter ->
                when {
                    index == 0 && parameter == String::class.java -> dataUrl
                    parameter == java.lang.Boolean.TYPE || parameter == java.lang.Boolean::class.java -> false
                    parameter.name == "kotlin.jvm.functions.Function1" -> callbacks[callbackIndex++]
                    else -> null
                }
            }.toTypedArray()
            withTimeoutOrNull(60_000) { invokeSuspend(provider, method, args) }
            links.map { it.copy(subtitles = subtitles.toList()) }
        }

    private suspend fun providerFor(scraper: PluginScraper): Any? {
        providers[scraper.id]?.let { return it }
        synchronized(loadLock) {
            providers[scraper.id]?.let { return it }
            val context = appContext ?: return null
            val packageFile = downloadPackage(context, scraper.filename) ?: return null
            val manifest = ZipFile(packageFile).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith("manifest.json") }
                    ?: return null
                JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            }
            val className = manifest.optString("pluginClassName").takeIf(String::isNotBlank) ?: return null
            val loader = DexClassLoader(
                packageFile.absolutePath,
                context.getDir("cloudstream-dex", Context.MODE_PRIVATE).absolutePath,
                null,
                context.classLoader,
            )
            val apiHolderClass = Class.forName("com.lagradost.cloudstream3.APIHolder", true, context.classLoader)
            val apiHolder = apiHolderClass.getField("INSTANCE").get(null)
            val before = providerList(apiHolderClass, apiHolder).toSet()
            val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance()
            runCatching {
                plugin.javaClass.getMethod("setFilename", String::class.java).invoke(plugin, scraper.id)
            }
            val loadWithContext = plugin.javaClass.methods.firstOrNull {
                it.name == "load" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "android.content.Context"
            }
            if (loadWithContext != null) loadWithContext.invoke(plugin, context)
            else plugin.javaClass.getMethod("load").invoke(plugin)
            val registered = providerList(apiHolderClass, apiHolder).filterNot { it in before }
            val provider = registered.firstOrNull { property(it, "sourcePlugin")?.toString() == scraper.id }
                ?: registered.firstOrNull { property(it, "name")?.toString().equals(scraper.name, true) }
                ?: registered.lastOrNull()
                ?: return null
            pluginInstances[scraper.id] = plugin
            providers[scraper.id] = provider
            return provider
        }
    }

    private fun providerList(holderClass: Class<*>, holder: Any): List<Any> =
        (holderClass.getMethod("getAllProviders").invoke(holder) as? Iterable<*>)
            ?.filterNotNull()?.toList().orEmpty()

    private fun downloadPackage(context: Context, packageUrl: String): File? = runCatching {
        if (!packageUrl.startsWith("https://", true)) return null
        val file = File(context.cacheDir, "cloudstream-${packageUrl.hashCode().toUInt().toString(16)}.cs3")
        if (file.length() > 0L) return file
        val connection = URL(packageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Nuvio CloudStream provider runtime")
        connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        file.takeIf { it.length() > 0L }
    }.getOrNull()

    private fun suspendMethod(type: Class<*>, name: String, argumentCount: Int) =
        type.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == argumentCount + 1 &&
                it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
        }

    private suspend fun invokeSuspend(target: Any, method: java.lang.reflect.Method, args: Array<Any?>): Any? =
        suspendCancellableCoroutine { cancellable ->
            val callArgs = args + object : Continuation<Any?> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Any?>) {
                    result.fold(cancellable::resume, cancellable::resumeWithException)
                }
            }
            try {
                val immediate = method.invoke(target, *callArgs)
                if (immediate !== COROUTINE_SUSPENDED) cancellable.resume(immediate)
            } catch (error: Throwable) {
                cancellable.resumeWithException(error.cause ?: error)
            }
        }

    private fun property(value: Any?, name: String): Any? {
        if (value == null) return null
        val cap = name.replaceFirstChar { it.uppercase() }
        return runCatching { value.javaClass.methods.firstOrNull { it.name == "get$cap" }?.invoke(value) }.getOrNull()
            ?: runCatching { value.javaClass.methods.firstOrNull { it.name == "is$cap" }?.invoke(value) }.getOrNull()
            ?: runCatching { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) }.getOrNull()
    }
}
