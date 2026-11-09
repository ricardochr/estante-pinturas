@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ricardochr.estantepinturas.Recommendation
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint

/** Lista de compras: lo que se te está acabando y lo que marcaste para comprar. */
@Composable
fun ShoppingScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val recs by produceState<List<Recommendation>?>(null, vm.owned, vm.catalog) { value = vm.recommendations() }
    val low = vm.lowPaints
    val toBuy = vm.shopping.mapNotNull { s -> vm.byId[s.paintId]?.let { it to s } }
        .filter { (p, _) -> low.none { it.id == p.id } }
        .sortedWith(compareBy({ it.first.brand }, { it.first.name }))

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text("Lista de compras") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
                    actions = {
                        if (low.isNotEmpty() || toBuy.isNotEmpty()) {
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, vm.shoppingText())
                                }
                                context.startActivity(Intent.createChooser(send, "Compartir lista"))
                            }) { Icon(Icons.Default.Share, contentDescription = "Compartir") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("+ Agregar una pintura del catálogo")
                }
                run {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (low.isEmpty() && toBuy.isEmpty()) {
                            item {
                                EmptyState(
                                    "Tu lista está vacía",
                                    "En la ficha de una pintura activa “Se está acabando”, o agrega las recomendaciones de abajo.",
                                )
                            }
                        }
                        if (low.isNotEmpty()) {
                            item { SectionTitle("Se están acabando (${low.size})") }
                            items(low, key = { "low-" + it.id }) { p ->
                                PaintRow(p, extra = "tienes ${vm.qtyOf(p.id)}", onClick = { onOpenPaint(p) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { vm.bought(p.id); say("${p.name}: +1 bote") }) { Text("Comprada") }
                                        IconButton(onClick = { vm.setLow(p.id, false) }) { Icon(Icons.Default.Close, contentDescription = "Quitar de la lista") }
                                    }
                                }
                            }
                        }
                        if (toBuy.isNotEmpty()) {
                            item { SectionTitle("Por comprar (${toBuy.size})") }
                            items(toBuy, key = { "buy-" + it.first.id }) { (p, s) ->
                                PaintRow(p, extra = s.reason.ifBlank { null }, onClick = { onOpenPaint(p) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { vm.bought(p.id); say("Agregada al inventario: ${p.name}") }) { Text("Comprada") }
                                        IconButton(onClick = { vm.removeFromShopping(p.id) }) { Icon(Icons.Default.Close, contentDescription = "Quitar de la lista") }
                                    }
                                }
                            }
                        }
                        recommendationItems(vm, recs, onOpenPaint, say)
                        item {
                            Text(
                                "“Comprada” suma un bote a tu inventario y la quita de la lista.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
        if (picking) {
            PaintPickerDialog(vm, "Agregar a la lista", onDismiss = { picking = false }, fromCatalog = true) { p ->
                picking = false
                vm.addToShopping(p.id, "")
                say("En tu lista: ${p.name}")
            }
        }
    }
}

/** Sección "Te recomiendo": pinturas para completar sombras, luces, básicos, lavados y metálicos. */
private fun LazyListScope.recommendationItems(
    vm: MainViewModel,
    recs: List<Recommendation>?,
    onOpenPaint: (Paint) -> Unit,
    say: (String) -> Unit,
) {
    item {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Te recomiendo", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    vm.owned.values.none { it.qty > 0 } -> "Agrega tus pinturas al inventario y te digo cuáles te faltan."
                    recs == null -> "Revisando tu inventario…"
                    recs.isEmpty() -> "Tu inventario está muy completo: tienes básicos, sombras y luces para tus colores."
                    else -> "Revisé tus colores: para cada uno busqué una pintura más oscura para sombra y una más clara para luz del mismo tono. " +
                        "También los básicos, lavados y metálicos que conviene tener. Prefiero las marcas que más usas."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (recs.isNullOrEmpty() || vm.owned.values.none { it.qty > 0 }) return
    val pending = recs.filter { r -> vm.shopping.none { it.paintId == r.paint.id } }
    if (pending.size > 1) {
        item {
            TextButton(onClick = {
                pending.forEach { vm.addToShopping(it.paint.id, "Recomendada · " + it.kind.label.lowercase()) }
                say("${pending.size} recomendaciones agregadas a tu lista")
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Agregar las ${pending.size} a la lista") }
        }
    }
    recs.groupBy { it.kind }.forEach { (kind, list) ->
        item(key = "rec-title-" + kind.name) { SectionTitle(kind.label) }
        items(list, key = { "rec-" + it.paint.id }) { r ->
            val inList = vm.shopping.any { it.paintId == r.paint.id }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPaint(r.paint) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Swatch(r.paint.hex, metallic = r.paint.type == "Metálica")
                    Column(Modifier.weight(1f)) {
                        Text(r.paint.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(r.paint.brand, r.paint.line, r.paint.code).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (inList) {
                        Text("✓ En lista", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    } else {
                        TextButton(onClick = { vm.addToShopping(r.paint.id, "Recomendada · " + r.kind.label.lowercase()) }) { Text("+ Lista") }
                    }
                }
                Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(width = 16.dp, height = 5.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .background(diagonal(Pigment.Magenta)),
        )
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
