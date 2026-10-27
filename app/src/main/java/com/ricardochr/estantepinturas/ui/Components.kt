@file:OptIn(ExperimentalMaterial3Api::class)

package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ricardochr.estantepinturas.OwnedEntry
import com.ricardochr.estantepinturas.PAINT_TYPES
import com.ricardochr.estantepinturas.Paint
import com.ricardochr.estantepinturas.parseHex
import com.ricardochr.estantepinturas.validHex

/** Ícono de visor para la pestaña Escanear (no viene en material-icons-core). */
val ScanIcon: ImageVector by lazy {
    ImageVector.Builder("scan", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 8f); lineTo(4f, 5f); lineTo(7f, 5f)
            moveTo(17f, 5f); lineTo(20f, 5f); lineTo(20f, 8f)
            moveTo(20f, 16f); lineTo(20f, 19f); lineTo(17f, 19f)
            moveTo(7f, 19f); lineTo(4f, 19f); lineTo(4f, 16f)
            moveTo(8f, 12f); lineTo(16f, 12f)
        }
    }.build()
}

@Composable
fun Swatch(hex: String, size: Dp = 40.dp, metallic: Boolean = false) {
    val base = Color(parseHex(hex))
    val brush = if (metallic) {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f).compositeOver(base), base, Color.Black.copy(alpha = 0.3f).compositeOver(base)))
    } else {
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f).compositeOver(base), base, Color.Black.copy(alpha = 0.15f).compositeOver(base)))
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(brush)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}

private fun Color.compositeOver(bg: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + bg.red * (1 - a),
        green = this.green * a + bg.green * (1 - a),
        blue = this.blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Borrar búsqueda") }
            }
        },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun BrandChips(brands: List<Pair<String, Int>>, selected: String?, onSelect: (String?) -> Unit) {
    if (brands.size < 2) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Todas") },
            )
        }
        items(brands, key = { it.first }) { (b, n) ->
            FilterChip(
                selected = selected == b,
                onClick = { onSelect(if (selected == b) null else b) },
                label = { Text("$b  $n") },
            )
        }
    }
}

@Composable
fun CodeTag(code: String) {
    if (code.isBlank()) return
    Text(
        code,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Fila de una pintura: muestra, nombre, código, marca/línea/tipo y lo que se ponga a la derecha. */
@Composable
fun PaintRow(
    paint: Paint,
    extra: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Swatch(paint.hex, metallic = paint.type == "Metálica")
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    paint.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CodeTag(paint.code)
            }
            Text(
                paint.subtitle + (if (extra.isNullOrBlank()) "" else " — $extra"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun QtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(36.dp)) { Text("−", fontSize = 18.sp) }
        Text(
            qty.toString(),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(30.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(36.dp)) { Text("+", fontSize = 18.sp) }
    }
}

/** Hoja para editar una pintura del inventario: botes, notas, eliminar. */
@Composable
fun EntrySheet(
    paint: Paint,
    entry: OwnedEntry?,
    onDismiss: () -> Unit,
    onSave: (qty: Int, notes: String) -> Unit,
    onDelete: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var qty by remember { mutableIntStateOf(entry?.qty ?: 1) }
    var notes by remember { mutableStateOf(entry?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Swatch(paint.hex, size = 56.dp, metallic = paint.type == "Metálica")
                Column(Modifier.weight(1f)) {
                    Text(paint.name, style = MaterialTheme.typography.titleLarge)
                    Text(paint.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (paint.code.isNotBlank()) {
                        Spacer(Modifier.height(4.dp)); CodeTag(paint.code)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Botes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                QtyStepper(qty, onMinus = { if (qty > 0) qty-- }, onPlus = { qty++ })
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notas") },
                placeholder = { Text("Casi vacía, para piel, comprar otra…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSave(qty, notes) }, modifier = Modifier.weight(1f)) { Text("Guardar") }
                if (entry != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Eliminar") }
                }
            }
            if (confirmDelete) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("¿Quitar “${paint.name}” del inventario?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onDelete,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Sí, quitar") }
                            TextButton(onClick = { confirmDelete = false }) { Text("No") }
                        }
                    }
                }
            }
        }
    }
}

/** Diálogo para registrar una pintura que no está en el catálogo. */
@Composable
fun CustomPaintDialog(
    initialName: String = "",
    initialCode: String = "",
    onDismiss: () -> Unit,
    onSave: (brand: String, line: String, name: String, code: String, hex: String, type: String, qty: Int) -> Unit,
) {
    var brand by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    var hex by remember { mutableStateOf("#888888") }
    var type by remember { mutableStateOf("Opaca") }
    var qty by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pintura fuera del catálogo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(brand, { brand = it }, label = { Text("Marca") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(line, { line = it }, label = { Text("Línea") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        qty, { qty = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Botes") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(validHex(hex), size = 36.dp)
                    OutlinedTextField(hex, { hex = it.take(7) }, label = { Text("Color (#RRGGBB)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Tipo", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PAINT_TYPES) { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || code.isNotBlank(),
                onClick = { onSave(brand, line, name.ifBlank { code }, code, hex, type, qty.toIntOrNull() ?: 1) },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
