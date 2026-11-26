package com.ricardochr.estantepinturas

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BslTest {
    private fun gc(code: String, name: String, hex: String = "#808080") =
        Paint("Vallejo|Game Color|$name|$code", "Vallejo", "Game Color", name, code, hex, "Opaca")

    @Test fun elSetCompletoTiene48ColoresEn12Familias() {
        val big = BSL_FAMILIES.filter { it.inBigSet }
        assertEquals(12, big.size)
        assertTrue(big.all { it.codes.size == 4 })
        assertEquals(48, big.flatMap { it.codes }.distinct().size)
        assertEquals(32, BSL_TRIADS.size)
    }

    @Test fun papelesDeUnaPintura() {
        val uses = bslTriadsWith("72.005").map { it.second }.toSet() // Moon Yellow
        assertEquals(setOf(BslRole.BASE, BslRole.LIGHT), uses)
    }

    @Test fun sombraYLuzSalenDeLaTriadaConMasPinturasTuyas() {
        val sun = gc("72.006", "Sun Yellow")
        val moon = gc("72.005", "Moon Yellow")
        val gold = gc("72.007", "Gold Yellow")
        val scro = gc("72.038", "Scrofulous Brown")
        // Sun Yellow es base en dos tríadas: con Gold Yellow o con Scrofulous Brown de sombra
        val (sh1, li1) = bslTonesFor(sun, listOf(sun, moon, gold))!!
        assertEquals("Gold Yellow", sh1?.name)
        assertEquals("Moon Yellow", li1?.name)
        val (sh2, _) = bslTonesFor(sun, listOf(sun, moon, scro))!!
        assertEquals("Scrofulous Brown", sh2?.name)
        // Sin pinturas de la tríada: no propone nada propio
        val (sh3, li3) = bslTonesFor(sun, listOf(sun))!!
        assertNull(sh3); assertNull(li3)
        // Otra marca o una pintura que no es base de ninguna tríada
        assertNull(bslTonesFor(Paint("x", "Pro Acryl", "", "Sun", "MPA-001", "#FFFF00", "Opaca"), listOf(moon)))
        assertNull(bslTonesFor(gc("72.007", "Gold Yellow"), listOf(moon)))
    }

    @Test fun tonesForUsaBslAntesQueElColor() {
        val sun = gc("72.006", "Sun Yellow", "#F5C500")
        val gold = gc("72.007", "Gold Yellow", "#E89B00")
        val moon = gc("72.005", "Moon Yellow", "#F9E04A")
        val (sh, li) = tonesFor(sun, listOf(sun, gold, moon))
        assertEquals("Gold Yellow", sh?.name)
        assertEquals("Moon Yellow", li?.name)
    }

    @Test fun escalaEncadenada() {
        assertEquals(listOf("72.007", "72.006", "72.005", "72.109"), bslRamp("72.006"))
    }

    @Test fun todosLosColoresEstanEnElCatalogo() {
        val f = listOf(File("src/main/assets/catalog.json"), File("app/src/main/assets/catalog.json")).first { it.exists() }
        val arr = JSONArray(f.readText())
        val codes = HashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONArray(i)
            if (r.getString(0) == "Vallejo" && r.getString(1) == "Game Color") codes += r.getString(3)
        }
        val needed = (BSL_TRIADS.flatMap { it.codes } + BSL_FAMILIES.flatMap { it.codes }).toSet()
        assertEquals(emptySet<String>(), needed - codes)
    }
}
