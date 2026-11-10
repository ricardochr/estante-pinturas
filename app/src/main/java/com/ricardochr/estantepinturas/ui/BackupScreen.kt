@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Respaldo del inventario, lista de compras y proyectos en un archivo (local o en Google Drive). */
@Composable
fun BackupScreen(vm: MainViewModel, onClose: () -> Unit, say: (String) -> Unit) {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val autoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.enableAutoBackup(uri) { say(it) }
    }
    val nowLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupNow(uri) { say(it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreBackup(uri) { say(it) }
    }
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(tr("Respaldo")) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        tr("Guarda en un solo archivo tu inventario, pinturas propias, colores corregidos, códigos de barras, lista de compras y proyectos (con su imagen de referencia y fotos de avance). Las fotos de gavetas no se incluyen."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Respaldo automático"), style = MaterialTheme.typography.titleMedium)
                            if (vm.autoBackupUri == null) {
                                Text(
                                    tr("Elige dónde guardar el archivo. Si eliges Google Drive en el selector, tu respaldo queda en la nube y se actualiza solo cada vez que cambias algo."),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { autoLauncher.launch("gotario-respaldo.zip") }, modifier = Modifier.fillMaxWidth()) {
                                    Text(tr("Activar respaldo automático"))
                                }
                            } else {
                                Text(
                                    tr("Activado. Último respaldo: ") +
                                        if (vm.lastBackupAt > 0) SimpleDateFormat("d MMM yyyy, HH:mm", Locale(com.ricardochr.estantepinturas.Prefs.lang)).format(Date(vm.lastBackupAt)) else "pendiente",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                vm.backupError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                OutlinedButton(onClick = { autoLauncher.launch("gotario-respaldo.zip") }, modifier = Modifier.fillMaxWidth()) {
                                    Text(tr("Cambiar archivo"))
                                }
                                TextButton(onClick = { vm.disableAutoBackup(); say(tr("Respaldo automático desactivado")) }) {
                                    Text(tr("Desactivar"))
                                }
                            }
                        }
                    }

                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("A mano"), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { nowLauncher.launch("gotario-respaldo-$stamp.zip") }, modifier = Modifier.fillMaxWidth()) {
                                Text(tr("Guardar un respaldo ahora"))
                            }
                            OutlinedButton(
                                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("Restaurar desde un respaldo")) }
                            Text(
                                tr("Restaurar no borra nada: une el respaldo con lo que ya tienes. Si una pintura está en ambos, se queda el dato más reciente."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text(
                        tr("Además, Android respalda los datos de Gotario en tu cuenta de Google (si tienes activada la copia de seguridad del teléfono) y los recupera al reinstalar."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}
