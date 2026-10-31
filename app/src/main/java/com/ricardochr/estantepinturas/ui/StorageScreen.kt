@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.DetectedPaint
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.StorageSpot
import com.ricardochr.estantepinturas.matchesWords
import com.ricardochr.estantepinturas.norm
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
    val focusPaintId: String? = null,
    val placingPaintId: String? = null,
)

/**
 * Fotos de tus gavetas con marcas donde está cada pintura.
 * - Detectar etiquetas: lee los botes con la etiqueta visible y los ubica solos.
 * - Toca la foto para marcar a mano dónde está una pintura (útil para botes parados).
 * - Busca una pintura y la marca parpadea en la foto.
 */
@Composable
fun StorageScreen(
    vm: MainViewModel,
    request: StorageRequest,
    onClose: () -> Unit,
    onOpenPaint: (Paint) -> Unit,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spotId by remember { mutableStateOf(request.spotId ?: request.focusPaintId?.let { vm.locations[it]?.spotId } ?: vm.spots.firstOrNull()?.id) }
    var placing by remember { mutableStateOf(request.placingPaintId?.let { vm.byId[it] }) }
    var focus by remember { mutableStateOf(request.focusPaintId) }
    var query by remember { mutableStateOf("") }
    var selectedPin by remember { mutableStateOf<String?>(null) }
    var pickAt by remember { mutableStateOf<Offset?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<DetectedPaint>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val spot = vm.spots.firstOrNull { it.id == spotId }

    fun added(s: StorageSpot?) {
        if (s == null) say("No pude guardar la foto.") else {
            spotId = s.id
            say("Foto guardada: ${s.name}. Toca \"Detectar etiquetas\" o marca a mano.")
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // La ventana del diálogo ocupa toda la pantalla: dejar libre la barra de estado y la de navegación
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("DÓNDE ESTÁN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(spot?.name ?: "Mis gavetas", style = MaterialTheme.typography.headlineSmall)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") } },
                    actions = {
                        if (spot != null) {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Opciones") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menuOpen = false; newName = spot.name; renaming = true })
                                DropdownMenuItem(text = { Text("Eliminar esta foto") }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )

                // Lugares guardados + agregar
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(vm.spots, key = { it.id }) { s ->
                        FilterChip(selected = s.id == spotId, onClick = { spotId = s.id; selectedPin = null }, label = { Text(s.name) })
                    }
                    item { AssistChip(onClick = { takePhoto() }, label = { Text("+ Tomar foto") }) }
                    item {
                        AssistChip(
                            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            label = { Text("+ Desde galería") },
                        )
                    }
                }

                if (spot == null) {
                    EmptyState(
                        "Fotografía tu gaveta",
                        "Toma una foto de donde guardas tus pinturas, desde arriba y con buena luz. " +
                            "La app lee las etiquetas que se vean y marca dónde está cada pintura; las que no se lean (botes parados) las marcas tocando la foto.",
                    )
                    return@Column
                }

                val pinsHere = vm.locations.values.filter { it.spotId == spot.id }
                val words = queryWords(query)
                val matching = remember(query, pinsHere, vm.byId) {
                    if (words.isEmpty()) emptySet()
                    else pinsHere.mapNotNull { l -> vm.byId[l.paintId]?.takeIf { matchesWords(it.searchText, words) }?.id }.toSet()
                }
                val elsewhere = remember(query, vm.locations, spot.id) {
                    if (words.isEmpty()) emptyList()
                    else vm.locations.values.filter { it.spotId != spot.id }
                        .mapNotNull { l -> vm.byId[l.paintId]?.takeIf { matchesWords(it.searchText, words) }?.let { it to l } }
                }

                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchField(query, { query = it; focus = null }, "¿Qué pintura buscas?")
                    val p = placing
                    when {
                        p != null -> Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Toca en la foto dónde está ${p.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { placing = null }) { Text("Cancelar") }
                            }
                        }
                        words.isNotEmpty() -> Text(
                            when {
                                matching.isNotEmpty() -> "${matching.size} en esta foto (parpadeando)"
                                elsewhere.isNotEmpty() -> "No está en esta foto"
                                else -> "No la tienes ubicada. Búscala en Mis pinturas y usa \"Marcar dónde está\"."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            "${pinsHere.size} pinturas ubicadas · toca la foto para marcar una · pellizca para acercar",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (elsewhere.isNotEmpty() && matching.isEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(elsewhere) { (paint, loc) ->
                                val other = vm.spots.firstOrNull { it.id == loc.spotId }
                                AssistChip(onClick = { spotId = loc.spotId; focus = paint.id; query = "" }, label = { Text("${paint.name} → ${other?.name}") })
                            }
                        }
                    }
                }

                // Foto con marcas
                val photo by produceState<ImageBitmap?>(null, spot.id) {
                    value = withContext(Dispatchers.IO) {
                        val f = vm.spotFile(spot)
                        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                        BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                    }
                }
                val img = photo
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .background(Color(0xFF0E0F12)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (img == null) CircularProgressIndicator()
                    else PhotoWithPins(
                        vm = vm,
                        img = img,
                        spot = spot,
                        highlight = if (focus != null) setOf(focus!!) else matching,
                        selected = selectedPin,
                        onTapEmpty = { pos ->
                            val p = placing
                            if (p != null) {
                                vm.setLocation(p.id, spot.id, pos.x, pos.y)
                                say("Ubicada: ${p.name}")
                                placing = null
                                focus = p.id
                            } else {
                                selectedPin = null
                                pickAt = pos
                            }
                        },
                        onTapPin = { id -> selectedPin = id; focus = null },
                    )
                    if (detecting) {
                        Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                                Text("Leyendo etiquetas…")
                            }
                        }
                    }
                }

                // Pin seleccionado o acciones
                val sel = selectedPin?.let { vm.byId[it] }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
                    if (sel != null) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Swatch(sel.hex, size = 36.dp, metallic = sel.type == "Metálica")
                            Column(Modifier.weight(1f)) {
                                Text(sel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(sel.brand, sel.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onOpenPaint(sel) }) { Text("Ficha") }
                            TextButton(onClick = { placing = sel; selectedPin = null }) { Text("Mover") }
                            TextButton(onClick = { vm.clearLocation(sel.id); selectedPin = null }) { Text("Quitar") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    detecting = true
                                    scope.launch {
                                        val res = try { vm.detectInSpot(spot) } catch (e: Exception) { emptyList() }
                                        detecting = false
                                        if (res.isEmpty()) say("No pude leer etiquetas en esta foto. Marca las pinturas tocando la foto.")
                                        else detected = res
                                    }
                                },
                                enabled = !detecting,
                                modifier = Modifier.weight(1f),
                            ) { Text("Detectar etiquetas") }
                        }
                    }
                }
            }
        }

        // Elegir qué pintura está en el punto tocado
        pickAt?.let { pos ->
            PaintPickerDialog(
                vm = vm,
                title = "¿Qué pintura está aquí?",
                onDismiss = { pickAt = null },
                onPick = { p ->
                    spot?.let { vm.setLocation(p.id, it.id, pos.x, pos.y) }
                    say("Ubicada: ${p.name}")
                    pickAt = null
                    focus = p.id
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
                    say("Ubicadas ${chosen.size}" + if (nuevas > 0) " ($nuevas agregadas a tu inventario)" else "")
                    detected = null
                },
            )
        }

        if (renaming && spot != null) {
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Nombre del lugar") },
                text = { OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("Gaveta de arriba") }) },
                confirmButton = { TextButton(onClick = { vm.renameSpot(spot.id, newName); renaming = false }) { Text("Guardar") } },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
            )
        }
        if (confirmDelete && spot != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("¿Eliminar “${spot.name}”?") },
                text = { Text("Se borra la foto y las ubicaciones marcadas en ella. Tus pinturas siguen en el inventario.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteSpot(spot.id)
                        spotId = vm.spots.firstOrNull { it.id != spot.id }?.id
                        confirmDelete = false
                    }) { Text("Eliminar") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
private fun PhotoWithPins(
    vm: MainViewModel,
    img: ImageBitmap,
    spot: StorageSpot,
    highlight: Set<String>,
    selected: String?,
    onTapEmpty: (Offset) -> Unit,
    onTapPin: (String) -> Unit,
) {
    var zoom by remember(spot.id) { mutableStateOf(1f) }
    var pan by remember(spot.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val pins = vm.locations.values.filter { it.spotId == spot.id }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
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

        // Al enfocar una pintura: acercar y centrar su marca
        val focusId = highlight.singleOrNull()
        LaunchedEffect(focusId, spot.id) {
            val l = focusId?.let { vm.locations[it] }
            if (l != null && l.spotId == spot.id) {
                zoom = 3f
                pan = Offset(-zoom * (l.x * w - w / 2), -zoom * (l.y * h - h / 2))
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
                .pointerInput(spot.id, pins) {
                    detectTapGestures { pos ->
                        val fx = pos.x / size.width
                        val fy = pos.y / size.height
                        // ¿Tocó una marca? (radio de 18 dp en pantalla)
                        val r = 18.dp.toPx() / zoom
                        val hit = pins.minByOrNull { (it.x * size.width - pos.x).let { dx -> dx * dx } + (it.y * size.height - pos.y).let { dy -> dy * dy } }
                        if (hit != null) {
                            val dx = hit.x * size.width - pos.x
                            val dy = hit.y * size.height - pos.y
                            if (dx * dx + dy * dy <= r * r) { onTapPin(hit.paintId); return@detectTapGestures }
                        }
                        onTapEmpty(Offset(fx, fy))
                    }
                },
        ) {
            Image(img, contentDescription = "Foto de ${spot.name}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            val dim = highlight.isNotEmpty()
            pins.forEach { l ->
                val paint = vm.byId[l.paintId] ?: return@forEach
                val isHi = l.paintId in highlight
                val isSel = l.paintId == selected
                val base = 14.dp
                val sizePx = with(density) { base.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((l.x * w - sizePx / 2).roundToInt(), (l.y * h - sizePx / 2).roundToInt()) }
                        .size(base)
                        .graphicsLayer {
                            val s = (if (isHi) pulse else 1f) * (if (isSel) 1.4f else 1f) / zoom
                            scaleX = s
                            scaleY = s
                            alpha = if (dim && !isHi) 0.3f else 1f
                        }
                        .background(Color(parseHex(paint.hex)), CircleShape)
                        .border(2.dp, if (isHi || isSel) Color(0xFFFFE600) else Color.White, CircleShape)
                        .border(3.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
    }
}

@Composable
fun PaintPickerDialog(vm: MainViewModel, title: String, onDismiss: () -> Unit, onPick: (Paint) -> Unit) {
    var q by remember { mutableStateOf("") }
    val inv = remember(vm.owned, vm.byId) {
        vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }.sortedWith(compareBy({ it.brand }, { it.name }))
    }
    val words = queryWords(q)
    val shown = remember(q, inv) { if (words.isEmpty()) inv else inv.filter { matchesWords(it.searchText, words) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(q, { q = it }, "Buscar en tu inventario…")
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { p ->
                        val where = vm.spotOf(p.id)?.name
                        PaintRow(p, extra = where?.let { "ya en $it" }, onClick = { onPick(p) }) {}
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
            Text("Encontré ${list.size} pinturas", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Revisa y desmarca las que no correspondan. Las que no estaban en tu inventario se agregarán con 1 bote.",
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
                                        !d.owned -> " · nueva en tu inventario"
                                        prev != null -> " · antes en $prev"
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
                    Text("Guardar $n ubicaciones")
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    }
}
