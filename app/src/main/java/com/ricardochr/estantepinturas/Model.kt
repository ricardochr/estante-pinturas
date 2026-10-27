package com.ricardochr.estantepinturas

import java.text.Normalizer

val PAINT_TYPES = listOf(
    "Opaca", "Metálica", "Wash", "Contraste", "Tinta", "Aerógrafo",
    "Imprimación", "Barniz", "Médium", "Otro"
)

data class Paint(
    val id: String,
    val brand: String,
    val line: String,
    val name: String,
    val code: String,
    val hex: String,
    val type: String,
    val custom: Boolean = false,
) {
    /** Color ARGB para dibujar la muestra. */
    val argb: Long by lazy { parseHex(hex) }

    /** Texto normalizado donde se busca: marca, línea, nombre, código, tipo y familia de color. */
    val searchText: String by lazy {
        norm(listOf(brand, line, name, code, type, codeKey(code).lowercase(), colorWords(hex)).joinToString(" "))
    }

    val subtitle: String
        get() = listOf(brand, line, type).filter { it.isNotBlank() }.joinToString(" · ")
}

data class OwnedEntry(
    val paintId: String,
    val qty: Int,
    val notes: String,
    val updatedAt: Long,
)

/** Minúsculas, sin acentos, espacios simples. */
fun norm(s: String): String {
    val n = Normalizer.normalize(s, Normalizer.Form.NFD)
    return n.replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("\\s+"), " ").trim()
}

/** Clave de código: solo letras y números en mayúscula ("70.955" -> "70955", "MPA-040" -> "MPA040"). */
fun codeKey(s: String): String = s.uppercase().filter { it.isLetterOrDigit() && it.code < 128 }

/** Clave de nombre: solo letras y números, sin acentos ("Flat Flesh" -> "flatflesh"). */
fun nameKey(s: String): String = norm(s).filter { it.isLetterOrDigit() }

/** Clave de marca tolerante a variantes ("Monument Hobbies" = "Pro Acryl", "Army Painter" = "The Army Painter"). */
fun brandKey(s: String): String {
    val k = nameKey(s)
    return when {
        k.contains("monument") || k.contains("proacryl") -> "proacryl"
        k.contains("armypainter") -> "armypainter"
        k.contains("citadel") || k.contains("gamesworkshop") -> "citadel"
        k.contains("akrealcolor") -> "akrealcolors"
        k == "ak" || k.contains("akinteractive") -> "akinteractive"
        k.contains("mig") || k.contains("ammo") -> "ammobymig"
        k.contains("greenstuff") -> "greenstuffworld"
        else -> k
    }
}

fun parseHex(hex: String): Long {
    val h = hex.trim().removePrefix("#")
    return if (h.length == 6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        0xFF000000L or h.toLong(16)
    } else {
        0xFF888888L
    }
}

fun validHex(hex: String): String {
    val h = hex.trim().removePrefix("#")
    return if (h.length == 6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#" + h.uppercase() else "#888888"
}

/** Palabras de familia de color para poder buscar "rojo", "piel", "azul"... */
fun colorWords(hex: String): String {
    val v = parseHex(hex)
    val r = ((v shr 16) and 0xFF) / 255.0
    val g = ((v shr 8) and 0xFF) / 255.0
    val b = (v and 0xFF) / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2
    val d = max - min
    val s = if (d == 0.0) 0.0 else d / (1 - Math.abs(2 * l - 1))
    var hue = 0.0
    if (d != 0.0) {
        hue = when (max) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
    }
    if (hue < 0) hue += 360
    val w = mutableListOf<String>()
    if (s < 0.12) {
        w.add(if (l > 0.85) "blanco" else if (l < 0.15) "negro" else "gris")
        return w.joinToString(" ")
    }
    when {
        hue < 15 || hue >= 345 -> w.add("rojo")
        hue < 40 -> { w.add(if (l < 0.45) "cafe marron" else "naranja"); w.add("piel") }
        hue < 65 -> { w.add("amarillo"); w.add(if (l < 0.5) "ocre cafe" else "piel crema") }
        hue < 165 -> w.add("verde")
        hue < 195 -> w.add("turquesa cian")
        hue < 255 -> w.add("azul")
        hue < 290 -> w.add("morado violeta")
        else -> w.add("rosa magenta")
    }
    if (l > 0.7) w.add("claro pastel")
    if (l < 0.3) w.add("oscuro")
    return w.joinToString(" ")
}

/** Deduce el tipo de pintura por el nombre y la línea (misma regla que tools/build_catalog.py). */
fun guessType(name: String, line: String): String {
    val s = norm("$name $line")
    return when {
        "primer" in s || "imprim" in s -> "Imprimación"
        "varnish" in s || "barniz" in s || "'ardcoat" in s || "lahmian medium" in s -> "Barniz"
        "medium" in s || "thinner" in s -> "Médium"
        "contrast" in s || "speedpaint" in s || "xpress" in s -> "Contraste"
        "wash" in s || "shade" in s -> "Wash"
        Regex("\\bink\\b|tinta").containsMatchIn(s) -> "Tinta"
        listOf("metal", "gold", "silver", "bronze", "copper", "brass", "steel", "iron", "chrome", "oro", "plata").any { it in s } -> "Metálica"
        Regex("\\bair\\b").containsMatchIn(s) -> "Aerógrafo"
        else -> "Opaca"
    }
}

/** Convierte lo que venga en una columna de color ("#E09959", "e09959", "224,153,89") a #RRGGBB, o null. */
fun parseColorCell(s: String): String? {
    val t = s.trim()
    Regex("#?([0-9A-Fa-f]{6})\\b").find(t)?.let { return "#" + it.groupValues[1].uppercase() }
    val nums = Regex("\\d{1,3}").findAll(t).map { it.value.toInt() }.toList()
    if (nums.size == 3 && nums.all { it in 0..255 }) return String.format("#%02X%02X%02X", nums[0], nums[1], nums[2])
    return null
}

/** True si todas las palabras de la búsqueda aparecen en el texto. */
fun matchesQuery(hay: String, query: String): Boolean {
    val words = norm(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    return words.all { hay.contains(it) }
}
