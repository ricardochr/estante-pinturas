package com.ricardochr.estantepinturas

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Medir el color de una pintura con la cámara.
 *
 * La foto lleva la muestra de la pintura dudosa y varias muestras de pinturas propias con color
 * medido (referencias), pintadas en el mismo papel y con la misma luz. Se compara lo que ve la cámara
 * en cada referencia contra su color conocido, se calcula la corrección (balance de blancos,
 * exposición y respuesta de la cámara) y se aplica a la muestra. Antes de aceptar el resultado se
 * comprueba que la corrección reproduce cada referencia sin usarla (validación "dejando una fuera").
 */

/** Zona marcada en la foto: centro (0–1 del ancho y alto) y radio (fracción del lado corto). */
data class MeasureSpot(val x: Float, val y: Float, val r: Float)

/** Problema de una zona marcada. [label] se traduce al mostrarse. */
enum class SampleIssue(val label: String, val blocking: Boolean) {
    QUEMADA("Zona quemada por exceso de luz: baja la luz o aleja la lámpara", true),
    OSCURA("Zona demasiado oscura: agrega luz", true),
    REFLEJO("Tiene reflejos: inclina el papel o mueve la luz", false),
    DISPAREJA("Muestra dispareja: marca solo la pintura, sin papel ni bordes", false),
    VACIA("Zona muy pequeña o fuera de la foto", true),
}

/** Lo que mide la cámara en una zona: color promedio en RGB lineal (0–1) y los problemas encontrados. */
data class SampleReading(
    val r: Double,
    val g: Double,
    val b: Double,
    val issues: List<SampleIssue>,
) {
    val y: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val usable: Boolean get() = issues.none { it.blocking }
}

private val LIN = DoubleArray(256) {
    val s = it / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun yToL(y: Double): Double = if (y > 0.008856) 116 * Math.cbrt(y) - 16 else 903.3 * y

/** Lee una zona: descarta píxeles quemados y brillos, y promedia el resto en RGB lineal. */
fun readSample(pixels: IntArray, w: Int, h: Int, spot: MeasureSpot): SampleReading {
    val cx = spot.x * w
    val cy = spot.y * h
    val rad = spot.r * min(w, h)
    val x0 = max(0, (cx - rad).toInt())
    val x1 = min(w - 1, (cx + rad).toInt())
    val y0 = max(0, (cy - rad).toInt())
    val y1 = min(h - 1, (cy + rad).toInt())
    val area = ((x1 - x0 + 1).toLong() * (y1 - y0 + 1)).coerceAtLeast(1)
    val step = max(1, sqrt(area / 6000.0).toInt())
    val rs = ArrayList<Double>()
    val gs = ArrayList<Double>()
    val bs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    var total = 0
    var clipped = 0
    var dark = 0
    val r2 = rad * rad
    var yy = y0
    while (yy <= y1) {
        var xx = x0
        while (xx <= x1) {
            val dx = xx - cx
            val dy = yy - cy
            if (dx * dx + dy * dy <= r2) {
                val c = pixels[yy * w + xx]
                val ri = (c shr 16) and 0xFF
                val gi = (c shr 8) and 0xFF
                val bi = c and 0xFF
                total++
                val mx = max(ri, max(gi, bi))
                if (mx >= 250) clipped++
                else {
                    if (mx <= 6) dark++
                    val rl = LIN[ri]
                    val gl = LIN[gi]
                    val bl = LIN[bi]
                    rs.add(rl); gs.add(gl); bs.add(bl)
                    ys.add(0.2126 * rl + 0.7152 * gl + 0.0722 * bl)
                }
            }
            xx += step
        }
        yy += step
    }
    val issues = ArrayList<SampleIssue>()
    if (total < 25 || ys.size < 15) {
        if (total >= 25 && clipped.toDouble() / total > 0.5) issues.add(SampleIssue.QUEMADA) else issues.add(SampleIssue.VACIA)
        return SampleReading(0.0, 0.0, 0.0, issues)
    }
    if (clipped.toDouble() / total > 0.03) issues.add(SampleIssue.QUEMADA)
    if (dark.toDouble() / total > 0.3) issues.add(SampleIssue.OSCURA)

    // Brillos: píxeles bastante más claros que la mediana de la zona
    val sorted = ys.sorted()
    val med = sorted[sorted.size / 2]
    val mad = ys.map { abs(it - med) }.sorted()[ys.size / 2] * 1.4826
    val limit = med + max(4 * mad, med * 0.25 + 0.004)
    var sr = 0.0
    var sg = 0.0
    var sb = 0.0
    var n = 0
    var glare = 0
    val keptL = ArrayList<Double>()
    for (i in ys.indices) {
        if (ys[i] > limit) { glare++; continue }
        sr += rs[i]; sg += gs[i]; sb += bs[i]; n++
        keptL.add(yToL(ys[i]))
    }
    if (glare.toDouble() / ys.size > 0.12) issues.add(SampleIssue.REFLEJO)
    if (n < 10) {
        issues.add(SampleIssue.VACIA)
        return SampleReading(0.0, 0.0, 0.0, issues)
    }
    val meanL = keptL.average()
    val sd = sqrt(keptL.sumOf { (it - meanL) * (it - meanL) } / keptL.size)
    if (sd > 7) issues.add(SampleIssue.DISPAREJA)
    return SampleReading(sr / n, sg / n, sb / n, issues)
}

/** Píxeles de la foto para medir varias zonas sin volver a leerla. */
fun pixelsOf(bmp: Bitmap): IntArray {
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return px
}

// ---------------------------------------------------------------- corrección de color

private fun hexToLinear(hex: String): DoubleArray {
    val v = parseHex(hex)
    return doubleArrayOf(LIN[((v shr 16) and 0xFF).toInt()], LIN[((v shr 8) and 0xFF).toInt()], LIN[(v and 0xFF).toInt()])
}

/** RGB lineal (0–1) a #RRGGBB. */
fun linearToHex(r: Double, g: Double, b: Double): String {
    fun enc(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }
    return String.format("#%02X%02X%02X", enc(r), enc(g), enc(b))
}

/** Tipo de corrección. Se usa la que mejor reproduce las referencias que no participaron en el cálculo. */
enum class CorrectionModel(val label: String, val minRefs: Int, val penalty: Double) {
    GANANCIA("balance de blancos y exposición", 3, 0.0),
    MATRIZ("matriz de color", 4, 0.5),
    MATRIZ_OFFSET("matriz de color con corrección de velo", 5, 1.0),
}

private class Correction(val model: CorrectionModel, val m: Array<DoubleArray>) {
    /** m: 3 filas; cada fila son los coeficientes (r, g, b[, 1]) de un canal. */
    fun apply(x: DoubleArray): DoubleArray = DoubleArray(3) { c ->
        val row = m[c]
        var s = row[0] * x[0] + row[1] * x[1] + row[2] * x[2]
        if (row.size > 3) s += row[3]
        s
    }
}

/** Resuelve A·x = b (sistema pequeño) por eliminación de Gauss con pivoteo. Devuelve null si es casi singular. */
private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
        if (abs(m[piv][col]) < 1e-10) return null
        val t = m[piv]; m[piv] = m[col]; m[col] = t
        for (r in 0 until n) {
            if (r == col) continue
            val f = m[r][col] / m[col][col]
            for (k in col..n) m[r][k] -= f * m[col][k]
        }
    }
    return DoubleArray(n) { m[it][n] / m[it][it] }
}

private fun fit(model: CorrectionModel, measured: List<DoubleArray>, known: List<DoubleArray>): Correction? {
    if (measured.size < model.minRefs - 1 || measured.isEmpty()) return null
    return when (model) {
        CorrectionModel.GANANCIA -> {
            val rows = Array(3) { c ->
                val num = measured.indices.sumOf { measured[it][c] * known[it][c] }
                val den = measured.indices.sumOf { measured[it][c] * measured[it][c] }
                if (den < 1e-9) return null
                DoubleArray(3).also { it[c] = num / den }
            }
            Correction(model, rows)
        }
        CorrectionModel.MATRIZ, CorrectionModel.MATRIZ_OFFSET -> {
            val k = if (model == CorrectionModel.MATRIZ) 3 else 4
            if (measured.size < k) return null
            fun feat(x: DoubleArray) = if (k == 3) x else doubleArrayOf(x[0], x[1], x[2], 1.0)
            val ata = Array(k) { DoubleArray(k) }
            for (x in measured) {
                val f = feat(x)
                for (i in 0 until k) for (j in 0 until k) ata[i][j] += f[i] * f[j]
            }
            // Pequeña regularización hacia la identidad para que pocas referencias no den una matriz disparatada
            val lambda = 1e-3 * (0 until k).sumOf { ata[it][it] } / k
            val rows = Array(3) { c ->
                val a = Array(k) { i -> DoubleArray(k) { j -> ata[i][j] + if (i == j) lambda else 0.0 } }
                val atb = DoubleArray(k)
                for (n in measured.indices) {
                    val f = feat(measured[n])
                    for (i in 0 until k) atb[i] += f[i] * known[n][c]
                }
                // La regularización empuja el coeficiente propio del canal hacia 1, no hacia 0
                atb[c] += lambda
                solve(a, atb) ?: return null
            }
            Correction(model, rows)
        }
    }
}

private fun linToLab(x: DoubleArray): Lab = hexToLab(linearToHex(x[0], x[1], x[2]))

/** Resultado de una toma. */
data class MeasureTake(
    val hex: String,
    /** Error al reproducir cada referencia sin usarla en el cálculo (ΔE2000), por id de pintura. */
    val refErrors: Map<String, Double>,
    val model: CorrectionModel,
    /** La muestra es más clara o más oscura que todas las referencias: la corrección se extrapola. */
    val outOfRange: Boolean,
    /** Avisos no bloqueantes de las zonas (reflejos, muestra dispareja). */
    val warnings: List<String>,
    /** Se usó el papel blanco para el balance de blancos. */
    val paperUsed: Boolean = false,
) {
    val meanError: Double get() = if (refErrors.isEmpty()) 99.0 else refErrors.values.average()
    val maxError: Double get() = refErrors.values.maxOrNull() ?: 99.0
}

/**
 * Ganancias por canal que vuelven neutro (gris) al papel blanco sin cambiar su luminancia. Se aplican
 * antes de la corrección con referencias: así el balance de blancos queda anclado al papel y las
 * referencias solo tienen que corregir exposición y respuesta de color. null si el papel no sirve.
 */
fun paperGains(paper: SampleReading?): DoubleArray? {
    if (paper == null || !paper.usable) return null
    val y = paper.y
    if (paper.r < 1e-4 || paper.g < 1e-4 || paper.b < 1e-4 || y < 0.05) return null
    val g = doubleArrayOf(y / paper.r, y / paper.g, y / paper.b)
    // Un "blanco" con un canal 3 veces más débil no es papel blanco: mejor no usarlo
    if (g.max() / g.min() > 3.0) return null
    return g
}

private fun SampleReading.scaled(g: DoubleArray?): SampleReading =
    if (g == null) this else SampleReading(r * g[0], g[1] * this.g, b * g[2], issues)

/**
 * Calcula el color de la muestra a partir de las referencias. Devuelve null si no hay datos suficientes.
 * @param paper lectura opcional del papel blanco, para anclar el balance de blancos.
 */
fun measureTake(target0: SampleReading, refs0: List<Pair<Paint, SampleReading>>, paper: SampleReading? = null): MeasureTake? {
    val gains = paperGains(paper)
    val target = target0.scaled(gains)
    val refs = refs0.map { it.first to it.second.scaled(gains) }
    val usable = refs.filter { it.second.usable }
    if (!target.usable || usable.size < 3) return null
    val measured = usable.map { doubleArrayOf(it.second.r, it.second.g, it.second.b) }
    val known = usable.map { hexToLinear(it.first.hex) }
    val knownLab = usable.map { it.first.lab }

    var best: Triple<CorrectionModel, List<Double>, Double>? = null
    for (model in CorrectionModel.entries) {
        if (usable.size < model.minRefs) continue
        val errs = ArrayList<Double>()
        var ok = true
        for (i in usable.indices) {
            val mm = measured.filterIndexed { j, _ -> j != i }
            val kk = known.filterIndexed { j, _ -> j != i }
            val c = fit(model, mm, kk)
            if (c == null) { ok = false; break }
            errs.add(deltaE2000(linToLab(c.apply(measured[i])), knownLab[i]))
        }
        if (!ok) continue
        // Un modelo más complejo tiene que ganar con claridad para preferirlo
        val score = errs.average() + model.penalty
        if (best == null || score < best.third) best = Triple(model, errs, score)
    }
    val (model, errs, _) = best ?: return null
    val corr = fit(model, measured, known) ?: return null
    val t = corr.apply(doubleArrayOf(target.r, target.g, target.b))
    val hex = linearToHex(t[0], t[1], t[2])
    val ys = usable.map { it.second.y }
    val outOfRange = target.y > ys.max() * 1.2 || target.y < ys.min() * 0.8
    val warnings = (listOf(target) + usable.map { it.second })
        .flatMap { it.issues }.filter { !it.blocking }.distinct().map { it.label }
        .let { if (paper != null && gains == null) it + tr("El papel no se pudo usar para el balance de blancos (quemado, oscuro o no es blanco).") else it }
    val byId = usable.indices.associate { usable[it].first.id to errs[it] }
    return MeasureTake(hex, byId, model, outOfRange, warnings, paperUsed = gains != null)
}

/** Nivel de confianza. [label] se traduce al mostrarse. */
enum class MeasureConfidence(val label: String, val accept: Boolean) {
    ALTA("Alta", true),
    MEDIA("Media", true),
    BAJA("Baja", true),
    RECHAZADA("No confiable", false),
}

/** Combinación de una o varias tomas. */
data class MeasureResult(
    val hex: String,
    val confidence: MeasureConfidence,
    /** Diferencia máxima entre tomas (ΔE2000); 0 con una sola toma. */
    val spread: Double,
    val meanError: Double,
    val maxError: Double,
    val outOfRange: Boolean,
    val takes: Int,
)

fun combineTakes(takes: List<MeasureTake>): MeasureResult? {
    if (takes.isEmpty()) return null
    val labs = takes.map { hexToLab(it.hex) }
    val l = labs.sumOf { it.l } / labs.size
    val a = labs.sumOf { it.a } / labs.size
    val b = labs.sumOf { it.b } / labs.size
    val argb = labToArgb(l, a, b)
    val hex = String.format("#%06X", argb and 0xFFFFFF)
    var spread = 0.0
    for (i in labs.indices) for (j in i + 1 until labs.size) spread = max(spread, deltaE2000(labs[i], labs[j]))
    val meanErr = takes.map { it.meanError }.average()
    val maxErr = takes.maxOf { it.maxError }
    val out = takes.any { it.outOfRange }
    val base = when {
        meanErr > 10 || maxErr > 18 || spread > 8 -> MeasureConfidence.RECHAZADA
        meanErr <= 3 && maxErr <= 6 -> MeasureConfidence.ALTA
        meanErr <= 6 && maxErr <= 10 -> MeasureConfidence.MEDIA
        else -> MeasureConfidence.BAJA
    }
    var conf = base
    fun down() {
        conf = when (conf) {
            MeasureConfidence.ALTA -> MeasureConfidence.MEDIA
            MeasureConfidence.MEDIA -> MeasureConfidence.BAJA
            else -> conf
        }
    }
    if (out) down()
    if (takes.size > 1 && spread > 4) down()
    if (takes.size == 1 && conf == MeasureConfidence.ALTA) {
        // Una sola toma no permite comprobar que el resultado se repite
        conf = MeasureConfidence.MEDIA
    }
    return MeasureResult(hex, conf, spread, meanErr, maxErr, out, takes.size)
}

// ---------------------------------------------------------------- referencias

/** Pinturas tuyas que sirven de referencia: color medido (no estimado), opacas y sin efectos especiales. */
fun referenceCandidates(target: Paint, owned: List<Paint>): List<Paint> =
    owned.filter {
        it.id != target.id && it.hasColor && it.quality == "" && it.type == "Opaca" && specialOf(it) == null
    }.distinctBy { it.id }

/**
 * Sugiere 4 referencias que cubren la foto: la más clara, la más oscura, la más parecida al color
 * esperado de la muestra y una de color intenso de otro tono. Se prefieren las de acabado mate (menos reflejos).
 */
fun suggestReferences(target: Paint, owned: List<Paint>, count: Int = 4): List<Paint> {
    val all = referenceCandidates(target, owned)
    val matte = all.filter { finishOf(it).finish != Finish.BRILLANTE }
    val pool = if (matte.size >= 3) matte else all
    if (pool.size <= count) return pool
    val picked = LinkedHashSet<Paint>()
    pool.maxByOrNull { it.lab.l }?.let { picked.add(it) }
    pool.filter { it !in picked }.minByOrNull { it.lab.l }?.let { picked.add(it) }
    if (target.hasColor) {
        pool.filter { it !in picked }.minByOrNull { deltaE2000(it.lab, target.lab) }?.let { picked.add(it) }
    }
    while (picked.size < count) {
        // La que más se aleja de las ya elegidas, con preferencia por colores intensos
        val next = pool.filter { it !in picked }.maxByOrNull { p ->
            val dmin = picked.minOf { deltaE2000(it.lab, p.lab) }
            dmin + 0.2 * sqrt(p.lab.a * p.lab.a + p.lab.b * p.lab.b)
        } ?: break
        picked.add(next)
    }
    return picked.toList()
}

// ---------------------------------------------------------------- registro de mediciones

/**
 * Mediciones hechas con la cámara, para poder compartirlas y corregir el catálogo para todos
 * (tools/color-overrides.json usa la clave "Marca|código o nombre").
 */
object MeasurementLog {
    data class Entry(val key: String, val paintId: String, val name: String, val hex: String, val confidence: String, val takes: Int, val meanError: Double, val at: Long)

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("mediciones", android.content.Context.MODE_PRIVATE)

    fun overrideKey(p: Paint): String = "${p.brand}|${p.code.ifBlank { p.name }}"

    fun record(context: android.content.Context, p: Paint, r: MeasureResult) {
        val o = org.json.JSONObject()
            .put("key", overrideKey(p)).put("paintId", p.id).put("name", p.name).put("hex", r.hex)
            .put("confidence", r.confidence.name).put("takes", r.takes).put("meanError", r.meanError).put("at", System.currentTimeMillis())
        prefs(context).edit().putString(p.id, o.toString()).apply()
    }

    fun all(context: android.content.Context): List<Entry> = prefs(context).all.values.mapNotNull { v ->
        try {
            val o = org.json.JSONObject(v as String)
            Entry(o.getString("key"), o.getString("paintId"), o.optString("name"), o.getString("hex"), o.getString("confidence"),
                o.optInt("takes", 1), o.optDouble("meanError", 99.0), o.optLong("at"))
        } catch (_: Exception) {
            null
        }
    }.sortedBy { it.key }

    /** Solo las de confianza Alta, en el formato de tools/color-overrides.json. */
    fun exportJson(context: android.content.Context): String {
        val o = org.json.JSONObject()
        all(context).filter { it.confidence == MeasureConfidence.ALTA.name }.forEach { o.put(it.key, it.hex) }
        return o.toString(2)
    }
}
