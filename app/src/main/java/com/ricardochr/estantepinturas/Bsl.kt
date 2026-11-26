package com.ricardochr.estantepinturas

/**
 * Sistema BSL (Base, Shadow, Light) de Vallejo Game Color.
 *
 * Fuentes:
 *  - Tríadas: hoja "Vallejo Triads" que compartió Ricardo (32 tríadas luz / base / sombra).
 *  - Familias: contenido del set Color BSL System (72.183, 12 grupos de 4) y de los blísteres de 4 colores
 *    de Game Color (72.376–72.389), ordenadas de la más clara a la más oscura.
 */
data class BslTriad(val light: String, val base: String, val shadow: String) {
    val codes get() = listOf(light, base, shadow)
}

data class BslFamily(val name: String, val codes: List<String>, val note: String = "", val inBigSet: Boolean = true)

enum class BslRole(val label: String) { LIGHT("Luz"), BASE("Base"), SHADOW("Sombra") }

/** Código del set completo de Vallejo con las 12 familias. */
const val BSL_SET_CODE = "72.183"

val BSL_TRIADS: List<BslTriad> = listOf(
    BslTriad("72.001", "72.101", "72.098"), // Dead White · Off-White · Elfic Flesh
    BslTriad("72.101", "72.098", "72.034"), // Off-White · Elfic Flesh · Bone White
    BslTriad("72.003", "72.100", "72.107"), // Pale Flesh · Rosy Flesh · Athena Skin
    BslTriad("72.003", "72.107", "72.108"), // Pale Flesh · Athena Skin · Succubus Skin
    BslTriad("72.099", "72.004", "72.071"), // Skin Tone · Elf Skin Tone · Barbarian Skin
    BslTriad("72.109", "72.005", "72.007"), // Toxic Yellow · Moon Yellow · Gold Yellow
    BslTriad("72.005", "72.006", "72.007"), // Moon Yellow · Sun Yellow · Gold Yellow
    BslTriad("72.005", "72.006", "72.038"), // Moon Yellow · Sun Yellow · Scrofulous Brown
    BslTriad("72.110", "72.008", "72.009"), // Sunset Orange · Orange Fire · Hot Orange
    BslTriad("72.010", "72.012", "72.111"), // Bloody Red · Scarlet Red · Nocturnal Red
    BslTriad("72.106", "72.011", "72.112"), // Scarlet Blood · Gory Red · Evil Red
    BslTriad("72.013", "72.014", "72.113"), // Squid Pink · Warlord Purple · Deep Magenta
    BslTriad("72.114", "72.076", "72.016"), // Lustful Purple · Alien Purple · Royal Purple
    BslTriad("72.076", "72.016", "72.116"), // Alien Purple · Royal Purple · Midnight Purple
    BslTriad("72.076", "72.015", "72.116"), // Alien Purple · Hexed Lichen · Midnight Purple
    BslTriad("72.096", "72.119", "72.024"), // Verdigris · Aquamarine · Turquoise
    BslTriad("72.119", "72.024", "72.120"), // Aquamarine · Turquoise · Abyssal Turquoise
    BslTriad("72.117", "72.022", "72.019"), // Elfic Blue · Ultramarine Blue · Night Blue
    BslTriad("72.118", "72.023", "72.021"), // Sunrise Blue · Electric Blue · Magic Blue
    BslTriad("72.023", "72.021", "72.020"), // Electric Blue · Magic Blue · Imperial Blue
    BslTriad("72.121", "72.025", "72.026"), // Ghost Green · Foul Green · Jade Green
    BslTriad("72.025", "72.026", "72.027"), // Foul Green · Jade Green · Scurvy Green
    BslTriad("72.122", "72.032", "72.030"), // Bile Green · Scorpy Green · Goblin Green
    BslTriad("72.030", "72.029", "72.028"), // Goblin Green · Sick Green · Dark Green
    BslTriad("72.030", "72.123", "72.028"), // Goblin Green · Angel Green · Dark Green
    BslTriad("72.035", "72.031", "72.145"), // Dead Flesh · Camouflage Green · Dirty Grey
    BslTriad("72.042", "72.115", "72.124"), // Parasite Brown · Grunge Brown · Gorgon Brown
    BslTriad("72.066", "72.044", "72.045"), // Tan · Dark Fleshtone · Charred Brown
    BslTriad("72.047", "72.102", "72.048"), // Wolf Grey · Steel Grey · Sombre Grey
    BslTriad("72.095", "72.102", "72.048"), // Glacier Blue · Steel Grey · Sombre Grey
    BslTriad("72.148", "72.049", "72.155"), // Warm Grey · Stonewall Grey · Charcoal
    BslTriad("72.050", "72.155", "72.051"), // Neutral Grey · Charcoal · Black
)

val BSL_FAMILIES: List<BslFamily> = listOf(
    BslFamily("Piel cálida", listOf("72.003", "72.100", "72.107", "72.108")),
    BslFamily("Piel", listOf("72.098", "72.099", "72.004", "72.071")),
    BslFamily("Amarillo", listOf("72.109", "72.005", "72.006", "72.007")),
    BslFamily("Naranja", listOf("72.110", "72.008", "72.009", "72.106"), "Incluye el rojo: Scarlet Blood es su tono más oscuro."),
    BslFamily("Magenta", listOf("72.013", "72.014", "72.113", "72.112")),
    BslFamily("Violeta", listOf("72.114", "72.076", "72.015", "72.116")),
    BslFamily("Azul", listOf("72.118", "72.021", "72.020", "72.019")),
    BslFamily("Turquesa", listOf("72.096", "72.119", "72.024", "72.120")),
    BslFamily("Verde frío", listOf("72.121", "72.025", "72.026", "72.027")),
    BslFamily("Verde", listOf("72.122", "72.032", "72.123", "72.028")),
    BslFamily("Negro", listOf("72.001", "72.049", "72.155", "72.051"), "Escala de grises de blanco a negro."),
    BslFamily(
        "Multiusos", listOf("72.011", "72.042", "72.124", "72.045"),
        "No es una escala de un solo color: un rojo y tres cafés para cuero, madera, sangre o para ensuciar y oscurecer mezclas.",
    ),
    BslFamily("Rojo", listOf("72.110", "72.106", "72.011", "72.112"), "Blíster 72.377.", inBigSet = false),
    BslFamily("Ultramar", listOf("72.095", "72.117", "72.022", "72.016"), "Blíster 72.389.", inBigSet = false),
    BslFamily("Blanco", listOf("72.001", "72.101", "72.034", "72.061"), "Blíster 72.386.", inBigSet = false),
)

/** Pinturas de Vallejo Game Color por código ("72.005"). */
class BslIndex(paints: Collection<Paint>) {
    private val byCode: Map<String, Paint> = paints.asSequence()
        .filter { isGameColor(it) }
        .groupBy { it.code.trim() }
        // Si hay duplicados (catálogo y agregadas), gana la que no es de la línea descontinuada
        .mapValues { (_, l) -> l.firstOrNull { !it.line.contains("descontinuada", true) } ?: l.first() }

    operator fun get(code: String): Paint? = byCode[code]
}

private val GAME_CODE = Regex("""^72\.\d{3}$""")

fun isGameColor(p: Paint): Boolean =
    brandKey(p.brand) == brandKey("Vallejo") && GAME_CODE.matches(p.code.trim()) && p.line.startsWith("Game Color", ignoreCase = true)

/** Tríadas en las que aparece [code] y el papel que cumple en cada una. */
fun bslTriadsWith(code: String): List<Pair<BslTriad, BslRole>> = BSL_TRIADS.mapNotNull { t ->
    when (code) {
        t.light -> t to BslRole.LIGHT
        t.base -> t to BslRole.BASE
        t.shadow -> t to BslRole.SHADOW
        else -> null
    }
}

fun bslFamiliesWith(code: String): List<BslFamily> = BSL_FAMILIES.filter { code in it.codes }

/**
 * Sombra y luz de una base según BSL, tomadas de lo que tienes. Entre las tríadas donde [base] es la base,
 * gana la que tiene más pinturas tuyas. Devuelve null si la pintura no es base de ninguna tríada.
 */
fun bslTonesFor(base: Paint, inventory: Collection<Paint>): Pair<Paint?, Paint?>? {
    if (!isGameColor(base)) return null
    val code = base.code.trim()
    val triads = BSL_TRIADS.filter { it.base == code }
    if (triads.isEmpty()) return null
    val mine = BslIndex(inventory)
    val best = triads.maxBy { t -> (if (mine[t.shadow] != null) 1 else 0) + (if (mine[t.light] != null) 1 else 0) }
    return mine[best.shadow] to mine[best.light]
}

/**
 * Escala extendida: une las tríadas que se encadenan (la luz de una es la base de otra) y devuelve los códigos
 * de la más oscura a la más clara que pasan por [code].
 */
fun bslRamp(code: String): List<String> {
    val down = HashMap<String, String>() // base -> sombra
    val up = HashMap<String, String>() // base -> luz
    for (t in BSL_TRIADS) {
        down.putIfAbsent(t.base, t.shadow)
        up.putIfAbsent(t.base, t.light)
    }
    val ramp = ArrayDeque<String>().apply { add(code) }
    var c = code
    while (true) { val n = down[c] ?: break; if (n in ramp) break; ramp.addFirst(n); c = n }
    c = code
    while (true) { val n = up[c] ?: break; if (n in ramp) break; ramp.addLast(n); c = n }
    return ramp.toList()
}
