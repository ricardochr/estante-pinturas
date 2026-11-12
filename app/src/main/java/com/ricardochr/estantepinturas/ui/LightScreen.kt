package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.DepthModel
import com.ricardochr.estantepinturas.LightParams
import com.ricardochr.estantepinturas.MeshData
import com.ricardochr.estantepinturas.MeshLoadException
import com.ricardochr.estantepinturas.PhotoLook
import com.ricardochr.estantepinturas.PhotoScene
import com.ricardochr.estantepinturas.PhotoSide
import com.ricardochr.estantepinturas.RelightRenderer
import com.ricardochr.estantepinturas.SavedMesh
import com.ricardochr.estantepinturas.ZipPart
import com.ricardochr.estantepinturas.loadBitmapForAnalysis
import com.ricardochr.estantepinturas.loadMeshFromUri
import com.ricardochr.estantepinturas.loadMeshFromZip
import com.ricardochr.estantepinturas.loadSavedMesh
import com.ricardochr.estantepinturas.saveMesh
import com.ricardochr.estantepinturas.savedMeshes
import com.ricardochr.estantepinturas.zipPartsOf
import com.ricardochr.estantepinturas.zoneColors
import com.ricardochr.estantepinturas.zoneNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Estado de una foto de "Luz y sombra". */
private class PhotoSlot(val photo: Bitmap) {
    var scene by mutableStateOf<PhotoScene?>(null)
    var status by mutableStateOf<String?>(null)
}

private enum class LightTemp(val label: String, val rgb: FloatArray) {
    CALIDA("Cálida", floatArrayOf(1f, 0.82f, 0.62f)),
    NEUTRA("Neutra", floatArrayOf(1f, 1f, 1f)),
    FRIA("Fría", floatArrayOf(0.78f, 0.88f, 1.08f)),
}

/**
 * Luz y sombra: coloca una luz alrededor de la figura y mira dónde caen las luces y sombras, sobre un
 * modelo 3D (STL/OBJ) o sobre fotos de la figura (frente, lados, espalda).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var azimuth by rememberSaveable { mutableFloatStateOf(35f) }
    var elevation by rememberSaveable { mutableFloatStateOf(55f) }
    var ambient by rememberSaveable { mutableFloatStateOf(0.18f) }
    var cavity by rememberSaveable { mutableFloatStateOf(0.6f) }
    var zones by rememberSaveable { mutableIntStateOf(0) }
    var temp by rememberSaveable { mutableStateOf(LightTemp.NEUTRA) }
    val light = LightParams(azimuth, elevation, ambient, cavity, temp.rgb, zones)

    // ---- 3D
    val glHolder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val renderer = remember { RelightRenderer { glHolder[0]?.requestRender() } }
    var mesh by remember { mutableStateOf<MeshData?>(null) }
    var saved by remember { mutableStateOf<List<SavedMesh>>(emptyList()) }
    var loadPhase by remember { mutableStateOf<String?>(null) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var meshError by remember { mutableStateOf<String?>(null) }
    var zipChoice by remember { mutableStateOf<Pair<File, List<ZipPart>>?>(null) }
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { savedMeshes(context) } }
    LaunchedEffect(light) { renderer.light = light }
    LaunchedEffect(mesh) { renderer.mesh = mesh }

    fun runLoad(block: ((String, Float) -> Unit) -> MeshData) {
        meshError = null
        loadPhase = tr("Abriendo")
        loadProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val m = block { phase, p -> scope.launch(Dispatchers.Main) { loadPhase = tr(phase); loadProgress = p } }
                    try { saveMesh(context, m) } catch (_: Exception) { }
                    Result.success(m)
                } catch (e: MeshLoadException) {
                    Result.failure(e)
                } catch (e: OutOfMemoryError) {
                    Result.failure(MeshLoadException(tr("El modelo es demasiado grande para la memoria del teléfono.")))
                } catch (e: Exception) {
                    Result.failure(MeshLoadException(tr("No pude leer el modelo: {}", e.message ?: e.javaClass.simpleName)))
                }
            }
            loadPhase = null
            result.onSuccess {
                mesh = it
                renderer.turnsX = 0; renderer.turnsY = 0; renderer.yaw = 0f; renderer.pitch = 12f; renderer.distance = 4.4f
                saved = withContext(Dispatchers.IO) { savedMeshes(context) }
            }.onFailure { meshError = tr(it.message ?: tr("No pude leer el modelo.")) }
        }
    }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        meshError = null
        loadPhase = tr("Abriendo")
        scope.launch {
            val zip = withContext(Dispatchers.IO) {
                try { Result.success(zipPartsOf(context, uri)) } catch (e: Exception) { Result.failure(e) }
            }
            loadPhase = null
            zip.onFailure { meshError = tr(it.message ?: tr("No pude abrir el archivo.")) }
            zip.onSuccess { z ->
                when {
                    z == null -> runLoad { p -> loadMeshFromUri(context, uri, p) }
                    z.second.size == 1 -> runLoad { p -> loadMeshFromZip(z.first, z.second[0], p) }
                    else -> zipChoice = z
                }
            }
        }
    }

    // ---- Fotos
    val slots = remember { mutableStateMapOf<PhotoSide, PhotoSlot>() }
    var side by rememberSaveable { mutableStateOf(PhotoSide.FRENTE) }
    var look by rememberSaveable { mutableStateOf(PhotoLook.ARCILLA) }
    var cut by rememberSaveable { mutableFloatStateOf(0f) }
    var relief by rememberSaveable { mutableFloatStateOf(0.8f) }
    var showOriginal by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(DepthModel.isReady(context)) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }

    fun process(s: PhotoSide) {
        val slot = slots[s] ?: return
        if (!DepthModel.isReady(context)) { slot.status = tr("Falta descargar el modelo"); return }
        slot.status = tr("Calculando el relieve…")
        scope.launch {
            val sc = withContext(Dispatchers.Default) {
                try {
                    val dm = DepthModel.estimate(context, slot.photo)
                    PhotoScene.from(slot.photo, dm).also { it.prepare(cut, relief) }
                } catch (e: Throwable) {
                    null
                }
            }
            if (slots[s] !== slot) return@launch
            slot.scene = sc
            slot.status = if (sc == null) tr("No pude calcular el relieve de esta foto.") else null
        }
    }

    fun startDownload() {
        downloadError = false
        downloading = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                DepthModel.download(context) { p -> scope.launch(Dispatchers.Main) { downloading = p } }
            }
            downloading = null
            modelReady = ok && DepthModel.isReady(context)
            downloadError = !modelReady
            if (modelReady) slots.keys.toList().forEach { if (slots[it]?.scene == null) process(it) }
        }
    }

    fun addPhoto(uri: Uri) {
        val target = side
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { try { loadBitmapForAnalysis(context, uri, 1280) } catch (_: Exception) { null } }
            if (bmp == null) return@launch
            slots[target] = PhotoSlot(bmp)
            process(target)
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addPhoto(uri) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) addPhoto(u)
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "figura-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        captureUri = uri
        camera.launch(uri)
    }

    val currentScene = slots[side]?.scene
    LaunchedEffect(currentScene, light, look, cut, relief, showOriginal, side) {
        val sc = currentScene ?: run { rendered = null; return@LaunchedEffect }
        delay(30)
        val job = coroutineContext[Job]
        val b = withContext(Dispatchers.Default) {
            sc.prepare(cut, relief)
            if (showOriginal) sc.original() else sc.render(light, side, look) { job?.isActive == false }
        }
        if (b != null) rendered = b
    }

    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(tr("LUZ Y SOMBRA"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(tr("Dónde cae la luz en tu figura"), style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Cerrar")) } },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Modelo 3D")) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("Fotos")) })
                }
                // Vista
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1C1D22))) {
                    if (tab == 0) {
                        if (mesh != null) {
                            AndroidView(
                                factory = { ctx ->
                                    GLSurfaceView(ctx).apply {
                                        setEGLContextClientVersion(3)
                                        setRenderer(renderer)
                                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                                        glHolder[0] = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        renderer.yaw -= pan.x * 0.35f
                                        renderer.pitch = (renderer.pitch + pan.y * 0.35f).coerceIn(-85f, 85f)
                                        renderer.distance = (renderer.distance / zoom).coerceIn(1.6f, 12f)
                                        renderer.changed()
                                    }
                                },
                            )
                            Text(
                                tr("Arrastra para girar · pellizca para acercar"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                            )
                        } else {
                            EmptyHint(
                                if (loadPhase != null) null
                                else tr("Abre un STL, OBJ o ZIP de tu figura. Los archivos grandes se simplifican al leerlos y quedan guardados aquí."),
                            )
                        }
                        loadPhase?.let { ph ->
                            Column(
                                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp).width(220.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(ph + "…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { loadProgress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        val slot = slots[side]
                        val img = rendered
                        when {
                            slot == null -> EmptyHint(tr("Agrega la foto de este lado con la cámara o desde la galería."))
                            slot.scene == null -> EmptyHint(slot.status ?: tr("Calculando el relieve…"))
                            img != null -> Image(img.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                    if (zones > 0) ZoneLegend(zones, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                // Controles
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LightPad(azimuth, elevation, Modifier.size(150.dp)) { az, el -> azimuth = az; elevation = el }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(describeLight(azimuth, elevation), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("Mueve el sol: centro = cenital, borde = horizontal, fuera del círculo = desde abajo. Abajo del círculo es el frente de la figura."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(tr("Cenital"), azimuth, 88f),
                            Triple(tr("Frontal"), 0f, 30f),
                            Triple(tr("Lateral"), 90f, 20f),
                            Triple(tr("Contraluz"), 180f, 25f),
                            Triple(tr("Abajo"), 0f, -20f),
                        ).forEach { (label, az, el) ->
                            FilterChip(selected = false, onClick = { azimuth = az; elevation = el }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = zones == 0, onClick = { zones = 0 }, label = { Text(tr("Iluminada")) })
                        FilterChip(selected = zones == 3, onClick = { zones = 3 }, label = { Text(tr("3 zonas")) })
                        FilterChip(selected = zones == 5, onClick = { zones = 5 }, label = { Text(tr("5 zonas")) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LightTemp.entries.forEach { t ->
                            FilterChip(selected = temp == t, onClick = { temp = t }, label = { Text(tr(t.label)) })
                        }
                    }
                    LabeledSlider(tr("Luz ambiental"), ambient, 0.03f..0.6f) { ambient = it }
                    LabeledSlider(tr("Oscurecer huecos"), cavity, 0f..1f) { cavity = it }

                    if (tab == 0) {
                        mesh?.let { m ->
                            Text(
                                tr("{}: {} triángulos", m.name, "%,d".format(m.triangleCount)) +
                                    if (m.sourceTriangles > m.triangleCount) tr(" (simplificado de {})", "%,d".format(m.sourceTriangles)) else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { renderer.turnsY = (renderer.turnsY + 1) % 4; renderer.changed() }) {
                                    Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(tr("Girar"))
                                }
                                OutlinedButton(onClick = { renderer.turnsX = (renderer.turnsX + 1) % 4; renderer.changed() }) {
                                    Icon(Icons.Rounded.SwapVert, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(tr("Voltear"))
                                }
                                IconButton(onClick = { renderer.yaw = 0f; renderer.pitch = 12f; renderer.distance = 4.4f; renderer.changed() }) {
                                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = tr("Ver de frente"))
                                }
                            }
                            Text(
                                tr("Si la figura sale acostada o de espaldas, usa Girar y Voltear: la luz se mide desde el frente de la figura."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { openModel.launch(arrayOf("*/*")) }, enabled = loadPhase == null, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(tr("Abrir STL, OBJ o ZIP"))
                        }
                        meshError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (saved.isNotEmpty()) {
                            Text(tr("Modelos guardados"), style = MaterialTheme.typography.titleSmall)
                            saved.forEach { sm ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = loadPhase == null) {
                                        runLoad { _ -> loadSavedMesh(sm.file) }
                                    }.padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(sm.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(tr("{} triángulos", "%,d".format(sm.triangles)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        sm.file.delete()
                                        scope.launch { saved = withContext(Dispatchers.IO) { savedMeshes(context) } }
                                    }) { Icon(Icons.Rounded.Delete, contentDescription = tr("Borrar")) }
                                }
                            }
                        }
                    } else {
                        if (!modelReady) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tr("Las fotos necesitan un modelo de profundidad ({} MB). Se descarga una sola vez y luego funciona sin internet.", DepthModel.SIZE_MB), style = MaterialTheme.typography.bodySmall)
                                    val d = downloading
                                    if (d != null) {
                                        LinearProgressIndicator(progress = { d }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        FilledTonalButton(onClick = ::startDownload) { Text(tr("Descargar modelo")) }
                                    }
                                    if (downloadError) Text(tr("No se pudo descargar. Revisa la conexión e intenta de nuevo."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PhotoSide.entries.forEach { s ->
                                SideSlot(s, slots[s], selected = side == s, modifier = Modifier.weight(1f)) { side = s }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = ::takePhoto, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.CameraAlt, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(tr("Cámara"))
                            }
                            FilledTonalButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(tr("Galería"))
                            }
                            if (slots[side] != null) {
                                IconButton(onClick = { slots.remove(side) }) { Icon(Icons.Rounded.Delete, contentDescription = tr("Quitar foto")) }
                            }
                        }
                        if (slots[side]?.scene != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PhotoLook.entries.forEach { l ->
                                    FilterChip(selected = look == l && !showOriginal, onClick = { look = l; showOriginal = false }, label = { Text(tr(l.label)) })
                                }
                                FilterChip(selected = showOriginal, onClick = { showOriginal = !showOriginal }, label = { Text(tr("Original")) })
                            }
                            LabeledSlider(tr("Recorte"), cut, -1f..1f) { cut = it }
                            LabeledSlider(tr("Volumen"), relief, 0.2f..1.5f) { relief = it }
                        }
                        Text(
                            tr("Para mejor resultado: figura imprimada en gris o blanco, luz suave sin sombras fuertes, fondo liso y la cámara a la altura de la figura. Para los lados, deja la cámara fija y gira la figura 90° cada vez."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { glHolder[0]?.onPause(); glHolder[0] = null } }

    zipChoice?.let { (file, parts) ->
        AlertDialog(
            onDismissRequest = { zipChoice = null },
            title = { Text(tr("¿Qué pieza abro?")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    parts.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                zipChoice = null
                                runLoad { pr -> loadMeshFromZip(file, p, pr) }
                            }.padding(vertical = 8.dp),
                        ) {
                            Text(p.name.substringAfterLast('/'), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            // i18n:off
                            Text(if (p.size > 0) String.format("%.1f MB", p.size / 1e6) else "", style = MaterialTheme.typography.labelSmall)
                            // i18n:on
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { zipChoice = null }) { Text(tr("Cancelar")) } },
        )
    }
}

private fun describeLight(az: Float, el: Float): String {
    val a = ((az % 360) + 360) % 360
    val from = when {
        el >= 75 -> return tr("Luz cenital ({}°)", el.toInt())
        a < 22.5 || a >= 337.5 -> tr("de frente")
        a < 67.5 -> tr("de frente, por la derecha")
        a < 112.5 -> tr("de lado, por la derecha")
        a < 157.5 -> tr("de atrás, por la derecha")
        a < 202.5 -> tr("de atrás")
        a < 247.5 -> tr("de atrás, por la izquierda")
        a < 292.5 -> tr("de lado, por la izquierda")
        else -> tr("de frente, por la izquierda")
    }
    return if (el < 0) tr("Luz desde abajo, {}", from) else tr("Luz {} a {}° de altura", from, el.toInt())
}

/** Vista desde arriba: la figura en el centro, su frente abajo. Arrastrar el sol cambia ángulo y altura. */
@Composable
private fun LightPad(azimuth: Float, elevation: Float, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val accent = Color(0xFFFFC23D)
    val fig = MaterialTheme.colorScheme.onSurfaceVariant
    fun update(pos: Offset, w: Float, h: Float) {
        val r = minOf(w, h) / 2f / 1.3f
        val dx = (pos.x - w / 2f) / r
        val dy = (pos.y - h / 2f) / r
        val d = sqrt(dx * dx + dy * dy).coerceAtMost(1.3f)
        val el = (90f * (1f - d)).coerceIn(-27f, 90f)
        val az = if (d < 0.02f) azimuth else Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        onChange(((az % 360) + 360) % 360, el)
    }
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { update(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> update(change.position, size.width.toFloat(), size.height.toFloat()) } },
    ) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = minOf(size.width, size.height) / 2f / 1.3f
        drawCircle(Color(0x22FFC23D), r * 1.3f, c)
        drawCircle(Color(0x33FFFFFF), r, c)
        drawCircle(ring, r, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(ring, r * 2 / 3, c, style = Stroke(1.dp.toPx()))
        drawCircle(ring, r / 3, c, style = Stroke(1.dp.toPx()))
        // La figura (vista desde arriba) con su frente hacia abajo
        drawCircle(fig, 7.dp.toPx(), c)
        drawLine(fig, c, Offset(c.x, c.y + 14.dp.toPx()), strokeWidth = 3.dp.toPx())
        val d = 1f - elevation / 90f
        val a = Math.toRadians(azimuth.toDouble())
        val p = Offset(c.x + (r * d * sin(a)).toFloat(), c.y + (r * d * cos(a)).toFloat())
        drawLine(accent.copy(alpha = 0.6f), c, p, strokeWidth = 2.dp.toPx())
        drawCircle(Color.Black.copy(alpha = 0.4f), 12.dp.toPx(), p)
        drawCircle(accent, 10.dp.toPx(), p)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(120.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyHint(text: String?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (text != null) Text(text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ZoneLegend(zones: Int, modifier: Modifier) {
    val colors = zoneColors(zones)
    val names = zoneNames(zones)
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        names.indices.reversed().forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(colors[i])).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp)))
                Text(tr(names[i]), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun SideSlot(side: PhotoSide, slot: PhotoSlot?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (slot != null) {
                val thumb = remember(slot.photo) { slot.photo.asImageBitmap() }
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (slot.scene == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            } else {
                Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(tr(side.label), style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 1)
    }
}
