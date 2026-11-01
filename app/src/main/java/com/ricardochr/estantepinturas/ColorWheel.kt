package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Círculo cromático de pintor (rojo-amarillo-azul), como el que se usa con pigmentos:
 * los complementarios son rojo-verde, amarillo-violeta y azul-naranja (en el círculo de luz
 * RGB serían otros). Se convierte el tono de cada pintura al ángulo de ese círculo.
 */
data class WheelSector(val name: String, val ryb: Float, val hex: String)

/** 12 sectores, en grados RYB (0 = rojo, 120 = amarillo, 240 = azul). */
val WHEEL_SECTORS = listOf(
    WheelSector("Rojo", 0f, "#C8252B"),
    WheelSector("Rojo naranja", 30f, "#E0391E"),
    WheelSector("Naranja", 60f, "#EC6E1F"),
    WheelSector("Amarillo naranja", 90f, "#F0A31E"),
    WheelSector("Amarillo", 120f, "#F2D21B"),
    WheelSector("Amarillo verdoso", 150f, "#9CC23A"),
    WheelSector("Verde", 180f, "#2E9A44"),
    WheelSector("Azul verdoso", 210f, "#1E7FA0"),
    WheelSector("Azul", 240f, "#2B4BA8"),
    WheelSector("Azul violeta", 270f, "#3E3A8A"),
    WheelSector("Violeta", 300f, "#6E3F8C"),
    WheelSector("Rojo violeta", 330f, "#B23A7E"),
)

// Tono HSV (luz) -> ángulo RYB (pigmento), por tramos
private val ANCHORS = listOf(
    0f to 0f, 30f to 60f, 60f to 120f, 120f to 180f, 180f to 210f,
    240f to 240f, 275f to 300f, 320f to 330f, 360f to 360f,
)

fun hsvHueToRyb(h: Float): Float {
    val x = ((h % 360f) + 360f) % 360f
    for (i in 0 until ANCHORS.size - 1) {
        val (h0, r0) = ANCHORS[i]
        val (h1, r1) = ANCHORS[i + 1]
        if (x in h0..h1) return r0 + (x - h0) / (h1 - h0) * (r1 - r0)
    }
    return x
}

/** Posición de una pintura en el círculo: ángulo RYB y radio (0 centro = neutro, 1 borde = muy saturado). */
data class WheelPos(val ryb: Float, val radius: Float, val neutral: Boolean)

fun wheelPosOf(p: Paint): WheelPos {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(parseHex(p.hex).toInt(), hsv)
    val chroma = sqrt(p.lab.a * p.lab.a + p.lab.b * p.lab.b).toFloat()
    val neutral = chroma < 8f
    val radius = if (neutral) 0.08f + 0.1f * (p.lab.l.toFloat() / 100f) else 0.2f + 0.8f * min(1f, chroma / 75f)
    return WheelPos(hsvHueToRyb(hsv[0]), radius, neutral)
}

fun angleDiff(a: Float, b: Float): Float {
    val d = abs(((a - b) % 360f + 540f) % 360f - 180f)
    return d
}

fun sectorOf(ryb: Float): WheelSector = WHEEL_SECTORS.minByOrNull { angleDiff(it.ryb, ryb) } ?: WHEEL_SECTORS[0]

enum class Harmony(val label: String, val offsets: List<Float>, val tip: String) {
    COMPLEMENTARY(
        "Complementario", listOf(180f),
        "El opuesto da el máximo contraste. Úsalo en poca cantidad para resaltar, o mezcla una gota con la base para sombras que no se ensucian como con negro.",
    ),
    ANALOGOUS(
        "Análogos", listOf(-30f, 30f),
        "Los vecinos combinan de forma natural: ideales para transiciones suaves de sombra a luz dentro de un mismo color.",
    ),
    SPLIT(
        "Dividido", listOf(150f, 210f),
        "Los dos vecinos del complementario: contraste fuerte pero más equilibrado y fácil de combinar.",
    ),
    TRIAD(
        "Tríada", listOf(120f, 240f),
        "Tres colores a la misma distancia: paleta viva. Elige uno dominante y usa los otros dos para detalles.",
    ),
    SQUARE(
        "Cuadrado", listOf(90f, 180f, 270f),
        "Cuatro colores equidistantes: muy variado. Funciona mejor si uno domina y los demás son acentos.",
    ),
}

/** Pinturas del inventario cercanas a un ángulo, ordenadas de más a menos cercanas. */
fun paintsNearAngle(paints: List<Pair<Paint, WheelPos>>, ryb: Float, exclude: String?, limit: Int = 4): List<Paint> =
    paints.asSequence()
        .filter { (p, w) -> p.id != exclude && !w.neutral && angleDiff(w.ryb, ryb) <= 18f }
        .sortedWith(compareBy({ angleDiff(it.second.ryb, ryb) }, { -it.second.radius }))
        .map { it.first }
        .take(limit)
        .toList()
