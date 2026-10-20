package io.github.schwi2807.atlas.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.schwi2807.atlas.BuildConfig
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.suggest.SuggestionPack
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(survey: SurveyController) {
    val scope = rememberCoroutineScope()
    val pack by survey.suggestions.status.collectAsState()
    val baro by survey.sensors.baro.collectAsState()
    val g by survey.gnss.state.collectAsState()
    var n by remember { mutableFloatStateOf(survey.settings.targetSamples.toFloat()) }
    var stable by remember { mutableFloatStateOf(survey.settings.stableAccM.toFloat()) }
    var warn by remember { mutableFloatStateOf(survey.settings.warnAccM.toFloat()) }
    var floorH by remember { mutableFloatStateOf(survey.sensors.floorHeightM) }
    var busy by remember { mutableStateOf(false) }
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { survey.suggestions.import(uri) }
    }
    fun save() = survey.updateSettings(n.toInt(), stable.toDouble(), warn.toDouble(), floorH)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = gap) {
        Panel("Averaged capture") {
            SliderRow("Samples", n, 10f..120f, "%.0f fixes".format(n)) { n = it; save() }
            SliderRow("Stop early when 95% radius ≤", stable, 1f..10f, "%.1f m".format(stable)) { stable = it; save() }
            SliderRow("Warn when accuracy worse than", warn, 5f..30f, "%.0f m".format(warn)) { warn = it; save() }
        }
        Panel("Sensors on this phone") {
            val a = survey.sensors.availability
            Text("Compass ${yes(a.compass)} · Barometer ${yes(a.barometer)} · Camera ${yes(a.camera)} · Raw GNSS ${yes(g.raw.supported == true)}",
                style = value.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            if (!a.barometer) Text("No barometer: floors are entered by hand in the form.", fontSize = 12.sp, color = P.muted)
            if (a.barometer) {
                SliderRow("Storey height", floorH, 2.5f..6f, "%.1f m".format(floorH)) { floorH = it; save() }
                Row(horizontalArrangement = gap) {
                    InstrButton("Calibrate ground floor") { survey.sensors.calibrateGround() }
                    Text(baro.hPa?.let { "%.1f hPa".format(it) } ?: "—", style = value, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        Panel("Name suggestions (offline pack)") {
            Text(
                if (pack.loaded) "${pack.items} names · built ${pack.createdAt?.take(10)}" else "No pack yet. Download it on Wi-Fi before walking.",
                style = value.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp),
            )
            pack.message?.let { Text(it, color = P.bad, fontSize = 12.sp) }
            Row(horizontalArrangement = gap, modifier = Modifier.padding(top = 6.dp)) {
                InstrButton(if (busy) "Downloading…" else "Download (Wi-Fi)", Modifier.weight(1f), primary = true, enabled = !busy) {
                    busy = true
                    scope.launch { survey.suggestions.download(SuggestionPack.DEFAULT_URL); busy = false }
                }
                InstrButton("Import file", Modifier.weight(1f)) { importPack.launch(arrayOf("application/json", "*/*")) }
            }
            Text("Built by `atlas suggest` from OpenStreetMap + Wikidata. Chips only fill the form; you always confirm the name.",
                fontSize = 11.sp, color = P.dim)
        }
        Panel("About") {
            Text("Atlas Surveyor ${BuildConfig.VERSION_NAME} · schema v${BuildConfig.SCHEMA_VERSION} · campus ${BuildConfig.CAMPUS_ID}",
                style = value.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            Text("Field tool for UTHM Atlas, an unofficial student-made map by Schwi2807. No account, no cloud, no analytics: " +
                "everything stays on this phone until you export it.", fontSize = 12.sp, color = P.muted)
            Text("Map data © OpenStreetMap contributors · basemap © Protomaps", fontSize = 11.sp, color = P.dim)
        }
    }
}

@Composable
private fun SliderRow(name: String, v: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row { Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(shown, style = value.copy(color = P.amber)) }
        Slider(v, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = P.amber, activeTrackColor = P.amber))
    }
}

private fun yes(b: Boolean) = if (b) "✓" else "✗"
