package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import java.util.concurrent.Executors

/** Un bote anotado en el escaneo en serie y cuántos tenías antes de escanearlo. */
private class ScanLog(val paint: Paint, val hadBefore: Int)

/** Analiza cuadros de la cámara con ML Kit (sin internet): códigos de barras + texto. */
class PaintAnalyzer(
    private val isPaused: () -> Boolean,
    private val onResult: (barcodes: List<String>, blocks: List<List<String>>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_QR_CODE,
            )
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var last = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        if (media == null || isPaused() || now - last < 300) {
            image.close()
            return
        }
        last = now
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        val barcodeTask = barcodeScanner.process(input)
        val textTask = textRecognizer.process(input)
        Tasks.whenAllComplete(barcodeTask, textTask).addOnCompleteListener {
            val codes = if (barcodeTask.isSuccessful) barcodeTask.result.mapNotNull { it.rawValue } else emptyList()
            val blocks = if (textTask.isSuccessful) textTask.result.textBlocks.map { b -> b.lines.map { it.text } } else emptyList()
            image.close()
            if (!isPaused()) onResult(codes, blocks)
        }
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }
}

/**
 * Escáner de una pintura a la vez: al reconocer algo se detiene y muestra solo ese resultado.
 * Agregar o "Escanear otra" limpia el resultado y vuelve a buscar.
 *
 * @param active false mientras hay un diálogo encima, para no seguir detectando detrás.
 */
@Composable
fun ScanScreen(
    vm: MainViewModel,
    active: Boolean,
    onLinkBarcode: (String) -> Unit,
    onNotFound: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(tr("Falta permiso de cámara"), tr("Para escanear las etiquetas la app necesita usar la cámara. Todo se procesa en el teléfono."))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(tr("Dar permiso")) }
        }
        return
    }

    val haptics = LocalHapticFeedback.current
    var found by remember { mutableStateOf<List<Paint>>(emptyList()) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    // Ids vistos en el cuadro anterior: el texto debe repetirse dos veces seguidas para aceptarse
    var lastIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val showingResult = found.isNotEmpty() || unknownBarcode != null
    val vmState by rememberUpdatedState(vm)
    val activeState by rememberUpdatedState(active)
    val showingState by rememberUpdatedState(showingResult)

    // Escaneo en serie: cada bote reconocido se anota solo y la cámara sigue buscando
    var serial by rememberSaveable { mutableStateOf(false) }
    val serialState by rememberUpdatedState(serial)
    var session by remember { mutableStateOf<List<ScanLog>>(emptyList()) }
    val recent = remember { HashMap<String, Long>() }

    fun reset() {
        found = emptyList()
        unknownBarcode = null
        lastIds = emptySet()
    }

    val analyzer = remember {
        PaintAnalyzer(isPaused = { showingState || !activeState }) { codes, blocks ->
            if (showingState || !activeState) return@PaintAnalyzer
            val result = vmState.interpret(codes, blocks)
            val ids = result.hits.map { it.paint.id }.toSet()
            val byBarcode = result.hits.any { it.via == "código de barras" }
            if (ids.isNotEmpty()) {
                val stable = ids.intersect(lastIds)
                if (byBarcode || stable.isNotEmpty()) {
                    val ordered = result.hits.map { it.paint }
                        .sortedByDescending { if (it.id in stable) 1 else 0 }
                        .distinctBy { it.id }
                        .take(5)
                    if (serialState && (ordered.size == 1 || byBarcode)) {
                        // Una sola candidata: se anota sin detener la cámara. La misma pintura no se repite en 5 s.
                        val p = ordered.first()
                        val now = SystemClock.elapsedRealtime()
                        if (now - (recent[p.id] ?: 0L) > 5000) {
                            recent[p.id] = now
                            val had = vmState.qtyOf(p.id)
                            if (had == 0) vmState.add(p.id, 1)
                            session = listOf(ScanLog(p, had)) + session.filterNot { it.paint.id == p.id }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        found = ordered
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    lastIds = emptySet()
                } else {
                    lastIds = ids
                }
            } else {
                lastIds = emptySet()
                if (result.unknownBarcodes.isNotEmpty()) {
                    unknownBarcode = result.unknownBarcodes.first()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .background(Color.Black)
        ) {
            CameraPreview(analyzer, torch, Modifier.fillMaxSize())
            // Marco guía
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.7f)
                    .height(120.dp)
                    .border(2.dp, Color.White.copy(alpha = if (showingResult) 0.2f else 0.8f), RoundedCornerShape(12.dp))
            )
            if (showingResult) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            ) {
                Text(
                    if (showingResult) tr("Detectada — revisa abajo") else tr("Pon la etiqueta dentro del marco"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            androidx.compose.material3.FilterChip(
                selected = serial,
                onClick = { serial = !serial; if (!serial) session = emptyList() },
                label = { Text(tr("En serie")) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    labelColor = Color.White,
                ),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
            FilledTonalButton(
                onClick = { torch = !torch },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) { Text(if (torch) tr("Apagar luz") else tr("Linterna")) }
        }

        // Panel de resultados: fondo opaco y colores del tema para que el texto siempre se lea
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            val ub = unknownBarcode
            when {
                found.isNotEmpty() -> FoundPanel(
                    vm = vm,
                    paints = found,
                    onAdd = { p ->
                        val q = vm.qtyOf(p.id)
                        vm.add(p.id, 1)
                        onAdded(if (q == 0) tr("Agregada: {}", p.name) else tr("{}: ahora {} botes", p.name, q + 1))
                        reset()
                    },
                    onNext = { reset() },
                    onNotFound = { reset(); onNotFound() },
                )
                ub != null -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(tr("Código de barras nuevo"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(ub, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tr("No sé a qué pintura corresponde. Asócialo una vez y la próxima vez se reconocerá solo."),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onLinkBarcode(ub); reset() }) { Text(tr("Asociar")) }
                        OutlinedButton(onClick = { reset() }) { Text(tr("Escanear otra")) }
                    }
                }
                serial -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                    Text(
                        if (session.isEmpty()) tr("Escaneo en serie") else tr("Escaneadas: {}", session.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        tr("Pasa los botes uno tras otro: las que no tienes se agregan solas y te aviso de las que ya tienes."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    session.forEach { log ->
                        val qty = vm.qtyOf(log.paint.id)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Swatch(log.paint.hex, size = 36.dp, metallic = log.paint.type == "Metálica")
                            Column(Modifier.weight(1f)) {
                                Text(log.paint.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    if (log.hadBefore == 0) tr("Agregada · tienes {}", qty) else tr("Ya la tenías · tienes {}", qty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (log.hadBefore == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (log.hadBefore == 0 && qty > 0) {
                                TextButton(onClick = { vm.remove(log.paint.id); session = session.filterNot { it.paint.id == log.paint.id } }) { Text(tr("Deshacer")) }
                            } else {
                                TextButton(onClick = { vm.add(log.paint.id, 1) }) { Text(tr("+1 bote")) }
                            }
                        }
                    }
                    TextButton(onClick = onNotFound, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("Buscar a mano en el catálogo")) }
                }
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(tr("Buscando…"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        tr("Se reconoce el código (70.955, MPA-040, WP1401), el nombre impreso o el código de barras. Al detectar una pintura el escáner se detiene para que confirmes."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = onNotFound) { Text(tr("Buscar a mano en el catálogo")) }
                }
            }
        }
    }
}

@Composable
private fun FoundPanel(
    vm: MainViewModel,
    paints: List<Paint>,
    onAdd: (Paint) -> Unit,
    onNext: () -> Unit,
    onNotFound: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            if (paints.size == 1) tr("Encontré esta pintura") else tr("¿Cuál de estas es?"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        paints.forEach { p ->
            val qty = vm.qtyOf(p.id)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Swatch(p.hex, size = 48.dp, metallic = p.type == "Metálica")
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(p.brand, p.line, p.code).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (qty > 0) {
                        Box(Modifier.padding(top = 4.dp)) { OwnedBadge(qty) }
                    }
                }
                Button(onClick = { onAdd(p) }) { Text(if (qty == 0) tr("Agregar") else "+1") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text(tr("Escanear otra")) }
            TextButton(onClick = onNotFound) { Text(tr("No es ninguna")) }
        }
    }
}

@Composable
private fun CameraPreview(analyzer: PaintAnalyzer, torch: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: respeta el recorte del contenedor (SurfaceView se dibujaba debajo del panel de resultados)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                camera = null
                android.widget.Toast.makeText(context, tr("No pude abrir la cámara: {}", e.message ?: e.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, torch) {
        val c = camera
        if (c != null && c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(torch)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
