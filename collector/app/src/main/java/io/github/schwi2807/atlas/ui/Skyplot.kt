package io.github.schwi2807.atlas.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.schwi2807.atlas.gnss.Constellation
import io.github.schwi2807.atlas.gnss.Sat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polar skyplot: centre = zenith, rim = horizon, north up. Dot size follows C/N0; filled = used in the fix.
 * Positions glide between updates (each satellite keeps its own animators) so the plot feels alive.
 * An optional heading draws the phone's facing direction as a wedge.
 */
@Composable
fun Skyplot(sats: List<Sat>, headingDeg: Float?, modifier: Modifier = Modifier) {
    val anim = remember { HashMap<String, Pair<Animatable<Float, *>, Animatable<Float, *>>>() }
    val keys = sats.map { "${it.constellation}-${it.svid}" }
    for ((k, s) in keys.zip(sats)) {
        val pair = anim.getOrPut(k) { Animatable(s.azimuthDeg) to Animatable(s.elevationDeg) }
        LaunchedEffect(k, s.azimuthDeg, s.elevationDeg) {
            val target = pair.first.value + shortest(pair.first.value, s.azimuthDeg)
            pair.first.animateTo(target, tween(900))
            pair.second.animateTo(s.elevationDeg, tween(900))
        }
    }
    anim.keys.retainAll(keys.toSet())

    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.9f
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 10.dp.toPx(); typeface = android.graphics.Typeface.MONOSPACE
        }
        // rings at elevation 0, 30, 60 and the cross
        for (e in listOf(0f, 30f, 60f)) drawCircle(P.line, radius = r * (90f - e) / 90f, center = c, style = Stroke(1.dp.toPx()))
        drawLine(P.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(P.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        paint.color = P.muted.toArgb()
        drawContext.canvas.nativeCanvas.apply {
            drawText("N", c.x - 4.dp.toPx(), c.y - r - 4.dp.toPx(), paint)
            drawText("E", c.x + r + 3.dp.toPx(), c.y + 4.dp.toPx(), paint)
            drawText("S", c.x - 4.dp.toPx(), c.y + r + 12.dp.toPx(), paint)
            drawText("W", c.x - r - 12.dp.toPx(), c.y + 4.dp.toPx(), paint)
        }
        headingDeg?.let { h ->
            rotate(h, c) {
                drawArc(P.amberDim, startAngle = -90f - 20f, sweepAngle = 40f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
            }
        }
        for ((k, s) in keys.zip(sats)) {
            val (az, el) = anim[k]?.let { it.first.value to it.second.value } ?: (s.azimuthDeg to s.elevationDeg)
            val rr = r * (90f - el.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(az.toDouble())
            val p = Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            val dot = (2.5f + (s.cn0DbHz.coerceIn(10f, 50f) - 10f) / 40f * 7f).dp.toPx()
            val col = if (s.usedInFix) P.amber else P.muted
            if (s.usedInFix) drawCircle(col, dot, p) else drawCircle(col, dot, p, style = Stroke(1.5.dp.toPx()))
            if (s.usedInFix) { // label only the satellites in the fix: keeps a 40-satellite sky readable
                paint.color = P.muted.toArgb()
                paint.textSize = 9.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(short(s.constellation) + s.svid, p.x + dot + 1.dp.toPx(), p.y + 3.dp.toPx(), paint)
            }
        }
    }
}

private fun short(c: Constellation) = when (c) {
    Constellation.GPS -> "G"; Constellation.GLONASS -> "R"; Constellation.GALILEO -> "E"; Constellation.BEIDOU -> "C"
    Constellation.QZSS -> "J"; Constellation.SBAS -> "S"; Constellation.IRNSS -> "I"; Constellation.OTHER -> "?"
}

private fun shortest(from: Float, to: Float): Float { var d = (to - from) % 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return d }
