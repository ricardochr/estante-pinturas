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
 *  - projects.json + projects/<id>/<archivo>: proyectos con su referencia y fotos de avance.
 */
object Backup {
    private val SAFE_PATH = Regex("^projects/[A-Za-z0-9-]{1,64}/[A-Za-z0-9._-]{1,80}$")

    class Content(val state: SavedState, val projects: List<Project>)

    fun write(out: OutputStream, state: SavedState, projects: List<Project>, projectsRoot: File) {
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
        }
    }

    /**
     * Lee un respaldo. Las imágenes de proyectos se copian directo a [projectsRoot]
     * sin pisar las que ya existan. Devuelve null si el archivo no es un respaldo válido.
     */
    fun read(input: InputStream, projectsRoot: File): Content? {
        var state: SavedState? = null
        var projects: List<Project> = emptyList()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.isDirectory -> {}
                    e.name == "inventario.json" ->
                        state = Store.stateFromJson(JSONObject(zip.readBytes().toString(Charsets.UTF_8)))
                    e.name == "projects.json" ->
                        projects = ProjectStore.fromJson(JSONArray(zip.readBytes().toString(Charsets.UTF_8)))
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
        return Content(s.copy(spots = emptyList(), locations = emptyMap()), projects)
    }
}
