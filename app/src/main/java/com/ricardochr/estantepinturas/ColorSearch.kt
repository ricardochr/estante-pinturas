package com.ricardochr.estantepinturas

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Busca en una foto las zonas cuyo color se parece al de una pintura (tapas, goteros, cuerpo
 * del bote o la franja de color de la etiqueta).
 *
 * La foto de una gaveta casi nunca tiene buena luz, así que antes de comparar se corrige:
 * balance de blancos "mundo gris" (el promedio de la escena debería ser neutro) y exposición
 * (la luminosidad media se lleva a un gris medio). La comparación da menos peso a la
 * luminosidad que al tono, porque la sombra cambia la luz pero no tanto el color.
 */
data class ColorCandidate(val x: Float, val y: Float, val score: Float, val distance: Float)

private val LIN = FloatArray(256) {
    val s = it / 255f
    if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
}

private fun f(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f

private fun gammaOut(v: Float): Float = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1 / 2.4f) - 0.055f

/**
 * @param target color de la pintura buscada.
 * @return candidatos ordenados de mejor a peor, con posición relativa (0–1).
 *         Vacío si la pintura es neutra (blanco, negro, gris): por color no se distingue de tapas y etiquetas.
 */
fun findColorCandidates(bmp: Bitmap, target: Lab, maxSide: Int = 560, limit: Int = 6): List<ColorCandidate> {
    val tChroma = sqrt(target.a * target.a + target.b * target.b).toFloat()
    if (tChroma < 12f) return emptyList()

    val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
    val img = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    val w = img.width
    val h = img.height
    val n = w * h
    val px = IntArray(n)
    img.getPixels(px, 0, w, 0, 0, w, h)
    if (img !== bmp) img.recycle()

    val r = FloatArray(n)
    val g = FloatArray(n)
    val b = FloatArray(n)
    var sr = 0.0
    var sg = 0.0
    var sb = 0.0
    var cnt = 0
    for (i in 0 until n) {
        val c = px[i]
        r[i] = LIN[(c shr 16) and 0xFF]; g[i] = LIN[(c shr 8) and 0xFF]; b[i] = LIN[c and 0xFF]
        val y = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
        if (y > 0.02f && y < 0.8f) { sr += r[i]; sg += g[i]; sb += b[i]; cnt++ }
    }
    // Balance de blancos
    val avg = if (cnt > 0) ((sr + sg + sb) / (3 * cnt)).toFloat() else 1f
    val gr = if (cnt > 0) (avg / (sr / cnt).toFloat()).coerceIn(0.6f, 1.6f) else 1f
    val gg = if (cnt > 0) (avg / (sg / cnt).toFloat()).coerceIn(0.6f, 1.6f) else 1f
    val gb = if (cnt > 0) (avg / (sb / cnt).toFloat()).coerceIn(0.6f, 1.6f) else 1f
    // Exposición: luminancia mediana a 0.18
    val lum = FloatArray(n) { (0.2126f * r[it] * gr + 0.7152f * g[it] * gg + 0.0722f * b[it] * gb) }
    val sortedLum = lum.copyOf().also { it.sort() }
    val exp = (0.18f / max(sortedLum[n / 2], 1e-3f)).coerceIn(0.7f, 4f)

    val L = FloatArray(n)
    val A = FloatArray(n)
    val B = FloatArray(n)
    for (i in 0 until n) {
        // A sRGB corregido y de vuelta a lineal (igual que la conversión del catálogo)
        val rr = LIN[(gammaOut((r[i] * gr * exp).coerceIn(0f, 1f)) * 255).toInt().coerceIn(0, 255)]
        val gg2 = LIN[(gammaOut((g[i] * gg * exp).coerceIn(0f, 1f)) * 255).toInt().coerceIn(0, 255)]
        val bb = LIN[(gammaOut((b[i] * gb * exp).coerceIn(0f, 1f)) * 255).toInt().coerceIn(0, 255)]
        val fx = f((rr * 0.4124f + gg2 * 0.3576f + bb * 0.1805f) / 0.95047f)
        val fy = f(rr * 0.2126f + gg2 * 0.7152f + bb * 0.0722f)
        val fz = f((rr * 0.0193f + gg2 * 0.1192f + bb * 0.9505f) / 1.08883f)
        L[i] = 116 * fy - 16; A[i] = 500 * (fx - fy); B[i] = 200 * (fy - fz)
    }

    val tl = target.l.toFloat()
    val ta = target.a.toFloat()
    val tb = target.b.toFloat()
    val dist = FloatArray(n)
    val chroma = FloatArray(n)
    for (i in 0 until n) {
        val dl = 0.45f * (L[i] - tl)
        val da = A[i] - ta
        val db = B[i] - tb
        dist[i] = sqrt(dl * dl + da * da + db * db)
        chroma[i] = sqrt(A[i] * A[i] + B[i] * B[i])
    }

    val minArea = max(4, (n * 0.00003f).toInt())
    val labels = IntArray(n)
    val queue = IntArray(n)
    for (threshold in floatArrayOf(16f, 22f, 28f)) {
        java.util.Arrays.fill(labels, 0)
        val comps = ArrayList<ColorCandidate>()
        var next = 1
        for (start in 0 until n) {
            if (labels[start] != 0 || dist[start] >= threshold || chroma[start] < tChroma * 0.45f) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = next
            var area = 0
            var sx = 0.0
            var sy = 0.0
            var sd = 0.0
            while (head < tail) {
                val i = queue[head++]
                area++
                val x = i % w
                val y = i / w
                sx += x; sy += y; sd += dist[i]
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (labels[j] == 0 && dist[j] < threshold && chroma[j] >= tChroma * 0.45f) {
                        labels[j] = next
                        queue[tail++] = j
                    }
                }
            }
            next++
            if (area < minArea) continue
            val md = (sd / area).toFloat()
            comps.add(ColorCandidate((sx / area / w).toFloat(), (sy / area / h).toFloat(), area * (1 - md / threshold), md))
        }
        if (comps.isEmpty()) continue
        comps.sortByDescending { it.score }
        // Quitar candidatos pegados a uno mejor (mismo bote)
        val kept = ArrayList<ColorCandidate>()
        for (c in comps) {
            if (kept.none { k -> (k.x - c.x).let { it * it } + (k.y - c.y).let { it * it } < 0.025f * 0.025f }) kept.add(c)
            if (kept.size >= limit) break
        }
        val best = kept.first().score
        return kept.map { it.copy(score = it.score / best) }
    }
    return emptyList()
}
