package com.ricardochr.estantepinturas

import com.ricardochr.estantepinturas.MainViewModel.PaletteView
import com.ricardochr.estantepinturas.MainViewModel.PointPick
import com.ricardochr.estantepinturas.MainViewModel.Equivalent
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Paleta desde una imagen y escaneo (funciones de MainViewModel; el estado vive en MainViewModel)

/**
 * Prueba otro color en una zona: [base] pasa a ser [target] y los demás tonos de la zona
 * (sombras y luces) conservan su diferencia de claridad respecto a la base.
 */
fun MainViewModel.recolorZone(members: List<Int>, base: Int, target: Lab) {
    val clusters = palette?.palette?.clusters ?: return
    val b = clusters.getOrNull(base) ?: return
    val bc = kotlin.math.sqrt(b.a * b.a + b.b * b.b).coerceAtLeast(4.0)
    val next = HashMap(paletteOverrides)
    for (m in members) {
        val c = clusters.getOrNull(m) ?: continue
        // Sombras y luces: misma diferencia de claridad y la misma proporción de saturación que tenían
        val ratio = (kotlin.math.sqrt(c.a * c.a + c.b * c.b) / bc).coerceIn(0.45, 1.4)
        next[m] = Lab((target.l + (c.l - b.l)).coerceIn(3.0, 97.0), target.a * ratio, target.b * ratio)
    }
    paletteOverrides = next
    refreshPaletteMatches()
}

fun MainViewModel.resetZoneColor(members: List<Int>) {
    if (members.none { it in paletteOverrides }) return
    paletteOverrides = paletteOverrides - members.toSet()
    refreshPaletteMatches()
}

fun MainViewModel.clearRecolors() {
    if (paletteOverrides.isEmpty()) return
    paletteOverrides = emptyMap()
    refreshPaletteMatches()
}

internal fun MainViewModel.inventoryPaints(): List<Paint> =
    owned.values.filter { it.qty > 0 }.mapNotNull { byId[it.paintId] }

internal fun MainViewModel.buildPaletteView(uri: Uri, detail: Int, bmp: Bitmap, original: ImagePalette, inv: List<Paint>, cat: List<Paint>): PaletteView {
    val fixed = brightenedPalette(original)
    val base = if (paletteBrighten && fixed != null) fixed else original
    val over = paletteOverrides.filterKeys { it in base.clusters.indices }
    fun build(ip: ImagePalette): Triple<List<PaletteMatch>, PaletteAdvice, Bitmap> {
        val raw = matchPalette(ip, inv, cat)
        val advice = buildAdvice(ip, raw, inv, gearKit)
        val matches = refineZones(raw, advice.families, inv, cat)
        return Triple(matches, advice, renderPreview(ip, matches, bmp))
    }
    if (over.isEmpty()) {
        val (matches, advice, preview) = build(base)
        return PaletteView(uri, detail, bmp, base, matches, preview, advice, original, fixed != null, base !== original)
    }
    // Con variantes: mismos píxeles, pero los colores cambiados se sustituyen antes de elegir pinturas
    val varied = ImagePalette(
        base.width, base.height, base.labels,
        base.clusters.mapIndexed { i, c -> over[i]?.let { ColorCluster(it.l, it.a, it.b, c.fraction) } ?: c },
        base.backgroundFound, base.zones,
    )
    val basePreview = build(base).third
    val (matches, advice, preview) = build(varied)
    return PaletteView(uri, detail, bmp, varied, matches, preview, advice, original, fixed != null, base !== original, over.keys, basePreview)
}

/** Analiza la imagen y sugiere pinturas del inventario. [detail]: cuántos colores buscar. */
fun MainViewModel.analyzePalette(uri: Uri, detail: Int) {
    if (paletteBusy) return
    paletteBusy = true
    paletteError = null
    // Los colores se numeran de nuevo en cada análisis: las variantes anteriores ya no aplican
    paletteOverrides = emptyMap()
    val inv = inventoryPaints()
    val cat = extra + catalog
    val reuse = palette?.takeIf { it.uri == uri }?.source
    viewModelScope.launch {
        var error: String? = null
        val result = withContext(Dispatchers.Default) {
            val bmp = try {
                reuse ?: loadBitmapForAnalysis(getApplication(), uri)
            } catch (e: Throwable) {
                null
            }
            if (bmp == null) {
                error = tr("No pude abrir esa imagen. Prueba con otra (JPG o PNG).")
                null
            } else {
                try {
                    buildPaletteView(uri, detail, bmp, analyzeImage(bmp, detail), inv, cat)
                } catch (e: Throwable) {
                    error = tr("Abrí la imagen pero falló el análisis ({}: {}). Envíame este mensaje.", e.javaClass.simpleName, e.message)
                    null
                }
            }
        }
        if (result == null) paletteError = error ?: tr("No pude analizar la imagen.")
        else palette = result
        paletteBusy = false
    }
}

/** Recalcula las sugerencias (cambió el inventario o la corrección de luz), sin volver a analizar la imagen. */
fun MainViewModel.refreshPaletteMatches() {
    val current = palette ?: return
    val inv = inventoryPaints()
    val cat = extra + catalog
    viewModelScope.launch {
        val updated = withContext(Dispatchers.Default) {
            buildPaletteView(current.uri, current.detail, current.source, current.original, inv, cat)
        }
        if (palette === current) palette = updated
    }
}

fun MainViewModel.changePaletteBrighten(on: Boolean) {
    if (paletteBrighten == on) return
    paletteBrighten = on
    refreshPaletteMatches()
}

/**
 * Lee el color en el punto ([x], [y] de 0 a 1) promediando un cuadrito de la foto, aplica la
 * misma corrección de luz que la paleta y busca tus pinturas más parecidas.
 */
fun MainViewModel.pickPoint(x: Float, y: Float): PointPick? {
    val view = palette ?: return null
    val src = view.source
    val px = (x * src.width).toInt().coerceIn(0, src.width - 1)
    val py = (y * src.height).toInt().coerceIn(0, src.height - 1)
    val r = maxOf(1, minOf(src.width, src.height) / 200)
    var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
    for (yy in (py - r)..(py + r)) for (xx in (px - r)..(px + r)) {
        if (xx < 0 || yy < 0 || xx >= src.width || yy >= src.height) continue
        val c = src.getPixel(xx, yy)
        sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
    }
    if (n == 0) return null
    val argb = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
    var lab = hexToLab(String.format("#%06X", argb and 0xFFFFFF))
    val ip = view.palette
    val lx = (x * ip.width).toInt().coerceIn(0, ip.width - 1)
    val ly = (y * ip.height).toInt().coerceIn(0, ip.height - 1)
    val label = ip.labels[ly * ip.width + lx]
    // Misma corrección de luz que se aplicó a la paleta (si la foto era oscura)
    if (view.brightened && label >= 0 && label !in view.recolored) {
        val o = view.original.clusters.getOrNull(label)
        val a = ip.clusters.getOrNull(label)
        if (o != null && a != null && o.l > 1) {
            val chromaO = kotlin.math.sqrt(o.a * o.a + o.b * o.b).coerceAtLeast(1.0)
            val chromaA = kotlin.math.sqrt(a.a * a.a + a.b * a.b)
            val k = (chromaA / chromaO).coerceIn(0.5, 2.0)
            lab = Lab((lab.l * a.l / o.l).coerceIn(0.0, 100.0), lab.a * k, lab.b * k)
        }
    }
    val inv = inventoryPaints().filter { it.hasColor && typeGroup(it.type) != "auxiliar" && typeGroup(it.type) != "metal" && it.type != "Imprimación" }
    val ranked = inv.map { it to deltaE2000(lab, it.lab) }.sortedBy { it.second + if (typeGroup(it.first.type) == "opaca") 0.0 else 3.0 }.take(4)
    val matches = ranked.map { SimilarPaint(it.first, it.second, typeGroup(it.first.type) == "opaca") }
    var buy: Paint? = null
    var buyD = Double.MAX_VALUE
    if ((matches.firstOrNull()?.distance ?: 99.0) > 8) {
        val ownedIds = owned.keys
        for (p in extra + catalog) {
            if (!p.hasColor || p.type != "Opaca" || p.quality == "n" || p.id in ownedIds) continue
            val d = deltaE2000(lab, p.lab)
            if (d < buyD) { buyD = d; buy = p }
        }
    }
    return PointPick(x, y, argb, lab, label, matches, buy, buyD)
}

fun MainViewModel.clearPalette() {
    palette = null
    paletteError = null
}

/**
 * Interpreta lo que vio la cámara: códigos de barras y líneas de texto (agrupadas por bloque).
 * Busca códigos de producto ("70.955", "MPA-040", "WP1401") y nombres exactos ("Flat Flesh").
 */
fun MainViewModel.interpret(barcodeValues: List<String>, blocks: List<List<String>>): ScanResult {
    val hits = LinkedHashMap<String, ScanHit>()
    val unknown = ArrayList<String>()

    for (raw in barcodeValues) {
        val code = raw.trim()
        val linked = barcodes[code]?.let { byId[it] }
        if (linked != null) {
            hits[linked.id] = ScanHit(linked, "código de barras")
            continue
        }
        // Vallejo: EAN 8429551 + referencia de 5 dígitos (70.955 -> 8429551709552)
        if (code.length == 13 && code.startsWith("8429551")) {
            val ref = code.substring(7, 12)
            val found = codeIndex[ref].orEmpty().filter { it.brand == "Vallejo" }
            if (found.isNotEmpty()) {
                found.forEach { hits[it.id] = ScanHit(it, "código de barras") }
                continue
            }
        }
        if (code.length >= 8) unknown.add(code)
    }

    for (lines in blocks) {
        for (h in matchBlock(lines)) if (!hits.containsKey(h.paint.id)) hits[h.paint.id] = h
    }
    return ScanResult(hits.values.toList(), unknown)
}

/** Pinturas que coinciden con un bloque de texto (código impreso o nombre). */
fun MainViewModel.matchBlock(lines: List<String>): List<ScanHit> {
    val hits = LinkedHashMap<String, ScanHit>()
    val candidates = LinkedHashSet<String>()
    lines.forEachIndexed { i, line ->
        val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        tokens.forEachIndexed { j, t ->
            candidates.add("C:" + codeKey(t))
            if (j + 1 < tokens.size) candidates.add("C:" + codeKey(t + tokens[j + 1]))
        }
        candidates.add("C:" + codeKey(line))
        candidates.add("N:" + nameKey(line))
        if (i + 1 < lines.size) candidates.add("N:" + nameKey(line + " " + lines[i + 1]))
    }
    for (c in candidates) {
        val key = c.substring(2)
        if (c.startsWith("C:")) {
            if (key.length < 4 || key.length > 10) continue
            if (key.all { it.isDigit() } && key.length < 5) continue
            codeIndex[key]?.forEach { if (!hits.containsKey(it.id)) hits[it.id] = ScanHit(it, tr("código {}", it.code)) }
        } else {
            if (key.length < 5) continue
            nameIndex[key]?.forEach { if (!hits.containsKey(it.id)) hits[it.id] = ScanHit(it, "nombre") }
        }
    }
    return hits.values.toList()
}
