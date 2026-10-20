package io.github.schwi2807.atlas.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Constellation(val label: String, val short: String) {
    GPS("GPS", "GPS"), GLONASS("GLONASS", "GLO"), GALILEO("Galileo", "GAL"), BEIDOU("BeiDou", "BDS"),
    QZSS("QZSS", "QZS"), SBAS("SBAS", "SBA"), IRNSS("NavIC", "IRN"), OTHER("Other", "UNK");

    companion object {
        fun of(type: Int) = when (type) {
            GnssStatus.CONSTELLATION_GPS -> GPS
            GnssStatus.CONSTELLATION_GLONASS -> GLONASS
            GnssStatus.CONSTELLATION_GALILEO -> GALILEO
            GnssStatus.CONSTELLATION_BEIDOU -> BEIDOU
            GnssStatus.CONSTELLATION_QZSS -> QZSS
            GnssStatus.CONSTELLATION_SBAS -> SBAS
            GnssStatus.CONSTELLATION_IRNSS -> IRNSS
            else -> OTHER
        }
    }
}

data class Sat(
    val svid: Int,
    val constellation: Constellation,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val carrierHz: Float?,
)

data class ConstellationCount(val c: Constellation, val used: Int, val visible: Int)

data class RawStatus(
    val supported: Boolean?,      // null = not asked yet
    val enabled: Boolean = false,
    val measurements: Int = 0,
    val dualFrequency: Boolean = false,
    val lastClockNs: Long? = null,
    val note: String? = null,
)

data class GnssState(
    val hasPermission: Boolean = false,
    val providerEnabled: Boolean = true,
    val fix: Location? = null,
    val fixAgeMs: Long = Long.MAX_VALUE,
    val sats: List<Sat> = emptyList(),
    val ttffMs: Int? = null,
    val raw: RawStatus = RawStatus(null),
    val hardware: String? = null,
) {
    val used get() = sats.count { it.usedInFix }
    val visible get() = sats.size
    val perConstellation: List<ConstellationCount>
        get() = Constellation.entries.mapNotNull { c ->
            val s = sats.filter { it.constellation == c }
            if (s.isEmpty()) null else ConstellationCount(c, s.count { it.usedInFix }, s.size)
        }
    val meanCn0Used get() = sats.filter { it.usedInFix }.map { it.cn0DbHz }.average().takeIf { !it.isNaN() }
}

/**
 * Wraps LocationManager's GPS provider (raw GNSS fixes, not the fused location, so every capture is a real
 * satellite fix) plus GnssStatus and optional raw measurements. Fixes are also published as a flow for the
 * averager, the track recorder and the coverage layer.
 */
class GnssMonitor(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state.asStateFlow()
    private val _fixes = MutableSharedFlow<Location>(extraBufferCapacity = 64)
    val fixes: SharedFlow<Location> = _fixes.asSharedFlow()
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _state.update { it.copy(fix = location, fixAgeMs = 0) }
            _fixes.tryEmit(location)
        }
        override fun onProviderEnabled(provider: String) = _state.update { it.copy(providerEnabled = true) }
        override fun onProviderDisabled(provider: String) = _state.update { it.copy(providerEnabled = false) }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = (0 until status.satelliteCount).map { i ->
                Sat(
                    svid = status.getSvid(i),
                    constellation = Constellation.of(status.getConstellationType(i)),
                    azimuthDeg = status.getAzimuthDegrees(i),
                    elevationDeg = status.getElevationDegrees(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    usedInFix = status.usedInFix(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                )
            }
            _state.update { it.copy(sats = list) }
        }
        override fun onFirstFix(ttffMillis: Int) = _state.update { it.copy(ttffMs = ttffMillis) }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val ms = event.measurements
            val dual = ms.map { if (it.hasCarrierFrequencyHz()) (it.carrierFrequencyHz / 1e6).toInt() else 1575 }
                .distinct().any { it in 1160..1280 } // L5/E5a/B2a band present
            _state.update {
                it.copy(raw = it.raw.copy(supported = true, measurements = ms.size, dualFrequency = dual,
                    lastClockNs = event.clock.timeNanos))
            }
        }
        override fun onStatusChanged(status: Int) {
            val (ok, note) = when (status) {
                STATUS_READY -> true to null
                STATUS_NOT_SUPPORTED -> false to "This phone does not report raw GNSS measurements"
                STATUS_LOCATION_DISABLED -> null to "Location is off"
                else -> null to "Raw measurements unavailable (status $status)"
            }
            _state.update { it.copy(raw = it.raw.copy(supported = ok ?: it.raw.supported, note = note)) }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            _state.update { s -> s.fix?.let { s.copy(fixAgeMs = (System.currentTimeMillis() - it.time).coerceAtLeast(0)) } ?: s }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(hasPermission: Boolean) {
        _state.update {
            it.copy(
                hasPermission = hasPermission,
                providerEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER),
                hardware = if (Build.VERSION.SDK_INT >= 30) lm.gnssHardwareModelName else null,
                raw = it.raw.copy(supported = if (Build.VERSION.SDK_INT >= 31) lm.gnssCapabilities.hasMeasurements() else it.raw.supported),
            )
        }
        if (!hasPermission || started) return
        started = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        lm.registerGnssStatusCallback(statusCallback, handler)
        handler.post(ticker)
    }

    @SuppressLint("MissingPermission")
    fun setRawEnabled(on: Boolean) {
        if (!started) return
        if (on) lm.registerGnssMeasurementsCallback(measurementsCallback, handler)
        else lm.unregisterGnssMeasurementsCallback(measurementsCallback)
        _state.update { it.copy(raw = it.raw.copy(enabled = on, measurements = if (on) it.raw.measurements else 0)) }
    }

    fun stop() {
        if (!started) return
        started = false
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measurementsCallback)
        handler.removeCallbacks(ticker)
    }
}
