package com.ricardochr.estantepinturas

/**
 * Dilución y compatibilidad de cada pintura según su marca, su gama y su base química.
 *
 * Las proporciones se escriben siempre como "pintura : diluyente" (en gotas o volumen).
 * Cada guía dice de dónde sale: [Source.MAKER] = indicación del fabricante;
 * [Source.COMMUNITY] = práctica habitual entre pintores cuando el fabricante no da una cifra.
 */
enum class Source(val label: String) {
    MAKER("Indicación del fabricante"),
    COMMUNITY("Práctica habitual (el fabricante no da cifra)"),
}

data class DilutionUse(val use: String, val ratio: String, val detail: String)

data class DilutionGuide(
    val summary: String,
    val thinner: String,
    val uses: List<DilutionUse>,
    val notes: List<String>,
    val source: Source,
)

/** Base química: define con qué se puede mezclar y con qué se diluye. */
enum class Chemistry(val label: String, val mixesWith: String) {
    WATER_ACRYLIC(
        "Acrílico al agua",
        "Se mezcla con otros acrílicos al agua de cualquier marca (Vallejo, Army Painter, Pro Acryl, AK 3Gen, Citadel, Scale75…).",
    ),
    WATER_SPECIAL(
        "Acrílico al agua con diluyente propio",
        "Mézclala solo con pinturas de su misma gama y dilúyela solo con su diluyente; el agua o pinturas de otras marcas cambian su acabado.",
    ),
    ALCOHOL_ACRYLIC(
        "Acrílico con alcohol",
        "No la mezcles con acrílicos al agua (Vallejo, Army Painter…): se corta y hace grumos. Mézclala solo con su misma línea.",
    ),
    LACQUER(
        "Laca (solvente)",
        "Solo con lacas y su diluyente de laca. Nunca con acrílicos al agua. Usa mascarilla y ventilación.",
    ),
    ENAMEL(
        "Esmalte",
        "Solo con esmaltes y diluyente de esmalte (white spirit / aguarrás sin olor). Se puede aplicar sobre acrílico seco, no mezclar.",
    ),
    OIL(
        "Óleo",
        "Solo con óleos y diluyente inodoro. Se aplica sobre acrílico barnizado; no se mezcla con acrílico.",
    ),
}

fun chemistryOf(p: Paint): Chemistry {
    val brand = norm(p.brand)
    val line = norm(p.line)
    val name = norm(p.name)
    return when {
        line.contains("oleo") || line.contains("oil") || name.contains("oilbrusher") -> Chemistry.OIL
        line.contains("enamel") || name.contains("enamel") || line.contains("esmalte") -> Chemistry.ENAMEL
        brand.contains("tamiya") && (line.contains("lacquer") || line.contains("spray")) -> Chemistry.LACQUER
        brand.contains("tamiya") -> Chemistry.ALCOHOL_ACRYLIC
        brand.contains("mr. hobby") || brand.contains("mr hobby") -> when {
            line.contains("aqueous") -> Chemistry.ALCOHOL_ACRYLIC
            line.contains("acrysion") -> Chemistry.WATER_SPECIAL
            else -> Chemistry.LACQUER
        }
        brand.contains("humbrol") && !line.contains("acrylic") -> Chemistry.ENAMEL
        brand.contains("vallejo") && (line.contains("liquid") ) -> Chemistry.ALCOHOL_ACRYLIC
        brand.contains("vallejo") && line.contains("metal color") -> Chemistry.WATER_SPECIAL
        brand.contains("mission models") -> Chemistry.WATER_SPECIAL
        else -> Chemistry.WATER_ACRYLIC
    }
}

private fun g(
    summary: String,
    thinner: String,
    source: Source,
    vararg uses: DilutionUse,
    notes: List<String> = emptyList(),
) = DilutionGuide(summary, thinner, uses.toList(), notes, source)

private fun u(use: String, ratio: String, detail: String) = DilutionUse(use, ratio, detail)

fun dilutionFor(p: Paint): DilutionGuide {
    val brand = norm(p.brand)
    val line = norm(p.line)
    val type = p.type
    val chem = chemistryOf(p)

    // ---------------- bases no acrílicas
    when (chem) {
        Chemistry.OIL -> return g(
            "Óleo: se diluye con diluyente inodoro, nunca con agua.", "Diluyente inodoro (odorless thinner)", Source.COMMUNITY,
            u("Pincel", "Directa", "Para fundir colores; tarda horas en secar."),
            u("Veladura / filtro", "1:3 a 1:6", "Con diluyente inodoro."),
            u("Lavado", "1:8 a 1:15", "Con diluyente inodoro, para envejecido y filtros."),
            notes = listOf("Aplícalo sobre una capa de barniz para poder corregir sin dañar el acrílico de abajo."),
        )
        Chemistry.ENAMEL -> return g(
            "Esmalte: se diluye con diluyente de esmalte.", "White spirit o diluyente de esmalte", Source.COMMUNITY,
            u("Aerógrafo", "2:1 a 1:1", "Con diluyente de esmalte; necesita ventilación."),
            u("Lavado", "1:3 a 1:6", "Clásico para lavados de paneles sobre acrílico seco y barnizado brillante."),
            notes = listOf("El diluyente de esmalte no daña el acrílico seco, por eso se usa para lavados que se pueden limpiar."),
        )
        Chemistry.LACQUER -> return g(
            "Laca: se diluye con diluyente de laca.", "Diluyente de laca (Mr. Color Thinner / Leveling Thinner, Tamiya Lacquer Thinner)", Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", "Con Leveling Thinner queda muy lisa. Solo aerógrafo con juntas resistentes a solventes."),
            notes = listOf("Usa mascarilla para vapores orgánicos y buena ventilación."),
        )
        Chemistry.ALCOHOL_ACRYLIC -> {
            if (brand.contains("vallejo")) return g(
                "Liquid Metal / Liquid Gold: base alcohol.", "Su diluyente propio (Liquid Gold/Metal thinner) o alcohol isopropílico", Source.COMMUNITY,
                u("Pincel", "Directa", "Agita mucho; no la diluyas con agua."),
                notes = listOf("Se seca muy rápido y puede levantar el acrílico si repasas mucho."),
            )
            return g(
                "Acrílico con alcohol (Tamiya X/XF, Mr. Hobby Aqueous).", "Su diluyente propio (Tamiya X-20A, Mr. Color Leveling para Aqueous) o alcohol isopropílico", Source.COMMUNITY,
                u("Aerógrafo", "1:1 a 2:3", "Tamiya X-20A al 50–60%; muy fácil de aerografiar."),
                u("Pincel", "Directa", "Seca rápido; con pincel deja marcas, mejor en zonas pequeñas."),
                notes = listOf("Con agua se corta. No la mezcles con Vallejo, Army Painter o Pro Acryl."),
            )
        }
        else -> {}
    }

    // ---------------- por marca y gama
    val vallejo = brand.contains("vallejo")
    val ap = brand.contains("army painter")
    val pro = brand.contains("pro acryl")
    val ak = brand.startsWith("ak")
    val s75 = brand.contains("scale75")
    val citadel = brand.contains("citadel")

    return when {
        brand.contains("kaleido") -> g(
            "Kaleido viene lista para aerógrafo.", "Kaleido Retarder Thinner (KT102) o Airbrush Thinner", Source.MAKER,
            u("Aerógrafo", "Directa", "Agita 3–5 minutos (trae bola mezcladora). 15–30 PSI; 15 PSI o menos para detalle."),
            u("Aerógrafo detalle", "1:2", "Una de pintura por dos de retarder thinner, solo para líneas finas o si se seca en la punta."),
            notes = listOf("Gaahleri solo indica su propio diluyente; no hay información oficial sobre mezclarla con otras marcas."),
        )
        brand.contains("dspiae") -> g(
            "DSPIAE Pre-Mixed: al agua y ya diluida para aerógrafo.", "No necesita; si se espesa, unas gotas de diluyente de aerógrafo al agua", Source.MAKER,
            u("Aerógrafo", "Directa", "Lista para usar; agita bien."),
            u("Pincel", "Directa", "Muy fluida: varias capas finas."),
        )
        vallejo && line.contains("metal color") -> g(
            "Metal Color: lista para aerógrafo, con diluyente propio.", "Vallejo Airbrush Thinner 71.261 y Flow Improver 71.362", Source.MAKER,
            u("Aerógrafo", "Directa · máx. 3:1", "Directa; si hace falta, 1 de diluyente por 3 de pintura. 10–15 PSI. Agrega 2 gotas de flow improver por cada 10 de pintura."),
            u("Pincel", "Directa", "Funciona, pero rinde mejor con aerógrafo."),
            notes = listOf("Se puede aplicar sin imprimación; para aluminio brillante, base negra brillante (77.660).", "El agua no la arruina pero cambia sus propiedades: mejor su diluyente."),
        )
        vallejo && (line.contains("model air") || line.contains("game air") || line.contains("premium")) -> g(
            "Gama de aerógrafo: ya viene diluida.", "Vallejo Airbrush Thinner 71.161", Source.MAKER,
            u("Aerógrafo", "Directa · máx. 4:1", "Vallejo indica hasta 25% de diluyente como máximo."),
            u("Pincel", "Directa · máx. 2:1", "Hasta 50% de diluyente para pincel."),
            u("Veladura", "1:2 a 1:3", "Con Glaze Medium."),
        )
        vallejo && line.contains("xpress") -> g(
            "Xpress Color: pintura de contraste.", "Xpress Medium (72.448), no agua", Source.MAKER,
            u("Pincel", "Directa", "Una capa generosa sobre base clara."),
            u("Aclarar / veladura", "1:1 a 1:3", "Con Xpress Medium; el agua rompe el efecto."),
            u("Aerógrafo", "Directa a 2:1", "Con Xpress Medium si hace falta."),
        )
        vallejo && line.contains("wash") -> g(
            "Wash FX: lavado listo para usar.", "Agua o Wash FX Medium", Source.COMMUNITY,
            u("Pincel", "Directa", "Aplica en huecos y limpia el exceso de zonas planas."),
            u("Más suave", "1:1", "Con medio de lavado o agua."),
        )
        vallejo -> g(
            "Vallejo Model Color / Game Color: pintura de pincel.", "Vallejo Airbrush Thinner 71.161 + Flow Improver; para pincel, agua", Source.COMMUNITY,
            u("Aerógrafo", "1:1", "Vallejo no da cifra para estas gamas; la práctica habitual es partes iguales con su diluyente de aerógrafo y una gota de flow improver."),
            u("Pincel", "3:1 a 2:1", "Con agua; consistencia de leche."),
            u("Veladura", "1:3 a 1:5", "Con Glaze Medium."),
            u("Lavado", "1:5 a 1:8", "Con Wash Medium o agua con flow improver."),
        )
        pro && type == "Imprimación" -> g(
            "Pro Acryl Prime.", "Agua o diluyente de aerógrafo", Source.COMMUNITY,
            u("Aerógrafo", "Directa", "Lista para aerógrafo; capas finas a 20–25 PSI."),
            u("Pincel", "Directa", "Las versiones Brush-On se aplican tal cual."),
        )
        pro -> g(
            "Pro Acryl: muy pigmentada.", "Para veladura y lavado: Glaze & Wash Medium (MPAM-001); para aerógrafo: diluyente de aerógrafo o agua", Source.MAKER,
            u("Veladura", "1:4", "Monument indica 1 de pintura por 4 de Glaze & Wash Medium; más medio = más transparente."),
            u("Lavado", "1:4 + agua", "1 de pintura, 4 de medio y tanta agua como medio; menos agua = más control."),
            u("Aerógrafo", "1:1 a 1:2", "Monument no da cifra; por su pigmento suele necesitar partes iguales o más diluyente."),
            u("Pincel", "2:1 a 1:1", "Con agua; cubre muy bien aun diluida."),
            notes = listOf("Las proporciones de veladura y lavado son del fabricante; las de aerógrafo y pincel son práctica habitual."),
        )
        ap && (line.contains("air")) -> g(
            "Warpaints Air: lista para aerógrafo.", "Warpaints Airbrush Medium (AW2001)", Source.MAKER,
            u("Aerógrafo", "Directa", "Army Painter indica que no necesita diluyente."),
            u("Pincel", "Directa", "Fluida: varias capas finas."),
        )
        ap && (line.contains("speedpaint")) -> g(
            "Speedpaint: pintura de contraste.", "Speedpaint Medium (WP2090), no agua", Source.COMMUNITY,
            u("Pincel", "Directa", "Una capa generosa sobre base clara o zenital."),
            u("Aclarar / veladura", "1:1 a 1:3", "Con Speedpaint Medium."),
            u("Aerógrafo", "Directa", "Se suele aerografiar tal cual a baja presión."),
        )
        ap && line.contains("wash") -> g(
            "Warpaints Fanatic Wash.", "Fanatic Wash Medium", Source.MAKER,
            u("Pincel", "Directa", "Aplica en huecos."),
            u("Tinte sobre metal", "1:1", "Army Painter: mitad wash y mitad Wash Medium para teñir armadura metálica."),
        )
        ap -> g(
            "Warpaints / Fanatic: pintura de pincel.", "Agua para pincel; Warpaints Airbrush Medium para aerógrafo", Source.COMMUNITY,
            u("Pincel", "Directa a 3:1", "Army Painter indica diluir con agua limpia, sin cifra; unas gotas bastan."),
            u("Aerógrafo", "1:1", "Con Airbrush Medium; el fabricante no da proporción."),
            u("Veladura", "1:3 a 1:5", "Con medio de veladura o Speedpaint Medium."),
            u("Lavado", "1:5 a 1:8", "Con Wash Medium."),
        )
        ak && type == "Metálica" -> g(
            "AK 3Gen metálica.", "AK 3Gen Acrylic Thinner (AK11500)", Source.COMMUNITY,
            u("Aerógrafo", "1:4", "2 partes de pintura por 8 de diluyente, a unos 22 PSI."),
            u("Pincel", "Directa", "Agita bien."),
        )
        ak -> g(
            "AK 3rd Gen: acrílico al agua.", "AK 3Gen Acrylic Thinner (AK11500); retardante AK11231 opcional", Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 3:7", "Entre partes iguales y 7 de diluyente por 3 de pintura, a unos 22 PSI. Evita diluyentes con alcohol."),
            u("Pincel", "Directa a 3:1", "Con agua."),
            u("Veladura", "1:3 a 1:5", "Con AK Glaze Medium."),
            u("Lavado", "1:5 a 1:8", "Con agua y una gota de flow improver."),
            notes = listOf("Agrega el retardante o medio antes del diluyente."),
        )
        s75 && line.contains("instant") -> g(
            "Instant Colors: contraste.", "Su medio propio", Source.COMMUNITY,
            u("Pincel", "Directa", "Capa generosa sobre base clara."),
            u("Aclarar", "1:1 a 1:2", "Con medio, no agua."),
        )
        s75 -> g(
            "Scale75 Scalecolor: mate y muy cubriente.", "Scale75 Acrylic Airbrush Thinner", Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", "Es espesa; necesita bastante diluyente y algo de retardante (seca muy rápido)."),
            u("Pincel", "2:1 a 1:1", "Con agua."),
            u("Veladura", "1:4 a 1:6", "Con medio de veladura."),
            u("Lavado", "1:6 a 1:10", "Con medio de lavado."),
        )
        citadel && line.contains("contrast") -> g(
            "Citadel Contrast.", "Contrast Medium, no agua", Source.COMMUNITY,
            u("Pincel", "Directa", "Capa generosa sobre base clara."),
            u("Aclarar", "1:1 a 1:3", "Con Contrast Medium."),
        )
        citadel && line.contains("air") -> g(
            "Citadel Air: lista para aerógrafo.", "Citadel Air Caste Thinner", Source.COMMUNITY,
            u("Aerógrafo", "Directa", "Tal cual."),
        )
        citadel && (line.contains("shade") || line.contains("wash")) -> g(
            "Citadel Shade.", "Lahmian Medium o agua", Source.COMMUNITY,
            u("Pincel", "Directa", "Aplica en huecos."),
            u("Más suave", "1:1", "Con Lahmian Medium."),
        )
        citadel && line.contains("base") -> g(
            "Citadel Base: espesa y muy cubriente.", "Agua para pincel; diluyente de aerógrafo", Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", "Muy espesa; necesita bastante diluyente."),
            u("Pincel", "2:1", "Con agua; dos capas finas."),
        )
        citadel -> g(
            "Citadel Layer / Dry / Technical.", "Agua para pincel; diluyente de aerógrafo", Source.COMMUNITY,
            u("Aerógrafo", "1:1", "Con diluyente de aerógrafo."),
            u("Pincel", "3:1", "Con agua; las Dry se usan sin diluir para pincel seco."),
            u("Veladura", "1:3 a 1:5", "Con Lahmian Medium."),
        )
        else -> genericByType(p)
    }
}

/** Para marcas sin indicación específica: según el tipo de pintura. */
private fun genericByType(p: Paint): DilutionGuide {
    val t = "Diluyente de aerógrafo de la marca, o agua destilada con una gota de flow improver"
    return when (p.type) {
        "Aerógrafo" -> g("Lista para aerógrafo.", t, Source.COMMUNITY,
            u("Aerógrafo", "Directa · hasta 4:1", "Tal cual; agrega 10–20% si salpica."),
            u("Pincel", "Directa", "Muy fluida: varias capas."))
        "Metálica" -> g("Metálica: pigmento pesado.", t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", "Boquilla de 0.3 mm o más; limpia bien después."),
            u("Pincel", "Directa a 3:1", "Muy poca agua: diluida de más se separan las escamas."),
            notes = listOf("Para matizar un metal usa una tinta o lavado encima, no diluyas la metálica."))
        "Contraste" -> g("Contraste: no la diluyas con agua.", "Su medio propio", Source.COMMUNITY,
            u("Pincel", "Directa", "Capa generosa sobre base clara."),
            u("Aclarar / veladura", "1:1 a 1:3", "Con su medio."))
        "Wash" -> g("Lavado listo para usar.", "Medio de lavado o agua", Source.COMMUNITY,
            u("Pincel", "Directa", "Aplica en huecos."),
            u("Más suave", "1:1", "Con medio de lavado."))
        "Tinta" -> g("Tinta: intensa y transparente.", t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", "Ideal para filtros."),
            u("Veladura", "1:3 a 1:6", "Con medio de veladura."),
            u("Lavado", "1:4 a 1:8", "Con medio de lavado."))
        "Imprimación" -> g("Imprimación.", t, Source.COMMUNITY,
            u("Aerógrafo", "Directa", "Capas finas a 20–25 PSI."),
            u("Pincel", "Directa", "No la diluyas con agua: pierde agarre."))
        "Barniz", "Médium" -> g("Barniz o médium.", t, Source.COMMUNITY,
            u("Aerógrafo", "Directa a 2:1", "10–30% de diluyente, capas finas a 15–20 PSI."))
        else -> g("Pintura de pincel estándar.", t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", "Partes iguales con diluyente de aerógrafo."),
            u("Pincel", "3:1 a 2:1", "Con agua; consistencia de leche."),
            u("Veladura", "1:3 a 1:5", "Con medio de veladura."),
            u("Lavado", "1:5 a 1:8", "Con medio de lavado."))
    }
}

/** ¿Se pueden mezclar estas pinturas entre sí? Devuelve un aviso o null si no hay problema. */
fun mixWarning(paints: List<Paint>): String? {
    val chems = paints.map { chemistryOf(it) }.toSet()
    if (chems.size > 1) return "Estas pinturas tienen bases distintas (${chems.joinToString { it.label.lowercase() }}): no se mezclan bien."
    val c = chems.first()
    if (c == Chemistry.WATER_SPECIAL) {
        val lines = paints.map { it.brand + it.line }.toSet()
        if (lines.size > 1) return "Esta gama usa diluyente propio: mezclarla con otras puede cambiar su acabado."
    }
    if (paints.any { it.type == "Contraste" } && paints.any { it.type != "Contraste" }) {
        return "Mezclar contraste con pintura opaca le quita el efecto de contraste."
    }
    if (paints.map { it.brand }.toSet().size > 1 && c == Chemistry.WATER_ACRYLIC) {
        return "Acrílicos al agua de distintas marcas se pueden mezclar; prueba primero en poca cantidad."
    }
    return null
}
