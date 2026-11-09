@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

private val DATE = SimpleDateFormat("d MMM yyyy", Locale("es"))

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
        ) { Text("Nuevo proyecto") }
        if (list.isEmpty()) {
            EmptyState(
                "Aún no tienes proyectos",
                "Un proyecto guarda la imagen de referencia, las pinturas que usas en cada parte, tus notas y mezclas, y fotos de cómo va la figura. " +
                    "También puedes crearlo desde Paleta con “Guardar como proyecto”.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                val active = list.filter { it.status != "Terminado" }
                val done = list.filter { it.status == "Terminado" }
                if (active.isNotEmpty()) {
                    item { SectionTitle("En progreso (${active.size})") }
                    items(active, key = { it.id }) { p -> ProjectCard(vm, p) { onOpenProject(p.id) } }
                }
                if (done.isNotEmpty()) {
                    item { SectionTitle("Terminados (${done.size})") }
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
                else Text("Sin\nimagen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.paints.size} pinturas · ${p.photos.size} fotos de avance" + if (missing > 0) " · te faltan $missing" else "",
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
        if (uri != null) vm.addProgressPhoto(p.id, uri, "") { ok -> say(if (ok) "Foto de avance guardada" else "No pude abrir esa imagen") }
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) vm.addProgressPhoto(p.id, u, "") { saved -> say(if (saved) "Foto de avance guardada" else "No pude guardar la foto") }
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "avance-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        captureUri = uri
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else say("Sin permiso de cámara: usa “De la galería”")
    }
    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    val ref = rememberFileBitmap(p.reference?.let { vm.projectFile(p.id, it) }, 1200)
    var comparing by remember { mutableStateOf(false) }
    val paints = p.paints.mapNotNull { pp -> vm.byId[pp.paintId]?.let { it to pp } }
    val missing = paints.filter { vm.qtyOf(it.first.id) == 0 }

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Cambiar imagen de referencia") }, onClick = {
                                menu = false
                                refPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("Eliminar proyecto") }, onClick = { menu = false; confirmDelete = true })
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
                                    contentDescription = "Imagen de referencia",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                )
                                Button(onClick = { comparing = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Comparar con mi figura (cámara)")
                                }
                            } else if (p.reference == null) {
                                OutlinedButton(
                                    onClick = { refPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Agregar imagen de referencia") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = p.status != "Terminado", onClick = { vm.setProjectStatus(p.id, "En progreso") }, label = { Text("En progreso") })
                                FilterChip(selected = p.status == "Terminado", onClick = { vm.setProjectStatus(p.id, "Terminado") }, label = { Text("Terminado") })
                                Text(
                                    "Desde " + DATE.format(Date(p.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // ---- Pinturas
                    item { SectionTitle("Pinturas (${paints.size})") }
                    if (paints.isEmpty()) {
                        item {
                            Text(
                                "Agrega las pinturas que usarás y para qué parte (piel, sombra del pelo, metal…).",
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
                            extra = listOfNotNull(pp.use.ifBlank { null }, if (have) null else "no la tienes").joinToString(" · ").ifBlank { null },
                            onClick = { onOpenPaint(paint) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { editingUse = paint.id }) { Text("Uso") }
                                IconButton(onClick = { vm.removePaintFromProject(p.id, paint.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Quitar del proyecto")
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickFrom = false }) { Text("+ De mi inventario") }
                                OutlinedButton(onClick = { pickFrom = true }) { Text("+ Del catálogo") }
                            }
                            if (missing.isNotEmpty()) {
                                val pending = missing.filter { m -> vm.shopping.none { it.paintId == m.first.id } }
                                if (pending.isEmpty()) {
                                    Text("Las ${missing.size} que te faltan ya están en tu lista de compras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    TextButton(onClick = {
                                        pending.forEach { vm.addToShopping(it.first.id, "Para ${p.name}") }
                                        say("${pending.size} agregadas a tu lista de compras")
                                    }) { Text("Agregar las ${pending.size} que te faltan a la lista de compras") }
                                }
                            }
                        }
                    }

                    // ---- Notas
                    item { SectionTitle("Notas y mezclas") }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                placeholder = { Text("Ej.: piel = 2 Flesh + 1 Rojo, glaseado de púrpura en sombras…") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (notes != p.notes) {
                                Button(onClick = { vm.setProjectNotes(p.id, notes); say("Notas guardadas") }) { Text("Guardar notas") }
                            }
                        }
                    }

                    // ---- Avance
                    item { SectionTitle("Avance (${p.photos.size} fotos)") }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { takePhoto() }) { Text("Tomar foto") }
                            OutlinedButton(onClick = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Text("De la galería")
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
            PaintPickerDialog(vm, if (catalog) "Agregar del catálogo" else "Agregar de tu inventario", onDismiss = { pickFrom = null }, fromCatalog = catalog) { paint ->
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
            title = { Text("¿Para qué la usas?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.byId[pid]?.name ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Base de la piel, luz del pelo…") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.setPaintUse(p.id, pid, text.trim()); editingUse = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editingUse = null }) { Text("Cancelar") } },
        )
    }

    if (renaming) {
        ProjectNameDialog(onDismiss = { renaming = false }, onSave = { vm.renameProject(p.id, it); renaming = false }, initial = p.name, title = "Renombrar")
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar “${p.name}”?") },
            text = { Text("Se borran sus notas, la imagen de referencia y las ${p.photos.size} fotos de avance. Tus pinturas no cambian.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteProject(p.id); onClose() }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    if (comparing) {
        val file = p.reference?.let { vm.projectFile(p.id, it) }
        CompareScreen(initialUri = file?.let { android.net.Uri.fromFile(it) }, onClose = { comparing = false })
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
            if (img != null) Image(img, contentDescription = ph.caption.ifBlank { "Foto de avance" }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White) }
                    Text(DATE.format(Date(ph.takenAt)), color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirm = true }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, contentDescription = "Foto de avance", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = caption,
                            onValueChange = { caption = it },
                            placeholder = { Text("Nota: capa base, luces terminadas…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { onCaption(caption) }, enabled = caption != ph.caption) { Text("Guardar") }
                    }
                }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("¿Eliminar esta foto?") },
                confirmButton = {
                    TextButton(onClick = { confirm = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Eliminar") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
            )
        }
    }
}
