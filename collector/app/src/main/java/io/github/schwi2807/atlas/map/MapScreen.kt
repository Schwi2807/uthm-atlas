package io.github.schwi2807.atlas.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.ui.P

/** Offline MapLibre map with captured features, breadcrumb track and coverage heat (milestone 6). */
@Composable
fun MapScreen(survey: SurveyController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Map arrives in milestone 6", color = P.muted) }
}
