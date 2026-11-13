package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Test

class FinishTest {
    private fun p(brand: String, line: String, name: String, code: String) = Paint("$brand-$code-$name", brand, line, name, code, "#808080", "Opaca")

    @Test fun tamiyaPorCodigo() {
        assertEquals(Finish.BRILLANTE, finishOf(p("Tamiya", "Tamiya Spray", "Italian Red", "TS-8")).finish)
        assertEquals(Finish.MATE, finishOf(p("Tamiya", "Tamiya Spray", "Gunship Grey", "TS-48")).finish)
        assertEquals(Finish.SATINADO, finishOf(p("Tamiya", "Tamiya Spray", "Gun Metal", "TS-38")).finish)
        assertEquals(Finish.DESCONOCIDO, finishOf(p("Tamiya", "Tamiya Spray", "Metallic Black", "TS-40")).finish)
        assertEquals(Finish.MATE, finishOf(p("Tamiya", "Tamiya Color", "Flat Black", "XF-1")).finish)
        assertEquals(Finish.BRILLANTE, finishOf(p("Tamiya", "Tamiya Color", "Red", "X-7")).finish)
    }

    @Test fun nombreDePintorNoEsAcabado() {
        assertEquals(Finish.MATE, finishOf(p("Pro Acryl", "Signature", "Matt Cexwish Red", "S01")).finish)
    }
}
