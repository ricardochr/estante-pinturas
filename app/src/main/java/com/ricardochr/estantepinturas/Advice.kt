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

/**
 * Una zona de la figura (piel, chaqueta roja, pelo…): varios colores de la imagen que son la misma
 * pintura iluminada distinto. [base] es el tono principal; [roles] dice qué es cada color
 * ("Sombra profunda", "Sombra", "Base", "Luz", "Punto de luz").
 */
data class PaletteFamily(val name: String, val members: List<Int>, val base: Int, val roles: Map<Int, String>) {
    val shadows: List<Int> get() = members.filter { roles[it]?.startsWith("Sombra") == true }
    val lights: List<Int> get() = members.filter { roles[it] == "Luz" || roles[it] == "Punto de luz" }
}

data class PaletteAdvice(val plan: List<String>, val zones: List<ZoneAdvice>, val families: List<PaletteFamily> = emptyList())

private fun chroma(c: ColorCluster) = sqrt(c.a * c.a + c.b * c.b)

private fun hueDeg(c: ColorCluster): Double {
    val h = Math.toDegrees(atan2(c.b, c.a))
    return if (h < 0) h + 360 else h
}

/** Nombre del color en español a partir de CIELAB. */
fun colorName(c: ColorCluster): String {
    val ch = chroma(c)
    if (ch < 6) return when {
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
    // Misma familia: tono parecido y saturación comparable (una piel no es la chaqueta roja de al lado)
    return hueDiff(a, b) < 24 && maxOf(ca, cb) / minOf(ca, cb) < 1.75
}

fun buildAdvice(palette: ImagePalette, matches: List<PaletteMatch>, inventory: List<Paint>, kit: GearKit = GearKit(emptyList(), null)): PaletteAdvice {
    val cs = palette.clusters
    val zones = palette.zones
    val n = cs.size
    if (n == 0 || zones.size != n) return PaletteAdvice(emptyList(), emptyList())

    // Familias: colores del mismo tono que se tocan en la figura = escala de luz y sombra
    // Zonas: colores del mismo tono que se tocan en la figura = la misma pintura con luz y sombra.
    // Se unen primero los pares más parecidos, y un color solo entra a una zona si es compatible con
    // TODOS sus miembros (así la sombra de la piel no arrastra a la chaqueta roja a la misma zona).
    val family = IntArray(n) { it }
    fun find(i: Int): Int { var x = i; while (family[x] != x) x = family[x]; return x }
    val candidates = ArrayList<Triple<Int, Int, Double>>()
    for (i in 0 until n) for (j in i + 1 until n) {
        val touch = zones[i].adjacency[j]
        val minSize = minOf(cs[i].fraction, cs[j].fraction) * palette.width * palette.height
        if (sameFamily(cs[i], cs[j]) && touch > 0.02 * minSize && abs(cs[i].l - cs[j].l) > 4) {
            val ca = chroma(cs[i]).coerceAtLeast(1.0)
            val cb = chroma(cs[j]).coerceAtLeast(1.0)
            val score = hueDiff(cs[i], cs[j]) + 10 * kotlin.math.ln(maxOf(ca, cb) / minOf(ca, cb))
            candidates.add(Triple(i, j, score))
        }
    }
    for ((i, j, _) in candidates.sortedBy { it.third }) {
        val ri = find(i)
        val rj = find(j)
        if (ri == rj) continue
        val gi = (0 until n).filter { find(it) == ri }
        val gj = (0 until n).filter { find(it) == rj }
        if (gi.all { x -> gj.all { y -> sameFamily(cs[x], cs[y]) } }) family[ri] = rj
    }
    val groups = (0 until n).groupBy { find(it) }.values.map { g -> g.sortedBy { cs[it].l } }

    val contrastPaints = inventory.filter { it.hasColor && (it.type == "Contraste" || it.type == "Wash" || it.type == "Tinta") }

    val advice = arrayOfNulls<ZoneAdvice>(n)
    val families = ArrayList<PaletteFamily>()
    for (g in groups) {
        // Base = el color que más área ocupa; los más oscuros son sombras y los más claros, luces
        val baseIdx = g.maxByOrNull { cs[it].fraction } ?: continue
        val darker = g.filter { cs[it].l < cs[baseIdx].l - 3 }.sortedBy { cs[it].l }
        val lighter = g.filter { cs[it].l > cs[baseIdx].l + 3 }.sortedBy { cs[it].l }
        val roles = HashMap<Int, String>()
        for (i in g) roles[i] = "Base"
        darker.forEachIndexed { k, i -> roles[i] = if (darker.size >= 2 && k == 0) "Sombra profunda" else "Sombra" }
        lighter.forEachIndexed { k, i -> roles[i] = if (lighter.size >= 2 && k == lighter.size - 1) "Punto de luz" else "Luz" }
        val baseName = colorName(cs[baseIdx])
        val skinLike = g.any { i -> val h = hueDeg(cs[i]); h in 25.0..75.0 && chroma(cs[i]) in 12.0..45.0 && cs[i].l > 45 }
        val name = when {
            g.size > 1 && skinLike && baseName in setOf("rojo", "naranja", "café") -> "piel o tono cálido"
            g.size > 1 && baseName.startsWith("gris") -> "gris"
            else -> baseName
        }
        families.add(PaletteFamily(name, g.sortedBy { cs[it].l }, baseIdx, roles))
        for (i in g) {
            val c = cs[i]
            val z = zones[i]
            val ch = chroma(c)
            val isDetail = g.size == 1 && (c.fraction < 0.03 || z.edgeRatio > 0.45)
            val isLarge = c.fraction > 0.12 && z.edgeRatio < 0.3
            val role = when {
                g.size == 1 -> if (isDetail) "Detalle" else "Color base"
                else -> roles[i] ?: "Base"
            }
            val brush = when {
                isDetail -> "Pincel de detalle talla 0 o 00, con poca pintura en la punta."
                isLarge -> "Pincel redondo talla 2–4 (o aerógrafo) para cubrir parejo."
                else -> "Pincel redondo talla 1–2."
            }
            val tips = ArrayList<String>()
            when (role) {
                "Sombra", "Sombra profunda" -> {
                    tips.add("Va en huecos, pliegues y zonas que no reciben luz del grupo $name.")
                    val wash = contrastPaints
                        .map { it to deltaE2000(c.lab, it.lab) }
                        .filter { it.second < 18 }
                        .minByOrNull { it.second }?.first
                    if (wash != null) tips.add("Puedes hacerla con un lavado o veladura de ${wash.name} (${wash.brand}) sobre la base.")
                    tips.add("Estilo anime/2D: píntala con borde definido, sin difuminar. Realista: veladuras finas para fundirla.")
                }
                "Luz", "Punto de luz" -> {
                    tips.add("Va en bordes y caras que miran hacia arriba o hacia la luz; deja ver el tono de abajo.")
                    if (z.texture > 3.5) tips.add("La zona tiene textura: aplica la luz con pincel seco (drybrush) usando un pincel viejo de cerdas firmes.")
                    else tips.add("Capas finas y diluidas (consistencia de leche) para que la transición se vea suave.")
                }
                "Base" -> tips.add("Es el color principal de la zona $name: cúbrela entera con él y luego agrega sombra y luz encima.")
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
    if (kit.hasAirbrush) {
        val wide = kit.wide
        plan.add(
            "Con aerógrafo: imprima con tu ${wide?.let { gearLabel(it) } ?: "aerógrafo"} a 25–30 psi. Zenital: todo en negro y luego blanco solo desde arriba a 45°; " +
                "las zonas grandes (en cada tarjeta lo indico) se pueden basear y degradar con aerógrafo, y los detalles se terminan a pincel."
        )
    } else {
        plan.add("Si tienes aerógrafo, regístralo en el menú → Mi equipo: te diré en qué zonas usarlo, con qué boquilla, presión y dilución.")
    }
    plan.add("Orden: de lo más interno a lo más externo (piel y cara, luego ropa, luego armadura y equipo). Así si te sales, lo tapas con la capa siguiente.")
    plan.add("Cada zona tiene su \"Paso a paso\": base → sombra → luz → punto de luz. Termina una zona antes de pasar a la siguiente.")
    val details = (0 until n).filter { advice[it]?.role == "Detalle" }
    if (details.isNotEmpty()) plan.add("Detalles al final con pincel fino: " + details.joinToString(", ") { colorName(cs[it]) } + ".")
    val varnish = inventory.firstOrNull { it.type == "Barniz" }
    plan.add("Barniz mate para proteger" + (varnish?.let { " (tienes ${it.name})" } ?: "") + "; si algo debe brillar (ojos, metal, joyas), barniz brillante solo ahí.")

    families.sortByDescending { f -> f.members.sumOf { cs[it].fraction } }
    return PaletteAdvice(plan, advice.map { it ?: ZoneAdvice("Color base", "", "Pincel redondo talla 1–2.", emptyList()) }, families)
}

/** Cómo conseguir la sombra o la luz, en palabras: pintura tuya, mezcla o compra. */
fun toneText(p: Paint?, mix: ToneMix?, buy: Paint?, darker: Boolean): String = when {
    p != null -> p.name
    mix != null -> "mezcla ${mix.text}"
    buy != null -> "${buy.name} de ${buy.brand} (no la tienes; está en sugerencias para comprar)"
    darker -> "la base con una gota de un lavado oscuro o de su color complementario"
    else -> "la base con un poco de blanco o blanco hueso, agregado de a poco"
}

/**
 * Pasos concretos para pintar una zona completa: base, sombra(s), luz y punto de luz, usando los
 * colores de la imagen que forman esa zona (o, si la imagen no los tiene, una pintura, mezcla o
 * compra sugerida), con cuánto diluir y dónde va cada capa.
 */
fun familySteps(f: PaletteFamily, matches: List<PaletteMatch>, zones: List<ZoneAdvice>, kit: GearKit = GearKit(emptyList(), null)): List<String> {
    val mb = matches.getOrNull(f.base) ?: return emptyList()
    val baseAdvice = zones.getOrNull(f.base)
    val baseName = mb.best?.paint?.name ?: "un color parecido al de la muestra"
    val brush = baseAdvice?.brush ?: "Pincel redondo talla 1–2."
    fun paintOf(i: Int): String? = matches.getOrNull(i)?.let { m ->
        m.best?.paint?.name ?: (m.shadowMix ?: m.highlightMix)?.let { "mezcla ${it.text}" } ?: m.toBuy?.let { "${it.name} de ${it.brand} (para comprar)" }
    }
    val shadowMembers = f.members.filter { f.roles[it] == "Sombra" }
    val deepMembers = f.members.filter { f.roles[it] == "Sombra profunda" }
    val lightMembers = f.members.filter { f.roles[it] == "Luz" }
    val topMembers = f.members.filter { f.roles[it] == "Punto de luz" }
    val wash = mb.washShadow
    val ownShadow = shadowMembers.firstNotNullOfOrNull { paintOf(it) }
    val shadow = when {
        ownShadow != null -> ownShadow
        mb.shadow != null -> mb.shadow.name
        wash != null -> null
        else -> toneText(null, mb.shadowMix, mb.shadowBuy, darker = true)
    }
    val light = lightMembers.firstNotNullOfOrNull { paintOf(it) } ?: toneText(mb.highlight, mb.highlightMix, mb.highlightBuy, darker = false)
    val steps = ArrayList<String>()
    if (f.members.size == 1 && baseAdvice?.role == "Detalle") {
        steps.add("Base: $baseName, diluida a consistencia de leche. $brush Una o dos capas, sin salirte de la zona.")
        steps.add("Si el detalle es grande, marca la parte de abajo con $shadow.")
        steps.add("Un solo toque de luz con $light en el borde o punto más alto (o un punto blanco si brilla, como ojos o gemas).")
        return steps
    }
    steps.add(
        "Base: $baseName (${mb.best?.paint?.type?.lowercase() ?: "opaca"}) en toda la zona. Dilúyela con un poco de agua hasta que fluya como leche " +
            "y aplica 2–3 capas finas, dejando secar entre capas, hasta que quede pareja. $brush"
    )
    // Zonas grandes y lisas: el aerógrafo da bases parejas y degradados suaves
    val zoneFraction = f.members.sumOf { matches.getOrNull(it)?.cluster?.fraction ?: 0.0 }
    val isBigZone = zoneFraction >= 0.06 && baseAdvice?.role != "Detalle" && (baseAdvice?.tips?.none { it.contains("textura") } ?: true)
    if (kit.hasAirbrush && isBigZone) {
        airbrushStep(kit, mb.best?.paint, shadow ?: wash?.name ?: "la sombra", light)?.let { steps.add(it) }
    }
    mb.quick?.let { q ->
        steps.add(
            "Atajo: en vez de base + sombra + luz, puedes usar ${q.name} (${q.brand}, contraste/speedpaint) en una sola capa sobre imprimación blanca o gris claro: " +
                "se acumula sola en los huecos. Después solo agrega luces opacas en lo más alto."
        )
    }
    if (shadow != null) {
        steps.add(
            "Sombra: $shadow (opaca) en los huecos, pliegues, debajo de lo que sobresale y en el lado contrario a la luz. " +
                "Dilúyela más (1 de pintura por 2–3 de agua, una veladura) y pasa varias capas que se vayan juntando hacia lo más hundido. " +
                "Descarga el pincel en papel antes de tocar la figura." +
                (wash?.let { " Más rápido: un lavado de ${it.name} (${it.type.lowercase()}) sobre la base seca hace lo mismo." } ?: "")
        )
    } else if (wash != null) {
        steps.add(
            "Sombra con lavado: ${wash.name} (${wash.type.lowercase()}, ${wash.brand}) sobre la base ya seca. Aplícalo sin diluir o apenas diluido, " +
                "con el pincel cargado, para que corra a los huecos y pliegues; quita con un pincel limpio y seco lo que se acumule en zonas planas. " +
                "Déjalo secar bien (mínimo 30 minutos) antes de la luz."
        )
    }
    deepMembers.firstNotNullOfOrNull { paintOf(it) }?.let {
        steps.add("Sombra profunda: $it solo en lo más hundido y en las uniones (entre piernas, bajo la falda, axilas), sobre la sombra anterior.")
    }
    steps.add(
        "Luz: $light en las caras que miran hacia arriba y hacia la luz (rodillas, muslos, nariz, pómulos si es piel). " +
            "Capas finas que cubran menos área que la base, para que se vea la transición."
    )
    val top = topMembers.firstNotNullOfOrNull { paintOf(it) }
    steps.add(
        if (top != null) "Punto de luz: $top solo en las aristas y puntos más altos, con la punta del pincel."
        else "Punto de luz (opcional): mezcla la luz con un poco de blanco y marca solo las aristas y puntos más altos con la punta del pincel."
    )
    return steps
}
