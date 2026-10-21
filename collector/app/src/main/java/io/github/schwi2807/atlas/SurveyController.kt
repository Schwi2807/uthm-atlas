package io.github.schwi2807.atlas

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import io.github.schwi2807.atlas.capture.Averager
import io.github.schwi2807.atlas.capture.CaptureResult
import io.github.schwi2807.atlas.capture.CaptureSettings
import io.github.schwi2807.atlas.capture.Sample
import io.github.schwi2807.atlas.capture.StopReason
import io.github.schwi2807.atlas.data.AtlasDb
import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.data.FixEntity
import io.github.schwi2807.atlas.data.SessionEntity
import io.github.schwi2807.atlas.data.TrackPointEntity
import io.github.schwi2807.atlas.export.DeviceInfo
import io.github.schwi2807.atlas.export.SensorInfo
import io.github.schwi2807.atlas.export.SurveyExport
import io.github.schwi2807.atlas.gnss.GnssMonitor
import io.github.schwi2807.atlas.sensors.Sensors
import io.github.schwi2807.atlas.suggest.SuggestionPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Capture lifecycle shown by the HUD. */
sealed interface CaptureUi {
    data object Idle : CaptureUi
    data class Running(val result: CaptureResult?, val scatter: List<Pair<Double, Double>>, val lastAccM: Double?, val target: Int) : CaptureUi
    data class Done(val result: CaptureResult, val reason: StopReason, val weak: Boolean, val featureId: String?) : CaptureUi
}

/**
 * Application-wide survey state: active session, fix recording, the averaged capture and saving/exporting.
 * Lives as long as the process (the foreground TrackService keeps that alive while walking with the screen off).
 */
class SurveyController(private val app: Context) {
    val db = AtlasDb.get(app)
    val gnss = GnssMonitor(app)
    val sensors = Sensors(app)
    val suggestions = SuggestionPack(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("atlas", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(prefs.getString("session", null))
    val sessionId: StateFlow<String?> = _session.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _capture = MutableStateFlow<CaptureUi>(CaptureUi.Idle)
    val capture: StateFlow<CaptureUi> = _capture.asStateFlow()

    var settings = loadSettings()
        private set
    private var averager: Averager? = null
    private var recapturing: String? = null
    private var lastTrack: Location? = null
    private var segment = -1

    init {
        scope.launch { gnss.fixes.collect { onFix(it) } }
        sensors.floorHeightM = prefs.getFloat("floor_h", 3.6f)
    }

    // ------------------------------------------------------------------ settings
    private fun loadSettings() = CaptureSettings(
        targetSamples = prefs.getInt("n", 30),
        stableAccM = prefs.getFloat("stable", 4f).toDouble(),
        warnAccM = prefs.getFloat("warn", 10f).toDouble(),
    )

    fun updateSettings(n: Int, stable: Double, warn: Double, floorH: Float) {
        prefs.edit().putInt("n", n.coerceIn(5, 300)).putFloat("stable", stable.toFloat()).putFloat("warn", warn.toFloat())
            .putFloat("floor_h", floorH).apply()
        sensors.floorHeightM = floorH
        settings = loadSettings()
    }

    // ------------------------------------------------------------------ sessions
    suspend fun newSession(title: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val t = title?.takeIf { it.isNotBlank() } ?: ("Walk " + SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(now)))
        db.sessions().upsert(SessionEntity(id, t.take(80), now))
        selectSession(id)
        return id
    }

    fun selectSession(id: String?) {
        _session.value = id
        prefs.edit().putString("session", id).apply()
        segment = -1
    }

    suspend fun endSession(id: String) {
        db.sessions().get(id)?.let { db.sessions().upsert(it.copy(endedAt = System.currentTimeMillis())) }
    }

    suspend fun deleteSession(id: String) {
        db.features().listForSession(id).forEach { f -> photoFiles(f).forEach { it.delete() } }
        db.features().deleteSession(id)
        db.fixes().deleteSession(id)
        db.track().deleteSession(id)
        db.sessions().delete(id)
        if (_session.value == id) selectSession(null)
    }

    // ------------------------------------------------------------------ recording (breadcrumb + raw fixes)
    fun setRecording(on: Boolean) {
        _recording.value = on
        if (!on) lastTrack = null
    }

    private suspend fun ensureSegment(sid: String): Int {
        if (segment < 0) segment = db.track().lastSegment(sid) + 1
        return segment
    }

    private fun onFix(loc: Location) {
        sensors.setLocation(loc.latitude, loc.longitude, loc.altitude, loc.time)
        val st = gnss.state.value
        val sid = _session.value
        // capture first: it should never wait on the database
        averager?.let { av ->
            val reason = av.add(Sample(loc.time, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null,
                loc.accuracy.toDouble(), st.used, st.visible))
            if (Averager.isGoodFix(loc.accuracy.toDouble(), av.settings)) tick()
            if (reason != null) finishCapture(reason)
            else _capture.value = CaptureUi.Running(av.result(), av.scatter(), loc.accuracy.toDouble(), av.settings.targetSamples)
        }
        if (sid == null || !(_recording.value || averager != null)) return
        scope.launch(Dispatchers.IO) {
            db.fixes().insert(FixEntity(sessionId = sid, tMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                alt = if (loc.hasAltitude()) loc.altitude else null, acc = loc.accuracy,
                speed = if (loc.hasSpeed()) loc.speed else null, bearing = if (loc.hasBearing()) loc.bearing else null,
                provider = loc.provider ?: "gps", satsUsed = st.used, satsVisible = st.visible))
            if (_recording.value) {
                val prev = lastTrack
                if (prev == null || prev.distanceTo(loc) >= 3f || loc.time - prev.time >= 15_000) {
                    if (prev != null && loc.time - prev.time > 120_000) segment = -1 // a long gap starts a new segment
                    db.track().insert(TrackPointEntity(sessionId = sid, tMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                        acc = loc.accuracy, segment = ensureSegment(sid)))
                    lastTrack = loc
                }
            }
        }
    }

    // ------------------------------------------------------------------ averaged capture
    fun startCapture(recaptureOf: String? = null) {
        recapturing = recaptureOf
        averager = Averager(settings)
        _capture.value = CaptureUi.Running(null, emptyList(), null, settings.targetSamples)
    }

    fun stopCapture() {
        if (averager?.count ?: 0 > 0) finishCapture(StopReason.MANUAL) else cancelCapture()
    }

    fun cancelCapture() {
        averager = null
        recapturing = null
        _capture.value = CaptureUi.Idle
    }

    private fun finishCapture(reason: StopReason) {
        val av = averager ?: return
        val r = av.result() ?: return cancelCapture()
        averager = null
        val weak = r.isWeak(settings)
        buzz(if (weak) longArrayOf(0, 120, 80, 120, 80, 120) else longArrayOf(0, 60, 60, 60))
        val target = recapturing
        recapturing = null
        _capture.value = CaptureUi.Done(r, reason, weak, target)
        if (target != null) scope.launch { applyRecapture(target, r, reason) }
    }

    private suspend fun applyRecapture(id: String, r: CaptureResult, reason: StopReason) {
        val f = db.features().get(id) ?: return
        db.features().upsert(f.withCapture(r, reason).copy(recaptures = f.recaptures + 1, updatedAt = System.currentTimeMillis(),
            lastVerified = today()))
        db.fixes().tag(f.sessionId, r.startMs, r.endMs, id)
    }

    fun clearDone() { if (_capture.value is CaptureUi.Done) _capture.value = CaptureUi.Idle }

    // ------------------------------------------------------------------ saving features
    suspend fun saveFeature(draft: FeatureEntity): FeatureEntity {
        val now = System.currentTimeMillis()
        val f = draft.copy(updatedAt = now)
        db.features().upsert(f)
        db.fixes().tag(f.sessionId, f.captureStart, f.captureEnd, f.id)
        return f
    }

    suspend fun deleteFeature(f: FeatureEntity) {
        photoFiles(f).forEach { it.delete() }
        db.features().delete(f)
    }

    fun newFeature(sessionId: String, r: CaptureResult, reason: StopReason): FeatureEntity {
        val now = System.currentTimeMillis()
        return FeatureEntity(
            id = UUID.randomUUID().toString(), sessionId = sessionId, kind = "poi", category = "poi_other", nameEn = "",
            lon = r.lon, lat = r.lat, alt = r.alt, n = r.n, stdM = r.stdM, meanAccM = r.meanAccM, bestAccM = r.bestAccM,
            satsUsed = r.satsUsed, satsVisible = r.satsVisible, stopReason = reason.name, captureStart = r.startMs,
            captureEnd = r.endMs, createdAt = now, updatedAt = now, lastVerified = today(),
        )
    }

    private fun FeatureEntity.withCapture(r: CaptureResult, reason: StopReason) = copy(
        lon = r.lon, lat = r.lat, alt = r.alt, n = r.n, stdM = r.stdM, meanAccM = r.meanAccM, bestAccM = r.bestAccM,
        satsUsed = r.satsUsed, satsVisible = r.satsVisible, stopReason = reason.name, captureStart = r.startMs, captureEnd = r.endMs,
    )

    // ------------------------------------------------------------------ photos
    val photoDir: File get() = File(app.filesDir, "photos").apply { mkdirs() }
    fun photoFiles(f: FeatureEntity): List<File> =
        Regex("\"local\":\"([^\"]+)\"").findAll(f.photosJson).map { File(photoDir, it.groupValues[1]) }.toList()

    // ------------------------------------------------------------------ export
    suspend fun export(sessionId: String): File = withContext(Dispatchers.IO) {
        val s = db.sessions().get(sessionId) ?: error("no session $sessionId")
        val features = db.features().listForSession(sessionId)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val safe = s.title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "walk" }
        val file = File(dir, "$safe-${s.id.take(8)}.uthmsurvey")
        val st = gnss.state.value
        file.outputStream().use { out ->
            SurveyExport.write(
                out = out, schemaVersion = BuildConfig.SCHEMA_VERSION, appVersion = BuildConfig.VERSION_NAME,
                campusId = BuildConfig.CAMPUS_ID, session = s, features = features,
                track = db.track().forSession(sessionId), fixes = db.fixes().forSession(sessionId),
                device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, st.hardware),
                sensors = SensorInfo(sensors.availability.barometer, sensors.availability.compass, sensors.availability.camera,
                    st.raw.supported == true),
                settings = mapOf("capture_samples" to settings.targetSamples, "stable_acc_m" to settings.stableAccM,
                    "warn_acc_m" to settings.warnAccM, "floor_height_m" to sensors.floorHeightM),
                exportedAt = System.currentTimeMillis(),
                photo = { name -> File(photoDir, name).takeIf { it.exists() }?.readBytes() },
            )
        }
        db.sessions().upsert(s.copy(exportedAt = System.currentTimeMillis()))
        file
    }

    /** Copy an export into the public Downloads folder (MediaStore; no storage permission needed on API 29+). */
    suspend fun saveToDownloads(file: File): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            // octet-stream: with "application/zip" MediaStore renames the file to .uthmsurvey.zip
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UTHM Atlas")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        uri
    }

    // ------------------------------------------------------------------ haptics
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    }

    private fun tick() = vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    private fun buzz(pattern: LongArray) = vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))

    companion object {
        fun today(): String = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString()
    }
}
