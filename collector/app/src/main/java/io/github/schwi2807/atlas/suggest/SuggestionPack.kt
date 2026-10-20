package io.github.schwi2807.atlas.suggest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class SuggestionItem(
    val name: String,
    val name_ms: String? = null,
    val name_en: String? = null,
    val code: String? = null,
    val source: String,             // osm | wikidata
    val ref: String,
    val lon: Double,
    val lat: Double,
    val hint: String? = null,       // category guess from OSM tags
)

@Serializable
data class SuggestionFile(
    val format: String,
    val version: Int,
    val campus_id: String,
    val created_at: String,
    val attribution: String,
    val items: List<SuggestionItem>,
)

data class Suggestion(val item: SuggestionItem, val distanceM: Double)

data class PackStatus(val loaded: Boolean, val items: Int = 0, val createdAt: String? = null, val message: String? = null)

/**
 * Offline name-suggestion pack (OSM names + Wikidata labels for the campus, built by `atlas suggest`).
 * Downloaded on Wi-Fi beforehand (from the published site) or imported from a file. Suggestions are advisory:
 * the surveyor always types or confirms the final name, and nothing is ever auto-accepted.
 */
class SuggestionPack(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(ctx.filesDir, "suggestions.json")
    private var items: List<SuggestionItem> = emptyList()
    private val _status = MutableStateFlow(PackStatus(false))
    val status: StateFlow<PackStatus> = _status.asStateFlow()

    init { runCatching { load(file.readText()) } }

    private fun load(text: String) {
        val f = json.decodeFromString(SuggestionFile.serializer(), text)
        require(f.format == "uthm-atlas-suggestions") { "not a suggestion pack" }
        items = f.items
        _status.value = PackStatus(true, items.size, f.created_at)
    }

    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun download(url: String, allowMetered: Boolean = false): PackStatus = withContext(Dispatchers.IO) {
        if (!allowMetered && !onWifi()) return@withContext _status.value.copy(message = "Connect to Wi-Fi first (or allow mobile data)")
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val text = c.inputStream.use { it.readBytes().decodeToString() }
            load(text)
            file.writeText(text)
            _status.value
        }.getOrElse { _status.value.copy(message = "Download failed: ${it.message}") }.also { _status.value = it }
    }

    suspend fun import(uri: Uri): PackStatus = withContext(Dispatchers.IO) {
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            load(text)
            file.writeText(text)
            _status.value
        }.getOrElse { _status.value.copy(message = "Not a suggestion pack: ${it.message}") }.also { _status.value = it }
    }

    fun nearby(lat: Double, lon: Double, radiusM: Double = 60.0, max: Int = 8): List<Suggestion> =
        items.asSequence()
            .map { Suggestion(it, distance(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceM <= radiusM }
            .sortedBy { it.distanceM }
            .distinctBy { it.item.name.lowercase() }
            .take(max)
            .toList()

    companion object {
        const val DEFAULT_URL = "https://schwi2807.github.io/uthm-atlas/data/suggestions.json"

        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = PI / 180
            val h = sin((lat2 - lat1) * r / 2).let { it * it } +
                cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
            return 2 * 6_371_008.8 * asin(sqrt(h))
        }
    }
}
