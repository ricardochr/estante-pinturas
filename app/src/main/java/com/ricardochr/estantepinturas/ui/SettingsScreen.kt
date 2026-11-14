@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuración: idioma, apariencia, preferencias del inventario y del aerógrafo, y todo lo de
 * catálogo y datos (actualizar, respaldo, importar y exportar).
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit, onAction: (HubAction) -> Unit, onLanguageChanged: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(tr("Configuración")) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsCard(tr("Idioma"), Pigment.Cobalt) {
                        Choice(
                            tr("Idioma de la app"),
                            listOf("es" to tr("Español"), "en" to tr("English")),
                            Prefs.lang,
                        ) { Prefs.changeLang(it); onLanguageChanged() }
                        Hint(tr("Los nombres de las pinturas, líneas y marcas no se traducen: se muestran como los llama cada marca."))
                    }
                    SettingsCard(tr("Apariencia"), Pigment.Violet) {
                        Choice(
                            tr("Tema"),
                            listOf("light" to tr("Claro"), "dark" to tr("Oscuro"), "system" to tr("Según el teléfono")),
                            Prefs.theme,
                        ) { Prefs.changeTheme(it) }
                        Choice(
                            tr("Listas de pinturas"),
                            listOf("compact" to tr("Compactas"), "comfortable" to tr("Cómodas")),
                            if (Prefs.compactRows) "compact" else "comfortable",
                        ) { Prefs.changeCompactRows(it == "compact") }
                    }
                    SettingsCard(tr("Inventario"), Pigment.Emerald) {
                        Choice(
                            tr("Orden al abrir"),
                            PaintSort.values().map { it.name to tr(it.label) },
                            Prefs.defaultSort,
                        ) { Prefs.changeDefaultSort(it) }
                    }
                    SettingsCard("Aerógrafo", Pigment.Slate) {
                        Choice(
                            tr("Unidad de presión"),
                            listOf("psi" to "psi", "bar" to "bar"),
                            Prefs.pressureUnit,
                        ) { Prefs.changePressureUnit(it); onLanguageChanged() }
                    }
                    SettingsCard(tr("Catálogo"), Pigment.Tangerine) {
                        ActionRow(
                            Icons.Rounded.Sync, Pigment.Cobalt,
                            if (vm.catalogUpdating) tr("Actualizando…") else tr("Actualizar catálogo y sets"),
                            tr("{} pinturas · ", vm.catalog.size) +
                                if (vm.catalogDownloadedAt > 0) tr("descargado el ") + SimpleDateFormat("d MMM yyyy", Locale(Prefs.lang)).format(Date(vm.catalogDownloadedAt))
                                else tr("el que vino con la app"),
                        ) { onAction(HubAction.UpdateCatalog) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Actualizar sola cada semana"), style = MaterialTheme.typography.titleSmall)
                                Text(tr("Al abrir la app, si hay internet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = Prefs.autoUpdate, onCheckedChange = { Prefs.changeAutoUpdate(it) })
                        }
                        ActionRow(Icons.Rounded.LibraryAdd, Pigment.Violet, tr("Agregar pinturas al catálogo"), tr("Desde un archivo CSV")) { onAction(HubAction.ImportCatalog) }
                        ActionRow(Icons.Rounded.FileUpload, Pigment.Violet, tr("Exportar catálogo completo"), tr("Archivo CSV")) { onAction(HubAction.ExportCatalog) }
                        if (vm.extra.isNotEmpty()) {
                            ActionRow(Icons.Rounded.DeleteSweep, Pigment.Crimson, tr("Quitar pinturas importadas"), tr("{} pinturas que importaste", vm.extra.size)) { onAction(HubAction.ClearImported) }
                        }
                    }
                    SettingsCard(tr("Colores medidos"), Pigment.Gold) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val entries = androidx.compose.runtime.remember { com.ricardochr.estantepinturas.MeasurementLog.all(ctx) }
                        val high = entries.count { it.confidence == com.ricardochr.estantepinturas.MeasureConfidence.ALTA.name }
                        Hint(tr("{} medidos con la cámara; {} con confianza alta. Comparte los de confianza alta para corregir el catálogo de la app.", entries.size, high))
                        val saver = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
                        ) { uri ->
                            if (uri != null) try {
                                ctx.contentResolver.openOutputStream(uri)?.use { it.write(com.ricardochr.estantepinturas.MeasurementLog.exportJson(ctx).toByteArray()) }
                                android.widget.Toast.makeText(ctx, tr("Colores exportados"), android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(ctx, tr("No pude guardar el archivo: {}", e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        if (high > 0) {
                            ActionRow(Icons.Rounded.FileUpload, Pigment.Gold, tr("Exportar colores medidos"), tr("Archivo para tools/color-overrides.json")) {
                                saver.launch("color-overrides-medidos.json")
                            }
                            ActionRow(Icons.Rounded.Info, Pigment.Gold, tr("Enviar al repositorio"), tr("Abre un issue en GitHub con los colores")) {
                                val intro = tr("Colores medidos con la cámara (confianza alta). Agregar a tools/color-overrides.json:")
                                // i18n:off
                                val body = intro + "\n\n```json\n" + com.ricardochr.estantepinturas.MeasurementLog.exportJson(ctx) + "\n```"
                                // i18n:on
                                val url = "https://github.com/ricardochr/estante-pinturas/issues/new?title=" +
                                    android.net.Uri.encode(tr("Colores medidos ({})", high)) + "&body=" + android.net.Uri.encode(body)
                                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (e: Exception) { android.widget.Toast.makeText(ctx, tr("No hay un navegador para abrir GitHub."), android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                    SettingsCard(tr("Tus datos"), Pigment.Magenta) {
                        ActionRow(Icons.Rounded.Backup, Pigment.Emerald, tr("Respaldo"), tr("Copia de tu inventario y proyectos")) { onAction(HubAction.Backup) }
                        ActionRow(Icons.Rounded.FileUpload, Pigment.Teal, tr("Exportar inventario"), tr("Archivo CSV")) { onAction(HubAction.ExportInventory) }
                        ActionRow(Icons.Rounded.FileDownload, Pigment.Teal, tr("Importar inventario"), tr("Desde un archivo CSV")) { onAction(HubAction.ImportInventory) }
                    }
                    SettingsCard(tr("Gotario"), Pigment.Night) {
                        ActionRow(Icons.Rounded.Info, Pigment.Slate, tr("Acerca de"), tr("Versión y fuentes del catálogo")) { onAction(HubAction.About) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, accent: List<Color>, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(width = 16.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(diagonal(accent)))
                Text(title.uppercase(), style = MaterialTheme.typography.labelLarge)
            }
            content()
        }
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActionRow(icon: ImageVector, colors: List<Color>, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(diagonal(colors)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
