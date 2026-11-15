package com.ricardochr.estantepinturas

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Pintura reconocida en la foto de una gaveta, con su posición relativa (0–1). */
data class DetectedPaint(val paint: Paint, val x: Float, val y: Float, val owned: Boolean)

enum class LocateKind(val label: String) {
    SAVED("Ubicación que confirmaste"),
    LABEL("Etiqueta leída"),
    COLOR("Color parecido"),
    ZONE("Zona de su marca"),
    PLACE("Está en este lugar (sin punto marcado)"),
}

/** Posible lugar de una pintura en una foto (x, y relativos 0–1). */
data class LocateHit(val spot: StorageSpot, val x: Float, val y: Float, val kind: LocateKind, val confidence: Float, val zone: SpotZone? = null) {
    /** Señala un bote concreto (no una zona ni solo el lugar). */
    val isPoint: Boolean get() = kind != LocateKind.ZONE && kind != LocateKind.PLACE
}

/** Bloque de texto leído en la foto y su centro en píxeles de la imagen original. */
class TextBlockAt(val lines: List<String>, val x: Float, val y: Float)

/**
 * Lee el texto de la foto en las cuatro orientaciones, porque en una gaveta los botes acostados
 * tienen la etiqueta de lado. Devuelve los centros convertidos a la orientación original.
 * Llamar fuera del hilo principal.
 */
fun readTextBlocks(bmp: Bitmap): List<TextBlockAt> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val out = ArrayList<TextBlockAt>()
    try {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val img = if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            val result = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(img, 0)))
            } catch (e: Exception) {
                null
            } ?: continue
            for (block in result.textBlocks) {
                val box = block.boundingBox ?: continue
                val cx = box.exactCenterX()
                val cy = box.exactCenterY()
                // Volver del sistema girado al original (giro horario de deg grados)
                val (x, y) = when (deg) {
                    90 -> cy to (h - cx)
                    180 -> (w - cx) to (h - cy)
                    270 -> (w - cy) to cx
                    else -> cx to cy
                }
                out.add(TextBlockAt(block.lines.map { it.text }, x, y))
            }
            if (img !== bmp) img.recycle()
        }
    } finally {
        recognizer.close()
    }
    return out
}

// ---------------------------------------------------------------- lectura por partes y coincidencia tolerante

/** Abre una foto guardada reducida a la mitad si es muy grande (para mostrarla o buscar colores sin gastar memoria). */
fun decodeForDisplay(path: String, maxSide: Int = 2600): Bitmap? {
    val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, b)
    var sample = 1
    while (maxOf(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * Lee el texto de una foto por partes: la recorre en recuadros que se solapan, ampliados al doble, para
 * que las letras pequeñas de las etiquetas tengan tamaño suficiente. Cada recuadro se lee derecho y
 * girado a ambos lados (botes acostados). Devuelve centros relativos (0–1). [progress] recibe 0–1.
 */
fun readTextBlocksTiled(path: String, progress: (Float) -> Unit = {}): List<TextBlockAt> {
    val decoder = try {
        @Suppress("DEPRECATION")
        android.graphics.BitmapRegionDecoder.newInstance(path, false)
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    val w = decoder.width
    val h = decoder.height
    val tile = 800
    val stride = 600
    val xs = generateSequence(0) { it + stride }.takeWhile { it < w - tile / 4 }.toList().ifEmpty { listOf(0) }
    val ys = generateSequence(0) { it + stride }.takeWhile { it < h - tile / 4 }.toList().ifEmpty { listOf(0) }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val out = ArrayList<TextBlockAt>()
    val total = xs.size * ys.size
    var done = 0
    try {
        for (ty in ys) for (tx in xs) {
            val rect = android.graphics.Rect(tx, ty, minOf(w, tx + tile), minOf(h, ty + tile))
            val raw = try { decoder.decodeRegion(rect, null) } catch (e: Exception) { null }
            if (raw != null) {
                val scale = 2f
                for (deg in intArrayOf(0, 90, 270)) {
                    val m = Matrix().apply { postScale(scale, scale); if (deg != 0) postRotate(deg.toFloat()) }
                    val img = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    val rw = raw.width * scale
                    val rh = raw.height * scale
                    val result = try { Tasks.await(recognizer.process(InputImage.fromBitmap(img, 0))) } catch (e: Exception) { null }
                    if (result != null) for (block in result.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val cx = box.exactCenterX()
                        val cy = box.exactCenterY()
                        // Del recuadro girado y ampliado a la foto completa
                        val (px, py) = when (deg) {
                            90 -> cy to (rh - cx)
                            270 -> (rw - cy) to cx
                            else -> cx to cy
                        }
                        out.add(TextBlockAt(block.lines.map { it.text }, (tx + px / scale) / w, (ty + py / scale) / h))
                    }
                    img.recycle()
                }
                raw.recycle()
            }
            done++
            progress(done.toFloat() / total)
        }
    } finally {
        recognizer.close()
        decoder.recycle()
    }
    return out
}

private fun editDistance(a: String, b: String, max: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (cur[j] < rowMin) rowMin = cur[j]
        }
        if (rowMin > max) return max + 1
        prev = cur
    }
    return prev[b.length]
}

/** Letras que la lectura confunde con números en los códigos (O/0, I/1, S/5…). */
private fun codeShape(s: String): String = buildString {
    for (c in s.uppercase()) when (c) {
        'O', 'Q', 'D' -> append('0')
        'I', 'L', '|' -> append('1')
        'S' -> append('5')
        'B' -> append('8')
        'Z' -> append('2')
        else -> if (c.isLetterOrDigit() && c.code < 128) append(c)
    }
}

/** Una pintura reconocida en un bloque de texto: qué tan segura es la coincidencia y cómo se encontró. */
class LabelMatch(val paint: Paint, val block: TextBlockAt, val score: Int, val byCode: Boolean)

/**
 * Busca tus pinturas en el texto leído de una foto, tolerando etiquetas a medias: el nombre puede ir
 * dentro de una línea más larga ("74.521 Tierra Europea / European Earth"), con una o dos letras mal
 * leídas, y el código con letras confundidas con números. Solo compara contra [candidates] (tu
 * inventario), así los nombres comunes no dan falsos positivos con todo el catálogo.
 * Devuelve la mejor coincidencia de cada pintura.
 */
fun matchLabels(blocks: List<TextBlockAt>, candidates: List<Paint>): Map<String, LabelMatch> {
    if (blocks.isEmpty() || candidates.isEmpty()) return emptyMap()
    class Cand(val p: Paint, val words: List<String>, val joined: String, val code: String, val brandTokens: List<String>)
    // i18n:off — palabras de relleno en nombres de marcas
    val stop = setOf("the", "by", "of", "and")
    // i18n:on
    val cands = candidates.map { p ->
        val words = norm(p.name).split(' ').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val code = codeShape(p.code).let { c -> if (c.length >= 5 || (c.length == 4 && c.any { it.isLetter() })) c else "" }
        Cand(p, words, words.joinToString(""), code, norm(p.brand).split(' ').filter { it.length >= 3 && it !in stop })
    }
    val blockText = blocks.map { b -> norm(b.lines.joinToString(" ")) }
    val best = HashMap<String, LabelMatch>()
    for ((bi, b) in blocks.withIndex()) {
        val words = blockText[bi].split(' ').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val shape = codeShape(b.lines.joinToString(""))
        var top = 0
        val tops = ArrayList<Pair<Cand, Boolean>>()
        fun offer(c: Cand, score: Int, byCode: Boolean) {
            if (score > top) { top = score; tops.clear() }
            if (score == top) tops.add(c to byCode)
        }
        for (c in cands) {
            if (c.code.isNotEmpty() && shape.contains(c.code)) { offer(c, 200 + c.code.length, true); continue }
            val k = c.words.size
            if (k == 0 || c.joined.length < 5 || k > words.size) continue
            val tol = when { c.joined.length >= 10 -> 2; c.joined.length >= 7 -> 1; else -> 0 }
            var bestD = tol + 1
            for (s in 0..words.size - k) {
                val window = buildString { for (j in 0 until k) append(words[s + j]) }
                val d = if (window == c.joined) 0 else if (tol > 0) editDistance(window, c.joined, tol) else tol + 1
                if (d < bestD) bestD = d
                if (d == 0) break
            }
            if (bestD <= tol) offer(c, c.joined.length * 4 - bestD * 6, false)
        }
        if (tops.isEmpty()) continue
        // Mismo nombre en dos marcas: decide la marca que se lea cerca ("Vallejo", "Warpaints"…)
        val chosen = if (tops.size == 1) tops[0] else {
            val near = StringBuilder()
            for ((oi, o) in blocks.withIndex()) {
                val dx = o.x - b.x; val dy = o.y - b.y
                if (dx * dx + dy * dy < 0.08f * 0.08f) near.append(blockText[oi]).append(' ')
            }
            val t = near.toString()
            tops.firstOrNull { (c, _) -> c.brandTokens.any { t.contains(it) } } ?: tops[0]
        }
        val prev = best[chosen.first.p.id]
        if (prev == null || top > prev.score) best[chosen.first.p.id] = LabelMatch(chosen.first.p, b, top, chosen.second)
    }
    return best
}
