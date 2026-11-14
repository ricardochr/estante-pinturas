package com.ricardochr.estantepinturas

/**
 * Todo lo tuyo que apunta a una pintura por su id. Cuando el catálogo se actualiza, una pintura puede
 * cambiar de id (cambió de línea o de nombre) o desaparecer; aquí se reasignan todas esas referencias
 * de una vez para que nada quede apuntando a una pintura que ya no existe.
 */
data class UserRefs(
    val owned: Map<String, OwnedEntry>,
    val barcodes: Map<String, String>,
    val colorFixes: Map<String, String>,
    val shopping: List<ShopItem>,
    val locations: Map<String, PaintLocation>,
    val projects: List<Project>,
    val customSets: List<PaintSet>,
)

/** Ids a los que apunta algo tuyo y que deben existir (las correcciones de color solo se mueven si la pintura sigue). */
fun referencedIds(r: UserRefs): Set<String> {
    val s = HashSet<String>()
    s += r.owned.keys
    s += r.barcodes.values
    r.shopping.forEach { s += it.paintId }
    s += r.locations.keys
    r.projects.forEach { p ->
        p.paints.forEach { s += it.paintId }
        p.recipes.forEach { m -> m.parts.forEach { s += it.paintId } }
    }
    r.customSets.forEach { s += it.paintIds }
    return s
}

/** Aplica [remap] (id viejo → id nuevo) a todas las referencias, uniendo lo que quede duplicado. */
fun applyRemap(r: UserRefs, remap: Map<String, String>): UserRefs {
    if (remap.isEmpty()) return r
    fun id(x: String) = remap[x] ?: x
    val owned = LinkedHashMap<String, OwnedEntry>()
    r.owned.values.forEach { e ->
        val nid = id(e.paintId)
        val prev = owned[nid]
        owned[nid] = if (prev == null) e.copy(paintId = nid)
        else prev.copy(
            qty = prev.qty + e.qty,
            low = prev.low || e.low,
            notes = listOf(prev.notes, e.notes).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            thin = prev.thin.ifBlank { e.thin },
            updatedAt = maxOf(prev.updatedAt, e.updatedAt),
        )
    }
    // Una corrección de color se mueve a la pintura nueva solo si esa no tiene ya la suya
    val fixes = LinkedHashMap<String, String>()
    r.colorFixes.forEach { (k, v) -> if (k !in remap) fixes[k] = v }
    r.colorFixes.forEach { (k, v) -> remap[k]?.let { n -> if (n !in fixes) fixes[n] = v } }
    return UserRefs(
        owned = owned,
        barcodes = r.barcodes.mapValues { (_, v) -> id(v) },
        colorFixes = fixes,
        shopping = r.shopping.map { it.copy(paintId = id(it.paintId)) }.distinctBy { it.paintId },
        locations = r.locations.entries.associate { (k, v) -> id(k) to v.copy(paintId = id(k)) },
        projects = r.projects.map { p ->
            p.copy(
                paints = p.paints.map { it.copy(paintId = id(it.paintId)) }.distinctBy { it.paintId },
                recipes = p.recipes.map { m -> m.copy(parts = m.parts.map { it.copy(paintId = id(it.paintId)) }) },
            )
        },
        customSets = r.customSets.map { s -> s.copy(paintIds = s.paintIds.map { id(it) }.distinct()) },
    )
}
