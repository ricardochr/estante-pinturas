package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Etiquetas leídas a medias, como salen de una foto real de una gaveta. */
class LabelMatchTest {
    private fun p(id: String, brand: String, name: String, code: String = "", line: String = "L") = Paint(id, brand, line, name, code, "#808080", "Opaca")
    private fun b(text: String, x: Float = 0.5f, y: Float = 0.5f) = TextBlockAt(text.split("\n"), x, y)

    private val inv = listOf(
        p("earth", "Vallejo", "European Earth", "74.521"),
        p("guardian", "The Army Painter", "Guardian Green", "WP3052"),
        p("greenskin", "The Army Painter", "Greenskin", "WP3054"),
        p("rust", "AK Interactive", "Light Rust Wash", "AK19316"),
        p("blackV", "Vallejo", "Black", "72.051"),
        p("blackA", "The Army Painter", "Black", "WP3002"),
        p("red", "Vallejo", "Red", "72.010"),
    )

    @Test fun nombreDentroDeUnaLineaLarga() {
        val m = matchLabels(listOf(b("74 Tierra Europea / European Earth")), inv)
        assertEquals("earth", m.values.single().paint.id)
    }

    @Test fun letrasMalLeidas() {
        assertEquals("guardian", matchLabels(listOf(b("Guardlan Green")), inv).values.single().paint.id)
        assertEquals("greenskin", matchLabels(listOf(b("Greenskln")), inv).values.single().paint.id)
    }

    @Test fun codigoConLetrasConfundidas() {
        val m = matchLabels(listOf(b("AKI93I6 LIGHT")), inv)
        assertEquals("rust", m.values.single().paint.id)
        assertTrue(m.values.single().byCode)
        assertEquals("earth", matchLabels(listOf(b("74.52I")), inv).values.single().paint.id)
    }

    @Test fun mismaPalabraEnDosMarcasDecideLaMarcaCercana() {
        val blocks = listOf(b("Black", 0.5f, 0.5f), b("WARPAINTS fanatic\nThe Army Painter", 0.5f, 0.47f), b("Vallejo", 0.1f, 0.1f))
        assertEquals("blackA", matchLabels(blocks, inv).values.single { it.paint.name == "Black" }.paint.id)
    }

    @Test fun nombresCortosYTextoAjenoNoCoinciden() {
        assertNull(matchLabels(listOf(b("Red")), inv)["red"])
        assertTrue(matchLabels(listOf(b("ACRYLIC WASH 17 ml")), inv).isEmpty())
    }
}
