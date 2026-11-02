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

/** Una pintura reconocida por la cámara. */
data class ScanHit(val paint: Paint, val via: String)

/** Resultado de analizar un cuadro de la cámara. */
data class ScanResult(val hits: List<ScanHit>, val unknownBarcodes: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val projectStore = ProjectStore(app)
    private val prefs = app.getSharedPreferences("gotario", Context.MODE_PRIVATE)

    var loading by mutableStateOf(true)
        private set
    var catalog by mutableStateOf<List<Paint>>(emptyList())
        private set
    var custom by mutableStateOf<List<Paint>>(emptyList())
        private set
    /** Pinturas importadas al catálogo desde un CSV. */
    var extra by mutableStateOf<List<Paint>>(emptyList())
        private set
    var owned by mutableStateOf<Map<String, OwnedEntry>>(emptyMap())
        private set
    var barcodes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Catálogo + pinturas propias, indexado por id. */
    var byId by mutableStateOf<Map<String, Paint>>(emptyMap())
        private set

    private var codeIndex: Map<String, List<Paint>> = emptyMap()
    private var nameIndex: Map<String, List<Paint>> = emptyMap()

    val allPaints: List<Paint> get() = custom + extra + catalog

    /** Cuántas pinturas hay por marca en el catálogo (incluido + importado). */
    val brandCounts: List<Pair<String, Int>>
        get() = (extra + catalog).groupingBy { it.brand }.eachCount().toList().sortedByDescending { it.second }

    init {
        viewModelScope.launch {
            val (cat, saved) = withContext(Dispatchers.IO) { store.loadCatalog() to store.loadState() }
            val ext = withContext(Dispatchers.IO) { store.loadExtraCatalog() }
            catalog = cat
            extra = ext
            custom = saved.custom
            owned = saved.owned
            barcodes = saved.barcodes
            spots = saved.spots
            locations = saved.locations
            colorFixes = saved.colorFixes
            shopping = saved.shopping
            projects = withContext(Dispatchers.IO) { projectStore.load() }
            if (colorFixes.isNotEmpty()) {
                catalog = catalog.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
                extra = extra.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
                custom = custom.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
            }
            // Prepara texto de búsqueda y color Lab de todas las pinturas antes de usarlas
            withContext(Dispatchers.Default) { (ext + cat).forEach { it.searchText; it.lab } }
            rebuildIndexes()
            migrateOrphans()
            adoptCatalogMatches()
            loading = false
        }
    }

    /**
     * Cuando el catálogo se actualiza, algunas pinturas cambian de línea o de nombre y su id
     * cambia. Aquí se reasigna el inventario (y los códigos de barras) a la pintura equivalente
     * del catálogo nuevo; si no existe, se conserva como pintura propia para no perder nada.
     */
    private fun migrateOrphans() {
        val orphanIds = (owned.keys + barcodes.values).filter { !byId.containsKey(it) }.toSet()
        if (orphanIds.isEmpty()) return
        val remap = HashMap<String, String>()
        val newCustom = ArrayList<Paint>()
        for (oldId in orphanIds) {
            val parts = oldId.removePrefix("x|").split("|")
            if (parts.size < 4) continue
            val (brand, line, name, code) = parts
            val found = findPaint(brand, line, name, code)
            if (found != null) {
                remap[oldId] = found.id
            } else {
                val p = Paint(
                    id = "custom:" + UUID.randomUUID().toString(),
                    brand = brand, line = line, name = name, code = code,
                    hex = "#888888", type = guessType(name, line), custom = true,
                )
                newCustom.add(p)
                remap[oldId] = p.id
            }
        }
        if (remap.isEmpty()) return
        val newOwned = LinkedHashMap<String, OwnedEntry>()
        owned.values.forEach { e ->
            val id = remap[e.paintId] ?: e.paintId
            val prev = newOwned[id]
            newOwned[id] = if (prev == null) e.copy(paintId = id)
            else prev.copy(qty = prev.qty + e.qty, notes = listOf(prev.notes, e.notes).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        }
        owned = newOwned
        barcodes = barcodes.mapValues { (_, v) -> remap[v] ?: v }
        if (newCustom.isNotEmpty()) {
            custom = custom + newCustom
            rebuildIndexes()
        }
        persist()
    }

    /**
     * Si registraste a mano una pintura que después llegó al catálogo (por ejemplo los lavados de AK
     * o los metálicos de Kaleido), se pasa a la del catálogo: botes, notas, códigos de barras,
     * lista de compras, ubicación y proyectos. Si le habías puesto color, se conserva como corrección.
     */
    private fun adoptCatalogMatches() {
        if (custom.isEmpty()) return
        val pool = extra + catalog
        val byCode = pool.filter { codeKey(it.code).length >= 3 }.groupBy { brandKey(it.brand) + "|" + codeKey(it.code) }
        fun loose(n: String) = nameKey(n).removeSuffix("wash").removeSuffix("lavado").removeSuffix("ink").removeSuffix("tinta")
        val byName = pool.groupBy { brandKey(it.brand) + "|" + loose(it.name) }
        val remap = HashMap<String, Paint>()
        for (c in custom) {
            val bk = brandKey(c.brand)
            val ck = codeKey(c.code)
            val hit = (if (ck.length >= 3) byCode["$bk|$ck"]?.firstOrNull() else null)
                ?: byName["$bk|${loose(c.name)}"]?.takeIf { it.size == 1 }?.first()
            if (hit != null) remap[c.id] = hit
        }
        if (remap.isEmpty()) return
        val newOwned = LinkedHashMap<String, OwnedEntry>()
        owned.values.forEach { e ->
            val id = remap[e.paintId]?.id ?: e.paintId
            val prev = newOwned[id]
            newOwned[id] = if (prev == null) e.copy(paintId = id)
            else prev.copy(qty = prev.qty + e.qty, low = prev.low || e.low,
                notes = listOf(prev.notes, e.notes).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        }
        owned = newOwned
        barcodes = barcodes.mapValues { (_, v) -> remap[v]?.id ?: v }
        shopping = shopping.map { s -> remap[s.paintId]?.let { s.copy(paintId = it.id) } ?: s }.distinctBy { it.paintId }
        locations = locations.entries.associate { (k, v) ->
            val id = remap[k]?.id ?: k
            id to v.copy(paintId = id)
        }
        // Color elegido a mano: se respeta si el del catálogo es solo aproximado
        val fixes = HashMap(colorFixes)
        for ((cid, target) in remap) {
            val c = custom.firstOrNull { it.id == cid } ?: continue
            if (c.hasColor && !c.hex.equals("#888888", true) && target.quality != "" && !fixes.containsKey(target.id)) fixes[target.id] = c.hex
        }
        if (fixes != colorFixes) {
            colorFixes = fixes
            catalog = catalog.map { p -> fixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
            extra = extra.map { p -> fixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
        }
        custom = custom.filterNot { remap.containsKey(it.id) }
        if (projects.isNotEmpty()) {
            projects = projects.map { pr ->
                pr.copy(paints = pr.paints.map { pp -> remap[pp.paintId]?.let { pp.copy(paintId = it.id) } ?: pp }.distinctBy { it.paintId })
            }
            saveProjects()
        }
        rebuildIndexes()
        persist()
        adoptedCount = remap.size
    }

    /** Cuántas pinturas tuyas se pasaron al catálogo en este arranque (para avisarte). */
    var adoptedCount by mutableStateOf(0)

    private fun rebuildIndexes() {
        val all = custom + extra + catalog
        byId = all.associateBy { it.id }
        val codes = HashMap<String, MutableList<Paint>>()
        val names = HashMap<String, MutableList<Paint>>()
        all.forEach { p ->
            val ck = codeKey(p.code)
            if (ck.length >= 4) codes.getOrPut(ck) { ArrayList() }.add(p)
            val nk = nameKey(p.name)
            if (nk.length >= 5) names.getOrPut(nk) { ArrayList() }.add(p)
        }
        codeIndex = codes
        nameIndex = names
    }

    private fun persist() {
        val snapshot = SavedState(owned, custom, barcodes, spots, locations, colorFixes, shopping)
        viewModelScope.launch(Dispatchers.IO) { store.saveState(snapshot) }
        scheduleAutoBackup()
    }

    // ---------- inventario ----------

    fun qtyOf(paintId: String): Int = owned[paintId]?.qty ?: 0

    fun add(paintId: String, delta: Int = 1) {
        val cur = owned[paintId]
        val qty = ((cur?.qty ?: 0) + delta).coerceAtLeast(0)
        owned = owned + (paintId to OwnedEntry(paintId, qty, cur?.notes ?: "", System.currentTimeMillis(), low = (cur?.low ?: false) && qty > 0))
        persist()
    }

    fun update(paintId: String, qty: Int, notes: String) {
        val low = (owned[paintId]?.low ?: false) && qty > 0
        owned = owned + (paintId to OwnedEntry(paintId, qty.coerceAtLeast(0), notes.trim(), System.currentTimeMillis(), low))
        persist()
    }

    fun remove(paintId: String) {
        owned = owned - paintId
        locations = locations - paintId
        val p = byId[paintId]
        if (p != null && p.custom) {
            custom = custom.filterNot { it.id == paintId }
            barcodes = barcodes.filterValues { it != paintId }
            rebuildIndexes()
        }
        persist()
    }

    /** Crea una pintura que no está en el catálogo y la agrega al inventario. Devuelve su id. */
    fun addCustom(brand: String, line: String, name: String, code: String, hex: String, type: String, qty: Int): String {
        val existing = findPaint(brand, line, name, code)
        if (existing != null) {
            add(existing.id, qty.coerceAtLeast(1))
            return existing.id
        }
        val p = Paint(
            id = "custom:" + UUID.randomUUID().toString(),
            brand = brand.trim(), line = line.trim(), name = name.trim(), code = code.trim(),
            hex = validHex(hex), type = type, custom = true,
        )
        custom = custom + p
        rebuildIndexes()
        add(p.id, qty.coerceAtLeast(1))
        return p.id
    }

    fun linkBarcode(barcode: String, paintId: String) {
        barcodes = barcodes + (barcode to paintId)
        persist()
    }

    /** Busca una pintura del catálogo por marca + código, o marca + nombre. */
    fun findPaint(brand: String, line: String, name: String, code: String): Paint? {
        val bk = brandKey(brand)
        val ck = codeKey(code)
        val all = custom + extra + catalog
        if (ck.isNotEmpty()) {
            val byCode = all.filter { codeKey(it.code) == ck }
            byCode.firstOrNull { brandKey(it.brand) == bk }?.let { return it }
            if (bk.isBlank() && byCode.size == 1) return byCode[0]
        }
        val nk = nameKey(name)
        if (nk.isEmpty()) return null
        val byName = all.filter { nameKey(it.name) == nk && (bk.isBlank() || brandKey(it.brand) == bk) }
        if (byName.isEmpty()) return null
        val lk = nameKey(line)
        return byName.firstOrNull { lk.isNotEmpty() && nameKey(it.line) == lk } ?: byName.first()
    }

    // ---------- colores parecidos ----------

    /**
     * Pinturas de tu inventario con el color más cercano a [target] (CIEDE2000).
     * Primero las de la misma familia (opaca, metálica, transparente); si no hay suficientes,
     * completa con otras familias. Barnices y médiums no cuentan.
     */
    fun similarOwned(target: Paint, limit: Int = 5, maxDistance: Double = 25.0): List<SimilarPaint> {
        if (target.hex.equals(UNKNOWN_HEX, true)) return emptyList()
        val lab = target.lab
        val group = typeGroup(target.type)
        val candidates = owned.values
            .filter { it.qty > 0 && it.paintId != target.id }
            .mapNotNull { byId[it.paintId] }
            .filter { !it.hex.equals(UNKNOWN_HEX, true) && typeGroup(it.type) != "auxiliar" }
            .map { SimilarPaint(it, deltaE2000(lab, it.lab), typeGroup(it.type) == group) }
            .filter { it.distance <= maxDistance }
        val same = candidates.filter { it.sameFamily }.sortedBy { it.distance }
        val other = candidates.filter { !it.sameFamily }.sortedBy { it.distance }
        return (same.take(limit) + other.take((limit - same.size).coerceAtLeast(0).coerceAtMost(2)))
    }

    // ---------- corregir colores ----------

    var colorFixes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Cambia el color de una pintura (se guarda y se aplica también tras actualizar el catálogo). */
    fun fixColor(paintId: String, hex: String) {
        val h = validHex(hex)
        colorFixes = colorFixes + (paintId to h)
        fun upd(list: List<Paint>) = list.map { if (it.id == paintId) it.copy(hex = h, quality = "u") else it }
        catalog = upd(catalog)
        extra = upd(extra)
        custom = upd(custom)
        rebuildIndexes()
        persist()
    }

    // ---------- mezclas ----------

    /** Recetas para acercarse a [target] con las pinturas del inventario (sin contar [excludeId]). */
    suspend fun mixesFor(target: Lab, excludeId: String? = null): List<MixRecipe> {
        val inv = inventoryPaints().filter { it.id != excludeId }
        return withContext(Dispatchers.Default) { findMixes(target, inv) }
    }

    // ---------- paleta desde una imagen ----------

    class PaletteView(
        val uri: Uri,
        val detail: Int,
        val source: Bitmap,
        val palette: ImagePalette,
        val matches: List<PaletteMatch>,
        val preview: Bitmap,
        val advice: PaletteAdvice,
    )

    var palette by mutableStateOf<PaletteView?>(null)
        private set
    var paletteBusy by mutableStateOf(false)
        private set
    var paletteError by mutableStateOf<String?>(null)
        private set

    private fun inventoryPaints(): List<Paint> =
        owned.values.filter { it.qty > 0 }.mapNotNull { byId[it.paintId] }

    /** Analiza la imagen y sugiere pinturas del inventario. [detail]: 5 pocos, 8 normal, 12 detallado. */
    fun analyzePalette(uri: Uri, detail: Int) {
        if (paletteBusy) return
        paletteBusy = true
        paletteError = null
        val inv = inventoryPaints()
        val cat = extra + catalog
        val reuse = palette?.takeIf { it.uri == uri }?.source
        viewModelScope.launch {
            var error: String? = null
            val result = withContext(Dispatchers.Default) {
                val bmp = try {
                    reuse ?: loadBitmapForAnalysis(getApplication(), uri)
                } catch (e: Throwable) {
                    null
                }
                if (bmp == null) {
                    error = "No pude abrir esa imagen. Prueba con otra (JPG o PNG)."
                    null
                } else {
                    try {
                        val ip = analyzeImage(bmp, detail)
                        val matches = matchPalette(ip, inv, cat)
                        PaletteView(uri, detail, bmp, ip, matches, renderPreview(ip, matches, bmp), buildAdvice(ip, matches, inv))
                    } catch (e: Throwable) {
                        error = "Abrí la imagen pero falló el análisis (${e.javaClass.simpleName}: ${e.message}). Envíame este mensaje."
                        null
                    }
                }
            }
            if (result == null) paletteError = error ?: "No pude analizar la imagen."
            else palette = result
            paletteBusy = false
        }
    }

    /** Recalcula las sugerencias cuando cambia el inventario, sin volver a analizar la imagen. */
    fun refreshPaletteMatches() {
        val current = palette ?: return
        val inv = inventoryPaints()
        val cat = extra + catalog
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) {
                val matches = matchPalette(current.palette, inv, cat)
                PaletteView(
                    current.uri, current.detail, current.source, current.palette, matches,
                    renderPreview(current.palette, matches, current.source), buildAdvice(current.palette, matches, inv),
                )
            }
            if (palette === current) palette = updated
        }
    }

    fun clearPalette() {
        palette = null
        paletteError = null
    }

    // ---------- escaneo ----------

    /**
     * Interpreta lo que vio la cámara: códigos de barras y líneas de texto (agrupadas por bloque).
     * Busca códigos de producto ("70.955", "MPA-040", "WP1401") y nombres exactos ("Flat Flesh").
     */
    fun interpret(barcodeValues: List<String>, blocks: List<List<String>>): ScanResult {
        val hits = LinkedHashMap<String, ScanHit>()
        val unknown = ArrayList<String>()

        for (raw in barcodeValues) {
            val code = raw.trim()
            val linked = barcodes[code]?.let { byId[it] }
            if (linked != null) {
                hits[linked.id] = ScanHit(linked, "código de barras")
                continue
            }
            // Vallejo: EAN 8429551 + referencia de 5 dígitos (70.955 -> 8429551709552)
            if (code.length == 13 && code.startsWith("8429551")) {
                val ref = code.substring(7, 12)
                val found = codeIndex[ref].orEmpty().filter { it.brand == "Vallejo" }
                if (found.isNotEmpty()) {
                    found.forEach { hits[it.id] = ScanHit(it, "código de barras") }
                    continue
                }
            }
            if (code.length >= 8) unknown.add(code)
        }

        for (lines in blocks) {
            for (h in matchBlock(lines)) if (!hits.containsKey(h.paint.id)) hits[h.paint.id] = h
        }
        return ScanResult(hits.values.toList(), unknown)
    }

    /** Pinturas que coinciden con un bloque de texto (código impreso o nombre). */
    fun matchBlock(lines: List<String>): List<ScanHit> {
        val hits = LinkedHashMap<String, ScanHit>()
        val candidates = LinkedHashSet<String>()
        lines.forEachIndexed { i, line ->
            val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            tokens.forEachIndexed { j, t ->
                candidates.add("C:" + codeKey(t))
                if (j + 1 < tokens.size) candidates.add("C:" + codeKey(t + tokens[j + 1]))
            }
            candidates.add("C:" + codeKey(line))
            candidates.add("N:" + nameKey(line))
            if (i + 1 < lines.size) candidates.add("N:" + nameKey(line + " " + lines[i + 1]))
        }
        for (c in candidates) {
            val key = c.substring(2)
            if (c.startsWith("C:")) {
                if (key.length < 4 || key.length > 10) continue
                if (key.all { it.isDigit() } && key.length < 5) continue
                codeIndex[key]?.forEach { if (!hits.containsKey(it.id)) hits[it.id] = ScanHit(it, "código ${it.code}") }
            } else {
                if (key.length < 5) continue
                nameIndex[key]?.forEach { if (!hits.containsKey(it.id)) hits[it.id] = ScanHit(it, "nombre") }
            }
        }
        return hits.values.toList()
    }

    // ---------- dónde guardas tus pinturas ----------

    var spots by mutableStateOf<List<StorageSpot>>(emptyList())
        private set
    var locations by mutableStateOf<Map<String, PaintLocation>>(emptyMap())
        private set

    fun spotFile(s: StorageSpot): File = File(File(getApplication<Application>().filesDir, "spots"), s.photo)

    fun spotOf(paintId: String): StorageSpot? = locations[paintId]?.let { l -> spots.firstOrNull { it.id == l.spotId } }

    /** Guarda una foto de una gaveta o estante (reducida a 2400 px) como nuevo lugar. */
    fun addSpot(uri: Uri, name: String, onDone: (StorageSpot?) -> Unit) {
        viewModelScope.launch {
            val spot = withContext(Dispatchers.IO) {
                try {
                    val bmp = loadBitmapForAnalysis(getApplication(), uri, 2400) ?: return@withContext null
                    val id = UUID.randomUUID().toString()
                    val dir = File(getApplication<Application>().filesDir, "spots").apply { mkdirs() }
                    val file = "$id.jpg"
                    File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    StorageSpot(id, name.ifBlank { "Gaveta ${spots.size + 1}" }, file)
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

    fun renameSpot(id: String, name: String) {
        spots = spots.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        persist()
    }

    fun deleteSpot(id: String) {
        ocrCache.remove(id)
        val s = spots.firstOrNull { it.id == id } ?: return
        spots = spots - s
        locations = locations.filterValues { it.spotId != id }
        persist()
        viewModelScope.launch(Dispatchers.IO) { spotFile(s).delete() }
    }

    fun setLocation(paintId: String, spotId: String, x: Float, y: Float) {
        locations = locations + (paintId to PaintLocation(paintId, spotId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
        persist()
    }

    fun clearLocation(paintId: String) {
        locations = locations - paintId
        persist()
    }

    /**
     * Lee las etiquetas visibles en la foto (texto en cualquier orientación) y devuelve qué
     * pinturas reconoce y dónde. Las de tu inventario se aceptan por nombre o código; las que no
     * tienes, solo por código (para evitar falsos positivos con nombres comunes como "Black").
     */
    suspend fun detectInSpot(spot: StorageSpot): List<DetectedPaint> {
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

    // ---------- localizar una pintura ----------

    /** Texto leído por foto (posiciones relativas 0–1), para no repetir el OCR en cada búsqueda. */
    private val ocrCache = HashMap<String, List<TextBlockAt>>()

    private fun loadSpotBitmap(spot: StorageSpot): android.graphics.Bitmap? =
        android.graphics.BitmapFactory.decodeFile(spotFile(spot).absolutePath)

    fun isSpotRead(spot: StorageSpot) = ocrCache.containsKey(spot.id)

    /**
     * Dónde podría estar [paint] en tus fotos, de más a menos probable:
     * 1. la ubicación que confirmaste antes;
     * 2. su etiqueta leída en la foto;
     * 3. zonas del color de la pintura, con más peso si están cerca de otras pinturas de la misma marca.
     */
    suspend fun locatePaint(paint: Paint): List<LocateHit> {
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
    fun applyDetections(spotId: String, list: List<DetectedPaint>) {
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

    // ---------- catálogo importado ----------

    private fun readCsv(uri: Uri): List<List<String>>? = try {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            Csv.parse(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Agrega pinturas al catálogo desde un CSV. Columnas reconocidas (en español o inglés):
     * Marca/Brand, Línea/Line/Set/Gama, Nombre/Name, Código/Code, Color/Hex (o R,G,B), Tipo/Type.
     * Las que ya están en el catálogo incluido se saltan; volver a importar actualiza las importadas.
     */
    fun importCatalogCsv(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { readCsv(uri) }
            if (rows == null || rows.size < 2) {
                onDone("No se pudo leer el archivo o está vacío")
                return@launch
            }
            val header = rows[0].map { nameKey(it) }
            fun col(vararg names: String): Int = header.indexOfFirst { h -> names.any { h == it } }
            val iBrand = col("marca", "brand", "fabricante", "manufacturer")
            val iLine = col("linea", "line", "set", "gama", "range", "serie", "series")
            val iName = col("nombre", "name", "colorname", "paint")
            val iCode = col("codigo", "code", "ref", "referencia", "sku", "id")
            val iHex = col("color", "hex", "colour", "rgb", "hexcode")
            val iR = col("r", "red", "rojo")
            val iG = col("g", "green", "verde")
            val iB = col("b", "blue", "azul")
            val iType = col("tipo", "type")
            if (iName < 0) {
                onDone("El archivo necesita al menos una columna Nombre (o Name)")
                return@launch
            }

            val base = custom + catalog
            val baseCodes = base.filter { codeKey(it.code).isNotEmpty() }.map { brandKey(it.brand) + "|" + codeKey(it.code) }.toHashSet()
            val baseNames = base.map { brandKey(it.brand) + "|" + nameKey(it.name) }.toHashSet()
            fun primaryKey(p: Paint): String {
                val ck = codeKey(p.code)
                return if (ck.isNotEmpty()) brandKey(p.brand) + "|c|" + ck else brandKey(p.brand) + "|n|" + nameKey(p.line) + "|" + nameKey(p.name)
            }
            val merged = LinkedHashMap<String, Paint>()
            extra.forEach { merged[primaryKey(it)] = it }

            var added = 0
            var updated = 0
            var skipped = 0
            var invalid = 0
            for (r in rows.drop(1)) {
                fun v(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
                val name = v(iName)
                if (name.isEmpty()) { invalid++; continue }
                val brand = v(iBrand).ifBlank { "Sin marca" }
                val line = v(iLine)
                val code = v(iCode).takeUnless { it.equals("null", true) || it == "-" } ?: ""
                val hex = parseColorCell(v(iHex))
                    ?: (if (iR >= 0 && iG >= 0 && iB >= 0) parseColorCell("${v(iR)},${v(iG)},${v(iB)}") else null)
                    ?: "#888888"
                val typeCell = v(iType)
                val type = PAINT_TYPES.firstOrNull { norm(it) == norm(typeCell) } ?: guessType(name, line)
                val p = Paint(id = "x|$brand|$line|$name|$code", brand = brand, line = line, name = name, code = code, hex = hex, type = type)

                val bk = brandKey(brand)
                val inBase = if (codeKey(code).isNotEmpty()) (bk + "|" + codeKey(code)) in baseCodes else (bk + "|" + nameKey(name)) in baseNames
                if (inBase) { skipped++; continue }
                val k = primaryKey(p)
                val prev = merged[k]
                if (prev != null) {
                    merged[k] = p.copy(id = prev.id)
                    updated++
                } else {
                    merged[k] = p
                    added++
                }
            }
            val list = merged.values.toList()
            extra = list
            rebuildIndexes()
            withContext(Dispatchers.IO) { store.saveExtraCatalog(list) }
            val parts = mutableListOf("$added nuevas")
            if (updated > 0) parts.add("$updated actualizadas")
            if (skipped > 0) parts.add("$skipped ya estaban")
            if (invalid > 0) parts.add("$invalid filas sin nombre")
            onDone("Catálogo: " + parts.joinToString(", "))
        }
    }

    /** Quita las pinturas importadas al catálogo. Las que estén en tu inventario se conservan como pinturas propias. */
    fun clearExtraCatalog(onDone: (String) -> Unit) {
        val ownedExtra = extra.filter { owned.containsKey(it.id) }.map { it.copy(custom = true) }
        val removed = extra.size - ownedExtra.size
        custom = custom + ownedExtra
        extra = emptyList()
        rebuildIndexes()
        persist()
        viewModelScope.launch(Dispatchers.IO) { store.saveExtraCatalog(emptyList()) }
        onDone("Quitadas $removed pinturas importadas" + if (ownedExtra.isNotEmpty()) " (${ownedExtra.size} de tu inventario se conservaron)" else "")
    }

    fun exportCatalogCsv(uri: Uri, onDone: (String) -> Unit) {
        val rows = (extra + catalog).map { p -> listOf(p.brand, p.line, p.name, p.code, p.type, p.hex) }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(Csv.write(rows, Csv.CATALOG_HEADER).toByteArray(Charsets.UTF_8))
                    }
                    "Catálogo exportado: ${rows.size} pinturas"
                } catch (e: Exception) {
                    "No se pudo guardar el archivo: ${e.message}"
                }
            }
            onDone(msg)
        }
    }

    // ---------- CSV ----------

    fun exportCsv(uri: Uri, onDone: (String) -> Unit) {
        val rows = owned.values.mapNotNull { e ->
            val p = byId[e.paintId] ?: return@mapNotNull null
            listOf(p.brand, p.line, p.name, p.code, p.type, p.hex, e.qty.toString(), e.notes)
        }.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(Csv.write(rows).toByteArray(Charsets.UTF_8))
                    }
                    "Exportadas ${rows.size} pinturas"
                } catch (e: Exception) {
                    "No se pudo guardar el archivo: ${e.message}"
                }
            }
            onDone(msg)
        }
    }

    fun importCsv(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { readCsv(uri) }
            if (rows == null || rows.size < 2) {
                onDone("No se pudo leer el archivo o está vacío")
                return@launch
            }
            val header = rows[0].map { nameKey(it) }
            fun col(vararg names: String): Int = header.indexOfFirst { h -> names.any { h == it } }
            val iBrand = col("marca", "brand")
            val iLine = col("linea", "line", "gama")
            val iName = col("nombre", "name")
            val iCode = col("codigo", "code")
            val iType = col("tipo", "type")
            val iHex = col("color", "hex")
            val iQty = col("botes", "cantidad", "qty")
            val iNotes = col("notas", "notes")
            if (iName < 0 && iCode < 0) {
                onDone("El archivo no tiene columnas Nombre o Código")
                return@launch
            }
            var matched = 0
            var created = 0
            for (r in rows.drop(1)) {
                fun v(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
                val name = v(iName)
                val code = v(iCode)
                if (name.isEmpty() && code.isEmpty()) continue
                val qty = v(iQty).toIntOrNull() ?: 1
                val notes = v(iNotes)
                val found = findPaint(v(iBrand), v(iLine), name, code)
                if (found != null) {
                    val cur = owned[found.id]
                    val mergedNotes = listOf(cur?.notes ?: "", notes).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    update(found.id, (cur?.qty ?: 0) + qty, mergedNotes)
                    matched++
                } else {
                    val type = v(iType).takeIf { it in PAINT_TYPES } ?: "Opaca"
                    val id = addCustom(v(iBrand), v(iLine), name.ifEmpty { code }, code, v(iHex), type, qty)
                    if (notes.isNotBlank()) update(id, qtyOf(id), notes)
                    created++
                }
            }
            onDone("Importadas ${matched + created}: $matched del catálogo, $created nuevas")
        }
    }

    // ---------- lista de compras ----------

    var shopping by mutableStateOf<List<ShopItem>>(emptyList())
        private set

    fun inShopping(paintId: String): Boolean = shopping.any { it.paintId == paintId }

    fun addToShopping(paintId: String, reason: String) {
        if (inShopping(paintId)) return
        shopping = shopping + ShopItem(paintId, reason, System.currentTimeMillis())
        persist()
    }

    fun removeFromShopping(paintId: String) {
        shopping = shopping.filterNot { it.paintId == paintId }
        persist()
    }

    /** Marca una pintura que tienes como "se está acabando" (o lo quita). */
    fun setLow(paintId: String, low: Boolean) {
        val cur = owned[paintId] ?: return
        owned = owned + (paintId to cur.copy(low = low, updatedAt = System.currentTimeMillis()))
        persist()
    }

    /** La compraste: suma un bote al inventario y la quita de la lista. */
    fun bought(paintId: String) {
        val cur = owned[paintId]
        owned = owned + (paintId to OwnedEntry(paintId, (cur?.qty ?: 0) + 1, cur?.notes ?: "", System.currentTimeMillis(), low = false))
        shopping = shopping.filterNot { it.paintId == paintId }
        persist()
    }

    /** Pinturas que tienes marcadas como "se está acabando". */
    val lowPaints: List<Paint>
        get() = owned.values.filter { it.low && it.qty > 0 }.mapNotNull { byId[it.paintId] }.sortedWith(compareBy({ it.brand }, { it.name }))

    val shoppingCount: Int get() = shopping.size + owned.values.count { it.low && it.qty > 0 }

    /** La lista como texto para compartir (WhatsApp, notas, correo). */
    fun shoppingText(): String {
        val lines = ArrayList<String>()
        val items = shopping.mapNotNull { byId[it.paintId] } + lowPaints.filter { lp -> shopping.none { it.paintId == lp.id } }
        items.groupBy { it.brand }.toSortedMap().forEach { (brand, list) ->
            lines.add(brand.uppercase())
            list.sortedBy { it.name }.forEach { p ->
                lines.add("• " + listOf(p.code, p.name).filter { it.isNotBlank() }.joinToString(" ") + (if (p.line.isNotBlank()) " (${p.line})" else ""))
            }
            lines.add("")
        }
        return "Lista de compras · Gotario\n\n" + lines.joinToString("\n").trim()
    }

    /**
     * Pinturas del catálogo para cubrir un tono que no tienes: opacas, con color medido y bien
     * saturadas; primero las de marcas que ya usas.
     */
    private var wheelCache: Pair<List<Paint>, List<Pair<Paint, WheelPos>>>? = null

    fun suggestForHue(ryb: Float, limit: Int = 3): List<Paint> {
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
    suspend fun recommendations(): List<Recommendation> {
        val inv = inventoryPaints()
        val cat = extra + catalog
        return withContext(Dispatchers.Default) { recommendPaints(inv, cat) }
    }

    // ---------- proyectos ----------

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    fun projectFile(projectId: String, name: String): File = File(projectStore.dir(projectId), name)

    private fun saveProjects() {
        val snapshot = projects
        viewModelScope.launch(Dispatchers.IO) { projectStore.save(snapshot) }
        scheduleAutoBackup()
    }

    /** Copia una imagen al proyecto, reducida a 1600 px en JPG. Devuelve el nombre del archivo. */
    private fun importImage(projectId: String, uri: Uri, prefix: String): String? {
        val ctx = getApplication<Application>()
        return try {
            val bmp = loadBitmapScaled(ctx, uri, 1600) ?: return null
            val name = prefix + "-" + System.currentTimeMillis() + ".jpg"
            File(projectStore.dir(projectId), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            name
        } catch (e: Throwable) {
            null
        }
    }

    private fun loadBitmapScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Respeta la orientación de la foto
        val rotation = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        if (rotation == 0f && scale >= 1f) return raw
        val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    fun createProject(name: String, reference: Uri?, paints: List<ProjectPaint>, notes: String, onDone: (Project) -> Unit) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            val ref = if (reference != null) withContext(Dispatchers.IO) { importImage(id, reference, "ref") } else null
            val p = Project(
                id = id, name = name.trim().ifBlank { "Proyecto" }, createdAt = System.currentTimeMillis(),
                status = "En progreso", notes = notes, reference = ref,
                paints = paints.distinctBy { it.paintId }, photos = emptyList(),
            )
            projects = listOf(p) + projects
            saveProjects()
            onDone(p)
        }
    }

    /** Crea un proyecto con la imagen y las pinturas sugeridas en Paleta. */
    fun projectFromPalette(name: String, onDone: (Project) -> Unit) {
        val pv = palette ?: return
        val paints = pv.matches.mapIndexedNotNull { i, m ->
            val p = m.best?.paint ?: return@mapIndexedNotNull null
            val z = pv.advice.zones.getOrNull(i)
            ProjectPaint(p.id, listOfNotNull(z?.role, z?.group).joinToString(" · "))
        }
        val notes = pv.advice.plan.joinToString("\n") { "• $it" }
        createProject(name, pv.uri, paints, notes, onDone)
    }

    private fun updateProject(id: String, f: (Project) -> Project) {
        projects = projects.map { if (it.id == id) f(it) else it }
        saveProjects()
    }

    fun renameProject(id: String, name: String) = updateProject(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun setProjectNotes(id: String, notes: String) = updateProject(id) { it.copy(notes = notes) }
    fun setProjectStatus(id: String, status: String) = updateProject(id) { it.copy(status = status) }

    fun addPaintToProject(id: String, paintId: String, use: String = "") =
        updateProject(id) { p -> if (p.paints.any { it.paintId == paintId }) p else p.copy(paints = p.paints + ProjectPaint(paintId, use)) }

    fun setPaintUse(id: String, paintId: String, use: String) =
        updateProject(id) { p -> p.copy(paints = p.paints.map { if (it.paintId == paintId) it.copy(use = use) else it }) }

    fun removePaintFromProject(id: String, paintId: String) =
        updateProject(id) { p -> p.copy(paints = p.paints.filterNot { it.paintId == paintId }) }

    fun setProjectReference(id: String, uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { importImage(id, uri, "ref") } ?: return@launch
            val old = projects.firstOrNull { it.id == id }?.reference
            updateProject(id) { it.copy(reference = name) }
            if (old != null && old != name) withContext(Dispatchers.IO) { projectFile(id, old).delete() }
        }
    }

    fun addProgressPhoto(id: String, uri: Uri, caption: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { importImage(id, uri, "avance") }
            if (name != null) updateProject(id) { it.copy(photos = it.photos + ProgressPhoto(name, System.currentTimeMillis(), caption.trim())) }
            onDone(name != null)
        }
    }

    fun setPhotoCaption(id: String, file: String, caption: String) =
        updateProject(id) { p -> p.copy(photos = p.photos.map { if (it.file == file) it.copy(caption = caption.trim()) else it }) }

    fun removeProgressPhoto(id: String, file: String) {
        updateProject(id) { p -> p.copy(photos = p.photos.filterNot { it.file == file }) }
        viewModelScope.launch(Dispatchers.IO) { projectFile(id, file).delete() }
    }

    fun deleteProject(id: String) {
        projects = projects.filterNot { it.id == id }
        saveProjects()
        viewModelScope.launch(Dispatchers.IO) { projectStore.dir(id).deleteRecursively() }
    }

    // ---------- respaldo ----------

    /** Archivo elegido para el respaldo automático (puede estar en Google Drive). */
    var autoBackupUri by mutableStateOf(prefs.getString("backup_uri", null)?.let { Uri.parse(it) })
        private set
    var lastBackupAt by mutableStateOf(prefs.getLong("backup_at", 0L))
        private set
    var backupError by mutableStateOf<String?>(null)
        private set
    private var backupJob: Job? = null

    private fun projectsRoot(): File = File(getApplication<Application>().filesDir, "projects")

    private fun currentState() = SavedState(owned, custom, barcodes, spots, locations, colorFixes, shopping)

    private fun writeBackup(uri: Uri): String? = try {
        val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
            ?: getApplication<Application>().contentResolver.openOutputStream(uri, "w")
        if (out == null) "No se pudo abrir el archivo"
        else {
            out.use { Backup.write(it, currentState(), projects, projectsRoot()) }
            null
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun scheduleAutoBackup() {
        val uri = autoBackupUri ?: return
        if (loading) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(4000)
            val err = withContext(Dispatchers.IO) { writeBackup(uri) }
            if (err == null) {
                lastBackupAt = System.currentTimeMillis()
                prefs.edit().putLong("backup_at", lastBackupAt).apply()
                backupError = null
            } else {
                backupError = "El respaldo automático falló: $err. Vuelve a elegir el archivo."
            }
        }
    }

    /** Activa el respaldo automático en el archivo elegido y escribe el primer respaldo. */
    fun enableAutoBackup(uri: Uri, onDone: (String) -> Unit) {
        val ctx = getApplication<Application>()
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            // Algunos proveedores no dan permiso permanente; se intenta igual
        }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { writeBackup(uri) }
            if (err == null) {
                autoBackupUri = uri
                lastBackupAt = System.currentTimeMillis()
                prefs.edit().putString("backup_uri", uri.toString()).putLong("backup_at", lastBackupAt).apply()
                backupError = null
                onDone("Respaldo automático activado")
            } else {
                onDone("No se pudo escribir el respaldo: $err")
            }
        }
    }

    fun disableAutoBackup() {
        val uri = autoBackupUri
        autoBackupUri = null
        backupError = null
        prefs.edit().remove("backup_uri").apply()
        if (uri != null) try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    fun backupNow(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { writeBackup(uri) }
            onDone(if (err == null) "Respaldo guardado: ${owned.size} pinturas, ${projects.size} proyectos" else "No se pudo guardar: $err")
        }
    }

    /**
     * Restaura un respaldo uniéndolo con lo que ya hay: para cada pintura gana el registro más reciente,
     * y se agregan las pinturas propias, códigos, colores, compras y proyectos que no estén.
     */
    fun restoreBackup(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { Backup.read(it, projectsRoot()) }
                } catch (e: Exception) {
                    null
                }
            }
            if (content == null) {
                onDone("Ese archivo no es un respaldo de Gotario")
                return@launch
            }
            val b = content.state
            val knownCustom = custom.map { it.id }.toHashSet()
            val newCustom = b.custom.filter { it.id !in knownCustom }
            custom = custom + newCustom
            val mergedOwned = LinkedHashMap(owned)
            var added = 0
            b.owned.values.forEach { e ->
                val cur = mergedOwned[e.paintId]
                if (cur == null) added++
                if (cur == null || e.updatedAt > cur.updatedAt) mergedOwned[e.paintId] = e
            }
            owned = mergedOwned
            barcodes = b.barcodes + barcodes
            val fixes = b.colorFixes + colorFixes
            if (fixes != colorFixes) {
                colorFixes = fixes
                catalog = catalog.map { p -> fixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
                extra = extra.map { p -> fixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
            }
            shopping = shopping + b.shopping.filter { s -> shopping.none { it.paintId == s.paintId } }
            val knownProjects = projects.map { it.id }.toHashSet()
            val newProjects = content.projects.filter { it.id !in knownProjects }
            projects = projects + newProjects
            rebuildIndexes()
            migrateOrphans()
            persist()
            saveProjects()
            onDone("Restaurado: $added pinturas nuevas, ${newCustom.size} propias, ${newProjects.size} proyectos")
        }
    }
}
