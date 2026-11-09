package com.ricardochr.estantepinturas.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ricardochr.estantepinturas.loadBitmapForAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cómo se muestra la referencia sobre la cámara. */
private enum class OverlayMode(val label: String) {
    Blend("Fundido"),
    Curtain("Cortina"),
    Edges("Contornos"),
}

private val LINE_COLORS = listOf(0xFFFFFFFF.toInt(), 0xFF00FF66.toInt(), 0xFFFF3DCB.toInt(), 0xFF111111.toInt())
private val LINE_NAMES = listOf("blanco", "verde", "rosa", "negro")

/**
 * Superponer referencia: la cámara muestra tu figura y encima la foto de referencia con la
 * transparencia que elijas, para comparar colores, luces y sombras mientras pintas.
 *  - Fundido: la foto semitransparente sobre la cámara.
 *  - Cortina: una línea que deslizas; a un lado la cámara, al otro la foto.
 *  - Contornos: solo las líneas de la foto, para ver tu figura completa y alinear formas.
 * La foto se mueve, se acerca y se gira con dos dedos; B/N compara valores (claro/oscuro) sin
 * que el color distraiga; Congelar fija la imagen de la cámara para dejar el teléfono.
 */
@Composable
fun CompareScreen(initialUri: Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }

    var uriText by rememberSaveable { mutableStateOf(initialUri?.toString()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) uriText = uri.toString()
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    LaunchedEffect(Unit) { if (uriText == null) pick() }

    var image by remember { mutableStateOf<Bitmap?>(null) }
    var edges by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var lineColor by rememberSaveable { mutableStateOf(0) }

    var mode by rememberSaveable { mutableStateOf(OverlayMode.Blend) }
    var opacity by rememberSaveable { mutableFloatStateOf(0.5f) }
    var divider by rememberSaveable { mutableFloatStateOf(0.5f) }
    var gray by rememberSaveable { mutableStateOf(false) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var camZoom by rememberSaveable { mutableFloatStateOf(0f) }
    var showControls by rememberSaveable { mutableStateOf(true) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }

    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var rotation by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    fun resetImage() { scale = 1f; rotation = 0f; offsetX = 0f; offsetY = 0f }

    // Carga la foto (con su orientación) y calcula sus contornos
    LaunchedEffect(uriText) {
        val u = uriText ?: return@LaunchedEffect
        loadError = false
        val bmp = withContext(Dispatchers.IO) {
            try { loadBitmapForAnalysis(context, Uri.parse(u), 1600) } catch (e: Exception) { null }
        }
        if (bmp == null) { loadError = true; return@LaunchedEffect }
        image = bmp
        edges = null
        resetImage()
    }
    LaunchedEffect(image, lineColor, mode) {
        val src = image ?: return@LaunchedEffect
        if (mode == OverlayMode.Edges) edges = withContext(Dispatchers.Default) { edgeBitmap(src, LINE_COLORS[lineColor]) }
    }

    // Pantalla encendida mientras comparas
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val grayMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
    var boxWidth by remember { mutableStateOf(1) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().onSizeChanged { boxWidth = it.width }) {
            // ---- Fondo: tu figura (cámara en vivo o congelada)
            val camModifier = Modifier.fillMaxSize().then(
                if (gray && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.graphicsLayer {
                        val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                        renderEffect = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm)).asComposeRenderEffect()
                    }
                } else Modifier
            )
            when {
                !hasPermission -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Falta permiso de cámara", color = Color.White)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
                }
                frozen != null -> Image(
                    frozen!!.asImageBitmap(), contentDescription = "Tu figura (congelada)",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (gray) ColorFilter.colorMatrix(grayMatrix) else null,
                )
                else -> CompareCamera(camModifier, torch, camZoom, onFreeze = { frozen = it }, freezeRequest = frozenRequest)
            }

            // ---- Encima: la referencia
            val shown = if (mode == OverlayMode.Edges) edges else image
            if (shown != null) {
                Image(
                    shown.asImageBitmap(),
                    contentDescription = "Referencia",
                    contentScale = ContentScale.Fit,
                    colorFilter = if (gray && mode != OverlayMode.Edges) ColorFilter.colorMatrix(grayMatrix) else null,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            if (mode == OverlayMode.Curtain) {
                                clipRect(left = divider * size.width) { this@drawWithContent.drawContent() }
                            } else drawContent()
                        }
                        .graphicsLayer {
                            scaleX = scale * if (mirror) -1f else 1f
                            scaleY = scale
                            rotationZ = rotation
                            translationX = offsetX
                            translationY = offsetY
                            alpha = if (mode == OverlayMode.Curtain) 1f else opacity
                        },
                )
            }
            // Gestos sobre la referencia: mover, acercar y girar con dos dedos
            if (!locked) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            scale = (scale * zoom).coerceIn(0.2f, 8f)
                            rotation += rot
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                )
            }
            // Cortina: línea que se desliza
            if (mode == OverlayMode.Curtain && shown != null) {
                val x = with(density) { (divider * boxWidth).toDp() }
                Box(Modifier.fillMaxHeight().width(2.dp).offset(x = x - 1.dp).background(Color.White))
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((divider * boxWidth).roundToInt() - 28.dp.roundToPx(), 0) }
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .pointerInput(boxWidth) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                divider = (divider + drag.x / boxWidth).coerceIn(0f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("⇆", color = Color.Black, style = MaterialTheme.typography.titleLarge) }
            }
        }

        // ---- Barra superior
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Surface(color = Color.Black.copy(alpha = 0.55f)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text("Superponer referencia", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                image == null && loadError -> "No pude abrir esa imagen"
                                image == null -> "Elige una foto de tu galería"
                                locked -> "Referencia fija · toca “Mover” para ajustarla"
                                else -> "Dos dedos: mover, acercar y girar la referencia"
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { showControls = !showControls }) {
                        Text(if (showControls) "Ocultar" else "Ajustes", color = Color.White)
                    }
                }
            }
            Box(Modifier.weight(1f))
            if (showControls) {
                Surface(color = Color.Black.copy(alpha = 0.65f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OverlayMode.values().forEach { m ->
                                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                            }
                            if (mode == OverlayMode.Edges) {
                                FilterChip(
                                    selected = false,
                                    onClick = { lineColor = (lineColor + 1) % LINE_COLORS.size },
                                    label = { Text("Línea: " + LINE_NAMES[lineColor]) },
                                )
                            }
                        }
                        if (mode != OverlayMode.Curtain) {
                            LabeledSlider("Referencia visible ${(opacity * 100).roundToInt()} %", opacity, { opacity = it })
                        } else {
                            Text(
                                "Desliza el círculo: a la izquierda tu figura, a la derecha la referencia.",
                                color = Color.White, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (frozen == null) LabeledSlider("Zoom de la cámara", camZoom, { camZoom = it })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = gray, onClick = { gray = !gray }, label = { Text("B/N (valores)") })
                            FilterChip(selected = mirror, onClick = { mirror = !mirror }, label = { Text("Espejo") })
                            FilterChip(selected = locked, onClick = { locked = !locked }, label = { Text(if (locked) "Fija" else "Mover") })
                            FilterChip(
                                selected = frozen != null,
                                onClick = { if (frozen != null) frozen = null else frozenRequest.value++ },
                                label = { Text(if (frozen != null) "Reanudar cámara" else "Congelar") },
                                enabled = hasPermission,
                            )
                            FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("Luz") }, enabled = frozen == null)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { pick() }) { Text("Otra imagen", color = Color.White) }
                            TextButton(onClick = { resetImage() }) { Text("Centrar referencia", color = Color.White) }
                        }
                        if (gray && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && frozen == null) {
                            Text(
                                "En tu versión de Android el B/N solo se aplica a la referencia; congela la cámara para verla también en B/N.",
                                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Contador que pide a la cámara una foto fija (cada incremento = congelar). */
private val frozenRequest = mutableStateOf(0)

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}

@Composable
private fun CompareCamera(
    modifier: Modifier,
    torch: Boolean,
    zoom: Float,
    onFreeze: (Bitmap) -> Unit,
    freezeRequest: androidx.compose.runtime.MutableState<Int>,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: permite aplicar el filtro B/N a la vista previa
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(camera, zoom) { camera?.cameraControl?.setLinearZoom(zoom) }
    val request by freezeRequest
    var handled by remember { mutableStateOf(request) }
    LaunchedEffect(request) {
        if (request != handled) {
            handled = request
            previewView.bitmap?.let { onFreeze(it.copy(Bitmap.Config.ARGB_8888, false)) }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Contornos de la foto: bordes (Sobel sobre la luminancia suavizada) en el color elegido y el
 * resto transparente. Se queda con el ~12 % de los bordes más marcados.
 */
private fun edgeBitmap(src: Bitmap, color: Int): Bitmap {
    val maxSide = 900
    val s = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
    val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(2), (src.height * s).toInt().coerceAtLeast(2), true) else src
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val lum = FloatArray(w * h) { i ->
        val c = px[i]
        0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
    }
    // Suavizado 3x3 para no marcar el ruido de la foto
    val blur = FloatArray(w * h)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) sum += lum[(y + dy) * w + x + dx]
        blur[y * w + x] = sum / 9f
    }
    val mag = FloatArray(w * h)
    for (y in 2 until h - 2) for (x in 2 until w - 2) {
        val i = y * w + x
        val gx = -blur[i - w - 1] - 2 * blur[i - 1] - blur[i + w - 1] + blur[i - w + 1] + 2 * blur[i + 1] + blur[i + w + 1]
        val gy = -blur[i - w - 1] - 2 * blur[i - w] - blur[i - w + 1] + blur[i + w - 1] + 2 * blur[i + w] + blur[i + w + 1]
        mag[i] = sqrt(gx * gx + gy * gy)
    }
    // Umbral: percentil 88 de la magnitud (con histograma)
    val maxMag = mag.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val hist = IntArray(256)
    for (m in mag) hist[((m / maxMag) * 255).toInt().coerceIn(0, 255)]++
    var acc = 0
    var cut = 255
    val target = (mag.size * 0.88).toInt()
    for (b in 0..255) { acc += hist[b]; if (acc >= target) { cut = b; break } }
    val threshold = maxOf(cut / 255f * maxMag, maxMag * 0.06f)
    val rgb = color and 0x00FFFFFF
    val out = IntArray(w * h)
    for (i in out.indices) {
        val m = mag[i]
        if (m >= threshold) {
            // Más opaco cuanto más marcado el borde
            val a = (150 + 105 * ((m - threshold) / (maxMag - threshold + 1e-3f))).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or rgb
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}
