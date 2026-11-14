package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Ciclo completo: exportar el respaldo, "borrar" (carpetas nuevas vacías) e importar. */
class BackupTest {
    private fun tmp(): File = Files.createTempDirectory("gotario").toFile()

    @Test fun exportarBorrarImportar() {
        val projectsRoot = tmp()
        val filesRoot = tmp()
        // Datos de ejemplo: inventario con nota de dilución, color corregido, proyecto con plan, receta y foto
        val state = SavedState(
            owned = mapOf("a" to OwnedEntry("a", 2, "mi nota", 10L, low = true, thin = "1:2 con flow")),
            custom = listOf(Paint("custom:1", "Mía", "Línea", "Verde raro", "", "#2A8040", "Opaca", custom = true)),
            barcodes = mapOf("123" to "a"),
            colorFixes = mapOf("a" to "#112233"),
            shopping = listOf(ShopItem("b", "falta", 5L)),
        )
        File(projectsRoot, "p1").mkdirs()
        File(File(projectsRoot, "p1"), "foto1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val project = Project(
            "p1", "Paladín", 1L, "En progreso", "notas", null,
            listOf(ProjectPaint("a", "Piel · base")),
            listOf(ProgressPhoto("foto1.jpg", 2L, "avance")),
            plan = listOf(PlanStep("s1", "Piel", "Base", done = true)),
            recipes = listOf(SavedMix("r1", "Piel clara", listOf(MixIngredient("a", 2), MixIngredient("b", 1)), "#D0A080", "luces")),
        )
        File(filesRoot, "modelos").mkdirs()
        File(File(filesRoot, "modelos"), "paladin.gmesh").writeBytes(ByteArray(64) { it.toByte() })
        File(filesRoot, "luz/general").mkdirs()
        File(filesRoot, "luz/general/FRENTE.jpg").writeBytes(byteArrayOf(9, 9))
        val prefs = Backup.prefsToJson(
            mapOf(
                "settings" to mapOf("lang" to "en", "compactRows" to false),
                "mediciones" to mapOf("a" to """{"key":"Marca|A","hex":"#112233","at":5}"""),
            ),
        )
        val out = ByteArrayOutputStream()
        Backup.write(out, state, listOf(project), projectsRoot, filesRoot, prefs)

        // "Borrar": restaurar en carpetas nuevas vacías
        val pr2 = tmp()
        val fr2 = tmp()
        val c = Backup.read(ByteArrayInputStream(out.toByteArray()), pr2, fr2)
        assertNotNull(c)
        c!!
        val e = c.state.owned["a"]!!
        assertEquals(2, e.qty)
        assertEquals("1:2 con flow", e.thin)
        assertTrue(e.low)
        assertEquals("#112233", c.state.colorFixes["a"])
        assertEquals("a", c.state.barcodes["123"])
        assertEquals(1, c.state.custom.size)
        assertEquals(1, c.state.shopping.size)
        val p = c.projects.single()
        assertEquals(1, p.plan.size)
        assertTrue(p.plan[0].done)
        assertEquals(2, p.recipes.single().parts.size)
        assertTrue(File(File(pr2, "p1"), "foto1.jpg").exists())
        assertEquals(64L, File(fr2, "modelos/paladin.gmesh").length())
        assertTrue(File(fr2, "luz/general/FRENTE.jpg").exists())
        assertEquals(2, c.files)
        assertEquals("en", c.prefs!!.getJSONObject("settings").getJSONObject("lang").getString("v"))
        assertEquals("b", c.prefs!!.getJSONObject("settings").getJSONObject("compactRows").getString("t"))
    }

    @Test fun rutasPeligrosasSeIgnoran() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("inventario.json"))
            z.write(Store.stateToJson(SavedState(emptyMap(), emptyList(), emptyMap())).toString().toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("archivos/../../evil.gmesh"))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        val fr = tmp()
        val c = Backup.read(ByteArrayInputStream(out.toByteArray()), tmp(), fr)
        assertNotNull(c)
        assertEquals(0, c!!.files)
        assertTrue(!File(fr.parentFile.parentFile, "evil.gmesh").exists())
    }
}
