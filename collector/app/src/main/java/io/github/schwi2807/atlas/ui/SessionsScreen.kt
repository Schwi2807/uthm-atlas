package io.github.schwi2807.atlas.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.data.SessionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(survey: SurveyController, onReverify: () -> Unit = {}, onEdit: (FeatureEntity) -> Unit) {
    val sessions by survey.db.sessions().all().collectAsState(initial = emptyList())
    val active by survey.sessionId.collectAsState()
    var open by remember { mutableStateOf(active) }
    var confirmDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                Text("SESSIONS", style = label.copy(fontSize = 12.sp, color = P.amber), modifier = Modifier.weight(1f))
                InstrButton("Re-verify ›") { onReverify() }
                InstrButton("+ New walk", primary = true) { scope.launch { open = survey.newSession() } }
            }
            status?.let { Banner(it, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        if (sessions.isEmpty()) item { Text("No walks yet. Start one, then capture places from the HUD.", color = P.muted) }
        items(sessions, key = { it.id }) { s ->
            val features by remember(s.id) { if (open == s.id) survey.db.features().forSession(s.id) else flowOf(emptyList()) }
                .collectAsState(initial = emptyList())
            val count by survey.db.features().countForSession(s.id).collectAsState(initial = 0)
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(P.panel)
                    .border(1.dp, if (s.id == active) P.amber else P.line, RoundedCornerShape(6.dp)),
            ) {
                Row(Modifier.fillMaxWidth().clickable { open = if (open == s.id) null else s.id }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 16.sp)
                        Text("${date(s.startedAt)} · $count features" + (s.exportedAt?.let { " · exported ${date(it)}" } ?: ""), style = label)
                    }
                    if (s.id == active) Text("ACTIVE", style = label.copy(color = P.amber))
                }
                if (open == s.id) {
                    Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = gap) {
                        if (s.id != active) InstrButton("Make active", Modifier.weight(1f)) { survey.selectSession(s.id) }
                        InstrButton("Export", Modifier.weight(1f), primary = true, enabled = count > 0) {
                            scope.launch {
                                val f = survey.export(s.id)
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Send ${f.name}"))
                            }
                        }
                        InstrButton("To Downloads", Modifier.weight(1f), enabled = count > 0) {
                            scope.launch {
                                val f = survey.export(s.id)
                                status = if (survey.saveToDownloads(f) != null) "Saved to Downloads/UTHM Atlas/${f.name}" else "Could not save to Downloads"
                            }
                        }
                    }
                    for (f in features) FeatureRow(f) { onEdit(f) }
                    Row(Modifier.padding(10.dp), horizontalArrangement = gap) {
                        InstrButton("End walk", Modifier.weight(1f)) { scope.launch { survey.endSession(s.id) } }
                        InstrButton("Delete walk", Modifier.weight(1f)) { confirmDelete = s }
                    }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton({ scope.launch { survey.deleteSession(s.id) }; confirmDelete = null }) { Text("Delete", color = P.bad) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep") } },
            title = { Text("Delete \"${s.title}\"?") },
            text = { Text("All its features, photos, track and fixes are removed from this phone. Exported files are not affected.") },
            containerColor = P.panel2,
        )
    }
}

@Composable
private fun FeatureRow(f: FeatureEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.kind.take(4).uppercase(), style = label.copy(color = P.amber), modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text((f.code?.let { "$it  " } ?: "") + f.nameEn, fontSize = 14.sp, maxLines = 1)
            Text(catLabel(f.category) + (f.floor?.let { " · floor $it" } ?: "") + (f.bearingDeg?.let { " · %03.0f°".format(it) } ?: ""), style = label)
        }
        Text("±${fmt(f.stdM, 1)}", style = value.copy(fontSize = 13.sp, color = if (f.bestAccM > 10) P.bad else P.text))
    }
}

private fun date(ms: Long) = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(ms))
