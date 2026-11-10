package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.Lab
import com.ricardochr.estantepinturas.MixRecipe
import com.ricardochr.estantepinturas.PaletteMatch
import com.ricardochr.estantepinturas.familySteps
import com.ricardochr.estantepinturas.PaletteFamily
import com.ricardochr.estantepinturas.ToneMix
import com.ricardochr.estantepinturas.Project
import com.ricardochr.estantepinturas.ZoneAdvice
import com.ricardochr.estantepinturas.renderHighlight
import com.ricardochr.estantepinturas.similarityLabel
import kotlin.math.roundToInt

private val DETAIL_OPTIONS = listOf(6 to "Pocos colores", 10 to "Normal", 14 to "Detallado")

/**
 * Sube una imagen (de preferencia con fondo liso) y la app sugiere qué pinturas de tu
 * inventario usar para cada color, con una más oscura para sombra y una más clara para luz.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaletteScreen(vm: MainViewModel, onOpenPaint: (Paint) -> Unit, onProjectCreated: (Project) -> Unit = {}) {
    var askProjectName by remember { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf(vm.palette?.detail ?: 10) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            showPreview = false
            vm.analyzePalette(uri, detail)
        }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // Si agregas o quitas pinturas, se actualizan las sugerencias
    var firstOwned by remember { mutableStateOf(true) }
    LaunchedEffect(vm.owned) {
        if (firstOwned) firstOwned = false else vm.refreshPaletteMatches()
    }

    val view = vm.palette
    val inventorySize = vm.owned.values.count { it.qty > 0 }

    if (view == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(tr("Paleta desde una imagen"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(
                tr("Elige una imagen de referencia (un personaje, una figura pintada, un concept art). Funciona mejor si tiene fondo liso. La app separa el fondo, encuentra los colores principales y te dice qué pinturas de tu inventario usar, con una para sombra y otra para luz."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (inventorySize == 0) {
                Text(
                    tr("Tu inventario está vacío: primero agrega tus pinturas para recibir sugerencias."),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            DetailChips(detail) { detail = it }
            Button(onClick = { pick() }, enabled = !vm.paletteBusy, modifier = Modifier.padding(top = 12.dp)) {
                Text(tr("Elegir imagen"))
            }
            if (vm.paletteBusy) CircularProgressIndicator(Modifier.padding(16.dp))
            vm.paletteError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        return
    }

    val sourceImg = remember(view.source) { view.source.asImageBitmap() }
    val previewImg = remember(view.preview) { view.preview.asImageBitmap() }
    val basePreviewImg = remember(view.basePreview) { view.basePreview?.asImageBitmap() }
    var showBase by remember(view.basePreview == null) { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<PaletteFamily?>(null) }
    var selected by remember(view) { mutableStateOf<Highlight?>(null) }
    val sel = selected
    val highlightImg = remember(view, sel) {
        if (sel == null) null else renderHighlight(view.palette, view.source, sel.ids).asImageBitmap()
    }
    var picked by remember(view) { mutableStateOf<MainViewModel.PointPick?>(null) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var zoom by remember(view) { mutableStateOf(1f) }
    var pan by remember(view) { mutableStateOf(Offset.Zero) }
    var showPlan by rememberSaveable { mutableStateOf(true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // La imagen queda fija arriba para ver dónde va cada color mientras recorres la lista
        stickyHeader {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel != null) Color(0xFFE6E6E6) else MaterialTheme.colorScheme.surfaceContainer)
                        .onSizeChanged { boxSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    // Geometría de la imagen dentro del recuadro (ajustada, centrada, con zoom y desplazamiento)
                    val iw = view.palette.width.toFloat()
                    val ih = view.palette.height.toFloat()
                    val bw = boxSize.width.toFloat()
                    val bh = boxSize.height.toFloat()
                    val fit = if (iw > 0 && ih > 0 && bw > 0 && bh > 0) minOf(bw / iw, bh / ih) else 1f
                    val dw = iw * fit
                    val dh = ih * fit
                    val ox = (bw - dw) / 2f
                    val oy = (bh - dh) / 2f
                    val center = Offset(bw / 2f, bh / 2f)
                    Image(
                        bitmap = highlightImg ?: if (showPreview) (if (showBase && basePreviewImg != null) basePreviewImg else previewImg) else sourceImg,
                        contentDescription = when {
                            sel != null -> tr("Dónde va el color seleccionado")
                            showPreview -> tr("Figura con tus pinturas")
                            else -> tr("Imagen original")
                        },
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(view) {
                                detectTransformGestures { _, p, z, _ ->
                                    zoom = (zoom * z).coerceIn(1f, 6f)
                                    pan = if (zoom == 1f) Offset.Zero else pan + p
                                }
                            }
                            .pointerInput(view, boxSize) {
                                detectTapGestures(
                                    onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                                    onTap = { pos ->
                                        val local = center + (pos - center - pan) / zoom
                                        val nx = (local.x - ox) / dw
                                        val ny = (local.y - oy) / dh
                                        if (dw > 0 && dh > 0 && nx in 0f..1f && ny in 0f..1f) picked = vm.pickPoint(nx, ny)
                                    },
                                )
                            }
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    )
                    picked?.let { pk ->
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val local = Offset(ox + pk.x * dw, oy + pk.y * dh)
                            val pt = center + pan + (local - center) * zoom
                            drawCircle(Color.Black, 9.dp.toPx(), pt, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                            drawCircle(Color.White, 9.dp.toPx(), pt, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f.dp.toPx()))
                        }
                    }
                    if (vm.paletteBusy) CircularProgressIndicator()
                    if (zoom == 1f && picked == null) {
                        Text(
                            tr("Toca un punto para ver su color · pellizca para acercar"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                picked?.let { pk ->
                    val zoneIdx = view.advice.families.indexOfFirst { pk.label in it.members }
                    val fam = view.advice.families.getOrNull(zoneIdx)
                    PickCard(
                        pk = pk,
                        corrected = view.brightened,
                        zoneText = fam?.let { tr("Zona {} · {} ({})", zoneIdx + 1, it.name, (it.roles[pk.label] ?: "Base").lowercase()) },
                        onShowZone = fam?.let { f -> { selected = Highlight(f.members.toSet(), tr("Zona {} · {}: resaltada en la imagen", zoneIdx + 1, f.name)) } },
                        onOpen = onOpenPaint,
                        onAddShopping = { b -> vm.addToShopping(b.id, tr("Color de un punto de la foto")) },
                        inShopping = { b -> vm.shopping.any { it.paintId == b.id } },
                        onClose = { picked = null },
                    )
                }
                if (sel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            sel.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { selected = null }) { Text(tr("Ver todo")) }
                    }
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showPreview, onClick = { showPreview = false }, label = { Text(tr("Original")) })
                        if (basePreviewImg == null) {
                            FilterChip(selected = showPreview, onClick = { showPreview = true }, label = { Text(tr("Con tus pinturas")) })
                        } else {
                            // Con variantes de color: se compara el plan original contra la variante
                            FilterChip(selected = showPreview && showBase, onClick = { showPreview = true; showBase = true }, label = { Text(tr("Plan original")) })
                            FilterChip(selected = showPreview && !showBase, onClick = { showPreview = true; showBase = false }, label = { Text(tr("Tu variante")) })
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!view.palette.backgroundFound) {
                    Text(
                        tr("No encontré un fondo liso, así que analicé la imagen completa. Los colores del fondo pueden aparecer en la lista."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DetailChips(detail) {
                    detail = it
                    vm.analyzePalette(view.uri, it)
                }
                if (view.darkPhoto) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (view.brightened) tr("La foto es oscura: aclaré sus colores para elegir pinturas como se verían con buena luz (en la foto las sombras y el barniz oscurecen todo).")
                                else tr("Estás usando los colores tal cual salen de la foto, que es oscura: las pinturas sugeridas serán más oscuras."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = vm.paletteBrighten, onClick = { vm.changePaletteBrighten(true) }, label = { Text(tr("Corregir luz")) })
                                FilterChip(selected = !vm.paletteBrighten, onClick = { vm.changePaletteBrighten(false) }, label = { Text(tr("Colores de la foto")) })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick() }, enabled = !vm.paletteBusy) { Text(tr("Otra imagen")) }
                    Text(
                        tr("{} colores · tu inventario: {}", view.matches.size, inventorySize),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                vm.paletteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (view.recolored.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("Estás probando una variante de color: las pinturas y los pasos ya son los de la variante."),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.clearRecolors() }) { Text(tr("Quitar")) }
                        }
                    }
                }
                Button(onClick = { askProjectName = true }, enabled = !vm.paletteBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Guardar como proyecto"))
                }
                if (askProjectName) {
                    ProjectNameDialog(
                        onDismiss = { askProjectName = false },
                        onSave = { name ->
                            askProjectName = false
                            vm.projectFromPalette(name, onProjectCreated)
                        },
                    )
                }
                Text(
                    tr("Cada tarjeta es una zona de la figura (piel, chaqueta, pelo…) con su base, sombra y luz. Toca la zona o uno de sus colores para verlo en la imagen."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (view.advice.plan.isNotEmpty()) {
            item {
                PlanCard(view.advice.plan, showPlan) { showPlan = !showPlan }
            }
        }
        val families = view.advice.families.ifEmpty {
            view.matches.indices.map { PaletteFamily("color", listOf(it), it, mapOf(it to "Base")) }
        }
        itemsIndexed(families) { zi, f ->
            ZoneCard(
                number = zi + 1,
                f = f,
                matches = view.matches,
                zones = view.advice.zones,
                fraction = f.members.sumOf { view.matches.getOrNull(it)?.cluster?.fraction ?: 0.0 },
                selected = sel,
                onSelect = { h -> selected = if (sel == h) null else h },
                onOpen = onOpenPaint,
                mixFor = { lab -> vm.mixesFor(lab) },
                onAddShopping = { b -> vm.addToShopping(b.id, tr("Para la paleta de una imagen")) },
                isInShopping = { b -> vm.shopping.any { it.paintId == b.id } },
                kit = vm.gearKit,
                recolored = f.members.any { it in view.recolored },
                onRecolor = { lab -> vm.recolorZone(f.members, f.base, lab); showPreview = true; showBase = false; selected = null },
                onPickPaint = { pickFor = f },
                onResetColor = { vm.resetZoneColor(f.members) },
            )
        }
        item {
            pickFor?.let { f ->
                PaintPickerDialog(vm, tr("Pintar esta zona con…"), onDismiss = { pickFor = null }, fromCatalog = true) { p ->
                    pickFor = null
                    if (p.hasColor) {
                        vm.recolorZone(f.members, f.base, p.lab)
                        showPreview = true; showBase = false; selected = null
                    }
                }
            }
        }
        item {
            Text(
                tr("Los colores de la foto dependen de la luz con que se tomó. Úsalo como punto de partida y prueba en la paleta antes de pintar."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DetailChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DETAIL_OPTIONS.forEach { (k, label) ->
            FilterChip(selected = selected == k, onClick = { if (selected != k) onSelect(k) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ColorChip(argb: Int, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
    )
}

@Composable
private fun PlanCard(plan: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Plan de pintado"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) tr("Ocultar") else tr("Ver"), style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                plan.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Lo que está resaltado en la imagen: uno o varios colores y el texto que lo describe. */
data class Highlight(val ids: Set<Int>, val label: String)

private val ROLE_ORDER = listOf("Base", "Sombra", "Sombra profunda", "Luz", "Punto de luz")

/**
 * Una zona de la figura: su color base, sus sombras y luces (los colores de la imagen que son esa
 * misma zona con otra iluminación) y el paso a paso para pintarla.
 */
@Composable
private fun ZoneCard(
    number: Int,
    f: PaletteFamily,
    matches: List<PaletteMatch>,
    zones: List<ZoneAdvice>,
    fraction: Double,
    selected: Highlight?,
    onSelect: (Highlight) -> Unit,
    onOpen: (Paint) -> Unit,
    mixFor: suspend (Lab) -> List<MixRecipe>,
    onAddShopping: (Paint) -> Unit,
    isInShopping: (Paint) -> Boolean,
    kit: com.ricardochr.estantepinturas.GearKit,
    recolored: Boolean = false,
    onRecolor: (Lab) -> Unit = {},
    onPickPaint: () -> Unit = {},
    onResetColor: () -> Unit = {},
) {
    val mb = matches.getOrNull(f.base) ?: return
    var showVariants by remember { mutableStateOf(false) }
    val baseAdvice = zones.getOrNull(f.base)
    var showSteps by remember { mutableStateOf(false) }
    var showMix by remember { mutableStateOf(false) }
    val mixes by produceState<List<MixRecipe>?>(null, showMix, mb) {
        value = if (showMix) mixFor(mb.cluster.lab) else null
    }
    val zoneHighlight = Highlight(f.members.toSet(), tr("Zona {} · {}: resaltada en la imagen", number, f.name))
    val zoneSelected = selected?.ids == zoneHighlight.ids
    val members = f.members.sortedBy { ROLE_ORDER.indexOf(f.roles[it] ?: "Base").let { r -> if (r < 0) 0 else r } }
    val steps = remember(f, matches, kit) { familySteps(f, matches, zones, kit) }
    val title = when {
        f.members.size == 1 && baseAdvice?.role == "Detalle" -> tr("Detalle · {}", f.name)
        else -> f.name.replaceFirstChar { it.uppercase() }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        border = if (selected != null && selected.ids.any { it in f.members }) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(zoneHighlight) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Zona {} · {}", number, title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        tr("{}% de la figura · ", (fraction * 100).roundToInt().coerceAtLeast(1)) +
                            if (f.members.size == 1) "un solo tono" else tr("{} tonos: base, sombras y luces", f.members.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (zoneSelected) "Resaltada" else "Ver zona",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Un renglón por cada tono de la zona
            members.forEach { i ->
                val m = matches.getOrNull(i) ?: return@forEach
                val role = if (f.members.size == 1) "Base" else (f.roles[i] ?: "Base")
                val h = Highlight(setOf(i), tr("Zona {} · {}: ", number, role) + (m.best?.paint?.name ?: "sin pintura") + tr(" — resaltado"))
                val rowSelected = selected?.ids == h.ids
                Surface(
                    color = if (rowSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(h) },
                ) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ColorChip(m.cluster.argb, 34.dp)
                            Text(
                                "${(m.cluster.fraction * 100).roundToInt().coerceAtLeast(1)}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(role.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            val best = m.best
                            if (best == null) {
                                val rowMix = if (f.members.size > 1 && role != "Base") (m.shadowMix ?: m.highlightMix) else null
                                if (rowMix != null) {
                                    Text("Mezcla", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(rowMix.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(
                                        if (f.members.size > 1 && role != "Base") "No tienes una distinta de la base para este tono" else "Nada parecido en tu inventario",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    best.paint.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { onOpen(best.paint) },
                                )
                                Text(
                                    listOf(best.paint.brand, best.paint.code.ifBlank { best.paint.line }, best.paint.type.lowercase()).filter { it.isNotBlank() }.joinToString(" · ") +
                                        " · " + similarityLabel(best.distance).lowercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Lavado para las sombras de la zona y contraste como atajo para la base
                            val wash = mb.washShadow
                            if (role.startsWith("Sombra") && wash != null && wash.id != m.best?.paint?.id) {
                                Text(
                                    tr("o lavado: {} ({}) sobre la base", wash.name, wash.type.lowercase()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpen(wash) },
                                )
                            }
                            val q = m.quick
                            if (role == "Base" && q != null) {
                                Text(
                                    tr("Atajo: {} (contraste) de una capa", q.name),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpen(q) },
                                )
                            }
                            val buy = m.toBuy
                            if (buy != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Swatch(buy.hex, size = 16.dp, metallic = false)
                                    Text(
                                        tr("Mejor: {} · {}", buy.name, buy.brand),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).clickable { onOpen(buy) },
                                    )
                                    if (isInShopping(buy)) Text("✓ lista", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    else Text(
                                        "+ Lista",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onAddShopping(buy) }.padding(4.dp),
                                    )
                                }
                            }
                        }
                        val bestPaint = m.best?.paint
                        val mixShown = if (f.members.size > 1 && role != "Base") (m.shadowMix ?: m.highlightMix) else null
                        if (bestPaint != null) Swatch(bestPaint.hex, size = 34.dp, metallic = bestPaint.type == "Metálica")
                        else if (mixShown != null) ColorChip(mixShown.argb, 34.dp)
                    }
                }
            }

            // Si la imagen no trae sombra o luz para esta zona, se sugiere cómo lograrla desde la base
            if (mb.best != null && f.shadows.isEmpty()) {
                ToneChip("Sombra", mb.shadow ?: mb.washShadow, if (mb.shadow == null && mb.washShadow != null) null else mb.shadowMix, mb.shadowBuy, onOpen, onAddShopping, isInShopping)
            }
            if (mb.best != null && f.lights.isEmpty()) {
                ToneChip("Luz", mb.highlight, mb.highlightMix, mb.highlightBuy, onOpen, onAddShopping, isInShopping)
            }
            if (mb.alternatives.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Otra base:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    mb.alternatives.forEach { alt -> MiniPaint(alt.paint, onOpen, Modifier.weight(1f)) }
                }
            }

            // Variantes: probar otro color en esta zona y ver la figura con él
            Surface(
                color = if (recolored) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { showVariants = !showVariants }, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (recolored) "Probar otro color · variante activa" else "Probar otro color",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Text(if (showVariants) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
                    }
                    if (showVariants) {
                        val base = mb.cluster.lab
                        Text(
                            "Elige una variante y mira la figura arriba: se cambian la base, las sombras y las luces de esta zona, con las pinturas y los pasos que le corresponden.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            colorVariants(base).forEach { (label, lab) ->
                                Column(
                                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onRecolor(lab) }.padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    ColorChip(com.ricardochr.estantepinturas.labToArgb(lab.l, lab.a, lab.b), 38.dp)
                                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPickPaint) { Text("Elegir una pintura…") }
                            if (recolored) TextButton(onClick = onResetColor) { Text("Volver al original") }
                        }
                    }
                }
            }

            // Paso a paso
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSteps = !showSteps },
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Paso a paso", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (showSteps) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
                    }
                    if (showSteps) {
                        steps.forEachIndexed { k, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${k + 1}.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(step, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val tips = baseAdvice?.tips.orEmpty()
                        if (tips.isNotEmpty()) {
                            Text("Consejos", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            tips.forEach { t ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodySmall)
                                    Text(t, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        Text(
                            tr("Toca para ver {} pasos: qué pintura va en cada parte, cuánto diluir y dónde aplicarla.", steps.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Mezcla: útil cuando nada del inventario se parece lo suficiente a la base
            if (mb.best == null || mb.best.distance > 6) {
                if (!showMix) {
                    TextButton(onClick = { showMix = true }) { Text("Cómo lograr la base mezclando lo que tienes") }
                } else {
                    MixSection(mb.cluster.argb, mixes, onOpen, title = "Mezcla para la base")
                }
            }
        }
    }
}

@Composable
private fun MiniPaint(p: Paint, onOpen: (Paint) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clickable { onOpen(p) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Swatch(p.hex, size = 18.dp, metallic = p.type == "Metálica")
        Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToneChip(
    label: String,
    p: Paint?,
    mix: ToneMix?,
    buy: Paint?,
    onOpen: (Paint) -> Unit,
    onAddShopping: (Paint) -> Unit,
    isInShopping: (Paint) -> Boolean,
) {
    val darker = label == "Sombra"
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    p != null -> Modifier.clickable { onOpen(p) }
                    buy != null -> Modifier.clickable { onOpen(buy) }
                    else -> Modifier
                }
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                p != null -> Swatch(p.hex, size = 26.dp, metallic = p.type == "Metálica")
                mix != null -> ColorChip(mix.argb, 26.dp)
                buy != null -> Swatch(buy.hex, size = 26.dp, metallic = false)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label + when {
                        p != null && p.type != "Opaca" && p.type != "Aerógrafo" -> " · " + p.type.lowercase() + tr(" sobre la base seca")
                        p != null -> ""
                        mix != null -> tr(" · mezcla")
                        buy != null -> tr(" · para comprar")
                        else -> tr(" · sin pintura")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        p != null -> p.name
                        mix != null -> mix.text
                        buy != null -> buy.name + " · " + buy.brand + if (buy.code.isNotBlank()) " " + buy.code else ""
                        darker -> tr("Oscurece la base con un lavado oscuro o una gota de su complementario")
                        else -> tr("Aclara la base agregando blanco o blanco hueso poco a poco")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p == null && mix == null && buy != null) {
                if (isInShopping(buy)) Text("✓", style = MaterialTheme.typography.labelLarge)
                else TextButton(onClick = { onAddShopping(buy) }) { Text(tr("+ Lista")) }
            }
        }
    }
}

/** Pide el nombre de un proyecto nuevo. */
@Composable
fun ProjectNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit, initial: String = "", title: String = "Nuevo proyecto") {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(tr("Nombre")) },
                placeholder = { Text(tr("Motoko, Space Marine, busto…")) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(tr("Guardar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Color del punto que tocaste en la foto y tus pinturas más parecidas. */
@Composable
private fun PickCard(
    pk: MainViewModel.PointPick,
    corrected: Boolean,
    zoneText: String?,
    onShowZone: (() -> Unit)?,
    onOpen: (Paint) -> Unit,
    onAddShopping: (Paint) -> Unit,
    inShopping: (Paint) -> Boolean,
    onClose: () -> Unit,
) {
    val shown = com.ricardochr.estantepinturas.labToArgb(pk.lab.l, pk.lab.a, pk.lab.b)
    val name = com.ricardochr.estantepinturas.colorName(com.ricardochr.estantepinturas.ColorCluster(pk.lab.l, pk.lab.a, pk.lab.b, 0.0))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorChip(pk.photoArgb, 30.dp)
                if (corrected) ColorChip(shown, 30.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Punto elegido: {}", name) + String.format(" · #%06X", shown and 0xFFFFFF),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(if (corrected) tr("foto → con luz corregida") else null, zoneText).joinToString(" · ").ifBlank { tr("Fondo o zona sin analizar") },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onShowZone != null) TextButton(onClick = onShowZone) { Text(tr("Zona")) }
                androidx.compose.material3.IconButton(onClick = onClose) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = tr("Cerrar")) }
            }
            val best = pk.matches.firstOrNull()
            if (best != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(best.paint) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Swatch(best.paint.hex, size = 28.dp, metallic = best.paint.type == "Metálica")
                    Column(Modifier.weight(1f)) {
                        Text(best.paint.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(best.paint.brand, best.paint.code, best.paint.type.lowercase(), similarityLabel(best.distance).lowercase()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pk.matches.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("También:"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        pk.matches.drop(1).take(3).forEach { m -> MiniPaint(m.paint, onOpen, Modifier.weight(1f)) }
                    }
                }
            } else {
                Text(tr("No tienes pinturas parecidas."), style = MaterialTheme.typography.bodySmall)
            }
            val buy = pk.toBuy
            if (buy != null && pk.toBuyDistance < (best?.distance ?: 99.0) - 3) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Swatch(buy.hex, size = 18.dp, metallic = false)
                    Text(
                        tr("Más parecida para comprar: {} · {}", buy.name, buy.brand),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f).clickable { onOpen(buy) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (inShopping(buy)) Text("✓", style = MaterialTheme.typography.labelMedium)
                    else Text(
                        tr("+ Lista"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onAddShopping(buy) }.padding(4.dp),
                    )
                }
            }
        }
    }
}

/** Variantes de un color para probar en una zona: mismo valor con otro tono, o el mismo tono más claro, oscuro, vivo o apagado. */
private fun colorVariants(base: Lab): List<Pair<String, Lab>> {
    val c = kotlin.math.sqrt(base.a * base.a + base.b * base.b)
    val h = kotlin.math.atan2(base.b, base.a)
    fun lch(l: Double, chroma: Double, hueRad: Double) = Lab(l.coerceIn(5.0, 95.0), chroma * kotlin.math.cos(hueRad), chroma * kotlin.math.sin(hueRad))
    // Un color casi gris no tiene tono que girar: se le da saturación para que la variante se note
    val cc = c.coerceAtLeast(28.0)
    fun turn(deg: Double) = lch(base.l, cc, h + Math.toRadians(deg))
    return listOf(
        tr("Análogo +") to turn(35.0),
        tr("Análogo −") to turn(-35.0),
        tr("Tríada +") to turn(120.0),
        tr("Tríada −") to turn(-120.0),
        tr("Complementario") to turn(180.0),
        tr("Más claro") to lch(base.l + 14, c, h),
        tr("Más oscuro") to lch(base.l - 14, c, h),
        tr("Más vivo") to lch(base.l, (c * 1.45).coerceAtLeast(c + 12), h),
        tr("Más apagado") to lch(base.l, c * 0.5, h),
    )
}
