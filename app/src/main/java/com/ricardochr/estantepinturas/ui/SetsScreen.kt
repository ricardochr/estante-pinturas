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
    var creating by remember { mutableStateOf(false) }
    val all = vm.allSets
    val mine = remember(vm.owned, vm.byId) { ownedBrandCounts(vm) }
    val brands = remember(all, mine) { brandsOwnedFirst(all.groupingBy { it.brand }.eachCount().toList(), mine) }
    // Texto de búsqueda de cada set: marca, nombre, código y línea ("Squidmar Color", "Game Color"…)
    val searchText = remember(all, vm.byId) { all.associateWith { s -> norm(s.brand + " " + vm.lineOf(s) + " " + s.name + " " + s.code) } }
    val shown = remember(all, query, brand, searchText) {
        val words = queryWords(query)
        all.filter { s -> (brand == null || s.brand == brand) && (words.isEmpty() || matchesWords(searchText[s] ?: "", words)) }
    }
    val open = openKey?.let { k -> all.firstOrNull { it.uid == k } }

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
                    SetDetail(vm, open, onOpenPaint, say, onChanged = { openKey = it?.uid })
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SearchField(query, { query = it }, "Buscar set: intro, starter, skin, metal…")
                        androidx.compose.material3.TextButton(onClick = { creating = true }) { Text("+ Crear un set a mano") }
                    }
                    BrandChips(brands, brand, mine.keys) { brand = it }
                    if (all.isEmpty()) {
                        EmptyState("Sin sets todavía", "La lista de sets se descarga de las tiendas junto con el catálogo; llegará en la próxima actualización.")
                    } else if (shown.isEmpty()) {
                        EmptyState("Nada encontrado", "Ningún set coincide con “$query”.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(shown, key = { it.uid }) { s -> SetRow(vm, s) { openKey = s.uid } }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        SetNameDialog("Nuevo set", "", "", onDismiss = { creating = false }) { name, b ->
            creating = false
            openKey = vm.createSet(name, b).uid
        }
    }
}

/** Pide nombre y marca de un set. */
@Composable
private fun SetNameDialog(title: String, name0: String, brand0: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var b by remember { mutableStateOf(brand0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, placeholder = { Text("Fairy Flesh Set") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(value = b, onValueChange = { b = it }, label = { Text("Marca") }, placeholder = { Text("Vallejo") }, singleLine = true)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(name, b) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            SetBadge(s)
        }
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
private fun SetDetail(vm: MainViewModel, s: PaintSet, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit, onChanged: (PaintSet?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val paints = vm.paintsOf(s)
    val have = paints.filter { vm.qtyOf(it.id) > 0 }
    val missing = paints.filter { vm.qtyOf(it.id) == 0 }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOf(s.brand, vm.lineOf(s), s.code).filter { it.isNotBlank() }.distinct().joinToString(" · ") + " · ${paints.size} pinturas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!s.custom && (s.note.isNotBlank() || s.source.isNotBlank())) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SetBadge(s, always = true)
                        Text(
                            listOf(s.note, s.source.takeIf { it.isNotBlank() }?.let { "Fuente: $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · ") +
                                if (s.status != "ok") ". Revisa la caja o la tienda antes de comprarlo; puedes corregirlo con “Editar pinturas”." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = { editing = !editing }) { Text(if (editing) "Listo" else "Editar pinturas") }
                    if (editing) {
                        androidx.compose.material3.TextButton(onClick = { picking = true }) { Text("+ Agregar") }
                        androidx.compose.material3.TextButton(onClick = { renaming = true }) { Text("Renombrar") }
                    }
                }
                if (editing && s.custom) {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.deleteSet(s)
                        say(if (s.replaces != null) "Se restauró el set original de la tienda" else "Set eliminado")
                        onChanged(null)
                    }) { Text(if (s.replaces != null) "Deshacer mis cambios (volver al original)" else "Eliminar este set", color = MaterialTheme.colorScheme.error) }
                }
                Text(
                    when {
                        s.custom && s.replaces != null -> "Editaste este set; tus cambios se guardan en tu respaldo."
                        s.custom -> "Set creado por ti."
                        else -> "La lista sale de la descripción del set en la tienda; si falta o sobra alguna pintura, toca Editar pinturas."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (missing.isNotEmpty()) {
            item { SectionTitle("Te faltan (${missing.size})") }
            items(missing, key = { "m-" + it.id }) { p ->
                PaintRow(p, extra = if (vm.inShopping(p.id)) "en tu lista de compras" else null, onClick = { onOpenPaint(p) }) {
                    if (editing) IconButton(onClick = { onChanged(vm.removePaintFromSet(s, p.id)) }) { Icon(Icons.Default.Close, contentDescription = "Quitar del set") }
                    else Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (have.isNotEmpty()) {
            item { SectionTitle("Ya las tienes (${have.size})") }
            items(have, key = { "h-" + it.id }) { p ->
                PaintRow(p, extra = "tienes ${vm.qtyOf(p.id)}", onClick = { onOpenPaint(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editing) IconButton(onClick = { onChanged(vm.removePaintFromSet(s, p.id)) }) { Icon(Icons.Default.Close, contentDescription = "Quitar del set") }
                        else Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (picking) {
        PaintPickerDialog(vm, "Agregar al set", onDismiss = { picking = false }, fromCatalog = true) { p ->
            picking = false
            onChanged(vm.addPaintToSet(s, p.id))
            say("Agregada: ${p.name}")
        }
    }
    if (renaming) {
        SetNameDialog("Renombrar set", s.name, s.brand, onDismiss = { renaming = false }) { name, b ->
            renaming = false
            onChanged(vm.renameSet(s, name, b))
        }
    }
    }
}

/** Etiqueta de validación del set: verificado, incompleto o por revisar. */
@Composable
private fun SetBadge(s: PaintSet, always: Boolean = false) {
    if (s.custom) {
        if (always) Badge("Tuyo", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        return
    }
    when (s.status) {
        "parcial" -> Badge("Incompleto", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        "revisar" -> Badge("Por revisar", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        else -> if (always) Badge("Verificado", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Badge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
