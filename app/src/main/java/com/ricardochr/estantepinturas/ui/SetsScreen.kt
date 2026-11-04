@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.PaintSet
import com.ricardochr.estantepinturas.matchesWords
import com.ricardochr.estantepinturas.norm
import com.ricardochr.estantepinturas.queryWords

/** Sets de pinturas de las tiendas: cuáles de sus pinturas ya tienes y cuáles te faltan. */
@Composable
fun SetsScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf<String?>(null) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val all = vm.sets
    val brands = remember(all) { all.groupingBy { it.brand }.eachCount().toList().sortedBy { it.first } }
    // Texto de búsqueda de cada set: marca, nombre, código y línea ("Squidmar Color", "Game Color"…)
    val searchText = remember(all, vm.byId) { all.associateWith { s -> norm(s.brand + " " + vm.lineOf(s) + " " + s.name + " " + s.code) } }
    val shown = remember(all, query, brand, searchText) {
        val words = queryWords(query)
        all.filter { s -> (brand == null || s.brand == brand) && (words.isEmpty() || matchesWords(searchText[s] ?: "", words)) }
    }
    val open = openKey?.let { k -> all.firstOrNull { it.brand + "|" + it.name == k } }

    Box(Modifier.fillMaxSize()) {
        BackHandler { if (open != null) openKey = null else onClose() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(open?.name ?: "Sets de pinturas", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (open != null) IconButton(onClick = { openKey = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
                        else IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (open != null) {
                    SetDetail(vm, open, onOpenPaint, say)
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SearchField(query, { query = it }, "Buscar set: intro, starter, skin, metal…")
                    }
                    BrandChips(brands, brand) { brand = it }
                    if (all.isEmpty()) {
                        EmptyState("Sin sets todavía", "La lista de sets se descarga de las tiendas junto con el catálogo; llegará en la próxima actualización.")
                    } else if (shown.isEmpty()) {
                        EmptyState("Nada encontrado", "Ningún set coincide con “$query”.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(shown, key = { it.brand + "|" + it.name }) { s -> SetRow(vm, s) { openKey = s.brand + "|" + s.name } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(vm: MainViewModel, s: PaintSet, onClick: () -> Unit) {
    val paints = vm.paintsOf(s)
    val have = paints.count { vm.qtyOf(it.id) > 0 }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(s.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(s.brand, vm.lineOf(s)).filter { it.isNotBlank() }.distinct().joinToString(" · ") + " · " + when {
                paints.isEmpty() -> "sin pinturas reconocidas"
                have == paints.size -> "las tienes todas (${paints.size})"
                have == 0 -> "no tienes ninguna de sus ${paints.size}"
                else -> "tienes $have de ${paints.size}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (have == paints.size && paints.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            paints.take(16).forEach { Swatch(it.hex, size = 14.dp, metallic = it.type == "Metálica") }
        }
        if (paints.isNotEmpty()) {
            LinearProgressIndicator(progress = { have.toFloat() / paints.size }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SetDetail(vm: MainViewModel, s: PaintSet, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    val paints = vm.paintsOf(s)
    val have = paints.filter { vm.qtyOf(it.id) > 0 }
    val missing = paints.filter { vm.qtyOf(it.id) == 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOf(s.brand, vm.lineOf(s), s.code).filter { it.isNotBlank() }.distinct().joinToString(" · ") + " · ${paints.size} pinturas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        paints.isEmpty() -> "No reconocí pinturas de este set en el catálogo."
                        missing.isEmpty() -> "Ya tienes todas las pinturas de este set: no necesitas comprarlo."
                        have.isEmpty() -> "No tienes ninguna de sus pinturas."
                        else -> "Tienes ${have.size} de ${paints.size}. Te faltan ${missing.size}: compara si te conviene el set completo o solo esas."
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (paints.isNotEmpty()) LinearProgressIndicator(progress = { have.size.toFloat() / paints.size }, modifier = Modifier.fillMaxWidth())
                if (missing.isNotEmpty()) {
                    Button(onClick = {
                        val n = vm.addMissingToShopping(s)
                        say(if (n == 0) "Ya estaban en tu lista de compras" else "$n agregadas a tu lista de compras")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Agregar las ${missing.size} que faltan a la lista de compras") }
                }
                if (paints.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        val n = vm.boughtSet(s)
                        say("Set agregado: +1 bote de $n pinturas")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Ya compré el set: agregarlo a mi inventario") }
                }
                Text(
                    "La lista de pinturas sale de la descripción del set en la tienda; puede que no incluya primers, barnices o pinceles.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (missing.isNotEmpty()) {
            item { SectionTitle("Te faltan (${missing.size})") }
            items(missing, key = { "m-" + it.id }) { p ->
                PaintRow(p, extra = if (vm.inShopping(p.id)) "en tu lista de compras" else null, onClick = { onOpenPaint(p) }) {
                    Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (have.isNotEmpty()) {
            item { SectionTitle("Ya las tienes (${have.size})") }
            items(have, key = { "h-" + it.id }) { p ->
                PaintRow(p, extra = "tienes ${vm.qtyOf(p.id)}", onClick = { onOpenPaint(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
