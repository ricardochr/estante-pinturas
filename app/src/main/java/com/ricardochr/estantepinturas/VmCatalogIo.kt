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

// Importar y exportar catálogo e inventario en CSV (funciones de MainViewModel; el estado vive en MainViewModel)

internal fun MainViewModel.readCsv(uri: Uri): List<List<String>>? = try {
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
fun MainViewModel.importCatalogCsv(uri: Uri, onDone: (String) -> Unit) {
    viewModelScope.launch {
        val rows = withContext(Dispatchers.IO) { readCsv(uri) }
        if (rows == null || rows.size < 2) {
            onDone(tr("No se pudo leer el archivo o está vacío"))
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
            onDone(tr("El archivo necesita al menos una columna Nombre (o Name)"))
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
            val brand = v(iBrand).ifBlank { tr("Sin marca") }
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
        val parts = mutableListOf(tr("{} nuevas", added))
        if (updated > 0) parts.add(tr("{} actualizadas", updated))
        if (skipped > 0) parts.add(tr("{} ya estaban", skipped))
        if (invalid > 0) parts.add(tr("{} filas sin nombre", invalid))
        onDone(tr("Catálogo: ") + parts.joinToString(", "))
    }
}

/** Quita las pinturas importadas al catálogo. Las que estén en tu inventario se conservan como pinturas propias. */
fun MainViewModel.clearExtraCatalog(onDone: (String) -> Unit) {
    val ownedExtra = extra.filter { owned.containsKey(it.id) }.map { it.copy(custom = true) }
    val removed = extra.size - ownedExtra.size
    custom = custom + ownedExtra
    extra = emptyList()
    rebuildIndexes()
    persist()
    viewModelScope.launch(Dispatchers.IO) { store.saveExtraCatalog(emptyList()) }
    onDone(tr("Quitadas {} pinturas importadas", removed) + if (ownedExtra.isNotEmpty()) tr(" ({} de tu inventario se conservaron)", ownedExtra.size) else "")
}

fun MainViewModel.exportCatalogCsv(uri: Uri, onDone: (String) -> Unit) {
    val rows = (extra + catalog).map { p -> listOf(p.brand, p.line, p.name, p.code, p.type, p.hex) }
    viewModelScope.launch {
        val msg = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(Csv.write(rows, Csv.CATALOG_HEADER).toByteArray(Charsets.UTF_8))
                }
                tr("Catálogo exportado: {} pinturas", rows.size)
            } catch (e: Exception) {
                tr("No se pudo guardar el archivo: {}", e.message)
            }
        }
        onDone(msg)
    }
}

fun MainViewModel.exportCsv(uri: Uri, onDone: (String) -> Unit) {
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
                tr("Exportadas {} pinturas", rows.size)
            } catch (e: Exception) {
                tr("No se pudo guardar el archivo: {}", e.message)
            }
        }
        onDone(msg)
    }
}

fun MainViewModel.importCsv(uri: Uri, onDone: (String) -> Unit) {
    viewModelScope.launch {
        val rows = withContext(Dispatchers.IO) { readCsv(uri) }
        if (rows == null || rows.size < 2) {
            onDone(tr("No se pudo leer el archivo o está vacío"))
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
            onDone(tr("El archivo no tiene columnas Nombre o Código"))
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
        onDone(tr("Importadas {}: {} del catálogo, {} nuevas", matched + created, matched, created))
    }
}
