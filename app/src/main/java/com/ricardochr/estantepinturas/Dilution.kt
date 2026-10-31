package com.ricardochr.estantepinturas

/**
 * Proporciones de dilución como punto de partida, según el tipo de pintura y su gama.
 * Las proporciones son en volumen o gotas: "pintura : diluyente".
 * Cada marca y cada bote varía (la pintura se espesa con el tiempo), así que siempre se ajusta
 * a ojo: para aerógrafo, consistencia de leche descremada; para veladura, casi como tinta.
 */
data class DilutionUse(val use: String, val ratio: String, val detail: String)

data class DilutionGuide(val summary: String, val uses: List<DilutionUse>, val notes: List<String>)

private enum class Body { THICK, STANDARD, PRETHINNED, CONTRAST, WASH, INK, METALLIC, PRIMER, AUX, OIL }

private fun bodyOf(p: Paint): Body {
    val line = norm(p.line)
    val brand = norm(p.brand)
    val name = norm(p.name)
    return when {
        p.type == "Barniz" || p.type == "Médium" -> Body.AUX
        p.type == "Imprimación" -> Body.PRIMER
        line.contains("oleo") || line.contains("oil") -> Body.OIL
        p.type == "Contraste" -> Body.CONTRAST
        p.type == "Wash" -> Body.WASH
        p.type == "Tinta" -> Body.INK
        // Gamas que vienen listas para aerógrafo
        p.type == "Aerógrafo" || line.contains("mixed") || brand.contains("kaleido") || line.contains("premium") ||
            // "Air" de AK son colores de aviación, no de aerógrafo
            (Regex("\\bair\\b").containsMatchIn(line) && !brand.startsWith("ak")) -> Body.PRETHINNED
        p.type == "Metálica" -> Body.METALLIC
        // Gamas muy pigmentadas o espesas
        brand.contains("pro acryl") || brand.contains("proacryl") || line.contains("base") ||
            line.contains("scalecolor") || line.contains("artist") || line.contains("expert") ||
            name.contains("titanium white") -> Body.THICK
        else -> Body.STANDARD
    }
}

fun dilutionFor(p: Paint): DilutionGuide {
    val thinner = "diluyente de aerógrafo (o agua destilada con una gota de flow improver)"
    return when (bodyOf(p)) {
        Body.PRETHINNED -> DilutionGuide(
            "Viene lista para aerógrafo.",
            listOf(
                DilutionUse("Aerógrafo", "Directa · hasta 4:1", "Úsala tal cual; si salpica o se seca en la boquilla, agrega 10–20% de diluyente."),
                DilutionUse("Pincel", "Directa", "Es muy fluida: carga poco el pincel y da varias capas; tarda en cubrir."),
                DilutionUse("Veladura", "1:2 a 1:3", "Con medio de veladura (glaze medium) para que no pierda adherencia."),
                DilutionUse("Lavado", "1:4 a 1:6", "Con medio de lavado o agua con una gota de jabón para que corra por los huecos."),
            ),
            listOf("Agita bien: el pigmento se asienta en el fondo. Presión sugerida: 15–25 PSI."),
        )
        Body.THICK -> DilutionGuide(
            "Muy pigmentada: necesita más diluyente que otras.",
            listOf(
                DilutionUse("Aerógrafo", "1:1 a 1:2", "Una parte de pintura por una o dos de $thinner. Mezcla en la paleta antes de pasarla al aerógrafo."),
                DilutionUse("Pincel", "2:1 a 1:1", "Con agua, hasta consistencia de leche; dos capas finas cubren mejor que una gruesa."),
                DilutionUse("Veladura", "1:4 a 1:6", "Con medio de veladura; debe teñir sin tapar lo de abajo."),
                DilutionUse("Lavado", "1:6 a 1:10", "Con medio de lavado; mientras más diluido, más sutil."),
            ),
            listOf("Pro Acryl, Scalecolor y Citadel Base se secan rápido: un retardante en la paleta ayuda con las transiciones."),
        )
        Body.STANDARD -> DilutionGuide(
            "Pintura de pincel estándar.",
            listOf(
                DilutionUse("Aerógrafo", "1:1", "Partes iguales de pintura y $thinner. Si el chorro sale granulado, agrega un poco más de diluyente."),
                DilutionUse("Pincel", "3:1 a 2:1", "Con agua; consistencia de leche entera."),
                DilutionUse("Veladura", "1:3 a 1:5", "Con medio de veladura."),
                DilutionUse("Lavado", "1:5 a 1:8", "Con medio de lavado o agua con flow improver."),
            ),
            listOf("Presión sugerida en aerógrafo: 15–20 PSI para detalle, 25–30 PSI para cubrir."),
        )
        Body.METALLIC -> DilutionGuide(
            "Metálica: el pigmento es más pesado.",
            listOf(
                DilutionUse("Aerógrafo", "1:1", "Con diluyente de aerógrafo; usa boquilla de 0.3 mm o más y limpia bien después (las escamas metálicas tapan)."),
                DilutionUse("Pincel", "Directa a 3:1", "Muy poca agua: si la diluyes de más se separan las escamas y queda grumoso."),
                DilutionUse("Veladura", "No recomendada", "Para matizar un metal, aplica encima una tinta o un contraste diluido."),
                DilutionUse("Lavado", "No recomendado", "Lava el metal con un wash oscuro (negro o sepia) en vez de diluir la metálica."),
            ),
            listOf("Mezcla con un palillo, no solo agites: las escamas se quedan en el fondo."),
        )
        Body.CONTRAST -> DilutionGuide(
            "Contraste / Speedpaint / Xpress: no la diluyas con agua.",
            listOf(
                DilutionUse("Aerógrafo", "Directa o 2:1", "Si hace falta, con su medio (Contrast Medium, Speedpaint Medium); el agua rompe el efecto."),
                DilutionUse("Pincel", "Directa", "Una sola capa generosa sobre base clara; no repases mientras se seca."),
                DilutionUse("Veladura", "1:1 a 1:3", "Con su medio: da un filtro de color muy uniforme."),
                DilutionUse("Lavado", "1:1 a 1:2", "Con su medio, para un lavado de color sobre zonas ya pintadas."),
            ),
            listOf("Funciona sobre imprimación blanca, gris claro o zenital; sobre negro casi no se ve."),
        )
        Body.WASH -> DilutionGuide(
            "Lavado: ya viene diluido.",
            listOf(
                DilutionUse("Aerógrafo", "Directa", "Sirve para sombrear de forma general a baja presión (10–15 PSI)."),
                DilutionUse("Pincel", "Directa", "Aplica en huecos; retira el exceso de zonas planas con el pincel limpio y seco."),
                DilutionUse("Veladura", "1:1", "Con medio de lavado o veladura para un tono más suave."),
                DilutionUse("Lavado", "Directa", "Tal cual; diluye 1:1 si se ve muy oscuro."),
            ),
            listOf("Deja secar del todo (20–30 min) antes de pintar encima; si no, se levanta."),
        )
        Body.INK -> DilutionGuide(
            "Tinta: muy intensa y transparente.",
            listOf(
                DilutionUse("Aerógrafo", "1:1", "Con diluyente de aerógrafo; perfecta para filtros y para saturar colores."),
                DilutionUse("Pincel", "Directa a 1:1", "Pinta como un lavado de color intenso."),
                DilutionUse("Veladura", "1:3 a 1:6", "Con medio de veladura: es la mejor base para veladuras."),
                DilutionUse("Lavado", "1:4 a 1:8", "Con medio de lavado."),
            ),
            listOf("Unas gotas de tinta en una pintura opaca aumentan su saturación sin cambiar mucho el tono."),
        )
        Body.PRIMER -> DilutionGuide(
            "Imprimación.",
            listOf(
                DilutionUse("Aerógrafo", "Directa", "La mayoría de imprimaciones de aerógrafo vienen listas; 20–25 PSI, capas finas."),
                DilutionUse("Pincel", "Directa", "Si es de pincel, capa fina y pareja; no la diluyas con agua porque pierde agarre."),
            ),
            listOf("Deja curar la imprimación varias horas (idealmente una noche) antes de pintar."),
        )
        Body.OIL -> DilutionGuide(
            "Óleo: se diluye con diluyente sin olor, no con agua.",
            listOf(
                DilutionUse("Pincel", "Directa", "Para fundir colores: tarda horas en secar, ideal para degradados."),
                DilutionUse("Veladura", "1:3 a 1:6", "Con diluyente inodoro (odorless thinner)."),
                DilutionUse("Lavado", "1:8 a 1:15", "Con diluyente inodoro, para filtros y lavados de envejecido."),
            ),
            listOf("Aplica sobre una capa de barniz para poder corregir con diluyente sin dañar lo acrílico de abajo."),
        )
        Body.AUX -> DilutionGuide(
            "Barniz o médium.",
            listOf(
                DilutionUse("Aerógrafo", "Directa a 2:1", "Barniz con 10–30% de diluyente, capas finas a 15–20 PSI."),
                DilutionUse("Pincel", "Directa", "Capa fina con pincel plano suave."),
            ),
            listOf("Barniza en un ambiente seco: con humedad alta el barniz mate puede quedar blanquecino."),
        )
    }
}
