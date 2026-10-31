package com.ricardochr.estantepinturas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Todo lo que se guarda en el teléfono. */
data class SavedState(
    val owned: Map<String, OwnedEntry>,
    val custom: List<Paint>,
    val barcodes: Map<String, String>,
    val spots: List<StorageSpot> = emptyList(),
    val locations: Map<String, PaintLocation> = emptyMap(),
)

/** Un lugar donde guardas pinturas (gaveta, estante), con su foto. */
data class StorageSpot(val id: String, val name: String, val photo: String)

/** Dónde está una pintura: en qué foto y en qué punto (0–1 relativo al ancho y alto). */
data class PaintLocation(val paintId: String, val spotId: String, val x: Float, val y: Float)

class Store(private val context: Context) {

    private val file: File get() = File(context.filesDir, "inventario.json")

    fun loadCatalog(): List<Paint> {
        val text = context.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        val out = ArrayList<Paint>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONArray(i)
            val brand = r.getString(0)
            val line = r.getString(1)
            val name = r.getString(2)
            val code = r.getString(3)
            out.add(
                Paint(
                    id = "$brand|$line|$name|$code",
                    brand = brand,
                    line = line,
                    name = name,
                    code = code,
                    hex = r.getString(4),
                    type = r.getString(5),
                )
            )
        }
        return out
    }

    private val extraFile: File get() = File(context.filesDir, "catalogo-extra.json")

    /** Pinturas que el usuario importó al catálogo (además del catálogo incluido). */
    fun loadExtraCatalog(): List<Paint> {
        val f = extraFile
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<Paint>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                out.add(
                    Paint(
                        id = r.getString(0),
                        brand = r.getString(1),
                        line = r.getString(2),
                        name = r.getString(3),
                        code = r.getString(4),
                        hex = r.getString(5),
                        type = r.getString(6),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveExtraCatalog(paints: List<Paint>) {
        val arr = JSONArray()
        paints.forEach { p ->
            arr.put(JSONArray().put(p.id).put(p.brand).put(p.line).put(p.name).put(p.code).put(p.hex).put(p.type))
        }
        val tmp = File(context.filesDir, "catalogo-extra.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(extraFile)) {
            extraFile.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun loadState(): SavedState {
        val f = file
        if (!f.exists()) return SavedState(emptyMap(), emptyList(), emptyMap())
        return loadStateFrom(f)
    }

    private fun loadStateFrom(f: File): SavedState {
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val owned = LinkedHashMap<String, OwnedEntry>()
            val oa = o.optJSONArray("owned") ?: JSONArray()
            for (i in 0 until oa.length()) {
                val e = oa.getJSONObject(i)
                val id = e.getString("paintId")
                owned[id] = OwnedEntry(
                    paintId = id,
                    qty = e.optInt("qty", 1),
                    notes = e.optString("notes", ""),
                    updatedAt = e.optLong("updatedAt", 0L),
                )
            }
            val custom = ArrayList<Paint>()
            val ca = o.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val p = ca.getJSONObject(i)
                custom.add(
                    Paint(
                        id = p.getString("id"),
                        brand = p.optString("brand", ""),
                        line = p.optString("line", ""),
                        name = p.optString("name", ""),
                        code = p.optString("code", ""),
                        hex = validHex(p.optString("hex", "#888888")),
                        type = p.optString("type", "Opaca"),
                        custom = true,
                    )
                )
            }
            val barcodes = LinkedHashMap<String, String>()
            val bo = o.optJSONObject("barcodes") ?: JSONObject()
            val keys = bo.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                barcodes[k] = bo.getString(k)
            }
            val spots = ArrayList<StorageSpot>()
            val sa = o.optJSONArray("spots") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val e = sa.getJSONObject(i)
                spots.add(StorageSpot(e.getString("id"), e.optString("name", "Gaveta"), e.getString("photo")))
            }
            val locations = LinkedHashMap<String, PaintLocation>()
            val la = o.optJSONArray("locations") ?: JSONArray()
            for (i in 0 until la.length()) {
                val e = la.getJSONObject(i)
                val id = e.getString("paintId")
                locations[id] = PaintLocation(id, e.getString("spotId"), e.getDouble("x").toFloat(), e.getDouble("y").toFloat())
            }
            SavedState(owned, custom, barcodes, spots, locations)
        } catch (e: Exception) {
            // Archivo dañado: se conserva una copia para no perderlo y se empieza vacío.
            f.copyTo(File(context.filesDir, "inventario-danado-${System.currentTimeMillis()}.json"), overwrite = true)
            SavedState(emptyMap(), emptyList(), emptyMap())
        }
    }

    @Synchronized
    fun saveState(state: SavedState) {
        val o = JSONObject()
        o.put("version", 1)
        val oa = JSONArray()
        state.owned.values.forEach { e ->
            oa.put(
                JSONObject()
                    .put("paintId", e.paintId)
                    .put("qty", e.qty)
                    .put("notes", e.notes)
                    .put("updatedAt", e.updatedAt)
            )
        }
        o.put("owned", oa)
        val ca = JSONArray()
        state.custom.forEach { p ->
            ca.put(
                JSONObject()
                    .put("id", p.id)
                    .put("brand", p.brand)
                    .put("line", p.line)
                    .put("name", p.name)
                    .put("code", p.code)
                    .put("hex", p.hex)
                    .put("type", p.type)
            )
        }
        o.put("custom", ca)
        val bo = JSONObject()
        state.barcodes.forEach { (k, v) -> bo.put(k, v) }
        o.put("barcodes", bo)
        val sa = JSONArray()
        state.spots.forEach { s -> sa.put(JSONObject().put("id", s.id).put("name", s.name).put("photo", s.photo)) }
        o.put("spots", sa)
        val la = JSONArray()
        state.locations.values.forEach { l ->
            la.put(JSONObject().put("paintId", l.paintId).put("spotId", l.spotId).put("x", l.x.toDouble()).put("y", l.y.toDouble()))
        }
        o.put("locations", la)

        val tmp = File(context.filesDir, "inventario.json.tmp")
        tmp.writeText(o.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }
}

/** CSV compatible con el que exporta la versión web: Marca,Línea,Nombre,Código,Tipo,Color,Botes,Notas */
object Csv {
    val HEADER = listOf("Marca", "Línea", "Nombre", "Código", "Tipo", "Color", "Botes", "Notas")

    val CATALOG_HEADER = listOf("Marca", "Línea", "Nombre", "Código", "Tipo", "Color")

    fun write(rows: List<List<String>>, header: List<String> = HEADER): String {
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { q(it) }).append("\r\n")
        rows.forEach { r -> sb.append(r.joinToString(",") { q(it) }).append("\r\n") }
        return sb.toString()
    }

    fun parse(textIn: String): List<List<String>> {
        val text = textIn.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val sep = detectSeparator(text)
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    sep -> { row.add(cell.toString()); cell.setLength(0) }
                    '\r' -> {}
                    '\n' -> {
                        row.add(cell.toString()); cell.setLength(0)
                        if (row.any { it.isNotBlank() }) rows.add(row)
                        row = ArrayList()
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun detectSeparator(text: String): Char {
        val first = text.lineSequence().firstOrNull() ?: return ','
        return if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
    }
}
