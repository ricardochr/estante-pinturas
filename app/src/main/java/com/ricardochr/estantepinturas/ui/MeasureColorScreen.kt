package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.MeasureConfidence
import com.ricardochr.estantepinturas.MeasureSpot
import com.ricardochr.estantepinturas.MeasureTake
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.SampleReading
import com.ricardochr.estantepinturas.combineTakes
import com.ricardochr.estantepinturas.deltaE2000
import com.ricardochr.estantepinturas.hexToLab
import com.ricardochr.estantepinturas.loadBitmapForAnalysis
import com.ricardochr.estantepinturas.measureTake
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.pixelsOf
import com.ricardochr.estantepinturas.readSample
import com.ricardochr.estantepinturas.referenceCandidates
import com.ricardochr.estantepinturas.suggestReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MeasureStage { PREPARAR, MARCAR, RESULTADO }

/** Foto cargada para marcar las muestras. */
private class MeasurePhoto(val bmp: Bitmap, val pixels: IntArray)

/**
 * Medir el color de [target] con una foto donde aparecen su muestra y muestras de pinturas propias
 * con color conocido. Pantalla completa; [onSave] recibe el color medido (#RRGGBB).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureColorDialog(target: Paint, owned: List<Paint>, onDismiss: () -> Unit, onSave: (com.ricardochr.estantepinturas.MeasureResult) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MeasureColorScreen(target, owned, onDismiss, onSave)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasureColorScreen(target: Paint, owned: List<Paint>, onClose: () -> Unit, onSave: (com.ricardochr.estantepinturas.MeasureResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val candidates = remember(target.id, owned) { referenceCandidates(target, owned).sortedBy { it.name.lowercase() } }
    val refs = remember { mutableStateListOf<Paint>().also { it.addAll(suggestReferences(target, owned)) } }
    var stage by remember { mutableStateOf(MeasureStage.PREPARAR) }
    var photo by remember { mutableStateOf<MeasurePhoto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Zonas marcadas: 0 = la muestra a medir; 1 = papel blanco (opcional); 2.. = referencias en el orden de [refs]
    val spots = remember { mutableStateListOf<MeasureSpot?>() }
    var current by remember { mutableIntStateOf(0) }
    var radius by remember { mutableStateOf(0.035f) }
    val takes = remember { mutableStateListOf<MeasureTake>() }
    var picking by remember { mutableStateOf<Int?>(null) } // índice de referencia a cambiar; -1 = agregar

    fun resetSpots(keep: Boolean) {
        val old = spots.toList()
        spots.clear()
        repeat(refs.size + 2) { spots.add(if (keep) old.getOrNull(it) else null) }
        current = spots.indexOfFirst { it == null }.coerceAtLeast(0)
    }

    fun load(uri: Uri) {
        loading = true
        error = null
        scope.launch {
            val p = withContext(Dispatchers.Default) {
                loadBitmapForAnalysis(context, uri, 1600)?.let { MeasurePhoto(it, pixelsOf(it)) }
            }
            loading = false
            if (p == null) {
                error = tr("No pude abrir la foto.")
            } else {
                photo = p
                // En una toma adicional se conservan las marcas anteriores como punto de partida
                resetSpots(keep = takes.isNotEmpty())
                stage = MeasureStage.MARCAR
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    // Al cerrar, borra las fotos temporales de la medición
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { File(context.cacheDir, "captures").listFiles()?.forEach { if (it.name.startsWith("medicion-") || it.name.startsWith("muestra-")) it.delete() } }
    }
    // Cámara propia con exposición y balance de blancos bloqueados (la del sistema no permite bloquearlos)
    var capturing by remember { mutableStateOf(false) }
    fun takePhoto() { capturing = true }
    fun pickGallery() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun back() {
        when (stage) {
            MeasureStage.PREPARAR -> onClose()
            MeasureStage.MARCAR -> stage = if (takes.isEmpty()) MeasureStage.PREPARAR else MeasureStage.RESULTADO
            MeasureStage.RESULTADO -> { takes.clear(); stage = MeasureStage.PREPARAR }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = ::back)
        FirstTimeHelp(
            "medir", tr("Medir color"),
            listOf(
                tr("Pinta en papel blanco una franja de la pintura a medir y otras de pinturas tuyas que la app te sugiere."),
                tr("Con la cámara de la app, ajusta la exposición hasta que nada esté quemado y bloquea la luz."),
                tr("Marca cada franja y el papel; la app corrige la luz con tus pinturas conocidas y te dice qué tan confiable es."),
                tr("Toma 2 o 3 fotos para subir la confianza antes de guardar."),
            ),
        )
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(tr("MEDIR COLOR"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(target.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = ::back) {
                            Icon(if (stage == MeasureStage.PREPARAR) Icons.Default.Close else Icons.Default.ArrowBack, contentDescription = tr("Atrás"))
                        }
                    },
                )
                when (stage) {
                    MeasureStage.PREPARAR -> PrepareStage(
                        target = target,
                        refs = refs,
                        candidates = candidates,
                        loading = loading,
                        error = error,
                        onChange = { picking = it },
                        onRemove = { refs.removeAt(it) },
                        onAdd = { picking = -1 },
                        onCamera = ::takePhoto,
                        onGallery = ::pickGallery,
                    )
                    MeasureStage.MARCAR -> photo?.let { ph ->
                        MarkStage(
                            target = target,
                            refs = refs,
                            photo = ph,
                            spots = spots,
                            current = current,
                            radius = radius,
                            onRadius = { r ->
                                radius = r
                                spots.indices.forEach { i -> spots[i]?.let { s -> spots[i] = s.copy(r = r) } }
                            },
                            onSelect = { current = it },
                            onTap = { x, y ->
                                spots[current] = MeasureSpot(x, y, radius)
                                val next = (1..spots.size).map { (current + it) % spots.size }.firstOrNull { spots[it] == null }
                                if (next != null) current = next
                            },
                            onCalculate = { readings ->
                                val paper = readings[1].takeIf { spots[1] != null }
                                val tk = measureTake(readings[0], refs.indices.map { refs[it] to readings[it + 2] }, paper)
                                if (tk == null) {
                                    error = tr("Hacen falta la muestra y al menos 3 referencias sin problemas.")
                                } else {
                                    error = null
                                    takes.add(tk)
                                    stage = MeasureStage.RESULTADO
                                }
                            },
                            error = error,
                        )
                    }
                    MeasureStage.RESULTADO -> ResultStage(
                        target = target,
                        refs = refs,
                        takes = takes,
                        loading = loading,
                        onCamera = ::takePhoto,
                        onGallery = ::pickGallery,
                        onRemark = { takes.removeAt(takes.size - 1); stage = MeasureStage.MARCAR },
                        onRestart = { takes.clear(); stage = MeasureStage.PREPARAR },
                        onSave = onSave,
                    )
                }
            }
        }
    }

    if (capturing) {
        Dialog(onDismissRequest = { capturing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LockedCaptureScreen(
                onCaptured = { uri -> capturing = false; load(uri) },
                onCancel = { capturing = false },
            )
        }
    }

    picking?.let { idx ->
        RefPicker(
            candidates = candidates.filter { c -> refs.none { it.id == c.id } },
            onDismiss = { picking = null },
            onPick = { p ->
                if (idx < 0) refs.add(p) else refs[idx] = p
                takes.clear()
                picking = null
            },
        )
    }
}

@Composable
private fun PrepareStage(
    target: Paint,
    refs: List<Paint>,
    candidates: List<Paint>,
    loading: Boolean,
    error: String?,
    onChange: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            tr("La cámara cambia los colores según la luz. Para corregirlo, en la misma foto van muestras de pinturas tuyas cuyo color sí se conoce."),
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Cómo preparar la muestra"), style = MaterialTheme.typography.titleSmall)
                listOf(
                    tr("Pinta en un papel blanco una franja de {} y una de cada referencia, de unos 2 cm, con 2 capas para que cubran. Deja papel sin pintar visible: sirve para el balance de blancos.", target.name),
                    tr("Déjalas secar bien: mojadas se ven más oscuras y brillantes."),
                    tr("Usa luz de día indirecta o una lámpara blanca, sin flash y sin sombras sobre el papel."),
                    tr("Toma la foto de frente y de cerca, con todas las muestras en la misma foto."),
                ).forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(s, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text(tr("Referencias"), style = MaterialTheme.typography.titleSmall)
        if (candidates.size < 3) {
            Text(
                tr("Necesitas al menos 3 pinturas en tu inventario con color medido (no estimado) para usar esta función. Tienes {}.", candidates.size),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                tr("Elegidas para cubrir claros, oscuros y tonos cercanos. Con 4 o más la corrección es más precisa y se puede comprobar mejor."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        refs.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(p.hex, size = 34.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(p.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = { onChange(i) }) { Text(tr("Cambiar")) }
                if (refs.size > 3) IconButton(onClick = { onRemove(i) }) { Icon(Icons.Default.Close, contentDescription = tr("Quitar")) }
            }
        }
        if (refs.size < 6 && candidates.size > refs.size) {
            OutlinedButton(onClick = onAdd) { Text(tr("Agregar referencia")) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val ready = refs.size >= 3 && !loading
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCamera, enabled = ready, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CameraAlt, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Tomar foto"))
            }
            FilledTonalButton(onClick = onGallery, enabled = ready, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Galería"))
            }
        }
        if (loading) Text(tr("Abriendo la foto…"), style = MaterialTheme.typography.bodySmall)
    }
}

private val SPOT_COLORS = listOf(
    Color(0xFFFFE600), Color(0xFF3DDC84), Color(0xFF4FC3F7), Color(0xFFFF8A65), Color(0xFFBA68C8), Color(0xFFF06292), Color(0xFFAED581),
)

@Composable
private fun MarkStage(
    target: Paint,
    refs: List<Paint>,
    photo: MeasurePhoto,
    spots: List<MeasureSpot?>,
    current: Int,
    radius: Float,
    onRadius: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onTap: (Float, Float) -> Unit,
    onCalculate: (List<SampleReading>) -> Unit,
    error: String?,
) {
    val w = photo.bmp.width
    val h = photo.bmp.height
    val readings: List<SampleReading?> = spots.map { s -> s?.let { readSample(photo.pixels, w, h, it) } }
    val image = remember(photo) { photo.bmp.asImageBitmap() }
    val names = listOf(tr("Muestra: {}", target.name), tr("Papel blanco (opcional)")) + refs.map { it.name }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val scale = minOf(boxW / w, boxH / h)
            val dw = w * scale
            val dh = h * scale
            val ox = (boxW - dw) / 2
            val oy = (boxH - dh) / 2
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(photo, current) {
                    detectTapGestures { pos ->
                        val x = (pos.x - ox) / dw
                        val y = (pos.y - oy) / dh
                        if (x in 0f..1f && y in 0f..1f) onTap(x, y)
                    }
                },
            ) {
                spots.forEachIndexed { i, s ->
                    if (s == null) return@forEachIndexed
                    val c = Offset(ox + s.x * dw, oy + s.y * dh)
                    val r = s.r * minOf(w, h) * scale
                    val col = SPOT_COLORS[i % SPOT_COLORS.size]
                    val bad = readings[i]?.usable == false
                    drawCircle(Color.Black.copy(alpha = 0.6f), r + 2.dp.toPx(), c, style = Stroke(5.dp.toPx()))
                    drawCircle(if (bad) Color.Red else col, r, c, style = Stroke(if (i == current) 4.dp.toPx() else 2.5.dp.toPx()))
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                tr("Toca en la foto el centro de: {}", names.getOrElse(current) { "" }),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                tr("Marca solo pintura, lejos de los bordes de la franja. Toca una fila para volver a marcarla."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Tamaño"), style = MaterialTheme.typography.labelMedium)
                Slider(value = radius, onValueChange = onRadius, valueRange = 0.01f..0.08f, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            names.forEachIndexed { i, n ->
                val r = readings.getOrNull(i)
                val sel = i == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(SPOT_COLORS[i % SPOT_COLORS.size]))
                    if (i > 1) Swatch(refs[i - 2].hex, size = 22.dp)
                    Column(Modifier.weight(1f)) {
                        Text(n, style = MaterialTheme.typography.bodyMedium, maxLines = 1, fontWeight = if (i == 0) FontWeight.Bold else null)
                        val status = when {
                            r == null && i == 1 -> tr("Opcional: márcalo para anclar el balance de blancos")
                            r == null -> tr("Sin marcar")
                            r.issues.isEmpty() -> tr("Bien")
                            else -> r.issues.joinToString(" · ") { tr(it.label) }
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                r == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                !r.usable -> MaterialTheme.colorScheme.error
                                r.issues.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val okRefs = readings.drop(2).count { it?.usable == true }
            val ready = readings.firstOrNull()?.usable == true && okRefs >= 3
            Button(onClick = { onCalculate(readings.map { it ?: SampleReading(0.0, 0.0, 0.0, listOf(com.ricardochr.estantepinturas.SampleIssue.VACIA)) }) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Calcular color"))
            }
            if (!ready) {
                Text(
                    tr("Marca la muestra y al menos 3 referencias sin problemas en rojo."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultStage(
    target: Paint,
    refs: List<Paint>,
    takes: List<MeasureTake>,
    loading: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemark: () -> Unit,
    onRestart: () -> Unit,
    onSave: (com.ricardochr.estantepinturas.MeasureResult) -> Unit,
) {
    val res = combineTakes(takes) ?: return
    val last = takes.last()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BigSquare(target.hex)
                Text(tr("Antes"), style = MaterialTheme.typography.labelMedium)
                Text(target.hex, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BigSquare(res.hex)
                Text(tr("Medido"), style = MaterialTheme.typography.labelMedium)
                Text(res.hex, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Confianza"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    tr(res.confidence.label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = when (res.confidence) {
                        MeasureConfidence.ALTA -> MaterialTheme.colorScheme.primary
                        MeasureConfidence.MEDIA -> MaterialTheme.colorScheme.secondary
                        MeasureConfidence.BAJA -> MaterialTheme.colorScheme.tertiary
                        MeasureConfidence.RECHAZADA -> MaterialTheme.colorScheme.error
                    },
                )
                if (target.hasColor) {
                    val d = deltaE2000(hexToLab(target.hex), hexToLab(res.hex))
                    Text(
                        when {
                            d < 3 -> tr("Casi igual al color que tenía")
                            d < 8 -> tr("Algo distinto al color que tenía")
                            else -> tr("Muy distinto al color que tenía")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Comprobación con tus referencias"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Cada referencia se calculó sin usarla en la corrección y se comparó con su color conocido. Diferencia promedio {} (máxima {}). Menos de 3 es casi imperceptible; más de 10 es mala.", "%.1f".format(res.meanError), "%.1f".format(res.maxError)),
                    style = MaterialTheme.typography.bodySmall,
                )
                refs.forEach { p ->
                    val e = last.refErrors[p.id]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatch(p.hex, size = 20.dp)
                        Text(p.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            e?.let { "%.1f".format(it) } ?: tr("no usada"),
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                e == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                e > 10 -> MaterialTheme.colorScheme.error
                                e > 5 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                Text(
                    tr("Corrección usada: {}.", tr(last.model.label)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val notes = buildList {
            if (last.paperUsed) add(tr("Balance de blancos anclado al papel blanco."))
            if (res.takes == 1) add(tr("Con una sola foto no se puede comprobar que el resultado se repite. Toma otra (puedes mover un poco la luz o el papel) para subir la confianza."))
            else add(tr("{} fotos; diferencia entre ellas {}.", res.takes, "%.1f".format(res.spread)) + if (res.spread > 4) tr(" No coinciden bien: revisa la luz y los reflejos.") else "")
            if (res.outOfRange) add(tr("La muestra es más clara o más oscura que todas tus referencias: agrega una referencia más parecida en claridad."))
            last.warnings.forEach { add(tr(it)) }
            if (res.confidence == MeasureConfidence.RECHAZADA) add(tr("Las referencias no se reproducen bien, así que el color medido no es confiable. Revisa que cada referencia sea la pintura correcta, que estén secas y la luz sea pareja."))
        }
        notes.forEach { n -> Text("• $n", style = MaterialTheme.typography.bodySmall) }

        if (loading) Text(tr("Abriendo la foto…"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCamera, enabled = !loading, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CameraAlt, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Otra foto"))
            }
            OutlinedButton(onClick = onGallery, enabled = !loading, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Galería"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onRemark, modifier = Modifier.weight(1f)) { Text(tr("Volver a marcar")) }
            TextButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text(tr("Empezar de nuevo")) }
        }
        Button(
            onClick = { onSave(res) },
            enabled = res.confidence.accept,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Guardar este color")) }
        if (res.confidence == MeasureConfidence.BAJA) {
            Text(
                tr("Puedes guardarlo, pero conviene tomar otra foto o agregar referencias antes."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun BigSquare(hex: String) {
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(parseHex(hex).toInt()))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun RefPicker(candidates: List<Paint>, onDismiss: () -> Unit, onPick: (Paint) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) candidates else candidates.filter { "${it.name} ${it.brand} ${it.line} ${it.code}".lowercase().contains(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Elegir referencia")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(tr("Buscar")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    tr("Solo pinturas tuyas con color medido, opacas y sin efectos."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Swatch(p.hex, size = 28.dp)
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(p.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}
