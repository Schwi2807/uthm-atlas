package io.github.schwi2807.atlas.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class SensorAvailability(val compass: Boolean, val barometer: Boolean, val camera: Boolean)

data class CompassState(
    val available: Boolean,
    /** Degrees from TRUE north (magnetic declination applied once a fix is known), 0..360. */
    val headingDeg: Float? = null,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val declinationDeg: Float = 0f,
)

data class BaroState(
    val available: Boolean,
    val hPa: Float? = null,
    val groundHPa: Float? = null,
    /** Height above the calibration point, metres (only once calibrated). */
    val relHeightM: Float? = null,
    val floorEstimate: Int? = null,
)

/**
 * Compass (rotation vector, falling back to accelerometer + magnetometer) and barometer, each optional.
 * A phone without a barometer (e.g. many budget models) simply reports available=false and the form asks for the
 * floor by hand.
 */
class Sensors(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val availability = SensorAvailability(
        compass = rotation != null || (accel != null && magnet != null),
        barometer = pressure != null,
        camera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
    )

    private val _compass = MutableStateFlow(CompassState(availability.compass))
    val compass: StateFlow<CompassState> = _compass.asStateFlow()
    private val _baro = MutableStateFlow(BaroState(availability.barometer))
    val baro: StateFlow<BaroState> = _baro.asStateFlow()

    var floorHeightM = 3.6f
    private val g = FloatArray(3)
    private val m = FloatArray(3)
    private var haveG = false
    private var haveM = false
    private var smoothed: Float? = null

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_UI
        if (rotation != null) sm.registerListener(this, rotation, rate)
        else if (availability.compass) { sm.registerListener(this, accel, rate); sm.registerListener(this, magnet, rate) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    /** Magnetic declination for the current position, so headings are true-north like the map. */
    fun setLocation(lat: Double, lon: Double, alt: Double, timeMs: Long) {
        val d = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), timeMs).declination
        _compass.update { it.copy(declinationDeg = d) }
    }

    /** Remember the current pressure as "ground floor". */
    fun calibrateGround() = _baro.update { it.copy(groundHPa = it.hPa, relHeightM = 0f, floorEstimate = 0) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                publishHeading(r)
            }
            Sensor.TYPE_ACCELEROMETER -> { System.arraycopy(e.values, 0, g, 0, 3); haveG = true; fuse() }
            Sensor.TYPE_MAGNETIC_FIELD -> { System.arraycopy(e.values, 0, m, 0, 3); haveM = true; fuse() }
            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                _baro.update { b ->
                    val ground = b.groundHPa
                    if (ground == null) b.copy(hPa = p)
                    else {
                        val h = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p) -
                            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ground)
                        b.copy(hPa = p, relHeightM = h, floorEstimate = floorFromHeight(h, floorHeightM))
                    }
                }
            }
        }
    }

    private fun fuse() {
        if (!haveG || !haveM) return
        val r = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, null, g, m)) publishHeading(r)
    }

    private fun publishHeading(r: FloatArray) {
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)
        val magnetic = Math.toDegrees(o[0].toDouble()).toFloat()
        val trueHeading = norm(magnetic + _compass.value.declinationDeg)
        // light low-pass on the circle so the arrow doesn't jitter
        val prev = smoothed
        smoothed = if (prev == null) trueHeading else norm(prev + 0.2f * shortest(prev, trueHeading))
        _compass.update { it.copy(headingDeg = smoothed) }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR || sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
            _compass.update { it.copy(accuracy = accuracy) }
    }

    companion object {
        fun norm(d: Float) = ((d % 360f) + 360f) % 360f
        fun shortest(from: Float, to: Float): Float { var d = (to - from) % 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

        /** Height above ground floor -> floor number; rounds to the nearest storey, with a dead band near 0. */
        fun floorFromHeight(h: Float, storeyM: Float): Int =
            if (abs(h) < storeyM * 0.4f) 0 else (h / storeyM).roundToInt()

        /** hPa -> metres via the standard atmosphere (same formula SensorManager.getAltitude uses). */
        fun altitude(p0: Float, p: Float): Float = 44330f * (1f - (p / p0).toDouble().pow(1 / 5.255).toFloat())
    }
}
