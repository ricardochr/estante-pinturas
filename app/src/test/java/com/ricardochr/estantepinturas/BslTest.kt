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

class FanaticTriadsTest {
    private fun wp(n: Int, name: String, line: String = "Warpaints Fanatic") =
        Paint("The Army Painter|$line|$name|WP3%03d".format(n), "The Army Painter", line, name, "WP3%03d".format(n), "#336633", "Opaca")

    @Test fun sonVeintisieteFamiliasDeSeis() {
        assertEquals(27, FANATIC_FAMILIES.size)
        assertTrue(FANATIC_FAMILIES.all { it.codes.size == 6 })
        assertEquals(162, FANATIC_FAMILIES.flatMap { it.codes }.distinct().size)
        // Deep Greens: WP3049–WP3054, guardada de clara a oscura
        assertEquals(listOf("WP3054", "WP3053", "WP3052", "WP3051", "WP3050", "WP3049"), FANATIC_FAMILIES[8].codes)
        assertEquals(108, FANATIC_TRIADS.size)
    }

    @Test fun sombraYLuzSonLosVecinos() {
        val greenskin = wp(51, "Greenskin")
        val (sh, li) = bslTonesFor(greenskin, listOf(greenskin, wp(50, "Guardian Green"), wp(52, "Eternal Hunt")))!!
        assertEquals("Guardian Green", sh?.name)
        assertEquals("Eternal Hunt", li?.name)
        // Las metálicas o de otra línea no son parte de las familias
        assertNull(bslTonesFor(wp(51, "Greenskin", "Warpaints Fanatic Metallic").copy(code = "WP3190"), listOf(greenskin)))
    }

    @Test fun todasEstanEnElCatalogo() {
        val f = listOf(File("src/main/assets/catalog.json"), File("app/src/main/assets/catalog.json")).first { it.exists() }
        val arr = JSONArray(f.readText())
        val codes = HashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONArray(i)
            if (r.getString(0) == "The Army Painter" && r.getString(1).startsWith("Warpaints Fanatic")) codes += r.getString(3)
        }
        assertEquals(emptySet<String>(), FANATIC_FAMILIES.flatMap { it.codes }.toSet() - codes)
    }
}

class CalculatedTriadsTest {
    private fun pa(name: String, hex: String) = Paint("Pro Acryl|Pro Acryl|$name|", "Pro Acryl", "Pro Acryl", name, "", hex, "Opaca")

    @Test fun calculaSombraYLuzDeLaMismaMarca() {
        val red = pa("Bold Pyrrole Red", "#C8252B")
        val dark = pa("Dark Red", "#7A1A1E")
        val light = pa("Coral", "#E86A5A")
        val blue = pa("Blue", "#2B4BA8")
        val s = calculatedSystem("Pro Acryl", listOf(red, dark, light, blue), listOf(red))!!
        assertTrue(!s.official)
        val t = s.triads.single()
        assertEquals(red.id, t.base)
        assertEquals(dark.id, t.shadow)
        assertEquals(light.id, t.light)
        // Las familias van de clara a oscura
        val reds = s.families.first { red.id in it.codes }.codes
        assertEquals(listOf(light.id, red.id, dark.id), reds)
    }

    @Test fun losSistemasSonDeLasMarcasQueTienes() {
        val gc = Paint("Vallejo|Game Color|Moon Yellow|72.005", "Vallejo", "Game Color", "Moon Yellow", "72.005", "#F9E04A", "Opaca")
        val red = pa("Bold Pyrrole Red", "#C8252B")
        val ids = triadSystemsFor(listOf(gc, red, red.copy(id = "x", name = "Otra")), listOf(gc, red)).map { it.id }
        // Los oficiales siempre están; primero los que tienen más pinturas tuyas
        assertEquals(listOf("mix-opaca", "calc-" + brandKey("Pro Acryl"), "vallejo-bsl", "vallejo-tmm", "fanatic"), ids)
    }
}

class TrueMetallicTest {
    private fun tmm(code: String, name: String, type: String = "Metálica") =
        Paint("Vallejo|True Metallic Metal|$name|$code", "Vallejo", "True Metallic Metal", name, code, "#C8813E", type)

    @Test fun veinteFamiliasDeCuatro() {
        assertEquals(20, TMM_FAMILIES.size)
        assertEquals(80, TMM_FAMILIES.flatMap { it.codes }.distinct().size)
        // Imperial Gold: Light 77.103, Base 77.123, Shade 77.143, Airbrush 77.163
        assertEquals(listOf("77.103", "77.123", "77.143", "77.163"), TMM_FAMILIES[2].codes)
    }

    @Test fun sombraYLuzDeLaFamilia() {
        val base = tmm("77.123", "Imperial Gold (Base)")
        val (sh, li) = bslTonesFor(base, listOf(base, tmm("77.143", "Imperial Gold (Shade)", "Wash"), tmm("77.103", "Imperial Gold (Light)")))!!
        assertEquals("Imperial Gold (Shade)", sh?.name)
        assertEquals("Imperial Gold (Light)", li?.name)
        // Metal Color (77.7xx) no es parte del sistema
        assertNull(bslTonesFor(Paint("v", "Vallejo", "Metal Color", "Gold", "77.725", "#C8A040", "Metálica"), listOf(base)))
    }

    @Test fun estanEnElCatalogoConSuTipo() {
        val f = listOf(File("src/main/assets/catalog.json"), File("app/src/main/assets/catalog.json")).first { it.exists() }
        val arr = JSONArray(f.readText())
        val byCode = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONArray(i)
            if (r.getString(0) == "Vallejo" && r.getString(1) == "True Metallic Metal") byCode[r.getString(3)] = r.getString(2)
        }
        assertEquals(emptySet<String>(), TMM_FAMILIES.flatMap { it.codes }.toSet() - byCode.keys)
        assertEquals("Sterling Silver (Shade)", byCode["77.141"])
    }
}

class MixedTriadsTest {
    private fun p(brand: String, line: String, name: String, hex: String, type: String = "Opaca") =
        Paint("$brand|$line|$name|", brand, line, name, "", hex, type)

    @Test fun combinaMarcasSoloConPinturasCompatibles() {
        val base = p("Pro Acryl", "Pro Acryl", "Bold Pyrrole Red", "#C8252B")
        val shadow = p("Vallejo", "Game Color", "Gory Red", "#7A1A1E")
        val light = p("Scale75", "Scalecolor", "Coral", "#E86A5A")
        // Parecidas en color pero de otro tipo: no deben usarse
        val fluor = p("Pro Acryl", "Fluorescent", "Fluorescent Red", "#E86A5A")
        val speed = p("The Army Painter", "Speedpaint 2.0", "Blood Red", "#7A1A1E", "Contraste")
        val wash = p("Vallejo", "Wash FX", "Red Wash", "#7A1A1E", "Wash")
        val s = mixedSystem(listOf(base, shadow, light, fluor, speed, wash), MixKind.OPACA)!!
        val t = s.triads.first { it.base == base.id }
        assertEquals(shadow.id, t.shadow)
        assertEquals(light.id, t.light)
        assertTrue(listOf(fluor, speed, wash).none { s.matches(it) })
    }

    @Test fun trueMetallicMetalEsAcrilicoAlAguaYTrueMetalDeAkEsCera() {
        assertEquals(Chemistry.WATER_ACRYLIC, chemistryOf(p("Vallejo", "True Metallic Metal", "Imperial Gold (Base)", "#C8813E", "Metálica")))
        assertEquals(Chemistry.ENAMEL, chemistryOf(p("AK Interactive", "True Metal (Wax)", "Gold", "#C8A040", "Metálica")))
    }

    @Test fun lasMetalicasVanAparte() {
        val gold = p("Vallejo", "True Metallic Metal", "Imperial Gold (Base)", "#C8813E", "Metálica")
        val dark = p("Kaleido Colorworks", "Metal Colors", "Dark Gold", "#7A5020", "Metálica")
        val light = p("Pro Acryl", "Pro Acryl", "Bright Gold", "#F0C070", "Metálica")
        val opaque = p("Pro Acryl", "Pro Acryl", "Golden Brown", "#7A5020")
        val s = mixedSystem(listOf(gold, dark, light, opaque), MixKind.METALICA)!!
        val t = s.triads.first { it.base == gold.id }
        assertEquals(dark.id, t.shadow)
        assertEquals(light.id, t.light)
        assertTrue(!s.matches(opaque))
        // Con una sola marca no hay nada que combinar
        assertNull(mixedSystem(listOf(gold, gold.copy(id = "b"), gold.copy(id = "c")), MixKind.METALICA))
    }
}
