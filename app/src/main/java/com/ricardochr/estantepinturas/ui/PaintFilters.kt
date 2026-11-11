@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.Finish
import com.ricardochr.estantepinturas.PAINT_TYPES
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.finishOf
import com.ricardochr.estantepinturas.norm
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.trData

/** Cómo ordenar una lista de pinturas. Cantidad solo aplica a tu inventario. */
enum class PaintSort(val label: String) { Marca("Marca"), Color("Color"), Nombre("Nombre"), Tipo("Tipo"), Cantidad("Cantidad") }

/** Familia de color para filtrar ("los azules", "los cafés"…), con un color de muestra. */
enum class ColorFamily(val label: String, val hex: String) {
    Rojo("Rojos", "#C8252B"), Naranja("Naranjas", "#EC6E1F"), Amarillo("Amarillos", "#F2D21B"),
    Verde("Verdes", "#2E9A44"), Turquesa("Turquesas", "#1E9AA0"), Azul("Azules", "#2B4BA8"),
    Morado("Morados", "#6E3F8C"), Rosa("Rosas", "#D2559A"), Cafe("Cafés", "#7A4E2D"),
    Piel("Pieles", "#E3A97F"), Blanco("Blancos", "#F4F2EC"), Gris("Grises", "#8A8D93"), Negro("Negros", "#1E1E22"),
}

/** Familia de color de una pintura, por su tono, saturación y claridad (en CIELAB). */
fun colorFamilyOf(p: Paint): ColorFamily? {
    if (!p.hasColor) return null
    val lab = p.lab
    val c = kotlin.math.sqrt(lab.a * lab.a + lab.b * lab.b)
    if (c < 9) return when {
        lab.l > 86 -> ColorFamily.Blanco
        lab.l < 20 -> ColorFamily.Negro
        else -> ColorFamily.Gris
    }
    var h = Math.toDegrees(kotlin.math.atan2(lab.b, lab.a))
    if (h < 0) h += 360
    // Cafés y pieles: tonos cálidos (rojo-naranja-amarillo) poco saturados u oscuros
    if (h in 30.0..95.0) {
        if (lab.l < 50 && c < 45) return ColorFamily.Cafe
        if (lab.l >= 50 && c in 12.0..42.0 && h < 80) return ColorFamily.Piel
    }
    return when {
        h < 15 || h >= 345 -> if (lab.l > 60) ColorFamily.Rosa else ColorFamily.Rojo
        h < 45 -> ColorFamily.Rojo
        h < 70 -> ColorFamily.Naranja
        h < 105 -> ColorFamily.Amarillo
        h < 170 -> ColorFamily.Verde
        h < 225 -> ColorFamily.Turquesa
        h < 290 -> ColorFamily.Azul
        h < 320 -> ColorFamily.Morado
        else -> ColorFamily.Rosa
    }
}

/** Qué pinturas mostrar. Dentro de cada grupo es "cualquiera de" (OR); entre grupos, "y" (AND). */
data class PaintFilter(
    val brands: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val finishes: Set<String> = emptySet(),
    val colors: Set<String> = emptySet(),
    /** Inventario: solo las que tienes repetidas. Catálogo: "own" = las que tienes, "missing" = las que no. */
    val onlyMultiple: Boolean = false,
    val ownership: String? = null,
) {
    val activeCount: Int
        get() = brands.size + types.size + finishes.size + colors.size + (if (onlyMultiple) 1 else 0) + (if (ownership != null) 1 else 0)

    fun matches(p: Paint, qty: Int): Boolean =
        (brands.isEmpty() || p.brand in brands) &&
            (types.isEmpty() || p.type in types) &&
            (finishes.isEmpty() || finishOf(p).finish.name in finishes) &&
            (colors.isEmpty() || colorFamilyOf(p)?.name in colors) &&
            (!onlyMultiple || qty > 1) &&
            (ownership == null || (ownership == "own") == (qty > 0))

    companion object {
        /** Para que los filtros sobrevivan a girar la pantalla o cambiar de pestaña. */
        val saver: Saver<PaintFilter, Any> = Saver(
            save = { arrayListOf(ArrayList(it.brands), ArrayList(it.types), ArrayList(it.finishes), ArrayList(it.colors), it.onlyMultiple, it.ownership ?: "") },
            restore = { v ->
                @Suppress("UNCHECKED_CAST")
                val l = v as List<Any>
                PaintFilter(
                    (l[0] as List<String>).toSet(), (l[1] as List<String>).toSet(), (l[2] as List<String>).toSet(),
                    (l[3] as List<String>).toSet(), l[4] as Boolean, (l[5] as String).ifBlank { null },
                )
            },
        )
    }
}

private val paintOrder = compareBy<Paint>({ it.brand }, { it.line }, { it.code.ifBlank { it.name } }, { it.name })

/** Orden por color: cromáticos por tono (rojo → … → morado) y luminosidad; al final neutros de claro a oscuro. */
private fun colorKey(p: Paint): Triple<Int, Double, Double> {
    if (!p.hasColor) return Triple(2, 0.0, 0.0)
    val c = kotlin.math.sqrt(p.lab.a * p.lab.a + p.lab.b * p.lab.b)
    if (c < 10) return Triple(1, -p.lab.l, 0.0)
    var h = Math.toDegrees(kotlin.math.atan2(p.lab.b, p.lab.a))
    if (h < 15) h += 360
    return Triple(0, h, -p.lab.l)
}

/** Ordena cualquier lista de pinturas; [qtyOf] da los botes para el orden por cantidad. */
fun <T> sortPaints(list: List<T>, mode: PaintSort, paintOf: (T) -> Paint, qtyOf: (T) -> Int): List<T> = when (mode) {
    PaintSort.Marca -> list.sortedWith(compareBy(paintOrder) { paintOf(it) })
    PaintSort.Nombre -> list.sortedWith(compareBy({ norm(paintOf(it).name) }, { paintOf(it).brand }))
    PaintSort.Cantidad -> list.sortedWith(compareByDescending<T> { qtyOf(it) }.thenBy { paintOf(it).brand }.thenBy { paintOf(it).name })
    PaintSort.Tipo -> list.sortedWith(compareBy({ PAINT_TYPES.indexOf(paintOf(it).type).let { i -> if (i < 0) 99 else i } }, { paintOf(it).brand }, { paintOf(it).name }))
    PaintSort.Color -> list.sortedWith(compareBy({ colorKey(paintOf(it)).first }, { colorKey(paintOf(it)).second }, { colorKey(paintOf(it)).third }))
}

/** Cuántas pinturas hay de cada opción, para mostrar los números junto a cada filtro. */
class FilterCounts(source: List<Paint>, ownedBrands: Map<String, Int>) {
    val brands: List<Pair<String, Int>> = brandsOwnedFirst(source.groupingBy { it.brand }.eachCount().toList(), ownedBrands)
    val types: List<Pair<String, Int>> = source.groupingBy { it.type }.eachCount().toList()
        .sortedBy { (t, _) -> PAINT_TYPES.indexOf(t).let { if (it < 0) 99 else it } }
    val finishes: List<Pair<Finish, Int>> = source.groupingBy { finishOf(it).finish }.eachCount().toList()
        .sortedBy { it.first.ordinal }
    val colors: Map<ColorFamily, Int> = source.mapNotNull { colorFamilyOf(it) }.groupingBy { it }.eachCount()
}

/**
 * Barra de búsqueda: orden (menú), botón Filtros con cuántos hay activos, y cada filtro activo como
 * chip que se quita tocando la ✕.
 */
@Composable
fun FilterBar(
    filter: PaintFilter,
    onFilter: (PaintFilter) -> Unit,
    sort: PaintSort,
    sorts: List<PaintSort>,
    onSort: (PaintSort) -> Unit,
    onOpenSheet: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            FilterChip(
                selected = filter.activeCount > 0,
                onClick = onOpenSheet,
                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(if (filter.activeCount == 0) tr("Filtros") else tr("Filtros ({})", filter.activeCount)) },
            )
        }
        item {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { sortMenu = true },
                    label = { Text(tr("Orden: {}", tr(sort.label)) + " ▾") },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    sorts.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(tr(m.label), fontWeight = if (m == sort) FontWeight.Bold else null) },
                            leadingIcon = { Text(if (m == sort) "✓" else " ") },
                            onClick = { onSort(m); sortMenu = false },
                        )
                    }
                }
            }
        }
        // Filtros activos: se quitan uno por uno
        if (filter.onlyMultiple) item { ActiveChip(tr("Repetidas")) { onFilter(filter.copy(onlyMultiple = false)) } }
        filter.ownership?.let { o ->
            item { ActiveChip(if (o == "own") tr("Las que tengo") else tr("Las que no tengo")) { onFilter(filter.copy(ownership = null)) } }
        }
        items(filter.types.toList(), key = { "t-$it" }) { t -> ActiveChip(trData(t)) { onFilter(filter.copy(types = filter.types - t)) } }
        items(filter.finishes.toList(), key = { "f-$it" }) { f ->
            ActiveChip(tr(Finish.valueOf(f).label)) { onFilter(filter.copy(finishes = filter.finishes - f)) }
        }
        items(filter.colors.toList(), key = { "c-$it" }) { c ->
            val fam = ColorFamily.valueOf(c)
            ActiveChip(tr(fam.label), dot = Color(parseHex(fam.hex))) { onFilter(filter.copy(colors = filter.colors - c)) }
        }
        items(filter.brands.toList(), key = { "b-$it" }) { b -> ActiveChip(b) { onFilter(filter.copy(brands = filter.brands - b)) } }
        if (filter.activeCount > 1) {
            item { TextButton(onClick = { onFilter(PaintFilter()) }) { Text(tr("Quitar todos")) } }
        }
    }
}

@Composable
private fun ActiveChip(text: String, dot: Color? = null, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text) },
        leadingIcon = dot?.let { c -> { Box(Modifier.size(12.dp).clip(CircleShape).background(c)) } },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = tr("Quitar filtro"), modifier = Modifier.size(16.dp)) },
    )
}

/**
 * Hoja de filtros: todas las opciones a la vista, con cuántas pinturas hay en cada una. Se pueden
 * marcar varias de cada grupo. [inventory] = true muestra "Repetidas"; false, "Las que tengo / no tengo".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: PaintFilter,
    onFilter: (PaintFilter) -> Unit,
    sort: PaintSort,
    sorts: List<PaintSort>,
    onSort: (PaintSort) -> Unit,
    counts: FilterCounts,
    resultCount: Int?,
    inventory: Boolean,
    ownedBrands: Set<String>,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var brandQuery by remember { mutableStateOf("") }
    var allBrands by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Filtros y orden"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (filter.activeCount > 0) TextButton(onClick = { onFilter(PaintFilter()) }) { Text(tr("Quitar todos")) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section(tr("Ordenar por")) {
                    sorts.forEach { m -> FilterChip(selected = m == sort, onClick = { onSort(m) }, label = { Text(tr(m.label)) }) }
                }
                if (inventory) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Solo las que tengo repetidas"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = filter.onlyMultiple, onCheckedChange = { onFilter(filter.copy(onlyMultiple = it)) })
                    }
                } else {
                    Section(tr("En tu inventario")) {
                        listOf(null to tr("Todas"), "own" to tr("Las que tengo"), "missing" to tr("Las que no tengo")).forEach { (v, l) ->
                            FilterChip(selected = filter.ownership == v, onClick = { onFilter(filter.copy(ownership = v)) }, label = { Text(l) })
                        }
                    }
                }
                Section(tr("Tipo")) {
                    counts.types.forEach { (t, n) ->
                        FilterChip(
                            selected = t in filter.types,
                            onClick = { onFilter(filter.copy(types = filter.types.toggle(t))) },
                            label = { Text("${trData(t)}  $n") },
                        )
                    }
                }
                Section(tr("Acabado")) {
                    counts.finishes.forEach { (f, n) ->
                        FilterChip(
                            selected = f.name in filter.finishes,
                            onClick = { onFilter(filter.copy(finishes = filter.finishes.toggle(f.name))) },
                            label = { Text("${tr(f.label)}  $n") },
                        )
                    }
                }
                Section(tr("Color")) {
                    ColorFamily.values().forEach { fam ->
                        val n = counts.colors[fam] ?: 0
                        if (n > 0) {
                            FilterChip(
                                selected = fam.name in filter.colors,
                                onClick = { onFilter(filter.copy(colors = filter.colors.toggle(fam.name))) },
                                leadingIcon = {
                                    Box(
                                        Modifier.size(16.dp).clip(CircleShape).background(Color(parseHex(fam.hex)))
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                },
                                label = { Text("${tr(fam.label)}  $n") },
                            )
                        }
                    }
                }
                // Marcas: primero las tuyas; si son muchas, se buscan o se despliegan
                val brandList = counts.brands.filter { brandQuery.isBlank() || norm(it.first).contains(norm(brandQuery)) }
                val visible = if (allBrands || brandQuery.isNotBlank() || brandList.size <= 14) brandList
                else (brandList.take(12) + brandList.filter { it.first in filter.brands }).distinct()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Marca"), style = MaterialTheme.typography.titleSmall)
                    if (counts.brands.size > 14) {
                        SearchField(brandQuery, { brandQuery = it }, tr("Buscar marca…"))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        visible.forEach { (b, n) ->
                            FilterChip(
                                selected = b in filter.brands,
                                onClick = { onFilter(filter.copy(brands = filter.brands.toggle(b))) },
                                label = { Text((if (b in ownedBrands) "★ " else "") + "$b  $n") },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                    if (visible.size < brandList.size) {
                        TextButton(onClick = { allBrands = true }) { Text(tr("Ver las {} marcas", brandList.size)) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text(if (resultCount == null) tr("Listo") else tr("Ver {} pinturas", resultCount)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

private fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v
