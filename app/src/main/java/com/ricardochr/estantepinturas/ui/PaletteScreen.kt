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
import com.ricardochr.estantepinturas.PaletteMatch
import com.ricardochr.estantepinturas.ZoneAdvice
import com.ricardochr.estantepinturas.renderHighlight
import com.ricardochr.estantepinturas.similarityLabel
import kotlin.math.roundToInt

private val DETAIL_OPTIONS = listOf(5 to "Pocos colores", 8 to "Normal", 12 to "Detallado")

/**
 * Sube una imagen (de preferencia con fondo liso) y la app sugiere qué pinturas de tu
 * inventario usar para cada color, con una más oscura para sombra y una más clara para luz.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaletteScreen(vm: MainViewModel, onOpenPaint: (Paint) -> Unit) {
    var detail by rememberSaveable { mutableStateOf(vm.palette?.detail ?: 8) }
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
    var selected by remember(view) { mutableStateOf<Int?>(null) }
    val sel = selected
    val highlightImg = remember(view, sel) {
        if (sel == null) null else renderHighlight(view.palette, view.source, sel).asImageBitmap()
    }
    val highlightFill = remember(view, sel) {
        if (sel == null) null else renderHighlight(view.palette, view.source, sel, fill = true).asImageBitmap()
    }
    // Parpadeo entre el color real y el color marcador para que se noten hasta las zonas pequeñas
    var blinkOn by remember { mutableStateOf(false) }
    LaunchedEffect(sel) {
        blinkOn = false
        if (sel != null) while (true) {
            delay(650)
            blinkOn = !blinkOn
        }
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
                        .background(if (sel != null) Color(0xFF15171B) else MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = (if (blinkOn) highlightFill else highlightImg) ?: if (showPreview) previewImg else sourceImg,
                        contentDescription = when {
                            sel != null -> "Dónde va el color seleccionado"
                            showPreview -> "Figura con tus pinturas"
                            else -> "Imagen original"
                        },
                        contentScale = ContentScale.Fit,
                        filterQuality = if (sel != null) FilterQuality.None else FilterQuality.Low,
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
                    val m = view.matches.getOrNull(sel)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Color ${sel + 1}: " + (m?.best?.paint?.name ?: "sin pintura") + " — resaltado en la imagen",
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
                Text(
                    "Toca un color de la lista para ver en la imagen dónde va.",
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
        itemsIndexed(view.matches) { i, m ->
            MatchCard(
                index = i,
                m = m,
                advice = view.advice.zones.getOrNull(i),
                selected = sel == i,
                onSelect = { selected = if (sel == i) null else i },
                onOpen = onOpenPaint,
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

@Composable
private fun MatchCard(
    index: Int,
    m: PaletteMatch,
    advice: ZoneAdvice?,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: (Paint) -> Unit,
) {
    var showTips by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Color ${index + 1}" + (advice?.let { " · ${it.role}" + if (it.group.isNotBlank()) " · ${it.group}" else "" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (selected) "Resaltado" else "Ver dónde va",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ColorChip(m.cluster.argb, 44.dp)
                    Text(
                        "${(m.cluster.fraction * 100).roundToInt().coerceAtLeast(1)}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val best = m.best
                if (best == null) {
                    Text(
                        "Nada parecido en tu inventario",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onOpen(best.paint) },
                    ) {
                        Text(
                            best.paint.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(best.paint.brand, best.paint.code.ifBlank { best.paint.line }).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            similarityLabel(best.distance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Swatch(best.paint.hex, size = 44.dp, metallic = best.paint.type == "Metálica")
                }
            }
            if (m.alternatives.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("También:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.alternatives.forEach { alt -> MiniPaint(alt.paint, onOpen, Modifier.weight(1f)) }
                }
            }
            if (m.shadow != null || m.highlight != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneChip("Sombra", m.shadow, onOpen, Modifier.weight(1f))
                    ToneChip("Luz", m.highlight, onOpen, Modifier.weight(1f))
                }
            }
            if (advice != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTips = !showTips },
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cómo pintarlo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (showTips) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
                        }
                        Text("Pincel: ${advice.brush}", style = MaterialTheme.typography.bodySmall)
                        if (showTips) {
                            advice.tips.forEach { t ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodySmall)
                                    Text(t, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            val buy = m.toBuy
            if (buy != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(buy) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Swatch(buy.hex, size = 24.dp, metallic = false)
                        Text(
                            "Para comprar: ${buy.name} · ${buy.brand}" + if (buy.code.isNotBlank()) " ${buy.code}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(similarityLabel(m.toBuyDistance), style = MaterialTheme.typography.labelSmall)
                    }
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
private fun ToneChip(label: String, p: Paint?, onOpen: (Paint) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.then(if (p != null) Modifier.clickable { onOpen(p) } else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (p != null) Swatch(p.hex, size = 22.dp, metallic = p.type == "Metálica")
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    p?.name ?: "No tienes una adecuada",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
