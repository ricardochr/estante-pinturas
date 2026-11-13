package com.ricardochr.estantepinturas

/**
 * Zonas de color de un proyecto para "Luz y sombra · con tu esquema": por cada parte de la figura
 * (piel, armadura…) la pintura de base, la de sombra y la de luz.
 */
data class SchemeZone(val name: String, val base: Paint?, val shadow: Paint?, val light: Paint?) {
    private fun shift(p: Paint, dl: Double): Int {
        val l = p.lab
        return labToArgb((l.l + dl).coerceIn(2.0, 98.0), l.a, l.b)
    }

    /** Colores para el mapa de [zones] tonos, de más oscuro a más claro. */
    fun palette(zones: Int): IntArray? {
        val b = base ?: shadow ?: light ?: return null
        val sh = shadow ?: b
        val li = light ?: b
        fun argb(p: Paint) = p.argb.toInt()
        return if (zones == 3) {
            intArrayOf(if (shadow != null) argb(sh) else shift(b, -18.0), argb(b), if (light != null) argb(li) else shift(b, 15.0))
        } else {
            intArrayOf(
                shift(sh, -14.0),
                if (shadow != null) argb(sh) else shift(b, -18.0),
                argb(b),
                if (light != null) argb(li) else shift(b, 15.0),
                shift(li, 12.0),
            )
        }
    }

    /** Qué pintura va en cada tono (null = mezcla aclarando u oscureciendo la vecina). */
    fun names(zones: Int): List<String?> = if (zones == 3) listOf(shadow?.name, base?.name, light?.name)
    else listOf(null, shadow?.name, base?.name, light?.name, null)
}

// i18n:off — palabras de los usos guardados en el proyecto (en español o inglés), no textos de la interfaz
private val SHADOW_WORDS = listOf("sombra", "shadow", "shade")
private val LIGHT_WORDS = listOf("luz", "light", "highlight", "brillo", "iluminacion")
private val BASE_WORDS = listOf("base", "basecoat")
// i18n:on

/** Agrupa las pinturas del proyecto por zona y asigna base, sombra y luz. */
fun schemeZones(project: Project, byId: Map<String, Paint>): List<SchemeZone> {
    class Item(val paint: Paint, val group: String, val role: Char?)
    val items = project.paints.mapNotNull { pp ->
        val p = byId[pp.paintId] ?: return@mapNotNull null
        if (!p.hasColor) return@mapNotNull null
        val parts = pp.use.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        var role: Char? = null
        val rest = ArrayList<String>()
        for (part in parts) {
            val n = norm(part)
            val r = when {
                SHADOW_WORDS.any { n == it || n.startsWith("$it ") } -> 'S'
                LIGHT_WORDS.any { n == it || n.startsWith("$it ") } -> 'L'
                BASE_WORDS.any { n == it } -> 'B'
                else -> null
            }
            if (r != null && role == null) role = r else rest.add(part)
        }
        Item(p, rest.joinToString(" · ").ifBlank { "" }, role)
    }
    if (items.isEmpty()) return emptyList()
    val groups = LinkedHashMap<String, MutableList<Item>>()
    for (it in items) groups.getOrPut(it.group) { ArrayList() }.add(it)
    return groups.map { (name, list) ->
        var base = list.firstOrNull { it.role == 'B' }?.paint
        var shadow = list.firstOrNull { it.role == 'S' }?.paint
        var light = list.firstOrNull { it.role == 'L' }?.paint
        val free = list.filter { it.role == null }.map { it.paint }.sortedBy { it.lab.l }
        // Sin rol escrito: la más oscura es sombra, la más clara luz, la del medio base
        val pool = free.toMutableList()
        if (base == null && pool.isNotEmpty()) {
            // Con 3 o más, la del medio; con 1 o 2, la más clara (la otra queda de sombra)
            base = if (pool.size >= 3) pool.removeAt(pool.size / 2) else pool.removeAt(pool.size - 1)
        }
        val b = base
        if (shadow == null) pool.firstOrNull { b == null || it.lab.l < b.lab.l }?.let { shadow = it; pool.remove(it) }
        if (light == null) pool.lastOrNull { b == null || it.lab.l > b.lab.l }?.let { light = it; pool.remove(it) }
        SchemeZone(name, base, shadow, light)
    }
}
