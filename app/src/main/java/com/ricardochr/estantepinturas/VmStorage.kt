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

/** Tipos de lugar. Se guardan en español y se traducen al mostrarse. */
val SPOT_KINDS = listOf("Gaveta", "Pared", "Estante", "Caja", "Otro")

/** Dónde está una pintura: el lugar, la zona (si se dedujo por la marca) y si tiene un punto marcado. */
data class PlaceInfo(val spot: StorageSpot, val zone: SpotZone?, val hasPoint: Boolean) {
    val text: String get() = if (zone != null && zone.line.isNotBlank()) "${spot.name} · ${zone.line}" else spot.name
}

fun MainViewModel.spotFile(s: StorageSpot): File = File(File(getApplication<Application>().filesDir, "spots"), s.photo)

private fun MainViewModel.ocrFile(spotId: String): File = File(File(getApplication<Application>().filesDir, "spots"), "$spotId.ocr2.json")

fun MainViewModel.spotOf(paintId: String): StorageSpot? = placeOf(paintId)?.spot

/** Todas las ubicaciones guardadas de una pintura (la principal y las de otros botes). */
fun MainViewModel.allLocations(paintId: String): List<PaintLocation> =
    listOfNotNull(locations[paintId]) + moreLocations.filter { it.paintId == paintId }

/** Zonas donde, por su marca y línea, debería estar esta pintura. */
fun MainViewModel.zonesFor(p: Paint): List<SpotZone> {
    if (zones.isEmpty()) return emptyList()
    val bk = brandKey(p.brand)
    val line = norm(p.line)
    return zones.filter { z -> brandKey(z.brand) == bk && (z.line.isBlank() || norm(z.line) == line) && p.id !in z.exclude }
        // Una zona con línea es más específica que una de toda la marca
        .sortedByDescending { it.line.isNotBlank() }
}

/** Lugares de una pintura: los que guardaste; si no hay, los que se deducen de las zonas por marca. */
fun MainViewModel.placesOf(paintId: String): List<PlaceInfo> {
    val saved = allLocations(paintId).mapNotNull { l -> spots.firstOrNull { it.id == l.spotId }?.let { PlaceInfo(it, null, l.hasPoint) } }
    if (saved.isNotEmpty()) return saved
    val p = byId[paintId] ?: return emptyList()
    if ((owned[paintId]?.qty ?: 0) <= 0) return emptyList()
    return zonesFor(p).mapNotNull { z -> spots.firstOrNull { it.id == z.spotId }?.let { PlaceInfo(it, z, false) } }
}

fun MainViewModel.placeOf(paintId: String): PlaceInfo? = placesOf(paintId).firstOrNull()

/** Guarda una foto de una gaveta, pared o estante (reducida a 2400 px) como nuevo lugar. */
fun MainViewModel.addSpot(uri: Uri, name: String, onDone: (StorageSpot?) -> Unit) {
    viewModelScope.launch {
        val spot = withContext(Dispatchers.IO) {
            try {
                val bmp = loadBitmapForAnalysis(getApplication(), uri, 4096) ?: return@withContext null
                val id = UUID.randomUUID().toString()
                val dir = File(getApplication<Application>().filesDir, "spots").apply { mkdirs() }
                val file = "$id.jpg"
                File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                StorageSpot(id, name.ifBlank { tr("Lugar {}", spots.size + 1) }, file)
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

fun MainViewModel.renameSpot(id: String, name: String, kind: String? = null) {
    spots = spots.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, kind = kind ?: it.kind) else it }
    persist()
}

fun MainViewModel.deleteSpot(id: String) {
    ocrCache.remove(id)
    val s = spots.firstOrNull { it.id == id } ?: return
    spots = spots - s
    locations = locations.filterValues { it.spotId != id }
    moreLocations = moreLocations.filterNot { it.spotId == id }
    zones = zones.filterNot { it.spotId == id }
    if (stowSpotId == id) stowSpotId = null
    persist()
    viewModelScope.launch(Dispatchers.IO) { spotFile(s).delete(); ocrFile(id).delete() }
}

/**
 * Guarda dónde está una pintura. Con [also] se agrega como ubicación de otro bote en vez de moverla.
 * x, y negativos: solo se sabe el lugar, sin punto en la foto.
 */
fun MainViewModel.setLocation(paintId: String, spotId: String, x: Float, y: Float, also: Boolean = false) {
    val loc = if (x < 0f || y < 0f) PaintLocation(paintId, spotId, -1f, -1f) else PaintLocation(paintId, spotId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    val primary = locations[paintId]
    if (also && primary != null && primary.spotId != spotId) {
        moreLocations = moreLocations.filterNot { it.paintId == paintId && it.spotId == spotId } + loc
    } else {
        locations = locations + (paintId to loc)
        moreLocations = moreLocations.filterNot { it.paintId == paintId && it.spotId == spotId }
    }
    persist()
}

/** Quita la ubicación de una pintura (solo la de [spotId] si se indica; otra de sus ubicaciones pasa a ser la principal). */
fun MainViewModel.clearLocation(paintId: String, spotId: String? = null) {
    val primary = locations[paintId]
    if (spotId == null) {
        locations = locations - paintId
        moreLocations = moreLocations.filterNot { it.paintId == paintId }
    } else if (primary?.spotId == spotId) {
        val next = moreLocations.firstOrNull { it.paintId == paintId }
        locations = if (next != null) locations + (paintId to next) else locations - paintId
        if (next != null) moreLocations = moreLocations - next
    } else {
        moreLocations = moreLocations.filterNot { it.paintId == paintId && it.spotId == spotId }
    }
    persist()
}

/** Varias pinturas a un lugar de una vez (sin punto en la foto). Las que ya estaban ahí no cambian. */
fun MainViewModel.assignToSpot(paintIds: Collection<String>, spotId: String) {
    var loc = locations
    for (id in paintIds) {
        if (allLocations(id).any { it.spotId == spotId }) continue
        loc = loc + (id to PaintLocation(id, spotId, -1f, -1f))
    }
    locations = loc
    persist()
}

fun MainViewModel.addZone(spotId: String, x0: Float, y0: Float, x1: Float, y1: Float, brand: String, line: String) {
    val z = SpotZone(
        UUID.randomUUID().toString(), spotId,
        minOf(x0, x1).coerceIn(0f, 1f), minOf(y0, y1).coerceIn(0f, 1f), maxOf(x0, x1).coerceIn(0f, 1f), maxOf(y0, y1).coerceIn(0f, 1f),
        brand, line,
    )
    zones = zones + z
    persist()
}

fun MainViewModel.removeZone(id: String) {
    zones = zones.filterNot { it.id == id }
    persist()
}

/** "No está aquí": esta pintura no está en esa zona aunque sea de su marca. */
fun MainViewModel.excludeFromZone(zoneId: String, paintId: String) {
    zones = zones.map { if (it.id == zoneId) it.copy(exclude = it.exclude + paintId) else it }
    persist()
}

/** Cuántas pinturas tuyas están en un lugar: con punto, sin punto y por zona. */
fun MainViewModel.countsIn(spotId: String): Triple<Int, Int, Int> {
    val saved = (locations.values + moreLocations).filter { it.spotId == spotId }
    val savedIds = (locations.keys + moreLocations.map { it.paintId }).toSet()
    val zs = zones.filter { it.spotId == spotId }
    val byZone = if (zs.isEmpty()) 0 else owned.values.count { e ->
        e.qty > 0 && e.paintId !in savedIds && byId[e.paintId]?.let { p -> zonesFor(p).firstOrNull()?.spotId == spotId } == true
    }
    return Triple(saved.count { it.hasPoint }, saved.count { !it.hasPoint }, byZone)
}

// ---------------------------------------------------------------- lectura de etiquetas (se guarda)

/** Texto leído en la foto de un lugar: de la memoria, del archivo guardado o leyéndolo ahora (y guardándolo). */
internal fun MainViewModel.textBlocksOf(spot: StorageSpot, bmp: android.graphics.Bitmap? = null): List<TextBlockAt> {
    ocrCache[spot.id]?.let { return it }
    val f = ocrFile(spot.id)
    if (f.exists()) try {
        val arr = org.json.JSONArray(f.readText())
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ls = o.getJSONArray("l")
            TextBlockAt((0 until ls.length()).map { ls.getString(it) }, o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
        }
        ocrCache[spot.id] = list
        return list
    } catch (_: Exception) {
        // archivo dañado: se vuelve a leer la foto
    }
    // Dos pasadas: la foto entera (texto grande, en las cuatro orientaciones) y por partes ampliadas (letra pequeña)
    val image = bmp ?: loadSpotBitmap(spot) ?: return emptyList()
    val whole = readTextBlocks(image).map { TextBlockAt(it.lines, it.x / image.width, it.y / image.height) }
    if (bmp == null) image.recycle()
    val list = whole + readTextBlocksTiled(spotFile(spot).absolutePath) { readProgress = it }
    readProgress = 0f
    ocrCache[spot.id] = list
    try {
        val arr = org.json.JSONArray()
        list.forEach { b -> arr.put(org.json.JSONObject().put("l", org.json.JSONArray(b.lines)).put("x", b.x.toDouble()).put("y", b.y.toDouble())) }
        f.writeText(arr.toString())
    } catch (_: Exception) {
        // sin espacio: la lectura queda solo en memoria
    }
    return list
}

/**
 * Lee las etiquetas visibles en la foto (texto en cualquier orientación) y devuelve qué
 * pinturas reconoce y dónde. Las de tu inventario se aceptan por nombre o código; las que no
 * tienes, solo por código (para evitar falsos positivos con nombres comunes como "Black").
 */
suspend fun MainViewModel.detectInSpot(spot: StorageSpot, reread: Boolean = false): List<DetectedPaint> {
    val ownedIds = owned.filterValues { it.qty > 0 }.keys
    return withContext(Dispatchers.Default) {
        if (reread) { ocrCache.remove(spot.id); ocrFile(spot.id).delete() }
        val blocks = textBlocksOf(spot)
        val found = LinkedHashMap<String, DetectedPaint>()
        // Tus pinturas: coincidencia tolerante (nombre dentro de una línea, letras mal leídas, código confuso)
        val mine = ownedIds.mapNotNull { byId[it] }
        matchLabels(blocks, mine).values.sortedByDescending { it.score }.forEach { m ->
            found[m.paint.id] = DetectedPaint(m.paint, m.block.x, m.block.y, true)
        }
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
                if (!found.containsKey(h.paint.id)) found[h.paint.id] = DetectedPaint(h.paint, b.x, b.y, h.paint.id in ownedIds)
            }
        }
        found.values.toList()
    }
}

internal fun MainViewModel.loadSpotBitmap(spot: StorageSpot): android.graphics.Bitmap? =
    decodeForDisplay(spotFile(spot).absolutePath)

fun MainViewModel.isSpotRead(spot: StorageSpot) = ocrCache.containsKey(spot.id) || ocrFile(spot.id).exists()

/**
 * Cambia la foto de un lugar por una nueva (cuando moviste pinturas). Los puntos marcados en la foto
 * anterior ya no valen: esas pinturas siguen en el lugar pero sin punto, hasta que se vuelvan a leer
 * sus etiquetas. Devuelve cuántas quedaron sin punto, o null si no se pudo abrir la foto.
 */
fun MainViewModel.replaceSpotPhoto(spotId: String, uri: Uri, onDone: (Int?) -> Unit) {
    val spot = spots.firstOrNull { it.id == spotId } ?: return onDone(null)
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            try {
                val bmp = loadBitmapForAnalysis(getApplication(), uri, 4096) ?: return@withContext false
                spotFile(spot).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ocrFile(spotId).delete()
                true
            } catch (e: Exception) {
                false
            }
        }
        if (!ok) return@launch onDone(null)
        ocrCache.remove(spotId)
        var n = 0
        locations = locations.mapValues { (_, l) -> if (l.spotId == spotId && l.hasPoint) { n++; l.copy(x = -1f, y = -1f) } else l }
        moreLocations = moreLocations.map { l -> if (l.spotId == spotId && l.hasPoint) { n++; l.copy(x = -1f, y = -1f) } else l }
        spotsVersion++
        persist()
        onDone(n)
    }
}

/** Marcas más probables dentro de un rectángulo de la foto, por las pinturas ya ubicadas y el texto leído ahí. */
fun MainViewModel.suggestBrands(spot: StorageSpot, x0: Float, y0: Float, x1: Float, y1: Float): List<String> {
    val ownedPaints = owned.values.filter { it.qty > 0 }.mapNotNull { byId[it.paintId] }
    val score = HashMap<String, Int>()
    ownedPaints.forEach { score.putIfAbsent(it.brand, 0) }
    (locations.values + moreLocations).filter { it.spotId == spot.id && it.hasPoint && it.x in x0..x1 && it.y in y0..y1 }
        .forEach { l -> byId[l.paintId]?.let { score[it.brand] = (score[it.brand] ?: 0) + 3 } }
    val texts = (ocrCache[spot.id] ?: emptyList()).filter { it.x in x0..x1 && it.y in y0..y1 }.map { norm(it.lines.joinToString(" ")) }
    if (texts.isNotEmpty()) {
        // i18n:off — palabras de relleno de nombres de marcas
        val stop = setOf("the", "by", "of", "and", "color", "colour", "colors", "paint", "paints")
        // i18n:on
        val tokens = ownedPaints.groupBy { it.brand }.mapValues { (b, ps) ->
            (b.split(' ') + ps.map { it.line }.distinct().flatMap { it.split(' ') }).map { norm(it) }.filter { it.length >= 3 && it !in stop }.toSet()
        }
        for ((b, toks) in tokens) {
            val n = texts.count { t -> toks.any { t.contains(it) } }
            if (n > 0) score[b] = (score[b] ?: 0) + n
        }
    }
    return score.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
}

/**
 * Dónde podría estar [paint] en tus fotos, de más a menos probable:
 * 1. las ubicaciones que guardaste (con punto o solo el lugar);
 * 2. su etiqueta leída en la foto;
 * 3. la zona de su marca, y dentro de ella los botes del color de la pintura;
 * 4. botes de su color en el resto de las fotos.
 */
suspend fun MainViewModel.locatePaint(paint: Paint): List<LocateHit> {
    val saved = allLocations(paint.id)
    val allSpots = spots
    val myZones = zonesFor(paint)
    val ownedList = (owned.values.filter { it.qty > 0 }.mapNotNull { byId[it.paintId] } + paint).distinctBy { it.id }
    val sameBrand = (locations.values + moreLocations).filter { l -> l.hasPoint && l.paintId != paint.id && byId[l.paintId]?.brand == paint.brand }
    return withContext(Dispatchers.Default) {
        val hits = ArrayList<LocateHit>()
        for (spot in allSpots) {
            val here = saved.filter { it.spotId == spot.id }
            here.filter { it.hasPoint }.forEach { hits.add(LocateHit(spot, it.x, it.y, LocateKind.SAVED, 1f)) }
            val placeOnly = here.any { !it.hasPoint }
            if (placeOnly) hits.add(LocateHit(spot, -1f, -1f, LocateKind.PLACE, 0.93f))
            val bmp = loadSpotBitmap(spot) ?: continue
            val blocks = textBlocksOf(spot, bmp)
            val label = matchLabels(blocks, ownedList)[paint.id]
            if (label != null) hits.add(LocateHit(spot, label.block.x, label.block.y, LocateKind.LABEL, 0.95f))
            else for (b in blocks) {
                if (matchBlock(b.lines).any { it.paint.id == paint.id }) { hits.add(LocateHit(spot, b.x, b.y, LocateKind.LABEL, 0.95f)); break }
            }
            val zonesHere = myZones.filter { it.spotId == spot.id }
            // Si ya se sabe exactamente dónde está, la zona no agrega nada
            if (saved.none { it.hasPoint }) zonesHere.forEach { z -> hits.add(LocateHit(spot, (z.x0 + z.x1) / 2, (z.y0 + z.y1) / 2, LocateKind.ZONE, 0.9f, z)) }
            // Por color: primero dentro de la zona de su marca (recortada, así se ve con más detalle)
            for (z in zonesHere) {
                val cx = (z.x0 * bmp.width).toInt().coerceIn(0, bmp.width - 2)
                val cy = (z.y0 * bmp.height).toInt().coerceIn(0, bmp.height - 2)
                val cw = ((z.x1 - z.x0) * bmp.width).toInt().coerceIn(1, bmp.width - cx)
                val ch = ((z.y1 - z.y0) * bmp.height).toInt().coerceIn(1, bmp.height - cy)
                if (cw < 16 || ch < 16) continue
                val crop = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
                for (c in findColorCandidates(crop, paint.lab, limit = 4)) {
                    val conf = 0.5f + 0.38f * c.score * (1f - c.distance / 30f).coerceIn(0f, 1f)
                    hits.add(LocateHit(spot, z.x0 + c.x * (z.x1 - z.x0), z.y0 + c.y * (z.y1 - z.y0), LocateKind.COLOR, conf.coerceAtMost(0.89f), z))
                }
                crop.recycle()
            }
            val brandHere = sameBrand.filter { it.spotId == spot.id }
            for (c in findColorCandidates(bmp, paint.lab)) {
                var conf = 0.25f + 0.45f * c.score * (1f - c.distance / 30f).coerceIn(0f, 1f)
                if (brandHere.any { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) < 0.12f * 0.12f }) conf += 0.15f
                // Si su marca tiene zona, lo que está fuera de ella (o en otra foto) es poco probable
                if (myZones.isNotEmpty() && zonesHere.none { it.contains(c.x, c.y) }) conf *= 0.5f
                if (placeOnly) conf += 0.1f
                hits.add(LocateHit(spot, c.x, c.y, LocateKind.COLOR, conf.coerceAtMost(0.89f)))
            }
            bmp.recycle()
        }
        // Un solo marcador por bote: quitar los que caen sobre uno mejor (las zonas y lugares no son botes)
        val out = ArrayList<LocateHit>()
        for (h in hits.sortedByDescending { it.confidence }) {
            val area = h.kind == LocateKind.ZONE || h.kind == LocateKind.PLACE
            if (area || out.none { o ->
                    o.kind != LocateKind.ZONE && o.kind != LocateKind.PLACE && o.spot.id == h.spot.id &&
                        (o.x - h.x) * (o.x - h.x) + (o.y - h.y) * (o.y - h.y) < 0.03f * 0.03f
                }
            ) out.add(h)
        }
        out.take(10)
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
    moreLocations = moreLocations.filterNot { m -> m.spotId == spotId && list.any { it.paint.id == m.paintId } }
    persist()
}

/** Pinturas agrupadas por lugar para sacarlas en un solo recorrido; al final, las que no tienen lugar. */
fun MainViewModel.pickList(paintIds: List<String>): List<Pair<String, List<Paint>>> {
    val groups = LinkedHashMap<String, MutableList<Paint>>()
    val none = ArrayList<Paint>()
    val notOwned = ArrayList<Paint>()
    for (id in paintIds.distinct()) {
        val p = byId[id] ?: continue
        if ((owned[id]?.qty ?: 0) <= 0) { notOwned.add(p); continue }
        val place = placeOf(id)
        if (place == null) none.add(p) else groups.getOrPut(place.text) { ArrayList() }.add(p)
    }
    val order = spots.map { it.name }
    val out = groups.entries.sortedBy { e -> order.indexOfFirst { e.key.startsWith(it) }.let { if (it < 0) 999 else it } }.map { it.key to it.value.toList() }.toMutableList()
    if (none.isNotEmpty()) out.add(tr("Sin lugar guardado") to none)
    if (notOwned.isNotEmpty()) out.add(tr("No las tienes") to notOwned)
    return out
}
