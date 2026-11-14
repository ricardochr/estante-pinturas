package com.ricardochr.estantepinturas

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Respaldo en un solo archivo .zip:
 *  - inventario.json: inventario, pinturas propias, códigos de barras, colores corregidos y lista de compras
 *    (sin gavetas ni ubicaciones: las pinturas cambian de lugar).
 *  - projects.json + projects/<id>/<archivo>: proyectos con su referencia y fotos de avance (con sus
 *    planes, recetas y notas de dilución dentro de los JSON).
 *  - preferencias.json: configuración, colores medidos con la cámara, ajustes de Luz y sombra.
 *  - archivos/modelos/*.gmesh y archivos/luz/<proyecto>/<lado>.*: modelos 3D guardados y fotos con su relieve.
 */
object Backup {
    /** Preferencias que viajan en el respaldo (las de este teléfono, como el archivo de respaldo, no). */
    val PREF_FILES = listOf("settings", "mediciones", "luz", "taller", "ayuda")

    /** Convierte preferencias a JSON conservando el tipo de cada valor. */
    fun prefsToJson(all: Map<String, Map<String, *>>): JSONObject {
        val o = JSONObject()
        all.forEach { (file, values) ->
            val f = JSONObject()
            values.forEach { (k, v) ->
                val t = when (v) {
                    is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"
                    is Set<*> -> "set"; else -> null
                } ?: return@forEach
                f.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
            }
            o.put(file, f)
        }
        return o
    }

    private val SAFE_PATH = Regex("^projects/[A-Za-z0-9-]{1,64}/[A-Za-z0-9._-]{1,80}$")
    private val SAFE_FILE = Regex("^archivos/(modelos/[A-Za-z0-9._ -]{1,80}\\.gmesh|luz/[A-Za-z0-9_-]{1,64}/[A-Z]{3,12}\\.(jpg|depth|mask))$")

    class Content(val state: SavedState, val projects: List<Project>, val prefs: JSONObject? = null, val files: Int = 0)

    /** Archivos de la app que van en el respaldo, relativos a filesDir. */
    private fun extraFiles(filesRoot: File): List<String> {
        val out = ArrayList<String>()
        File(filesRoot, "modelos").listFiles()?.filter { it.isFile && it.name.endsWith(".gmesh") }?.forEach { out.add("modelos/" + it.name) }
        File(filesRoot, "luz").listFiles()?.filter { it.isDirectory }?.forEach { d ->
            d.listFiles()?.filter { it.isFile }?.forEach { out.add("luz/" + d.name + "/" + it.name) }
        }
        return out.filter { SAFE_FILE.matches("archivos/$it") }
    }

    fun write(
        out: OutputStream,
        state: SavedState,
        projects: List<Project>,
        projectsRoot: File,
        filesRoot: File? = null,
        prefs: JSONObject? = null,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            val clean = state.copy(spots = emptyList(), locations = emptyMap())
            val inv = Store.stateToJson(clean).put("app", "gotario").put("savedAt", System.currentTimeMillis())
            zip.putNextEntry(ZipEntry("inventario.json"))
            zip.write(inv.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("projects.json"))
            zip.write(ProjectStore.toJson(projects).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in projects) {
                val files = listOfNotNull(p.reference) + p.photos.map { it.file }
                for (name in files.distinct()) {
                    val f = File(File(projectsRoot, p.id), name)
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("projects/${p.id}/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (prefs != null) {
                zip.putNextEntry(ZipEntry("preferencias.json"))
                zip.write(prefs.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (filesRoot != null) for (rel in extraFiles(filesRoot)) {
                zip.putNextEntry(ZipEntry("archivos/$rel"))
                File(filesRoot, rel).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Lee un respaldo. Las imágenes de proyectos se copian directo a [projectsRoot]
     * sin pisar las que ya existan. Devuelve null si el archivo no es un respaldo válido.
     */
    fun read(input: InputStream, projectsRoot: File, filesRoot: File? = null): Content? {
        var state: SavedState? = null
        var projects: List<Project> = emptyList()
        var prefs: JSONObject? = null
        var files = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.isDirectory -> {}
                    e.name == "inventario.json" ->
                        state = Store.stateFromJson(JSONObject(zip.readBytes().toString(Charsets.UTF_8)))
                    e.name == "projects.json" ->
                        projects = ProjectStore.fromJson(JSONArray(zip.readBytes().toString(Charsets.UTF_8)))
                    e.name == "preferencias.json" ->
                        prefs = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    filesRoot != null && SAFE_FILE.matches(e.name) -> {
                        val target = File(filesRoot, e.name.removePrefix("archivos/"))
                        if (!target.exists()) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            files++
                        }
                    }
                    SAFE_PATH.matches(e.name) -> {
                        val target = File(projectsRoot, e.name.removePrefix("projects/"))
                        if (!target.exists()) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val s = state ?: return null
        return Content(s.copy(spots = emptyList(), locations = emptyMap()), projects, prefs, files)
    }
}
