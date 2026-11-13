package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeZonesTest {
    private fun paint(id: String, hex: String) = Paint(id, "Marca", "Línea", id, "", hex, "Opaca")
    private fun project(vararg uses: Pair<String, String>) =
        Project("p", "Prueba", 0, "En progreso", "", null, uses.map { ProjectPaint(it.first, it.second) }, emptyList())

    private val byId = listOf(
        paint("piel", "#C8906C"), paint("pielS", "#7A4A34"), paint("pielL", "#E8C4A8"),
        paint("a", "#303030"), paint("b", "#707070"), paint("c", "#C0C0C0"),
    ).associateBy { it.id }

    @Test fun rolesEscritos() {
        val z = schemeZones(project("piel" to "Piel · base", "pielS" to "Piel · sombra", "pielL" to "Piel · luz"), byId)
        assertEquals(1, z.size)
        assertEquals("Piel", z[0].name)
        assertEquals("piel", z[0].base?.id)
        assertEquals("pielS", z[0].shadow?.id)
        assertEquals("pielL", z[0].light?.id)
    }

    @Test fun sinRolesSeOrdenaPorClaridad() {
        val z = schemeZones(project("c" to "Metal", "a" to "Metal", "b" to "Metal"), byId)
        assertEquals("b", z[0].base?.id)
        assertEquals("a", z[0].shadow?.id)
        assertEquals("c", z[0].light?.id)
    }

    @Test fun paletaVaDeOscuroAClaro() {
        val z = schemeZones(project("piel" to "Piel · base", "pielS" to "Piel · sombra", "pielL" to "Piel · luz"), byId)[0]
        for (n in listOf(3, 5)) {
            val p = z.palette(n)!!
            assertEquals(n, p.size)
            val ls = p.map { hexToLab(String.format("#%06X", it and 0xFFFFFF)).l }
            assertTrue(ls.zipWithNext().all { (a, b) -> a <= b + 0.5 })
        }
    }
}
