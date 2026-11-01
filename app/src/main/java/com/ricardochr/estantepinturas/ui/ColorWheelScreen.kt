package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.Harmony
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.WHEEL_SECTORS
import com.ricardochr.estantepinturas.WheelPos
import com.ricardochr.estantepinturas.angleDiff
import com.ricardochr.estantepinturas.paintsNearAngle
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.sectorOf
import com.ricardochr.estantepinturas.typeGroup
import com.ricardochr.estantepinturas.wheelPosOf
import kotlin.math.cos
import kotlin.math.sin

/** Ángulo en pantalla (radianes, 0 = arriba, sentido horario) para un ángulo RYB. Amarillo arriba como en el círculo de pintor. */
private fun screenAngle(ryb: Float): Double = Math.toRadians((((120f - ryb) % 360f + 360f) % 360f).toDouble())

private fun point(center: Offset, radius: Float, ryb: Float, frac: Float): Offset {
    val a = screenAngle(ryb)
    return Offset(center.x + (sin(a) * radius * frac).toFloat(), center.y - (cos(a) * radius * frac).toFloat())
}

/** Paleta: elegir entre analizar una imagen o ver el círculo cromático. */
@Composable
fun PaletteHub(vm: MainViewModel, onOpenPaint: (Paint) -> Unit, onProjectCreated: (com.ricardochr.estantepinturas.Project) -> Unit = {}) {
    var mode by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Desde una imagen") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Círculo cromático") })
        }
        Box(Modifier.weight(1f)) {
            if (mode == 0) PaletteScreen(vm, onOpenPaint, onProjectCreated) else ColorWheelScreen(vm, onOpenPaint)
        }
    }
}

/**
 * Tus pinturas sobre el círculo cromático. Toca una para ver su complementario, análogos,
 * tríada, etc., y qué pinturas tuyas caen en esos puntos.
 */
@Composable
fun ColorWheelScreen(vm: MainViewModel, onOpenPaint: (Paint) -> Unit, focus: Paint? = null) {
    val placed: List<Pair<Paint, WheelPos>> = remember(vm.owned, vm.byId, focus) {
        val own = vm.owned.values.filter { it.qty > 0 }
            .mapNotNull { vm.byId[it.paintId] }
            .filter { it.hasColor && typeGroup(it.type) != "auxiliar" && it.type != "Imprimación" }
        // La pintura elegida aparece aunque no la tengas
        (if (focus != null && focus.hasColor && own.none { it.id == focus.id }) own + focus else own).map { it to wheelPosOf(it) }
    }
    var selectedId by rememberSaveable { mutableStateOf(focus?.id) }
    var harmony by rememberSaveable { mutableStateOf(Harmony.COMPLEMENTARY) }
    val selected = placed.firstOrNull { it.first.id == selectedId }

    val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val ringBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val lineColor = MaterialTheme.colorScheme.onSurface

    // Huecos: sectores sin ninguna pintura tuya
    val empty = remember(placed) {
        WHEEL_SECTORS.filter { s -> placed.none { (_, w) -> !w.neutral && angleDiff(w.ryb, s.ryb) <= 15f } }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(8.dp),
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(placed) {
                            detectTapGestures { pos ->
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val r = minOf(size.width, size.height) / 2f * 0.80f
                                val hit = placed.minByOrNull { (_, w) -> (point(c, r * 0.86f, w.ryb, w.radius) - pos).getDistance() }
                                if (hit != null && (point(c, r * 0.86f, hit.second.ryb, hit.second.radius) - pos).getDistance() < 28.dp.toPx()) {
                                    selectedId = if (selectedId == hit.first.id) null else hit.first.id
                                }
                            }
                        },
                ) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = minOf(size.width, size.height) / 2f * 0.80f
                    // Fondo: sectores tenues y anillo exterior saturado
                    WHEEL_SECTORS.forEach { s ->
                        val start = Math.toDegrees(screenAngle(s.ryb)).toFloat() - 90f - 15f
                        val col = Color(parseHex(s.hex))
                        drawArc(col.copy(alpha = 0.13f), start, 30f, true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r))
                        drawArc(col, start + 0.8f, 28.4f, false, topLeft = Offset(c.x - r * 0.96f, c.y - r * 0.96f), size = Size(2 * r * 0.96f, 2 * r * 0.96f), style = Stroke(r * 0.08f))
                    }
                    drawCircle(ringBg, r * 0.12f, c)
                    // Etiquetas
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = labelColor
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 10.dp.toPx()
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    WHEEL_SECTORS.forEach { s ->
                        val p = point(c, r, s.ryb, 1.13f)
                        val words = s.name.uppercase().split(" ")
                        words.forEachIndexed { i, w ->
                            drawContext.canvas.nativeCanvas.drawText(w, p.x, p.y + (i - (words.size - 1) / 2f) * paint.textSize * 1.05f + paint.textSize / 3, paint)
                        }
                    }
                    // Armonía de la pintura elegida
                    if (selected != null) {
                        val base = selected.second.ryb
                        val angles = listOf(base) + harmony.offsets.map { (base + it + 360f) % 360f }
                        angles.forEachIndexed { i, a ->
                            val end = point(c, r, a, 0.92f)
                            drawLine(lineColor.copy(alpha = if (i == 0) 0.9f else 0.6f), c, end, strokeWidth = 2.dp.toPx(),
                                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            drawCircle(lineColor, 7.dp.toPx(), end, style = Stroke(2.5f.dp.toPx()))
                        }
                    }
                    // Tus pinturas
                    placed.forEach { (p, w) ->
                        val pos = point(c, r * 0.86f, w.ryb, w.radius)
                        val isSel = p.id == selectedId
                        val inHarmony = selected != null && !isSel && !w.neutral &&
                            (harmony.offsets.map { (selected.second.ryb + it + 360f) % 360f } + selected.second.ryb)
                                .any { angleDiff(it, w.ryb) <= 18f }
                        val rad = when {
                            isSel -> 11.dp.toPx()
                            inHarmony -> 8.dp.toPx()
                            else -> 6.dp.toPx()
                        }
                        val dim = selected != null && !isSel && !inHarmony
                        drawCircle(Color.Black.copy(alpha = if (dim) 0.15f else 0.5f), rad + 2.dp.toPx(), pos)
                        drawCircle(Color(parseHex(p.hex)).copy(alpha = if (dim) 0.35f else 1f), rad, pos)
                        drawCircle(if (isSel) Color(0xFFFFE600) else Color.White.copy(alpha = if (dim) 0.4f else 1f), rad, pos, style = Stroke(if (isSel) 3.dp.toPx() else 1.5f.dp.toPx()))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (placed.isEmpty()) {
                    Text("Agrega pinturas a tu inventario para verlas en el círculo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (selected == null) {
                    Text(
                        "Cada punto es una de tus ${placed.size} pinturas: el ángulo es su tono y la distancia al centro, qué tan saturada es (al centro, grises y neutros). Toca una para ver con qué combina.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (empty.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Te faltan colores en: " + empty.joinToString(", ") { it.name.lowercase() } + ". Opciones para cubrirlos:",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                empty.forEach { sec ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Swatch(sec.hex, size = 16.dp, metallic = false)
                                        Text(sec.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                    SuggestionRows(vm, sec.ryb, onOpenPaint)
                                }
                            }
                        }
                    }
                } else {
                    val (sp, sw) = selected
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.clickable { onOpenPaint(sp) }) {
                        Swatch(sp.hex, size = 40.dp, metallic = sp.type == "Metálica")
                        Column(Modifier.weight(1f)) {
                            Text(sp.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (sw.neutral) "Neutro" else sectorOf(sw.ryb).name) + " · " + listOf(sp.brand, sp.code).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Harmony.values().toList()) { h ->
                            FilterChip(selected = harmony == h, onClick = { harmony = h }, label = { Text(h.label) })
                        }
                    }
                    Text(harmony.tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sw.neutral) {
                        Text("Es un neutro (gris, blanco o negro): combina con todo, no tiene complementario.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        harmony.offsets.forEach { off ->
                            val a = (sw.ryb + off + 360f) % 360f
                            val list = paintsNearAngle(placed, a, sp.id)
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val sec = sectorOf(a)
                                        Swatch(sec.hex, size = 18.dp, metallic = false)
                                        Text(sec.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                    if (list.isEmpty()) {
                                        Text("No tienes pinturas de este tono. Podrías conseguir:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        SuggestionRows(vm, a, onOpenPaint)
                                    }
                                    list.forEach { p ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { onOpenPaint(p) },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Swatch(p.hex, size = 26.dp, metallic = p.type == "Metálica")
                                            Column(Modifier.weight(1f)) {
                                                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(listOf(p.brand, p.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pinturas del catálogo para un tono que no tienes, con botón para la lista de compras. */
@Composable
private fun SuggestionRows(vm: MainViewModel, ryb: Float, onOpenPaint: (Paint) -> Unit) {
    val list = remember(ryb, vm.owned, vm.catalog) { vm.suggestForHue(ryb) }
    if (list.isEmpty()) {
        Text("No encontré opciones en el catálogo.", style = MaterialTheme.typography.bodySmall)
        return
    }
    list.forEach { p ->
        val inList = vm.shopping.any { it.paintId == p.id }
        Row(
            Modifier.fillMaxWidth().clickable { onOpenPaint(p) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Swatch(p.hex, size = 24.dp, metallic = false)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(p.brand, p.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
            if (inList) {
                Text("✓ En lista", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            } else {
                androidx.compose.material3.TextButton(onClick = { vm.addToShopping(p.id, "Tono que te falta en el círculo") }) { Text("+ Lista") }
            }
        }
    }
}
