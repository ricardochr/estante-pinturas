package com.ricardochr.estantepinturas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ricardochr.estantepinturas.ui.PaintPickerDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El selector de pinturas se abre desde dentro de listas (Paleta, Repintar): no debe cerrar la app. */
@RunWith(AndroidJUnit4::class)
class PickerInListTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectorDentroDeUnaLista() {
        val vm = MainViewModel(rule.activity.application)
        rule.setContent {
            LazyColumn {
                item { PaintPickerDialog(vm, "Repintar con…", onDismiss = {}, fromCatalog = true) {} }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Repintar con…").assertExists()
    }
}
