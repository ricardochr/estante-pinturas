@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
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
    var spotId by remember { mutableStateOf(request.spotId ?: request.findPaintId?.let { vm.placeOf(it)?.spot?.id } ?: vm.spots.firstOrNull()?.id) }
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
    var newKind by remember { mutableStateOf("Gaveta") }
    // Zonas por marca, varias pinturas a la vez, cambio de foto y botes en más de un lugar
    var zoning by remember { mutableStateOf(false) }
    var zoneRect by remember { mutableStateOf<FloatArray?>(null) }
    var showZones by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    var replaceId by remember { mutableStateOf<String?>(null) }
    var askAlso by remember { mutableStateOf<Triple<Paint, String, Offset>?>(null) }

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
            // Enseguida se le pone nombre y tipo (gaveta, pared…)
            newName = s.name; newKind = s.kind; renaming = true
        }
    }

    /** Lee las etiquetas de la foto y muestra lo encontrado para confirmar. */
    fun readLabels(s: StorageSpot, reread: Boolean) {
        detecting = true
        scope.launch {
            val res = try { vm.detectInSpot(s, reread) } catch (e: Exception) {
                say(tr("Error al leer la foto: {}", e.message ?: e.javaClass.simpleName))
                emptyList()
            }
            detecting = false
            if (res.isEmpty()) say(tr("No pude leer etiquetas en esta foto. Marca zonas por marca o agrega las pinturas a este lugar desde el menú.")) else detected = res
        }
    }

    fun gotPhoto(uri: Uri) {
        val rid = replaceId
        replaceId = null
        if (rid == null) { vm.addSpot(uri, "") { added(it) }; return }
        vm.replaceSpotPhoto(rid, uri) { n ->
            if (n == null) say(tr("No pude guardar la foto."))
            else {
                say(if (n > 0) tr("Foto actualizada. {} pinturas quedaron sin punto; leo las etiquetas para volver a ubicarlas.", n) else tr("Foto actualizada. Leo las etiquetas."))
                vm.spots.firstOrNull { it.id == rid }?.let { readLabels(it, reread = true) }
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) gotPhoto(uri) else replaceId = null
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) gotPhoto(u) else replaceId = null
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
                                DropdownMenuItem(text = { Text(tr("Leer todas las etiquetas de esta foto")) }, onClick = { menuOpen = false; readLabels(spot, reread = false) })
                                DropdownMenuItem(text = { Text(tr("Marcar zona de una marca")) }, onClick = {
                                    menuOpen = false; target = null; hits = null; placing = null; zoning = true
                                })
                                if (vm.zones.any { it.spotId == spot.id }) {
                                    DropdownMenuItem(text = { Text(tr("Zonas de esta foto")) }, onClick = { menuOpen = false; showZones = true })
                                }
                                DropdownMenuItem(text = { Text(tr("Agregar pinturas a este lugar")) }, onClick = { menuOpen = false; bulk = true })
                                DropdownMenuItem(text = { Text(tr("Moví pinturas: tomar foto nueva")) }, onClick = { menuOpen = false; replaceId = spot.id; takePhoto() })
                                DropdownMenuItem(text = { Text(tr("Moví pinturas: foto nueva de la galería")) }, onClick = {
                                    menuOpen = false; replaceId = spot.id
                                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                })
                                DropdownMenuItem(text = { Text(tr("Nombre y tipo de lugar")) }, onClick = { menuOpen = false; newName = spot.name; newKind = spot.kind; renaming = true })
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
                            onNotHere = {
                                currentHit?.let { h ->
                                    when (h.kind) {
                                        LocateKind.ZONE -> h.zone?.let { vm.excludeFromZone(it.id, t.id) }
                                        LocateKind.SAVED, LocateKind.PLACE -> vm.clearLocation(t.id, h.spot.id)
                                        else -> {}
                                    }
                                    // Lo que dependía de esa zona también se descarta
                                    val rest = hits.orEmpty().filter { it !== h && !(h.kind == LocateKind.ZONE && it.zone?.id == h.zone?.id) }
                                    hits = rest
                                    hitIdx = hitIdx.coerceIn(0, (rest.size - 1).coerceAtLeast(0))
                                }
                            },
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
                    if (zoning) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Arrastra el dedo sobre la foto para encerrar los botes de una misma marca."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { zoning = false }) { Text(tr("Cancelar")) }
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
                                TextButton(onClick = { vm.clearLocation(sel.id, spot?.id); selectedPin = null }) { Text(tr("Quitar")) }
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
                            label = { Text((if (s.kind != "Gaveta") tr(s.kind) + ": " else "") + if (target != null && n > 0) "${s.name} · $n" else s.name) },
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
                        tr("Fotografía donde guardas tus pinturas"),
                        tr("Una foto por cada gaveta, organizador de pared, estante o caja. La app lee las etiquetas que se vean; donde no se ven (botes vistos desde arriba), marcas una zona por marca y te señala el bote por su color."),
                    )
                    return@Column
                }

                if (target == null && !zoning) {
                    val (withPoint, noPoint, byZone) = vm.countsIn(spot.id)
                    Text(
                        tr("{} pinturas aquí: {} con punto, {} sin punto, {} por zona de marca", withPoint + noPoint + byZone, withPoint, noPoint, byZone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                    )
                }
                val photo by produceState<ImageBitmap?>(null, spot.id, vm.spotsVersion) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(vm.spotFile(spot).absolutePath)?.asImageBitmap() }
                }
                val candidatesHere = hits.orEmpty().withIndex().filter { it.value.spot.id == spot.id && it.value.isPoint }
                val activeZone = currentHit?.takeIf { it.spot.id == spot.id }?.zone
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
                        zones = if (target == null) vm.zones.filter { it.spotId == spot.id } else listOfNotNull(activeZone),
                        focusZone = activeZone?.takeIf { currentHit?.kind == LocateKind.ZONE },
                        drawZone = zoning,
                        onZoneDrawn = { r -> zoning = false; zoneRect = r },
                        onTapEmpty = { pos ->
                            val p = placing
                            val other = p?.let { vm.locations[it.id] }
                            if (p != null && other != null && other.spotId != spot.id && (vm.owned[p.id]?.qty ?: 0) > 1) {
                                // Tiene más de un bote y ya estaba en otro lugar: ¿se movió o hay uno en cada sitio?
                                askAlso = Triple(p, spot.id, pos)
                                placing = null
                            } else if (p != null) {
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
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text(tr("Gaveta de arriba")) })
                        Text(tr("Tipo de lugar"), style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SPOT_KINDS.forEach { k -> FilterChip(selected = newKind == k, onClick = { newKind = k }, label = { Text(tr(k)) }) }
                        }
                        if (!vm.isSpotRead(spot)) {
                            Text(tr("Al guardar leo las etiquetas de la foto para ubicar tus pinturas."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val first = !vm.isSpotRead(spot)
                        vm.renameSpot(spot.id, newName, newKind)
                        renaming = false
                        if (first) readLabels(spot, reread = false)
                    }) { Text(tr("Guardar")) }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("Cancelar")) } },
            )
        }
        zoneRect?.let { r ->
            if (spot != null) ZoneDialog(vm, spot, r, onDismiss = { zoneRect = null }) { brand, line ->
                vm.addZone(spot.id, r[0], r[1], r[2], r[3], brand, line)
                zoneRect = null
                say(tr("Zona guardada: {}. Tus pinturas de esa marca sin otro lugar quedan aquí.", if (line.isBlank()) brand else "$brand · $line"))
            }
        }
        if (showZones && spot != null) {
            val zs = vm.zones.filter { it.spotId == spot.id }
            AlertDialog(
                onDismissRequest = { showZones = false },
                title = { Text(tr("Zonas de esta foto")) },
                text = {
                    Column {
                        zs.forEach { z ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(z.label, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.removeZone(z.id) }) { Text(tr("Quitar")) }
                            }
                        }
                        if (zs.isEmpty()) Text(tr("No hay zonas."))
                    }
                },
                confirmButton = { TextButton(onClick = { showZones = false }) { Text(tr("Cerrar")) } },
            )
        }
        if (bulk && spot != null) {
            BulkAssignDialog(vm, spot, onDismiss = { bulk = false }) { ids ->
                vm.assignToSpot(ids, spot.id)
                bulk = false
                say(tr("{} pinturas agregadas a {}", ids.size, spot.name))
            }
        }
        askAlso?.let { (p, sid, pos) ->
            val from = vm.spotOf(p.id)?.name ?: ""
            AlertDialog(
                onDismissRequest = { askAlso = null },
                title = { Text(p.name) },
                text = { Text(tr("Tienes {} botes y ya estaba en {}. ¿La moviste o hay un bote en cada lugar?", vm.owned[p.id]?.qty ?: 2, from)) },
                confirmButton = { TextButton(onClick = { vm.setLocation(p.id, sid, pos.x, pos.y, also = true); askAlso = null; say(tr("Ubicada: {}", p.name)) }) { Text(tr("También está aquí")) } },
                dismissButton = { TextButton(onClick = { vm.setLocation(p.id, sid, pos.x, pos.y); askAlso = null; say(tr("Ubicada: {}", p.name)) }) { Text(tr("La moví")) } },
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
    onNotHere: () -> Unit = {},
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
                                when (h.kind) {
                                    LocateKind.COLOR -> tr(h.kind.label) + (h.zone?.let { tr(" dentro de la zona {}", it.label) } ?: "") + tr(" · {}% probable", (h.confidence * 100).roundToInt())
                                    LocateKind.ZONE -> tr("Zona {}: debería estar dentro del recuadro. Las siguientes opciones señalan botes de su color ahí.", h.zone?.label ?: "")
                                    else -> tr(h.kind.label)
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h != null && h.isPoint && h.kind != LocateKind.SAVED) {
                    Button(onClick = onConfirm) { Text(tr("Es aquí ✓")) }
                }
                if (hasSpots && !searching && (h == null || !h.isPoint)) {
                    Button(onClick = onMarkManually) { Text(tr("Marcar el bote")) }
                }
                if (h != null) OutlinedButton(onClick = onNotHere) { Text(tr("No está aquí")) }
                FilledTonalButton(onClick = onLive) { Text(tr("Cámara")) }
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
    zones: List<SpotZone> = emptyList(),
    focusZone: SpotZone? = null,
    drawZone: Boolean = false,
    onZoneDrawn: (FloatArray) -> Unit = {},
) {
    var zoom by remember(spot.id) { mutableStateOf(1f) }
    var pan by remember(spot.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val pins = if (showPins) (vm.locations.values + vm.moreLocations).filter { it.spotId == spot.id && it.hasPoint } else emptyList()
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(drawZone) { if (drawZone) { zoom = 1f; pan = Offset.Zero }; dragStart = null; dragEnd = null }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.75f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(spot.id) {
                detectTransformGestures { _, p, z, _ ->
                    if (drawZone) return@detectTransformGestures
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
        LaunchedEffect(focus, spot.id, focusZone) {
            if (focus != null) {
                zoom = 2.6f
                pan = Offset(-zoom * (focus.x * w - w / 2), -zoom * (focus.y * h - h / 2))
            } else if (focusZone != null) {
                // Acercar hasta que la zona llene la vista (sin pasarse)
                val zw = (focusZone.x1 - focusZone.x0).coerceAtLeast(0.05f)
                val zh = (focusZone.y1 - focusZone.y0).coerceAtLeast(0.05f)
                zoom = minOf(0.9f / zw, 0.9f / zh).coerceIn(1f, 3f)
                val cx = (focusZone.x0 + focusZone.x1) / 2
                val cy = (focusZone.y0 + focusZone.y1) / 2
                pan = if (zoom == 1f) Offset.Zero else Offset(-zoom * (cx * w - w / 2), -zoom * (cy * h - h / 2))
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
            // Zonas por marca
            zones.forEach { z ->
                val zc = if (z.id == focusZone?.id) MARKER else Color(0xFF4FC3F7)
                Box(
                    Modifier
                        .offset { IntOffset((z.x0 * w).roundToInt(), (z.y0 * h).roundToInt()) }
                        .size(with(density) { ((z.x1 - z.x0) * w).toDp() }, with(density) { ((z.y1 - z.y0) * h).toDp() })
                        .border(2.dp, zc, RoundedCornerShape(6.dp)),
                ) {
                    Text(
                        z.label,
                        color = Color.Black,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.background(zc, RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp)).padding(horizontal = 4.dp),
                    )
                }
            }
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
            if (drawZone) {
                // Capa para dibujar el rectángulo de la zona con el dedo
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragEnd = it },
                            onDrag = { change, _ -> dragEnd = change.position },
                            onDragCancel = { dragStart = null; dragEnd = null },
                            onDragEnd = {
                                val a = dragStart; val b = dragEnd
                                dragStart = null; dragEnd = null
                                if (a != null && b != null && kotlin.math.abs(a.x - b.x) > 24 && kotlin.math.abs(a.y - b.y) > 24) {
                                    onZoneDrawn(
                                        floatArrayOf(
                                            (minOf(a.x, b.x) / size.width).coerceIn(0f, 1f), (minOf(a.y, b.y) / size.height).coerceIn(0f, 1f),
                                            (maxOf(a.x, b.x) / size.width).coerceIn(0f, 1f), (maxOf(a.y, b.y) / size.height).coerceIn(0f, 1f),
                                        ),
                                    )
                                }
                            },
                        )
                    },
                )
                val a = dragStart; val b = dragEnd
                if (a != null && b != null) {
                    Box(
                        Modifier
                            .offset { IntOffset(minOf(a.x, b.x).roundToInt(), minOf(a.y, b.y).roundToInt()) }
                            .size(with(density) { kotlin.math.abs(a.x - b.x).toDp() }, with(density) { kotlin.math.abs(a.y - b.y).toDp() })
                            .background(MARKER.copy(alpha = 0.2f))
                            .border(2.dp, MARKER),
                    )
                }
            }
        }
    }
}

/** Elegir de qué marca (y línea, si quieres) son los botes encerrados en la zona. */
@Composable
private fun ZoneDialog(vm: MainViewModel, spot: StorageSpot, r: FloatArray, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val brands = remember(spot.id, r) { vm.suggestBrands(spot, r[0], r[1], r[2], r[3]) }
    var brand by remember { mutableStateOf(brands.firstOrNull() ?: "") }
    var line by remember(brand) { mutableStateOf("") }
    val lines = remember(brand, vm.owned) {
        vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }.filter { it.brand == brand }.groupingBy { it.line }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("¿Qué hay en esta zona?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (brands.isEmpty()) Text(tr("Primero agrega pinturas a tu inventario."))
                Text(tr("Marca"), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    brands.forEach { b -> FilterChip(selected = brand == b, onClick = { brand = b }, label = { Text(b) }) }
                }
                if (lines.size > 1) {
                    Text(tr("Línea (opcional)"), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = line.isEmpty(), onClick = { line = "" }, label = { Text(tr("Todas")) })
                        lines.forEach { (l, n) -> FilterChip(selected = line == l, onClick = { line = l }, label = { Text("$l · $n") }) }
                    }
                }
                Text(
                    tr("Tus pinturas de esta marca que no tengan otro lugar guardado se darán por guardadas en esta zona, y al buscarlas la app señala dentro del recuadro los botes de su color."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(brand, line) }, enabled = brand.isNotBlank()) { Text(tr("Guardar zona")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Elegir varias pinturas de tu inventario para decir que están en este lugar. */
@Composable
private fun BulkAssignDialog(vm: MainViewModel, spot: StorageSpot, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var q by remember { mutableStateOf("") }
    var onlyUnplaced by remember { mutableStateOf(true) }
    var brand by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    val inv = remember(vm.owned, vm.byId) {
        vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }.sortedWith(compareBy({ it.brand }, { it.line }, { it.name }))
    }
    val words = queryWords(q)
    val shown = remember(q, inv, onlyUnplaced, brand, vm.locations, vm.zones) {
        inv.filter { p ->
            (brand == null || p.brand == brand) && (words.isEmpty() || matchesWords(p.searchText, words)) &&
                (!onlyUnplaced || vm.placeOf(p.id) == null) && vm.allLocations(p.id).none { it.spotId == spot.id }
        }
    }
    val brands = remember(inv) { inv.groupingBy { it.brand }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(tr("Agregar a {}", spot.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Volver")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchField(q, { q = it }, tr("Buscar en tu inventario…"))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = onlyUnplaced, onClick = { onlyUnplaced = !onlyUnplaced }, label = { Text(tr("Solo sin lugar")) })
                        brands.forEach { b -> FilterChip(selected = brand == b, onClick = { brand = if (brand == b) null else b }, label = { Text(b) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("{} en la lista", shown.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { shown.forEach { if (it.id !in chosen) chosen.add(it.id) } }, enabled = shown.isNotEmpty()) { Text(tr("Marcar todas")) }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { p ->
                        val on = p.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) chosen.remove(p.id) else chosen.add(p.id) }.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) chosen.add(p.id) else chosen.remove(p.id) })
                            Swatch(p.hex, size = 26.dp, metallic = p.type == "Metálica")
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(p.brand, p.line, p.code).filter { it.isNotBlank() }.joinToString(" · ") + (vm.placeOf(p.id)?.let { tr(" · ahora en {}", it.text) } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Button(onClick = { onSave(chosen.toList()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(tr("Agregar {} pinturas", chosen.size))
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
    // Ventana propia: así funciona aunque se abra desde dentro de una lista (sin alto definido)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Box(Modifier.fillMaxSize()) {
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
