package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Color en espacio CIELAB (D65). */
data class Lab(val l: Double, val a: Double, val b: Double)

fun hexToLab(hex: String): Lab {
    val v = parseHex(hex)
    fun lin(c: Long): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    val r = lin((v shr 16) and 0xFF)
    val g = lin((v shr 8) and 0xFF)
    val b = lin(v and 0xFF)
    val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    val y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.00000
    val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
    val fx = f(x)
    val fy = f(y)
    val fz = f(z)
    return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

/** Diferencia de color CIEDE2000: <2 casi idéntico, <5 muy parecido, <10 parecido, <20 aproximado. */
fun deltaE2000(c1: Lab, c2: Lab): Double {
    val avgL = (c1.l + c2.l) / 2
    val cA = sqrt(c1.a * c1.a + c1.b * c1.b)
    val cB = sqrt(c2.a * c2.a + c2.b * c2.b)
    val avgC = (cA + cB) / 2
    val g = 0.5 * (1 - sqrt(avgC.pow(7) / (avgC.pow(7) + 25.0.pow(7))))
    val a1p = c1.a * (1 + g)
    val a2p = c2.a * (1 + g)
    val c1p = sqrt(a1p * a1p + c1.b * c1.b)
    val c2p = sqrt(a2p * a2p + c2.b * c2.b)
    val avgCp = (c1p + c2p) / 2
    fun hp(b: Double, ap: Double): Double {
        if (b == 0.0 && ap == 0.0) return 0.0
        val h = Math.toDegrees(atan2(b, ap))
        return if (h < 0) h + 360 else h
    }
    val h1p = hp(c1.b, a1p)
    val h2p = hp(c2.b, a2p)
    val avgHp = when {
        c1p * c2p == 0.0 -> h1p + h2p
        abs(h1p - h2p) > 180 -> (h1p + h2p + 360) / 2
        else -> (h1p + h2p) / 2
    }
    val t = 1 - 0.17 * cos(Math.toRadians(avgHp - 30)) + 0.24 * cos(Math.toRadians(2 * avgHp)) +
        0.32 * cos(Math.toRadians(3 * avgHp + 6)) - 0.20 * cos(Math.toRadians(4 * avgHp - 63))
    var dhp = h2p - h1p
    if (c1p * c2p != 0.0) {
        if (dhp > 180) dhp -= 360 else if (dhp < -180) dhp += 360
    } else {
        dhp = 0.0
    }
    val dLp = c2.l - c1.l
    val dCp = c2p - c1p
    val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
    val sl = 1 + (0.015 * (avgL - 50).pow(2)) / sqrt(20 + (avgL - 50).pow(2))
    val sc = 1 + 0.045 * avgCp
    val sh = 1 + 0.015 * avgCp * t
    val dTheta = 30 * exp(-((avgHp - 275) / 25).pow(2))
    val rc = 2 * sqrt(avgCp.pow(7) / (avgCp.pow(7) + 25.0.pow(7)))
    val rt = -rc * sin(Math.toRadians(2 * dTheta))
    return sqrt(
        (dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh)
    )
}

/** Familias que tiene sentido sustituir entre sí. */
fun typeGroup(type: String): String = when (type) {
    "Metálica" -> "metal"
    "Contraste", "Wash", "Tinta" -> "transparente"
    "Barniz", "Médium" -> "auxiliar"
    else -> "opaca"
}

fun similarityLabel(d: Double): String = when {
    d < 2.5 -> "Casi idéntica"
    d < 6 -> "Muy parecida"
    d < 12 -> "Parecida"
    else -> "Aproximada"
}

const val UNKNOWN_HEX = "#888888"

data class SimilarPaint(val paint: Paint, val distance: Double, val sameFamily: Boolean)
