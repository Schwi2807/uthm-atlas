package io.github.schwi2807.atlas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.map.MapScreen
import io.github.schwi2807.atlas.track.TrackService
import io.github.schwi2807.atlas.ui.AtlasTheme
import io.github.schwi2807.atlas.ui.FeatureForm
import io.github.schwi2807.atlas.ui.HudScreen
import io.github.schwi2807.atlas.ui.Mono
import io.github.schwi2807.atlas.ui.P
import io.github.schwi2807.atlas.ui.SessionsScreen
import io.github.schwi2807.atlas.ui.SetupScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String) { HUD("HUD"), MAP("MAP"), LOG("WALKS"), SETUP("SETUP") }

class MainActivity : ComponentActivity() {
    private val survey get() = (application as AtlasApp).survey
    private var permissionGranted = mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permissionGranted.value = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocation()
        survey.gnss.start(permissionGranted.value)
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // field tool: the HUD stays on while walking
        permissionGranted.value = hasLocation()
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
        setContent { AtlasTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        survey.gnss.start(hasLocation())
        survey.sensors.start()
    }

    override fun onStop() {
        super.onStop()
        survey.sensors.stop()
        // keep GNSS alive only while the track service is recording
        if (!survey.recording.value) survey.gnss.stop()
    }

    @Composable
    private fun App() {
        var tab by remember { mutableStateOf(Tab.HUD) }
        var editing by remember { mutableStateOf<Pair<FeatureEntity, Boolean>?>(null) }
        val sessionId by survey.sessionId.collectAsState()
        val sessions by survey.db.sessions().all().collectAsState(initial = emptyList())
        val recording by survey.recording.collectAsState()
        val scope = rememberCoroutineScope()
        val title = sessions.firstOrNull { it.id == sessionId }?.title

        LaunchedEffect(sessionId, sessions) { if (sessionId != null && sessions.isNotEmpty() && sessions.none { it.id == sessionId }) survey.selectSession(null) }
        BackHandler(enabled = editing != null) { editing = null }

        Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                val e = editing
                if (e != null) {
                    FeatureForm(survey, e.first, e.second) { editing = null; tab = Tab.HUD }
                } else when (tab) {
                    Tab.HUD -> HudScreen(
                        survey, title,
                        onCaptured = {
                            val c = survey.capture.value as? CaptureUi.Done ?: return@HudScreen
                            val sid = sessionId ?: return@HudScreen
                            editing = survey.newFeature(sid, c.result, c.reason) to true
                        },
                        onNeedSession = { scope.launch { survey.newSession(); survey.startCapture() } },
                    )
                    Tab.MAP -> MapScreen(survey)
                    Tab.LOG -> SessionsScreen(survey) { editing = it to false }
                    Tab.SETUP -> SetupScreen(survey)
                }
            }
            if (editing == null) Row(Modifier.fillMaxWidth().height(56.dp).background(P.panel)) {
                for (t in Tab.entries) {
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickable { tab = t }.background(if (tab == t) P.panel2 else P.panel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.label, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (tab == t) P.amber else P.muted)
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable {
                        if (recording) TrackService.stop(this@MainActivity)
                        else scope.launch { if (survey.sessionId.value == null) survey.newSession(); TrackService.start(this@MainActivity) }
                    }.background(if (recording) P.bad.copy(alpha = 0.25f) else P.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (recording) "■ TRK" else "● TRK", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = if (recording) P.bad else P.muted, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
