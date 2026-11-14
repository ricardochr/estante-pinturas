package com.ricardochr.estantepinturas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Pantallas que se abren encima de las pestañas. Se guardan en una pila: la última es la que se ve
 * y Atrás la cierra. Cada una se escribe como texto para sobrevivir a que el sistema recree la app.
 */
private fun enc(s: String?): String = if (s == null) "" else java.net.URLEncoder.encode(s, "UTF-8")

sealed class Screen(val key: String) {
    data object Shopping : Screen("shopping")
    data object Backup : Screen("backup")
    data object Gear : Screen("gear")
    data object Compare : Screen("compare")
    data object Light : Screen("light")
    data object Sets : Screen("sets")
    data object Guide : Screen("guide")
    data object Settings : Screen("settings")
    data object Schemes : Screen("schemes")
    data object Gaps : Screen("gaps")
    // Los ids de pintura llevan "|", así que cada parte se codifica
    data class Wheel(val paintId: String?) : Screen("wheel|" + enc(paintId))
    data class Project(val id: String) : Screen("project|" + enc(id))
    data class Storage(val spotId: String?, val findPaintId: String?, val placingPaintId: String?) :
        Screen("storage|" + enc(spotId) + "|" + enc(findPaintId) + "|" + enc(placingPaintId))
    data class LiveFind(val paintId: String) : Screen("livefind|" + enc(paintId))

    companion object {
        // Perezoso: si se armara al cargar la clase, las pantallas todavía no existirían (quedarían en null)
        private val simple by lazy { listOf(Shopping, Backup, Gear, Compare, Light, Sets, Guide, Settings, Schemes, Gaps).associateBy { it.key } }

        fun parse(k: String): Screen? {
            simple[k]?.let { return it }
            val p = k.split("|")
            fun at(i: Int) = p.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            return when (p[0]) {
                "wheel" -> Wheel(at(1))
                "project" -> at(1)?.let { Project(it) }
                "storage" -> Storage(at(1), at(2), at(3))
                "livefind" -> at(1)?.let { LiveFind(it) }
                else -> null
            }
        }
    }
}

/** Pila de pantallas abiertas. Abrir una que ya está abierta la trae al frente en vez de duplicarla. */
class NavStack(val items: SnapshotStateList<Screen>) {
    fun open(s: Screen) {
        items.remove(s)
        items.add(s)
    }

    fun close(s: Screen) {
        items.remove(s)
    }

    /** Cierra la de arriba. Devuelve false si no había ninguna. */
    fun back(): Boolean = items.removeLastOrNull() != null

    fun isOpen(s: Screen) = s in items
    val isEmpty: Boolean get() = items.isEmpty()
}

@Composable
fun rememberNavStack(): NavStack {
    val list = rememberSaveable(
        saver = listSaver<SnapshotStateList<Screen>, String>(
            save = { l -> l.map { it.key } },
            restore = { keys -> mutableStateListOf<Screen>().apply { keys.mapNotNull { Screen.parse(it) }.forEach { add(it) } } },
        ),
    ) { mutableStateListOf() }
    return NavStack(list)
}
