package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Para qué sirve una pintura recomendada. */
enum class RecKind(val label: String) {
    BASICS("Básicos que te faltan"),
    SHADOW("Para sombras"),
    LIGHT("Para luces"),
    WASH("Lavados"),
    METAL("Metálicos"),
}

/** Una pintura del catálogo que te conviene comprar y por qué. */
data class Recommendation(val paint: Paint, val kind: RecKind, val reason: String)

private fun chromaOf(c: Lab) = sqrt(c.a * c.a + c.b * c.b)

private fun hueOf(c: Lab): Double {
    val h = Math.toDegrees(atan2(c.b, c.a))
    return if (h < 0) h + 360 else h
}

private fun hueGap(h1: Double, h2: Double): Double {
    val d = abs(h1 - h2) % 360
    return if (d > 180) 360 - d else d
}

private fun labFromLch(l: Double, c: Double, hDeg: Double): Lab {
    val r = Math.toRadians(hDeg)
    return Lab(l, c * cos(r), c * sin(r))
}

/**
 * Revisa tu inventario y propone pinturas del catálogo para pintar con volumen:
 *  1. Básicos: blanco, negro, gris, blanco hueso, marrón oscuro, piel, un tono frío oscuro.
 *  2. Escala de valor: para cada color que tienes, una versión más oscura (sombra) y una más
 *     clara (luz) del mismo tono. La sombra se enfría un poco y la luz se calienta un poco,
 *     que es como se ve la luz natural.
 *  3. Lavados (marrón y negro) y metálicos (plata y oro).
 * Prefiere las marcas que más usas y colores con muestra medida.
 */
fun recommendPaints(owned: List<Paint>, catalog: List<Paint>, max: Int = 24): List<Recommendation> {
    val ownedIds = owned.map { it.id }.toHashSet()
    val mine = owned.filter { it.hasColor }
    val opaqueMine = mine.filter { typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    val washMine = mine.filter { typeGroup(it.type) == "transparente" }
    val metalMine = mine.filter { typeGroup(it.type) == "metal" }

    val brandCount = owned.groupingBy { brandKey(it.brand) }.eachCount()
    val topBrand = brandCount.maxByOrNull { it.value }?.key
    fun brandPenalty(p: Paint): Double {
        val k = brandKey(p.brand)
        return when {
            k == topBrand -> 0.0
            brandCount.containsKey(k) -> 2.5
            else -> 6.0
        }
    }

    val pool = catalog.filter { it.hasColor && it.quality != "n" && it.id !in ownedIds }
    val opaquePool = pool.filter { it.type == "Opaca" }
    val washPool = pool.filter { it.type == "Wash" || it.type == "Tinta" }
    val metalPool = pool.filter { it.type == "Metálica" }

    val chosen = LinkedHashMap<String, Recommendation>()

    fun pick(target: Lab, from: List<Paint>, tolerance: Double): Paint? {
        var best: Paint? = null
        var bestScore = Double.MAX_VALUE
        for (p in from) {
            if (chosen.containsKey(p.id)) continue
            val d = deltaE2000(target, p.lab)
            if (d > tolerance) continue
            val score = d + brandPenalty(p) + if (p.quality == "f" || p.quality == "d") 1.5 else 0.0
            if (score < bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    fun add(target: Lab, from: List<Paint>, tolerance: Double, kind: RecKind, reason: String) {
        val p = pick(target, from, tolerance) ?: return
        chosen[p.id] = Recommendation(p, kind, reason)
    }

    // ---- 1. Básicos
    data class Basic(val target: Lab, val covered: (Lab) -> Boolean, val reason: String)
    val basics = listOf(
        Basic(Lab(97.0, 0.0, 1.0), { it.l > 92 && chromaOf(it) < 7 },
            tr("Blanco puro: para los puntos de luz más altos y para aclarar cualquier color.")),
        Basic(Lab(12.0, 0.0, 0.0), { it.l < 18 && chromaOf(it) < 9 },
            tr("Negro: para las sombras más profundas, delineado y contraste.")),
        Basic(Lab(52.0, 0.0, -1.0), { it.l in 38.0..64.0 && chromaOf(it) < 8 },
            tr("Gris neutro: para escalas de gris y para bajar la saturación de un color sin cambiar su tono.")),
        Basic(Lab(90.0, 1.0, 11.0), { it.l > 82 && it.b in 5.0..22.0 && chromaOf(it) < 24 },
            tr("Blanco hueso: luces cálidas, más naturales que el blanco puro en piel, tela y pergamino.")),
        Basic(Lab(26.0, 9.0, 15.0), { it.l in 14.0..36.0 && chromaOf(it) in 6.0..32.0 && hueOf(it) in 30.0..95.0 },
            tr("Marrón oscuro: sombras cálidas y bases de cuero, madera y pelo.")),
        Basic(Lab(68.0, 14.0, 22.0), { it.l in 56.0..80.0 && it.a in 7.0..24.0 && it.b in 12.0..34.0 },
            tr("Tono de piel medio: la base para casi cualquier piel; se oscurece y aclara desde aquí.")),
        Basic(Lab(22.0, 12.0, -24.0), { it.l < 36 && it.b < -10 },
            tr("Azul-violeta oscuro: sombras frías. Mezclado con la base oscurece sin ensuciar como el negro.")),
    )
    for (b in basics) {
        if (opaqueMine.none { b.covered(it.lab) }) add(b.target, opaquePool, 12.0, RecKind.BASICS, b.reason)
    }

    // ---- 2. Escala de valor por color
    data class Gap(val base: Paint, val shadow: Boolean, val target: Lab)
    val gaps = ArrayList<Gap>()
    val chromatic = opaqueMine.filter { chromaOf(it.lab) >= 12 && it.lab.l in 22.0..88.0 }
    for (base in chromatic) {
        val c = chromaOf(base.lab)
        val h = hueOf(base.lab)
        fun hasStep(dMin: Double, dMax: Double): Boolean = opaqueMine.any { o ->
            if (o.id == base.id) return@any false
            val dl = o.lab.l - base.lab.l
            dl in dMin..dMax && hueGap(hueOf(o.lab), h) <= 30 && chromaOf(o.lab) >= c * 0.35
        }
        if (base.lab.l - 20 >= 12 && !hasStep(-38.0, -11.0)) {
            // La sombra se enfría: el tono gira un poco hacia el azul-violeta (≈ 300° en Lab)
            val toCool = ((300 - h + 540) % 360) - 180
            gaps.add(Gap(base, true, labFromLch(base.lab.l - 20, c * 0.9, h + toCool.coerceIn(-12.0, 12.0))))
        }
        if (base.lab.l + 17 <= 95 && !hasStep(11.0, 38.0)) {
            // La luz se calienta: el tono gira un poco hacia el amarillo (≈ 90° en Lab) y pierde saturación
            val toWarm = ((90 - h + 540) % 360) - 180
            gaps.add(Gap(base, false, labFromLch(base.lab.l + 17, c * 0.8, h + toWarm.coerceIn(-10.0, 10.0))))
        }
    }
    // Una recomendación por familia de tono (sectores de 30°) y tipo; primero las familias en las que tienes más pinturas
    val familySize = chromatic.groupingBy { (hueOf(it.lab) / 30).toInt() }.eachCount()
    gaps.groupBy { (hueOf(it.base.lab) / 30).toInt() to it.shadow }
        .entries
        .sortedByDescending { familySize[it.key.first] ?: 0 }
        .take(12)
        .forEach { (key, list) ->
            val g = list.maxByOrNull { chromaOf(it.base.lab) } ?: return@forEach
            val name = g.base.name
            if (key.second) {
                add(g.target, opaquePool, 10.0, RecKind.SHADOW, tr("Sombra para {}: mismo tono, más oscuro y un poco más frío.", name))
            } else {
                add(g.target, opaquePool, 10.0, RecKind.LIGHT, tr("Luz para {}: mismo tono, más claro y un poco más cálido.", name))
            }
        }

    // ---- 3. Lavados
    if (washMine.none { it.lab.l < 45 && chromaOf(it.lab) > 8 && hueOf(it.lab) in 35.0..100.0 }) {
        add(Lab(30.0, 8.0, 20.0), washPool, 14.0, RecKind.WASH, tr("Lavado marrón (sepia): sombras rápidas en piel, cuero, hueso y metal dorado."))
    }
    if (washMine.none { it.lab.l < 30 && chromaOf(it.lab) < 10 }) {
        add(Lab(16.0, 0.0, 0.0), washPool, 14.0, RecKind.WASH, tr("Lavado negro: marca los recovecos en metal plateado, armaduras y negros."))
    }

    // ---- 4. Metálicos
    if (metalMine.none { chromaOf(it.lab) < 9 }) {
        add(Lab(72.0, 0.0, 0.0), metalPool, 15.0, RecKind.METAL, tr("Plata: armas, armaduras y brillos metálicos."))
    }
    if (metalMine.none { it.lab.b > 20 }) {
        add(Lab(66.0, 6.0, 42.0), metalPool, 15.0, RecKind.METAL, tr("Oro: adornos, joyería y detalles nobles."))
    }

    val order = RecKind.values()
    return chosen.values.sortedBy { order.indexOf(it.kind) }.take(max)
}
