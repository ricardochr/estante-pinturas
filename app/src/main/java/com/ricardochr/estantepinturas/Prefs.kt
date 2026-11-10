package com.ricardochr.estantepinturas

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Configuración de la app (se guarda en el teléfono). Cada valor es un estado de Compose:
 * al cambiarlo, la interfaz que lo lee se vuelve a dibujar sola.
 */
object Prefs {
    private var sp: SharedPreferences? = null

    /** Idioma de la interfaz: "es" o "en". Los nombres de las pinturas nunca se traducen. */
    var lang by mutableStateOf("es")
        private set

    /** Tema: "light" (por defecto), "dark" o "system". */
    var theme by mutableStateOf("light")
        private set

    /** Filas de pintura compactas (más pinturas por pantalla) o cómodas (más aire y dos líneas). */
    var compactRows by mutableStateOf(true)
        private set

    /** Orden con el que abre el inventario: Marca, Color, Nombre, Cantidad o Tipo. */
    var defaultSort by mutableStateOf("Marca")
        private set

    /** Unidad de presión para el aerógrafo: "psi" o "bar". */
    var pressureUnit by mutableStateOf("psi")
        private set

    /** Buscar novedades del catálogo sola, una vez por semana. */
    var autoUpdate by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp = p
        lang = p.getString("lang", null) ?: if (Locale.getDefault().language == "es") "es" else "en"
        theme = p.getString("theme", "light") ?: "light"
        compactRows = p.getBoolean("compactRows", true)
        defaultSort = p.getString("defaultSort", "Marca") ?: "Marca"
        pressureUnit = p.getString("pressureUnit", "psi") ?: "psi"
        autoUpdate = p.getBoolean("autoUpdate", false)
    }

    fun changeLang(v: String) { lang = v; sp?.edit()?.putString("lang", v)?.apply() }
    fun changeTheme(v: String) { theme = v; sp?.edit()?.putString("theme", v)?.apply() }
    fun changeCompactRows(v: Boolean) { compactRows = v; sp?.edit()?.putBoolean("compactRows", v)?.apply() }
    fun changeDefaultSort(v: String) { defaultSort = v; sp?.edit()?.putString("defaultSort", v)?.apply() }
    fun changePressureUnit(v: String) { pressureUnit = v; sp?.edit()?.putString("pressureUnit", v)?.apply() }
    fun changeAutoUpdate(v: Boolean) { autoUpdate = v; sp?.edit()?.putBoolean("autoUpdate", v)?.apply() }
}

/** Rango de presión en la unidad elegida: pressure(18, 25) = "18–25 psi" o "1.2–1.7 bar". */
fun pressure(fromPsi: Int, toPsi: Int): String =
    if (Prefs.pressureUnit == "bar") {
        String.format(Locale.US, "%.1f–%.1f bar", fromPsi * 0.0689476, toPsi * 0.0689476)
    } else {
        "$fromPsi–$toPsi psi"
    }
