package com.ricardochr.estantepinturas

import android.app.Application
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
    var owned by mutableStateOf<Map<String, OwnedEntry>>(emptyMap())
        private set
    var barcodes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Catálogo + pinturas propias, indexado por id. */
    var byId by mutableStateOf<Map<String, Paint>>(emptyMap())
        private set

    private var codeIndex: Map<String, List<Paint>> = emptyMap()
    private var nameIndex: Map<String, List<Paint>> = emptyMap()

    val allPaints: List<Paint> get() = custom + catalog

    init {
        viewModelScope.launch {
            val (cat, saved) = withContext(Dispatchers.IO) { store.loadCatalog() to store.loadState() }
            catalog = cat
            custom = saved.custom
            owned = saved.owned
            barcodes = saved.barcodes
            rebuildIndexes()
            loading = false
        }
    }

    private fun rebuildIndexes() {
        val all = custom + catalog
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
        val all = custom + catalog
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
            val rows = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        Csv.parse(it.readBytes().toString(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    null
                }
            }
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
