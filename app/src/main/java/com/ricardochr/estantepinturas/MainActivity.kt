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
        enableEdgeToEdge()
        setContent {
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

    BackHandler(enabled = tab != Tab.Inventario || invMode != 0) { tab = Tab.Inventario; invMode = 0 }

    Scaffold(
        topBar = {
            val accent = tabPigment(tab)
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrandDrops(7.dp)
                            Text(
                                "GOTARIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent[1],
                                letterSpacing = 3.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                        Text(
                            if (tab == Tab.Inventario && invMode == 1) "Catálogo" else if (tab == Tab.Inventario) "Mis pinturas" else tab.label,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    val toBuy = vm.shoppingCount
                    HeaderButton(Icons.Rounded.Layers, "Superponer referencia", Pigment.Magenta) { showCompare = true }
                    Box {
                        HeaderButton(Icons.Rounded.ShoppingCart, "Lista de compras", Pigment.Tangerine) { showShopping = true }
                        if (toBuy > 0) {
                            Badge(Modifier.align(Alignment.TopEnd), containerColor = Pigment.Crimson[0], contentColor = Color.White) { Text(toBuy.toString()) }
                        }
                    }
                }
                // Franja de pintura bajo el encabezado, del color de la pestaña
                Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(listOf(accent[0], accent[1], accent[1].copy(alpha = 0f)))))
            }
        },
        bottomBar = { GotarioNavBar(tab) { tab = it } },
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
                        openProject == null && !showShopping && !showBackup && !showGear && !showSets && !showCompare && !showWheel && !showGuide && wheelFor == null,
                    onLinkBarcode = { linking = it },
                    onNotFound = { goCatalog() },
                    onAdded = { say(it) },
                )
                Tab.Paleta -> PaletteHub(vm, onOpenPaint = { editing = it }, onProjectCreated = {
                    say("Proyecto creado: ${it.name}")
                    openProject = it.id
                })
                Tab.Taller -> HubScreen(vm) { action ->
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
                    }
                }
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
        com.ricardochr.estantepinturas.ui.CompareScreen(initialUri = null, onClose = { showCompare = false })
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
            .padding(4.dp)
            .size(42.dp)
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
 * Barra de navegación flotante: tarjeta redondeada con sombra, cada pestaña con su color de
 * pigmento y Escanear al centro como botón grande y elevado.
 */
@Composable
private fun GotarioNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth().height(70.dp),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.values().forEach { t ->
                    if (t == Tab.Escanear) {
                        // Espacio para el botón central
                        Box(Modifier.weight(1f))
                    } else {
                        NavItem(t, current == t, Modifier.weight(1f)) { onSelect(t) }
                    }
                }
            }
        }
        // Botón central de escanear, elevado sobre la barra
        val scanSelected = current == Tab.Escanear
        val scale by animateFloatAsState(if (scanSelected) 1.08f else 1f, label = "scan")
        Column(
            Modifier.padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(12.dp, CircleShape)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(diagonal(Pigment.Emerald))
                    .clickable { onSelect(Tab.Escanear) }
                    .semantics { contentDescription = "Escanear" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(
                "Escanear",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (scanSelected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                color = if (scanSelected) Pigment.Emerald[1] else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun NavItem(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pg = tabPigment(t)
    val pillWidth by animateDpAsState(if (selected) 52.dp else 36.dp, label = "pill")
    val iconTint by animateColorAsState(if (selected) Color.White else pg[0], label = "tint")
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
                .background(if (selected) diagonal(pg) else Brush.linearGradient(listOf(pg[0].copy(alpha = 0.12f), pg[0].copy(alpha = 0.12f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tabIcon(t), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Text(
            t.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
            color = if (selected) pg[1] else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
