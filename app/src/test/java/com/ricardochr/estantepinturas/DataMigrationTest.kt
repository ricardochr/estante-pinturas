package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuando una pintura cambia de id al actualizar el catálogo, nada tuyo debe quedar apuntando al id viejo. */
class DataMigrationTest {
    private val refs = UserRefs(
        owned = mapOf("viejo" to OwnedEntry("viejo", 1, "a", 1L, thin = "1:1"), "nuevo" to OwnedEntry("nuevo", 2, "b", 2L)),
        barcodes = mapOf("111" to "viejo"),
        colorFixes = mapOf("viejo" to "#123456"),
        shopping = listOf(ShopItem("viejo", "", 1L), ShopItem("nuevo", "", 2L)),
        locations = mapOf("viejo" to PaintLocation("viejo", "gaveta", 0.5f, 0.5f)),
        projects = listOf(
            Project(
                "p", "P", 0L, "En progreso", "", null, listOf(ProjectPaint("viejo", "base")), emptyList(),
                recipes = listOf(SavedMix("r", "R", listOf(MixIngredient("viejo", 1)), "#000000", "")),
            ),
        ),
        customSets = listOf(PaintSet("Mía", "Set", "", listOf("viejo", "nuevo"), custom = true, id = "s")),
    )

    @Test fun todoSeReasigna() {
        val r = applyRemap(refs, mapOf("viejo" to "nuevo"))
        val ids = referencedIds(r)
        assertTrue("viejo" !in ids)
        // Los botes se suman y se conserva la nota de dilución
        assertEquals(3, r.owned["nuevo"]!!.qty)
        assertEquals("1:1", r.owned["nuevo"]!!.thin)
        assertEquals("nuevo", r.barcodes["111"])
        assertEquals("#123456", r.colorFixes["nuevo"])
        assertEquals(1, r.shopping.size)
        assertEquals("nuevo", r.locations.keys.single())
        assertEquals("nuevo", r.projects[0].paints[0].paintId)
        assertEquals("nuevo", r.projects[0].recipes[0].parts[0].paintId)
        assertEquals(listOf("nuevo"), r.customSets[0].paintIds)
    }

    @Test fun correccionPropiaNoSePisa() {
        val r = applyRemap(refs.copy(colorFixes = mapOf("viejo" to "#111111", "nuevo" to "#222222")), mapOf("viejo" to "nuevo"))
        assertEquals("#222222", r.colorFixes["nuevo"])
    }
}
