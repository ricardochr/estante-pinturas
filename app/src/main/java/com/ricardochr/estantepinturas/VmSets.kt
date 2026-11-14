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

// Sets de pinturas y actualización del catálogo (funciones de MainViewModel; el estado vive en MainViewModel)

/**
 * Descarga el catálogo y los sets más recientes (los que el proceso automático publica cada
 * semana a partir de las tiendas) y los aplica sin reinstalar la app. Mantiene tu inventario,
 * colores corregidos y pinturas propias; las que llegaron nuevas al catálogo se unen solas.
 */
fun MainViewModel.refreshCatalog(quiet: Boolean = false, onDone: (String) -> Unit) {
    if (catalogUpdating) return
    catalogUpdating = true
    viewModelScope.launch {
        val oldIds = (extra + catalog).map { it.id }.toHashSet()
        val oldSets = sets.map { it.key }.toHashSet()
        val err = withContext(Dispatchers.IO) { store.downloadCatalog() }
        if (err != null) {
            catalogUpdating = false
            if (!quiet) onDone(tr("No se pudo actualizar: {}", err))
            return@launch
        }
        val (cat, newSets) = withContext(Dispatchers.IO) { store.loadCatalog() to store.loadSets() }
        val fixed = if (colorFixes.isEmpty()) cat else cat.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
        withContext(Dispatchers.Default) { fixed.forEach { it.searchText; it.lab } }
        catalog = fixed
        sets = newSets
        wheelCache = null
        rebuildIndexes()
        migrateOrphans()
        adoptCatalogMatches()
        catalogDownloadedAt = store.catalogDownloadedAt()
        catalogUpdating = false
        val newPaints = fixed.count { it.id !in oldIds }
        val addedSets = newSets.count { it.key !in oldSets }
        if (quiet && newPaints == 0 && addedSets == 0) return@launch
        onDone(
            if (newPaints == 0 && addedSets == 0) tr("Ya tenías el catálogo más reciente ({} pinturas, {} sets)", fixed.size, newSets.size)
            else tr("Catálogo actualizado: {} pinturas nuevas y {} sets nuevos", newPaints, addedSets)
        )
    }
}

fun MainViewModel.createSet(name: String, brand: String): PaintSet {
    val s = PaintSet(brand.trim(), name.trim().ifBlank { tr("Mi set") }, "", emptyList(), custom = true, id = UUID.randomUUID().toString())
    customSets = listOf(s) + customSets
    persist()
    return s
}

/** Un set editable: si es de la tienda, crea tu copia (que lo reemplaza en la lista). */
internal fun MainViewModel.editable(set: PaintSet): PaintSet {
    if (set.custom) return customSets.firstOrNull { it.id == set.id } ?: set
    val copy = set.copy(custom = true, id = UUID.randomUUID().toString(), replaces = set.key)
    customSets = listOf(copy) + customSets
    return copy
}

internal fun MainViewModel.saveSet(updated: PaintSet): PaintSet {
    customSets = customSets.map { if (it.id == updated.id) updated else it }
    persist()
    return updated
}

fun MainViewModel.addPaintToSet(set: PaintSet, paintId: String): PaintSet {
    val s = editable(set)
    return if (paintId in s.paintIds) s else saveSet(s.copy(paintIds = s.paintIds + paintId))
}

fun MainViewModel.removePaintFromSet(set: PaintSet, paintId: String): PaintSet {
    val s = editable(set)
    return saveSet(s.copy(paintIds = s.paintIds - paintId))
}

fun MainViewModel.renameSet(set: PaintSet, name: String, brand: String): PaintSet {
    val s = editable(set)
    return saveSet(s.copy(name = name.trim().ifBlank { s.name }, brand = brand.trim()))
}

/** Borra un set tuyo; si era tu versión de un set de la tienda, vuelve el original. */
fun MainViewModel.deleteSet(set: PaintSet) {
    if (!set.custom) return
    customSets = customSets.filterNot { it.id == set.id }
    persist()
}

/** Línea principal del set (la de la mayoría de sus pinturas), p. ej. "Squidmar Color" o "Game Color". */
fun MainViewModel.lineOf(set: PaintSet): String =
    paintsOf(set).groupingBy { it.line }.eachCount().maxByOrNull { it.value }?.key ?: ""

/** Pinturas del set que están en el catálogo (con su color), en el orden del set. */
fun MainViewModel.paintsOf(set: PaintSet): List<Paint> = set.paintIds.mapNotNull { byId[it] }

/** Agrega a la lista de compras las pinturas del set que no tienes. Devuelve cuántas agregó. */
fun MainViewModel.addMissingToShopping(set: PaintSet): Int {
    val missing = paintsOf(set).filter { qtyOf(it.id) == 0 && !inShopping(it.id) }
    if (missing.isEmpty()) return 0
    val now = System.currentTimeMillis()
    shopping = shopping + missing.map { ShopItem(it.id, tr("Del set {}", set.name), now) }
    persist()
    return missing.size
}

/** Compraste el set: suma un bote de cada pintura al inventario y las quita de la lista de compras. */
fun MainViewModel.boughtSet(set: PaintSet): Int {
    val ps = paintsOf(set)
    val now = System.currentTimeMillis()
    var o = owned
    for (p in ps) {
        val cur = o[p.id]
        o = o + (p.id to OwnedEntry(p.id, (cur?.qty ?: 0) + 1, cur?.notes ?: "", now, low = false, thin = cur?.thin ?: ""))
    }
    owned = o
    val ids = ps.map { it.id }.toHashSet()
    shopping = shopping.filterNot { it.paintId in ids }
    persist()
    return ps.size
}
