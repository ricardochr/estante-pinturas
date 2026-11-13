@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ricardochr.estantepinturas.OwnedEntry
import com.ricardochr.estantepinturas.Project
import com.ricardochr.estantepinturas.MixRecipe
import com.ricardochr.estantepinturas.PAINT_TYPES
import com.ricardochr.estantepinturas.SimilarPaint
import com.ricardochr.estantepinturas.UNKNOWN_HEX
import com.ricardochr.estantepinturas.similarityLabel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.validHex

/** Ícono de visor para la pestaña Escanear (no viene en material-icons-core). */
val ScanIcon: ImageVector by lazy {
    ImageVector.Builder("scan", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 8f); lineTo(4f, 5f); lineTo(7f, 5f)
            moveTo(17f, 5f); lineTo(20f, 5f); lineTo(20f, 8f)
            moveTo(20f, 16f); lineTo(20f, 19f); lineTo(17f, 19f)
            moveTo(7f, 19f); lineTo(4f, 19f); lineTo(4f, 16f)
            moveTo(8f, 12f); lineTo(16f, 12f)
        }
    }.build()
}

/** Ícono de dos capas superpuestas para Superponer referencia. */
val CompareIcon: ImageVector by lazy {
    ImageVector.Builder("compare", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 4f); lineTo(15f, 4f); lineTo(15f, 15f); lineTo(3f, 15f); close()
        }
        path(fill = SolidColor(Color.Black), fillAlpha = 0.35f, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(9f, 9f); lineTo(21f, 9f); lineTo(21f, 20f); lineTo(9f, 20f); close()
        }
    }.build()
}

/** Ícono de libro abierto para la pestaña Guía. */
val GuideIcon: ImageVector by lazy {
    ImageVector.Builder("guide", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 6.5f)
            curveTo(10f, 5f, 7f, 4.5f, 3f, 5f)
            lineTo(3f, 18.5f)
            curveTo(7f, 18f, 10f, 18.5f, 12f, 20f)
            curveTo(14f, 18.5f, 17f, 18f, 21f, 18.5f)
            lineTo(21f, 5f)
            curveTo(17f, 4.5f, 14f, 5f, 12f, 6.5f)
            close()
            moveTo(12f, 6.5f)
            lineTo(12f, 20f)
        }
    }.build()
}

/** Ícono de paleta para la pestaña Paleta. */
val PaletteIcon: ImageVector by lazy {
    ImageVector.Builder("palette", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, false, false, 12f, 21f)
            curveTo(13.5f, 21f, 14f, 20f, 13.5f, 18.8f)
            curveTo(13f, 17.5f, 13.8f, 16f, 15.5f, 16f)
            lineTo(17f, 16f)
            curveTo(19.2f, 16f, 21f, 14.2f, 21f, 12f)
            arcTo(9f, 9f, 0f, false, false, 12f, 3f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.5f, 11f); arcTo(1.5f, 1.5f, 0f, true, true, 7.5f, 11.01f); close()
            moveTo(10f, 7f); arcTo(1.5f, 1.5f, 0f, true, true, 10f, 7.01f); close()
            moveTo(15f, 7f); arcTo(1.5f, 1.5f, 0f, true, true, 15f, 7.01f); close()
        }
    }.build()
}

@Composable
fun Swatch(hex: String, size: Dp = 40.dp, metallic: Boolean = false) {
    val brush = remember(hex, metallic) {
        val base = Color(parseHex(hex))
        if (metallic) {
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f).compositeOver(base), base, Color.Black.copy(alpha = 0.3f).compositeOver(base)))
        } else {
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f).compositeOver(base), base, Color.Black.copy(alpha = 0.15f).compositeOver(base)))
        }
    }
    if (size < 34.dp) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(brush)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        return
    }
    // Tamaño grande: un bote de pintura (tapa oscura y cuerpo con el color, con brillo)
    val outline = MaterialTheme.colorScheme.outline
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bodyTop = h * 0.26f
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.2f, w * 0.2f)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF4A4D57), Color(0xFF1E2027))),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.14f, 0f),
            size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.34f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f, w * 0.1f),
        )
        drawRoundRect(
            brush,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, bodyTop),
            size = androidx.compose.ui.geometry.Size(w * 0.88f, h - bodyTop),
            cornerRadius = r,
        )
        drawRoundRect(
            outline,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, bodyTop),
            size = androidx.compose.ui.geometry.Size(w * 0.88f, h - bodyTop),
            cornerRadius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )
        // Brillo vertical a la izquierda
        drawRoundRect(
            Color.White.copy(alpha = if (metallic) 0.55f else 0.32f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, bodyTop + h * 0.1f),
            size = androidx.compose.ui.geometry.Size(w * 0.1f, (h - bodyTop) * 0.55f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f),
        )
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + bg.red * (1 - a),
        green = this.green * a + bg.green * (1 - a),
        blue = this.blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = tr("Borrar búsqueda")) }
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
fun BrandChips(brands: List<Pair<String, Int>>, selected: String?, mine: Set<String> = emptySet(), onSelect: (String?) -> Unit) {
    if (brands.size < 2) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(tr("Todas")) },
            )
        }
        items(brands, key = { it.first }) { (b, n) ->
            FilterChip(
                selected = selected == b,
                onClick = { onSelect(if (selected == b) null else b) },
                label = { Text((if (b in mine) "★ " else "") + "$b  $n") },
            )
        }
    }
}

@Composable
fun CodeTag(code: String) {
    if (code.isBlank()) return
    Text(
        code,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Fila de una pintura: muestra, nombre, código, marca/línea/tipo y lo que se ponga a la derecha. */
@Composable
fun PaintRow(
    paint: Paint,
    extra: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val compact = com.ricardochr.estantepinturas.Prefs.compactRows
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 5.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = if (compact) 7.dp else 12.dp, bottom = if (compact) 7.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Swatch(paint.hex, size = if (compact) 34.dp else 44.dp, metallic = paint.type == "Metálica")
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    paint.name,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CodeTag(paint.code)
            }
            Text(
                paint.subtitle + (if (extra.isNullOrBlank()) "" else " — $extra"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
fun QtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onMinus),
            contentAlignment = Alignment.Center,
        ) { Text("−", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface) }
        Text(
            qty.toString(),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(diagonal(Pigment.Cobalt)).clickable(onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

/** Hoja para editar una pintura del inventario: botes, notas, eliminar. */
@Composable
fun EntrySheet(
    paint: Paint,
    entry: OwnedEntry?,
    onDismiss: () -> Unit,
    onSave: (qty: Int, notes: String) -> Unit,
    onDelete: () -> Unit,
    similar: List<SimilarPaint> = emptyList(),
    onOpenSimilar: (Paint) -> Unit = {},
    mixes: List<MixRecipe>? = null,
    locationName: String? = null,
    onShowLocation: () -> Unit = {},
    onPlaceLocation: () -> Unit = {},
    onFixColor: (String) -> Unit = {},
    inventory: List<Paint> = emptyList(),
    onOpenWheel: () -> Unit = {},
    inShopping: Boolean = false,
    onToggleShopping: () -> Unit = {},
    onSetLow: (Boolean) -> Unit = {},
    projects: List<Project> = emptyList(),
    onAddToProject: (String) -> Unit = {},
    equivalents: List<com.ricardochr.estantepinturas.MainViewModel.Equivalent>? = null,
    onSaveThin: (String) -> Unit = {},
) {
    var projectMenu by remember { mutableStateOf(false) }
    var thin by remember { mutableStateOf(entry?.thin ?: "") }
    var fixingColor by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var qty by remember { mutableIntStateOf(entry?.qty ?: 1) }
    var notes by remember { mutableStateOf(entry?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Swatch(paint.hex, size = 56.dp, metallic = paint.type == "Metálica")
                Column(Modifier.weight(1f)) {
                    Text(paint.name, style = MaterialTheme.typography.titleLarge)
                    Text(paint.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (paint.code.isNotBlank()) {
                        Spacer(Modifier.height(4.dp)); CodeTag(paint.code)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (paint.quality) {
                        "f" -> "Color aproximado, tomado de la foto del producto."
                        "n" -> "Color aproximado según el nombre: no hay una muestra confiable."
                        "u" -> "Color corregido por ti."
                        "d" -> "Color por confirmar: las fuentes no coinciden en este color. Compáralo con el bote y corrígelo si hace falta."
                        else -> "Color de muestra medida."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (paint.quality == "f" || paint.quality == "n" || paint.quality == "d") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { fixingColor = true }) { Text("Corregir color") }
            }
            run {
                val sp = com.ricardochr.estantepinturas.specialOf(paint)
                Text(
                    tr("Acabado: {}", com.ricardochr.estantepinturas.finishText(paint)) +
                        (sp?.let { tr(" · Pintura {}", tr(it.label)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fixingColor) {
                ColorFixDialog(
                    paint.hex,
                    onDismiss = { fixingColor = false },
                    onSave = { fixingColor = false; onFixColor(it) },
                    onMeasure = { fixingColor = false; measuring = true },
                    equivalents = equivalents.orEmpty()
                        .filter { it.paint.quality == "" && it.paint.hasColor }
                        .map { "${it.paint.name} (${it.paint.brand})" to it.paint.hex },
                )
            }
            if (measuring) {
                MeasureColorDialog(
                    target = paint,
                    owned = inventory,
                    onDismiss = { measuring = false },
                    onSave = { measuring = false; onFixColor(it) },
                )
            }
            if (entry != null && entry.qty > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Dónde está", style = MaterialTheme.typography.titleSmall)
                            Text(
                                locationName?.let { tr("En {}", it) } ?: "Te ayudo a encontrarla por su etiqueta y su color",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onShowLocation) { Text("Buscar") }
                        TextButton(onClick = onPlaceLocation) { Text(if (locationName != null) "Mover" else "Marcar") }
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    if (entry != null && entry.qty > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Se está acabando", style = MaterialTheme.typography.titleSmall)
                                Text("Aparece en tu lista de compras", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = entry.low, onCheckedChange = onSetLow)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lista de compras", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (inShopping) "Ya está en tu lista" else "No la tienes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = onToggleShopping) { Text(if (inShopping) "Quitar" else "Agregar") }
                        }
                    }
                    if (projects.isNotEmpty()) {
                        val inProjects = projects.filter { pr -> pr.paints.any { it.paintId == paint.id } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Proyectos", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (inProjects.isEmpty()) "No la usas en ningún proyecto" else "En: " + inProjects.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box {
                                TextButton(onClick = { projectMenu = true }) { Text("Agregar a…") }
                                DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                                    projects.filter { pr -> inProjects.none { it.id == pr.id } }.forEach { pr ->
                                        DropdownMenuItem(text = { Text(pr.name) }, onClick = { projectMenu = false; onAddToProject(pr.id) })
                                    }
                                    if (inProjects.size == projects.size) {
                                        DropdownMenuItem(text = { Text("Ya está en todos tus proyectos") }, onClick = { projectMenu = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (entry == null || entry.qty == 0) {
                SimilarSection(paint, similar, onOpenSimilar)
                // Mezcla solo si no hay una casi idéntica en el estante
                if (paint.hasColor && (similar.firstOrNull()?.distance ?: 99.0) > 3.0) {
                    MixSection(paint.argb.toInt(), mixes, onOpenSimilar)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Botes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                QtyStepper(qty, onMinus = { if (qty > 0) qty-- }, onPlus = { qty++ })
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notas") },
                placeholder = { Text("Casi vacía, para piel, comprar otra…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSave(qty, notes) }, modifier = Modifier.weight(1f)) { Text("Guardar") }
                if (entry != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Eliminar") }
                }
            }
            if (paint.hasColor) TheorySection(paint, inventory, onOpenSimilar, onOpenWheel)
            DilutionSection(paint)
            if (entry != null && entry.qty > 0) {
                // Tu propia nota de dilución, junto a la guía de la marca
                OutlinedTextField(
                    value = thin,
                    onValueChange = { thin = it },
                    label = { Text("Mi dilución") },
                    placeholder = { Text("1:2 con agua en mi aerógrafo de 0.3…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (thin.trim() != entry.thin) {
                    TextButton(onClick = { onSaveThin(thin) }) { Text("Guardar mi dilución") }
                }
            }
            if (!equivalents.isNullOrEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Equivalentes en otras marcas", style = MaterialTheme.typography.titleMedium)
                    equivalents.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenSimilar(e.paint) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Swatch(e.paint.hex, size = 30.dp, metallic = e.paint.type == "Metálica")
                            Column(Modifier.weight(1f)) {
                                Text(e.paint.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(e.paint.brand, e.paint.line).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(com.ricardochr.estantepinturas.similarityLabel(e.distance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (e.owned) Text("✓ la tienes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        "Son los colores más parecidos del catálogo, no equivalencias oficiales de las marcas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry != null && entry.qty > 0 && similar.isNotEmpty()) {
                SimilarSection(paint, similar, onOpenSimilar, title = "Parecidas en tu estante")
            }
            if (confirmDelete) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("¿Quitar “{}” del inventario?", paint.name))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onDelete,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Sí, quitar") }
                            TextButton(onClick = { confirmDelete = false }) { Text("No") }
                        }
                    }
                }
            }
        }
    }
}

/** Diálogo para registrar una pintura que no está en el catálogo. */
@Composable
fun CustomPaintDialog(
    initialName: String = "",
    initialCode: String = "",
    onDismiss: () -> Unit,
    brandSuggestions: List<String> = emptyList(),
    onSave: (brand: String, line: String, name: String, code: String, hex: String, type: String, qty: Int) -> Unit,
) {
    var brand by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    var hex by remember { mutableStateOf("#888888") }
    var type by remember { mutableStateOf("Opaca") }
    var qty by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pintura fuera del catálogo")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(brand, { brand = it }, label = { Text(tr("Marca")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val sugg = brandSuggestions.filter { it.contains(brand.trim(), ignoreCase = true) && !it.equals(brand.trim(), ignoreCase = true) }.take(8)
                if (sugg.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sugg) { b -> FilterChip(selected = false, onClick = { brand = b }, label = { Text(b) }) }
                    }
                }
                OutlinedTextField(line, { line = it }, label = { Text(tr("Línea")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(tr("Nombre")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it }, label = { Text(tr("Código")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        qty, { qty = it.filter { c -> c.isDigit() }.take(3) }, label = { Text(tr("Botes")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(validHex(hex), size = 36.dp)
                    OutlinedTextField(hex, { hex = it.take(7) }, label = { Text(tr("Color (#RRGGBB)")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(tr("Tipo"), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PAINT_TYPES) { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || code.isNotBlank(),
                onClick = { onSave(brand, line, name.ifBlank { code }, code, hex, type, qty.toIntOrNull() ?: 1) },
            ) { Text(tr("Guardar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Lista de pinturas del inventario con color cercano a [paint]. */
@Composable
fun SimilarSection(
    paint: Paint,
    similar: List<SimilarPaint>,
    onOpen: (Paint) -> Unit,
    title: String = "No la tienes. Parecidas que sí tienes:",
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        when {
            paint.hex.equals(UNKNOWN_HEX, true) -> Text(
                tr("Esta pintura no tiene color registrado, así que no puedo compararla."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            similar.isEmpty() -> Text(
                tr("Ninguna pintura de tu inventario se le parece lo suficiente."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                Column {
                    similar.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(s.paint) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Las dos muestras juntas para comparar a simple vista
                            Row {
                                Swatch(paint.hex, size = 26.dp, metallic = paint.type == "Metálica")
                                Swatch(s.paint.hex, size = 26.dp, metallic = s.paint.type == "Metálica")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.paint.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(s.paint.brand, s.paint.code.ifBlank { s.paint.line }).filter { it.isNotBlank() }.joinToString(" · ") +
                                        if (s.sameFamily) "" else " · ${s.paint.type.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                similarityLabel(s.distance),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Cuántas pinturas tienes de cada marca (solo las que tienes en tu inventario). */
fun ownedBrandCounts(vm: com.ricardochr.estantepinturas.MainViewModel): Map<String, Int> =
    vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId]?.brand }.groupingBy { it }.eachCount()

/** Marcas con primero las que ya tienes (de la que más tienes a la que menos) y luego el resto en orden alfabético. */
fun brandsOwnedFirst(counts: List<Pair<String, Int>>, mine: Map<String, Int>): List<Pair<String, Int>> =
    counts.sortedWith(compareByDescending<Pair<String, Int>> { mine[it.first] ?: 0 }.thenBy { it.first.lowercase() })
