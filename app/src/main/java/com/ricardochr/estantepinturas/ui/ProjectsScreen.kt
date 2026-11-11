@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.ProgressPhoto
import com.ricardochr.estantepinturas.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE: SimpleDateFormat get() = SimpleDateFormat("d MMM yyyy", Locale(com.ricardochr.estantepinturas.Prefs.lang))

/** Carga una imagen guardada en el teléfono, reducida para mostrarla. */
@Composable
fun rememberFileBitmap(file: File?, maxSide: Int): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, b)
                var sample = 1
                while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (e: Throwable) {
                null
            }
        }
    }
    return state.value
}

/** Pestaña Proyectos: tus figuras en curso y terminadas. */
@Composable
fun ProjectsScreen(vm: MainViewModel, onOpenProject: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val list = vm.projects
    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = { creating = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(tr("Nuevo proyecto")) }
        if (list.isEmpty()) {
            EmptyState(
                tr("Aún no tienes proyectos"),
                tr("Un proyecto guarda la imagen de referencia, las pinturas que usas en cada parte, tus notas y mezclas, y fotos de cómo va la figura. También puedes crearlo desde Paleta con “Guardar como proyecto”."),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                val active = list.filter { it.status != "Terminado" }
                val done = list.filter { it.status == "Terminado" }
                if (active.isNotEmpty()) {
                    item { SectionTitle(tr("En progreso ({})", active.size)) }
                    items(active, key = { it.id }) { p -> ProjectCard(vm, p) { onOpenProject(p.id) } }
                }
                if (done.isNotEmpty()) {
                    item { SectionTitle(tr("Terminados ({})", done.size)) }
                    items(done, key = { it.id }) { p -> ProjectCard(vm, p) { onOpenProject(p.id) } }
                }
            }
        }
    }
    if (creating) {
        ProjectNameDialog(onDismiss = { creating = false }, onSave = { name ->
            creating = false
            vm.createProject(name, null, emptyList(), "") { onOpenProject(it.id) }
        })
    }
}

@Composable
private fun ProjectCard(vm: MainViewModel, p: Project, onClick: () -> Unit) {
    val cover = p.photos.lastOrNull()?.file ?: p.reference
    val img = rememberFileBitmap(cover?.let { vm.projectFile(p.id, it) }, 300)
    val missing = p.paints.count { vm.qtyOf(it.paintId) == 0 }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(tr("Sin\nimagen"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tr("{} pinturas · {} fotos de avance", p.paints.size, p.photos.size) + if (missing > 0) tr(" · te faltan {}", missing) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    p.paints.take(12).forEach { pp -> vm.byId[pp.paintId]?.let { Swatch(it.hex, size = 14.dp, metallic = it.type == "Metálica") } }
                }
            }
        }
    }
}

/** Detalle de un proyecto: referencia, pinturas con su uso, notas y fotos de avance. */
@Composable
fun ProjectDetail(vm: MainViewModel, projectId: String, onClose: () -> Unit, onOpenPaint: (Paint) -> Unit, say: (String) -> Unit) {
    val p = vm.projects.firstOrNull { it.id == projectId }
    if (p == null) {
        androidx.compose.runtime.LaunchedEffect(projectId) { onClose() }
        return
    }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf<Boolean?>(null) } // true = catálogo, false = inventario
    var editingUse by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<ProgressPhoto?>(null) }
    var notes by remember(p.id) { mutableStateOf(p.notes) }

    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setProjectReference(p.id, uri)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addProgressPhoto(p.id, uri, "") { ok -> say(if (ok) tr("Foto de avance guardada") else tr("No pude abrir esa imagen")) }
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) vm.addProgressPhoto(p.id, u, "") { saved -> say(if (saved) tr("Foto de avance guardada") else tr("No pude guardar la foto")) }
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "avance-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        captureUri = uri
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else say(tr("Sin permiso de cámara: usa “De la galería”"))
    }
    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    val ref = rememberFileBitmap(p.reference?.let { vm.projectFile(p.id, it) }, 1200)
    var comparing by remember { mutableStateOf(false) }
    var addingStep by remember { mutableStateOf(false) }
    var addingRecipe by remember { mutableStateOf(false) }
    val paints = p.paints.mapNotNull { pp -> vm.byId[pp.paintId]?.let { it to pp } }
    val missing = paints.filter { vm.qtyOf(it.first.id) == 0 }

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = tr("Más opciones")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Renombrar")) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(tr("Cambiar imagen de referencia")) }, onClick = {
                                menu = false
                                refPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text(tr("Compartir proyecto")) }, onClick = {
                                menu = false
                                if (!shareProject(context, vm, p)) say(tr("No pude preparar la imagen para compartir"))
                            })
                            DropdownMenuItem(text = { Text(tr("Eliminar proyecto")) }, onClick = { menu = false; confirmDelete = true })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (ref != null) {
                                Image(
                                    ref,
                                    contentDescription = tr("Imagen de referencia"),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                )
                                Button(onClick = { comparing = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(tr("Comparar con mi figura (cámara)"))
                                }
                            } else if (p.reference == null) {
                                OutlinedButton(
                                    onClick = { refPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(tr("Agregar imagen de referencia")) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = p.status != "Terminado", onClick = { vm.setProjectStatus(p.id, "En progreso") }, label = { Text("En progreso") })
                                FilterChip(selected = p.status == "Terminado", onClick = { vm.setProjectStatus(p.id, "Terminado") }, label = { Text("Terminado") })
                                Text(
                                    tr("Desde ") + DATE.format(Date(p.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // ---- Pinturas
                    item { SectionTitle(tr("Pinturas ({})", paints.size)) }
                    if (paints.isEmpty()) {
                        item {
                            Text(
                                tr("Agrega las pinturas que usarás y para qué parte (piel, sombra del pelo, metal…)."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(paints, key = { "paint-" + it.first.id }) { (paint, pp) ->
                        val have = vm.qtyOf(paint.id) > 0
                        PaintRow(
                            paint,
                            extra = listOfNotNull(pp.use.ifBlank { null }, if (have) null else tr("no la tienes")).joinToString(" · ").ifBlank { null },
                            onClick = { onOpenPaint(paint) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { editingUse = paint.id }) { Text(tr("Uso")) }
                                IconButton(onClick = { vm.removePaintFromProject(p.id, paint.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = tr("Quitar del proyecto"))
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickFrom = false }) { Text(tr("+ De mi inventario")) }
                                OutlinedButton(onClick = { pickFrom = true }) { Text(tr("+ Del catálogo")) }
                            }
                            if (missing.isNotEmpty()) {
                                val pending = missing.filter { m -> vm.shopping.none { it.paintId == m.first.id } }
                                if (pending.isEmpty()) {
                                    Text(tr("Las {} que te faltan ya están en tu lista de compras.", missing.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    TextButton(onClick = {
                                        pending.forEach { vm.addToShopping(it.first.id, tr("Para {}", p.name)) }
                                        say(tr("{} agregadas a tu lista de compras", pending.size))
                                    }) { Text(tr("Agregar las {} que te faltan a la lista de compras", pending.size)) }
                                }
                            }
                        }
                    }

                    // ---- Plan de pintura
                    val doneSteps = p.plan.count { it.done }
                    item { SectionTitle(if (p.plan.isEmpty()) tr("Plan de pintura") else tr("Plan de pintura ({} de {})", doneSteps, p.plan.size)) }
                    if (p.plan.isEmpty()) {
                        item {
                            Text(
                                tr("Lleva la cuenta de lo que ya pintaste: toma los pasos de la Paleta (base, sombra, luz y veladura de cada zona) o escribe los tuyos."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        item {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { doneSteps.toFloat() / p.plan.size },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        p.plan.groupBy { it.zone }.forEach { (zone, steps) ->
                            item(key = "zone-$zone") {
                                Text(
                                    zone.ifBlank { tr("General") } + "  ·  ${steps.count { it.done }}/${steps.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                                )
                            }
                            items(steps, key = { "step-" + it.id }) { st ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.togglePlanStep(p.id, st.id) }
                                        .padding(start = 6.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Checkbox(checked = st.done, onCheckedChange = { vm.togglePlanStep(p.id, st.id) })
                                    Text(
                                        st.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (st.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (st.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                                    )
                                    IconButton(onClick = { vm.removePlanStep(p.id, st.id) }) {
                                        Icon(Icons.Default.Close, contentDescription = tr("Quitar paso"), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { addingStep = true }) { Text(tr("+ Paso")) }
                            if (vm.palette != null) {
                                OutlinedButton(onClick = {
                                    if (vm.planFromCurrentPalette(p.id)) say(tr("Plan tomado de la Paleta"))
                                }) { Text(if (p.plan.isEmpty()) tr("Tomar de la Paleta") else tr("Reemplazar con la Paleta")) }
                            }
                        }
                    }

                    // ---- Recetas de mezcla
                    item { SectionTitle(tr("Recetas de mezcla ({})", p.recipes.size)) }
                    items(p.recipes, key = { "recipe-" + it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Swatch(r.hex, size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { tr("Mezcla") }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    r.parts.joinToString(" + ") { ing -> "${ing.parts} × ${vm.byId[ing.paintId]?.name ?: "?"}" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.removeRecipe(p.id, r.id) }) { Icon(Icons.Default.Close, contentDescription = tr("Borrar receta")) }
                        }
                    }
                    item {
                        OutlinedButton(onClick = { addingRecipe = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Text(tr("+ Nueva receta")) }
                    }

                    // ---- Notas
                    item { SectionTitle(tr("Notas")) }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                placeholder = { Text(tr("Ej.: piel = 2 Flesh + 1 Rojo, glaseado de púrpura en sombras…")) },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (notes != p.notes) {
                                Button(onClick = { vm.setProjectNotes(p.id, notes); say(tr("Notas guardadas")) }) { Text(tr("Guardar notas")) }
                            }
                        }
                    }

                    // ---- Avance
                    item { SectionTitle(tr("Avance ({} fotos)", p.photos.size)) }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { takePhoto() }) { Text(tr("Tomar foto")) }
                            OutlinedButton(onClick = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Text(tr("De la galería"))
                            }
                        }
                    }
                    val rows = p.photos.reversed().chunked(3)
                    items(rows.size) { i ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rows[i].forEach { ph -> PhotoThumb(vm, p.id, ph, Modifier.weight(1f)) { viewing = ph } }
                            repeat(3 - rows[i].size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        pickFrom?.let { catalog ->
            PaintPickerDialog(vm, if (catalog) tr("Agregar del catálogo") else tr("Agregar de tu inventario"), onDismiss = { pickFrom = null }, fromCatalog = catalog) { paint ->
                pickFrom = null
                vm.addPaintToProject(p.id, paint.id)
                editingUse = paint.id
            }
        }

        viewing?.let { ph ->
            PhotoViewer(vm, p.id, ph, onClose = { viewing = null }, onCaption = { vm.setPhotoCaption(p.id, ph.file, it); viewing = null }, onDelete = {
                vm.removeProgressPhoto(p.id, ph.file)
                viewing = null
            })
        }
    }

    editingUse?.let { pid ->
        val current = p.paints.firstOrNull { it.paintId == pid }?.use ?: ""
        var text by remember(pid) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editingUse = null },
            title = { Text(tr("¿Para qué la usas?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.byId[pid]?.name ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(tr("Base de la piel, luz del pelo…")) }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.setPaintUse(p.id, pid, text.trim()); editingUse = null }) { Text(tr("Guardar")) } },
            dismissButton = { TextButton(onClick = { editingUse = null }) { Text(tr("Cancelar")) } },
        )
    }

    if (renaming) {
        ProjectNameDialog(onDismiss = { renaming = false }, onSave = { vm.renameProject(p.id, it); renaming = false }, initial = p.name, title = tr("Renombrar"))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("¿Eliminar “{}”?", p.name)) },
            text = { Text(tr("Se borran sus notas, la imagen de referencia y las {} fotos de avance. Tus pinturas no cambian.", p.photos.size)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteProject(p.id); onClose() }) { Text(tr("Eliminar"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancelar")) } },
        )
    }

    if (addingStep) {
        val zones = p.plan.map { it.zone }.filter { it.isNotBlank() }.distinct()
        var zone by remember { mutableStateOf(zones.lastOrNull() ?: "") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingStep = false },
            title = { Text(tr("Nuevo paso")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = zone, onValueChange = { zone = it }, label = { Text(tr("Zona (piel, capa, base…)")) }, singleLine = true)
                    if (zones.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            zones.forEach { z -> FilterChip(selected = zone == z, onClick = { zone = z }, label = { Text(z) }) }
                        }
                    }
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(tr("Qué hay que hacer")) }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { vm.addPlanStep(p.id, zone, text); addingStep = false }) { Text(tr("Agregar")) }
            },
            dismissButton = { TextButton(onClick = { addingStep = false }) { Text(tr("Cancelar")) } },
        )
    }

    if (addingRecipe) {
        RecipeDialog(vm, onDismiss = { addingRecipe = false }) { name, parts, hex, note ->
            vm.addRecipe(p.id, name, parts, hex, note)
            addingRecipe = false
            say(tr("Receta guardada"))
        }
    }

    if (comparing) {
        val file = p.reference?.let { vm.projectFile(p.id, it) }
        CompareScreen(
            initialUri = file?.let { android.net.Uri.fromFile(it) },
            onClose = { comparing = false },
            saveTargets = listOf(p.id to p.name),
            onSave = { pid, bmp ->
                vm.addProgressBitmap(pid, bmp, com.ricardochr.estantepinturas.tr("Comparación con la referencia")) { ok ->
                    say(if (ok) tr("Comparación guardada en las fotos de avance") else tr("No pude guardar la comparación"))
                }
            },
        )
    }
}

@Composable
private fun PhotoThumb(vm: MainViewModel, projectId: String, ph: ProgressPhoto, modifier: Modifier, onClick: () -> Unit) {
    val img = rememberFileBitmap(vm.projectFile(projectId, ph.file), 400)
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            if (img != null) Image(img, contentDescription = ph.caption.ifBlank { tr("Foto de avance") }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(DATE.format(Date(ph.takenAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ph.caption.isNotBlank()) {
            Text(ph.caption, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PhotoViewer(
    vm: MainViewModel,
    projectId: String,
    ph: ProgressPhoto,
    onClose: () -> Unit,
    onCaption: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val img = rememberFileBitmap(vm.projectFile(projectId, ph.file), 2000)
    var caption by remember(ph.file) { mutableStateOf(ph.caption) }
    var confirm by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar"), tint = Color.White) }
                    Text(DATE.format(Date(ph.takenAt)), color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirm = true }) { Text(tr("Eliminar"), color = MaterialTheme.colorScheme.error) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, contentDescription = tr("Foto de avance"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = caption,
                            onValueChange = { caption = it },
                            placeholder = { Text(tr("Nota: capa base, luces terminadas…")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { onCaption(caption) }, enabled = caption != ph.caption) { Text(tr("Guardar")) }
                    }
                }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(tr("¿Eliminar esta foto?")) },
                confirmButton = {
                    TextButton(onClick = { confirm = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Eliminar")) }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancelar")) } },
            )
        }
    }
}

/** Nueva receta de mezcla: eliges pinturas y partes de cada una, y ves el color que resulta. */
@Composable
private fun RecipeDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onSave: (name: String, parts: List<com.ricardochr.estantepinturas.MixIngredient>, hex: String, note: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf<List<Pair<Paint, Int>>>(emptyList()) }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val colored = parts.filter { it.first.hasColor }
    val resultArgb = remember(parts) {
        if (colored.isEmpty()) null else com.ricardochr.estantepinturas.mixMany(colored.map { it.first }, colored.map { it.second })
    }
    val resultHex = resultArgb?.let { String.format("#%06X", it and 0xFFFFFF) } ?: "#888888"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Nueva receta de mezcla")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(tr("Nombre (sombra de la piel…)")) }, singleLine = true)
                parts.forEachIndexed { i, (paint, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatch(paint.hex, size = 28.dp, metallic = paint.type == "Metálica")
                        Text(paint.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                        QtyStepper(
                            n,
                            onMinus = { parts = if (n <= 1) parts.filterIndexed { k, _ -> k != i } else parts.mapIndexed { k, x -> if (k == i) x.first to n - 1 else x } },
                            onPlus = { parts = parts.mapIndexed { k, x -> if (k == i) x.first to (n + 1).coerceAtMost(12) else x } },
                        )
                    }
                }
                if (parts.size < 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = false }) { Text(tr("+ De mi inventario")) }
                        TextButton(onClick = { picking = true }) { Text(tr("Del catálogo")) }
                    }
                }
                if (resultArgb != null && parts.size >= 2) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Swatch(resultHex, size = 48.dp)
                        Text(
                            tr("Color aproximado de la mezcla. Los números son partes (gotas) de cada pintura."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (parts.size >= 2) {
                    com.ricardochr.estantepinturas.mixWarning(parts.map { it.first })?.let { w ->
                        Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(tr("Nota (dónde la usaste, dilución…)")) }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = parts.size >= 2,
                onClick = { onSave(name, parts.map { com.ricardochr.estantepinturas.MixIngredient(it.first.id, it.second) }, resultHex, note) },
            ) { Text(tr("Guardar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
    picking?.let { catalog ->
        PaintPickerDialog(vm, tr("Pintura para la mezcla"), onDismiss = { picking = null }, fromCatalog = catalog) { paint ->
            picking = null
            if (parts.none { it.first.id == paint.id }) parts = parts + (paint to 1)
        }
    }
}
