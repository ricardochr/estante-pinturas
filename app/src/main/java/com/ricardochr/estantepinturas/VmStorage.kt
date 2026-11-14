package com.ricardochr.estantepinturas

import com.ricardochr.estantepinturas.MainViewModel.PaletteView
import com.ricardochr.estantepinturas.MainViewModel.PointPick
import com.ricardochr.estantepinturas.MainViewModel.Equivalent
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

// Dónde guardas tus pinturas y cómo encontrarlas (funciones de MainViewModel; el estado vive en MainViewModel)

fun MainViewModel.spotFile(s: StorageSpot): File = File(File(getApplication<Application>().filesDir, "spots"), s.photo)

fun MainViewModel.spotOf(paintId: String): StorageSpot? = locations[paintId]?.let { l -> spots.firstOrNull { it.id == l.spotId } }

/** Guarda una foto de una gaveta o estante (reducida a 2400 px) como nuevo lugar. */
fun MainViewModel.addSpot(uri: Uri, name: String, onDone: (StorageSpot?) -> Unit) {
    viewModelScope.launch {
        val spot = withContext(Dispatchers.IO) {
            try {
                val bmp = loadBitmapForAnalysis(getApplication(), uri, 2400) ?: return@withContext null
                val id = UUID.randomUUID().toString()
                val dir = File(getApplication<Application>().filesDir, "spots").apply { mkdirs() }
                val file = "$id.jpg"
                File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                StorageSpot(id, name.ifBlank { tr("Gaveta {}", spots.size + 1) }, file)
            } catch (e: Exception) {
                null
            }
        }
        if (spot != null) {
            spots = spots + spot
            persist()
        }
        onDone(spot)
    }
}

fun MainViewModel.renameSpot(id: String, name: String) {
    spots = spots.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
    persist()
}

fun MainViewModel.deleteSpot(id: String) {
    ocrCache.remove(id)
    val s = spots.firstOrNull { it.id == id } ?: return
    spots = spots - s
    locations = locations.filterValues { it.spotId != id }
    persist()
    viewModelScope.launch(Dispatchers.IO) { spotFile(s).delete() }
}

fun MainViewModel.setLocation(paintId: String, spotId: String, x: Float, y: Float) {
    locations = locations + (paintId to PaintLocation(paintId, spotId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
    persist()
}

fun MainViewModel.clearLocation(paintId: String) {
    locations = locations - paintId
    persist()
}

/**
 * Lee las etiquetas visibles en la foto (texto en cualquier orientación) y devuelve qué
 * pinturas reconoce y dónde. Las de tu inventario se aceptan por nombre o código; las que no
 * tienes, solo por código (para evitar falsos positivos con nombres comunes como "Black").
 */
suspend fun MainViewModel.detectInSpot(spot: StorageSpot): List<DetectedPaint> {
    val file = spotFile(spot)
    val ownedIds = owned.filterValues { it.qty > 0 }.keys
    return withContext(Dispatchers.Default) {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext emptyList()
        val blocks = readTextBlocks(bmp)
        ocrCache[spot.id] = blocks.map { TextBlockAt(it.lines, it.x / bmp.width, it.y / bmp.height) }
        val found = LinkedHashMap<String, DetectedPaint>()
        for (b in blocks) {
            val hits = matchBlock(b.lines)
            if (hits.isEmpty()) continue
            val ownedHits = hits.filter { it.paint.id in ownedIds }
            val chosen = when {
                ownedHits.size == 1 -> ownedHits
                ownedHits.size > 1 -> ownedHits.filter { it.via.startsWith("código") }.take(1)
                else -> hits.filter { it.via.startsWith("código") }.take(1)
            }
            for (h in chosen) {
                if (!found.containsKey(h.paint.id)) {
                    found[h.paint.id] = DetectedPaint(h.paint, b.x / bmp.width, b.y / bmp.height, h.paint.id in ownedIds)
                }
            }
        }
        found.values.toList()
    }
}

internal fun MainViewModel.loadSpotBitmap(spot: StorageSpot): android.graphics.Bitmap? =
    android.graphics.BitmapFactory.decodeFile(spotFile(spot).absolutePath)

fun MainViewModel.isSpotRead(spot: StorageSpot) = ocrCache.containsKey(spot.id)

/**
 * Dónde podría estar [paint] en tus fotos, de más a menos probable:
 * 1. la ubicación que confirmaste antes;
 * 2. su etiqueta leída en la foto;
 * 3. zonas del color de la pintura, con más peso si están cerca de otras pinturas de la misma marca.
 */
suspend fun MainViewModel.locatePaint(paint: Paint): List<LocateHit> {
    val saved = locations[paint.id]
    val allSpots = spots
    val sameBrand = locations.values.filter { l -> l.paintId != paint.id && byId[l.paintId]?.brand == paint.brand }
    return withContext(Dispatchers.Default) {
        val hits = ArrayList<LocateHit>()
        for (spot in allSpots) {
            if (saved != null && saved.spotId == spot.id) {
                hits.add(LocateHit(spot, saved.x, saved.y, LocateKind.SAVED, 1f))
            }
            val bmp = loadSpotBitmap(spot) ?: continue
            val blocks = ocrCache.getOrPut(spot.id) {
                readTextBlocks(bmp).map { TextBlockAt(it.lines, it.x / bmp.width, it.y / bmp.height) }
            }
            for (b in blocks) {
                if (matchBlock(b.lines).any { it.paint.id == paint.id }) {
                    hits.add(LocateHit(spot, b.x, b.y, LocateKind.LABEL, 0.95f))
                }
            }
            val brandHere = sameBrand.filter { it.spotId == spot.id }
            for (c in findColorCandidates(bmp, paint.lab)) {
                var conf = 0.25f + 0.45f * c.score * (1f - c.distance / 30f).coerceIn(0f, 1f)
                if (brandHere.any { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) < 0.12f * 0.12f }) conf += 0.15f
                hits.add(LocateHit(spot, c.x, c.y, LocateKind.COLOR, conf.coerceAtMost(0.89f)))
            }
            bmp.recycle()
        }
        // Un solo marcador por bote: quitar los que caen sobre uno mejor
        val out = ArrayList<LocateHit>()
        for (h in hits.sortedByDescending { it.confidence }) {
            if (out.none { o -> o.spot.id == h.spot.id && (o.x - h.x) * (o.x - h.x) + (o.y - h.y) * (o.y - h.y) < 0.03f * 0.03f }) out.add(h)
        }
        out.take(8)
    }
}

/** Guarda las ubicaciones detectadas; las pinturas que no tenías se agregan con 1 bote. */
fun MainViewModel.applyDetections(spotId: String, list: List<DetectedPaint>) {
    var newOwned = owned
    var newLoc = locations
    val now = System.currentTimeMillis()
    for (d in list) {
        if ((newOwned[d.paint.id]?.qty ?: 0) == 0) newOwned = newOwned + (d.paint.id to OwnedEntry(d.paint.id, 1, "", now))
        newLoc = newLoc + (d.paint.id to PaintLocation(d.paint.id, spotId, d.x, d.y))
    }
    owned = newOwned
    locations = newLoc
    persist()
}
