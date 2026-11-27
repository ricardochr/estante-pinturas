@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Pinturas que tienes (al menos un bote). */
private fun ownedPaints(vm: MainViewModel): List<Paint> =
    vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }

/**
 * Sistema BSL: familias y tríadas de las marcas que tienes. Vallejo Game Color (BSL) y Warpaints Fanatic
 * (Flexible Triads) son oficiales; para las demás marcas se calculan por color.
 */
@Composable
fun BslScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, onOpenGuide: () -> Unit) {
    val owned = remember(vm.owned, vm.byId) { ownedPaints(vm) }
    val catalog = remember(vm.catalog, vm.extra) { vm.extra + vm.catalog }
    val systems by produceState(emptyList<TriadSystem>(), owned, catalog) {
        value = withContext(Dispatchers.Default) { triadSystemsFor(owned, catalog) }
    }
    var systemId by rememberSaveable { mutableStateOf<String?>(null) }
    val system = systems.firstOrNull { it.id == systemId } ?: systems.firstOrNull()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var onlyComplete by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(tr("Sistema BSL")) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                    actions = { TextButton(onClick = onOpenGuide) { Text(tr("Cómo usarlo")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (system == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val all = remember(system, catalog) { system.index(catalog + owned) }
                val mine = remember(system, owned) { system.index(owned) }
                val keys = system.allKeys
                val have = keys.count { mine[it] != null }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (systems.size > 1) {
                                Text(tr("Marca"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    systems.forEach { s ->
                                        FilterChip(
                                            s.id == system.id,
                                            { systemId = s.id; onlyComplete = false },
                                            label = { Text(s.brand + if (s.official) "" else " *") },
                                        )
                                    }
                                }
                            }
                            Text(system.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (system.official) tr("Sistema oficial de la marca") else tr("* Calculado por color, no es oficial"),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (system.official) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            )
                            Text(trData(system.about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (keys.isNotEmpty()) {
                                Text(
                                    if (system.fullSet != null) tr("Tienes {} de {} colores del {}", have, keys.size, system.fullSet)
                                    else tr("Tienes {} de {} colores", have, keys.size),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                LinearProgressIndicator(progress = { have.toFloat() / keys.size }, modifier = Modifier.fillMaxWidth())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(tab == 0, { tab = 0 }, label = { Text(tr("Familias")) })
                                FilterChip(tab == 1, { tab = 1 }, label = { Text(tr("Tríadas")) })
                                if (tab == 1) FilterChip(onlyComplete, { onlyComplete = !onlyComplete }, label = { Text(tr("Solo completas")) })
                            }
                        }
                    }
                    if (tab == 0) {
                        items(system.families, key = { system.id + "-f-" + it.name }) { f -> FamilyCard(f, all, mine, onOpenPaint) }
                    } else {
                        val triads = system.triads.filter { t -> !onlyComplete || t.codes.all { mine[it] != null } }
                        if (triads.isEmpty()) {
                            item {
                                Text(
                                    if (system.official) tr("Todavía no tienes ninguna tríada completa.")
                                    else tr("No encontré tríadas de la misma marca para tus pinturas."),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(triads, key = { system.id + "-t-" + it.codes.joinToString() }) { t -> TriadCard(t, all, mine, onOpenPaint) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyCard(f: BslFamily, all: BslIndex, mine: BslIndex, onOpenPaint: (Paint) -> Unit) {
    val have = f.codes.count { mine[it] != null }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trData(f.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (have == f.codes.size) tr("Completa") else tr("Tienes {} de {}", have, f.codes.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (have == f.codes.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!f.inBigSet) {
                Text(tr("No viene en el set de 48; está en un blíster de 4."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.note.isNotBlank()) {
                Text(trData(f.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.labels == null) {
                Text(tr("De la más clara a la más oscura"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.codes.size <= 6) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.codes.forEachIndexed { i, c -> BslChip(c, null, all, mine, highlight = false, onOpenPaint, Modifier.weight(1f), label = f.labels?.getOrNull(i)) }
                }
            } else {
                // Familias calculadas: pueden ser muchas pinturas, se desplazan de lado
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.codes.forEach { c -> BslChip(c, null, all, mine, highlight = false, onOpenPaint, Modifier.width(64.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TriadCard(t: BslTriad, all: BslIndex, mine: BslIndex, onOpenPaint: (Paint) -> Unit) {
    val missing = t.codes.filter { mine[it] == null }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BslChip(t.light, BslRole.LIGHT, all, mine, highlight = false, onOpenPaint, Modifier.weight(1f))
                BslChip(t.base, BslRole.BASE, all, mine, highlight = true, onOpenPaint, Modifier.weight(1f))
                BslChip(t.shadow, BslRole.SHADOW, all, mine, highlight = false, onOpenPaint, Modifier.weight(1f))
            }
            Text(
                if (missing.isEmpty()) tr("La tienes completa")
                else tr("Te falta: {}", missing.joinToString(", ") { all[it]?.name ?: it }),
                style = MaterialTheme.typography.bodySmall,
                color = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Un color del sistema: muestra, papel (si lo hay), nombre, código y si lo tienes. */
@Composable
fun BslChip(
    code: String,
    role: BslRole?,
    all: BslIndex,
    mine: BslIndex,
    highlight: Boolean,
    onOpenPaint: (Paint) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val p = mine[code] ?: all[code]
    val owned = mine[code] != null
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = p != null) { p?.let(onOpenPaint) }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val top = role?.label ?: label
        if (top != null) {
            Text(trData(top), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Box(contentAlignment = Alignment.BottomEnd) {
            Swatch(p?.hex ?: UNKNOWN_HEX, size = 30.dp)
            if (owned) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
        Text(
            p?.name ?: code,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (owned) FontWeight.Bold else FontWeight.Normal,
            color = if (owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val shownCode = p?.code?.ifBlank { null } ?: code.takeIf { "|" !in it }
        if (shownCode != null) Text(shownCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Sección de la ficha de una pintura con sistema oficial (Vallejo BSL, Warpaints Fanatic): sus tríadas, con su
 * papel resaltado, y su escala encadenada. No dibuja nada si la pintura no es parte de un sistema oficial.
 */
@Composable
fun BslSection(paint: Paint, catalog: List<Paint>, inventory: List<Paint>, onOpen: (Paint) -> Unit) {
    val system = remember(paint.id) { OFFICIAL_SYSTEMS.firstOrNull { it.matches(paint) } } ?: return
    val code = system.keyOf(paint)
    val uses = remember(code) { system.triadsWith(code) }
    val families = remember(code) { system.familiesWith(code) }
    if (uses.isEmpty() && families.isEmpty()) return
    val all = remember(system, catalog) { system.index(catalog) }
    val mine = remember(system, inventory) { system.index(inventory) }
    val ramp = remember(code) { system.ramp(code) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Sistema BSL"), style = MaterialTheme.typography.titleSmall)
        Text(system.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (families.isNotEmpty()) {
            Text(
                tr("Familia: {}", families.joinToString(", ") { trData(it.name) }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Familias con papel por color (True Metallic Metal): se muestra la familia completa
        families.filter { it.labels != null }.forEach { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                f.codes.forEachIndexed { i, c -> BslChip(c, null, all, mine, highlight = c == code, onOpen, Modifier.weight(1f), label = f.labels?.getOrNull(i)) }
            }
        }
        if (families.none { it.labels != null }) uses.forEach { (t, role) ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(
                        tr("Aquí es la {}", trData(role.label).lowercase()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BslChip(t.light, BslRole.LIGHT, all, mine, highlight = t.light == code, onOpen, Modifier.weight(1f))
                        BslChip(t.base, BslRole.BASE, all, mine, highlight = t.base == code, onOpen, Modifier.weight(1f))
                        BslChip(t.shadow, BslRole.SHADOW, all, mine, highlight = t.shadow == code, onOpen, Modifier.weight(1f))
                    }
                }
            }
        }
        if (ramp.size >= 4) {
            Text(tr("Escala encadenada, de oscura a clara"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ramp.forEach { c -> BslChip(c, null, all, mine, highlight = c == code, onOpen, Modifier.weight(1f)) }
            }
        }
    }
}
