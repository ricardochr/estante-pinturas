package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel

/** Acciones que abre el Taller; cada una la resuelve la pantalla principal. */
enum class HubAction {
    Compare, Wheel, Guide, Gear,
    Shopping, Sets, Find, AddCustom,
    UpdateCatalog, Backup, ExportInventory, ImportInventory,
    ImportCatalog, ExportCatalog, ClearImported, About,
}

private data class HubTile(val action: HubAction, val icon: ImageVector, val title: String, val subtitle: String, val accent: Boolean = false)

/**
 * Taller: todas las herramientas de la app a la vista, agrupadas por para qué sirven,
 * en lugar de esconderlas en el menú de tres puntos.
 */
@Composable
fun HubScreen(vm: MainViewModel, onAction: (HubAction) -> Unit) {
    val owned = vm.owned.values.filter { it.qty > 0 }
    val bottles = owned.sumOf { it.qty }
    val low = vm.lowPaints
    val toBuy = low.size + vm.shopping.count { s -> low.none { it.id == s.paintId } }
    val active = vm.projects.count { it.status != "Terminado" }
    val airbrushes = vm.gearKit.airbrushes.size

    val paint = listOf(
        HubTile(HubAction.Compare, CompareIcon, "Superponer referencia", "Compara tu figura con una foto usando la cámara", accent = true),
        HubTile(HubAction.Wheel, Icons.Default.Star, "Círculo cromático", "Armonías y complementarios con tus pinturas"),
        HubTile(HubAction.Guide, GuideIcon, "Guía de técnicas", "Dilución, capas, lavados, veladuras…"),
        HubTile(HubAction.Gear, Icons.Default.Build, "Mi equipo", if (airbrushes == 0) "Registra tu aerógrafo y compresor" else "$airbrushes aerógrafo" + (if (airbrushes > 1) "s" else "")),
    )
    val shop = listOf(
        HubTile(HubAction.Shopping, Icons.Default.ShoppingCart, "Lista de compras", if (toBuy == 0) "Vacía · ve recomendaciones" else "$toBuy por comprar", accent = toBuy > 0),
        HubTile(HubAction.Sets, Icons.Default.List, "Sets de pinturas", "${vm.allSets.size} sets · cuáles ya tienes"),
        HubTile(HubAction.Find, Icons.Default.Place, "Encontrar una pintura", "Dónde está en tu gaveta o estante"),
        HubTile(HubAction.AddCustom, Icons.Default.Add, "Pintura fuera del catálogo", "Agrégala a mano"),
    )
    val data = buildList {
        add(HubTile(HubAction.UpdateCatalog, Icons.Default.Refresh, if (vm.catalogUpdating) "Actualizando…" else "Actualizar catálogo y sets", "${vm.catalog.size} pinturas en el catálogo"))
        add(HubTile(HubAction.Backup, Icons.Default.Lock, "Respaldo", "Copia de tu inventario y proyectos"))
        add(HubTile(HubAction.ExportInventory, Icons.Default.Share, "Exportar inventario", "Archivo CSV"))
        add(HubTile(HubAction.ImportInventory, Icons.Default.Add, "Importar inventario", "Desde un CSV"))
        add(HubTile(HubAction.ImportCatalog, Icons.Default.Add, "Agregar al catálogo", "Pinturas desde un CSV"))
        add(HubTile(HubAction.ExportCatalog, Icons.Default.Share, "Exportar catálogo", "Todo el catálogo en CSV"))
        if (vm.extra.isNotEmpty()) add(HubTile(HubAction.ClearImported, Icons.Default.Delete, "Quitar importadas", "${vm.extra.size} pinturas que importaste"))
        add(HubTile(HubAction.About, Icons.Default.Info, "Acerca de", "Versión y fuentes del catálogo"))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            ) {
                Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HubStat(owned.size.toString(), "pinturas", Modifier.weight(1f))
                    HubStat(bottles.toString(), "botes", Modifier.weight(1f))
                    HubStat(active.toString(), "proyectos activos", Modifier.weight(1f))
                    HubStat(toBuy.toString(), "por comprar", Modifier.weight(1f))
                }
            }
        }
        hubSection("Pintar", paint, onAction)
        hubSection("Comprar y organizar", shop, onAction)
        hubSection("Catálogo y datos", data, onAction)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hubSection(title: String, tiles: List<HubTile>, onAction: (HubAction) -> Unit) {
    item(key = "title-$title") {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
        )
    }
    tiles.chunked(2).forEachIndexed { i, row ->
        item(key = "row-$title-$i") {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> HubCard(t, Modifier.weight(1f)) { onAction(t.action) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HubCard(t: HubTile, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (t.accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (t.accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = modifier.heightIn(min = 112.dp)) {
        Column(
            Modifier.clickable(onClick = onClick).padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), shape = CircleShape, modifier = Modifier.size(38.dp)) {}
                Icon(t.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HubStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(2.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
