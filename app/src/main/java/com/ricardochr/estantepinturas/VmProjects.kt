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

// Proyectos (funciones de MainViewModel; el estado vive en MainViewModel)

fun MainViewModel.projectFile(projectId: String, name: String): File = File(projectStore.dir(projectId), name)

internal fun MainViewModel.saveProjects() {
    val snapshot = projects
    viewModelScope.launch(Dispatchers.IO) { projectStore.save(snapshot) }
    scheduleAutoBackup()
}

/** Copia una imagen al proyecto, reducida a 1600 px en JPG. Devuelve el nombre del archivo. */
internal fun MainViewModel.importImage(projectId: String, uri: Uri, prefix: String): String? {
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

internal fun MainViewModel.loadBitmapScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
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

fun MainViewModel.createProject(name: String, reference: Uri?, paints: List<ProjectPaint>, notes: String, onDone: (Project) -> Unit) {
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

/** Crea un proyecto con la imagen, las pinturas sugeridas en Paleta y su plan de pintura paso a paso. */
fun MainViewModel.projectFromPalette(name: String, onDone: (Project) -> Unit) {
    val pv = palette ?: return
    val paints = pv.matches.mapIndexedNotNull { i, m ->
        val p = m.best?.paint ?: return@mapIndexedNotNull null
        val z = pv.advice.zones.getOrNull(i)
        ProjectPaint(p.id, listOfNotNull(z?.role, z?.group).joinToString(" · "))
    }
    val notes = pv.advice.plan.joinToString("\n") { "• $it" }
    val plan = planFromPalette(pv)
    createProject(name, pv.uri, paints, notes) { p ->
        setProjectPlan(p.id, plan)
        onDone(p)
    }
}

/** Los pasos de cada zona de la Paleta (con las variantes de color que estés probando), como lista para marcar. */
internal fun MainViewModel.planFromPalette(pv: PaletteView): List<PlanStep> {
    val families = pv.advice.families.ifEmpty {
        pv.matches.indices.map { PaletteFamily("color", listOf(it), it, mapOf(it to "Base")) }
    }
    val out = ArrayList<PlanStep>()
    families.forEachIndexed { zi, f ->
        val zone = tr("Zona {}", zi + 1) + " · " + f.name.replaceFirstChar { it.uppercase() }
        familySteps(f, pv.matches, pv.advice.zones, gearKit).forEach { out.add(PlanStep(UUID.randomUUID().toString(), zone, it)) }
    }
    return out
}

fun MainViewModel.setProjectPlan(id: String, plan: List<PlanStep>) = updateProject(id) { it.copy(plan = plan) }

/** Reemplaza el plan del proyecto por el de la Paleta abierta. Devuelve false si no hay paleta. */
fun MainViewModel.planFromCurrentPalette(id: String): Boolean {
    val pv = palette ?: return false
    setProjectPlan(id, planFromPalette(pv))
    return true
}

fun MainViewModel.togglePlanStep(id: String, stepId: String) =
    updateProject(id) { p -> p.copy(plan = p.plan.map { if (it.id == stepId) it.copy(done = !it.done) else it }) }

fun MainViewModel.addPlanStep(id: String, zone: String, text: String) =
    updateProject(id) { p -> p.copy(plan = p.plan + PlanStep(UUID.randomUUID().toString(), zone.trim(), text.trim())) }

fun MainViewModel.removePlanStep(id: String, stepId: String) =
    updateProject(id) { p -> p.copy(plan = p.plan.filterNot { it.id == stepId }) }

fun MainViewModel.addRecipe(id: String, name: String, parts: List<MixIngredient>, hex: String, note: String) =
    updateProject(id) { p -> p.copy(recipes = p.recipes + SavedMix(UUID.randomUUID().toString(), name.trim(), parts, hex, note.trim())) }

fun MainViewModel.removeRecipe(id: String, recipeId: String) =
    updateProject(id) { p -> p.copy(recipes = p.recipes.filterNot { it.id == recipeId }) }

/** Guarda tu nota de dilución de una pintura. */
fun MainViewModel.setThinNote(paintId: String, note: String) {
    val cur = owned[paintId] ?: return
    owned = owned + (paintId to cur.copy(thin = note.trim(), updatedAt = System.currentTimeMillis()))
    persist()
}

/** Guarda una imagen (por ejemplo la comparación con la referencia) como foto de avance. */
fun MainViewModel.addProgressBitmap(id: String, bmp: Bitmap, caption: String, onDone: (Boolean) -> Unit = {}) {
    viewModelScope.launch {
        val name = withContext(Dispatchers.IO) {
            try {
                val n = "avance-" + System.currentTimeMillis() + ".jpg"
                File(projectStore.dir(id), n).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                n
            } catch (e: Throwable) {
                null
            }
        }
        if (name != null) updateProject(id) { p -> p.copy(photos = p.photos + ProgressPhoto(name, System.currentTimeMillis(), caption)) }
        onDone(name != null)
    }
}

internal fun MainViewModel.updateProject(id: String, f: (Project) -> Project) {
    projects = projects.map { if (it.id == id) f(it) else it }
    saveProjects()
}

fun MainViewModel.renameProject(id: String, name: String) = updateProject(id) { it.copy(name = name.trim().ifBlank { it.name }) }

fun MainViewModel.setProjectNotes(id: String, notes: String) = updateProject(id) { it.copy(notes = notes) }

fun MainViewModel.setProjectStatus(id: String, status: String) = updateProject(id) { it.copy(status = status) }

fun MainViewModel.addPaintToProject(id: String, paintId: String, use: String = "") =
    updateProject(id) { p -> if (p.paints.any { it.paintId == paintId }) p else p.copy(paints = p.paints + ProjectPaint(paintId, use)) }

fun MainViewModel.setPaintUse(id: String, paintId: String, use: String) =
    updateProject(id) { p -> p.copy(paints = p.paints.map { if (it.paintId == paintId) it.copy(use = use) else it }) }

fun MainViewModel.removePaintFromProject(id: String, paintId: String) =
    updateProject(id) { p -> p.copy(paints = p.paints.filterNot { it.paintId == paintId }) }

fun MainViewModel.setProjectReference(id: String, uri: Uri) {
    viewModelScope.launch {
        val name = withContext(Dispatchers.IO) { importImage(id, uri, "ref") } ?: return@launch
        val old = projects.firstOrNull { it.id == id }?.reference
        updateProject(id) { it.copy(reference = name) }
        if (old != null && old != name) withContext(Dispatchers.IO) { projectFile(id, old).delete() }
    }
}

fun MainViewModel.addProgressPhoto(id: String, uri: Uri, caption: String, onDone: (Boolean) -> Unit = {}) {
    viewModelScope.launch {
        val name = withContext(Dispatchers.IO) { importImage(id, uri, "avance") }
        if (name != null) updateProject(id) { it.copy(photos = it.photos + ProgressPhoto(name, System.currentTimeMillis(), caption.trim())) }
        onDone(name != null)
    }
}

fun MainViewModel.setPhotoCaption(id: String, file: String, caption: String) =
    updateProject(id) { p -> p.copy(photos = p.photos.map { if (it.file == file) it.copy(caption = caption.trim()) else it }) }

fun MainViewModel.removeProgressPhoto(id: String, file: String) {
    updateProject(id) { p -> p.copy(photos = p.photos.filterNot { it.file == file }) }
    viewModelScope.launch(Dispatchers.IO) { projectFile(id, file).delete() }
}

fun MainViewModel.deleteProject(id: String) {
    projects = projects.filterNot { it.id == id }
    saveProjects()
    viewModelScope.launch(Dispatchers.IO) { projectStore.dir(id).deleteRecursively() }
}
