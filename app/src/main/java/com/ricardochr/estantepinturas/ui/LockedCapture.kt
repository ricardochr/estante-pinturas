package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors

/** Porcentaje de píxeles quemados (algún canal ≥ 250) y muy oscuros en un cuadro RGBA. */
private fun clipStats(image: ImageProxy): Pair<Float, Float> {
    val plane = image.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val w = image.width
    val h = image.height
    var total = 0
    var clipped = 0
    var dark = 0
    val step = 4
    var y = 0
    while (y < h) {
        var x = 0
        val row = y * rowStride
        while (x < w) {
            val o = row + x * 4
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            val mx = maxOf(r, g, b)
            if (mx >= 250) clipped++
            if (mx <= 8) dark++
            total++
            x += step
        }
        y += step
    }
    if (total == 0) return 0f to 0f
    return clipped * 100f / total to dark * 100f / total
}

/**
 * Cámara propia para medir color: deja ajustar la exposición, la bloquea junto con el balance de
 * blancos y solo entonces deja tomar la foto, sin HDR, modo noche ni escenas automáticas. Muestra en
 * vivo cuánto de la imagen está quemado. [onCaptured] recibe el archivo de la foto.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun LockedCaptureScreen(onCaptured: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var locked by remember { mutableStateOf(false) }
    var clipped by remember { mutableFloatStateOf(0f) }
    var dark by remember { mutableFloatStateOf(0f) }
    var evIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    BackHandler(onBack = onCancel)

    DisposableEffect(lifecycleOwner, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var last = 0L
        future.addListener({
            val p = future.get()
            provider = p
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { image ->
                        val now = System.currentTimeMillis()
                        if (now - last > 250) {
                            last = now
                            try {
                                val (c, d) = clipStats(image)
                                main.execute { clipped = c; dark = d }
                            } catch (_: Exception) {
                            }
                        }
                        image.close()
                    }
                }
            // Latencia mínima: sin fusión de varias tomas (HDR, noche) que cambie los colores
            val cap = ImageCapture.Builder()
                .setResolutionSelector(selector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, cap)
                camera = cam
                capture = cap
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                        .build(),
                )
            } catch (e: Exception) {
                error = tr("No pude abrir la cámara: {}", e.message ?: e.javaClass.simpleName)
            }
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    fun setLock(on: Boolean) {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, on)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, on)
                .build(),
        )
        locked = on
    }

    fun shoot() {
        val cap = capture ?: return
        busy = true
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "medicion-${System.currentTimeMillis()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onCaptured(Uri.fromFile(f))
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    error = tr("No pude tomar la foto: {}", exception.message ?: "")
                }
            },
        )
    }

    val evRange = camera?.cameraInfo?.exposureState?.exposureCompensationRange
    val evSupported = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true && evRange != null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar"), tint = Color.White) }
                Text(tr("Foto para medir color"), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!hasPermission) {
                    Text(tr("La app necesita permiso de cámara para esta foto. También puedes usar una foto de la galería."), color = Color.White)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(tr("Dar permiso")) }
                } else {
                    val clipBad = clipped > 1f
                    Text(
                        when {
                            clipBad -> tr("{}% de la imagen está quemada: baja la exposición o la luz.", "%.1f".format(clipped))
                            dark > 25f -> tr("Mucha zona muy oscura ({}%): sube la exposición o agrega luz.", dark.toInt())
                            else -> tr("Exposición bien: {}% quemado.", "%.1f".format(clipped))
                        },
                        color = if (clipBad) Color(0xFFFF6B6B) else Color(0xFF8CE99A),
                        fontWeight = FontWeight.Bold,
                    )
                    if (evSupported && !locked) {
                        val r = evRange!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Exposición"), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
                            Slider(
                                value = evIndex.toFloat(),
                                onValueChange = { v ->
                                    val i = v.toInt().coerceIn(r.lower, r.upper)
                                    if (i != evIndex) { evIndex = i; camera?.cameraControl?.setExposureCompensationIndex(i) }
                                },
                                valueRange = r.lower.toFloat()..r.upper.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        if (locked) tr("Luz y color bloqueados: no muevas la lámpara ni cambies el encuadre entre fotos.")
                        else tr("Encuadra todas las muestras y el papel, ajusta la exposición hasta que no haya zonas quemadas y bloquea."),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    error?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(onClick = { setLock(!locked) }, enabled = camera != null, modifier = Modifier.weight(1f)) {
                            Icon(if (locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null)
                            Text(if (locked) tr(" Desbloquear") else tr(" Bloquear luz"))
                        }
                        Button(onClick = ::shoot, enabled = locked && !busy && !clipBad, modifier = Modifier.weight(1f)) {
                            Text(if (busy) tr("Guardando…") else tr("Tomar foto"))
                        }
                    }
                }
            }
        }
    }
}
