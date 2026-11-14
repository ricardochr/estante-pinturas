package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RepaintTest {
    // Zona roja: sombra (0), base (1), luz (2)
    private val cur = mapOf(0 to Lab(25.0, 40.0, 25.0), 1 to Lab(45.0, 55.0, 35.0), 2 to Lab(70.0, 35.0, 25.0))
    private val group = listOf(1 to listOf(0, 1, 2))
    private val blue = Lab(40.0, 10.0, -45.0)

    @Test fun cambiarConservaContraste() {
        val r = repaintLabs(RepaintKind.CAMBIAR, cur, group, blue, 1f)
        assertEquals(40.0, r[1]!!.l, 0.01)
        assertTrue(r[0]!!.l < r[1]!!.l && r[1]!!.l < r[2]!!.l)
        assertTrue(r.values.all { it.b < 0 }) // todo azul
    }

    @Test fun veladuraNoAclara() {
        val light = Lab(85.0, 0.0, 60.0)
        val r = repaintLabs(RepaintKind.VELADURA, cur, group, light, 1f)
        for (i in 0..2) assertTrue(r[i]!!.l <= cur[i]!!.l + 0.01)
        assertTrue(r[1]!!.b > cur[1]!!.b) // se tiñe hacia el amarillo
    }

    @Test fun sombraAfectaMasLoOscuro() {
        val r = repaintLabs(RepaintKind.SOMBRA, cur, group, Lab(15.0, 5.0, -20.0), 0.6f)
        val dDark = cur[0]!!.l - r[0]!!.l
        val dLight = cur[2]!!.l - r[2]!!.l
        assertTrue(dDark > 0 && dLight >= 0)
        assertTrue(dDark / cur[0]!!.l > dLight / cur[2]!!.l)
    }

    @Test fun lucesAclaranLoClaro() {
        val r = repaintLabs(RepaintKind.LUCES, cur, group, Lab(90.0, 0.0, 10.0), 0.6f)
        assertTrue(r[2]!!.l > cur[2]!!.l)
        assertTrue(r[2]!!.l - cur[2]!!.l > r[0]!!.l - cur[0]!!.l)
    }

    @Test fun fuerzaCeroNoCambia() {
        for (k in listOf(RepaintKind.VELADURA, RepaintKind.SOMBRA, RepaintKind.LUCES)) {
            val r = repaintLabs(k, cur, group, blue, 0f)
            for (i in 0..2) assertTrue(abs(r[i]!!.l - cur[i]!!.l) < 0.01 && abs(r[i]!!.a - cur[i]!!.a) < 0.01)
        }
    }

    @Test fun veladuraPrefiereTransparentes() {
        val opaque = Paint("o", "V", "Game Color", "Blue", "", "#3050A0", "Opaca")
        val ink = Paint("i", "V", "Game Ink", "Blue Ink", "", "#3050A8", "Tinta")
        val (p, owned) = paintForRepaint(RepaintKind.VELADURA, hexToLab("#3050A0"), listOf(opaque, ink), emptyList())!!
        assertEquals("i", p.id)
        assertTrue(owned)
    }
}
