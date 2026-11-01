package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Teoría del color aplicada a una pintura: cómo es (temperatura, valor, saturación), cómo
 * sombrearla e iluminarla, y qué pasa al poner otra pintura encima.
 */
data class ColorProfile(val hue: String, val temperature: String, val value: String, val saturation: String)

private fun chroma(l: Lab) = sqrt(l.a * l.a + l.b * l.b)

private fun hueDeg(l: Lab): Double {
    val h = Math.toDegrees(atan2(l.b, l.a))
    return if (h < 0) h + 360 else h
}

fun profileOf(p: Paint): ColorProfile {
    val lab = p.lab
    val c = chroma(lab)
    val w = wheelPosOf(p)
    val hue = if (w.neutral) when {
        lab.l > 85 -> "blanco"; lab.l > 60 -> "gris claro"; lab.l > 35 -> "gris"; lab.l > 18 -> "gris oscuro"; else -> "negro"
    } else sectorOf(w.ryb).name.lowercase()
    val temp = when {
        w.neutral -> if (lab.b > 3) "neutro cálido" else if (lab.b < -3) "neutro frío" else "neutro"
        w.ryb < 135 || w.ryb > 320 -> "cálido"
        w.ryb in 170f..290f -> "frío"
        else -> "intermedio"
    }
    val value = when {
        lab.l > 75 -> "muy claro"; lab.l > 58 -> "claro"; lab.l > 40 -> "medio"; lab.l > 22 -> "oscuro"; else -> "muy oscuro"
    }
    val sat = when {
        c < 8 -> "sin saturación"; c < 25 -> "apagado"; c < 50 -> "saturado"; else -> "muy saturado"
    }
    return ColorProfile(hue, temp, value, sat)
}

/** Color objetivo desplazando luminosidad y tono (en grados de CIELAB). */
private fun shift(l: Lab, dl: Double, dHue: Double, chromaFactor: Double): Lab {
    val c = chroma(l) * chromaFactor
    val h = Math.toRadians(hueDeg(l) + dHue)
    return Lab((l.l + dl).coerceIn(2.0, 98.0), c * cos(h), c * sin(h))
}

/** Gira el tono hacia un ángulo destino (azul-violeta para sombras, amarillo para luces) como máximo [max] grados. */
private fun towards(from: Double, to: Double, max: Double): Double {
    val d = ((to - from + 540) % 360) - 180
    return d.coerceIn(-max, max)
}

data class ToneSuggestion(val role: String, val paint: Paint?, val why: String)

/**
 * Sombras y luces para usar [p] como base, con las pinturas que tienes.
 * La sombra se busca más oscura y un poco más fría (hacia azul/violeta); la luz, más clara y un
 * poco más cálida (hacia amarillo). Así el color no se ensucia como al agregar negro o blanco.
 */
fun baseAdvice(p: Paint, inventory: List<Paint>): List<ToneSuggestion> {
    val lab = p.lab
    val neutral = chroma(lab) < 8
    val h = hueDeg(lab)
    val shadowTarget = if (neutral) Lab(lab.l - 20, lab.a, lab.b - 3) else shift(lab, -20.0, towards(h, 285.0, 20.0), 0.95)
    val lightTarget = if (neutral) Lab(lab.l + 18, lab.a, lab.b + 2) else shift(lab, 18.0, towards(h, 95.0, 15.0), 0.85)
    val opaque = inventory.filter { it.id != p.id && it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    fun pick(t: Lab, darker: Boolean) = opaque
        .filter { if (darker) it.lab.l < lab.l - 6 else it.lab.l > lab.l + 6 }
        .map { it to deltaE2000(t, it.lab) }
        .filter { it.second < 22 }
        .minByOrNull { it.second }?.first
    val shadow = pick(shadowTarget, true)
    val light = pick(lightTarget, false)
    val transparentShadow = inventory.filter { it.hasColor && typeGroup(it.type) == "transparente" }
        .map { it to deltaE2000(shadowTarget, it.lab) }.filter { it.second < 20 }.minByOrNull { it.second }?.first
    val out = ArrayList<ToneSuggestion>()
    out.add(
        ToneSuggestion(
            "Sombra", shadow,
            if (neutral) "Más oscura que la base; un toque frío (azul) hace que la sombra de un gris o blanco se vea limpia."
            else "Más oscura y un poco más fría que la base. Evita el negro: apaga el color y lo vuelve sucio.",
        )
    )
    if (transparentShadow != null) {
        out.add(ToneSuggestion("Sombra con veladura", transparentShadow, "Transparente: tiñe la sombra sin tapar la base. Aplícala diluida en huecos."))
    }
    out.add(
        ToneSuggestion(
            "Luz", light,
            if (neutral) "Más clara; con un toque cálido (hueso, crema) la luz se ve natural."
            else "Más clara y algo más cálida. Aclarar solo con blanco enfría y apaga el color (lo vuelve pastel).",
        )
    )
    // Complementario: para neutralizar sombras o como acento
    if (!neutral) {
        val w = wheelPosOf(p)
        val comp = inventory.filter { it.id != p.id && it.hasColor && typeGroup(it.type) != "auxiliar" }
            .map { it to wheelPosOf(it) }
            .filter { (_, pw) -> !pw.neutral && angleDiff(pw.ryb, (w.ryb + 180f) % 360f) <= 20f }
            .maxByOrNull { it.second.radius }?.first
        out.add(
            ToneSuggestion(
                "Complementario", comp,
                "Opuesto en el círculo. Una gota en la mezcla de sombra la neutraliza sin ensuciar; junto a la base, como acento, da el máximo contraste.",
            )
        )
    }
    return out
}

/** Resultado de poner [top] encima de [base]. */
data class LayerResult(val glazeArgb: Int, val layerArgb: Int, val explanation: List<String>)

fun layerOver(base: Paint, top: Paint): LayerResult {
    val glaze = mixTwo(base, top, 3, 1)  // veladura: poca pintura de arriba
    val layer = mixTwo(base, top, 1, 1)  // capa semitransparente
    val bw = wheelPosOf(base)
    val tw = wheelPosOf(top)
    val bl = base.lab
    val tl = top.lab
    val ex = ArrayList<String>()
    val transparent = typeGroup(top.type) == "transparente"
    val diff = angleDiff(bw.ryb, tw.ryb)

    if (!transparent && top.type != "Metálica") {
        ex.add("${top.name} es opaca: una capa normal la tapa por completo. Lo que se ve abajo es lo que pasa si la aplicas diluida como veladura o en una capa fina.")
    }
    when {
        tw.neutral && tl.l < 25 -> ex.add("Negro sobre un color: lo oscurece pero también le quita saturación; la sombra se ve sucia o grisácea. Mejor un tono oscuro del mismo color o su complementario.")
        tw.neutral && tl.l > 80 -> ex.add("Blanco sobre un color: lo aclara pero lo enfría y lo vuelve pastel (más opaco y menos vivo). Para luces, mejor un tono claro y cálido del mismo color.")
        tw.neutral -> ex.add("Un gris encima baja la saturación: útil para apagar un color muy chillón o para sombras desaturadas.")
        bw.neutral -> ex.add("Sobre un neutro, ${top.name} tiñe directamente: el resultado toma su tono, más claro o más oscuro según la base.")
        diff >= 150 -> ex.add("Son complementarios: al superponerlos se neutralizan y el resultado tiende a gris o café. Sirve para sombras naturales o para calmar un color demasiado intenso; en exceso, ensucia.")
        diff <= 35 -> ex.add("Son análogos (vecinos en el círculo): el tono se desplaza suavemente hacia ${sectorOf(tw.ryb).name.lowercase()} y mantiene la saturación. Ideal para transiciones y para dar variedad sin romper la armonía.")
        diff in 80f..130f -> ex.add("Están a un tercio del círculo: el color cambia notablemente de tono y pierde algo de saturación. Úsalo con cuidado y en poca cantidad.")
        else -> ex.add("El tono se desplaza hacia ${sectorOf(tw.ryb).name.lowercase()}; cuanto más lejos están en el círculo, más se apaga la mezcla.")
    }
    if (!tw.neutral && !bw.neutral) {
        val bt = profileOf(base).temperature
        val tt = profileOf(top).temperature
        if (bt == "frío" && tt == "cálido") ex.add("Cálido sobre frío: calienta la zona; buena idea para luces (la luz suele ser cálida).")
        if (bt == "cálido" && tt == "frío") ex.add("Frío sobre cálido: enfría la zona; buena idea para sombras (la sombra suele ser fría).")
    }
    if (!tw.neutral) {
        when {
            tl.l < bl.l - 12 -> ex.add("Es más oscura que la base: sirve para sombrear.")
            tl.l > bl.l + 12 -> ex.add("Es más clara que la base: sirve para iluminar (en capas finas, sin cubrir toda la zona).")
        }
    }
    if (transparent) ex.add("${top.name} es transparente (${top.type.lowercase()}): se acumula en huecos y deja ver la base, ideal para este efecto.")
    return LayerResult(glaze, layer, ex)
}
