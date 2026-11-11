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
    /** "" = medido, "f" = estimado de la foto del producto, "n" = típico según el nombre, "u" = corregido por ti. */
    val quality: String = "",
) {
    /** Color ARGB para dibujar la muestra. */
    val argb: Long by lazy { parseHex(hex) }

    /** Texto normalizado donde se busca: marca, línea, nombre, código, tipo y familia de color. */
    val searchText: String by lazy {
        norm(listOf(brand, line, name, code, type, codeKey(code).lowercase(), colorWords(hex), finishWords(this)).joinToString(" "))
    }

    /** Color en CIELAB, para comparar colores. */
    val lab: Lab by lazy { hexToLab(hex) }

    val hasColor: Boolean get() = !hex.equals(UNKNOWN_HEX, true)

    /** Marca · línea · tipo. El tipo se muestra en el idioma de la app; marca y línea nunca se traducen. */
    val subtitle: String get() = listOf(brand, line, trData(type)).filter { it.isNotBlank() }.joinToString(" · ")
}

data class OwnedEntry(
    val paintId: String,
    val qty: Int,
    val notes: String,
    val updatedAt: Long,
    /** Marcada como "se está acabando": aparece en la lista de compras. */
    val low: Boolean = false,
    /** Tu nota de dilución para esta pintura ("1:2 con flow improver en mi aerógrafo de 0.3"). */
    val thin: String = "",
)

/**
 * Equipo de pintura: un aerógrafo (con su boquilla en mm) o un compresor (con o sin tanque).
 * [kind] = "airbrush" o "compressor".
 */
data class Gear(val id: String, val kind: String, val name: String, val nozzle: Double = 0.0, val tank: Boolean = false)

/** Un set de pinturas de una tienda (por ejemplo "PRO Acryl Intro Set") y las pinturas que trae. */
data class PaintSet(
    val brand: String,
    val name: String,
    val code: String,
    val paintIds: List<String>,
    /** Creado o editado por ti (se guarda en tu inventario y en el respaldo). */
    val custom: Boolean = false,
    val id: String = "",
    /** Si editaste un set de la tienda, la clave del original al que reemplaza. */
    val replaces: String? = null,
    /** Cuántas pinturas anuncia la tienda (0 = no lo dice). */
    val expected: Int = 0,
    /** Validación del set: "ok", "parcial" o "revisar". */
    val status: String = "ok",
    /** Por qué tiene ese estado. */
    val note: String = "",
    /** De dónde salió: tienda oficial o el minorista que lo vende. */
    val source: String = "",
) {
    val key: String get() = "$brand|$name"
    /** Identificador único para la pantalla: los tuyos por id, los de la tienda por marca y nombre. */
    val uid: String get() = if (custom) "c:$id" else "o:$key"
}

/** Algo por comprar: una pintura del catálogo y por qué. */
data class ShopItem(val paintId: String, val reason: String, val addedAt: Long)

/** Pintura usada en un proyecto y para qué (base de la piel, sombra del pelo…). */
data class ProjectPaint(val paintId: String, val use: String)

/** Foto de avance de un proyecto (archivo en la carpeta del proyecto). */
data class ProgressPhoto(val file: String, val takenAt: Long, val caption: String)

/** Una figura que estás pintando: referencia, pinturas, notas y fotos de avance. */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val status: String,          // "En progreso" o "Terminado"
    val notes: String,
    val reference: String?,      // archivo de la imagen de referencia
    val paints: List<ProjectPaint>,
    val photos: List<ProgressPhoto>,
    /** Plan de pintura: pasos por zona que vas marcando conforme avanzas. */
    val plan: List<PlanStep> = emptyList(),
    /** Recetas de mezcla guardadas para repetirlas después. */
    val recipes: List<SavedMix> = emptyList(),
)

/** Un paso del plan de pintura de un proyecto. [zone] agrupa los pasos ("Zona 1 · Piel"). */
data class PlanStep(val id: String, val zone: String, val text: String, val done: Boolean = false)

/** Parte de una receta: una pintura y cuántas partes (gotas) lleva. */
data class MixIngredient(val paintId: String, val parts: Int)

/** Receta de mezcla guardada: ingredientes, color que resulta y para qué la usas. */
data class SavedMix(val id: String, val name: String, val parts: List<MixIngredient>, val hex: String, val note: String)

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
        k.contains("akrealcolor") -> "akinteractive"
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

// i18n:off — palabras de búsqueda en español e inglés; no son textos de la interfaz
/** Palabras de familia de color para poder buscar "rojo", "piel", "azul" (o "red", "skin", "blue")... */
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
        w.add(if (l > 0.85) "blanco white" else if (l < 0.15) "negro black" else "gris gray grey")
        return w.joinToString(" ")
    }
    when {
        hue < 15 || hue >= 345 -> w.add("rojo red")
        hue < 40 -> { w.add(if (l < 0.45) "cafe marron brown" else "naranja orange"); w.add("piel skin flesh") }
        hue < 65 -> { w.add("amarillo yellow"); w.add(if (l < 0.5) "ocre cafe ochre brown" else "piel crema skin cream") }
        hue < 165 -> w.add("verde green")
        hue < 195 -> w.add("turquesa cian turquoise cyan teal")
        hue < 255 -> w.add("azul blue")
        hue < 290 -> w.add("morado violeta purple violet")
        else -> w.add("rosa magenta pink")
    }
    if (l > 0.7) w.add("claro pastel light")
    if (l < 0.3) w.add("oscuro dark")
    return w.joinToString(" ")
}
// i18n:on

/** Deduce el tipo de pintura por el nombre y la línea (misma regla que tools/build_catalog.py). */
fun guessType(name: String, line: String): String {
    val s = norm("$name $line")
    return when {
        "primer" in s || "imprim" in s -> "Imprimación"
        "varnish" in s || "barniz" in s || "'ardcoat" in s || "lahmian medium" in s -> "Barniz"
        "thinner" in s || ("medium" in s && !Regex("^\\s*medium\\s+\\w").containsMatchIn(norm(name))) -> "Médium"
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
fun matchesQuery(hay: String, query: String): Boolean = matchesWords(hay, queryWords(query))

/** Palabras normalizadas de una búsqueda; se calcula una sola vez por búsqueda. */
fun queryWords(query: String): List<String> = norm(query).split(' ').filter { it.isNotBlank() }

fun matchesWords(hay: String, words: List<String>): Boolean {
    for (w in words) if (!hay.contains(w)) return false
    return true
}

// i18n:off — palabras de búsqueda
/** Palabras para buscar por acabado: "brillante"/"gloss", "mate"/"matte", "satinado"/"satin". */
fun finishWords(p: Paint): String = when (finishOf(p).finish) {
    Finish.BRILLANTE -> "brillante gloss"
    Finish.MATE -> "mate matte flat"
    Finish.SATINADO -> "satinado satin"
    Finish.DESCONOCIDO -> ""
}
// i18n:on
