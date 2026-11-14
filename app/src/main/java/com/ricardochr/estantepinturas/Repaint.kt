package com.ricardochr.estantepinturas

import kotlin.math.sqrt

/**
 * Repintar zonas o colores de la imagen de la Paleta con una técnica de pintura. Cada técnica cambia
 * los colores como lo haría en la figura real:
 *  - Cambiar color: la base pasa a ser el color elegido; sombras y luces conservan su diferencia.
 *  - Veladura: una capa transparente tiñe todo hacia el color, oscureciendo un poco; la claridad casi no cambia.
 *  - Sombra: oscurece y tiñe sobre todo las partes oscuras (lavado o capa diluida en los huecos).
 *  - Luces: aclara y tiñe sobre todo las partes claras (capas finas o pincel seco en las aristas).
 */
enum class RepaintKind(val label: String, val help: String) {
    CAMBIAR("Cambiar color", "La base toma el color elegido; sombras y luces mantienen su contraste."),
    VELADURA("Veladura", "Capa transparente muy diluida: tiñe todo hacia el color y unifica, sin tapar sombras ni luces."),
    SOMBRA("Sombra", "Lavado o capa diluida en los huecos: oscurece y tiñe sobre todo lo más oscuro."),
    LUCES("Luces", "Capas finas o pincel seco en las aristas: aclara y tiñe sobre todo lo más claro."),
}

/** Una técnica aplicada, para el plan de pintura del proyecto. */
data class RepaintStep(val kind: RepaintKind, val members: Set<Int>, val target: Lab, val amount: Float, val label: String)

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

/**
 * Colores nuevos para los colores [members] de la imagen ([current]: color actual de cada índice).
 * [groups] reparte la selección en zonas con su color base (para Cambiar color); los colores sueltos
 * forman su propio grupo con base en el que más ocupa.
 */
fun repaintLabs(
    kind: RepaintKind,
    current: Map<Int, Lab>,
    groups: List<Pair<Int, List<Int>>>,
    target: Lab,
    amount: Float,
): Map<Int, Lab> {
    val out = HashMap<Int, Lab>()
    val t = amount.toDouble().coerceIn(0.0, 1.0)
    for ((base, members) in groups) {
        val labs = members.mapNotNull { m -> current[m]?.let { m to it } }
        if (labs.isEmpty()) continue
        val b = current[base] ?: labs.first().second
        val lMin = labs.minOf { it.second.l }
        val lMax = labs.maxOf { it.second.l }
        val range = (lMax - lMin).coerceAtLeast(1.0)
        when (kind) {
            RepaintKind.CAMBIAR -> {
                val bc = sqrt(b.a * b.a + b.b * b.b).coerceAtLeast(4.0)
                for ((m, c) in labs) {
                    val ratio = (sqrt(c.a * c.a + c.b * c.b) / bc).coerceIn(0.45, 1.4)
                    out[m] = Lab((target.l + (c.l - b.l)).coerceIn(3.0, 97.0), target.a * ratio, target.b * ratio)
                }
            }
            RepaintKind.VELADURA -> for ((m, c) in labs) {
                // La veladura nunca aclara: si el color es más oscuro, oscurece un poco
                val dl = (minOf(target.l, c.l) - c.l) * 0.35
                out[m] = Lab((c.l + dl * t).coerceIn(2.0, 98.0), lerp(c.a, target.a, t * 0.8), lerp(c.b, target.b, t * 0.8))
            }
            RepaintKind.SOMBRA -> for ((m, c) in labs) {
                // Más efecto en lo oscuro (huecos), poco en lo claro
                val w = if (labs.size == 1) 1.0 else 0.25 + 0.75 * (lMax - c.l) / range
                val dark = minOf(target.l, c.l - 6.0)
                out[m] = Lab(lerp(c.l, dark, t * w).coerceIn(2.0, 98.0), lerp(c.a, target.a, t * w * 0.7), lerp(c.b, target.b, t * w * 0.7))
            }
            RepaintKind.LUCES -> for ((m, c) in labs) {
                val w = if (labs.size == 1) 1.0 else 0.25 + 0.75 * (c.l - lMin) / range
                val light = maxOf(target.l, c.l + 6.0)
                out[m] = Lab(lerp(c.l, light, t * w).coerceIn(2.0, 98.0), lerp(c.a, target.a, t * w * 0.6), lerp(c.b, target.b, t * w * 0.6))
            }
        }
    }
    return out
}

/**
 * Pintura tuya más adecuada para la técnica: para veladura se prefieren transparentes (tintas,
 * veladuras, contrastes), para sombra lavados y contrastes, para luces y cambio de color opacas.
 * Si no tienes una cercana, la más parecida del catálogo para comprar.
 */
fun paintForRepaint(kind: RepaintKind, target: Lab, inventory: List<Paint>, catalog: List<Paint>): Pair<Paint, Boolean>? {
    fun ok(p: Paint): Boolean = p.hasColor && p.quality != "n" && p.quality != "d"
    fun preferred(p: Paint): Boolean = when (kind) {
        RepaintKind.VELADURA -> typeGroup(p.type) == "transparente" || p.type == "Contraste" || specialOf(p) == Special.TRANSPARENTE
        RepaintKind.SOMBRA -> typeGroup(p.type) == "transparente"
        else -> typeGroup(p.type) == "opaca" && specialOf(p) == null
    }
    val own = inventory.filter(::ok)
    val best = (own.filter(::preferred).ifEmpty { own }).minByOrNull { deltaE2000(it.lab, target) }
    if (best != null && deltaE2000(best.lab, target) < 12) return best to true
    val buy = catalog.filter { ok(it) && preferred(it) }.minByOrNull { deltaE2000(it.lab, target) }
    return when {
        buy != null && (best == null || deltaE2000(buy.lab, target) < deltaE2000(best.lab, target) - 3) -> buy to false
        best != null -> best to true
        else -> null
    }
}

/** Paso del plan para una técnica, con la pintura sugerida. */
fun repaintStepText(s: RepaintStep, paint: Paint?, owned: Boolean): String {
    val p = paint?.let { if (owned) "${it.name} (${it.brand})" else tr("{} de {} (para comprar)", it.name, it.brand) } ?: tr("un color parecido")
    val layers = when {
        s.amount < 0.35f -> tr("1 capa")
        s.amount < 0.7f -> tr("2 capas")
        else -> tr("3 o más capas")
    }
    return when (s.kind) {
        RepaintKind.CAMBIAR -> tr("Color nuevo en {}: {} como base, con su sombra y luz como indica la zona.", s.label, p)
        RepaintKind.VELADURA -> tr("Veladura sobre {}: {} muy diluida (1 parte por 4–6 de agua o medium), {} finas sobre la pintura seca, dejando secar entre capas.", s.label, p, layers)
        RepaintKind.SOMBRA -> tr("Sombra en {}: {} como lavado o capa diluida en los huecos y pliegues, {}. Quita el exceso de las zonas planas.", s.label, p, layers)
        RepaintKind.LUCES -> tr("Luces en {}: {} en capas finas (o pincel seco en texturas) en aristas y partes altas, {}.", s.label, p, layers)
    }
}
