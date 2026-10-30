package com.ricardochr.estantepinturas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Un color dominante de la imagen (en CIELAB) y qué parte de la figura ocupa. */
data class ColorCluster(val l: Double, val a: Double, val b: Double, val fraction: Double) {
    val lab: Lab get() = Lab(l, a, b)
    val argb: Int by lazy { labToArgb(l, a, b) }
}

/** Resultado del análisis: colores y, por píxel, a qué color pertenece (-1 = fondo). */
class ImagePalette(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val clusters: List<ColorCluster>,
    val backgroundFound: Boolean,
)

/** Pintura sugerida para un color de la imagen, con sombra y luz para sombreado por capas. */
data class PaletteMatch(
    val cluster: ColorCluster,
    val best: SimilarPaint?,
    val alternatives: List<SimilarPaint>,
    val shadow: Paint?,
    val highlight: Paint?,
    val toBuy: Paint?,
    val toBuyDistance: Double,
)

// ---------------------------------------------------------------- color

private val SRGB_TO_LINEAR = DoubleArray(256) {
    val s = it / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun labF(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116

fun labToArgb(l: Double, a: Double, b: Double): Int {
    val fy = (l + 16) / 116
    val fx = fy + a / 500
    val fz = fy - b / 200
    fun inv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 0.008856) t3 else (t - 16.0 / 116) / 7.787
    }
    val x = inv(fx) * 0.95047
    val y = inv(fy)
    val z = inv(fz) * 1.08883
    fun gamma(v: Double): Int {
        val c = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        return (c * 255).toInt().coerceIn(0, 255)
    }
    val r = gamma(3.2406 * x - 1.5372 * y - 0.4986 * z)
    val g = gamma(-0.9689 * x + 1.8758 * y + 0.0415 * z)
    val bl = gamma(0.0557 * x - 0.2040 * y + 1.0570 * z)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

// ---------------------------------------------------------------- carga de imagen

/** Abre la imagen reducida a [maxSide] px por lado y con la orientación de la foto corregida. */
fun loadBitmapForAnalysis(context: Context, uri: Uri, maxSide: Int = 400): Bitmap? {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Con inJustDecodeBounds, decodeStream siempre devuelve null: solo llena outWidth/outHeight
    (cr.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val rotation = try {
        cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    } catch (e: Exception) {
        0
    }
    val scale = min(1f, maxSide.toFloat() / max(raw.width, raw.height))
    if (scale >= 1f && rotation == 0) return raw
    val m = Matrix().apply {
        postScale(scale, scale)
        if (rotation != 0) postRotate(rotation.toFloat())
    }
    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (out !== raw) raw.recycle()
    return out
}

// ---------------------------------------------------------------- análisis

private class MutableCluster(var l: Double, var a: Double, var b: Double, var w: Double)

/**
 * Separa el fondo liso y agrupa los colores de la figura.
 *
 * Fondo: se toma el color mediano del borde de la imagen y se "inunda" desde el borde todo lo
 * conectado que se le parezca. Así un blanco dentro de la figura no se confunde con un fondo blanco.
 * Colores: k-means en CIELAB sobre los píxeles de la figura; grupos muy parecidos se unen y los
 * que ocupan menos de ~1% se descartan.
 *
 * @param k cantidad inicial de grupos (5 pocos, 8 normal, 12 detallado).
 */
fun analyzeImage(bmp: Bitmap, k: Int): ImagePalette {
    val w = bmp.width
    val h = bmp.height
    val n = w * h
    val px = IntArray(n)
    bmp.getPixels(px, 0, w, 0, 0, w, h)

    val L = FloatArray(n)
    val A = FloatArray(n)
    val B = FloatArray(n)
    val transparent = BooleanArray(n)
    for (i in 0 until n) {
        val c = px[i]
        if ((c ushr 24) < 128) {
            transparent[i] = true
            continue
        }
        val r = SRGB_TO_LINEAR[(c shr 16) and 0xFF]
        val g = SRGB_TO_LINEAR[(c shr 8) and 0xFF]
        val b = SRGB_TO_LINEAR[c and 0xFF]
        val fx = labF((r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047)
        val fy = labF(r * 0.2126 + g * 0.7152 + b * 0.0722)
        val fz = labF((r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883)
        L[i] = (116 * fy - 16).toFloat()
        A[i] = (500 * (fx - fy)).toFloat()
        B[i] = (200 * (fy - fz)).toFloat()
    }

    // ---- fondo
    val isBg = BooleanArray(n)
    for (i in 0 until n) if (transparent[i]) isBg[i] = true
    val border = ArrayList<Int>(2 * (w + h))
    for (x in 0 until w) { border.add(x); border.add((h - 1) * w + x) }
    for (y in 1 until h - 1) { border.add(y * w); border.add(y * w + w - 1) }
    val opaqueBorder = border.filter { !transparent[it] }
    var backgroundFound = transparent.any { it }

    if (opaqueBorder.size > border.size * 0.3) {
        fun median(arr: FloatArray): Float {
            val v = opaqueBorder.map { arr[it] }.sorted()
            return v[v.size / 2]
        }
        val bl = median(L)
        val ba = median(A)
        val bb = median(B)
        fun distBg(i: Int): Float {
            val dl = L[i] - bl
            val da = A[i] - ba
            val db = B[i] - bb
            return sqrt(dl * dl + da * da + db * db)
        }
        val dists = opaqueBorder.map { distBg(it) }.sorted()
        val within = dists.count { it < 15f }.toDouble() / dists.size
        if (within > 0.6) {
            backgroundFound = true
            val t = (dists[(dists.size * 0.8).toInt().coerceAtMost(dists.size - 1)] * 1.5f).coerceIn(10f, 22f)
            val queue = IntArray(n)
            var head = 0
            var tail = 0
            for (i in opaqueBorder) {
                if (!isBg[i] && distBg(i) < t) {
                    isBg[i] = true
                    queue[tail++] = i
                }
            }
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) { val j = i - 1; if (!isBg[j] && !transparent[j] && distBg(j) < t) { isBg[j] = true; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (!isBg[j] && !transparent[j] && distBg(j) < t) { isBg[j] = true; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (!isBg[j] && !transparent[j] && distBg(j) < t) { isBg[j] = true; queue[tail++] = j } }
                if (y < h - 1) { val j = i + w; if (!isBg[j] && !transparent[j] && distBg(j) < t) { isBg[j] = true; queue[tail++] = j } }
            }
            // Huecos de fondo encerrados por la figura (entre brazos, cables…): no tocan el borde,
            // pero son del mismo color que el fondo
            val tHole = t * 0.7f
            for (i in 0 until n) if (!isBg[i] && !transparent[i] && distBg(i) < tHole) isBg[i] = true
        }
    }

    var fgCount = isBg.count { !it }
    if (fgCount < n * 0.02) {
        // No se pudo separar: se usa toda la imagen
        for (i in 0 until n) isBg[i] = transparent[i]
        fgCount = isBg.count { !it }
        backgroundFound = false
    }
    val fg = IntArray(fgCount)
    run {
        var j = 0
        for (i in 0 until n) if (!isBg[i]) fg[j++] = i
    }

    // ---- k-means sobre una muestra de la figura
    val step = max(1, fgCount / 12000)
    val sample = IntArray((fgCount + step - 1) / step) { fg[it * step] }
    val rnd = Random(7)
    val kk = min(k, sample.size).coerceAtLeast(1)
    val cl = DoubleArray(kk)
    val ca = DoubleArray(kk)
    val cb = DoubleArray(kk)

    fun d2(i: Int, c: Int): Double {
        val dl = L[i] - cl[c]
        val da = A[i] - ca[c]
        val db = B[i] - cb[c]
        return dl * dl + da * da + db * db
    }

    // k-means++
    val first = sample[rnd.nextInt(sample.size)]
    cl[0] = L[first].toDouble(); ca[0] = A[first].toDouble(); cb[0] = B[first].toDouble()
    val minD = DoubleArray(sample.size) { Double.MAX_VALUE }
    for (c in 1 until kk) {
        var sum = 0.0
        for (s in sample.indices) {
            val d = d2(sample[s], c - 1)
            if (d < minD[s]) minD[s] = d
            sum += minD[s]
        }
        var r = rnd.nextDouble() * sum
        var pick = sample.last()
        for (s in sample.indices) {
            r -= minD[s]
            if (r <= 0) { pick = sample[s]; break }
        }
        cl[c] = L[pick].toDouble(); ca[c] = A[pick].toDouble(); cb[c] = B[pick].toDouble()
    }

    val assign = IntArray(sample.size)
    repeat(15) {
        val sl = DoubleArray(kk)
        val sa = DoubleArray(kk)
        val sb = DoubleArray(kk)
        val cnt = IntArray(kk)
        for (s in sample.indices) {
            val i = sample[s]
            var best = 0
            var bd = Double.MAX_VALUE
            for (c in 0 until kk) {
                val d = d2(i, c)
                if (d < bd) { bd = d; best = c }
            }
            assign[s] = best
            sl[best] += L[i]; sa[best] += A[i]; sb[best] += B[i]; cnt[best]++
        }
        for (c in 0 until kk) {
            if (cnt[c] > 0) {
                cl[c] = sl[c] / cnt[c]; ca[c] = sa[c] / cnt[c]; cb[c] = sb[c] / cnt[c]
            }
        }
    }

    // ---- unir grupos casi iguales
    val groups = ArrayList<MutableCluster>()
    val counts = IntArray(kk)
    for (s in assign) counts[s]++
    for (c in 0 until kk) if (counts[c] > 0) groups.add(MutableCluster(cl[c], ca[c], cb[c], counts[c].toDouble()))
    while (true) {
        var bi = -1
        var bj = -1
        var bd = 8.0
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            val d = deltaE2000(Lab(groups[i].l, groups[i].a, groups[i].b), Lab(groups[j].l, groups[j].a, groups[j].b))
            if (d < bd) { bd = d; bi = i; bj = j }
        }
        if (bi < 0) break
        val g1 = groups[bi]
        val g2 = groups[bj]
        val tw = g1.w + g2.w
        g1.l = (g1.l * g1.w + g2.l * g2.w) / tw
        g1.a = (g1.a * g1.w + g2.a * g2.w) / tw
        g1.b = (g1.b * g1.w + g2.b * g2.w) / tw
        g1.w = tw
        groups.removeAt(bj)
    }
    val totalW = groups.sumOf { it.w }
    val kept = groups.filter { it.w / totalW >= 0.012 }.ifEmpty { groups }

    // ---- asignar cada píxel de la figura a su color final
    val labels = IntArray(n) { -1 }
    val pixCount = IntArray(kept.size)
    for (i in fg) {
        var best = 0
        var bd = Double.MAX_VALUE
        for (c in kept.indices) {
            val dl = L[i] - kept[c].l
            val da = A[i] - kept[c].a
            val db = B[i] - kept[c].b
            val d = dl * dl + da * da + db * db
            if (d < bd) { bd = d; best = c }
        }
        labels[i] = best
        pixCount[best]++
    }
    val order = kept.indices.sortedByDescending { pixCount[it] }
    val remap = IntArray(kept.size)
    order.forEachIndexed { newIdx, old -> remap[old] = newIdx }
    for (i in 0 until n) if (labels[i] >= 0) labels[i] = remap[labels[i]]
    val clusters = order.map { ColorCluster(kept[it].l, kept[it].a, kept[it].b, pixCount[it].toDouble() / fgCount) }

    return ImagePalette(w, h, labels, clusters, backgroundFound)
}

// ---------------------------------------------------------------- sugerencias

private fun matchPenalty(p: Paint): Double = when (typeGroup(p.type)) {
    "metal" -> 6.0
    "transparente" -> 4.0
    else -> if (p.type == "Imprimación") 3.0 else 0.0
}

private fun pickTone(base: Lab, candidates: List<Paint>, exclude: String?, darker: Boolean): Paint? {
    val target = if (darker) Lab(base.l - 20, base.a, base.b) else Lab(base.l + 18, base.a * 0.85, base.b * 0.85)
    return candidates
        .asSequence()
        .filter { it.id != exclude && typeGroup(it.type) == "opaca" }
        .filter { if (darker) it.lab.l < base.l - 7 else it.lab.l > base.l + 7 }
        .map { it to deltaE2000(target, it.lab) }
        .filter { it.second < 20 }
        .minByOrNull { it.second }
        ?.first
}

/**
 * Para cada color de la imagen: la pintura más parecida de tu inventario, dos alternativas,
 * una pintura más oscura para la sombra y una más clara para la luz. Si nada de lo que tienes
 * se parece, sugiere la más cercana del catálogo para comprar.
 */
fun matchPalette(palette: ImagePalette, inventory: List<Paint>, catalog: List<Paint>): List<PaletteMatch> {
    val inv = inventory.filter { it.hasColor && typeGroup(it.type) != "auxiliar" }
    val ownedIds = inventory.map { it.id }.toHashSet()
    val buyable = catalog.filter { it.hasColor && it.type == "Opaca" && it.id !in ownedIds }
    return palette.clusters.map { c ->
        val lab = c.lab
        val ranked = inv
            .map { p ->
                val d = deltaE2000(lab, p.lab)
                Triple(p, d, d + matchPenalty(p))
            }
            .filter { it.second < 30 }
            .sortedBy { it.third }
        val best = ranked.firstOrNull()?.let { SimilarPaint(it.first, it.second, typeGroup(it.first.type) == "opaca") }
        val alts = ranked.drop(1).take(2).map { SimilarPaint(it.first, it.second, typeGroup(it.first.type) == "opaca") }
        val baseLab = best?.paint?.lab ?: lab
        val shadow = pickTone(baseLab, inv, best?.paint?.id, darker = true)
        val highlight = pickTone(baseLab, inv, best?.paint?.id, darker = false)
        var toBuy: Paint? = null
        var toBuyD = Double.MAX_VALUE
        if (best == null || best.distance > 10) {
            for (p in buyable) {
                val d = deltaE2000(lab, p.lab)
                if (d < toBuyD) { toBuyD = d; toBuy = p }
            }
            if (best != null && toBuyD > best.distance - 3) toBuy = null
        }
        PaletteMatch(c, best, alts, shadow, highlight, toBuy, toBuyD)
    }
}

/** Imagen de la figura pintada con las pinturas sugeridas (fondo transparente). */
fun renderPreview(palette: ImagePalette, matches: List<PaletteMatch>): Bitmap {
    val colors = IntArray(matches.size) { i ->
        matches[i].best?.paint?.argb?.toInt() ?: matches[i].cluster.argb
    }
    val out = IntArray(palette.width * palette.height)
    for (i in out.indices) {
        val lbl = palette.labels[i]
        out[i] = if (lbl < 0) 0 else colors[lbl]
    }
    return Bitmap.createBitmap(out, palette.width, palette.height, Bitmap.Config.ARGB_8888)
}
