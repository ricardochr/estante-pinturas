package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.findColorCandidates
import java.util.concurrent.Executors

/** Lo que se ve en el cuadro actual: etiquetas de la pintura y zonas de su color (0–1 del cuadro). */
private data class LiveMarks(
    val frameW: Int,
    val frameH: Int,
    val labels: List<android.graphics.RectF>,
    val colors: List<Triple<Float, Float, Float>>, // x, y, score
)

/**
 * Buscar una pintura apuntando la cámara a la gaveta: marca en verde dónde lee su etiqueta y
 * en amarillo las zonas de su color. Todo se procesa en el teléfono.
 */
@Composable
fun LiveFindScreen(vm: MainViewModel, paint: Paint, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    val haptics = LocalHapticFeedback.current
    var marks by remember { mutableStateOf<LiveMarks?>(null) }
    var torch by remember { mutableStateOf(false) }
    var lastBuzz by remember { mutableStateOf(0L) }
    val vmState by rememberUpdatedState(vm)
    val neutral = !paint.hasColor || kotlin.math.sqrt(paint.lab.a * paint.lab.a + paint.lab.b * paint.lab.b) < 12

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Encabezado
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Volver")) }
                        Swatch(paint.hex, size = 34.dp, metallic = paint.type == "Metálica")
                        Column(Modifier.weight(1f)) {
                            Text(paint.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (neutral) tr("Color neutro: solo puedo reconocer la etiqueta") else tr("Verde = etiqueta · amarillo = color parecido"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { torch = !torch }) { Text(if (torch) tr("Luz off") else "Luz") }
                    }
                }
                if (!hasPermission) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(tr("Falta permiso de cámara"), color = Color.White)
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(tr("Dar permiso")) }
                    }
                    return@Column
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LiveCamera(
                        torch = torch,
                        onFrame = { bmp ->
                            // Color en cada cuadro (rápido); etiquetas cada vez que hay tiempo
                            val colors = if (neutral) emptyList() else
                                findColorCandidates(bmp, paint.lab, maxSide = 320, limit = 4).map { Triple(it.x, it.y, it.score) }
                            val labels = ArrayList<android.graphics.RectF>()
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            try {
                                val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                                for (b in res.textBlocks) {
                                    val box = b.boundingBox ?: continue
                                    if (vmState.matchBlock(b.lines.map { it.text }).any { it.paint.id == paint.id }) {
                                        labels.add(android.graphics.RectF(box.left.toFloat() / bmp.width, box.top.toFloat() / bmp.height, box.right.toFloat() / bmp.width, box.bottom.toFloat() / bmp.height))
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                recognizer.close()
                            }
                            LiveMarks(bmp.width, bmp.height, labels, colors)
                        },
                        onMarks = { m ->
                            marks = m
                            val now = SystemClock.elapsedRealtime()
                            if (m.labels.isNotEmpty() && now - lastBuzz > 1500) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                lastBuzz = now
                            }
                        },
                    )
                    marks?.let { MarksOverlay(it) }
                    val m = marks
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    ) {
                        Text(
                            when {
                                m == null -> tr("Apunta a tus pinturas…")
                                m.labels.isNotEmpty() -> tr("¡Ahí está! (etiqueta leída)")
                                m.colors.isNotEmpty() -> tr("Zonas de color parecido marcadas. Acércate para leer la etiqueta.")
                                else -> tr("No la veo aquí. Mueve la cámara despacio.")
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarksOverlay(m: LiveMarks) {
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        initialValue = 0.8f, targetValue = 1.3f, animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p",
    )
    Canvas(Modifier.fillMaxSize()) {
        // Mismo recorte que la vista previa (FILL_CENTER): escalar el cuadro para llenar y centrar
        val s = maxOf(size.width / m.frameW, size.height / m.frameH)
        val ox = (size.width - m.frameW * s) / 2
        val oy = (size.height - m.frameH * s) / 2
        fun map(x: Float, y: Float) = Offset(ox + x * m.frameW * s, oy + y * m.frameH * s)
        m.colors.forEach { (x, y, score) ->
            val c = map(x, y)
            val r = (14.dp.toPx() + 14.dp.toPx() * score) * pulse
            drawCircle(Color.Black.copy(alpha = 0.5f), r + 3.dp.toPx(), c, style = Stroke(6.dp.toPx()))
            drawCircle(Color(0xFFFFE600).copy(alpha = 0.5f + 0.5f * score), r, c, style = Stroke(3.dp.toPx()))
        }
        m.labels.forEach { rect ->
            val tl = map(rect.left, rect.top)
            val br = map(rect.right, rect.bottom)
            val pad = 8.dp.toPx() * pulse
            drawRect(Color.Black.copy(alpha = 0.5f), Offset(tl.x - pad - 2, tl.y - pad - 2), Size(br.x - tl.x + 2 * pad + 4, br.y - tl.y + 2 * pad + 4), style = Stroke(6.dp.toPx()))
            drawRect(Color(0xFF3DDC84), Offset(tl.x - pad, tl.y - pad), Size(br.x - tl.x + 2 * pad, br.y - tl.y + 2 * pad), style = Stroke(4.dp.toPx()))
        }
    }
}

@Composable
private fun LiveCamera(
    torch: Boolean,
    onFrame: (Bitmap) -> LiveMarks,
    onMarks: (LiveMarks) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onFrameState by rememberUpdatedState(onFrame)
    val onMarksState by rememberUpdatedState(onMarks)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var last = 0L
        // Vista previa y análisis con la misma proporción para que las marcas coincidan
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { image: ImageProxy ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last < 350) { image.close(); return@setAnalyzer }
                        last = now
                        try {
                            val raw = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            val scale = 640f / maxOf(raw.width, raw.height)
                            val m = Matrix().apply {
                                if (scale < 1f) postScale(scale, scale)
                                if (rot != 0) postRotate(rot.toFloat())
                            }
                            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            val marks = onFrameState(bmp)
                            main.execute { onMarksState(marks) }
                        } catch (_: Exception) {
                        } finally {
                            image.close()
                        }
                    }
                }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                camera = null
            }
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, torch) {
        val c = camera
        if (c != null && c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(torch)
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
