package io.github.schwi2807.atlas.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.schwi2807.atlas.CaptureUi
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.gnss.GnssState
import kotlin.math.max
import kotlin.math.min

@Composable
fun HudScreen(survey: SurveyController, sessionTitle: String?, onCaptured: () -> Unit, onNeedSession: () -> Unit) {
    val g by survey.gnss.state.collectAsState()
    val compass by survey.sensors.compass.collectAsState()
    val capture by survey.capture.collectAsState()
    val recording by survey.recording.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = gap,
    ) {
        StatusStrip(g, sessionTitle, recording)
        FixPanel(g)
        Row(horizontalArrangement = gap) {
            Panel("Skyplot", Modifier.weight(1.15f)) {
                Skyplot(g.sats, compass.headingDeg, Modifier.padding(top = 4.dp))
            }
            Panel("Satellites", Modifier.weight(1f)) {
                ConstellationTable(g)
            }
        }
        RawPanel(g, onToggle = { survey.gnss.setRawEnabled(it) })
        when (val c = capture) {
            is CaptureUi.Running -> CapturePanel(c, onStop = survey::stopCapture, onCancel = survey::cancelCapture)
            is CaptureUi.Done -> DonePanel(c, survey.settings.warnAccM,
                onRecapture = { survey.startCapture(c.featureId) },
                onUse = { if (c.featureId != null) survey.clearDone() else onCaptured() },
                onDiscard = survey::clearDone)
            CaptureUi.Idle -> InstrButton(
                if (g.fix == null) "Waiting for fix…" else "Capture", Modifier.fillMaxWidth().height(64.dp),
                primary = true, enabled = g.fix != null,
            ) { if (sessionTitle == null) onNeedSession() else survey.startCapture() }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusStrip(g: GnssState, sessionTitle: String?, recording: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val fixOk = g.fix != null && g.fixAgeMs < 5000
        Text(
            when {
                !g.hasPermission -> "NO LOCATION PERMISSION"
                !g.providerEnabled -> "GPS OFF"
                fixOk -> "3D FIX"
                g.visible > 0 -> "SEARCHING"
                else -> "NO SIGNAL"
            },
            fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (fixOk) P.amber else P.bad,
        )
        Spacer(Modifier.width(10.dp))
        Text(sessionTitle?.let { "▸ $it" } ?: "no session", fontFamily = Mono, fontSize = 12.sp, color = P.muted,
            modifier = Modifier.weight(1f), maxLines = 1)
        if (recording) {
            val t = rememberInfiniteTransition(label = "rec")
            val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "rec")
            Box(Modifier.width(8.dp).height(8.dp).background(P.bad.copy(alpha = a)))
            Text(" TRK", fontFamily = Mono, fontSize = 12.sp, color = P.bad)
        }
    }
}

@Composable
private fun FixPanel(g: GnssState) {
    val f = g.fix
    Panel("Position", trailing = {
        Text(f?.let { "age ${g.fixAgeMs / 1000}s" } ?: "", style = label)
    }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f?.let { "±%.1f".format(it.accuracy) } ?: "±—", style = big)
            Text(" m", style = value.copy(color = P.muted), modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(f?.let { "%.7f".format(it.latitude) } ?: "—", style = value)
                Text(f?.let { "%.7f".format(it.longitude) } ?: "—", style = value)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Readout("alt", f?.takeIf { it.hasAltitude() }?.let { "%.0f m".format(it.altitude) } ?: "—")
            Readout("speed", f?.takeIf { it.hasSpeed() }?.let { "%.1f km/h".format(it.speed * 3.6) } ?: "—")
            Readout("bearing", f?.takeIf { it.hasBearing() }?.let { "%03.0f°".format(it.bearing) } ?: "—")
            Readout("prov", f?.provider ?: "—")
            Readout("ttff", g.ttffMs?.let { "%.1fs".format(it / 1000.0) } ?: "—")
        }
    }
}

@Composable
private fun ConstellationTable(g: GnssState) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row { Text("SYS", style = label, modifier = Modifier.weight(1f)); Text("USED/VIS", style = label) }
        for (c in g.perConstellation) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.c.short, style = value.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
                Text("%2d/%-2d".format(c.used, c.visible), style = value.copy(fontSize = 13.sp, color = if (c.used > 0) P.amber else P.muted))
            }
            Bar(c.used.toFloat() / max(1, c.visible))
        }
        Spacer(Modifier.height(4.dp))
        Row { Text("TOTAL", style = label, modifier = Modifier.weight(1f)); Text("${g.used}/${g.visible}", style = value.copy(color = P.amber)) }
        Row { Text("C/N0 μ", style = label, modifier = Modifier.weight(1f)); Text(fmt(g.meanCn0Used, 1, " dB"), style = value.copy(fontSize = 13.sp)) }
        g.hardware?.let { Text(it, style = label.copy(fontSize = 9.sp), maxLines = 2) }
    }
}

@Composable
private fun Bar(frac: Float) {
    val w by animateFloatAsState(frac.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(Modifier.fillMaxWidth().height(3.dp).background(P.line)) {
        Box(Modifier.fillMaxWidth(w).height(3.dp).background(P.amber))
    }
}

@Composable
private fun RawPanel(g: GnssState, onToggle: (Boolean) -> Unit) {
    val supported = g.raw.supported
    Panel("Raw GNSS measurements", trailing = {
        Switch(
            checked = g.raw.enabled, onCheckedChange = onToggle, enabled = supported != false && g.hasPermission,
            colors = SwitchDefaults.colors(checkedThumbColor = P.bg, checkedTrackColor = P.amber),
        )
    }) {
        Text(
            when {
                supported == false -> g.raw.note ?: "Not supported on this phone"
                g.raw.enabled -> "${g.raw.measurements} measurements/epoch · " + if (g.raw.dualFrequency) "dual-frequency (L5/E5a)" else "single-frequency"
                else -> g.raw.note ?: "Off. Turn on to check what the chip reports (not used for positions)."
            },
            fontSize = 12.sp, color = P.muted,
        )
    }
}

/** Live capture: rings shrink as the 95% radius of the averaged position tightens; dots are the raw fixes. */
@Composable
private fun CapturePanel(c: CaptureUi.Running, onStop: () -> Unit, onCancel: () -> Unit) {
    val r = c.result
    val ci = r?.ci95M ?: 25.0
    val ring by animateFloatAsState(min(1f, (ci / 25.0).toFloat()).coerceAtLeast(0.04f), tween(700), label = "ring")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(0.85f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Panel("Averaging", trailing = { Text("${r?.n ?: 0}/${c.target}", style = value.copy(color = P.amber)) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxR = size.minDimension / 2 * 0.92f
                val scale = maxR / 25f // pixels per metre at the outer ring
                for (m in listOf(5f, 10f, 25f)) drawCircle(P.line, m * scale, center, style = Stroke(1.dp.toPx()))
                for ((dx, dy) in c.scatter) {
                    val px = Offset(center.x + (dx * scale).toFloat().coerceIn(-maxR, maxR), center.y - (dy * scale).toFloat().coerceIn(-maxR, maxR))
                    drawCircle(P.muted, 2.dp.toPx(), px)
                }
                drawCircle(P.amber, ring * maxR * p, center, style = Stroke(3.dp.toPx()))
                drawCircle(P.amberDim, ring * maxR, center)
                drawCircle(P.amber, 3.dp.toPx(), center)
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text("95% ±${fmt(r?.ci95M, 1)} m", style = value.copy(color = P.amber))
                Text("σ ${fmt(r?.stdM, 1)} m · best ${fmt(r?.bestAccM, 1)} m", style = label)
            }
        }
        Row(horizontalArrangement = gap, modifier = Modifier.padding(top = 6.dp)) {
            InstrButton("Cancel", Modifier.weight(1f), onClick = onCancel)
            InstrButton("Stop now", Modifier.weight(1f), primary = true, onClick = onStop)
        }
    }
}

@Composable
private fun DonePanel(c: CaptureUi.Done, warnM: Double, onRecapture: () -> Unit, onUse: () -> Unit, onDiscard: () -> Unit) {
    val r = c.result
    Panel("Capture ${c.reason.name.lowercase()}") {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Readout("samples", "${r.n}")
            Readout("σ", fmt(r.stdM, 1, " m"), accent = true)
            Readout("95%", fmt(r.ci95M, 1, " m"))
            Readout("best", fmt(r.bestAccM, 1, " m"))
            Readout("sats", "${r.satsUsed}/${r.satsVisible}")
        }
        if (c.weak) Banner("Accuracy is worse than ${warnM.toInt()} m here. Move away from walls and trees and re-capture, or keep it and the build report will flag it.",
            Modifier.padding(top = 8.dp), warn = true)
        Row(horizontalArrangement = gap, modifier = Modifier.padding(top = 8.dp)) {
            if (c.featureId == null) InstrButton("Discard", Modifier.weight(1f), onClick = onDiscard)
            InstrButton("Re-capture", Modifier.weight(1f), onClick = onRecapture)
            InstrButton(if (c.featureId == null) "Describe ›" else "Done", Modifier.weight(1.2f), primary = true, onClick = onUse)
        }
    }
}
