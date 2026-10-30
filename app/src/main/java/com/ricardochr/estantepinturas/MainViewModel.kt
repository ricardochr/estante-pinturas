package com.ricardochr.estantepinturas

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Una pintura reconocida por la cámara. */
data class ScanHit(val paint: Paint, val via: String)

/** Resultado de analizar un cuadro de la cámara. */
data class ScanResult(val hits: List<ScanHit>, val unknownBarcodes: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

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
            // Prepara texto de búsqueda y color Lab de todas las pinturas antes de usarlas
            withContext(Dispatchers.Default) { (ext + cat).forEach { it.searchText; it.lab } }
            rebuildIndexes()
            migrateOrphans()
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
        val snapshot = SavedState(owned, custom, barcodes)
        viewModelScope.launch(Dispatchers.IO) { store.saveState(snapshot) }
    }

    // ---------- inventario ----------

    fun qtyOf(paintId: String): Int = owned[paintId]?.qty ?: 0

    fun add(paintId: String, delta: Int = 1) {
        val cur = owned[paintId]
        val qty = ((cur?.qty ?: 0) + delta).coerceAtLeast(0)
        owned = owned + (paintId to OwnedEntry(paintId, qty, cur?.notes ?: "", System.currentTimeMillis()))
        persist()
    }

    fun update(paintId: String, qty: Int, notes: String) {
        owned = owned + (paintId to OwnedEntry(paintId, qty.coerceAtLeast(0), notes.trim(), System.currentTimeMillis()))
        persist()
    }

    fun remove(paintId: String) {
        owned = owned - paintId
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

    // ---------- paleta desde una imagen ----------

    class PaletteView(
        val uri: Uri,
        val detail: Int,
        val source: Bitmap,
        val palette: ImagePalette,
        val matches: List<PaletteMatch>,
        val preview: Bitmap,
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
                        PaletteView(uri, detail, bmp, ip, matches, renderPreview(ip, matches))
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
                PaletteView(current.uri, current.detail, current.source, current.palette, matches, renderPreview(current.palette, matches))
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
        }
        return ScanResult(hits.values.toList(), unknown)
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
}
