@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.*

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
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

/** Pinturas tuyas de Vallejo Game Color (las que tienes al menos un bote). */
private fun ownedGameColor(vm: MainViewModel): BslIndex =
    BslIndex(vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] })

/**
 * Sistema BSL de Vallejo Game Color: las familias del set completo y las tríadas luz / base / sombra,
 * con lo que tienes y lo que te falta.
 */
@Composable
fun BslScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, onOpenGuide: () -> Unit) {
    val all = remember(vm.catalog, vm.extra) { BslIndex(vm.extra + vm.catalog) }
    val mine = remember(vm.owned, vm.byId) { ownedGameColor(vm) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var onlyComplete by rememberSaveable { mutableStateOf(false) }
    val bigSet = remember { BSL_FAMILIES.filter { it.inBigSet }.flatMap { it.codes }.distinct() }
    val haveBig = bigSet.count { mine[it] != null }

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
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                tr("Base, Shadow, Light: Vallejo agrupa sus Game Color en familias de tonos que combinan. Toca un color para ver su ficha."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(tr("Tienes {} de {} colores del set Color BSL System", haveBig, bigSet.size), style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(progress = { haveBig.toFloat() / bigSet.size }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(tab == 0, { tab = 0 }, label = { Text(tr("Familias")) })
                                FilterChip(tab == 1, { tab = 1 }, label = { Text(tr("Tríadas")) })
                                if (tab == 1) FilterChip(onlyComplete, { onlyComplete = !onlyComplete }, label = { Text(tr("Solo completas")) })
                            }
                        }
                    }
                    if (tab == 0) {
                        items(BSL_FAMILIES, key = { "f-" + it.name }) { f -> FamilyCard(f, all, mine, onOpenPaint) }
                    } else {
                        val triads = BSL_TRIADS.filter { t -> !onlyComplete || t.codes.all { mine[it] != null } }
                        if (triads.isEmpty()) {
                            item {
                                Text(
                                    tr("Todavía no tienes ninguna tríada completa."),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(triads, key = { "t-" + it.codes.joinToString() }) { t -> TriadCard(t, all, mine, onOpenPaint) }
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
            Text(tr("De la más clara a la más oscura"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.codes.forEach { c -> BslChip(c, null, all, mine, highlight = false, onOpenPaint, Modifier.weight(1f)) }
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
        if (role != null) {
            Text(trData(role.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
        Text(code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Sección de la ficha de una Game Color: sus tríadas BSL (con su papel resaltado) y su escala encadenada.
 * No dibuja nada si la pintura no es parte del sistema.
 */
@Composable
fun BslSection(paint: Paint, all: BslIndex, inventory: List<Paint>, onOpen: (Paint) -> Unit) {
    if (!isGameColor(paint)) return
    val code = paint.code.trim()
    val uses = remember(code) { bslTriadsWith(code) }
    val families = remember(code) { bslFamiliesWith(code) }
    if (uses.isEmpty() && families.isEmpty()) return
    val mine = remember(inventory) { BslIndex(inventory) }
    val ramp = remember(code) { bslRamp(code) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Sistema BSL"), style = MaterialTheme.typography.titleSmall)
        if (families.isNotEmpty()) {
            Text(
                tr("Familia: {}", families.joinToString(", ") { trData(it.name) }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uses.forEach { (t, role) ->
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
