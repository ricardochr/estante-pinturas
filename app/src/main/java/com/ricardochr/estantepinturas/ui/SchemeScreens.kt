@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.Harmony
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.Project
import com.ricardochr.estantepinturas.ProjectPaint
import com.ricardochr.estantepinturas.WHEEL_SECTORS
import com.ricardochr.estantepinturas.brandKey
import com.ricardochr.estantepinturas.paintsNearAngle
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.sectorOf
import com.ricardochr.estantepinturas.tonesFor
import com.ricardochr.estantepinturas.typeGroup
import com.ricardochr.estantepinturas.wheelPosOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun ToolScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                content()
            }
        }
    }
}

/** Un color del esquema: su papel, la pintura tuya que lo cubre (o una para comprar) y su sombra y luz. */
private class SchemeColor(val role: String, val share: String, val paint: Paint?, val owned: Boolean, val shadow: Paint?, val light: Paint?, val sectorName: String, val sectorHex: String)

/**
 * Esquemas de color sin foto: eliges una pintura base y una armonía, y la app arma la paleta con
 * tus pinturas (dominante, secundario y acentos), cada una con su sombra y su luz.
 */
@Composable
fun SchemeScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, onProjectCreated: (Project) -> Unit, say: (String) -> Unit) {
    var baseId by rememberSaveable { mutableStateOf<String?>(null) }
    var harmony by rememberSaveable { mutableStateOf(Harmony.COMPLEMENTARY) }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var naming by remember { mutableStateOf(false) }
    val base = baseId?.let { vm.byId[it] }
    val inventory = remember(vm.owned, vm.byId) {
        vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }
            .filter { it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    }
    val placed = remember(inventory) { inventory.map { it to wheelPosOf(it) } }
    val scheme: List<SchemeColor> = remember(base, harmony, placed, vm.shopping) {
        if (base == null) emptyList() else {
            val pos = wheelPosOf(base)
            val roles = listOf(tr("Dominante") to "≈ 60 %", tr("Secundario") to "≈ 30 %", tr("Acento") to "≈ 10 %", tr("Acento") to "detalles")
            val angles = listOf(pos.ryb) + harmony.offsets.map { (pos.ryb + it + 360f) % 360f }
            angles.mapIndexed { i, a ->
                val sec = sectorOf(a)
                val mine = if (i == 0) base else paintsNearAngle(placed, a, base.id, 1).firstOrNull()
                val paint = mine ?: vm.suggestForHue(a, 1).firstOrNull()
                val (sh, li) = if (paint != null) tonesFor(paint, inventory) else (null to null)
                val (role, share) = roles[i.coerceAtMost(roles.size - 1)]
                SchemeColor(role, share, paint, mine != null && (i > 0 || vm.qtyOf(base.id) > 0), sh, li, sec.name, sec.hex)
            }
        }
    }

    ToolScaffold(tr("Esquemas de color"), onClose) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                tr("Arma una paleta sin foto: elige el color principal de tu figura y una armonía. Te digo con qué pinturas tuyas combinarlo y cuánto usar de cada una."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (base != null) Swatch(base.hex, size = 44.dp, metallic = base.type == "Metálica")
                    Column(Modifier.weight(1f)) {
                        Text(tr("Color principal"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(base?.name ?: tr("Sin elegir"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = { picking = false }) { Text(tr("De mis pinturas")) }
                        TextButton(onClick = { picking = true }) { Text(tr("Del catálogo")) }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Harmony.values().forEach { h -> FilterChip(selected = harmony == h, onClick = { harmony = h }, label = { Text(h.label) }) }
            }
            Text(harmony.tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (scheme.isNotEmpty()) {
                // Franja con las proporciones: 60 / 30 / 10
                val weights = listOf(6f, 3f, 1f, 1f)
                Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp))) {
                    scheme.forEachIndexed { i, sc ->
                        Box(Modifier.weight(weights[i.coerceAtMost(3)]).fillMaxSize().background(Color(parseHex(sc.paint?.hex ?: sc.sectorHex))))
                    }
                }
                scheme.forEach { sc ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sc.role, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                Text(sc.sectorName + " · " + sc.share, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val p = sc.paint
                            if (p == null) {
                                Text(tr("No encontré una pintura de este tono, ni tuya ni en el catálogo."), style = MaterialTheme.typography.bodySmall)
                            } else {
                                SchemePaintRow(if (sc.owned) "Base" else tr("Base · no la tienes"), p, onOpenPaint) {
                                    if (!sc.owned) {
                                        val inList = vm.shopping.any { it.paintId == p.id }
                                        TextButton(enabled = !inList, onClick = { vm.addToShopping(p.id, tr("Para un esquema de color")); say(tr("En tu lista: {}", p.name)) }) {
                                            Text(if (inList) tr("✓ En lista") else tr("+ Lista"))
                                        }
                                    }
                                }
                                sc.shadow?.let { SchemePaintRow("Sombra", it, onOpenPaint) }
                                sc.light?.let { SchemePaintRow("Luz", it, onOpenPaint) }
                                if (sc.shadow == null || sc.light == null) {
                                    Text(
                                        when {
                                            sc.shadow == null && sc.light == null -> tr("No tienes una sombra ni una luz de este tono: oscurece la base con su complementario y aclárala con blanco hueso.")
                                            sc.shadow == null -> tr("No tienes una sombra de este tono: oscurece la base con una gota de su complementario o de azul oscuro.")
                                            else -> tr("No tienes una luz de este tono: aclara la base con blanco hueso o un amarillo claro.")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    tr("Regla 60-30-10: el dominante cubre la mayor parte, el secundario lo acompaña y el acento va solo en detalles que quieras resaltar. Un neutro (gris, hueso, café oscuro) descansa la vista entre ellos."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Guardar como proyecto")) }
            }
        }
    }

    picking?.let { catalog ->
        PaintPickerDialog(vm, tr("Color principal"), onDismiss = { picking = null }, fromCatalog = catalog) { p ->
            picking = null
            if (p.hasColor) baseId = p.id else say(tr("Esa pintura no tiene un color registrado"))
        }
    }
    if (naming) {
        ProjectNameDialog(onDismiss = { naming = false }, onSave = { name ->
            naming = false
            val paints = scheme.flatMap { sc ->
                listOfNotNull(
                    sc.paint?.let { ProjectPaint(it.id, sc.role + tr(" · base")) },
                    sc.shadow?.let { ProjectPaint(it.id, sc.role + tr(" · sombra")) },
                    sc.light?.let { ProjectPaint(it.id, sc.role + tr(" · luz")) },
                )
            }
            vm.createProject(name, null, paints, harmony.label + ": " + scheme.mapNotNull { it.paint?.name }.joinToString(" + "), onProjectCreated)
        })
    }
}

@Composable
private fun SchemePaintRow(label: String, p: Paint, onOpen: (Paint) -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpen(p) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Swatch(p.hex, size = 30.dp, metallic = p.type == "Metálica")
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

private val VALUE_ROWS = listOf("Claro", "Medio claro", "Medio oscuro", "Oscuro")

/** Casilla del mapa: columna = sector del círculo (12 = neutros), fila = claridad (0 claro … 3 oscuro). */
private fun cellOf(p: Paint): Pair<Int, Int> {
    val w = wheelPosOf(p)
    val col = if (w.neutral) WHEEL_SECTORS.size else WHEEL_SECTORS.indexOf(sectorOf(w.ryb)).coerceAtLeast(0)
    val l = p.lab.l
    val row = when {
        l >= 72 -> 0
        l >= 52 -> 1
        l >= 32 -> 2
        else -> 3
    }
    return col to row
}

/**
 * Huecos de tu colección: mapa de tonos (columnas) por claridad (filas) con cuántas pinturas opacas
 * tienes en cada casilla. Las casillas vacías muestran qué comprar para cubrirlas.
 */
@Composable
fun GapsScreen(vm: MainViewModel, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    val owned = remember(vm.owned, vm.byId) {
        vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] }
            .filter { it.hasColor && typeGroup(it.type) == "opaca" && it.type != "Imprimación" }
    }
    val cells = remember(owned) { owned.groupBy { cellOf(it) } }
    // Catálogo por casilla (se calcula una vez, fuera del hilo principal)
    val catalogCells by produceState<Map<Pair<Int, Int>, List<Paint>>?>(null, vm.catalog, vm.extra) {
        val all = vm.extra + vm.catalog
        value = withContext(Dispatchers.Default) {
            all.filter { it.hasColor && it.type == "Opaca" && it.quality != "n" }.groupBy { cellOf(it) }
        }
    }
    var selected by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
    val cols = WHEEL_SECTORS.size + 1
    val total = cols * VALUE_ROWS.size
    val covered = cells.keys.size
    val myBrands = remember(owned) { owned.groupingBy { brandKey(it.brand) }.eachCount() }

    ToolScaffold(tr("Huecos de tu colección"), onClose) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("Cada casilla es un tono (columna) con una claridad (fila). El número es cuántas pinturas opacas tienes ahí; las casillas vacías son tus huecos. Toca una para ver qué tienes o qué comprar."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("Cubres {} de {} casillas", covered, total), style = MaterialTheme.typography.titleMedium)
            // Mapa
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.height(18.dp))
                    VALUE_ROWS.forEach { r ->
                        Box(Modifier.height(40.dp).width(64.dp), contentAlignment = Alignment.CenterStart) {
                            Text(r, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                for (c in 0 until cols) {
                    val sectorColor = if (c < WHEEL_SECTORS.size) Color(parseHex(WHEEL_SECTORS[c].hex)) else Color(0xFF8A8A8A)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(18.dp).width(40.dp).clip(RoundedCornerShape(5.dp)).background(sectorColor))
                        for (r in VALUE_ROWS.indices) {
                            val key = c to r
                            val mine = cells[key].orEmpty()
                            val isSel = selected == key
                            val bg = if (mine.isNotEmpty()) Color(parseHex(mine[mine.size / 2].hex)) else MaterialTheme.colorScheme.surfaceContainerHighest
                            Box(
                                Modifier
                                    .width(40.dp).height(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .border(
                                        if (isSel) 3.dp else 1.dp,
                                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { selected = if (isSel) null else key },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (mine.isEmpty()) {
                                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    val dark = mine[mine.size / 2].lab.l < 55
                                    Text(mine.size.toString(), color = if (dark) Color.White else Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            val sel = selected
            if (sel == null) {
                // Los huecos más útiles de cubrir: tonos sin ninguna pintura en dos o más claridades
                val worst = (0 until WHEEL_SECTORS.size).map { c -> c to VALUE_ROWS.indices.count { r -> cells[c to r].isNullOrEmpty() } }
                    .filter { it.second >= 2 }.sortedByDescending { it.second }.take(4)
                if (worst.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            tr("Donde más te falta: ") + worst.joinToString(", ") { (c, n) -> com.ricardochr.estantepinturas.trData(WHEEL_SECTORS[c].name).lowercase() + " ($n)" } + ".",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }
            } else {
                val (c, r) = sel
                val name = (if (c < WHEEL_SECTORS.size) com.ricardochr.estantepinturas.trData(WHEEL_SECTORS[c].name) else com.ricardochr.estantepinturas.trData("Neutros")) +
                    " · " + com.ricardochr.estantepinturas.trData(VALUE_ROWS[r]).lowercase()
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                val mine = cells[sel].orEmpty()
                if (mine.isNotEmpty()) {
                    Text(tr("Tienes {}:", mine.size), style = MaterialTheme.typography.labelLarge)
                    mine.sortedBy { it.name }.forEach { p -> SchemePaintRow(p.brand, p, onOpenPaint) }
                } else {
                    Text(tr("No tienes ninguna pintura opaca aquí. Opciones del catálogo:"), style = MaterialTheme.typography.bodySmall)
                }
                val options = catalogCells?.get(sel).orEmpty()
                    .filter { vm.qtyOf(it.id) == 0 }
                    .sortedWith(compareBy({ -(myBrands[brandKey(it.brand)] ?: 0) }, { -wheelPosOf(it).radius }))
                    .distinctBy { brandKey(it.brand) }
                    .take(if (mine.isEmpty()) 4 else 2)
                if (mine.isNotEmpty() && options.isNotEmpty()) Text(tr("Otras del catálogo:"), style = MaterialTheme.typography.labelLarge)
                options.forEach { p ->
                    val inList = vm.shopping.any { it.paintId == p.id }
                    SchemePaintRow(p.brand, p, onOpenPaint) {
                        TextButton(enabled = !inList, onClick = { vm.addToShopping(p.id, tr("Para cubrir un hueco de color")); say(tr("En tu lista: {}", p.name)) }) {
                            Text(if (inList) tr("✓ En lista") else tr("+ Lista"))
                        }
                    }
                }
                if (catalogCells == null) Text(tr("Buscando en el catálogo…"), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { selected = null }) { Text(tr("Ver resumen")) }
            }
        }
    }
}
