package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.OwnedEntry
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.matchesWords
import com.ricardochr.estantepinturas.norm
import com.ricardochr.estantepinturas.queryWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val paintOrder = compareBy<Paint>({ it.brand }, { it.line }, { it.code.ifBlank { it.name } }, { it.name })

/**
 * Filtra fuera del hilo principal y espera un momento mientras se escribe, para que el teclado
 * no se trabe con miles de pinturas. Mientras calcula, sigue mostrando el resultado anterior.
 */
@Composable
private fun <T> rememberFiltered(
    query: String,
    brand: String?,
    source: Any?,
    compute: (words: List<String>, brand: String?) -> List<T>,
): List<T>? {
    val result by produceState<List<T>?>(initialValue = null, query, brand, source) {
        if (value != null) delay(120)
        val words = queryWords(query)
        value = withContext(Dispatchers.Default) { compute(words, brand) }
    }
    return result
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InventoryScreen(
    vm: MainViewModel,
    onOpenStorage: () -> Unit,
    onEdit: (Paint) -> Unit,
    onGoCatalog: () -> Unit,
    onGoScan: () -> Unit,
    onOpenShopping: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(PaintSort.values().firstOrNull { it.name == com.ricardochr.estantepinturas.Prefs.defaultSort } ?: PaintSort.Marca) }
    var filter by rememberSaveable(stateSaver = PaintFilter.saver) { mutableStateOf(PaintFilter()) }
    var showFilters by remember { mutableStateOf(false) }

    val ownedPaints: List<Pair<Paint, OwnedEntry>> = remember(vm.owned, vm.byId) {
        vm.owned.values.mapNotNull { e -> vm.byId[e.paintId]?.let { it to e } }
    }
    val brands = remember(ownedPaints) { ownedPaints.map { it.first.brand }.distinct() }
    val counts = remember(ownedPaints) { FilterCounts(ownedPaints.map { it.first }, emptyMap()) }
    val shown = rememberFiltered(query, null, listOf(ownedPaints, sortMode, filter)) { words, _ ->
        sortPaints(
            ownedPaints.filter { (p, e) ->
                filter.matches(p, e.qty) && (words.isEmpty() || matchesWords(p.searchText + " " + norm(e.notes), words))
            },
            sortMode, { it.first }, { it.second.qty },
        )
    }
    val totalBottles = ownedPaints.sumOf { it.second.qty }

    val listState = rememberLazyListState()
    LaunchedEffect(query, sortMode, filter) {
        // Al cambiar filtros vuelve al principio de la lista, con el buscador a la vista
        if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1)
    }

    // Una sola lista que se desplaza completa: el resumen se va hacia arriba y el buscador
    // con los filtros queda fijo arriba mientras recorres tus pinturas.
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (ownedPaints.isNotEmpty()) {
            item(key = "summary") {
                SummaryCard(
                    ownedPaints.map { it.first }, totalBottles, brands.size,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                )
            }
        }
        stickyHeader(key = "filters") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(top = 4.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchField(query, { query = it }, tr("Buscar en tus pinturas"), Modifier.weight(1f))
                    // Encontrar dónde está una pintura (gaveta o estante)
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(diagonal(Pigment.Emerald))
                            .clickable(onClick = onOpenStorage),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.TravelExplore,
                            contentDescription = tr("Encontrar una pintura"),
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                if (ownedPaints.isNotEmpty()) {
                    FilterBar(filter, { filter = it }, sortMode, PaintSort.values().toList(), { sortMode = it }, onOpenSheet = { showFilters = true })
                }
            }
        }
        when {
            vm.loading || shown == null -> item { EmptyState(tr("Cargando…"), tr("Abriendo el catálogo de pinturas.")) }
            ownedPaints.isEmpty() -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        tr("Tu estante está vacío"),
                        tr("Escanea la etiqueta o el código de barras de un bote, o búscalo en el catálogo de {} pinturas y tócalo para agregarlo.", vm.catalog.size),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onGoScan) { Text(tr("Escanear")) }
                        TextButton(onClick = onGoCatalog) { Text(tr("Abrir catálogo")) }
                    }
                }
            }
            filter.activeCount > 0 && shown.isEmpty() -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(tr("Nada con estos filtros"), tr("Ninguna de tus pinturas cumple todos los filtros elegidos."))
                    TextButton(onClick = { filter = PaintFilter() }) { Text(tr("Quitar filtros")) }
                }
            }
            shown.isEmpty() -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(tr("No la tienes"), tr("Ninguna pintura de tu inventario coincide con “{}”.", query))
                    TextButton(onClick = onGoCatalog) { Text(tr("Buscar en el catálogo")) }
                }
            }
            else -> items(shown, key = { it.first.id }, contentType = { 0 }) { (p, e) ->
                val where = vm.spotOf(p.id)?.name
                PaintRow(p, extra = listOfNotNull(where?.let { "📍 $it" }, e.notes.ifBlank { null }).joinToString(" · "), onClick = { onEdit(p) }) {
                    QtyStepper(
                        e.qty,
                        onMinus = { vm.add(p.id, -1) },
                        onPlus = { vm.add(p.id, 1) },
                    )
                }
            }
        }
    }
    if (showFilters) {
        FilterSheet(
            filter, { filter = it }, sortMode, PaintSort.values().toList(), { sortMode = it },
            counts, shown?.size, inventory = true, ownedBrands = emptySet(),
        ) { showFilters = false }
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
    var filter by rememberSaveable(stateSaver = PaintFilter.saver) { mutableStateOf(PaintFilter()) }
    var sortMode by rememberSaveable { mutableStateOf<PaintSort?>(null) } // null = relevancia: tus marcas primero
    var showFilters by remember { mutableStateOf(false) }
    val mine = remember(vm.owned, vm.byId) { ownedBrandCounts(vm) }
    // Primero las marcas de las que ya tienes pinturas: al asignar una pintura escaneada casi siempre es de esas
    val all = remember(vm.catalog, vm.custom, vm.extra, mine) { vm.allPaints.sortedByDescending { mine[it.brand] ?: 0 } }
    val counts by produceState<FilterCounts?>(null, all, mine) { value = withContext(Dispatchers.Default) { FilterCounts(all, mine) } }
    val shown = rememberFiltered(query, null, listOf(all, filter, sortMode, vm.owned)) { words, _ ->
        val f = all.filter { (filter.activeCount == 0 || filter.matches(it, vm.qtyOf(it.id))) && (words.isEmpty() || matchesWords(it.searchText, words)) }
        sortMode?.let { m -> sortPaints(f, m, { it }, { vm.qtyOf(it.id) }) } ?: f
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query, filter, sortMode) { listState.scrollToItem(0) }
    val catalogSorts = listOf(PaintSort.Marca, PaintSort.Color, PaintSort.Nombre, PaintSort.Tipo)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchField(query, { query = it }, tr("Buscar en {} pinturas…", all.size))
            Text(
                if (shown == null) tr("Buscando…") else tr("{} resultados", shown.size),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilterBar(filter, { filter = it }, sortMode ?: PaintSort.Marca, catalogSorts, { sortMode = it }, onOpenSheet = { showFilters = true })
        Spacer(Modifier.height(4.dp))
        if (showFilters) {
            counts?.let { c ->
                FilterSheet(
                    filter, { filter = it }, sortMode ?: PaintSort.Marca, catalogSorts, { sortMode = it },
                    c, shown?.size, inventory = false, ownedBrands = mine.keys,
                ) { showFilters = false }
            }
        }
        if (shown == null) {
            EmptyState(tr("Cargando…"), tr("Preparando el catálogo."))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }, contentType = { 0 }) { p ->
                val qty = vm.qtyOf(p.id)
                PaintRow(p, onClick = { onRowClick(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (qty > 0) OwnedBadge(qty)
                        if (onPlus != null) {
                            FilledTonalIconButton(onClick = { onPlus(p) }) {
                                Icon(Icons.Default.Add, contentDescription = tr("Agregar {}", p.name))
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
            tr("Tengo {}", qty),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Resumen del estante: cifras y una franja con todos tus colores ordenados por tono. */
@Composable
private fun SummaryCard(paints: List<Paint>, bottles: Int, brands: Int, modifier: Modifier = Modifier) {
    val strip = remember(paints) {
        paints.filter { it.hasColor }
            .sortedWith(compareBy<Paint>({
                val c = kotlin.math.sqrt(it.lab.a * it.lab.a + it.lab.b * it.lab.b)
                if (c < 10) 1 else 0
            }, {
                val c = kotlin.math.sqrt(it.lab.a * it.lab.a + it.lab.b * it.lab.b)
                if (c < 10) -it.lab.l else {
                    val h = Math.toDegrees(kotlin.math.atan2(it.lab.b, it.lab.a))
                    if (h < 0) h + 360 else h
                }
            }))
            .map { androidx.compose.ui.graphics.Color(it.argb) }
    }
    PigmentCard(Pigment.Night, modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat(paints.size.toString(), "colores", Pigment.Cobalt[0], Modifier.weight(1f))
                Stat(bottles.toString(), "botes", Pigment.Emerald[0], Modifier.weight(1f))
                Stat(brands.toString(), "marcas", Pigment.Magenta[0], Modifier.weight(1f))
            }
            if (strip.isNotEmpty()) {
                androidx.compose.foundation.Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                ) {
                    val w = size.width / strip.size
                    strip.forEachIndexed { i, c ->
                        drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(i * w, 0f), size = androidx.compose.ui.geometry.Size(w + 1f, size.height))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(accent))
        Text(value, style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f), maxLines = 1)
    }
}
