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

// Respaldo (funciones de MainViewModel; el estado vive en MainViewModel)

internal fun MainViewModel.projectsRoot(): File = File(getApplication<Application>().filesDir, "projects")

internal fun MainViewModel.currentState() = SavedState(owned, custom, barcodes, spots, locations, colorFixes, shopping, gear, customSets)

internal fun MainViewModel.writeBackup(uri: Uri): String? = try {
    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
        ?: getApplication<Application>().contentResolver.openOutputStream(uri, "w")
    if (out == null) tr("No se pudo abrir el archivo")
    else {
        val app = getApplication<Application>()
        val prefsJson = Backup.prefsToJson(Backup.PREF_FILES.associateWith { app.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).all })
        out.use { Backup.write(it, currentState(), projects, projectsRoot(), app.filesDir, prefsJson) }
        null
    }
} catch (e: Exception) {
    e.message ?: e.javaClass.simpleName
}

internal fun MainViewModel.scheduleAutoBackup() {
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
            backupError = tr("El respaldo automático falló: {}. Vuelve a elegir el archivo.", err)
        }
    }
}

/** Activa el respaldo automático en el archivo elegido y escribe el primer respaldo. */
fun MainViewModel.enableAutoBackup(uri: Uri, onDone: (String) -> Unit) {
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
            onDone(tr("Respaldo automático activado"))
        } else {
            onDone(tr("No se pudo escribir el respaldo: {}", err))
        }
    }
}

fun MainViewModel.disableAutoBackup() {
    val uri = autoBackupUri
    autoBackupUri = null
    backupError = null
    prefs.edit().remove("backup_uri").apply()
    if (uri != null) try {
        getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (e: Exception) {
    }
}

fun MainViewModel.backupNow(uri: Uri, onDone: (String) -> Unit) {
    viewModelScope.launch {
        val err = withContext(Dispatchers.IO) { writeBackup(uri) }
        onDone(if (err == null) tr("Respaldo guardado: {} pinturas, {} proyectos", owned.size, projects.size) else tr("No se pudo guardar: {}", err))
    }
}

/**
 * Restaura un respaldo uniéndolo con lo que ya hay: para cada pintura gana el registro más reciente,
 * y se agregan las pinturas propias, códigos, colores, compras y proyectos que no estén.
 */
fun MainViewModel.restoreBackup(uri: Uri, onDone: (String) -> Unit) {
    viewModelScope.launch {
        val content = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    Backup.read(it, projectsRoot(), getApplication<Application>().filesDir)
                }
            } catch (e: Exception) {
                null
            }
        }
        if (content == null) {
            onDone(tr("Ese archivo no es un respaldo de Gotario"))
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
        gear = gear + b.gear.filter { g -> gear.none { it.id == g.id } }
        customSets = customSets + b.customSets.filter { c -> customSets.none { it.id == c.id } }
        val knownProjects = projects.map { it.id }.toHashSet()
        val newProjects = content.projects.filter { it.id !in knownProjects }
        projects = projects + newProjects
        content.prefs?.let { restorePrefs(it) }
        rebuildIndexes()
        migrateOrphans()
        persist()
        saveProjects()
        onDone(
            tr("Restaurado: {} pinturas nuevas, {} propias, {} proyectos", added, newCustom.size, newProjects.size) +
                if (content.files > 0) tr(" · {} archivos (modelos 3D y fotos de Luz y sombra)", content.files) else "",
        )
    }
}

/**
 * Restaura preferencias del respaldo uniéndolas con las actuales: lo que ya tienes en este teléfono se
 * respeta; se agrega lo que falta. En los colores medidos gana la medición más reciente.
 */
internal fun MainViewModel.restorePrefs(o: org.json.JSONObject) {
    val app = getApplication<Application>()
    for (file in Backup.PREF_FILES) {
        val f = o.optJSONObject(file) ?: continue
        val sp = app.getSharedPreferences(file, android.content.Context.MODE_PRIVATE)
        val ed = sp.edit()
        for (k in f.keys()) {
            val e = f.getJSONObject(k)
            val exists = sp.contains(k)
            if (file == "mediciones" && exists) {
                // Gana la medición más reciente
                val cur = try { org.json.JSONObject(sp.getString(k, "{}")!!).optLong("at") } catch (_: Exception) { 0L }
                val new = try { org.json.JSONObject(e.optString("v")).optLong("at") } catch (_: Exception) { 0L }
                if (new <= cur) continue
            } else if (exists) continue
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.optString("v"))
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "set" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
            }
        }
        ed.apply()
    }
    Prefs.reload(app)
}
