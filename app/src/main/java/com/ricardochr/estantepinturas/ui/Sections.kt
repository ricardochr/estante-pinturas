package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MixRecipe
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.chemistryOf
import com.ricardochr.estantepinturas.dilutionFor
import com.ricardochr.estantepinturas.similarityLabel
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.validHex
import androidx.compose.runtime.remember

@Composable
private fun ColorSquare(argb: Int, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    )
}

/**
 * Recetas de mezcla con tus pinturas para acercarse a [targetArgb].
 * [mixes] = null mientras se calcula.
 */
@Composable
fun MixSection(
    targetArgb: Int,
    mixes: List<MixRecipe>?,
    onOpen: (Paint) -> Unit,
    title: String = "Mézclala con lo que tienes",
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        when {
            mixes == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(tr("Buscando mezclas…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            mixes.isEmpty() -> Text(
                tr("No encontré una mezcla que mejore lo que ya tienes. Necesitas al menos dos pinturas opacas en tu inventario."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Column {
                    mixes.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Objetivo y resultado lado a lado
                                Row {
                                    ColorSquare(targetArgb, 26.dp)
                                    ColorSquare(r.resultArgb, 26.dp)
                                }
                                Text(
                                    similarityLabel(r.distance),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            r.warning?.let { w ->
                                Text(w, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            r.parts.forEach { part ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(part.paint) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${part.drops}×",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(28.dp),
                                    )
                                    Swatch(part.paint.hex, size = 20.dp, metallic = false)
                                    Column(Modifier.weight(1f)) {
                                        Text(part.paint.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            listOf(part.paint.brand, part.paint.code).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!mixes.isNullOrEmpty()) {
            Text(
                tr("Proporciones en gotas. Es una estimación: mezcla poco, prueba en la paleta y ajusta."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Proporciones de dilución de una pintura para aerógrafo, pincel, veladura y lavado. */
@Composable
fun DilutionSection(paint: Paint) {
    var expanded by rememberSaveable(paint.id) { mutableStateOf(false) }
    val guide = dilutionFor(paint)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Dilución y compatibilidad"), style = MaterialTheme.typography.titleSmall)
                    Text(guide.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(tr("Diluyente: {}", guide.thinner), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                guide.uses.forEach { u ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.use, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(999.dp)) {
                                Text(u.ratio, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                        Text(u.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                guide.notes.forEach { n ->
                    Text("• $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val chem = chemistryOf(paint)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("Base: {}", chem.label), style = MaterialTheme.typography.labelLarge)
                        Text(chem.mixesWith, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    tr("Proporción pintura : diluyente, en gotas. {}. Ajusta a ojo: el bote viejo o espeso necesita más.", guide.source.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Elegir el color correcto de una pintura con tono, saturación y brillo, o escribiendo el código #RRGGBB. */
@Composable
fun ColorFixDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onMeasure: (() -> Unit)? = null) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(parseHex(initial).toInt(), it) }
    }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    var text by remember { mutableStateOf(initial) }
    fun current(): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
    fun hexOf(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Corregir color")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onMeasure != null) {
                    androidx.compose.material3.Button(onClick = onMeasure, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.CameraAlt, contentDescription = null)
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        Text(tr("Medir con cámara o foto"))
                    }
                    Text(
                        tr("Lo más preciso: compara una muestra pintada con pinturas tuyas de color conocido. O ajústalo a mano:"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorSquare(parseHex(initial).toInt(), 44.dp)
                        Text(tr("Antes"), style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorSquare(current(), 44.dp)
                        Text(tr("Ahora"), style = MaterialTheme.typography.labelSmall)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { t ->
                            text = t.take(7)
                            val hx = validHex(t)
                            if (t.trim().length >= 6 && hx != "#888888" || t.trim().equals("#888888", true)) {
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV(parseHex(hx).toInt(), out)
                                h = out[0]; sat = out[1]; v = out[2]
                            }
                        },
                        label = { Text("#RRGGBB") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(tr("Tono"), style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(value = h, onValueChange = { h = it; text = hexOf(current()) }, valueRange = 0f..360f)
                Text(tr("Saturación"), style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(value = sat, onValueChange = { sat = it; text = hexOf(current()) }, valueRange = 0f..1f)
                Text(tr("Brillo"), style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(value = v, onValueChange = { v = it; text = hexOf(current()) }, valueRange = 0f..1f)
                Text(
                    tr("Compáralo con el bote real con buena luz. El cambio se guarda en tu teléfono y se mantiene aunque se actualice el catálogo."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(hexOf(current())) }) { Text(tr("Guardar")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Teoría del color de una pintura: cómo es, cómo sombrearla e iluminarla, y qué pasa al poner otra encima. */
@Composable
fun TheorySection(
    paint: Paint,
    inventory: List<Paint>,
    onOpen: (Paint) -> Unit,
    onOpenWheel: () -> Unit,
) {
    var expanded by rememberSaveable(paint.id) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var top by remember(paint.id) { mutableStateOf<Paint?>(null) }
    val prof = remember(paint.id, paint.hex) { com.ricardochr.estantepinturas.profileOf(paint) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Teoría del color"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${prof.hue.replaceFirstChar { it.uppercase() }} · ${prof.temperature} · ${prof.value} · ${prof.saturation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                androidx.compose.material3.OutlinedButton(onClick = onOpenWheel) { Text(tr("Ver en el círculo cromático")) }
                Text(tr("Si la usas de base"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                val advice = remember(paint.id, paint.hex, inventory) { com.ricardochr.estantepinturas.baseAdvice(paint, inventory) }
                advice.forEach { a ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val p = a.paint
                        if (p != null) Swatch(p.hex, size = 26.dp, metallic = p.type == "Metálica")
                        else Box(Modifier.size(26.dp))
                        Column(Modifier.weight(1f).then(if (p != null) Modifier.clickable { onOpen(p) } else Modifier)) {
                            Text(
                                a.role + ": " + (p?.let { "${it.name} (${it.brand})" } ?: tr("no tienes una adecuada")),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(a.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(tr("¿Qué pasa si pongo otra encima?"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                val t = top
                if (t == null) {
                    androidx.compose.material3.TextButton(onClick = { picking = true }) { Text(tr("Elegir la pintura de encima")) }
                } else {
                    val res = remember(paint.id, paint.hex, t.id, t.hex) { com.ricardochr.estantepinturas.layerOver(paint, t) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            parseHex(paint.hex).toInt() to "Base",
                            res.glazeArgb to tr("Veladura"),
                            res.layerArgb to tr("Capa fina"),
                            parseHex(t.hex).toInt() to tr("Encima"),
                        ).forEach { (c, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ColorSquare(c, 40.dp)
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(tr("Encima: {} ({})", t.name, t.brand), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    res.explanation.forEach { e ->
                        Text("• $e", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.TextButton(onClick = { picking = true }) { Text(tr("Probar con otra")) }
                }
            }
        }
    }
    if (picking) {
        InventoryPickDialog(
            inventory = inventory.filter { it.id != paint.id && it.hasColor },
            onDismiss = { picking = false },
            onPick = { top = it; picking = false },
        )
    }
}

/** Diálogo pequeño para elegir una pintura del inventario. */
@Composable
fun InventoryPickDialog(inventory: List<Paint>, onDismiss: () -> Unit, onPick: (Paint) -> Unit) {
    var q by remember { mutableStateOf("") }
    val words = com.ricardochr.estantepinturas.queryWords(q)
    val shown = remember(q, inventory) {
        (if (words.isEmpty()) inventory else inventory.filter { com.ricardochr.estantepinturas.matchesWords(it.searchText, words) })
            .sortedWith(compareBy({ it.brand }, { it.name }))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Elige de tu inventario")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(q, { q = it }, tr("Buscar…"))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown.size) { i ->
                        val p = shown[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Swatch(p.hex, size = 24.dp, metallic = p.type == "Metálica")
                            Column {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text(p.brand, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}
