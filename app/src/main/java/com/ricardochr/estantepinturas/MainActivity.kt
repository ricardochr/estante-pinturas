@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.ricardochr.estantepinturas.ui.ScanIcon
import com.ricardochr.estantepinturas.ui.ScanScreen
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

private enum class Tab(val label: String) { Inventario("Mis pinturas"), Catalogo("Catálogo"), Escanear("Escanear") }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Inventario) }
    var editing by remember { mutableStateOf<Paint?>(null) }
    var linking by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createForBarcode by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
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

    BackHandler(enabled = tab != Tab.Inventario) { tab = Tab.Inventario }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label.uppercase(), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Agregar pintura fuera del catálogo") }, onClick = {
                            menuOpen = false; createForBarcode = null; creating = true
                        })
                        DropdownMenuItem(text = { Text("Exportar inventario (CSV)") }, onClick = {
                            menuOpen = false
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("inventario-pinturas-$stamp.csv")
                        })
                        DropdownMenuItem(text = { Text("Importar CSV") }, onClick = {
                            menuOpen = false
                            importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                        })
                        DropdownMenuItem(text = { Text("Acerca de") }, onClick = { menuOpen = false; showAbout = true })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Inventario, onClick = { tab = Tab.Inventario },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Mis pinturas") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Catalogo, onClick = { tab = Tab.Catalogo },
                    icon = { Icon(Icons.Default.List, contentDescription = null) }, label = { Text("Catálogo") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Escanear, onClick = { tab = Tab.Escanear },
                    icon = { Icon(ScanIcon, contentDescription = null) }, label = { Text("Escanear") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (tab) {
                Tab.Inventario -> InventoryScreen(
                    vm,
                    onEdit = { editing = it },
                    onGoCatalog = { tab = Tab.Catalogo },
                    onGoScan = { tab = Tab.Escanear },
                )
                Tab.Catalogo -> CatalogList(
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
                Tab.Escanear -> ScanScreen(
                    vm,
                    onLinkBarcode = { linking = it },
                    onNotFound = { tab = Tab.Catalogo },
                    onAdded = { say(it) },
                )
            }
        }
    }

    editing?.let { p ->
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
        )
    }

    linking?.let { code ->
        Dialog(onDismissRequest = { linking = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
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

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Cerrar") } },
            title = { Text("Estante de Pinturas") },
            text = {
                Text(
                    "Versión ${BuildConfig.VERSION_NAME}\n\n" +
                        "Catálogo de ${vm.catalog.size} pinturas de miniaturas tomado del proyecto miniature-paints de Rick Fleuren (licencia MIT). " +
                        "Los colores son aproximados.\n\n" +
                        "El reconocimiento de etiquetas y códigos de barras usa ML Kit de Google y funciona sin internet. " +
                        "Tu inventario se guarda solo en este teléfono; usa Exportar para respaldarlo."
                )
            },
        )
    }
}
