@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.DetectedPaint
import com.ricardochr.estantepinturas.LocateHit
import com.ricardochr.estantepinturas.LocateKind
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.StorageSpot
import com.ricardochr.estantepinturas.matchesWords
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.queryWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Qué abrir en la pantalla de gavetas. */
data class StorageRequest(
    val spotId: String? = null,
    val findPaintId: String? = null,
    val placingPaintId: String? = null,
)

private val MARKER = Color(0xFFFFE600)

/**
 * Encontrar una pintura en tus gavetas.
 * Eliges qué pintura buscas y la app señala en tus fotos dónde puede estar, usando la etiqueta
 * (si se lee), el color visible del bote y dónde guardas otras de la misma marca. Al confirmar
 * "Es aquí" lo recuerda para la próxima vez. También puedes buscar en vivo con la cámara.
 */
@Composable
fun StorageScreen(
    vm: MainViewModel,
    request: StorageRequest,
    onClose: () -> Unit,
    onOpenPaint: (Paint) -> Unit,
    onLiveFind: (Paint) -> Unit,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spotId by remember { mutableStateOf(request.spotId ?: request.findPaintId?.let { vm.locations[it]?.spotId } ?: vm.spots.firstOrNull()?.id) }
    var placing by remember { mutableStateOf(request.placingPaintId?.let { vm.byId[it] }) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(request.findPaintId?.let { vm.byId[it] }) }
    var hits by remember { mutableStateOf<List<LocateHit>?>(null) }
    var hitIdx by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var selectedPin by remember { mutableStateOf<String?>(null) }
    var pickAt by remember { mutableStateOf<Offset?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<DetectedPaint>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val spot = vm.spots.firstOrNull { it.id == spotId }

    // Buscar la pintura elegida en todas las fotos
    LaunchedEffect(target, vm.spots.size) {
        val t = target ?: return@LaunchedEffect
        if (vm.spots.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        searching = true
        hits = null
        val res = try { vm.locatePaint(t) } catch (e: Exception) {
            say(tr("No pude revisar las fotos de tus gavetas: {}", e.message ?: e.javaClass.simpleName))
            emptyList()
        }
        hits = res
        hitIdx = 0
        searching = false
        res.firstOrNull()?.let { spotId = it.spot.id }
    }
    val currentHit = hits?.getOrNull(hitIdx)
    LaunchedEffect(currentHit) { currentHit?.let { spotId = it.spot.id } }

    fun added(s: StorageSpot?) {
        if (s == null) say(tr("No pude guardar la foto.")) else {
            spotId = s.id
            say(tr("Foto guardada: {}", s.name))
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addSpot(uri, "") { added(it) }
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) vm.addSpot(u, "") { added(it) }
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "gaveta-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        captureUri = uri
        camera.launch(uri)
    }

    // Pantalla superpuesta dentro de la app (no un diálogo aparte): así respeta las barras del sistema
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(tr("ENCONTRAR PINTURA"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(spot?.name ?: tr("Mis gavetas"), style = MaterialTheme.typography.headlineSmall)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Volver")) } },
                    actions = {
                        if (spot != null) {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = tr("Opciones")) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(tr("Leer todas las etiquetas de esta foto")) }, onClick = {
                                    menuOpen = false
                                    detecting = true
                                    scope.launch {
                                        val res = try { vm.detectInSpot(spot) } catch (e: Exception) {
                                            say(tr("Error al leer la foto: {}", e.message ?: e.javaClass.simpleName))
                                            emptyList()
                                        }
                                        detecting = false
                                        if (res.isEmpty()) say(tr("No pude leer etiquetas en esta foto.")) else detected = res
                                    }
                                })
                                DropdownMenuItem(text = { Text(tr("Cambiar nombre")) }, onClick = { menuOpen = false; newName = spot.name; renaming = true })
                                DropdownMenuItem(text = { Text(tr("Eliminar esta foto")) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )

                // ---- ¿Qué buscas?
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val t = target
                    if (t == null) {
                        SearchField(query, { query = it }, tr("¿Qué pintura buscas?"))
                        val words = queryWords(query)
                        if (words.isNotEmpty()) {
                            val options = remember(query, vm.owned) {
                                vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }
                                    .filter { matchesWords(it.searchText, words) }.take(6)
                            }
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.heightIn(max = 280.dp)) {
                                    if (options.isEmpty()) {
                                        Text(tr("No está en tu inventario."), Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    options.forEachIndexed { i, p ->
                                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { target = p; query = ""; placing = null; selectedPin = null }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Swatch(p.hex, size = 28.dp, metallic = p.type == "Metálica")
                                            Column(Modifier.weight(1f)) {
                                                Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                                Text(listOf(p.brand, p.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(tr("Buscar"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        TargetCard(
                            paint = t,
                            searching = searching,
                            hits = hits,
                            hitIdx = hitIdx,
                            readingFirstTime = spot != null && !vm.isSpotRead(spot),
                            hasSpots = vm.spots.isNotEmpty(),
                            onPrev = { if (hitIdx > 0) hitIdx-- },
                            onNext = { if (hitIdx < (hits?.size ?: 1) - 1) hitIdx++ },
                            onConfirm = {
                                currentHit?.let { h ->
                                    vm.setLocation(t.id, h.spot.id, h.x, h.y)
                                    say(tr("Guardado: {} está en {}", t.name, h.spot.name))
                                    hits = listOf(h.copy(kind = LocateKind.SAVED, confidence = 1f))
                                    hitIdx = 0
                                }
                            },
                            onMarkManually = { placing = t; say(tr("Toca en la foto dónde está {}", t.name)) },
                            onLive = { onLiveFind(t) },
                            onClear = { target = null; hits = null },
                        )
                    }
                    val p = placing
                    if (p != null) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Toca en la foto dónde está {}", p.name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { placing = null }) { Text(tr("Cancelar")) }
                            }
                        }
                    }
                    // Pin tocado
                    val sel = selectedPin?.let { vm.byId[it] }
                    if (sel != null && target == null) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Swatch(sel.hex, size = 30.dp, metallic = sel.type == "Metálica")
                                Text(sel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onOpenPaint(sel) }) { Text(tr("Ficha")) }
                                TextButton(onClick = { placing = sel; selectedPin = null }) { Text(tr("Mover")) }
                                TextButton(onClick = { vm.clearLocation(sel.id); selectedPin = null }) { Text(tr("Quitar")) }
                            }
                        }
                    }
                }

                // ---- Fotos
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(vm.spots, key = { it.id }) { s ->
                        val n = hits?.count { it.spot.id == s.id } ?: 0
                        FilterChip(
                            selected = s.id == spotId,
                            onClick = { spotId = s.id; selectedPin = null },
                            label = { Text(if (target != null && n > 0) "${s.name} · $n" else s.name) },
                        )
                    }
                    item { AssistChip(onClick = { takePhoto() }, label = { Text(tr("+ Tomar foto")) }) }
                    item {
                        AssistChip(
                            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            label = { Text(tr("+ Desde galería")) },
                        )
                    }
                }

                if (spot == null) {
                    EmptyState(
                        tr("Fotografía tu gaveta"),
                        tr("Toma una foto desde arriba, con buena luz, de donde guardas tus pinturas. Después busca una pintura y la app te señala dónde puede estar, por su etiqueta y por su color."),
                    )
                    return@Column
                }

                val photo by produceState<ImageBitmap?>(null, spot.id) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(vm.spotFile(spot).absolutePath)?.asImageBitmap() }
                }
                val candidatesHere = hits.orEmpty().withIndex().filter { it.value.spot.id == spot.id }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .background(Color(0xFF0E0F12)),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = photo
                    if (img == null) CircularProgressIndicator()
                    else PhotoWithPins(
                        vm = vm,
                        img = img,
                        spot = spot,
                        showPins = target == null,
                        candidates = candidatesHere.map { Offset(it.value.x, it.value.y) to (it.index + 1) },
                        activeCandidate = candidatesHere.indexOfFirst { it.index == hitIdx }.takeIf { it >= 0 },
                        selected = selectedPin,
                        onTapEmpty = { pos ->
                            val p = placing
                            if (p != null) {
                                vm.setLocation(p.id, spot.id, pos.x, pos.y)
                                say(tr("Ubicada: {}", p.name))
                                placing = null
                                if (target?.id == p.id) {
                                    hits = listOf(LocateHit(spot, pos.x, pos.y, LocateKind.SAVED, 1f)); hitIdx = 0
                                }
                            } else if (target == null) {
                                selectedPin = null
                                pickAt = pos
                            }
                        },
                        onTapPin = { id -> selectedPin = id },
                        onTapCandidate = { n -> candidatesHere.getOrNull(n)?.let { hitIdx = it.index } },
                    )
                    if (detecting) {
                        Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                                Text(tr("Leyendo etiquetas…"))
                            }
                        }
                    }
                }
            }
        }

        pickAt?.let { pos ->
            PaintPickerDialog(
                vm = vm,
                title = tr("¿Qué pintura está aquí?"),
                onDismiss = { pickAt = null },
                onPick = { p ->
                    spot?.let { vm.setLocation(p.id, it.id, pos.x, pos.y) }
                    say(tr("Ubicada: {}", p.name))
                    pickAt = null
                },
            )
        }

        detected?.let { list ->
            DetectionsSheet(
                list = list,
                spotsName = { id -> vm.spotOf(id)?.name },
                onDismiss = { detected = null },
                onSave = { chosen ->
                    spot?.let { vm.applyDetections(it.id, chosen) }
                    val nuevas = chosen.count { !it.owned }
                    say(tr("Ubicadas {}", chosen.size) + if (nuevas > 0) tr(" ({} agregadas a tu inventario)", nuevas) else "")
                    detected = null
                },
            )
        }

        if (renaming && spot != null) {
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text(tr("Nombre del lugar")) },
                text = { OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text(tr("Gaveta de arriba")) }) },
                confirmButton = { TextButton(onClick = { vm.renameSpot(spot.id, newName); renaming = false }) { Text(tr("Guardar")) } },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("Cancelar")) } },
            )
        }
        if (confirmDelete && spot != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(tr("¿Eliminar “{}”?", spot.name)) },
                text = { Text(tr("Se borra la foto y las ubicaciones guardadas en ella. Tus pinturas siguen en el inventario.")) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteSpot(spot.id)
                        spotId = vm.spots.firstOrNull { it.id != spot.id }?.id
                        confirmDelete = false
                    }) { Text(tr("Eliminar")) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancelar")) } },
            )
        }
    }
}

@Composable
private fun TargetCard(
    paint: Paint,
    searching: Boolean,
    hits: List<LocateHit>?,
    hitIdx: Int,
    readingFirstTime: Boolean,
    hasSpots: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onConfirm: () -> Unit,
    onMarkManually: () -> Unit,
    onLive: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(paint.hex, size = 36.dp, metallic = paint.type == "Metálica")
                Column(Modifier.weight(1f)) {
                    Text(paint.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(paint.brand, paint.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = tr("Buscar otra")) }
            }
            val h = hits?.getOrNull(hitIdx)
            when {
                !hasSpots -> Text(tr("Primero toma una foto de tu gaveta (botón “+ Tomar foto”), o busca en vivo con la cámara."), style = MaterialTheme.typography.bodySmall)
                searching || hits == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (readingFirstTime) tr("Buscando… la primera vez leo todas las etiquetas de la foto (unos segundos).") else tr("Buscando…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                h == null -> Text(
                    if (paint.hasColor && kotlin.math.sqrt(paint.lab.a * paint.lab.a + paint.lab.b * paint.lab.b) < 12)
                        tr("No la encontré. Es un color neutro (blanco, negro, gris): por color no se distingue, y su etiqueta no se ve en la foto.")
                    else tr("No la encontré en tus fotos. Prueba con la cámara o márcala a mano."),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("Opción {} de {} · {}", hitIdx + 1, hits.size, h.spot.name),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                h.kind.label + when (h.kind) {
                                    LocateKind.COLOR -> tr(" · {}% probable", (h.confidence * 100).roundToInt())
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onPrev, enabled = hitIdx > 0) { Text("◀") }
                        TextButton(onClick = onNext, enabled = hitIdx < hits.size - 1) { Text("▶") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h != null && h.kind != LocateKind.SAVED) {
                    Button(onClick = onConfirm) { Text(tr("Es aquí ✓")) }
                }
                FilledTonalButton(onClick = onLive) { Text(tr("Buscar con cámara")) }
                if (h == null && hasSpots && !searching) {
                    OutlinedButton(onClick = onMarkManually) { Text(tr("Marcar")) }
                }
            }
        }
    }
}

@Composable
private fun PhotoWithPins(
    vm: MainViewModel,
    img: ImageBitmap,
    spot: StorageSpot,
    showPins: Boolean,
    candidates: List<Pair<Offset, Int>>,
    activeCandidate: Int?,
    selected: String?,
    onTapEmpty: (Offset) -> Unit,
    onTapPin: (String) -> Unit,
    onTapCandidate: (Int) -> Unit,
) {
    var zoom by remember(spot.id) { mutableStateOf(1f) }
    var pan by remember(spot.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val pins = if (showPins) vm.locations.values.filter { it.spotId == spot.id } else emptyList()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.75f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(spot.id) {
                detectTransformGestures { _, p, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 8f)
                    pan = if (zoom == 1f) Offset.Zero else pan + p
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val ratio = img.width.toFloat() / img.height
        val (w, h) = if (boxW / boxH > ratio) (boxH * ratio) to boxH else boxW to (boxW / ratio)

        // Acercar y centrar la opción activa
        val focus = activeCandidate?.let { candidates.getOrNull(it)?.first }
        LaunchedEffect(focus, spot.id) {
            if (focus != null) {
                zoom = 2.6f
                pan = Offset(-zoom * (focus.x * w - w / 2), -zoom * (focus.y * h - h / 2))
            }
        }

        Box(
            Modifier
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
                .pointerInput(spot.id, pins, candidates) {
                    detectTapGestures { pos ->
                        val r = 22.dp.toPx() / zoom
                        fun near(x: Float, y: Float): Float {
                            val dx = x * size.width - pos.x
                            val dy = y * size.height - pos.y
                            return dx * dx + dy * dy
                        }
                        val c = candidates.withIndex().minByOrNull { near(it.value.first.x, it.value.first.y) }
                        if (c != null && near(c.value.first.x, c.value.first.y) <= r * r) { onTapCandidate(c.index); return@detectTapGestures }
                        val hit = pins.minByOrNull { near(it.x, it.y) }
                        if (hit != null && near(hit.x, hit.y) <= r * r) { onTapPin(hit.paintId); return@detectTapGestures }
                        onTapEmpty(Offset(pos.x / size.width, pos.y / size.height))
                    }
                },
        ) {
            Image(img, contentDescription = tr("Foto de {}", spot.name), contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            pins.forEach { l ->
                val paint = vm.byId[l.paintId] ?: return@forEach
                val isSel = l.paintId == selected
                val base = 14.dp
                val half = with(density) { base.toPx() / 2 }
                Box(
                    Modifier
                        .offset { IntOffset((l.x * w - half).roundToInt(), (l.y * h - half).roundToInt()) }
                        .size(base)
                        .graphicsLayer {
                            val s = (if (isSel) 1.5f else 1f) / zoom
                            scaleX = s; scaleY = s
                        }
                        .background(Color(parseHex(paint.hex)), CircleShape)
                        .border(2.dp, if (isSel) MARKER else Color.White, CircleShape)
                )
            }
            candidates.forEachIndexed { i, (pos, number) ->
                val active = i == activeCandidate
                val base = 30.dp
                val half = with(density) { base.toPx() / 2 }
                Box(
                    Modifier
                        .offset { IntOffset((pos.x * w - half).roundToInt(), (pos.y * h - half).roundToInt()) }
                        .size(base)
                        .graphicsLayer {
                            val s = (if (active) pulse else 0.8f) / zoom
                            scaleX = s; scaleY = s
                            alpha = if (active) 1f else 0.75f
                        }
                        .border(3.dp, if (active) MARKER else Color.White, CircleShape)
                        .border(5.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        "$number",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .offset(x = 6.dp, y = (-6).dp)
                            .background(if (active) MARKER else Color.White, CircleShape)
                            .padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PaintPickerDialog(vm: MainViewModel, title: String, onDismiss: () -> Unit, fromCatalog: Boolean = false, onPick: (Paint) -> Unit) {
    var q by remember { mutableStateOf("") }
    val inv = remember(vm.owned, vm.byId, fromCatalog) {
        if (fromCatalog) vm.allPaints
        else vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }.sortedWith(compareBy({ it.brand }, { it.name }))
    }
    val words = queryWords(q)
    val shown = remember(q, inv) {
        when {
            words.isEmpty() && fromCatalog -> emptyList()
            words.isEmpty() -> inv
            else -> inv.asSequence().filter { matchesWords(it.searchText, words) }.take(200).toList()
        }
    }
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onDismiss)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Volver")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(q, { q = it }, if (fromCatalog) tr("Buscar en el catálogo: nombre, código, marca…") else tr("Buscar en tu inventario…"))
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { p ->
                        val where = vm.spotOf(p.id)?.name
                        PaintRow(p, extra = where?.let { tr("ya en {}", it) }, onClick = { onPick(p) }) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionsSheet(
    list: List<DetectedPaint>,
    spotsName: (String) -> String?,
    onDismiss: () -> Unit,
    onSave: (List<DetectedPaint>) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val checked = remember(list) { mutableStateListOf<Boolean>().apply { addAll(list.map { true }) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Encontré {} pinturas", list.size), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("Revisa y desmarca las que no correspondan. Las que no estaban en tu inventario se agregarán con 1 bote."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(list.size) { i ->
                    val d = list[i]
                    Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Swatch(d.paint.hex, size = 28.dp, metallic = d.paint.type == "Metálica")
                        Column(Modifier.weight(1f)) {
                            Text(d.paint.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val prev = spotsName(d.paint.id)
                            Text(
                                listOf(d.paint.brand, d.paint.code).filter { it.isNotBlank() }.joinToString(" · ") +
                                    when {
                                        !d.owned -> tr(" · nueva en tu inventario")
                                        prev != null -> tr(" · antes en {}", prev)
                                        else -> ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!d.owned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val n = checked.count { it }
                Button(onClick = { onSave(list.filterIndexed { i, _ -> checked[i] }) }, enabled = n > 0, modifier = Modifier.weight(1f)) {
                    Text(tr("Guardar {} ubicaciones", n))
                }
                OutlinedButton(onClick = onDismiss) { Text(tr("Cancelar")) }
            }
        }
    }
}
