package com.ricardochr.estantepinturas

import java.util.Locale

/** Tus aerógrafos y tu compresor, para recomendar cuándo y cómo usar aerógrafo. */
class GearKit(val airbrushes: List<Gear>, val compressor: Gear?) {
    val hasAirbrush: Boolean get() = airbrushes.isNotEmpty()

    /** Aerógrafo general (bases y capas medias): el más cercano a 0.3–0.35 mm. */
    val general: Gear? get() = airbrushes.minByOrNull { kotlin.math.abs(it.nozzle - 0.33) }

    /** Aerógrafo fino (degradados, veladuras, sombras suaves): el de boquilla más chica. */
    val fine: Gear? get() = airbrushes.minByOrNull { it.nozzle }

    /** Aerógrafo para imprimación y barniz: el de boquilla más grande. */
    val wide: Gear? get() = airbrushes.maxByOrNull { it.nozzle }

    companion object {
        fun of(list: List<Gear>) = GearKit(
            list.filter { it.kind == "airbrush" }.sortedBy { it.nozzle },
            list.firstOrNull { it.kind == "compressor" && it.tank } ?: list.firstOrNull { it.kind == "compressor" },
        )
    }
}

fun nozzleText(mm: Double): String = String.format(Locale.US, "%.2f", mm).trimEnd('0').trimEnd('.') + " mm"

fun gearLabel(g: Gear): String = g.name.ifBlank { "Aerógrafo" } + if (g.kind == "airbrush" && g.nozzle > 0) " (${nozzleText(g.nozzle)})" else ""

/** Proporción de dilución para aerógrafo de esa pintura (según su marca y gama). */
fun airbrushRatio(p: Paint): String {
    if (p.type == "Aerógrafo") return "lista para usar (ya viene diluida para aerógrafo)"
    val g = dilutionFor(p)
    val u = g.uses.firstOrNull { it.use.contains("aerógrafo", ignoreCase = true) }
    return (u?.ratio?.let { "$it pintura:diluyente" } ?: "1:1 pintura:diluyente") + " con ${g.thinner}"
}

/** Aviso sobre el compresor, si hace falta. */
fun compressorNote(kit: GearKit): String? = when {
    kit.compressor == null -> null
    !kit.compressor.tank -> "Tu compresor no tiene tanque: el aire sale a pulsos. Déjalo encendido un momento antes de pintar y trabaja a la presión más baja que funcione."
    else -> null
}

/** Paso de aerógrafo para una zona grande: base, sombra desde abajo y luz desde arriba. */
fun airbrushStep(kit: GearKit, base: Paint?, shadow: String, light: String): String? {
    val ab = kit.general ?: return null
    val fine = kit.fine?.takeIf { it.id != ab.id && it.nozzle < ab.nozzle }
    val baseText = base?.let { "${it.name} diluida ${airbrushRatio(it)}" } ?: "la base diluida 1:1"
    return buildString {
        append("Con aerógrafo (tu ${gearLabel(ab)}): $baseText, a 18–25 psi y a 10–15 cm, en pasadas finas y cruzadas hasta cubrir parejo. ")
        append("Sombra: $shadow aerografiada desde abajo y desde el lado contrario a la luz. ")
        append("Luz: $light desde arriba (zenital), dejando que se funda con la base")
        append(if (fine != null) ", mejor con tu ${gearLabel(fine)} a 12–18 psi. " else " a 12–18 psi y más cerca. ")
        append("Bordes definidos, detalles, textura y pincel seco se terminan a pincel.")
        compressorNote(kit)?.let { append(" $it") }
    }
}

/** Recomendaciones sobre tu equipo: qué te falta o qué te conviene mejorar. */
fun gearAdvice(kit: GearKit): List<String> {
    val out = ArrayList<String>()
    val sizes = kit.airbrushes.map { it.nozzle }.filter { it > 0 }
    if (!kit.hasAirbrush) {
        out.add("Sin aerógrafo: pintas todo a pincel, y está bien. Un aerógrafo de 0.3 mm de doble acción y gravedad es el más versátil: imprimación, bases y degradados suaves (piel, capas, superficies grandes).")
    } else {
        if (sizes.none { it in 0.25..0.4 }) out.add("Te conviene uno de 0.3–0.35 mm: es el más versátil para bases y capas medias en figuras.")
        if (sizes.none { it <= 0.25 }) out.add("Uno de 0.2 mm (opcional) da degradados más finos y puntuales, útil en piel, ojos de luz (OSL) y veladuras; necesita pintura más diluida y menos presión.")
        if (sizes.none { it >= 0.45 }) out.add("Para imprimación y barniz, una boquilla de 0.4–0.5 mm se tapa menos con pinturas espesas (opcional: con 0.3 mm también se puede, diluyendo más).")
        if (out.isEmpty()) out.add("Tienes cubiertas las boquillas fina, general y gruesa.")
    }
    when {
        kit.compressor == null && kit.hasAirbrush ->
            out.add("Registra también tu compresor: si no tiene tanque te aviso cómo compensarlo.")
        kit.compressor != null && !kit.compressor.tank ->
            out.add("Un compresor con tanque (1–3 L) y regulador con manómetro da aire constante, sin pulsos, y es más silencioso: es la mejora que más se nota al pintar figuras.")
    }
    out.add("Presiones de referencia: imprimación 25–30 psi · bases 18–25 · degradados y veladuras 12–18 · detalles finos 10–15.")
    return out
}
