package com.ricardochr.estantepinturas.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
            onResult(codes, blocks)
        }
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }
}

@Composable
fun ScanScreen(vm: MainViewModel, onLinkBarcode: (String) -> Unit, onNotFound: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("Falta permiso de cámara", "Para escanear las etiquetas la app necesita usar la cámara. Todo se procesa en el teléfono.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
        }
        return
    }

    // Detecciones de esta sesión: la más reciente arriba.
    val hits = remember { mutableStateListOf<Paint>() }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    val vmState by rememberUpdatedState(vm)

    val analyzer = remember {
        PaintAnalyzer(isPaused = { paused }) { codes, blocks ->
            val result = vmState.interpret(codes, blocks)
            result.hits.forEach { h ->
                val idx = hits.indexOfFirst { it.id == h.paint.id }
                if (idx > 0) hits.removeAt(idx)
                if (idx != 0) hits.add(0, h.paint)
            }
            while (hits.size > 12) hits.removeAt(hits.size - 1)
            if (result.hits.isEmpty() && result.unknownBarcodes.isNotEmpty()) unknownBarcode = result.unknownBarcodes.first()
            if (result.hits.isNotEmpty()) unknownBarcode = null
        }
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            CameraPreview(analyzer, torch, Modifier.fillMaxSize())
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            ) {
                Text(
                    if (paused) "En pausa" else "Apunta a la etiqueta o al código de barras",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { paused = !paused }) { Text(if (paused) "Seguir" else "Pausar") }
                FilledTonalButton(onClick = { torch = !torch }) { Text(if (torch) "Apagar luz" else "Linterna") }
                if (hits.isNotEmpty()) FilledTonalButton(onClick = { hits.clear(); unknownBarcode = null }) { Text("Limpiar") }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val ub = unknownBarcode
            if (ub != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Código de barras nuevo", style = MaterialTheme.typography.labelLarge)
                            Text(ub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { paused = true; onLinkBarcode(ub) }) { Text("Asociar") }
                    }
                }
            }
            if (hits.isEmpty()) {
                EmptyState(
                    "Buscando…",
                    "Se reconoce el código (70.955, MPA-040, WP1401), el nombre impreso o el código de barras. Los de Vallejo se reconocen solos; los demás se asocian una vez y quedan guardados.",
                )
                TextButton(onClick = onNotFound, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Buscar a mano en el catálogo") }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(hits, key = { it.id }) { p ->
                        val qty = vm.qtyOf(p.id)
                        PaintRow(p, onClick = {}) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (qty > 0) OwnedBadge(qty)
                                if (qty == 0) {
                                    Button(onClick = { vm.add(p.id, 1); onAdded("Agregada: ${p.name}") }) { Text("Agregar") }
                                } else {
                                    OutlinedButton(onClick = { vm.add(p.id, 1); onAdded("${p.name}: ahora ${qty + 1} botes") }) { Text("+1") }
                                }
                            }
                        }
                    }
                    item {
                        TextButton(onClick = onNotFound, modifier = Modifier.padding(horizontal = 8.dp)) { Text("¿No aparece? Buscar en el catálogo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(analyzer: PaintAnalyzer, torch: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
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
