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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint

/** Lista de compras: lo que se te está acabando y lo que marcaste para comprar. */
@Composable
fun ShoppingScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
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
                if (low.isEmpty() && toBuy.isEmpty()) {
                    EmptyState(
                        "Tu lista está vacía",
                        "En la ficha de una pintura activa “Se está acabando”, o agrega las que te sugiere Paleta y el círculo cromático.",
                    )
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
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

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}
