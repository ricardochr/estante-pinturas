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
    /** Colores que corregiste a mano: id de pintura -> #RRGGBB. */
    val colorFixes: Map<String, String> = emptyMap(),
    val shopping: List<ShopItem> = emptyList(),
    val gear: List<Gear> = emptyList(),
    val customSets: List<PaintSet> = emptyList(),
)

/** Un lugar donde guardas pinturas (gaveta, estante), con su foto. */
data class StorageSpot(val id: String, val name: String, val photo: String)

/** Dónde está una pintura: en qué foto y en qué punto (0–1 relativo al ancho y alto). */
data class PaintLocation(val paintId: String, val spotId: String, val x: Float, val y: Float)

class Store(private val context: Context) {

    private val file: File get() = File(context.filesDir, "inventario.json")

    // ---- Catálogo descargado: la versión más reciente publicada en GitHub, sin instalar otro APK

    private val remoteCatalog: File get() = File(context.filesDir, "catalog-remote.json")
    private val remoteSets: File get() = File(context.filesDir, "sets-remote.json")
    private val prefs get() = context.getSharedPreferences("gotario", Context.MODE_PRIVATE)

    /**
     * Usa el catálogo descargado solo si se bajó con esta misma versión de la app: al instalar un APK
     * nuevo, el catálogo que trae adentro es igual o más reciente, y el descargado se descarta.
     */
    private fun remoteValid(): Boolean {
        if (!remoteCatalog.exists()) return false
        if (prefs.getInt("catalog_dl_vc", -1) != BuildConfig.VERSION_CODE) {
            remoteCatalog.delete(); remoteSets.delete()
            prefs.edit().remove("catalog_dl_at").apply()
            return false
        }
        return true
    }

    /** Fecha (ms) de la última descarga del catálogo, o 0 si se usa el que viene en la app. */
    fun catalogDownloadedAt(): Long = if (remoteValid()) prefs.getLong("catalog_dl_at", 0L) else 0L

    /**
     * Descarga catálogo y sets publicados en el repositorio y los guarda si son válidos.
     * Devuelve null si todo salió bien, o el motivo del error.
     */
    fun downloadCatalog(): String? {
        val base = "https://raw.githubusercontent.com/ricardochr/estante-pinturas/main/app/src/main/assets/"
        fun fetch(name: String): String {
            val c = java.net.URL(base + name).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        return try {
            val cat = fetch("catalog.json")
            val sets = fetch("sets.json")
            // Validar antes de reemplazar: que se pueda leer y que no venga casi vacío
            val n = parseCatalog(cat).size
            if (n < 1000) return "el catálogo descargado parece incompleto ($n pinturas)"
            parseSets(sets)
            val t1 = File(context.filesDir, "catalog-remote.json.tmp").apply { writeText(cat, Charsets.UTF_8) }
            val t2 = File(context.filesDir, "sets-remote.json.tmp").apply { writeText(sets, Charsets.UTF_8) }
            if (!t1.renameTo(remoteCatalog)) { remoteCatalog.writeText(cat, Charsets.UTF_8); t1.delete() }
            if (!t2.renameTo(remoteSets)) { remoteSets.writeText(sets, Charsets.UTF_8); t2.delete() }
            prefs.edit().putLong("catalog_dl_at", System.currentTimeMillis()).putInt("catalog_dl_vc", BuildConfig.VERSION_CODE).apply()
            null
        } catch (e: java.net.UnknownHostException) {
            "sin conexión a internet"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Sets de pinturas conocidos (descargados o los de assets/sets.json). */
    fun loadSets(): List<PaintSet> = try {
        val text = if (remoteValid() && remoteSets.exists()) remoteSets.readText(Charsets.UTF_8)
        else context.assets.open("sets.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseSets(text)
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseSets(text: String): List<PaintSet> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ps = o.getJSONArray("paints")
            PaintSet(
                o.getString("brand"), o.getString("name"), o.optString("code", ""), (0 until ps.length()).map { ps.getString(it) },
                expected = o.optInt("expected", 0), status = o.optString("status", "ok"),
                note = o.optString("note", ""), source = o.optString("source", ""),
            )
        }
    }

    fun loadCatalog(): List<Paint> {
        if (remoteValid()) {
            try {
                return parseCatalog(remoteCatalog.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                remoteCatalog.delete()
            }
        }
        return parseCatalog(context.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun parseCatalog(text: String): List<Paint> {
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
                    quality = if (r.length() > 6) r.optString(6, "") else "",
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
            stateFromJson(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            // Archivo dañado: se conserva una copia para no perderlo y se empieza vacío.
            f.copyTo(File(context.filesDir, "inventario-danado-${System.currentTimeMillis()}.json"), overwrite = true)
            SavedState(emptyMap(), emptyList(), emptyMap())
        }
    }

    @Synchronized
    fun saveState(state: SavedState) {
        val o = stateToJson(state)
        val tmp = File(context.filesDir, "inventario.json.tmp")
        tmp.writeText(o.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        fun stateFromJson(o: JSONObject): SavedState {
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
                    low = e.optBoolean("low", false),
                    thin = e.optString("thin", ""),
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
            val fixes = LinkedHashMap<String, String>()
            val fo = o.optJSONObject("colorFixes") ?: JSONObject()
            val fk = fo.keys()
            while (fk.hasNext()) {
                val k = fk.next()
                fixes[k] = fo.getString(k)
            }
            val shopping = ArrayList<ShopItem>()
            val sha = o.optJSONArray("shopping") ?: JSONArray()
            for (i in 0 until sha.length()) {
                val e = sha.getJSONObject(i)
                shopping.add(ShopItem(e.getString("paintId"), e.optString("reason", ""), e.optLong("addedAt", 0L)))
            }
            val gear = ArrayList<Gear>()
            val ga = o.optJSONArray("gear") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val e = ga.getJSONObject(i)
                gear.add(Gear(e.getString("id"), e.optString("kind", "airbrush"), e.optString("name", ""), e.optDouble("nozzle", 0.0), e.optBoolean("tank", false)))
            }
            val customSets = ArrayList<PaintSet>()
            val csa = o.optJSONArray("customSets") ?: JSONArray()
            for (i in 0 until csa.length()) {
                val e = csa.getJSONObject(i)
                val ps = e.optJSONArray("paints") ?: JSONArray()
                customSets.add(
                    PaintSet(
                        brand = e.optString("brand", ""), name = e.optString("name", "Mi set"), code = e.optString("code", ""),
                        paintIds = (0 until ps.length()).map { ps.getString(it) }, custom = true, id = e.getString("id"),
                        replaces = if (e.isNull("replaces")) null else e.optString("replaces", "").ifBlank { null },
                    )
                )
            }
            return SavedState(owned, custom, barcodes, spots, locations, fixes, shopping, gear, customSets)
        }

        fun stateToJson(state: SavedState): JSONObject {
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
                        .put("low", e.low)
                        .put("thin", e.thin)
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
            val fo = JSONObject()
            state.colorFixes.forEach { (k, v) -> fo.put(k, v) }
            o.put("colorFixes", fo)
            val sha = JSONArray()
            state.shopping.forEach { sha.put(JSONObject().put("paintId", it.paintId).put("reason", it.reason).put("addedAt", it.addedAt)) }
            o.put("shopping", sha)
            val ga = JSONArray()
            state.gear.forEach { ga.put(JSONObject().put("id", it.id).put("kind", it.kind).put("name", it.name).put("nozzle", it.nozzle).put("tank", it.tank)) }
            o.put("gear", ga)
            val csa = JSONArray()
            state.customSets.forEach { s ->
                val ps = JSONArray()
                s.paintIds.forEach { ps.put(it) }
                csa.put(
                    JSONObject().put("id", s.id).put("brand", s.brand).put("name", s.name).put("code", s.code)
                        .put("replaces", s.replaces ?: JSONObject.NULL).put("paints", ps)
                )
            }
            o.put("customSets", csa)
            return o
        }
    }
}

/** Proyectos: projects.json + una carpeta por proyecto con sus imágenes. */
class ProjectStore(private val context: Context) {
    private val file: File get() = File(context.filesDir, "projects.json")

    fun dir(projectId: String): File = File(File(context.filesDir, "projects"), projectId).apply { mkdirs() }

    fun load(): List<Project> {
        val f = file
        if (!f.exists()) return emptyList()
        return try { fromJson(JSONArray(f.readText(Charsets.UTF_8))) } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun save(list: List<Project>) {
        val tmp = File(context.filesDir, "projects.json.tmp")
        tmp.writeText(toJson(list).toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(toJson(list).toString(), Charsets.UTF_8); tmp.delete() }
    }

    companion object {
        fun toJson(list: List<Project>): JSONArray {
            val arr = JSONArray()
            list.forEach { p ->
                val paints = JSONArray()
                p.paints.forEach { paints.put(JSONObject().put("paintId", it.paintId).put("use", it.use)) }
                val photos = JSONArray()
                p.photos.forEach { photos.put(JSONObject().put("file", it.file).put("takenAt", it.takenAt).put("caption", it.caption)) }
                val plan = JSONArray()
                p.plan.forEach { plan.put(JSONObject().put("id", it.id).put("zone", it.zone).put("text", it.text).put("done", it.done)) }
                val recipes = JSONArray()
                p.recipes.forEach { r ->
                    val parts = JSONArray()
                    r.parts.forEach { parts.put(JSONObject().put("paintId", it.paintId).put("parts", it.parts)) }
                    recipes.put(JSONObject().put("id", r.id).put("name", r.name).put("parts", parts).put("hex", r.hex).put("note", r.note))
                }
                arr.put(
                    JSONObject().put("id", p.id).put("name", p.name).put("createdAt", p.createdAt).put("status", p.status)
                        .put("notes", p.notes).put("reference", p.reference ?: JSONObject.NULL).put("paints", paints).put("photos", photos)
                        .put("plan", plan).put("recipes", recipes)
                )
            }
            return arr
        }

        fun fromJson(arr: JSONArray): List<Project> {
            val out = ArrayList<Project>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val paints = ArrayList<ProjectPaint>()
                val pa = o.optJSONArray("paints") ?: JSONArray()
                for (j in 0 until pa.length()) pa.getJSONObject(j).let { paints.add(ProjectPaint(it.getString("paintId"), it.optString("use", ""))) }
                val photos = ArrayList<ProgressPhoto>()
                val ph = o.optJSONArray("photos") ?: JSONArray()
                for (j in 0 until ph.length()) ph.getJSONObject(j).let { photos.add(ProgressPhoto(it.getString("file"), it.optLong("takenAt", 0L), it.optString("caption", ""))) }
                val plan = ArrayList<PlanStep>()
                val pl = o.optJSONArray("plan") ?: JSONArray()
                for (j in 0 until pl.length()) pl.getJSONObject(j).let {
                    plan.add(PlanStep(it.optString("id", "s$j"), it.optString("zone", ""), it.optString("text", ""), it.optBoolean("done", false)))
                }
                val recipes = ArrayList<SavedMix>()
                val rc = o.optJSONArray("recipes") ?: JSONArray()
                for (j in 0 until rc.length()) rc.getJSONObject(j).let { r ->
                    val parts = r.optJSONArray("parts") ?: JSONArray()
                    recipes.add(
                        SavedMix(
                            r.optString("id", "r$j"), r.optString("name", ""),
                            (0 until parts.length()).map { k -> parts.getJSONObject(k).let { MixIngredient(it.getString("paintId"), it.optInt("parts", 1)) } },
                            r.optString("hex", "#888888"), r.optString("note", ""),
                        )
                    )
                }
                out.add(
                    Project(
                        id = o.getString("id"), name = o.optString("name", "Proyecto"), createdAt = o.optLong("createdAt", 0L),
                        status = o.optString("status", "En progreso"), notes = o.optString("notes", ""),
                        reference = if (o.isNull("reference")) null else o.optString("reference", "").ifBlank { null },
                        paints = paints, photos = photos, plan = plan, recipes = recipes,
                    )
                )
            }
            return out
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
