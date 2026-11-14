package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel

/** Acciones que abre el Taller; cada una la resuelve la pantalla principal. */
enum class HubAction {
    Compare, Light, Wheel, Schemes, Gaps, Guide, Gear,
    Shopping, Sets, Find, AddCustom,
    UpdateCatalog, Backup, ExportInventory, ImportInventory,
    ImportCatalog, ExportCatalog, ClearImported, About,
}

private data class HubTile(
    val action: HubAction,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val colors: List<Color>,
    val badge: String? = null,
)

/**
 * Taller: todas las herramientas de la app a la vista, cada una con su color de pigmento,
 * agrupadas por para qué sirven.
 */
@Composable
fun HubScreen(vm: MainViewModel, onAction0: (HubAction) -> Unit) {
    // Cuántas veces usaste cada herramienta: las más usadas van primero en su sección
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val usagePrefs = remember { ctx.getSharedPreferences("taller", android.content.Context.MODE_PRIVATE) }
    var usage by remember { androidx.compose.runtime.mutableStateOf(usagePrefs.all.mapValues { (it.value as? Int) ?: 0 }) }
    val onAction: (HubAction) -> Unit = { a ->
        val n = (usage[a.name] ?: 0) + 1
        usagePrefs.edit().putInt(a.name, n).apply()
        usage = usage + (a.name to n)
        onAction0(a)
    }
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val owned = vm.owned.values.filter { it.qty > 0 }
    val bottles = owned.sumOf { it.qty }
    val toBuy = vm.shoppingCount
    val active = vm.projects.count { it.status != "Terminado" }
    val airbrushes = vm.gearKit.airbrushes.size
    val strip = remember(vm.owned, vm.byId) {
        owned.mapNotNull { vm.byId[it.paintId] }.filter { it.hasColor }
            .sortedBy {
                val c = kotlin.math.sqrt(it.lab.a * it.lab.a + it.lab.b * it.lab.b)
                if (c < 10) 400.0 - it.lab.l / 100 else (Math.toDegrees(kotlin.math.atan2(it.lab.b, it.lab.a)) + 360) % 360
            }
            .map { Color(it.argb) }
    }

    val paint = listOf(
        HubTile(HubAction.Compare, Icons.Rounded.Layers, tr("Superponer referencia"), tr("Compara tu figura con una foto"), Pigment.Magenta),
        HubTile(HubAction.Light, Icons.Rounded.WbSunny, tr("Luz y sombra"), tr("Ilumina tu figura en 3D o en fotos"), Pigment.Gold),
        HubTile(HubAction.Wheel, Icons.Rounded.ColorLens, tr("Círculo cromático"), tr("Armonías con tus pinturas"), Pigment.Violet),
        HubTile(HubAction.Schemes, Icons.Rounded.AutoAwesome, tr("Esquemas de color"), tr("Arma una paleta sin foto"), Pigment.Cobalt),
        HubTile(HubAction.Gaps, Icons.Rounded.GridView, tr("Huecos de tu colección"), tr("Qué tonos te faltan"), Pigment.Emerald),
        HubTile(HubAction.Guide, Icons.AutoMirrored.Rounded.MenuBook, tr("Guía de técnicas"), tr("Capas, lavados, veladuras"), Pigment.Teal),
        HubTile(HubAction.Gear, Icons.Rounded.Air, tr("Mi equipo"), if (airbrushes == 0) tr("Registra tu aerógrafo") else tr("{} aerógrafo", airbrushes) + (if (airbrushes > 1) "s" else ""), Pigment.Slate),
    )
    fun List<HubTile>.byUse() = sortedByDescending { usage[it.action.name] ?: 0 }
    // Acciones de Configuración que también se pueden buscar desde aquí
    val more = listOf(
        HubTile(HubAction.UpdateCatalog, Icons.Rounded.Sync, tr("Actualizar catálogo y sets"), tr("Buscar pinturas nuevas"), Pigment.Cobalt),
        HubTile(HubAction.Backup, Icons.Rounded.Backup, tr("Respaldo"), tr("Copia de tu inventario y proyectos"), Pigment.Emerald),
        HubTile(HubAction.ExportInventory, Icons.Rounded.FileUpload, tr("Exportar inventario"), tr("Archivo CSV"), Pigment.Teal),
        HubTile(HubAction.ImportInventory, Icons.Rounded.FileDownload, tr("Importar inventario"), tr("Desde un archivo CSV"), Pigment.Teal),
        HubTile(HubAction.ImportCatalog, Icons.Rounded.LibraryAdd, tr("Agregar pinturas al catálogo"), tr("Desde un archivo CSV"), Pigment.Violet),
        HubTile(HubAction.About, Icons.Rounded.Info, tr("Acerca de"), tr("Versión y fuentes del catálogo"), Pigment.Slate),
    )
    val shop = listOf(
        HubTile(HubAction.Shopping, Icons.Rounded.ShoppingCart, tr("Lista de compras"), if (toBuy == 0) tr("Vacía · ve recomendaciones") else tr("Por comprar"), Pigment.Tangerine, badge = toBuy.takeIf { it > 0 }?.toString()),
        HubTile(HubAction.Sets, Icons.Rounded.Widgets, tr("Sets de pinturas"), tr("{} sets · cuáles tienes", vm.allSets.size), Pigment.Gold),
        HubTile(HubAction.Find, Icons.Rounded.TravelExplore, tr("Encontrar pintura"), tr("En tu gaveta o estante"), Pigment.Emerald),
        HubTile(HubAction.AddCustom, Icons.Rounded.AddCircle, tr("Fuera del catálogo"), tr("Agrega una pintura a mano"), Pigment.Crimson),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
        item {
            PigmentCard(Pigment.Night, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandDrops(10.dp)
                        Text(tr("Tu estante"), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        HubStat(owned.size.toString(), tr("colores"), Pigment.Cobalt[0], Modifier.weight(1f))
                        HubStat(bottles.toString(), tr("botes"), Pigment.Emerald[0], Modifier.weight(1f))
                        HubStat(active.toString(), tr("proyectos"), Pigment.Magenta[0], Modifier.weight(1f))
                        HubStat(toBuy.toString(), tr("por comprar"), Pigment.Gold[0], Modifier.weight(1f))
                    }
                    if (strip.isNotEmpty()) {
                        Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                            val w = size.width / strip.size
                            strip.forEachIndexed { i, c -> drawRect(c, topLeft = Offset(i * w, 0f), size = Size(w + 1f, size.height)) }
                        }
                    }
                }
            }
        }
        item(key = "buscar") {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(tr("Buscar herramienta")) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { query = "" }) {
                        Icon(androidx.compose.material.icons.Icons.Default.Clear, contentDescription = tr("Borrar búsqueda"))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        val q = com.ricardochr.estantepinturas.norm(query.trim())
        if (q.isNotEmpty()) {
            val found = (paint + shop + more).filter { com.ricardochr.estantepinturas.norm(it.title + " " + it.subtitle).contains(q) }
            if (found.isEmpty()) {
                item(key = "nada") { Text(tr("No hay herramientas con \"{}\".", query.trim()), modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else hubSection(tr("Resultados"), Pigment.Cobalt[0], found, onAction)
        } else {
            hubSection(tr("Pintar"), Pigment.Magenta[0], paint.byUse(), onAction)
            hubSection(tr("Comprar y organizar"), Pigment.Tangerine[0], shop.byUse(), onAction)
        }
    }
}

private fun LazyListScope.sectionTitle(title: String, accent: Color) {
    item(key = "title-$title") {
        Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(18.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Herramientas principales: tarjetas grandes con degradado. */
private fun LazyListScope.hubSection(title: String, accent: Color, tiles: List<HubTile>, onAction: (HubAction) -> Unit) {
    sectionTitle(title, accent)
    tiles.chunked(2).forEachIndexed { i, row ->
        item(key = "row-$title-$i") {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t -> HubCard(t, Modifier.weight(1f)) { onAction(t.action) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HubCard(t: HubTile, modifier: Modifier, onClick: () -> Unit) {
    PigmentCard(t.colors, modifier.heightIn(min = 128.dp), onClick = onClick) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBubble(t.icon, 44.dp)
                Spacer(Modifier.weight(1f))
                t.badge?.let {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = t.colors[1], fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HubStat(value: String, label: String, accent: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
