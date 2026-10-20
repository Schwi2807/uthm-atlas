package io.github.schwi2807.atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Instrument panel: near-black, hairlines, monospace numerics and ONE accent (amber). */
object P {
    val bg = Color(0xFF0B0C0E)
    val panel = Color(0xFF131519)
    val panel2 = Color(0xFF1A1D22)
    val line = Color(0xFF2A2E35)
    val text = Color(0xFFE8E6E1)
    val muted = Color(0xFF8B9098)
    val dim = Color(0xFF555A62)
    val amber = Color(0xFFFFB000)
    val amberDim = Color(0x55FFB000)
    val bad = Color(0xFFFF6A3D)   // only for "warning" states
}

val Mono = FontFamily.Monospace

@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = P.amber, onPrimary = Color.Black, background = P.bg, surface = P.panel, onSurface = P.text,
            onBackground = P.text, surfaceVariant = P.panel2, outline = P.line, secondary = P.amber, error = P.bad,
        ),
        content = content,
    )
}

val label = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, color = P.muted, fontWeight = FontWeight.Medium)
val value = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = P.text)
val big = TextStyle(fontFamily = Mono, fontSize = 40.sp, color = P.amber, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)

/** A labelled instrument panel with a hairline border. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(P.panel)
            .border(1.dp, P.line, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = label, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
fun Readout(name: String, v: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier) {
        Text(name.uppercase(), style = label)
        Text(v, style = value.copy(color = if (accent) P.amber else P.text))
    }
}

/** Square-cornered instrument button. Primary = filled amber. */
@Composable
fun InstrButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (primary) P.amber else P.line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (primary) P.amber else P.panel2,
            contentColor = if (primary) Color.Black else P.text,
            disabledContainerColor = P.panel, disabledContentColor = P.dim,
        ),
    ) { Text(text.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) P.amber else P.panel2)
            .border(1.dp, if (selected) P.amber else P.line, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.Black else P.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, color = if (selected) Color.Black else P.muted, fontSize = 10.sp, fontFamily = Mono)
    }
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(if (warn) P.bad.copy(alpha = 0.15f) else P.amberDim.copy(alpha = 0.18f))
            .border(1.dp, if (warn) P.bad else P.amber, RoundedCornerShape(4.dp)).padding(10.dp),
    ) { Text(text, color = if (warn) P.bad else P.amber, fontSize = 13.sp) }
}

val gap = Arrangement.spacedBy(8.dp)

fun fmt(v: Double?, digits: Int = 1, unit: String = ""): String = v?.let { "%.${digits}f".format(it) + unit } ?: "—"
