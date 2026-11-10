package com.ricardochr.estantepinturas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
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
    val zones: List<ZoneInfo> = emptyList(),
)

/**
 * Cómo se reparte un color en la figura.
 * @param edgeRatio fracción de sus píxeles que tocan otro color: alto = detalles finos o líneas.
 * @param texture diferencia media de luminosidad entre píxeles vecinos del mismo color: alto = superficie texturada.
 * @param adjacency cuántos píxeles de este color tocan cada uno de los otros colores.
 */
class ZoneInfo(val edgeRatio: Double, val texture: Double, val adjacency: IntArray)

/** Pintura sugerida para un color de la imagen, con sombra y luz para sombreado por capas. */
data class PaletteMatch(
    val cluster: ColorCluster,
    val best: SimilarPaint?,
    val alternatives: List<SimilarPaint>,
    val shadow: Paint?,
    val highlight: Paint?,
    val toBuy: Paint?,
    val toBuyDistance: Double,
    /** Si no tienes una pintura para la sombra o la luz: cómo lograrla mezclando, o cuál comprar. */
    val shadowMix: ToneMix? = null,
    val highlightMix: ToneMix? = null,
    val shadowBuy: Paint? = null,
    val highlightBuy: Paint? = null,
    /** Lavado, tinta o contraste tuyo que, aplicado sobre la base seca, da la sombra. */
    val washShadow: Paint? = null,
    /** Pintura de contraste/speedpaint que da este color de una sola capa sobre imprimación clara. */
    val quick: Paint? = null,
    /** Tinta o pintura transparente de un tono parecido a la base, para la veladura que unifica la zona. */
    val glaze: Paint? = null,
)

/** Mezcla de la pintura base con otra para sacar la sombra o la luz: partes de cada una y color que resulta. */
data class ToneMix(val base: Paint, val other: Paint, val baseParts: Int, val otherParts: Int, val argb: Int) {
    val text: String get() = "$baseParts ${if (baseParts == 1) "parte" else "partes"} de ${base.name} + $otherParts de ${other.name}"
}

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
fun loadBitmapForAnalysis(context: Context, uri: Uri, maxSide: Int = 560): Bitmap? {
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
            // El croma pesa doble: un rojo muy oscuro de la figura no es un fondo negro
            val dl = L[i] - bl
            val da = 2f * (A[i] - ba)
            val db = 2f * (B[i] - bb)
            return sqrt(dl * dl + da * da + db * db)
        }
        val dists = opaqueBorder.map { distBg(it) }.sorted()
        val within = dists.count { it < 15f }.toDouble() / dists.size
        if (within > 0.6) {
            backgroundFound = true
            val t = (dists[(dists.size * 0.8).toInt().coerceAtMost(dists.size - 1)] * 1.3f).coerceIn(8f, 16f)
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
            val tHole = t * 0.6f
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

    // ---- colores poco frecuentes pero distintos (dientes, ojos, detalles): lo que quedó lejos de todos los grupos
    val groups = ArrayList<MutableCluster>()
    val counts = IntArray(kk)
    for (s in assign) counts[s]++
    for (c in 0 until kk) if (counts[c] > 0) groups.add(MutableCluster(cl[c], ca[c], cb[c], counts[c].toDouble() * step))
    run {
        val residual = ArrayList<Int>()
        for (i in fg) {
            var md = Double.MAX_VALUE
            for (c in 0 until kk) { val d = d2(i, c); if (d < md) md = d }
            if (md > 14.0 * 14.0) residual.add(i)
        }
        if (residual.size > 50) {
            val rStep = max(1, residual.size / 4000)
            val rs = IntArray((residual.size + rStep - 1) / rStep) { residual[it * rStep] }
            val rk = min(5, rs.size / 50).coerceAtLeast(1)
            val rl = DoubleArray(rk); val ra = DoubleArray(rk); val rb = DoubleArray(rk)
            for (c in 0 until rk) { val i = rs[(c * rs.size) / rk]; rl[c] = L[i].toDouble(); ra[c] = A[i].toDouble(); rb[c] = B[i].toDouble() }
            val rc = IntArray(rk)
            repeat(12) {
                val sl2 = DoubleArray(rk); val sa2 = DoubleArray(rk); val sb2 = DoubleArray(rk)
                java.util.Arrays.fill(rc, 0)
                for (i in rs) {
                    var best = 0
                    var bd = Double.MAX_VALUE
                    for (c in 0 until rk) {
                        val dl = L[i] - rl[c]; val da = A[i] - ra[c]; val db = B[i] - rb[c]
                        val d = dl * dl + da * da + db * db
                        if (d < bd) { bd = d; best = c }
                    }
                    sl2[best] += L[i]; sa2[best] += A[i]; sb2[best] += B[i]; rc[best]++
                }
                for (c in 0 until rk) if (rc[c] > 0) { rl[c] = sl2[c] / rc[c]; ra[c] = sa2[c] / rc[c]; rb[c] = sb2[c] / rc[c] }
            }
            for (c in 0 until rk) if (rc[c] > 0) groups.add(MutableCluster(rl[c], ra[c], rb[c], rc[c].toDouble() * rStep))
        }
    }

    // ---- unir grupos casi iguales
    while (true) {
        var bi = -1
        var bj = -1
        var bd = 7.0
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
    val kept = groups.filter { it.w / totalW >= 0.003 }.sortedByDescending { it.w }.take(16).ifEmpty { groups }

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

    // ---- cómo se reparte cada color: bordes, textura y vecinos
    val m = clusters.size
    val edges = IntArray(m)
    val sizes = IntArray(m)
    val texSum = DoubleArray(m)
    val texN = IntArray(m)
    val adj = Array(m) { IntArray(m) }
    for (i in fg) {
        val c = labels[i]
        sizes[c]++
        val x = i % w
        val y = i / w
        var edge = false
        if (x < w - 1) {
            val o = labels[i + 1]
            if (o == c) { texSum[c] += abs(L[i] - L[i + 1]).toDouble(); texN[c]++ }
            else { edge = true; if (o >= 0) { adj[c][o]++; adj[o][c]++ } }
        }
        if (y < h - 1) {
            val o = labels[i + w]
            if (o == c) { texSum[c] += abs(L[i] - L[i + w]).toDouble(); texN[c]++ }
            else { edge = true; if (o >= 0) { adj[c][o]++; adj[o][c]++ } }
        }
        if (edge) edges[c]++
    }
    val zones = (0 until m).map { c ->
        ZoneInfo(
            edgeRatio = if (sizes[c] > 0) edges[c].toDouble() / sizes[c] else 1.0,
            texture = if (texN[c] > 0) texSum[c] / texN[c] else 0.0,
            adjacency = adj[c],
        )
    }

    return ImagePalette(w, h, labels, clusters, backgroundFound, zones)
}

// ---------------------------------------------------------------- sugerencias

private fun matchPenalty(p: Paint): Double = when (typeGroup(p.type)) {
    "metal" -> 6.0
    "transparente" -> 4.0
    else -> if (p.type == "Imprimación") 3.0 else 0.0
}

/** Tono objetivo: la sombra baja la luz y se enfría un poco; la luz sube, pierde saturación y se calienta un poco. */
private fun toneTarget(base: Lab, darker: Boolean): Lab =
    if (darker) Lab(base.l - 20, base.a * 0.95, base.b * 0.95 - 3) else Lab(base.l + 18, base.a * 0.85, base.b * 0.85 + 3)

private fun argbLab(argb: Int): Lab = hexToLab(String.format("#%06X", argb and 0xFFFFFF))

/** Mejor mezcla de [base] con otra pintura tuya para llegar a la sombra o la luz. */
private fun mixTone(base: Paint, candidates: List<Paint>, darker: Boolean): ToneMix? =
    mixToward(base, candidates, toneTarget(base.lab, darker), darker)

/** Mejor mezcla de [base] con otra pintura tuya para acercarse a [target] (más oscuro o más claro que la base). */
private fun mixToward(base: Paint, candidates: List<Paint>, target: Lab, darker: Boolean): ToneMix? {
    val ratios = listOf(3 to 1, 2 to 1, 1 to 1)
    var best: ToneMix? = null
    var bestScore = Double.MAX_VALUE
    for (o in candidates) {
        if (o.id == base.id || typeGroup(o.type) != "opaca") continue
        if (darker && o.lab.l > base.lab.l - 8) continue
        if (!darker && o.lab.l < base.lab.l + 8) continue
        for ((wb, wo) in ratios) {
            val argb = mixTwo(base, o, wb, wo)
            val lab = argbLab(argb)
            val moved = if (darker) base.lab.l - lab.l else lab.l - base.lab.l
            if (moved < 8) continue
            val d = deltaE2000(target, lab)
            // A igual parecido, menos pintura añadida
            val score = d + wo.toDouble() / (wb + wo) * 2
            if (d < 12 && score < bestScore) {
                bestScore = score
                best = ToneMix(base, o, wb, wo, argb)
            }
        }
    }
    return best
}

/**
 * El lavado o tinta que mejor sombrea [base]: se simula la base con el lavado encima (2 partes de
 * base por 1 de lavado, que es más o menos lo que deja un lavado al acumularse en los huecos).
 */
private fun washFor(base: Paint, glazes: List<Paint>): Paint? {
    val target = toneTarget(base.lab, darker = true)
    return glazes.asSequence()
        .map { g -> g to argbLab(mixTwo(base, g, 2, 1)) }
        .filter { (_, lab) -> lab.l < base.lab.l - 6 }
        .map { (g, lab) -> g to deltaE2000(target, lab) }
        .filter { it.second < 14 }
        .minByOrNull { it.second + if (it.first.type == "Wash") 0.0 else 1.5 }
        ?.first
}

/** La pintura del catálogo más cercana al tono objetivo, prefiriendo marcas que ya usas. */
private fun buyTone(base: Lab, buyable: List<Paint>, brands: Set<String>, darker: Boolean): Paint? {
    val target = toneTarget(base, darker)
    return buyable.asSequence()
        .map { it to deltaE2000(target, it.lab) }
        .filter { it.second < 12 }
        .minByOrNull { it.second + if (brandKey(it.first.brand) in brands) 0.0 else 3.0 }
        ?.first
}

private fun pickTone(base: Lab, candidates: List<Paint>, exclude: String?, darker: Boolean): Paint? {
    val target = toneTarget(base, darker)
    return candidates
        .asSequence()
        .filter { it.id != exclude && typeGroup(it.type) == "opaca" }
        .filter { if (darker) it.lab.l < base.l - 7 else it.lab.l > base.l + 7 }
        .map { it to deltaE2000(target, it.lab) }
        .filter { it.second < 20 }
        .minByOrNull { it.second }
        ?.first
}

/** Sombra y luz del inventario para una pintura base (opacas, mismo tono, más oscura y más clara). */
fun tonesFor(base: Paint, inventory: List<Paint>): Pair<Paint?, Paint?> {
    val inv = inventory.filter { it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    return pickTone(base.lab, inv, base.id, darker = true) to pickTone(base.lab, inv, base.id, darker = false)
}

/**
 * Para cada color de la imagen: la pintura más parecida de tu inventario, dos alternativas,
 * una pintura más oscura para la sombra y una más clara para la luz. Si nada de lo que tienes
 * se parece, sugiere la más cercana del catálogo para comprar.
 */
fun matchPalette(palette: ImagePalette, inventory: List<Paint>, catalog: List<Paint>): List<PaletteMatch> {
    // Los metálicos no se sugieren para colores de la foto: un tono parecido en gris o café no es metal
    // La base, sombras y luces se pintan con pintura opaca; lavados, tintas y contrastes se proponen
    // aparte, para lo que sirven: oscurecer sobre la base o dar color de una capa.
    val inv = inventory.filter { it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    val glazes = inventory.filter { it.hasColor && (it.type == "Wash" || it.type == "Tinta" || it.type == "Contraste") }
    val contrasts = inventory.filter { it.hasColor && it.type == "Contraste" }
    val transparents = inventory.filter { it.hasColor && (it.type == "Tinta" || it.type == "Contraste") }
    val ownedIds = inventory.map { it.id }.toHashSet()
    val buyable = catalog.filter { it.hasColor && it.type == "Opaca" && it.quality != "n" && it.id !in ownedIds }
    val brands = inventory.map { brandKey(it.brand) }.toSet()
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
        val shadowMix = if (shadow == null && best != null) mixTone(best.paint, inv, darker = true) else null
        val highlightMix = if (highlight == null && best != null) mixTone(best.paint, inv, darker = false) else null
        val shadowBuy = if (shadow == null && shadowMix == null) buyTone(baseLab, buyable, brands, darker = true) else null
        val highlightBuy = if (highlight == null && highlightMix == null) buyTone(baseLab, buyable, brands, darker = false) else null
        val washShadow = best?.paint?.let { washFor(it, glazes) }
        val quick = contrasts.asSequence()
            .map { it to deltaE2000(lab, it.lab) }
            .filter { it.second < 9 && (best == null || it.second < best.distance + 3) }
            .minByOrNull { it.second }?.first
        val glaze = best?.paint?.let { b ->
            transparents.asSequence()
                .map { it to deltaE2000(b.lab, it.lab) }
                .filter { it.second < 20 }
                .minByOrNull { it.second + if (it.first.type == "Tinta") 0.0 else 2.0 }?.first
        }
        PaletteMatch(c, best, alts, shadow, highlight, toBuy, toBuyD, shadowMix, highlightMix, shadowBuy, highlightBuy, washShadow, quick, glaze)
    }
}

/** Imagen de la figura pintada con las pinturas sugeridas (fondo transparente). */
fun renderPreview(palette: ImagePalette, matches: List<PaletteMatch>, source: Bitmap): Bitmap {
    val colors = IntArray(matches.size) { i ->
        matches[i].best?.paint?.argb?.toInt() ?: matches[i].cluster.argb
    }
    val w = palette.width
    val h = palette.height
    // Lo que se tomó como fondo se deja como en la foto, para que no desaparezca ninguna parte de la figura
    val src = IntArray(w * h)
    val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
    scaled.getPixels(src, 0, w, 0, 0, w, h)
    if (scaled !== source) scaled.recycle()
    val out = IntArray(w * h)
    for (i in out.indices) {
        val lbl = palette.labels[i]
        out[i] = if (lbl < 0 || lbl >= colors.size) (src[i] or 0xFF000000.toInt()) else colors[lbl]
    }
    return Bitmap.createBitmap(out, palette.width, palette.height, Bitmap.Config.ARGB_8888)
}

/** Color de marcador que más contrasta con el color seleccionado: amarillo, cian o magenta. */
fun markerFor(c: ColorCluster): Int {
    val options = intArrayOf(0xFFFFE600.toInt(), 0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt())
    return options.maxByOrNull { o ->
        val r = SRGB_TO_LINEAR[(o shr 16) and 0xFF]
        val g = SRGB_TO_LINEAR[(o shr 8) and 0xFF]
        val b = SRGB_TO_LINEAR[o and 0xFF]
        val fx = labF((r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047)
        val fy = labF(r * 0.2126 + g * 0.7152 + b * 0.0722)
        val fz = labF((r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883)
        deltaE2000(c.lab, Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)))
    } ?: options[0]
}

/**
 * Imagen para ver dónde va el color [selected]: la zona elegida conserva su color y todo lo demás
 * (figura y fondo) queda en gris muy claro y desvaído, con un contorno fino gris oscuro alrededor
 * de la zona para que se encuentren también las partes pequeñas.
 */
fun renderHighlight(palette: ImagePalette, source: Bitmap, selected: Set<Int>): Bitmap {
    val w = palette.width
    val h = palette.height
    val n = w * h
    val src = IntArray(n)
    val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
    scaled.getPixels(src, 0, w, 0, 0, w, h)
    if (scaled !== source) scaled.recycle()
    val lbl = palette.labels
    val mask = BooleanArray(palette.clusters.size + 1) { it in selected }
    fun sel(l: Int) = l >= 0 && l < mask.size && mask[l]
    val out = IntArray(n)
    for (i in 0 until n) {
        if (sel(lbl[i])) {
            out[i] = src[i] or (0xFF shl 24)
            continue
        }
        // Contorno de 1 px junto a la zona elegida
        val x = i % w
        val y = i / w
        val edge = (x > 0 && sel(lbl[i - 1])) || (x < w - 1 && sel(lbl[i + 1])) ||
            (y > 0 && sel(lbl[i - w])) || (y < h - 1 && sel(lbl[i + w]))
        out[i] = if (edge) {
            0xFF505050.toInt()
        } else {
            val c = src[i]
            val lum = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            // Gris claro que conserva un poco de la forma de la figura
            val g = 200 + lum * 45 / 255
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Corrige las pinturas dentro de cada zona: la sombra, la luz y el punto de luz no pueden ser la
 * misma pintura que la base, y deben ir de más oscura a más clara. Si no tienes una pintura que
 * cumpla, propone una mezcla con la base o una para comprar.
 */
fun refineZones(matches: List<PaletteMatch>, families: List<PaletteFamily>, inventory: List<Paint>, catalog: List<Paint>): List<PaletteMatch> {
    val inv = inventory.filter { it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    val ownedIds = inventory.map { it.id }.toHashSet()
    val buyable = catalog.filter { it.hasColor && it.type == "Opaca" && it.quality != "n" && it.id !in ownedIds }
    val brands = inventory.map { brandKey(it.brand) }.toSet()
    val out = matches.toMutableList()
    for (f in families) {
        if (f.members.size < 2) continue
        val base = matches.getOrNull(f.base)?.best?.paint ?: continue
        val used = hashSetOf(base.id)
        val darker = f.members.filter { it != f.base && matches[it].cluster.l < matches[f.base].cluster.l }.sortedByDescending { matches[it].cluster.l }
        val lighter = f.members.filter { it != f.base && matches[it].cluster.l >= matches[f.base].cluster.l }.sortedBy { matches[it].cluster.l }
        for ((list, isDark) in listOf(darker to true, lighter to false)) {
            var prevL = base.lab.l
            for (i in list) {
                val m = matches[i]
                val ok: (Paint) -> Boolean = { p -> if (isDark) p.lab.l < prevL - 3 else p.lab.l > prevL + 3 }
                val ranked = inv.asSequence()
                    .filter { it.id !in used && ok(it) }
                    .map { it to deltaE2000(m.cluster.lab, it.lab) }
                    .filter { it.second < 20 }
                    .sortedBy { it.second }
                    .take(3)
                    .toList()
                val pick = ranked.firstOrNull()
                if (pick != null) {
                    used.add(pick.first.id)
                    prevL = pick.first.lab.l
                    out[i] = m.copy(
                        best = SimilarPaint(pick.first, pick.second, true),
                        alternatives = ranked.drop(1).map { SimilarPaint(it.first, it.second, true) },
                        toBuy = null,
                    )
                } else {
                    val mix = mixToward(base, inv, m.cluster.lab, isDark)
                    val buy = buyable.asSequence()
                        .filter { ok(it) }
                        .map { it to deltaE2000(m.cluster.lab, it.lab) }
                        .filter { it.second < 15 }
                        .minByOrNull { it.second + if (brandKey(it.first.brand) in brands) 0.0 else 3.0 }
                    mix?.let { prevL = argbLab(it.argb).l }
                    out[i] = m.copy(
                        best = null,
                        alternatives = emptyList(),
                        toBuy = buy?.first,
                        toBuyDistance = buy?.second ?: Double.MAX_VALUE,
                        shadowMix = if (isDark) mix else null,
                        highlightMix = if (isDark) null else mix,
                    )
                }
            }
        }
    }
    return out
}

/**
 * Las fotos de figuras suelen salir oscuras (poca luz, sombras fuertes, barniz brillante): los
 * colores de la foto quedan mucho más oscuros que la pintura en el bote. Si la luminosidad media
 * de la figura es baja, se aclaran los colores con una curva (como subir la exposición) conservando
 * el tono y recuperando un poco de saturación. Devuelve null si la foto no es oscura.
 */
fun brightenedPalette(ip: ImagePalette): ImagePalette? {
    if (ip.clusters.isEmpty()) return null
    val sorted = ip.clusters.sortedBy { it.l }
    var acc = 0.0
    var median = sorted.last().l
    for (c in sorted) {
        acc += c.fraction
        if (acc >= 0.5) { median = c.l; break }
    }
    if (median >= 40 || median <= 1) return null
    val g = (kotlin.math.ln(0.50) / kotlin.math.ln(median / 100.0)).coerceIn(0.55, 1.0)
    val clusters = ip.clusters.map { c ->
        val nl = 100.0 * Math.pow((c.l / 100.0).coerceIn(0.0, 1.0), g)
        val boost = 1.0 + 0.35 * (1.0 - g) / 0.45
        ColorCluster(nl, c.a * boost, c.b * boost, c.fraction)
    }
    return ImagePalette(ip.width, ip.height, ip.labels, clusters, ip.backgroundFound, ip.zones)
}
