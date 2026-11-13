package com.ricardochr.estantepinturas

/** Acabado al secar. [label] se traduce al mostrarse. */
enum class Finish(val label: String) {
    MATE("Mate"),
    SATINADO("Satinado"),
    BRILLANTE("Brillante"),
    DESCONOCIDO("Sin dato"),
}

/**
 * Acabado de una pintura y de dónde sale el dato:
 *  - [perPaint] true: lo dice la propia pintura (su nombre, su línea "Gloss/Flat" o su código, como Tamiya X / XF).
 *  - false: es el acabado general de su línea (Model Color, Pro Acryl…), no un dato del bote.
 */
data class FinishInfo(val finish: Finish, val perPaint: Boolean)

// i18n:off — palabras de los nombres y líneas de las marcas (en inglés y español), no son textos de la interfaz
private val SATIN_RX = Regex("""\b(semi[- ]?gloss|satin|satinado|semibrillo|semi[- ]?brillante)\b""")
private val GLOSS_RX = Regex("""\b(gloss|glossy|brillo|brillante|lucido|candy)\b""")
private val MATT_RX = Regex("""\b(flat|matt|matte|mate|ultra[- ]?matt?e?|dead flat)\b""")

/**
 * Acabado general de una línea, solo donde la marca (o una reseña independiente) lo dice para toda la
 * línea. Las líneas donde cada color tiene su acabado (Mr. Hobby, Tamiya TS/LP, Ammo, DSPIAE) o donde no
 * se encontró un dato confiable quedan "Sin dato" en vez de suponer.
 */
private fun lineFinish(brand: String, line: String): Finish? {
    val bk = brandKey(brand)
    return when {
        // Vallejo: Model Color, Model Air, Game Color y Game Air son mate; Mecha Color, satinado.
        // Panzer Aces y Nocturna usan la fórmula de Model Color. Hobby Paint (spray) es mate.
        brand.contains("vallejo") && (line.startsWith("model color") || line.startsWith("model air") ||
            line.startsWith("game color") || line.startsWith("game air") || line.startsWith("panzer aces") ||
            line.startsWith("nocturna") || line.startsWith("hobby paint")) -> Finish.MATE
        brand.contains("vallejo") && line.startsWith("mecha color") -> Finish.SATINADO
        // Pro Acryl (núcleo y Signature): mate
        bk == "proacryl" && (line == "pro acryl" || line.startsWith("signature")) -> Finish.MATE
        // AK: 3rd Gen y Real Colors (laca) secan mate
        bk == "akinteractive" && (line.startsWith("3rd gen") || line.contains("(3rd gen)") || line.startsWith("real colors")) -> Finish.MATE
        // Scale75 Scalecolor: mate
        brand.contains("scale75") && line.startsWith("scalecolor") -> Finish.MATE
        // Citadel: Base, Layer y Shade se venden como acabado mate
        bk == "citadel" && (line.startsWith("base") || line.startsWith("layer") || line.startsWith("shade")) -> Finish.MATE
        // Army Painter: Warpaints/Fanatic con un ligero satinado (reseñas); Colour Primer, mate
        bk == "armypainter" && (line == "warpaints" || line == "warpaints fanatic") -> Finish.SATINADO
        bk == "armypainter" && line.startsWith("colour primer") -> Finish.MATE
        // Green Stuff World Acrylic Colors, Two Thin Coats y Mission Models: mate
        bk == "greenstuffworld" && line.startsWith("acrylic colors") -> Finish.MATE
        brand.contains("duncan rhodes") && line.startsWith("wave") -> Finish.MATE
        brand.contains("mission models") && line == "mission models" -> Finish.MATE
        // Kaleido Colorworks (Gaahleri): Mecha con brillo de 85 GU, Auto de alto brillo y Primary brillantes
        // (sus "Matt …" se detectan por el nombre antes de llegar aquí)
        brand.contains("kaleido") && (line.startsWith("mecha") || line.startsWith("auto") || line.startsWith("primary")) -> Finish.BRILLANTE
        else -> null
    }
}

/**
 * Tamiya TS (laca en aerosol): acabado de cada color según el nombre oficial del producto en
 * tamiya.de ("TS-8 Italian Red Gloss", "TS-48 Gunship Grey Flat", "TS-38 Gun Metal Semi Gloss").
 * Los que la tienda no nombra con su acabado quedan "Sin dato".
 */
private val TAMIYA_TS: Map<Int, Finish> = buildMap {
    listOf(7, 8, 9, 11, 12, 13, 14, 16, 17, 21, 26, 30, 34, 41, 43, 44, 45, 47, 49, 64, 65, 71, 76, 84, 95, 98, 101)
        .forEach { put(it, Finish.BRILLANTE) }
    listOf(29, 38, 42, 79, 100).forEach { put(it, Finish.SATINADO) }
    listOf(2, 3, 4, 6, 27, 28, 32, 33, 46, 48, 61, 62, 63, 66, 77, 80).forEach { put(it, Finish.MATE) }
}

private val TS_RX = Regex("^TS-?(\\d+)$", RegexOption.IGNORE_CASE)
private fun tamiyaTs(code: String): Finish? = TS_RX.find(code.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { TAMIYA_TS[it] }
// i18n:on

private val finishCache = java.util.concurrent.ConcurrentHashMap<String, FinishInfo>()

fun finishOf(p: Paint): FinishInfo = finishCache.getOrPut(p.id) {
    val brand = norm(p.brand)
    val line = norm(p.line)
    val name = norm(p.name)
    // "Matt Cexwish" es el nombre de un pintor (serie Signature de Pro Acryl), no un acabado
    val text = "$line $name".replace("matt cexwish", "cexwish")
    when {
        SATIN_RX.containsMatchIn(text) -> FinishInfo(Finish.SATINADO, true)
        GLOSS_RX.containsMatchIn(text) -> FinishInfo(Finish.BRILLANTE, true)
        MATT_RX.containsMatchIn(text) -> FinishInfo(Finish.MATE, true)
        // Tamiya: X = brillante, XF = mate (acrílicos y esmaltes)
        brand.contains("tamiya") && Regex("^XF-?\\d", RegexOption.IGNORE_CASE).containsMatchIn(p.code) -> FinishInfo(Finish.MATE, true)
        brand.contains("tamiya") && Regex("^X-?\\d", RegexOption.IGNORE_CASE).containsMatchIn(p.code) -> FinishInfo(Finish.BRILLANTE, true)
        brand.contains("tamiya") && tamiyaTs(p.code) != null -> FinishInfo(tamiyaTs(p.code)!!, true)
        else -> lineFinish(brand, line)?.let { FinishInfo(it, false) } ?: FinishInfo(Finish.DESCONOCIDO, false)
    }
}

/** Texto para la ficha: "Brillante" o "Mate (según su línea)". */
fun finishText(p: Paint): String {
    val f = finishOf(p)
    if (f.finish == Finish.DESCONOCIDO) return tr("Sin dato")
    return tr(f.finish.label) + if (f.perPaint) "" else tr(" (según su línea)")
}

/** Propiedades que cambian cómo se comporta una pintura en una mezcla, además de su color. */
enum class Special(val label: String) {
    FLUOR("fluorescente"),
    TRANSPARENTE("transparente"),
    METAL("metálica"),
    EFECTO("de efecto (textura, técnica o especial)"),
    IRIDISCENTE("perlada o iridiscente"),
}

// i18n:off — palabras de los nombres y líneas de las marcas
fun specialOf(p: Paint): Special? {
    val s = norm("${p.line} ${p.name}")
    return when {
        Regex("""\b(fluor|fluo|fluorescent|fluorescente|neon|glow)\b""").containsMatchIn(s) -> Special.FLUOR
        p.type == "Metálica" || typeGroup(p.type) == "metal" -> Special.METAL
        Regex("""\b(pearl|perlad|iridescent|iridiscente|interference|chameleon|camaleon|colorshift|color shift|candy)\b""").containsMatchIn(s) -> Special.IRIDISCENTE
        typeGroup(p.type) == "transparente" ||
            Regex("""\b(transparent|transparente|translucent|translucida|clear|glaze|veladura|ink|tinta)\b""").containsMatchIn(s) -> Special.TRANSPARENTE
        // Por línea (Technical, Effects, Diorama FX…) o por palabras que en el nombre indican textura o efecto
        Regex("""\b(technical|effects?|efectos?|special fx|diorama fx|weathering|pigments?|pigmentos?|texture|textura|crackle|craquelado)\b""").containsMatchIn(norm(p.line)) ||
            Regex("""\b(texture|textura|crackle|craquelado|for the blood god|nurgle.s rot|water effect)\b""").containsMatchIn(norm(p.name)) ||
            norm(p.line) == "dry" -> Special.EFECTO
        else -> null
    }
}
// i18n:on

/**
 * ¿Sirve para mezclar colores como pintura "normal"? Solo opacas comunes de acrílico al agua.
 * Fluorescentes, transparentes, metálicas, perladas y de efecto pierden su efecto (o se lo pasan
 * a toda la mezcla) y el color que da el cálculo no es el real.
 */
fun mixable(p: Paint): Boolean =
    p.hasColor && typeGroup(p.type) == "opaca" && p.type != "Imprimación" && p.type != "Aerógrafo" &&
        chemistryOf(p) == Chemistry.WATER_ACRYLIC && specialOf(p) == null

/** ¿Se pueden mezclar entre sí sin cambiar el acabado? Brillante con mate da un satinado irregular. */
fun finishesCompatible(a: Paint, b: Paint): Boolean {
    val fa = finishOf(a).finish
    val fb = finishOf(b).finish
    return !((fa == Finish.BRILLANTE && fb == Finish.MATE) || (fa == Finish.MATE && fb == Finish.BRILLANTE))
}

/** Todas las pinturas de la lista se pueden mezclar entre sí (tipo, base química y acabado). */
fun mixableTogether(paints: List<Paint>): Boolean =
    paints.all { mixable(it) } && paints.indices.all { i -> (i + 1 until paints.size).all { j -> finishesCompatible(paints[i], paints[j]) } }
