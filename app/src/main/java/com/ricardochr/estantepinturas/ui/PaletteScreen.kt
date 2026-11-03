package com.ricardochr.estantepinturas.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            Text("Paleta desde una imagen", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(
                "Elige una imagen de referencia (un personaje, una figura pintada, un concept art). " +
                    "Funciona mejor si tiene fondo liso. La app separa el fondo, encuentra los colores principales " +
                    "y te dice qué pinturas de tu inventario usar, con una para sombra y otra para luz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (inventorySize == 0) {
                Text(
                    "Tu inventario está vacío: primero agrega tus pinturas para recibir sugerencias.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            DetailChips(detail) { detail = it }
            Button(onClick = { pick() }, enabled = !vm.paletteBusy, modifier = Modifier.padding(top = 12.dp)) {
                Text("Elegir imagen")
            }
            if (vm.paletteBusy) CircularProgressIndicator(Modifier.padding(16.dp))
            vm.paletteError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        return
    }

    val sourceImg = remember(view.source) { view.source.asImageBitmap() }
    val previewImg = remember(view.preview) { view.preview.asImageBitmap() }
    var selected by remember(view) { mutableStateOf<Highlight?>(null) }
    val sel = selected
    val highlightImg = remember(view, sel) {
        if (sel == null) null else renderHighlight(view.palette, view.source, sel.ids).asImageBitmap()
    }
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
                        .background(if (sel != null) Color(0xFFE6E6E6) else MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = highlightImg ?: if (showPreview) previewImg else sourceImg,
                        contentDescription = when {
                            sel != null -> "Dónde va el color seleccionado"
                            showPreview -> "Figura con tus pinturas"
                            else -> "Imagen original"
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
                            .pointerInput(view) {
                                detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
                            }
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    )
                    if (vm.paletteBusy) CircularProgressIndicator()
                    if (zoom == 1f) {
                        Text(
                            "Pellizca para acercar",
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
                        TextButton(onClick = { selected = null }) { Text("Ver todo") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showPreview, onClick = { showPreview = false }, label = { Text("Original") })
                        FilterChip(selected = showPreview, onClick = { showPreview = true }, label = { Text("Con tus pinturas") })
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!view.palette.backgroundFound) {
                    Text(
                        "No encontré un fondo liso, así que analicé la imagen completa. Los colores del fondo pueden aparecer en la lista.",
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
                                if (view.brightened) "La foto es oscura: aclaré sus colores para elegir pinturas como se verían con buena luz (en la foto las sombras y el barniz oscurecen todo)."
                                else "Estás usando los colores tal cual salen de la foto, que es oscura: las pinturas sugeridas serán más oscuras.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = vm.paletteBrighten, onClick = { vm.changePaletteBrighten(true) }, label = { Text("Corregir luz") })
                                FilterChip(selected = !vm.paletteBrighten, onClick = { vm.changePaletteBrighten(false) }, label = { Text("Colores de la foto") })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick() }, enabled = !vm.paletteBusy) { Text("Otra imagen") }
                    Text(
                        "${view.matches.size} colores · tu inventario: $inventorySize",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                vm.paletteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { askProjectName = true }, enabled = !vm.paletteBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Guardar como proyecto")
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
                    "Cada tarjeta es una zona de la figura (piel, chaqueta, pelo…) con su base, sombra y luz. Toca la zona o uno de sus colores para verlo en la imagen.",
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
                onAddShopping = { b -> vm.addToShopping(b.id, "Para la paleta de una imagen") },
                isInShopping = { b -> vm.shopping.any { it.paintId == b.id } },
                kit = vm.gearKit,
            )
        }
        item {
            Text(
                "Los colores de la foto dependen de la luz con que se tomó. Úsalo como punto de partida y prueba en la paleta antes de pintar.",
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
                Text("Plan de pintado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "Ocultar" else "Ver", style = MaterialTheme.typography.labelLarge)
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
) {
    val mb = matches.getOrNull(f.base) ?: return
    val baseAdvice = zones.getOrNull(f.base)
    var showSteps by remember { mutableStateOf(false) }
    var showMix by remember { mutableStateOf(false) }
    val mixes by produceState<List<MixRecipe>?>(null, showMix, mb) {
        value = if (showMix) mixFor(mb.cluster.lab) else null
    }
    val zoneHighlight = Highlight(f.members.toSet(), "Zona $number · ${f.name}: resaltada en la imagen")
    val zoneSelected = selected?.ids == zoneHighlight.ids
    val members = f.members.sortedBy { ROLE_ORDER.indexOf(f.roles[it] ?: "Base").let { r -> if (r < 0) 0 else r } }
    val steps = remember(f, matches, kit) { familySteps(f, matches, zones, kit) }
    val title = when {
        f.members.size == 1 && baseAdvice?.role == "Detalle" -> "Detalle · ${f.name}"
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
                        "Zona $number · $title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${(fraction * 100).roundToInt().coerceAtLeast(1)}% de la figura · " +
                            if (f.members.size == 1) "un solo tono" else "${f.members.size} tonos: base, sombras y luces",
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
                val h = Highlight(setOf(i), "Zona $number · $role: " + (m.best?.paint?.name ?: "sin pintura") + " — resaltado")
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
                                    "o lavado: ${wash.name} (${wash.type.lowercase()}) sobre la base",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpen(wash) },
                                )
                            }
                            val q = m.quick
                            if (role == "Base" && q != null) {
                                Text(
                                    "Atajo: ${q.name} (contraste) de una capa",
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
                                        "Mejor: ${buy.name} · ${buy.brand}",
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
                            "Toca para ver ${steps.size} pasos: qué pintura va en cada parte, cuánto diluir y dónde aplicarla.",
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
                        p != null && p.type != "Opaca" && p.type != "Aerógrafo" -> " · " + p.type.lowercase() + " sobre la base seca"
                        p != null -> ""
                        mix != null -> " · mezcla"
                        buy != null -> " · para comprar"
                        else -> " · sin pintura"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        p != null -> p.name
                        mix != null -> mix.text
                        buy != null -> buy.name + " · " + buy.brand + if (buy.code.isNotBlank()) " " + buy.code else ""
                        darker -> "Oscurece la base con un lavado oscuro o una gota de su complementario"
                        else -> "Aclara la base agregando blanco o blanco hueso poco a poco"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p == null && mix == null && buy != null) {
                if (isInShopping(buy)) Text("✓", style = MaterialTheme.typography.labelLarge)
                else TextButton(onClick = { onAddShopping(buy) }) { Text("+ Lista") }
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
                label = { Text("Nombre") },
                placeholder = { Text("Motoko, Space Marine, busto…") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
