package com.ricardochr.estantepinturas

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Equipo y lista de compras (funciones de MainViewModel; el estado vive en MainViewModel)

fun MainViewModel.addGear(kind: String, name: String, nozzle: Double, tank: Boolean) {
    gear = gear + Gear(UUID.randomUUID().toString(), kind, name.trim(), nozzle, tank)
    persist()
    refreshPaletteMatches()
}

fun MainViewModel.removeGear(id: String) {
    gear = gear.filterNot { it.id == id }
    persist()
    refreshPaletteMatches()
}

fun MainViewModel.inShopping(paintId: String): Boolean = shopping.any { it.paintId == paintId }

fun MainViewModel.addToShopping(paintId: String, reason: String) {
    if (inShopping(paintId)) return
    shopping = shopping + ShopItem(paintId, reason, System.currentTimeMillis())
    persist()
}

fun MainViewModel.removeFromShopping(paintId: String) {
    shopping = shopping.filterNot { it.paintId == paintId }
    persist()
}

/** Marca una pintura que tienes como "se está acabando" (o lo quita). */
fun MainViewModel.setLow(paintId: String, low: Boolean) {
    val cur = owned[paintId] ?: return
    owned = owned + (paintId to cur.copy(low = low, updatedAt = System.currentTimeMillis()))
    persist()
}

/** La compraste: suma un bote al inventario y la quita de la lista. */
fun MainViewModel.bought(paintId: String) {
    val cur = owned[paintId]
    owned = owned + (paintId to OwnedEntry(paintId, (cur?.qty ?: 0) + 1, cur?.notes ?: "", System.currentTimeMillis(), low = false, thin = cur?.thin ?: ""))
    shopping = shopping.filterNot { it.paintId == paintId }
    persist()
}

/** La lista como texto para compartir (WhatsApp, notas, correo). */
fun MainViewModel.shoppingText(): String {
    val lines = ArrayList<String>()
    val items = shopping.mapNotNull { byId[it.paintId] } + lowPaints.filter { lp -> shopping.none { it.paintId == lp.id } }
    items.groupBy { it.brand }.toSortedMap().forEach { (brand, list) ->
        lines.add(brand.uppercase())
        list.sortedBy { it.name }.forEach { p ->
            lines.add("• " + listOf(p.code, p.name).filter { it.isNotBlank() }.joinToString(" ") + (if (p.line.isNotBlank()) " (${p.line})" else ""))
        }
        lines.add("")
    }
    return tr("Lista de compras · Gotario\n\n") + lines.joinToString("\n").trim()
}

fun MainViewModel.suggestForHue(ryb: Float, limit: Int = 3): List<Paint> {
    val myBrands = owned.values.filter { it.qty > 0 }.mapNotNull { byId[it.paintId]?.let { p -> brandKey(p.brand) } }
        .groupingBy { it }.eachCount()
    val base = catalog
    val cached = wheelCache
    val positions = if (cached != null && cached.first === base) cached.second else {
        val list = (extra + base).filter { it.hasColor && it.type == "Opaca" && it.quality != "n" }
            .map { it to wheelPosOf(it) }
            .filter { (_, w) -> !w.neutral && w.radius >= 0.55f }
        wheelCache = base to list
        list
    }
    return positions.asSequence()
        .filter { (p, w) -> (owned[p.id]?.qty ?: 0) == 0 && angleDiff(w.ryb, ryb) <= 10f }
        .sortedWith(compareBy({ -(myBrands[brandKey(it.first.brand)] ?: 0) }, { angleDiff(it.second.ryb, ryb) + (1f - it.second.radius) * 20f }))
        .map { it.first }
        .distinctBy { brandKey(it.brand) }
        .take(limit)
        .toList()
}

/** Pinturas que te recomiendo comprar para tener sombras, luces, lavados y básicos. */
suspend fun MainViewModel.recommendations(): List<Recommendation> {
    val inv = inventoryPaints()
    val cat = extra + catalog
    return withContext(Dispatchers.Default) { recommendPaints(inv, cat) }
}
