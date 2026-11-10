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
import com.ricardochr.estantepinturas.ui.HubAction
import com.ricardochr.estantepinturas.ui.HubScreen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Brush as BrushIcon
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ricardochr.estantepinturas.ui.BrandDrops
import com.ricardochr.estantepinturas.ui.Pigment
import com.ricardochr.estantepinturas.ui.diagonal
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        I18n.load(this)
        enableEdgeToEdge()
        setContent {
            // Los íconos de la barra de estado siguen al tema elegido en Configuración, no al del teléfono
            val dark = when (Prefs.theme) {
                "dark" -> true
                "system" -> androidx.compose.foundation.isSystemInDarkTheme()
                else -> false
            }
            androidx.compose.runtime.LaunchedEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(clear) else androidx.activity.SystemBarStyle.light(clear, clear),
                    navigationBarStyle = androidx.activity.SystemBarStyle.dark(clear),
                )
            }
            EstanteTheme {
                App()
            }
        }
    }
}

private enum class Tab(val label: String) { Inventario("Inventario"), Proyectos("Proyectos"), Escanear("Escanear"), Paleta("Paleta"), Taller("Taller") }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Inventario) }
    var editing by remember { mutableStateOf<Paint?>(null) }
    var linking by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createForBarcode by remember { mutableStateOf<String?>(null) }
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
    var showCompare by rememberSaveable { mutableStateOf(false) }
    var showSets by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showWheel by rememberSaveable { mutableStateOf(false) }
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

    val hubAction: (HubAction) -> Unit = { action ->
        when (action) {
                        HubAction.Compare -> showCompare = true
                        HubAction.Wheel -> showWheel = true
                        HubAction.Guide -> showGuide = true
                        HubAction.Gear -> showGear = true
                        HubAction.Shopping -> showShopping = true
                        HubAction.Sets -> showSets = true
                        HubAction.Find -> storage = StorageRequest()
                        HubAction.AddCustom -> { createForBarcode = null; creating = true }
                        HubAction.UpdateCatalog -> if (!vm.catalogUpdating) {
                            say("Buscando novedades…")
                            vm.refreshCatalog { say(it) }
                        }
                        HubAction.Backup -> showBackup = true
                        HubAction.ExportInventory -> {
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("inventario-pinturas-$stamp.csv")
                        }
                        HubAction.ImportInventory -> importLauncher.launch(csvTypes)
                        HubAction.ImportCatalog -> catalogImportLauncher.launch(csvTypes)
                        HubAction.ExportCatalog -> catalogExportLauncher.launch("catalogo-pinturas.csv")
                        HubAction.ClearImported -> confirmClearCatalog = true
                        HubAction.About -> showAbout = true
                        else -> {}
        }
    }

    // Actualización automática del catálogo: una vez por semana, al abrir la app
    androidx.compose.runtime.LaunchedEffect(vm.loading) {
        if (!vm.loading && Prefs.autoUpdate && !vm.catalogUpdating &&
            System.currentTimeMillis() - vm.catalogDownloadedAt > 7L * 24 * 3600 * 1000
        ) {
            vm.refreshCatalog { msg -> if (!msg.contains("No pude", ignoreCase = true)) say(msg) }
        }
    }

    BackHandler(enabled = tab != Tab.Inventario || invMode != 0) { tab = Tab.Inventario; invMode = 0 }

    Scaffold(
        topBar = {
            val accent = tabPigment(tab)
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (tab == Tab.Inventario) {
                        // En Inventario el encabezado es el selector entre tus pinturas y el catálogo
                        Box(Modifier.weight(1f)) {
                            SegmentedPill(
                                listOf("Mis pinturas", "Catálogo"), invMode, accent,
                            ) { invMode = it }
                        }
                    } else {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(diagonal(accent)))
                            Text(tab.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                        }
                    }
                    HeaderButton(Icons.Rounded.Settings, "Configuración", Pigment.Slate) { showSettings = true }
                    HeaderButton(Icons.Rounded.Layers, "Superponer referencia", Pigment.Magenta) { showCompare = true }
                    Box {
                        HeaderButton(Icons.Rounded.ShoppingCart, "Lista de compras", Pigment.Tangerine) { showShopping = true }
                        val toBuy = vm.shoppingCount
                        if (toBuy > 0) {
                            Badge(Modifier.align(Alignment.TopEnd), containerColor = Pigment.Crimson[0], contentColor = Color.White) { Text(toBuy.toString()) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(accent[0], accent[1], accent[1].copy(alpha = 0f)))))
            }
        },
        bottomBar = { GotarioNavBar(tab) { tab = it } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (tab) {
                Tab.Inventario -> Column(Modifier.fillMaxSize()) {
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
                        openProject == null && !showShopping && !showBackup && !showGear && !showSets && !showCompare && !showWheel && !showGuide && !showSettings && wheelFor == null,
                    onLinkBarcode = { linking = it },
                    onNotFound = { goCatalog() },
                    onAdded = { say(it) },
                )
                Tab.Paleta -> PaletteHub(vm, onOpenPaint = { editing = it }, onProjectCreated = {
                    say("Proyecto creado: ${it.name}")
                    openProject = it.id
                })
                Tab.Taller -> HubScreen(vm) { hubAction(it) }
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
            equivalents = produceState<List<MainViewModel.Equivalent>?>(null, p.id, vm.owned) { value = vm.equivalentsOf(p) }.value,
            onSaveThin = { vm.setThinNote(p.id, it); say(tr("Dilución guardada")) },
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
            brandSuggestions = remember(vm.owned, vm.catalog) {
                val mine = com.ricardochr.estantepinturas.ui.ownedBrandCounts(vm)
                com.ricardochr.estantepinturas.ui.brandsOwnedFirst(vm.brandCounts.map { it.first to it.second }, mine).map { it.first }
            },
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

    if (showSets) {
        com.ricardochr.estantepinturas.ui.SetsScreen(vm, onClose = { showSets = false }, onOpenPaint = { editing = it }, say = { say(it) })
    }

    if (showCompare) {
        com.ricardochr.estantepinturas.ui.CompareScreen(
            initialUri = null,
            onClose = { showCompare = false },
            saveTargets = vm.projects.map { it.id to it.name },
            onSave = { pid, bmp ->
                vm.addProgressBitmap(pid, bmp, tr("Comparación con la referencia")) { ok ->
                    say(if (ok) tr("Comparación guardada en las fotos de avance") else tr("No pude guardar la comparación"))
                }
            },
        )
    }

    if (showSettings) {
        com.ricardochr.estantepinturas.ui.SettingsScreen(
            vm,
            onClose = { showSettings = false },
            onAction = { hubAction(it) },
            onLanguageChanged = { vm.refreshPaletteMatches() },
        )
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

    if (showGuide) {
        Box(Modifier.fillMaxSize()) {
            BackHandler { showGuide = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TopAppBar(
                        title = { Text("Guía de técnicas") },
                        navigationIcon = { IconButton(onClick = { showGuide = false }) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    Box(Modifier.weight(1f)) { GuideScreen() }
                }
            }
        }
    }

    if (wheelFor != null || showWheel) {
        val p = wheelFor
        Box(Modifier.fillMaxSize()) {
            BackHandler { wheelFor = null; showWheel = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TopAppBar(
                        title = { Text("Círculo cromático") },
                        navigationIcon = { IconButton(onClick = { wheelFor = null; showWheel = false }) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
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
                            "Catálogo: ${vm.catalog.size} pinturas y ${vm.sets.size} sets, armado con las tiendas oficiales, tiendas minoristas y los proyectos abiertos paintdex y miniature-paints (licencia MIT). " +
                            (if (vm.catalogDownloadedAt > 0) "Descargado el " + SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(vm.catalogDownloadedAt)) + ". " else "Es el que vino con la app; usa Actualizar catálogo para bajar el más reciente. ") +
                            "Importadas por ti: ${vm.extra.size}. Los colores son aproximados.\n\n" +
                            "El reconocimiento de etiquetas y códigos de barras usa ML Kit de Google y funciona sin internet. " +
                            "Tu inventario y proyectos se guardan en este teléfono; activa Respaldo (pestaña Taller) para tener una copia en Google Drive u otro lugar."
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

/** Color de pigmento de cada pestaña: se usa en su ícono, la franja del encabezado y la marca. */
private fun tabPigment(t: Tab): List<Color> = when (t) {
    Tab.Inventario -> Pigment.Cobalt
    Tab.Proyectos -> Pigment.Magenta
    Tab.Escanear -> Pigment.Emerald
    Tab.Paleta -> Pigment.Violet
    Tab.Taller -> Pigment.Tangerine
}

private fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.Inventario -> Icons.Rounded.Inventory2
    Tab.Proyectos -> Icons.Rounded.BrushIcon
    Tab.Escanear -> Icons.Rounded.QrCodeScanner
    Tab.Paleta -> Icons.Rounded.Palette
    Tab.Taller -> Icons.Rounded.Handyman
}

/** Botón redondo con degradado de pigmento para el encabezado. */
@Composable
private fun HeaderButton(icon: ImageVector, description: String, colors: List<Color>, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(diagonal(colors))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Barra de navegación: las cinco secciones con el mismo peso, cada una con su color de pigmento.
 * Solo la sección abierta se resalta (píldora con degradado e ícono blanco).
 */
@Composable
private fun GotarioNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(diagonal(Pigment.Night)),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.values().forEach { t -> NavItem(t, current == t, Modifier.weight(1f)) { onSelect(t) } }
        }
    }
}

@Composable
private fun NavItem(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pg = tabPigment(t)
    val pillWidth by animateDpAsState(if (selected) 56.dp else 40.dp, label = "pill")
    val iconTint by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.6f), label = "tint")
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .height(32.dp)
                .width(pillWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) diagonal(pg) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tabIcon(t), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Text(
            t.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

/** Selector de dos o más opciones en forma de píldora; la elegida va con el degradado de la pestaña. */
@Composable
private fun SegmentedPill(options: List<String>, selected: Int, colors: List<Color>, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (on) diagonal(colors) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
