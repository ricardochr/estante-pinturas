@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ricardochr.estantepinturas.ui.CatalogList
import com.ricardochr.estantepinturas.ui.CustomPaintDialog
import com.ricardochr.estantepinturas.ui.EntrySheet
import com.ricardochr.estantepinturas.ui.EstanteTheme
import com.ricardochr.estantepinturas.ui.InventoryScreen
import com.ricardochr.estantepinturas.ui.GuideIcon
import com.ricardochr.estantepinturas.ui.GuideScreen
import com.ricardochr.estantepinturas.ui.PaletteIcon
import com.ricardochr.estantepinturas.ui.PaletteHub
import com.ricardochr.estantepinturas.ui.ColorWheelScreen
import androidx.compose.foundation.layout.Box
import com.ricardochr.estantepinturas.ui.ScanIcon
import com.ricardochr.estantepinturas.ui.ScanScreen
import com.ricardochr.estantepinturas.ui.StorageRequest
import com.ricardochr.estantepinturas.ui.StorageScreen
import com.ricardochr.estantepinturas.ui.LiveFindScreen
import com.ricardochr.estantepinturas.ui.BackupScreen
import com.ricardochr.estantepinturas.ui.ProjectDetail
import com.ricardochr.estantepinturas.ui.ProjectsScreen
import com.ricardochr.estantepinturas.ui.ShoppingScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EstanteTheme {
                App()
            }
        }
    }
}

private enum class Tab(val label: String) { Inventario("Inventario"), Proyectos("Proyectos"), Escanear("Escanear"), Paleta("Paleta"), Guia("Guía") }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Inventario) }
    var editing by remember { mutableStateOf<Paint?>(null) }
    var linking by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createForBarcode by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmClearCatalog by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf<StorageRequest?>(null) }
    var liveFind by remember { mutableStateOf<Paint?>(null) }
    var wheelFor by remember { mutableStateOf<Paint?>(null) }
    var invMode by rememberSaveable { mutableStateOf(0) } // 0 = Mis pinturas, 1 = Catálogo
    var openProject by rememberSaveable { mutableStateOf<String?>(null) }
    var showShopping by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showGear by rememberSaveable { mutableStateOf(false) }
    fun goCatalog() { tab = Tab.Inventario; invMode = 1 }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri) { say(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri) { say(it) }
    }

    val catalogImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCatalogCsv(uri) { say(it) }
    }
    val catalogExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCatalogCsv(uri) { say(it) }
    }
    val csvTypes = arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")

    androidx.compose.runtime.LaunchedEffect(vm.adoptedCount) {
        if (vm.adoptedCount > 0) say("${vm.adoptedCount} pinturas que agregaste a mano ya están en el catálogo y se unieron a él")
    }

    BackHandler(enabled = tab != Tab.Inventario || invMode != 0) { tab = Tab.Inventario; invMode = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "GOTARIO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            letterSpacing = 2.sp,
                        )
                        Text(if (tab == Tab.Inventario && invMode == 1) "Catálogo" else if (tab == Tab.Inventario) "Mis pinturas" else tab.label, style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Agregar pintura fuera del catálogo") }, onClick = {
                            menuOpen = false; createForBarcode = null; creating = true
                        })
                        DropdownMenuItem(text = { Text("Lista de compras") }, onClick = {
                            menuOpen = false; showShopping = true
                        })
                        DropdownMenuItem(text = { Text("Encontrar una pintura") }, onClick = {
                            menuOpen = false; storage = StorageRequest()
                        })
                        DropdownMenuItem(text = { Text("Mi equipo (aerógrafos)") }, onClick = {
                            menuOpen = false; showGear = true
                        })
                        DropdownMenuItem(text = { Text("Respaldo") }, onClick = {
                            menuOpen = false; showBackup = true
                        })
                        DropdownMenuItem(text = { Text("Exportar inventario (CSV)") }, onClick = {
                            menuOpen = false
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("inventario-pinturas-$stamp.csv")
                        })
                        DropdownMenuItem(text = { Text("Importar inventario (CSV)") }, onClick = {
                            menuOpen = false
                            importLauncher.launch(csvTypes)
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Agregar pinturas al catálogo (CSV)") }, onClick = {
                            menuOpen = false
                            catalogImportLauncher.launch(csvTypes)
                        })
                        DropdownMenuItem(text = { Text("Exportar catálogo completo (CSV)") }, onClick = {
                            menuOpen = false
                            catalogExportLauncher.launch("catalogo-pinturas.csv")
                        })
                        if (vm.extra.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Quitar pinturas importadas (${vm.extra.size})") }, onClick = {
                                menuOpen = false
                                confirmClearCatalog = true
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Acerca de") }, onClick = { menuOpen = false; showAbout = true })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == Tab.Inventario, onClick = { tab = Tab.Inventario },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Inventario") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Proyectos, onClick = { tab = Tab.Proyectos },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) }, label = { Text("Proyectos") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Escanear, onClick = { tab = Tab.Escanear },
                    icon = { Icon(ScanIcon, contentDescription = null) }, label = { Text("Escanear") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Paleta, onClick = { tab = Tab.Paleta },
                    icon = { Icon(PaletteIcon, contentDescription = null) }, label = { Text("Paleta") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Guia, onClick = { tab = Tab.Guia },
                    icon = { Icon(GuideIcon, contentDescription = null) }, label = { Text("Guía") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (tab) {
                Tab.Inventario -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = invMode == 0, onClick = { invMode = 0 }, label = { Text("Mis pinturas (${vm.owned.values.count { it.qty > 0 }})") })
                        FilterChip(selected = invMode == 1, onClick = { invMode = 1 }, label = { Text("Catálogo") })
                    }
                    Box(Modifier.weight(1f)) {
                        if (invMode == 0) {
                            InventoryScreen(
                                vm,
                                onOpenStorage = { storage = StorageRequest() },
                                onEdit = { editing = it },
                                onGoCatalog = { goCatalog() },
                                onGoScan = { tab = Tab.Escanear },
                                onOpenShopping = { showShopping = true },
                            )
                        } else {
                            CatalogList(
                                vm,
                                onRowClick = { editing = it },
                                onPlus = { p ->
                                    val q = vm.qtyOf(p.id)
                                    vm.add(p.id, 1)
                                    say(if (q == 0) "Agregada: ${p.name}" else "${p.name}: ahora ${q + 1} botes")
                                },
                                footer = {
                                    TextButton(onClick = { createForBarcode = null; creating = true }, modifier = Modifier.padding(8.dp)) {
                                        Text("¿No está en el catálogo? Agrégala a mano")
                                    }
                                },
                            )
                        }
                    }
                }
                Tab.Proyectos -> ProjectsScreen(vm, onOpenProject = { openProject = it })
                Tab.Escanear -> ScanScreen(
                    vm,
                    active = linking == null && editing == null && !creating && storage == null && liveFind == null &&
                        openProject == null && !showShopping && !showBackup && !showGear,
                    onLinkBarcode = { linking = it },
                    onNotFound = { goCatalog() },
                    onAdded = { say(it) },
                )
                Tab.Paleta -> PaletteHub(vm, onOpenPaint = { editing = it }, onProjectCreated = {
                    say("Proyecto creado: ${it.name}")
                    openProject = it.id
                })
                Tab.Guia -> GuideScreen()
            }
        }
    }

    editing?.let { p -> key(p.id) {
        EntrySheet(
            paint = p,
            entry = vm.owned[p.id],
            onDismiss = { editing = null },
            onSave = { qty, notes ->
                vm.update(p.id, qty, notes)
                editing = null
                say("Guardada: ${p.name}")
            },
            onDelete = {
                vm.remove(p.id)
                editing = null
                say("Quitada del inventario: ${p.name}")
            },
            similar = remember(p.id, vm.owned) { vm.similarOwned(p) },
            onOpenSimilar = { editing = it },
            locationName = vm.spotOf(p.id)?.name,
            inventory = remember(vm.owned, vm.byId) { vm.owned.values.filter { it.qty > 0 }.mapNotNull { vm.byId[it.paintId] } },
            onOpenWheel = { editing = null; wheelFor = p },
            onFixColor = { hx ->
                vm.fixColor(p.id, hx)
                editing = vm.byId[p.id]
                say("Color guardado")
            },
            onShowLocation = { editing = null; storage = StorageRequest(findPaintId = p.id) },
            onPlaceLocation = { editing = null; storage = StorageRequest(placingPaintId = p.id) },
            inShopping = vm.shopping.any { it.paintId == p.id },
            onToggleShopping = {
                if (vm.inShopping(p.id)) { vm.removeFromShopping(p.id); say("Quitada de la lista de compras") }
                else { vm.addToShopping(p.id, ""); say("Agregada a la lista de compras") }
            },
            onSetLow = { low ->
                vm.setLow(p.id, low)
                say(if (low) "En tu lista de compras" else "Quitada de la lista de compras")
            },
            projects = vm.projects,
            onAddToProject = { pid ->
                vm.addPaintToProject(pid, p.id)
                say("Agregada a ${vm.projects.firstOrNull { it.id == pid }?.name ?: "el proyecto"}")
            },
            mixes = produceState<List<MixRecipe>?>(null, p.id, vm.owned) {
                val ownedQty = vm.owned[p.id]?.qty ?: 0
                value = if (ownedQty == 0 && p.hasColor) vm.mixesFor(p.lab, p.id) else emptyList()
            }.value,
        )
    } }

    linking?.let { code ->
        Dialog(onDismissRequest = { linking = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.systemBarsPadding()) {
                    TopAppBar(
                        title = { Text("¿Qué pintura es?") },
                        navigationIcon = {
                            IconButton(onClick = { linking = null }) { Icon(Icons.Default.Close, contentDescription = "Cerrar") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    Text(
                        "Elige la pintura del código $code. La próxima vez se reconocerá sola.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    CatalogList(
                        vm,
                        onRowClick = { p ->
                            vm.linkBarcode(code, p.id)
                            vm.add(p.id, 1)
                            linking = null
                            say("Asociada y agregada: ${p.name}")
                        },
                        onPlus = null,
                        footer = {
                            TextButton(onClick = { createForBarcode = code; linking = null; creating = true }, modifier = Modifier.padding(8.dp)) {
                                Text("No está: crear pintura nueva")
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        CustomPaintDialog(
            onDismiss = { creating = false },
            onSave = { brand, line, name, code, hex, type, qty ->
                val id = vm.addCustom(brand, line, name, code, hex, type, qty)
                createForBarcode?.let { vm.linkBarcode(it, id) }
                creating = false
                createForBarcode = null
                say("Agregada: $name")
            },
        )
    }

    openProject?.let { id ->
        ProjectDetail(vm, id, onClose = { openProject = null }, onOpenPaint = { editing = it }, say = { say(it) })
    }

    if (showShopping) {
        ShoppingScreen(vm, onClose = { showShopping = false }, onOpenPaint = { editing = it }, say = { say(it) })
    }

    if (showGear) {
        com.ricardochr.estantepinturas.ui.GearScreen(vm, onClose = { showGear = false }, say = { say(it) })
    }

    if (showBackup) {
        BackupScreen(vm, onClose = { showBackup = false }, say = { say(it) })
    }

    storage?.let { req ->
        StorageScreen(
            vm = vm,
            request = req,
            onClose = { storage = null },
            onOpenPaint = { editing = it },
            onLiveFind = { liveFind = it },
            say = { say(it) },
        )
    }

    wheelFor?.let { p ->
        Box(Modifier.fillMaxSize()) {
            BackHandler { wheelFor = null }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TopAppBar(
                        title = { Text("Círculo cromático") },
                        navigationIcon = { IconButton(onClick = { wheelFor = null }) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    Box(Modifier.weight(1f)) {
                        ColorWheelScreen(vm, onOpenPaint = { editing = it }, focus = p)
                    }
                }
            }
        }
    }

    liveFind?.let { p ->
        LiveFindScreen(vm, p, onClose = { liveFind = null })
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Cerrar") } },
            title = { Text("Gotario") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Versión ${BuildConfig.VERSION_NAME}\n\n" +
                            "Catálogo incluido: ${vm.catalog.size} pinturas del proyecto miniature-paints de Rick Fleuren (licencia MIT), actualizado a marzo de 2025. " +
                            "Importadas por ti: ${vm.extra.size}. Los colores son aproximados.\n\n" +
                            "El reconocimiento de etiquetas y códigos de barras usa ML Kit de Google y funciona sin internet. " +
                            "Tu inventario y proyectos se guardan en este teléfono; activa Respaldo (menú) para tener una copia en Google Drive u otro lugar."
                    )
                    Text("Pinturas por marca", style = MaterialTheme.typography.titleSmall)
                    vm.brandCounts.forEach { (b, n) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(b, Modifier.weight(1f))
                            Text(n.toString(), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
        )
    }

    if (confirmClearCatalog) {
        AlertDialog(
            onDismissRequest = { confirmClearCatalog = false },
            title = { Text("¿Quitar las pinturas importadas?") },
            text = { Text("Se quitan del catálogo las ${vm.extra.size} pinturas que importaste. Las que tengas en tu inventario se conservan.") },
            confirmButton = {
                TextButton(onClick = { confirmClearCatalog = false; vm.clearExtraCatalog { say(it) } }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { confirmClearCatalog = false }) { Text("Cancelar") } },
        )
    }
}
