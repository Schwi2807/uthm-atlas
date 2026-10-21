package io.github.schwi2807.atlas.reverify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The published map (campus.bundle) on the phone, for re-verify mode. Starts with the copy built into the APK;
 * "Update" fetches the live one from the site so the stale list matches what newcomers see.
 */
class CampusRepo(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "campus.bundle")
    private val _campus = MutableStateFlow<Campus?>(null)
    val campus: StateFlow<Campus?> = _campus.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (_campus.value != null) return@withContext
        val bytes = if (file.exists()) file.readBytes() else ctx.assets.open("map/campus.bundle").use { it.readBytes() }
        _campus.value = runCatching { CampusBundle.parse(bytes) }.getOrElse {
            _message.value = "Could not read the campus bundle: ${it.message}"; null
        }
    }

    suspend fun update(url: String = DEFAULT_URL) = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val bytes = c.inputStream.use { it.readBytes() }
            val parsed = CampusBundle.parse(bytes)
            file.writeBytes(bytes)
            _campus.value = parsed
            _message.value = "Updated to the build of ${parsed.date}"
        }.onFailure { _message.value = "Update failed (is the site public and online?): ${it.message}" }
    }

    companion object {
        const val DEFAULT_URL = "https://schwi2807.github.io/uthm-atlas/data/campus.bundle"
    }
}
