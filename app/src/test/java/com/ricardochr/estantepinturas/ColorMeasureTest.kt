package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ColorMeasureTest {
    private fun lin(hex: String): DoubleArray {
        val v = parseHex(hex)
        fun f(c: Long): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return doubleArrayOf(f((v shr 16) and 0xFF), f((v shr 8) and 0xFF), f(v and 0xFF))
    }

    private fun paint(id: String, hex: String) = Paint(id, "Marca", "Línea", id, "", hex, "Opaca")

    /** Lo que vería una cámara con luz de color [cast] y exposición [exp]. */
    private fun camera(hex: String, cast: DoubleArray, exp: Double): SampleReading {
        val l = lin(hex)
        return SampleReading(l[0] * cast[0] * exp, l[1] * cast[1] * exp, l[2] * cast[2] * exp, emptyList())
    }

    private val refs = listOf("#E8E4D8", "#2A2A2E", "#B03A2E", "#3A6EA5", "#5E8C3A").mapIndexed { i, h -> paint("r$i", h) }
    private val warm = doubleArrayOf(1.25, 1.0, 0.7)

    @Test fun recuperaElColorConLuzCalida() {
        val target = "#C49A6C"
        val take = measureTake(camera(target, warm, 0.8), refs.map { it to camera(it.hex, warm, 0.8) })
        assertNotNull(take)
        val d = deltaE2000(hexToLab(take!!.hex), hexToLab(target))
        assertTrue("ΔE $d", d < 2.0)
        assertTrue(take.meanError < 2.0)
    }

    @Test fun papelBlancoAnclaElBalance() {
        val target = "#6A8F9C"
        val paper = camera("#F2F2F2", warm, 0.8)
        val take = measureTake(camera(target, warm, 0.8), refs.take(3).map { it to camera(it.hex, warm, 0.8) }, paper)
        assertNotNull(take)
        assertTrue(take!!.paperUsed)
        assertTrue(deltaE2000(hexToLab(take.hex), hexToLab(target)) < 3.0)
    }

    @Test fun papelQueNoEsBlancoSeIgnora() {
        val notWhite = SampleReading(0.6, 0.1, 0.05, emptyList())
        assertNull(paperGains(notWhite))
    }

    @Test fun faltanReferencias() {
        val take = measureTake(camera("#808080", warm, 1.0), refs.take(2).map { it to camera(it.hex, warm, 1.0) })
        assertNull(take)
    }

    @Test fun unaSolaTomaNoEsAlta() {
        val take = measureTake(camera("#C49A6C", warm, 0.8), refs.map { it to camera(it.hex, warm, 0.8) })!!
        val res = combineTakes(listOf(take))!!
        assertEquals(MeasureConfidence.MEDIA, res.confidence)
        val res2 = combineTakes(listOf(take, take))!!
        assertEquals(MeasureConfidence.ALTA, res2.confidence)
    }

    @Test fun referenciasIncoherentesSeRechazan() {
        // Una referencia con el color equivocado (otra pintura) hace fallar la comprobación
        val bad = refs.mapIndexed { i, p -> if (i == 2) p to camera("#20C040", warm, 0.8) else p to camera(p.hex, warm, 0.8) }
        val take = measureTake(camera("#C49A6C", warm, 0.8), bad)!!
        assertTrue(take.maxError > 10)
    }
}
