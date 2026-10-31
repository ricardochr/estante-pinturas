package com.ricardochr.estantepinturas

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mezclas para acercarse a un color con las pinturas que ya tienes.
 *
 * Usa el modelo Kubelka-Munk de dos coeficientes por canal: cada pintura absorbe (K) y dispersa (S)
 * luz, y en la mezcla se suman esas propiedades (no el color que se ve). Así azul + amarillo da verde
 * en vez de gris. Como no se conocen los pigmentos reales, la dispersión se estima por la luminosidad
 * (los blancos aclaran mucho) y los negros reciben más absorción (oscurecen mucho con poca cantidad),
 * como pasa con la pintura real. Es una aproximación: las proporciones son un punto de partida.
 */
data class MixPart(val paint: Paint, val drops: Int)

data class MixRecipe(val parts: List<MixPart>, val resultArgb: Int, val distance: Double) {
    val description: String
        get() = parts.joinToString(" + ") { "${it.drops} ${if (it.drops == 1) "gota" else "gotas"} de ${it.paint.name}" }

    /** Aviso de compatibilidad (marcas distintas, bases distintas…) o null. */
    val warning: String? by lazy { mixWarning(parts.map { it.paint }) }
}

private fun srgbToLinear(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun linearToSrgb(v: Double): Int {
    val c = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    return (c * 255).toInt().coerceIn(0, 255)
}

/** Absorción K por canal y dispersión S de una pintura (Kubelka-Munk de dos coeficientes). */
private class KS(val r: Double, val g: Double, val b: Double, val s: Double)

private fun ksOf(p: Paint): KS {
    val argb = p.argb
    val lab = p.lab
    val scatter = kotlin.math.exp(lab.l / 18.0)
    val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
    val blackBoost = if (chroma < 10) 1 + 20 * (1 - lab.l / 100).pow(4) else 1.0
    fun k(c: Int): Double {
        val refl = srgbToLinear(c).coerceIn(0.002, 0.998)
        return (1 - refl) * (1 - refl) / (2 * refl) * scatter * blackBoost
    }
    return KS(k(((argb shr 16) and 0xFF).toInt()), k(((argb shr 8) and 0xFF).toInt()), k((argb and 0xFF).toInt()), scatter)
}

private fun reflectance(ks: Double): Double = 1 + ks - sqrt(ks * ks + 2 * ks)

private fun mixArgb(ks: List<KS>, w: IntArray): Int {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var t = 0.0
    for (i in ks.indices) {
        r += ks[i].r * w[i]; g += ks[i].g * w[i]; b += ks[i].b * w[i]; t += ks[i].s * w[i]
    }
    val rr = linearToSrgb(reflectance(r / t))
    val gg = linearToSrgb(reflectance(g / t))
    val bb = linearToSrgb(reflectance(b / t))
    return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
}

private fun argbToLab(argb: Int): Lab = hexToLab(String.format("#%06X", argb and 0xFFFFFF))

private val PAIR_RATIOS = listOf(1 to 1, 2 to 1, 1 to 2, 3 to 1, 1 to 3, 4 to 1, 1 to 4, 3 to 2, 2 to 3, 5 to 1, 1 to 5, 6 to 1, 1 to 6)

/**
 * Hasta [limit] recetas para acercarse a [target] usando [inventory].
 * Solo usa pinturas opacas (las metálicas, tintas y contrastes no se mezclan igual).
 */
fun findMixes(target: Lab, inventory: List<Paint>, limit: Int = 3): List<MixRecipe> {
    // Solo acrílicos al agua opacos: las bases con alcohol, laca, esmalte u óleo no se mezclan con ellos,
    // y las gamas con diluyente propio cambian su acabado si se mezclan con otras
    val paints = inventory.filter {
        it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" && chemistryOf(it) == Chemistry.WATER_ACRYLIC
    }.distinctBy { it.id }
    if (paints.size < 2) return emptyList()

    // Candidatas: las más cercanas al objetivo y los neutros (sirven para aclarar u oscurecer)
    val byDistance = paints.sortedBy { deltaE2000(target, it.lab) }
    val neutrals = paints.filter { sqrt(it.lab.a * it.lab.a + it.lab.b * it.lab.b) < 8 }
        .sortedBy { it.lab.l }
        .let { n -> if (n.size <= 6) n else n.take(3) + n.takeLast(3) }
    val cand = (byDistance.take(45) + neutrals).distinctBy { it.id }
    val ks = cand.map { ksOf(it) }

    val results = ArrayList<MixRecipe>()
    // Una sola pintura, como referencia
    for (i in cand.indices) {
        results.add(MixRecipe(listOf(MixPart(cand[i], 1)), cand[i].argb.toInt(), deltaE2000(target, cand[i].lab)))
    }
    // Pares
    for (i in cand.indices) for (j in i + 1 until cand.size) {
        var bestD = Double.MAX_VALUE
        var bestR = PAIR_RATIOS[0]
        var bestArgb = 0
        for (r in PAIR_RATIOS) {
            val argb = mixArgb(listOf(ks[i], ks[j]), intArrayOf(r.first, r.second))
            val d = deltaE2000(target, argbToLab(argb))
            if (d < bestD) { bestD = d; bestR = r; bestArgb = argb }
        }
        results.add(MixRecipe(listOf(MixPart(cand[i], bestR.first), MixPart(cand[j], bestR.second)), bestArgb, bestD))
    }
    // Tríos con las más prometedoras
    val top = results.filter { it.parts.size == 2 }.sortedBy { it.distance }.take(12)
        .flatMap { r -> r.parts.map { it.paint } }.distinctBy { it.id }.take(10)
    val topKs = top.map { ksOf(it) }
    for (i in top.indices) for (j in i + 1 until top.size) for (k in j + 1 until top.size) {
        var bestD = Double.MAX_VALUE
        var bestW = intArrayOf(1, 1, 1)
        var bestArgb = 0
        for (a in 1..4) for (b in 1..4) for (c in 1..4) {
            if (a == b && b == c && a > 1) continue
            val w = intArrayOf(a, b, c)
            val argb = mixArgb(listOf(topKs[i], topKs[j], topKs[k]), w)
            val d = deltaE2000(target, argbToLab(argb))
            if (d < bestD) { bestD = d; bestW = w; bestArgb = argb }
        }
        results.add(
            MixRecipe(
                listOf(MixPart(top[i], bestW[0]), MixPart(top[j], bestW[1]), MixPart(top[k], bestW[2])),
                bestArgb, bestD,
            )
        )
    }

    // Preferir recetas simples: un trío debe mejorar claramente a un par
    fun score(r: MixRecipe) = r.distance + when (r.parts.size) { 1 -> 0.0; 2 -> 0.6; else -> 2.0 }
    val bestSingle = results.filter { it.parts.size == 1 }.minOfOrNull { it.distance } ?: Double.MAX_VALUE
    return results
        .filter { it.parts.size > 1 && it.distance < bestSingle - 1.5 }
        .sortedBy { score(it) }
        .fold(ArrayList<MixRecipe>()) { acc, r ->
            // Evitar recetas casi iguales (mismas pinturas)
            val ids = r.parts.map { it.paint.id }.toSet()
            if (acc.none { it.parts.map { p -> p.paint.id }.toSet() == ids }) acc.add(r)
            acc
        }
        .take(limit)
}
