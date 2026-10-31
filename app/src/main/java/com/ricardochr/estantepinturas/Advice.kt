package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Consejos de pintura a partir del análisis de colores de la imagen.
 *
 * No reconoce qué es cada parte (pelo, piel, metal); deduce por el color y por cómo se reparte
 * en la figura: tamaño del área, si son líneas o detalles finos, si la superficie tiene textura
 * y si el color forma una escala de luz y sombra con otros tonos parecidos que lo tocan.
 */
data class ZoneAdvice(
    val role: String,          // "Base", "Sombra", "Luz", "Detalle"…
    val group: String,         // "azul", "blanco"…
    val brush: String,
    val tips: List<String>,
)

data class PaletteAdvice(val plan: List<String>, val zones: List<ZoneAdvice>)

private fun chroma(c: ColorCluster) = sqrt(c.a * c.a + c.b * c.b)

private fun hueDeg(c: ColorCluster): Double {
    val h = Math.toDegrees(atan2(c.b, c.a))
    return if (h < 0) h + 360 else h
}

/** Nombre del color en español a partir de CIELAB. */
fun colorName(c: ColorCluster): String {
    val ch = chroma(c)
    if (ch < 10) return when {
        c.l > 88 -> "blanco"
        c.l > 65 -> "gris claro"
        c.l > 38 -> "gris"
        c.l > 18 -> "gris oscuro"
        else -> "negro"
    }
    val h = hueDeg(c)
    val base = when {
        h < 20 || h >= 345 -> "magenta"
        h < 45 -> "rojo"
        h < 70 -> if (c.l < 45) "café" else "naranja"
        h < 105 -> if (c.l < 50) "ocre" else "amarillo"
        h < 165 -> "verde"
        h < 230 -> "turquesa"
        h < 290 -> "azul"
        h < 320 -> "violeta"
        else -> "magenta"
    }
    return base
}

private fun hueDiff(a: ColorCluster, b: ColorCluster): Double {
    val d = abs(hueDeg(a) - hueDeg(b))
    return if (d > 180) 360 - d else d
}

private fun sameFamily(a: ColorCluster, b: ColorCluster): Boolean {
    val ca = chroma(a)
    val cb = chroma(b)
    if (ca < 12 && cb < 12) return true                     // neutros: escala de grises
    if (ca < 12 || cb < 12) return false
    return hueDiff(a, b) < 28
}

fun buildAdvice(palette: ImagePalette, matches: List<PaletteMatch>, inventory: List<Paint>): PaletteAdvice {
    val cs = palette.clusters
    val zones = palette.zones
    val n = cs.size
    if (n == 0 || zones.size != n) return PaletteAdvice(emptyList(), emptyList())

    // Familias: colores del mismo tono que se tocan en la figura = escala de luz y sombra
    val family = IntArray(n) { it }
    fun find(i: Int): Int { var x = i; while (family[x] != x) x = family[x]; return x }
    for (i in 0 until n) for (j in i + 1 until n) {
        val touch = zones[i].adjacency[j]
        val minSize = minOf(cs[i].fraction, cs[j].fraction) * palette.width * palette.height
        if (sameFamily(cs[i], cs[j]) && touch > 0.02 * minSize && abs(cs[i].l - cs[j].l) > 6) {
            family[find(i)] = find(j)
        }
    }
    val groups = (0 until n).groupBy { find(it) }.values.map { g -> g.sortedBy { cs[it].l } }

    val contrastPaints = inventory.filter { it.hasColor && (it.type == "Contraste" || it.type == "Wash" || it.type == "Tinta") }

    val advice = arrayOfNulls<ZoneAdvice>(n)
    for (g in groups) {
        val name = colorName(cs[g.last()]).let { if (g.size > 1 && it.startsWith("gris")) "gris" else it }
        for ((rank, i) in g.withIndex()) {
            val c = cs[i]
            val z = zones[i]
            val ch = chroma(c)
            val isDetail = c.fraction < 0.03 || z.edgeRatio > 0.45
            val isLarge = c.fraction > 0.12 && z.edgeRatio < 0.3
            val role = when {
                g.size == 1 -> if (isDetail) "Detalle" else "Color base"
                rank == 0 -> "Sombra"
                rank == g.size - 1 -> "Luz"
                else -> "Tono medio"
            }
            val brush = when {
                isDetail -> "Pincel de detalle talla 0 o 00, con poca pintura en la punta."
                isLarge -> "Pincel redondo talla 2–4 (o aerógrafo) para cubrir parejo."
                else -> "Pincel redondo talla 1–2."
            }
            val tips = ArrayList<String>()
            when (role) {
                "Sombra" -> {
                    tips.add("Va en huecos, pliegues y zonas que no reciben luz del grupo $name.")
                    val wash = contrastPaints
                        .map { it to deltaE2000(c.lab, it.lab) }
                        .filter { it.second < 18 }
                        .minByOrNull { it.second }?.first
                    if (wash != null) tips.add("Puedes hacerla con un lavado o veladura de ${wash.name} (${wash.brand}) sobre la base.")
                    tips.add("Estilo anime/2D: píntala con borde definido, sin difuminar. Realista: veladuras finas para fundirla.")
                }
                "Luz" -> {
                    tips.add("Va en bordes y caras que miran hacia arriba o hacia la luz; deja ver el tono de abajo.")
                    if (z.texture > 3.5) tips.add("La zona tiene textura: aplica la luz con pincel seco (drybrush) usando un pincel viejo de cerdas firmes.")
                    else tips.add("Capas finas y diluidas (consistencia de leche) para que la transición se vea suave.")
                }
                "Tono medio" -> tips.add("Es el color principal del grupo $name: cúbrelo primero y luego agrega sombra y luz encima.")
                "Color base" -> tips.add("Color plano: 2–3 capas finas cubren mejor que una gruesa.")
                "Detalle" -> tips.add("Píntalo al final, después de bases, sombras y luces, para no ensuciarlo.")
            }
            when {
                c.l > 85 && ch < 10 ->
                    tips.add("El blanco cubre mal: empieza con un gris muy claro y sube a blanco en 2–3 capas; las sombras en gris azulado o lila se ven más limpias que en gris puro.")
                c.l < 20 && ch < 12 ->
                    tips.add("Un negro puro se ve plano: mézclalo con un poco de azul o violeta y marca los bordes con gris oscuro.")
                ch > 55 ->
                    tips.add("Color muy saturado: aplícalo sobre una base blanca o muy clara para que brille; tintas y pinturas de contraste dan más intensidad.")
            }
            if (ch < 12 && c.l in 30.0..75.0 && g.size >= 3 && isDetail) {
                tips.add("Si es metal: usa un metálico, o haz NMM (metal no metálico) con saltos marcados de gris oscuro a blanco.")
            }
            if (z.texture > 3.5 && role != "Luz") {
                tips.add("Superficie con textura: un lavado oscuro marca los relieves y el pincel seco los resalta.")
            }
            advice[i] = ZoneAdvice(role, name, brush, tips)
        }
    }

    // ---- plan general
    val plan = ArrayList<String>()
    val lightShare = cs.filter { it.l > 65 }.sumOf { it.fraction }
    val saturatedShare = cs.filter { chroma(it) > 45 }.sumOf { it.fraction }
    val primer = inventory.filter { it.type == "Imprimación" }
    val primerColor = when {
        lightShare > 0.35 || saturatedShare > 0.3 -> "blanca"
        cs.filter { it.l < 30 }.sumOf { it.fraction } > 0.4 -> "negra"
        else -> "gris"
    }
    val ownedPrimer = primer.firstOrNull { p ->
        val l = p.lab.l
        when (primerColor) { "blanca" -> l > 80; "negra" -> l < 25; else -> l in 25.0..80.0 }
    }
    plan.add(
        "Imprimación $primerColor" + (ownedPrimer?.let { " (tienes ${it.name})" } ?: "") +
            ". Truco para ver luces y sombras: imprima en negro y luego rocía blanco solo desde arriba (zenital); lo que quede claro es donde van las luces."
    )
    val bases = groups.mapNotNull { g ->
        val main = g.maxByOrNull { cs[it].fraction } ?: return@mapNotNull null
        if (cs[main].fraction < 0.05) null else main
    }.sortedByDescending { cs[it].fraction }
    if (bases.isNotEmpty()) {
        plan.add("Bases, de la zona más grande a la más chica: " + bases.joinToString(", ") { i ->
            colorName(cs[i]) + (matches.getOrNull(i)?.best?.paint?.name?.let { " ($it)" } ?: "")
        } + ".")
    }
    if (groups.any { it.size > 1 }) plan.add("Sombras y luces por grupo de color: primero la sombra, después la luz, siempre en capas finas.")
    val details = (0 until n).filter { advice[it]?.role == "Detalle" }
    if (details.isNotEmpty()) plan.add("Detalles al final con pincel fino: " + details.joinToString(", ") { colorName(cs[it]) } + ".")
    val varnish = inventory.firstOrNull { it.type == "Barniz" }
    plan.add("Barniz mate para proteger" + (varnish?.let { " (tienes ${it.name})" } ?: "") + "; si algo debe brillar (ojos, metal, joyas), barniz brillante solo ahí.")

    return PaletteAdvice(plan, advice.map { it ?: ZoneAdvice("Color base", "", "Pincel redondo talla 1–2.", emptyList()) })
}
