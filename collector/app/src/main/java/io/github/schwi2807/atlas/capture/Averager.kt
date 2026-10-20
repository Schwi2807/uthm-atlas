package io.github.schwi2807.atlas.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** One GNSS fix as the averager sees it. */
data class Sample(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accM: Double,
    val satsUsed: Int,
    val satsVisible: Int,
)

data class CaptureSettings(
    val targetSamples: Int = 30,      // stop after this many fixes
    val minSamples: Int = 10,         // never stop "stable" before this
    val stableAccM: Double = 4.0,     // stop early when the 95% radius of the mean is below this...
    val stableWindow: Int = 5,        // ...and the mean moved less than stableDriftM over this many samples
    val stableDriftM: Double = 0.35,
    val goodFixM: Double = 5.0,       // haptic tick for fixes at least this good
    val warnAccM: Double = 10.0,      // warn + offer re-capture when the result is worse than this
)

enum class StopReason { COUNT, STABLE, MANUAL }

data class CaptureResult(
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val n: Int,
    /** Horizontal standard deviation of the samples around the mean (metres). */
    val stdM: Double,
    /** Radius that contains the true mean with ~95% probability (2D, metres). Drives the convergence ring. */
    val ci95M: Double,
    val meanAccM: Double,
    val bestAccM: Double,
    val satsUsed: Int,
    val satsVisible: Int,
    val startMs: Long,
    val endMs: Long,
) {
    fun isWeak(s: CaptureSettings) = bestAccM > s.warnAccM || stdM > s.warnAccM
}

/**
 * Inverse-variance weighted average of fixes in a local tangent plane (metres east/north of the first fix).
 * Weighting by 1/acc^2 lets a few sharp fixes dominate a noisy start, which is what a person standing still under
 * trees sees. Pure Kotlin so the maths is unit-tested on the JVM.
 */
class Averager(val settings: CaptureSettings = CaptureSettings()) {
    private val samples = ArrayList<Sample>()
    private val means = ArrayList<Pair<Double, Double>>() // running mean after each sample (x, y)
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerDegLon = 0.0

    val count get() = samples.size

    fun reset() {
        samples.clear()
        means.clear()
    }

    /** Add a fix. Returns the stop reason if the capture is complete, else null. */
    fun add(s: Sample): StopReason? {
        if (samples.isEmpty()) {
            lat0 = s.lat
            lon0 = s.lon
            mPerDegLon = M_PER_DEG * cos(s.lat * PI / 180)
        }
        samples += s
        val r = result()!!
        means += toXY(r.lat, r.lon)
        if (samples.size >= settings.targetSamples) return StopReason.COUNT
        if (samples.size >= max(settings.minSamples, settings.stableWindow + 1) && r.ci95M <= settings.stableAccM) {
            val (x1, y1) = means[means.size - 1]
            val (x0, y0) = means[means.size - 1 - settings.stableWindow]
            if (hypot(x1 - x0, y1 - y0) <= settings.stableDriftM) return StopReason.STABLE
        }
        return null
    }

    fun result(): CaptureResult? {
        if (samples.isEmpty()) return null
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sAlt = 0.0
        var nAlt = 0
        for (s in samples) {
            val w = 1.0 / max(s.accM, 0.5).let { it * it }
            val (x, y) = toXY(s.lat, s.lon)
            sw += w; sx += w * x; sy += w * y
            s.alt?.let { sAlt += it; nAlt++ }
        }
        val mx = sx / sw
        val my = sy / sw
        // weighted variance around the mean, and Kish's effective sample size for the confidence radius
        var sw2 = 0.0
        var v = 0.0
        for (s in samples) {
            val w = 1.0 / max(s.accM, 0.5).let { it * it }
            val (x, y) = toXY(s.lat, s.lon)
            v += w * ((x - mx) * (x - mx) + (y - my) * (y - my))
            sw2 += w * w
        }
        val variance = v / sw
        val nEff = (sw * sw) / sw2
        val std = sqrt(variance)
        // with one sample the spread is unknown: fall back to the receiver's own accuracy estimate
        val meanAcc = samples.sumOf { it.accM } / samples.size
        val spread = if (samples.size < 3) meanAcc else max(std, 0.1)
        val ci95 = 2.45 * spread / sqrt(max(nEff, 1.0))
        val (lat, lon) = toLatLon(mx, my)
        return CaptureResult(
            lat = lat, lon = lon, alt = if (nAlt > 0) sAlt / nAlt else null,
            n = samples.size, stdM = if (samples.size < 2) samples[0].accM else std, ci95M = ci95,
            meanAccM = meanAcc, bestAccM = samples.minOf { it.accM },
            satsUsed = samples.last().satsUsed, satsVisible = samples.maxOf { it.satsVisible },
            startMs = samples.first().tMs, endMs = samples.last().tMs,
        )
    }

    /** Offsets of the individual fixes from the current mean (metres), for the scatter inside the ring. */
    fun scatter(): List<Pair<Double, Double>> {
        val r = result() ?: return emptyList()
        val (mx, my) = toXY(r.lat, r.lon)
        return samples.map { val (x, y) = toXY(it.lat, it.lon); (x - mx) to (y - my) }
    }

    private fun toXY(lat: Double, lon: Double) = ((lon - lon0) * mPerDegLon) to ((lat - lat0) * M_PER_DEG)
    private fun toLatLon(x: Double, y: Double) = (lat0 + y / M_PER_DEG) to (lon0 + if (mPerDegLon == 0.0) 0.0 else x / mPerDegLon)

    companion object {
        const val M_PER_DEG = 111_320.0

        fun isGoodFix(accM: Double, s: CaptureSettings) = abs(accM) <= s.goodFixM
    }
}
