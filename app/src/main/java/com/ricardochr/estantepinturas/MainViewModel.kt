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

    internal val store = Store(app)
    internal val projectStore = ProjectStore(app)
    internal val prefs = app.getSharedPreferences("gotario", Context.MODE_PRIVATE)

    var loading by mutableStateOf(true)
        internal set
    var catalog by mutableStateOf<List<Paint>>(emptyList())
        internal set
    var custom by mutableStateOf<List<Paint>>(emptyList())
        internal set
    /** Pinturas importadas al catálogo desde un CSV. */
    var extra by mutableStateOf<List<Paint>>(emptyList())
        internal set
    var owned by mutableStateOf<Map<String, OwnedEntry>>(emptyMap())
        internal set
    var barcodes by mutableStateOf<Map<String, String>>(emptyMap())
        internal set

    /** Catálogo + pinturas propias, indexado por id. */
    var byId by mutableStateOf<Map<String, Paint>>(emptyMap())
        internal set

    internal var codeIndex: Map<String, List<Paint>> = emptyMap()
    internal var nameIndex: Map<String, List<Paint>> = emptyMap()

    val allPaints: List<Paint> get() = custom + extra + catalog

    /** Cuántas pinturas hay por marca en el catálogo (incluido + importado). */
    val brandCounts: List<Pair<String, Int>>
        get() = (extra + catalog).groupingBy { it.brand }.eachCount().toList().sortedByDescending { it.second }

    init {
        viewModelScope.launch {
            val (cat, saved) = withContext(Dispatchers.IO) { store.loadCatalog() to store.loadState() }
            val ext = withContext(Dispatchers.IO) { store.loadExtraCatalog() }
            sets = withContext(Dispatchers.IO) { store.loadSets() }
            catalogDownloadedAt = withContext(Dispatchers.IO) { store.catalogDownloadedAt() }
            catalog = cat
            extra = ext
            custom = saved.custom
            owned = saved.owned
            barcodes = saved.barcodes
            spots = saved.spots
            locations = saved.locations
            colorFixes = saved.colorFixes
            shopping = saved.shopping
            gear = saved.gear
            customSets = saved.customSets
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
    internal fun migrateOrphans() {
        val refs = UserRefs(owned, barcodes, colorFixes, shopping, locations, projects, customSets)
        val orphanIds = referencedIds(refs).filter { !byId.containsKey(it) }.toSet() +
            colorFixes.keys.filter { !byId.containsKey(it) }
        if (orphanIds.isEmpty()) return
        val remap = HashMap<String, String>()
        val newCustom = ArrayList<Paint>()
        val needed = referencedIds(refs)
        for (oldId in orphanIds) {
            val parts = oldId.removePrefix("x|").split("|")
            if (parts.size < 4) continue
            val (brand, line, name, code) = parts
            val found = findPaint(brand, line, name, code)
            if (found != null) {
                remap[oldId] = found.id
            } else if (oldId in needed) {
                // Ya no está en el catálogo: se conserva como pintura propia (con su color corregido, si lo tenía)
                val p = Paint(
                    id = "custom:" + UUID.randomUUID().toString(),
                    brand = brand, line = line, name = name, code = code,
                    hex = colorFixes[oldId] ?: "#888888", type = guessType(name, line), custom = true,
                )
                newCustom.add(p)
                remap[oldId] = p.id
            }
        }
        if (remap.isEmpty()) return
        val r = applyRemap(refs, remap)
        owned = r.owned
        barcodes = r.barcodes
        shopping = r.shopping
        locations = r.locations
        customSets = r.customSets
        if (r.colorFixes != colorFixes) {
            colorFixes = r.colorFixes
            catalog = catalog.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
            extra = extra.map { p -> colorFixes[p.id]?.let { p.copy(hex = it, quality = "u") } ?: p }
        }
        if (r.projects != projects) {
            projects = r.projects
            saveProjects()
        }
        if (newCustom.isNotEmpty()) custom = custom + newCustom
        rebuildIndexes()
        persist()
    }

    /**
     * Si registraste a mano una pintura que después llegó al catálogo (por ejemplo los lavados de AK
     * o los metálicos de Kaleido), se pasa a la del catálogo: botes, notas, códigos de barras,
     * lista de compras, ubicación y proyectos. Si le habías puesto color, se conserva como corrección.
     */
    internal fun adoptCatalogMatches() {
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

    internal fun rebuildIndexes() {
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

    internal fun persist() {
        val snapshot = SavedState(owned, custom, barcodes, spots, locations, colorFixes, shopping, gear, customSets)
        viewModelScope.launch(Dispatchers.IO) { store.saveState(snapshot) }
        scheduleAutoBackup()
    }

    // ---------- inventario ----------

    fun qtyOf(paintId: String): Int = owned[paintId]?.qty ?: 0

    fun add(paintId: String, delta: Int = 1) {
        val cur = owned[paintId]
        val qty = ((cur?.qty ?: 0) + delta).coerceAtLeast(0)
        owned = owned + (paintId to OwnedEntry(paintId, qty, cur?.notes ?: "", System.currentTimeMillis(), low = (cur?.low ?: false) && qty > 0, thin = cur?.thin ?: ""))
        persist()
    }

    fun update(paintId: String, qty: Int, notes: String) {
        val low = (owned[paintId]?.low ?: false) && qty > 0
        owned = owned + (paintId to OwnedEntry(paintId, qty.coerceAtLeast(0), notes.trim(), System.currentTimeMillis(), low, thin = owned[paintId]?.thin ?: ""))
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
    /** Equivalente de una pintura en otra marca: la pintura, qué tan parecida es y si ya la tienes. */
    class Equivalent(val paint: Paint, val distance: Double, val owned: Boolean)

    /**
     * La pintura más parecida de cada una de las otras marcas del catálogo (mismo tipo de pintura).
     * Si tienes una casi igual de esa marca, se prefiere la tuya.
     */
    suspend fun equivalentsOf(target: Paint, limit: Int = 8): List<Equivalent> {
        if (!target.hasColor) return emptyList()
        val all = allPaints
        val ownedIds = owned.values.filter { it.qty > 0 }.map { it.paintId }.toHashSet()
        return withContext(Dispatchers.Default) {
            val lab = target.lab
            val group = typeGroup(target.type)
            val myBrand = brandKey(target.brand)
            val best = HashMap<String, Equivalent>()
            for (p in all) {
                if (!p.hasColor || p.quality == "n" || typeGroup(p.type) != group) continue
                val bk = brandKey(p.brand)
                if (bk == myBrand) continue
                val d = deltaE2000(lab, p.lab)
                if (d > 12) continue
                val has = p.id in ownedIds
                val cur = best[bk]
                // Una tuya gana si está a menos de 2 de diferencia de la mejor
                val score = d - if (has) 2.0 else 0.0
                if (cur == null || score < cur.distance - if (cur.owned) 2.0 else 0.0) best[bk] = Equivalent(p, d, has)
            }
            best.values.sortedBy { it.distance }.take(limit)
        }
    }

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
        internal set

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
        /** Colores tal como salen de la foto, antes de corregir la exposición. */
        val original: ImagePalette = palette,
        /** La foto es oscura (fotografía con poca luz o con sombras fuertes). */
        val darkPhoto: Boolean = false,
        /** Se aclararon los colores de la foto para elegir pinturas. */
        val brightened: Boolean = false,
        /** Colores de la imagen que cambiaste para probar una variante (índices de [palette].clusters). */
        val recolored: Set<Int> = emptySet(),
        /** La figura con tus pinturas sin las variantes, para comparar; null si no hay variantes. */
        val basePreview: Bitmap? = null,
    )

    /** Variantes de color: color nuevo para algunos colores de la imagen (por índice). */
    var paletteOverrides by mutableStateOf<Map<Int, Lab>>(emptyMap())
        internal set
    /** Técnicas aplicadas con Repintar (para el plan del proyecto). */
    var repaintSteps by mutableStateOf<List<RepaintStep>>(emptyList())
        internal set
    /** Estados anteriores para Deshacer en Repintar. */
    internal var repaintHistory: List<Pair<Map<Int, Lab>, List<RepaintStep>>> = emptyList()
    var canUndoRepaint by mutableStateOf(false)
        internal set

    var palette by mutableStateOf<PaletteView?>(null)
        internal set
    var paletteBusy by mutableStateOf(false)
        internal set
    var paletteError by mutableStateOf<String?>(null)
        internal set
    /** Corregir automáticamente las fotos oscuras antes de elegir pinturas. */
    var paletteBrighten by mutableStateOf(true)
        internal set

    /** Color de un punto de la foto y las pinturas tuyas que más se le parecen. */
    class PointPick(
        val x: Float,
        val y: Float,
        val photoArgb: Int,
        val lab: Lab,
        val label: Int,
        val matches: List<SimilarPaint>,
        val toBuy: Paint?,
        val toBuyDistance: Double,
    )
    // ---------- escaneo ----------
    // ---------- dónde guardas tus pinturas ----------

    var spots by mutableStateOf<List<StorageSpot>>(emptyList())
        internal set
    var locations by mutableStateOf<Map<String, PaintLocation>>(emptyMap())
        internal set
    // ---------- localizar una pintura ----------

    /** Texto leído por foto (posiciones relativas 0–1), para no repetir el OCR en cada búsqueda. */
    internal val ocrCache = HashMap<String, List<TextBlockAt>>()
    // ---------- catálogo importado ----------
    // ---------- CSV ----------
    // ---------- sets de pinturas ----------

    var sets by mutableStateOf<List<PaintSet>>(emptyList())
        internal set

    // ---------- actualizar catálogo desde internet ----------

    var catalogUpdating by mutableStateOf(false)
        internal set
    var catalogDownloadedAt by mutableStateOf(0L)
        internal set

    /** Sets que creaste o editaste. */
    var customSets by mutableStateOf<List<PaintSet>>(emptyList())
        internal set

    /** Tus sets primero, y los de las tiendas que no editaste. */
    val allSets: List<PaintSet>
        get() {
            val replaced = customSets.mapNotNull { it.replaces }.toHashSet()
            return customSets + sets.filter { it.key !in replaced }
        }
    // ---------- equipo (aerógrafos y compresor) ----------

    var gear by mutableStateOf<List<Gear>>(emptyList())
        internal set

    val gearKit: GearKit get() = GearKit.of(gear)
    // ---------- lista de compras ----------

    var shopping by mutableStateOf<List<ShopItem>>(emptyList())
        internal set

    /** Pinturas que tienes marcadas como "se está acabando". */
    val lowPaints: List<Paint>
        get() = owned.values.filter { it.low && it.qty > 0 }.mapNotNull { byId[it.paintId] }.sortedWith(compareBy({ it.brand }, { it.name }))

    val shoppingCount: Int get() = shopping.size + owned.values.count { it.low && it.qty > 0 }

    /**
     * Pinturas del catálogo para cubrir un tono que no tienes: opacas, con color medido y bien
     * saturadas; primero las de marcas que ya usas.
     */
    internal var wheelCache: Pair<List<Paint>, List<Pair<Paint, WheelPos>>>? = null
    // ---------- proyectos ----------

    var projects by mutableStateOf<List<Project>>(emptyList())
        internal set
    // ---------- respaldo ----------

    /** Archivo elegido para el respaldo automático (puede estar en Google Drive). */
    var autoBackupUri by mutableStateOf(prefs.getString("backup_uri", null)?.let { Uri.parse(it) })
        internal set
    var lastBackupAt by mutableStateOf(prefs.getLong("backup_at", 0L))
        internal set
    var backupError by mutableStateOf<String?>(null)
        internal set
    internal var backupJob: Job? = null
}
