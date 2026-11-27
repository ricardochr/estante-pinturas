package com.ricardochr.estantepinturas

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sistemas de tríadas por marca (en la app se llama "Sistema BSL").
 *
 * Oficiales:
 *  - Vallejo Game Color, BSL (Base, Shadow, Light). Tríadas: hoja "Vallejo Triads" que compartió Ricardo
 *    (32 tríadas luz / base / sombra). Familias: set Color BSL System (72.183, 12 grupos de 4) y blísteres
 *    de 4 colores (72.376–72.389).
 *  - The Army Painter Warpaints Fanatic, Flexible Triads: 27 familias de 6 colores de oscuro a claro.
 *    Los códigos WP3001–WP3162 van en bloques de 6 en el mismo orden que las familias (WP3001–WP3006 es
 *    Black & Greys, WP3049–WP3054 Deep Greens…). Army Painter recomienda tres colores vecinos para
 *    transiciones suaves; la app arma esas tríadas.
 * Para las demás marcas, la app calcula familias y tríadas por color (no son oficiales).
 *
 * Las claves de tríadas y familias son códigos en los sistemas oficiales e ids de pintura en los calculados.
 * Las familias van siempre de la más clara a la más oscura.
 */
data class BslTriad(val light: String, val base: String, val shadow: String) {
    val codes get() = listOf(light, base, shadow)
}

data class BslFamily(
    val name: String,
    val codes: List<String>,
    val note: String = "",
    val inBigSet: Boolean = true,
    /** Papel de cada color de la familia, en el mismo orden que [codes] (null: solo de clara a oscura). */
    val labels: List<String>? = null,
)

enum class BslRole(val label: String) { LIGHT("Luz"), BASE("Base"), SHADOW("Sombra") }

class TriadSystem(
    val id: String,
    val brand: String,
    /** Nombre del sistema; lleva nombres de marca y línea, que no se traducen. */
    val title: String,
    /** Texto corto en español (se traduce con trData). */
    val about: String,
    val official: Boolean,
    val triads: List<BslTriad>,
    val families: List<BslFamily>,
    /** Nombre del set que reúne todo el sistema, si existe ("set Color BSL System (72.183)"). */
    val fullSet: String? = null,
    val matches: (Paint) -> Boolean,
    val keyOf: (Paint) -> String = { it.code.trim() },
) {
    fun index(paints: Collection<Paint>) = BslIndex(paints, this)

    fun triadsWith(key: String): List<Pair<BslTriad, BslRole>> = triads.mapNotNull { t ->
        when (key) {
            t.light -> t to BslRole.LIGHT
            t.base -> t to BslRole.BASE
            t.shadow -> t to BslRole.SHADOW
            else -> null
        }
    }

    fun familiesWith(key: String): List<BslFamily> = families.filter { key in it.codes }

    /** Colores que cubre el sistema (los del set completo si lo hay). */
    val allKeys: List<String> by lazy { families.filter { it.inBigSet }.flatMap { it.codes }.distinct() }

    /**
     * Escala extendida: une las tríadas que se encadenan (la luz de una es la base de otra) y devuelve las
     * claves de la más oscura a la más clara que pasan por [key].
     */
    fun ramp(key: String): List<String> {
        val down = HashMap<String, String>()
        val up = HashMap<String, String>()
        for (t in triads) {
            down.putIfAbsent(t.base, t.shadow)
            up.putIfAbsent(t.base, t.light)
        }
        val ramp = ArrayDeque<String>().apply { add(key) }
        var c = key
        while (true) { val n = down[c] ?: break; if (n in ramp) break; ramp.addFirst(n); c = n }
        c = key
        while (true) { val n = up[c] ?: break; if (n in ramp) break; ramp.addLast(n); c = n }
        return ramp.toList()
    }

    /**
     * Sombra y luz de una base, tomadas de lo que tienes. Entre las tríadas donde [base] es la base gana la
     * que tiene más pinturas tuyas. Devuelve null si la pintura no es base de ninguna tríada del sistema.
     */
    fun tonesFor(base: Paint, inventory: Collection<Paint>): Pair<Paint?, Paint?>? {
        if (!matches(base)) return null
        val key = keyOf(base)
        val options = triads.filter { it.base == key }
        if (options.isEmpty()) return null
        val mine = index(inventory)
        val best = options.maxBy { t -> (if (mine[t.shadow] != null) 1 else 0) + (if (mine[t.light] != null) 1 else 0) }
        return mine[best.shadow] to mine[best.light]
    }
}

/** Pinturas de un sistema por clave (código o id). */
class BslIndex(paints: Collection<Paint>, val system: TriadSystem = VALLEJO_BSL) {
    private val byKey: Map<String, Paint> = paints.asSequence()
        .filter { system.matches(it) }
        .groupBy { system.keyOf(it) }
        // Si hay duplicados (catálogo y agregadas), gana la que no es de una línea descontinuada
        .mapValues { (_, l) -> l.firstOrNull { !it.line.contains("descontinuada", true) } ?: l.first() }

    operator fun get(key: String): Paint? = byKey[key]
}

// ---------------------------------------------------------------- Vallejo Game Color (BSL)

/** Código del set completo de Vallejo con las 12 familias. */
const val BSL_SET_CODE = "72.183"

private val GAME_CODE = Regex("""^72\.\d{3}$""")

fun isGameColor(p: Paint): Boolean =
    brandKey(p.brand) == brandKey("Vallejo") && GAME_CODE.matches(p.code.trim()) && p.line.startsWith("Game Color", ignoreCase = true)

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

val VALLEJO_BSL = TriadSystem(
    id = "vallejo-bsl",
    // i18n:off — nombres de marca y línea, no se traducen
    brand = "Vallejo",
    title = "Vallejo Game Color · BSL",
    // i18n:on
    about = "Base, Shadow, Light: Vallejo agrupa sus Game Color en familias de tonos que combinan. Toca un color para ver su ficha.",
    official = true,
    triads = BSL_TRIADS,
    families = BSL_FAMILIES,
    fullSet = "set Color BSL System (72.183)",
    matches = ::isGameColor,
)

// ---------------------------------------------------------------- The Army Painter Warpaints Fanatic

private val FANATIC_CODE = Regex("""^WP3(\d{3})$""")

/** Número de la pintura dentro de las 27 familias (1–162), o null si no es parte de ellas. */
private fun fanaticNumber(code: String): Int? =
    FANATIC_CODE.matchEntire(code.trim().uppercase())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..162 }

fun isFanaticTriadPaint(p: Paint): Boolean =
    brandKey(p.brand) == brandKey("The Army Painter") && p.line.startsWith("Warpaints Fanatic", ignoreCase = true) &&
        fanaticNumber(p.code) != null

/** Nombres de las 27 familias, en el orden de los códigos. */
private val FANATIC_FAMILY_NAMES = listOf(
    "Negro y grises", "Grises cálidos y blanco", "Azules grisáceos", "Azules pálidos intensos", "Azules vivos",
    "Azules verdosos profundos", "Turquesas", "Verdes azulados", "Verdes profundos", "Verdes vibrantes",
    "Verdes apagados", "Verdes oliva", "Cafés", "Ocres y canela", "Neutros claros", "Amarillos", "Naranjas",
    "Rojos cálidos", "Cafés rojizos", "Rojos fríos", "Rosas", "Púrpuras", "Magentas", "Violetas apagados",
    "Pieles rosadas", "Pieles cálidas", "Pieles oscuras",
)

private fun fanaticCode(n: Int) = "WP3" + n.toString().padStart(3, '0')

val FANATIC_FAMILIES: List<BslFamily> = FANATIC_FAMILY_NAMES.mapIndexed { i, name ->
    // Los códigos van de oscuro a claro; las familias se guardan de clara a oscura
    BslFamily(name, (6 downTo 1).map { fanaticCode(i * 6 + it) })
}

/** Tres colores vecinos de cada familia: oscuro (sombra), medio (base) y claro (luz). Cuatro tríadas por familia. */
val FANATIC_TRIADS: List<BslTriad> = (0 until 27).flatMap { f ->
    (1..4).map { i -> BslTriad(fanaticCode(f * 6 + i + 2), fanaticCode(f * 6 + i + 1), fanaticCode(f * 6 + i)) }
}

val FANATIC_FLEXIBLE = TriadSystem(
    id = "fanatic",
    // i18n:off — nombres de marca y línea, no se traducen
    brand = "The Army Painter",
    title = "Warpaints Fanatic · Flexible Triads",
    // i18n:on
    about = "Army Painter ordena Warpaints Fanatic en 27 familias de 6 colores, de oscuro a claro. Tres vecinos dan transiciones suaves; el más oscuro, uno del medio y el más claro dan el máximo contraste.",
    official = true,
    triads = FANATIC_TRIADS,
    families = FANATIC_FAMILIES,
    matches = ::isFanaticTriadPaint,
)

// ---------------------------------------------------------------- Vallejo True Metallic Metal

private val TMM_CODE = Regex("""^77\.(\d{3})$""")

fun isTrueMetallic(p: Paint): Boolean {
    val n = TMM_CODE.matchEntire(p.code.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return brandKey(p.brand) == brandKey("Vallejo") && n in 101..180 && p.line.startsWith("True Metallic Metal", ignoreCase = true)
}

// i18n:off — nombres de las familias de Vallejo (no se traducen)
/** Las 20 familias en el orden de sus códigos: Light 77.101–120, Base 121–140, Shade 141–160, Airbrush 161–180. */
private val TMM_FAMILY_NAMES = listOf(
    "Sterling Silver", "Radiant Yellow", "Imperial Gold", "Ancient Copper", "Forged Red", "Ruby Red", "Crimson Magenta",
    "Amethyst Purple", "Celestial Violet", "Ultramarine Blue", "Sapphire Blue", "Hydra Turquoise", "Beetle Green",
    "Dusken Green", "Amber Green", "Greenish Gold", "Arcane Gold", "Rusty Metal", "Aged Metal", "Obsidian Black",
)
// i18n:on

private fun tmmCode(n: Int) = "77.$n"

val TMM_FAMILIES: List<BslFamily> = TMM_FAMILY_NAMES.mapIndexed { i, name ->
    val k = i + 1
    BslFamily(
        name, listOf(tmmCode(100 + k), tmmCode(120 + k), tmmCode(140 + k), tmmCode(160 + k)),
        labels = listOf("Luz", "Base", "Sombra", "Aerógrafo"),
    )
}

val TMM_TRIADS: List<BslTriad> = (1..20).map { k -> BslTriad(tmmCode(100 + k), tmmCode(120 + k), tmmCode(140 + k)) }

val VALLEJO_TMM = TriadSystem(
    id = "vallejo-tmm",
    // i18n:off — nombres de marca y línea, no se traducen
    brand = "Vallejo TMM",
    title = "Vallejo True Metallic Metal · BSL",
    // i18n:on
    about = "BSL aplicado al metal: 20 familias, cada una con Light (metálica clara), Base (metálica), Shade (lavado satinado no metálico que no apaga el brillo) y Airbrush (la misma Base lista para aerógrafo).",
    official = true,
    triads = TMM_TRIADS,
    families = TMM_FAMILIES,
    fullSet = "estuche True Metallic Metal Full Collection (77.250)",
    matches = ::isTrueMetallic,
)

val OFFICIAL_SYSTEMS: List<TriadSystem> = listOf(VALLEJO_BSL, VALLEJO_TMM, FANATIC_FLEXIBLE)

// ---------------------------------------------------------------- funciones de siempre (Vallejo)

/** Tríadas en las que aparece [code] y el papel que cumple en cada una (Vallejo BSL). */
fun bslTriadsWith(code: String): List<Pair<BslTriad, BslRole>> = VALLEJO_BSL.triadsWith(code)

fun bslFamiliesWith(code: String): List<BslFamily> = VALLEJO_BSL.familiesWith(code)

fun bslRamp(code: String): List<String> = VALLEJO_BSL.ramp(code)

/** Sombra y luz según el sistema oficial de la marca de [base] (BSL, Flexible Triads), de lo que tienes. */
fun bslTonesFor(base: Paint, inventory: Collection<Paint>): Pair<Paint?, Paint?>? =
    OFFICIAL_SYSTEMS.firstOrNull { it.matches(base) }?.tonesFor(base, inventory)

// ---------------------------------------------------------------- marcas sin sistema oficial

private fun chromaOf(p: Paint) = sqrt(p.lab.a * p.lab.a + p.lab.b * p.lab.b)

private fun hueOf(p: Paint): Double {
    val h = Math.toDegrees(atan2(p.lab.b, p.lab.a))
    return if (h < 0) h + 360 else h
}

/** Grupo de color de una pintura, para armar familias calculadas. */
// i18n:off — claves internas; se traducen al mostrarlas con trData
private fun hueFamily(p: Paint): String {
    val c = chromaOf(p)
    val h = hueOf(p)
    return when {
        c < 9 -> "Neutros"
        c < 32 && h in 30.0..100.0 && p.lab.l < 70 -> "Cafés y tierras"
        h < 25 || h >= 350 -> "Rojo"
        h < 55 -> "Naranja"
        h < 100 -> "Amarillo"
        h < 140 -> "Verde amarillento"
        h < 190 -> "Verde"
        h < 230 -> "Turquesa"
        h < 290 -> "Azul"
        h < 325 -> "Violeta"
        else -> "Magenta"
    }
}
// i18n:on

private val HUE_FAMILY_ORDER = listOf(
    "Rojo", "Naranja", "Amarillo", "Verde amarillento", "Verde", "Turquesa", "Azul", "Violeta", "Magenta",
    "Cafés y tierras", "Neutros",
)

/** La sombra (o la luz) de [base] entre [pool]: más oscura (o clara), del mismo tono, la más cercana al tono ideal. */
private fun calcTone(base: Paint, pool: List<Paint>, darker: Boolean): Paint? {
    val b = base.lab
    val target = if (darker) Lab(b.l - 20, b.a * 0.95, b.b * 0.95 - 3) else Lab(b.l + 18, b.a * 0.85, b.b * 0.85 + 3)
    return pool.asSequence()
        .filter { it.id != base.id }
        .filter { if (darker) it.lab.l in (b.l - 32)..(b.l - 7) else it.lab.l in (b.l + 7)..(b.l + 32) }
        .map { it to deltaE2000(target, it.lab) }
        .filter { it.second < 16 }
        .minByOrNull { it.second }
        ?.first
}

/**
 * Sistema calculado por color para una marca que no publica tríadas: familias por tono (de clara a oscura) y,
 * para cada pintura tuya de la marca, la sombra y la luz más parecidas de la misma marca.
 * Solo usa las líneas de la marca que tienes y deja fuera las que ya cubre un sistema oficial.
 */
fun calculatedSystem(brand: String, catalog: Collection<Paint>, owned: Collection<Paint>): TriadSystem? {
    val key = brandKey(brand)
    fun usable(p: Paint) = brandKey(p.brand) == key && p.type == "Opaca" && p.hasColor && p.quality != "n" &&
        !p.line.contains("descontinuada", true) && OFFICIAL_SYSTEMS.none { it.matches(p) }
    val mine = owned.filter { usable(it) }
    if (mine.isEmpty()) return null
    val lines = mine.map { it.line }.toSet()
    val pool = (catalog.filter { usable(it) && it.line in lines } + mine).distinctBy { it.id }
    val families = pool.groupBy { hueFamily(it) }
        .filter { it.value.size >= 2 }
        .toList()
        .sortedBy { HUE_FAMILY_ORDER.indexOf(it.first) }
        .map { (name, ps) -> BslFamily(name, ps.sortedByDescending { it.lab.l }.map { it.id }) }
    val triads = mine.mapNotNull { base ->
        val sh = calcTone(base, pool, darker = true) ?: return@mapNotNull null
        val li = calcTone(base, pool, darker = false) ?: return@mapNotNull null
        BslTriad(li.id, base.id, sh.id)
    }.distinct()
    val ids = pool.map { it.id }.toHashSet()
    return TriadSystem(
        id = "calc-$key",
        brand = brand,
        title = brand + " · " + lines.sorted().joinToString(", "),
        about = tr("Esta marca no publica tríadas oficiales. Las familias agrupan sus pinturas por tono y las tríadas se calcularon por color a partir de las que tienes."),
        official = false,
        triads = triads,
        families = families,
        matches = { it.id in ids },
        keyOf = { it.id },
    )
}

/**
 * Sistemas para las marcas que tienes: los oficiales con al menos una pintura tuya y uno calculado por cada
 * otra marca con pinturas opacas. Los que más pinturas tuyas tienen van primero. Si no tienes ninguna, se
 * muestran los oficiales.
 */
fun triadSystemsFor(owned: Collection<Paint>, catalog: Collection<Paint>): List<TriadSystem> {
    val official = OFFICIAL_SYSTEMS.filter { s -> owned.any { s.matches(it) } }
    val brands = owned.filter { p -> p.type == "Opaca" && OFFICIAL_SYSTEMS.none { it.matches(p) } }.map { it.brand }.distinct()
    val calculated = brands.mapNotNull { calculatedSystem(it, catalog, owned) }
    val all = (official + calculated).sortedByDescending { s -> owned.count { s.matches(it) } }
    return all.ifEmpty { OFFICIAL_SYSTEMS }
}
