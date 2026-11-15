package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lugares, zonas por marca y ubicaciones adicionales se guardan y se recuperan completos. */
class PlacesTest {
    @Test fun seGuardanYRecuperan() {
        val state = SavedState(
            owned = mapOf("a" to OwnedEntry("a", 2, "", 1L)),
            custom = emptyList(),
            barcodes = emptyMap(),
            spots = listOf(StorageSpot("s1", "Gaveta 1", "s1.jpg"), StorageSpot("s2", "Organizador", "s2.jpg", "Pared")),
            locations = mapOf("a" to PaintLocation("a", "s1", 0.25f, 0.5f), "b" to PaintLocation("b", "s2", -1f, -1f)),
            zones = listOf(SpotZone("z1", "s1", 0.1f, 0.2f, 0.6f, 0.7f, "Pro Acryl", "", setOf("x"))),
            moreLocations = listOf(PaintLocation("a", "s2", 0.3f, 0.3f)),
        )
        val back = Store.stateFromJson(Store.stateToJson(state))
        assertEquals("Pared", back.spots[1].kind)
        assertTrue(back.locations["a"]!!.hasPoint)
        assertFalse(back.locations["b"]!!.hasPoint)
        val z = back.zones.single()
        assertEquals("Pro Acryl", z.brand)
        assertEquals(setOf("x"), z.exclude)
        assertTrue(z.contains(0.3f, 0.4f))
        assertFalse(z.contains(0.9f, 0.4f))
        assertEquals("s2", back.moreLocations.single().spotId)
    }
}
