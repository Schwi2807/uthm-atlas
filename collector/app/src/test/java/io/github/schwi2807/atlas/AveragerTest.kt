package io.github.schwi2807.atlas

import io.github.schwi2807.atlas.capture.Averager
import io.github.schwi2807.atlas.capture.CaptureSettings
import io.github.schwi2807.atlas.capture.Sample
import io.github.schwi2807.atlas.capture.StopReason
import io.github.schwi2807.atlas.sensors.Sensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AveragerTest {
    private val lat0 = 1.8585
    private val lon0 = 103.0855
    private fun sample(i: Int, dxM: Double, dyM: Double, acc: Double) = Sample(
        tMs = 1_000L * i, lat = lat0 + dyM / Averager.M_PER_DEG,
        lon = lon0 + dxM / (Averager.M_PER_DEG * Math.cos(Math.toRadians(lat0))), alt = 10.0, accM = acc, satsUsed = 20, satsVisible = 30,
    )

    @Test
    fun meanOfSymmetricCloudIsTheCentre() {
        val av = Averager(CaptureSettings(targetSamples = 100, stableAccM = 0.0))
        val offsets = listOf(3.0 to 0.0, -3.0 to 0.0, 0.0 to 3.0, 0.0 to -3.0)
        offsets.forEachIndexed { i, (x, y) -> av.add(sample(i, x, y, 4.0)) }
        val r = av.result()!!
        assertEquals(lat0, r.lat, 1e-7)
        assertEquals(lon0, r.lon, 1e-7)
        assertEquals(3.0, r.stdM, 0.01)
        assertEquals(4, r.n)
    }

    @Test
    fun sharpFixesOutweighNoisyOnes() {
        val av = Averager(CaptureSettings(targetSamples = 100, stableAccM = 0.0))
        av.add(sample(0, 20.0, 0.0, 20.0)) // bad fix 20 m east
        repeat(5) { av.add(sample(it + 1, 0.0, 0.0, 2.0)) }
        val offsetM = (av.result()!!.lon - lon0) * Averager.M_PER_DEG * Math.cos(Math.toRadians(lat0))
        assertTrue("mean pulled $offsetM m by one bad fix", offsetM < 0.2)
    }

    @Test
    fun stopsAtTargetCount() {
        val av = Averager(CaptureSettings(targetSamples = 30, stableAccM = 0.0))
        val rnd = Random(1)
        var reason: StopReason? = null
        var i = 0
        while (reason == null) { reason = av.add(sample(i, rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0), 5.0)); i++ }
        assertEquals(StopReason.COUNT, reason)
        assertEquals(30, i)
    }

    @Test
    fun stopsEarlyWhenStable() {
        val av = Averager(CaptureSettings(targetSamples = 60, minSamples = 10, stableAccM = 2.0, stableDriftM = 0.35))
        val rnd = Random(7)
        var reason: StopReason? = null
        var i = 0
        while (reason == null && i < 60) { reason = av.add(sample(i, rnd.nextDouble(-0.6, 0.6), rnd.nextDouble(-0.6, 0.6), 3.0)); i++ }
        assertEquals(StopReason.STABLE, reason)
        assertTrue("stopped after $i", i in 10..40)
    }

    @Test
    fun convergenceRadiusShrinks() {
        val av = Averager(CaptureSettings(targetSamples = 100, stableAccM = 0.0))
        val rnd = Random(3)
        val radii = (0 until 40).map { av.add(sample(it, rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0), 1.0)); av.result()!!.ci95M }
        assertTrue("ring should shrink: ${radii[5]} -> ${radii.last()}", radii.last() < radii[5])
    }

    @Test
    fun repeatedIdenticalFixesDoNotFakePrecision() {
        // indoors the GPS provider often repeats one position: zero scatter must not look like a 0.1 m fix
        val av = Averager(CaptureSettings(targetSamples = 30, stableAccM = 4.0))
        var reason: StopReason? = null
        var i = 0
        while (reason == null) { reason = av.add(sample(i, 0.0, 0.0, 12.0)); i++ }
        assertEquals(StopReason.COUNT, reason)
        assertTrue("ci95 ${av.result()!!.ci95M}", av.result()!!.ci95M >= 5.9)
    }

    @Test
    fun weakCaptureIsFlagged() {
        val s = CaptureSettings(targetSamples = 5, warnAccM = 10.0)
        val av = Averager(s)
        repeat(5) { av.add(sample(it, 0.0, 0.0, 14.0)) }
        assertTrue(av.result()!!.isWeak(s))
    }

    @Test
    fun emptyAveragerHasNoResult() = assertNull(Averager().result())

    @Test
    fun floorFromBarometricHeight() {
        assertEquals(0, Sensors.floorFromHeight(0.9f, 3.6f))
        assertEquals(1, Sensors.floorFromHeight(3.4f, 3.6f))
        assertEquals(3, Sensors.floorFromHeight(11.0f, 3.6f))
        assertEquals(-1, Sensors.floorFromHeight(-3.8f, 3.6f))
    }
}
