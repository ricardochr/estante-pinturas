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
        // AK Real Colors: acrílico con base de laca; True Metal: pasta de cera que se diluye con white spirit
        brand.contains("ak") && line.contains("real colors") -> Chemistry.LACQUER
        line.contains("true metal") || line.contains("(wax)") -> Chemistry.ENAMEL
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
            tr("Óleo: se diluye con diluyente inodoro, nunca con agua."), tr("Diluyente inodoro (odorless thinner)"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Para fundir colores; tarda horas en secar.")),
            u(tr("Veladura / filtro"), "1:3 a 1:6", tr("Con diluyente inodoro.")),
            u(tr("Lavado"), "1:8 a 1:15", tr("Con diluyente inodoro, para envejecido y filtros.")),
            notes = listOf(tr("Aplícalo sobre una capa de barniz para poder corregir sin dañar el acrílico de abajo.")),
        )
        Chemistry.ENAMEL -> return g(
            tr("Esmalte: se diluye con diluyente de esmalte."), tr("White spirit o diluyente de esmalte"), Source.COMMUNITY,
            u("Aerógrafo", "2:1 a 1:1", tr("Con diluyente de esmalte; necesita ventilación.")),
            u(tr("Lavado"), "1:3 a 1:6", tr("Clásico para lavados de paneles sobre acrílico seco y barnizado brillante.")),
            notes = listOf(tr("El diluyente de esmalte no daña el acrílico seco, por eso se usa para lavados que se pueden limpiar.")),
        )
        Chemistry.LACQUER -> return g(
            tr("Laca: se diluye con diluyente de laca."), tr("Diluyente de laca (Mr. Color Thinner / Leveling Thinner, Tamiya Lacquer Thinner)"), Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", tr("Con Leveling Thinner queda muy lisa. Solo aerógrafo con juntas resistentes a solventes.")),
            notes = listOf(tr("Usa mascarilla para vapores orgánicos y buena ventilación.")),
        )
        Chemistry.ALCOHOL_ACRYLIC -> {
            if (brand.contains("vallejo")) return g(
                tr("Liquid Metal / Liquid Gold: base alcohol."), tr("Su diluyente propio (Liquid Gold/Metal thinner) o alcohol isopropílico"), Source.COMMUNITY,
                u(tr("Pincel"), tr("Directa"), tr("Agita mucho; no la diluyas con agua.")),
                notes = listOf(tr("Se seca muy rápido y puede levantar el acrílico si repasas mucho.")),
            )
            return g(
                tr("Acrílico con alcohol (Tamiya X/XF, Mr. Hobby Aqueous)."), tr("Su diluyente propio (Tamiya X-20A, Mr. Color Leveling para Aqueous) o alcohol isopropílico"), Source.COMMUNITY,
                u("Aerógrafo", "1:1 a 2:3", tr("Tamiya X-20A al 50–60%; muy fácil de aerografiar.")),
                u(tr("Pincel"), tr("Directa"), tr("Seca rápido; con pincel deja marcas, mejor en zonas pequeñas.")),
                notes = listOf(tr("Con agua se corta. No la mezcles con Vallejo, Army Painter o Pro Acryl.")),
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
            tr("Kaleido viene lista para aerógrafo."), tr("Kaleido Retarder Thinner (KT102) o Airbrush Thinner"), Source.MAKER,
            u("Aerógrafo", tr("Directa"), tr("Agita 3–5 minutos (trae bola mezcladora). 15–30 PSI; 15 PSI o menos para detalle.")),
            u(tr("Aerógrafo detalle"), "1:2", tr("Una de pintura por dos de retarder thinner, solo para líneas finas o si se seca en la punta.")),
            notes = listOf(tr("Gaahleri solo indica su propio diluyente; no hay información oficial sobre mezclarla con otras marcas.")),
        )
        brand.contains("dspiae") -> g(
            tr("DSPIAE Pre-Mixed: al agua y ya diluida para aerógrafo."), tr("No necesita; si se espesa, unas gotas de diluyente de aerógrafo al agua"), Source.MAKER,
            u("Aerógrafo", tr("Directa"), tr("Lista para usar; agita bien.")),
            u(tr("Pincel"), tr("Directa"), tr("Muy fluida: varias capas finas.")),
        )
        vallejo && line.contains("metal color") -> g(
            tr("Metal Color: lista para aerógrafo, con diluyente propio."), tr("Vallejo Airbrush Thinner 71.261 y Flow Improver 71.362"), Source.MAKER,
            u("Aerógrafo", tr("Directa · máx. 3:1"), tr("Directa; si hace falta, 1 de diluyente por 3 de pintura. 10–15 PSI. Agrega 2 gotas de flow improver por cada 10 de pintura.")),
            u(tr("Pincel"), tr("Directa"), tr("Funciona, pero rinde mejor con aerógrafo.")),
            notes = listOf(tr("Se puede aplicar sin imprimación; para aluminio brillante, base negra brillante (77.660)."), tr("El agua no la arruina pero cambia sus propiedades: mejor su diluyente.")),
        )
        vallejo && (line.contains("model air") || line.contains("game air") || line.contains("premium")) -> g(
            tr("Gama de aerógrafo: ya viene diluida."), tr("Vallejo Airbrush Thinner 71.161"), Source.MAKER,
            u("Aerógrafo", tr("Directa · máx. 4:1"), tr("Vallejo indica hasta 25% de diluyente como máximo.")),
            u(tr("Pincel"), tr("Directa · máx. 2:1"), tr("Hasta 50% de diluyente para pincel.")),
            u(tr("Veladura"), "1:2 a 1:3", tr("Con Glaze Medium.")),
        )
        vallejo && line.contains("xpress") -> g(
            tr("Xpress Color: pintura de contraste."), tr("Xpress Medium (72.448), no agua"), Source.MAKER,
            u(tr("Pincel"), tr("Directa"), tr("Una capa generosa sobre base clara.")),
            u(tr("Aclarar / veladura"), "1:1 a 1:3", tr("Con Xpress Medium; el agua rompe el efecto.")),
            u("Aerógrafo", tr("Directa a 2:1"), tr("Con Xpress Medium si hace falta.")),
        )
        vallejo && line.contains("wash") -> g(
            tr("Wash FX: lavado listo para usar."), tr("Agua o Wash FX Medium"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Aplica en huecos y limpia el exceso de zonas planas.")),
            u(tr("Más suave"), "1:1", tr("Con medio de lavado o agua.")),
        )
        vallejo -> g(
            tr("Vallejo Model Color / Game Color: pintura de pincel."), tr("Vallejo Airbrush Thinner 71.161 + Flow Improver; para pincel, agua"), Source.COMMUNITY,
            u("Aerógrafo", "1:1", tr("Vallejo no da cifra para estas gamas; la práctica habitual es partes iguales con su diluyente de aerógrafo y una gota de flow improver.")),
            u(tr("Pincel"), "3:1 a 2:1", tr("Con agua; consistencia de leche.")),
            u(tr("Veladura"), "1:3 a 1:5", tr("Con Glaze Medium.")),
            u(tr("Lavado"), "1:5 a 1:8", tr("Con Wash Medium o agua con flow improver.")),
        )
        pro && type == "Imprimación" -> g(
            tr("Pro Acryl Prime."), tr("Agua o diluyente de aerógrafo"), Source.COMMUNITY,
            u("Aerógrafo", tr("Directa"), tr("Lista para aerógrafo; capas finas a 20–25 PSI.")),
            u(tr("Pincel"), tr("Directa"), tr("Las versiones Brush-On se aplican tal cual.")),
        )
        pro -> g(
            tr("Pro Acryl: muy pigmentada."), tr("Para veladura y lavado: Glaze & Wash Medium (MPAM-001); para aerógrafo: diluyente de aerógrafo o agua"), Source.MAKER,
            u(tr("Veladura"), "1:4", tr("Monument indica 1 de pintura por 4 de Glaze & Wash Medium; más medio = más transparente.")),
            u(tr("Lavado"), tr("1:4 + agua"), tr("1 de pintura, 4 de medio y tanta agua como medio; menos agua = más control.")),
            u("Aerógrafo", "1:1 a 1:2", tr("Monument no da cifra; por su pigmento suele necesitar partes iguales o más diluyente.")),
            u(tr("Pincel"), "2:1 a 1:1", tr("Con agua; cubre muy bien aun diluida.")),
            notes = listOf(tr("Las proporciones de veladura y lavado son del fabricante; las de aerógrafo y pincel son práctica habitual.")),
        )
        ap && (line.contains("air")) -> g(
            tr("Warpaints Air: lista para aerógrafo."), tr("Warpaints Airbrush Medium (AW2001)"), Source.MAKER,
            u("Aerógrafo", tr("Directa"), tr("Army Painter indica que no necesita diluyente.")),
            u(tr("Pincel"), tr("Directa"), tr("Fluida: varias capas finas.")),
        )
        ap && (line.contains("speedpaint")) -> g(
            tr("Speedpaint: pintura de contraste."), tr("Speedpaint Medium (WP2090), no agua"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Una capa generosa sobre base clara o zenital.")),
            u(tr("Aclarar / veladura"), "1:1 a 1:3", tr("Con Speedpaint Medium.")),
            u("Aerógrafo", tr("Directa"), tr("Se suele aerografiar tal cual a baja presión.")),
        )
        ap && line.contains("wash") -> g(
            tr("Warpaints Fanatic Wash."), tr("Fanatic Wash Medium"), Source.MAKER,
            u(tr("Pincel"), tr("Directa"), tr("Aplica en huecos.")),
            u(tr("Tinte sobre metal"), "1:1", tr("Army Painter: mitad wash y mitad Wash Medium para teñir armadura metálica.")),
        )
        ap -> g(
            tr("Warpaints / Fanatic: pintura de pincel."), tr("Agua para pincel; Warpaints Airbrush Medium para aerógrafo"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa a 3:1"), tr("Army Painter indica diluir con agua limpia, sin cifra; unas gotas bastan.")),
            u("Aerógrafo", "1:1", tr("Con Airbrush Medium; el fabricante no da proporción.")),
            u(tr("Veladura"), "1:3 a 1:5", tr("Con medio de veladura o Speedpaint Medium.")),
            u(tr("Lavado"), "1:5 a 1:8", tr("Con Wash Medium.")),
        )
        ak && type == "Metálica" -> g(
            tr("AK 3Gen metálica."), tr("AK 3Gen Acrylic Thinner (AK11500)"), Source.COMMUNITY,
            u("Aerógrafo", "1:4", tr("2 partes de pintura por 8 de diluyente, a unos 22 PSI.")),
            u(tr("Pincel"), tr("Directa"), tr("Agita bien.")),
        )
        ak -> g(
            tr("AK 3rd Gen: acrílico al agua."), tr("AK 3Gen Acrylic Thinner (AK11500); retardante AK11231 opcional"), Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 3:7", tr("Entre partes iguales y 7 de diluyente por 3 de pintura, a unos 22 PSI. Evita diluyentes con alcohol.")),
            u(tr("Pincel"), tr("Directa a 3:1"), tr("Con agua.")),
            u(tr("Veladura"), "1:3 a 1:5", tr("Con AK Glaze Medium.")),
            u(tr("Lavado"), "1:5 a 1:8", tr("Con agua y una gota de flow improver.")),
            notes = listOf(tr("Agrega el retardante o medio antes del diluyente.")),
        )
        s75 && line.contains("instant") -> g(
            tr("Instant Colors: contraste."), tr("Su medio propio"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Capa generosa sobre base clara.")),
            u(tr("Aclarar"), "1:1 a 1:2", tr("Con medio, no agua.")),
        )
        s75 -> g(
            tr("Scale75 Scalecolor: mate y muy cubriente."), tr("Scale75 Acrylic Airbrush Thinner"), Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", tr("Es espesa; necesita bastante diluyente y algo de retardante (seca muy rápido).")),
            u(tr("Pincel"), "2:1 a 1:1", tr("Con agua.")),
            u(tr("Veladura"), "1:4 a 1:6", tr("Con medio de veladura.")),
            u(tr("Lavado"), "1:6 a 1:10", tr("Con medio de lavado.")),
        )
        citadel && line.contains("contrast") -> g(
            tr("Citadel Contrast."), tr("Contrast Medium, no agua"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Capa generosa sobre base clara.")),
            u(tr("Aclarar"), "1:1 a 1:3", tr("Con Contrast Medium.")),
        )
        citadel && line.contains("air") -> g(
            tr("Citadel Air: lista para aerógrafo."), tr("Citadel Air Caste Thinner"), Source.COMMUNITY,
            u("Aerógrafo", tr("Directa"), tr("Tal cual.")),
        )
        citadel && (line.contains("shade") || line.contains("wash")) -> g(
            tr("Citadel Shade."), tr("Lahmian Medium o agua"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Aplica en huecos.")),
            u(tr("Más suave"), "1:1", tr("Con Lahmian Medium.")),
        )
        citadel && line.contains("base") -> g(
            tr("Citadel Base: espesa y muy cubriente."), tr("Agua para pincel; diluyente de aerógrafo"), Source.COMMUNITY,
            u("Aerógrafo", "1:1 a 1:2", tr("Muy espesa; necesita bastante diluyente.")),
            u(tr("Pincel"), "2:1", tr("Con agua; dos capas finas.")),
        )
        citadel -> g(
            tr("Citadel Layer / Dry / Technical."), tr("Agua para pincel; diluyente de aerógrafo"), Source.COMMUNITY,
            u("Aerógrafo", "1:1", tr("Con diluyente de aerógrafo.")),
            u(tr("Pincel"), "3:1", tr("Con agua; las Dry se usan sin diluir para pincel seco.")),
            u(tr("Veladura"), "1:3 a 1:5", tr("Con Lahmian Medium.")),
        )
        else -> genericByType(p)
    }
}

/** Para marcas sin indicación específica: según el tipo de pintura. */
private fun genericByType(p: Paint): DilutionGuide {
    val t = tr("Diluyente de aerógrafo de la marca, o agua destilada con una gota de flow improver")
    return when (p.type) {
        "Aerógrafo" -> g(tr("Lista para aerógrafo."), t, Source.COMMUNITY,
            u("Aerógrafo", tr("Directa · hasta 4:1"), tr("Tal cual; agrega 10–20% si salpica.")),
            u(tr("Pincel"), tr("Directa"), tr("Muy fluida: varias capas.")))
        "Metálica" -> g(tr("Metálica: pigmento pesado."), t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", tr("Boquilla de 0.3 mm o más; limpia bien después.")),
            u(tr("Pincel"), tr("Directa a 3:1"), tr("Muy poca agua: diluida de más se separan las escamas.")),
            notes = listOf(tr("Para matizar un metal usa una tinta o lavado encima, no diluyas la metálica.")))
        "Contraste" -> g(tr("Contraste: no la diluyas con agua."), tr("Su medio propio"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Capa generosa sobre base clara.")),
            u(tr("Aclarar / veladura"), "1:1 a 1:3", tr("Con su medio.")))
        "Wash" -> g(tr("Lavado listo para usar."), tr("Medio de lavado o agua"), Source.COMMUNITY,
            u(tr("Pincel"), tr("Directa"), tr("Aplica en huecos.")),
            u(tr("Más suave"), "1:1", tr("Con medio de lavado.")))
        "Tinta" -> g(tr("Tinta: intensa y transparente."), t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", tr("Ideal para filtros.")),
            u(tr("Veladura"), "1:3 a 1:6", tr("Con medio de veladura.")),
            u(tr("Lavado"), "1:4 a 1:8", tr("Con medio de lavado.")))
        "Imprimación" -> g(tr("Imprimación."), t, Source.COMMUNITY,
            u("Aerógrafo", tr("Directa"), tr("Capas finas a 20–25 PSI.")),
            u(tr("Pincel"), tr("Directa"), tr("No la diluyas con agua: pierde agarre.")))
        "Barniz", "Médium" -> g(tr("Barniz o médium."), t, Source.COMMUNITY,
            u("Aerógrafo", tr("Directa a 2:1"), tr("10–30% de diluyente, capas finas a 15–20 PSI.")))
        else -> g(tr("Pintura de pincel estándar."), t, Source.COMMUNITY,
            u("Aerógrafo", "1:1", tr("Partes iguales con diluyente de aerógrafo.")),
            u(tr("Pincel"), "3:1 a 2:1", tr("Con agua; consistencia de leche.")),
            u(tr("Veladura"), "1:3 a 1:5", tr("Con medio de veladura.")),
            u(tr("Lavado"), "1:5 a 1:8", tr("Con medio de lavado.")))
    }
}

/** ¿Se pueden mezclar estas pinturas entre sí? Devuelve un aviso o null si no hay problema. */
fun mixWarning(paints: List<Paint>): String? {
    val chems = paints.map { chemistryOf(it) }.toSet()
    if (chems.size > 1) return tr("Estas pinturas tienen bases distintas ({}): no se mezclan bien.", chems.joinToString { it.label.lowercase() })
    val c = chems.first()
    if (c == Chemistry.WATER_SPECIAL) {
        val lines = paints.map { it.brand + it.line }.toSet()
        if (lines.size > 1) return tr("Esta gama usa diluyente propio: mezclarla con otras puede cambiar su acabado.")
    }
    if (paints.any { it.type == "Contraste" } && paints.any { it.type != "Contraste" }) {
        return tr("Mezclar contraste con pintura opaca le quita el efecto de contraste.")
    }
    paints.firstOrNull { specialOf(it) == Special.FLUOR }?.let {
        return tr("{} es fluorescente: mezclada con otras pierde el brillo fluorescente y el color real no se parece al calculado.", it.name)
    }
    paints.firstOrNull { specialOf(it) == Special.METAL }?.takeIf { paints.any { p -> specialOf(p) != Special.METAL } }?.let {
        return tr("{} es metálica: le pasa el brillo metálico a toda la mezcla (y aclara menos de lo que parece).", it.name)
    }
    paints.firstOrNull { specialOf(it) == Special.IRIDISCENTE }?.let {
        return tr("{} es perlada o iridiscente: el efecto se diluye en la mezcla.", it.name)
    }
    paints.firstOrNull { specialOf(it) == Special.TRANSPARENTE }?.takeIf { paints.any { p -> specialOf(p) != Special.TRANSPARENTE } }?.let {
        return tr("{} es transparente: tiñe pero casi no cubre, así que la mezcla queda más translúcida y el color calculado es aproximado.", it.name)
    }
    paints.firstOrNull { specialOf(it) == Special.EFECTO }?.let {
        return tr("{} es una pintura de efecto o textura: no está hecha para mezclarse con otras.", it.name)
    }
    if (paints.any { finishOf(it).finish == Finish.BRILLANTE } && paints.any { finishOf(it).finish == Finish.MATE }) {
        return tr("Mezclas una pintura brillante con una mate: el acabado queda irregular. Pasa barniz al final para igualarlo.")
    }
    paints.firstOrNull { it.quality == "n" || it.quality == "d" || it.quality == "f" }?.let {
        return tr("El color de {} es aproximado (no medido): el resultado de la mezcla puede variar. Corrígelo con Medir color para un cálculo exacto.", it.name)
    }
    if (paints.map { it.brand }.toSet().size > 1 && c == Chemistry.WATER_ACRYLIC) {
        return tr("Acrílicos al agua de distintas marcas se pueden mezclar; prueba primero en poca cantidad.")
    }
    return null
}
