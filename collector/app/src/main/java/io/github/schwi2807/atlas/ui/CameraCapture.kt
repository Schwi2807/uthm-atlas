package io.github.schwi2807.atlas.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

/**
 * In-app CameraX capture (back camera, full-resolution JPEG). The engine re-encodes every photo and strips all
 * metadata before anything is published. Calls onFailed() so the form can fall back to the system camera.
 */
@Composable
fun CameraCapture(target: File, onDone: (Boolean) -> Unit, onFailed: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onFailure { onFailed() }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            Banner("Frame the entrance or sign. Avoid faces and number plates.")
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CANCEL", color = P.text, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.clickable { onDone(false) }.padding(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(78.dp).clip(CircleShape).border(4.dp, P.amber, CircleShape)
                        .background(if (busy) P.amberDim else P.amber.copy(alpha = 0.85f))
                        .clickable(enabled = !busy) {
                            busy = true
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(target).build(),
                                ContextCompat.getMainExecutor(ctx),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { busy = false; onDone(true) }
                                    override fun onError(exception: ImageCaptureException) { busy = false; onDone(false) }
                                },
                            )
                        },
                )
            }
            Text("      ", modifier = Modifier.padding(12.dp))
        }
    }
}
