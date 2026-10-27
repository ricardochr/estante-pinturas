package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.matchesQuery
import com.ricardochr.estantepinturas.norm

private val paintOrder = compareBy<Paint>({ it.brand }, { it.line }, { it.code.ifBlank { it.name } }, { it.name })

@Composable
fun InventoryScreen(vm: MainViewModel, onEdit: (Paint) -> Unit, onGoCatalog: () -> Unit, onGoScan: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf<String?>(null) }

    val ownedPaints = remember(vm.owned, vm.byId) {
        vm.owned.values.mapNotNull { e -> vm.byId[e.paintId]?.let { it to e } }
    }
    val brands = remember(ownedPaints) {
        ownedPaints.groupingBy { it.first.brand }.eachCount().toList().sortedBy { it.first }
    }
    val effectiveBrand = brand?.takeIf { b -> brands.any { it.first == b } }
    val shown = remember(ownedPaints, query, effectiveBrand) {
        ownedPaints
            .filter { effectiveBrand == null || it.first.brand == effectiveBrand }
            .filter { (p, e) -> matchesQuery(p.searchText + " " + norm(e.notes), query) }
            .sortedWith(compareBy(paintOrder) { it.first })
    }
    val totalBottles = ownedPaints.sumOf { it.second.qty }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchField(query, { query = it }, "Buscar: nombre, código, marca, color…")
            Text(
                "${ownedPaints.size} colores · $totalBottles botes",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrandChips(brands, effectiveBrand) { brand = it }
        Spacer(Modifier.height(4.dp))

        when {
            vm.loading -> EmptyState("Cargando…", "Abriendo el catálogo de pinturas.")
            ownedPaints.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    "Tu estante está vacío",
                    "Escanea la etiqueta o el código de barras de un bote, o búscalo en el catálogo de ${vm.catalog.size} pinturas y tócalo para agregarlo.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onGoScan) { Text("Escanear") }
                    TextButton(onClick = onGoCatalog) { Text("Abrir catálogo") }
                }
            }
            shown.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                EmptyState("No la tienes", "Ninguna pintura de tu inventario coincide con “$query”.")
                TextButton(onClick = onGoCatalog) { Text("Buscar en el catálogo") }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.first.id }) { (p, e) ->
                    PaintRow(p, extra = e.notes, onClick = { onEdit(p) }) {
                        QtyStepper(
                            e.qty,
                            onMinus = { vm.add(p.id, -1) },
                            onPlus = { vm.add(p.id, 1) },
                        )
                    }
                }
            }
        }
    }
}

/** Lista del catálogo completo; se usa en la pestaña Catálogo y para asociar un código de barras. */
@Composable
fun CatalogList(
    vm: MainViewModel,
    initialQuery: String = "",
    onRowClick: (Paint) -> Unit,
    onPlus: ((Paint) -> Unit)?,
    footer: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var brand by rememberSaveable { mutableStateOf<String?>(null) }
    val all = remember(vm.catalog, vm.custom) { vm.allPaints }
    val brands = remember(all) { all.groupingBy { it.brand }.eachCount().toList().sortedBy { it.first } }
    val shown = remember(all, query, brand) {
        all.filter { (brand == null || it.brand == brand) && matchesQuery(it.searchText, query) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchField(query, { query = it }, "Buscar en ${all.size} pinturas…")
            Text(
                "${shown.size} resultados",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrandChips(brands, brand) { brand = it }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { p ->
                val qty = vm.qtyOf(p.id)
                PaintRow(p, onClick = { onRowClick(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (qty > 0) OwnedBadge(qty)
                        if (onPlus != null) {
                            FilledTonalIconButton(onClick = { onPlus(p) }) {
                                Icon(Icons.Default.Add, contentDescription = "Agregar ${p.name}")
                            }
                        }
                    }
                }
            }
            item { footer() }
        }
    }
}

@Composable
fun OwnedBadge(qty: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
        Text(
            "Tengo $qty",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
