package com.ricardochr.estantepinturas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixingTest {
    private fun p(id: String, hex: String, type: String = "Opaca", brand: String = "Vallejo", line: String = "Game Color", name: String = id, quality: String = "") =
        Paint(id, brand, line, name, "", hex, type, quality = quality)

    private val red = p("rojo", "#C02020")
    private val yellow = p("amarillo", "#F0D020")
    private val blue = p("azul", "#2040C0")
    private val white = p("blanco", "#F4F4F0")

    @Test fun noSeMezclanEspeciales() {
        assertFalse(mixable(p("oro", "#C8A040", type = "Metálica")))
        assertFalse(mixable(p("neon", "#40FF40", name = "Fluorescent Green")))
        assertFalse(mixable(p("tinta", "#204080", line = "Game Ink", name = "Blue Ink")))
        assertFalse(mixable(p("tamiya", "#C02020", brand = "Tamiya", line = "Tamiya Color")))
        assertTrue(mixable(red))
    }

    @Test fun brillanteConMateNo() {
        val gloss = p("g", "#C02020", brand = "Kaleido", line = "Mecha")
        assertFalse(finishesCompatible(gloss, red))
        assertNotNull(mixWarning(listOf(gloss, red)))
    }

    @Test fun mezclasSoloConPinturasValidas() {
        val metal = p("oro", "#E0A030", type = "Metálica")
        val guessed = p("adivinado", "#E08020", quality = "n")
        val recipes = findMixes(hexToLab("#E07020"), listOf(red, yellow, blue, white, metal, guessed))
        assertTrue(recipes.isNotEmpty())
        recipes.forEach { r -> r.parts.forEach { assertTrue(it.paint.id != "oro" && it.paint.id != "adivinado") } }
    }

    @Test fun avisaColorAproximado() {
        assertNotNull(mixWarning(listOf(red, p("foto", "#2040C0", quality = "f"))))
    }

    @Test fun basesQuimicasDistintas() {
        val lacquer = p("laca", "#C02020", brand = "Tamiya", line = "Tamiya Spray")
        assertNotNull(mixWarning(listOf(red, lacquer)))
    }
}
