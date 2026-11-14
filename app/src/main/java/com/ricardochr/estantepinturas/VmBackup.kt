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

// Respaldo (funciones de MainViewModel; el estado vive en MainViewModel)

internal fun MainViewModel.projectsRoot(): File = File(getApplication<Application>().filesDir, "projects")

internal fun MainViewModel.currentState() = SavedState(owned, custom, barcodes, spots, locations, colorFixes, shopping, gear, customSets)

internal fun MainViewModel.writeBackup(uri: Uri): String? = try {
    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
        ?: getApplication<Application>().contentResolver.openOutputStream(uri, "w")
    if (out == null) tr("No se pudo abrir el archivo")
    else {
        out.use { Backup.write(it, currentState(), projects, projectsRoot()) }
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
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { Backup.read(it, projectsRoot()) }
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
        rebuildIndexes()
        migrateOrphans()
        persist()
        saveProjects()
        onDone(tr("Restaurado: {} pinturas nuevas, {} propias, {} proyectos", added, newCustom.size, newProjects.size))
    }
}
