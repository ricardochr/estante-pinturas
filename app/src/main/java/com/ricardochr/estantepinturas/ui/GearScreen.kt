@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.gearAdvice
import com.ricardochr.estantepinturas.nozzleText

private val NOZZLES = listOf(0.15, 0.2, 0.3, 0.35, 0.4, 0.5, 0.8)

/** Mi equipo: aerógrafos (con su boquilla) y compresor, para que las recomendaciones los tomen en cuenta. */
@Composable
fun GearScreen(vm: MainViewModel, onClose: () -> Unit, say: (String) -> Unit) {
    var adding by remember { mutableStateOf<String?>(null) } // "airbrush" o "compressor"
    val kit = vm.gearKit
    Box(Modifier.fillMaxSize()) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text("Mi equipo") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Con tus aerógrafos registrados, la Paleta te dice en qué zonas conviene usarlos, con qué boquilla, presión y dilución para cada pintura.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionTitle("Aerógrafos", Modifier.padding(0.dp))
                    if (kit.airbrushes.isEmpty()) {
                        Text("No has registrado aerógrafos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    kit.airbrushes.forEach { g ->
                        GearRow(g.name.ifBlank { "Aerógrafo" }, "Boquilla " + nozzleText(g.nozzle)) { vm.removeGear(g.id); say("Quitado: ${g.name}") }
                    }
                    OutlinedButton(onClick = { adding = "airbrush" }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar aerógrafo") }

                    SectionTitle("Compresor", Modifier.padding(0.dp))
                    val compressors = vm.gear.filter { it.kind == "compressor" }
                    if (compressors.isEmpty()) {
                        Text("No has registrado compresor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    compressors.forEach { g ->
                        GearRow(g.name.ifBlank { "Compresor" }, if (g.tank) "Con tanque" else "Sin tanque") { vm.removeGear(g.id); say("Quitado: ${g.name}") }
                    }
                    if (compressors.isEmpty()) {
                        OutlinedButton(onClick = { adding = "compressor" }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar compresor") }
                    }

                    SectionTitle("¿Te conviene algo más?", Modifier.padding(0.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            gearAdvice(kit).forEach { t ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodySmall)
                                    Text(t, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Aerógrafo o pincel", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Aerógrafo: imprimación, zenital, bases de zonas grandes, degradados suaves (piel, capas, superficies lisas), brillos de luz (OSL) y barniz. " +
                                    "Pincel: detalles, ojos, bordes definidos, texturas, pincel seco, lavados en recovecos y luces de arista.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    adding?.let { kind ->
        var name by remember(kind) { mutableStateOf("") }
        var nozzle by remember(kind) { mutableStateOf(0.3) }
        var tank by remember(kind) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(if (kind == "airbrush") "Nuevo aerógrafo" else "Compresor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre o modelo") },
                        placeholder = { Text(if (kind == "airbrush") "Gaahleri Mobius…" else "UTYUTY Thunder…") },
                        singleLine = true,
                    )
                    if (kind == "airbrush") {
                        Text("Boquilla", style = MaterialTheme.typography.labelLarge)
                        NOZZLES.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { n -> FilterChip(selected = nozzle == n, onClick = { nozzle = n }, label = { Text(nozzleText(n)) }) }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tiene tanque de aire", Modifier.weight(1f))
                            Switch(checked = tank, onCheckedChange = { tank = it })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.addGear(kind, name, if (kind == "airbrush") nozzle else 0.0, kind == "compressor" && tank)
                    adding = null
                    say("Guardado en tu equipo")
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun GearRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Quitar") }
        }
    }
}
