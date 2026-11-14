package com.ricardochr.estantepinturas

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Abre las pantallas principales en un emulador, como lo haría una persona, y verifica que no se cierre la app. */
@RunWith(AndroidJUnit4::class)
class OpenScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val camera: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @Before fun spanish() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", "es").commit()
        // La ayuda de la primera vez se muestra; no debe estorbar la prueba
        ctx.getSharedPreferences("taller", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("ayuda", Context.MODE_PRIVATE).edit().putBoolean("luz", true).putBoolean("medir", true).putBoolean("superponer", true).commit()
    }

    /** Toca una herramienta del Taller, desplazando la lista si hace falta. */
    private fun tool(t: String) {
        rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[0].performScrollToNode(androidx.compose.ui.test.hasText(t))
        rule.waitForIdle()
        rule.onNodeWithText(t).performClick()
        rule.waitForIdle()
    }

    private fun launch() {
        ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(30_000) { rule.onAllNodesWithText("Taller").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    @Test fun abreConfiguracion() {
        launch()
        rule.onNodeWithContentDescription("Configuración").performClick()
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("IDIOMA").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun abreLuzYSombra() {
        launch()
        rule.onAllNodesWithText("Taller")[0].performClick()
        rule.waitForIdle()
        tool("Luz y sombra")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("LUZ Y SOMBRA").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Fotos").performClick()
        rule.waitForIdle()
    }

    @Test fun abreOtrasHerramientas() {
        launch()
        rule.onAllNodesWithText("Taller")[0].performClick()
        rule.waitForIdle()
        // Superponer referencia abre el selector de fotos del sistema, así que se prueba aparte
        for (t in listOf("Círculo cromático", "Esquemas de color", "Guía de técnicas", "Lista de compras", "Sets de pinturas", "Mi equipo")) {
            tool(t)
            Thread.sleep(600)
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Cerrar"))[0].performClick()
            rule.waitForIdle()
        }
    }

    @Test fun abreSuperponer() {
        launch()
        rule.onAllNodesWithText("Taller")[0].performClick()
        rule.waitForIdle()
        tool("Superponer referencia")
        Thread.sleep(1500)
    }
}
