package com.marcmayol.rodet.flow

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.marcmayol.rodet.R

@Composable
fun CaptureScreen(vm: FlowViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { Images.load(context.contentResolver, it) }?.let(vm::onPhoto)
    }
    val pickFromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                CameraPreview(onPhoto = vm::onPhoto)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.capture_permission), textAlign = TextAlign.Center)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.capture_allow)) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = pickFromGallery, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoLibrary, null)
                Text(" " + stringResource(R.string.capture_gallery))
            }
            OutlinedButton(onClick = vm::startManual, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null)
                Text(" " + stringResource(R.string.capture_manual))
            }
        }
    }
}

@Composable
private fun CameraPreview(onPhoto: (android.graphics.Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // TextureView: se compone bien con el marco dibujado encima.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        GuideOverlay()
        Text(
            stringResource(R.string.capture_hint),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(24.dp),
        )
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            TextButton(
                onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp),
            ) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, stringResource(R.string.capture_torch), tint = Color.White)
            }
        }
        FilledIconButton(
            onClick = {
                busy = true
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val full = Images.rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                            image.close()
                            val cropped = Images.cropToGuide(full, previewView.width.toFloat(), previewView.height.toFloat(), GuideRect.DEFAULT)
                            busy = false
                            onPhoto(Images.limit(cropped))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                        }
                    },
                )
            },
            enabled = !busy,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(32.dp)) else Text("")
        }
    }
}

/** Oscurece fuera del marco para que se vea donde tiene que ir la etiqueta. */
@Composable
private fun GuideOverlay() {
    val g = GuideRect.DEFAULT
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawRect(Color.Black.copy(alpha = 0.5f))
        val topLeft = Offset(g.left * size.width, g.top * size.height)
        val rect = Size((g.right - g.left) * size.width, (g.bottom - g.top) * size.height)
        val r = CornerRadius(16.dp.toPx())
        drawRoundRect(Color.Transparent, topLeft, rect, r, blendMode = BlendMode.Clear)
        drawRoundRect(Color.White, topLeft, rect, r, style = Stroke(2.dp.toPx()))
    }
}
