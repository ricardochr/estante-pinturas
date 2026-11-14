package com.ricardochr.estantepinturas.ui

import com.ricardochr.estantepinturas.tr

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Explicación breve que aparece solo la primera vez que abres una herramienta. [key] identifica la
 * herramienta; se puede volver a ver desde el botón de ayuda de la pantalla con [show].
 */
@Composable
fun FirstTimeHelp(key: String, title: String, steps: List<String>, show: Boolean = false, onClosed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ayuda", Context.MODE_PRIVATE) }
    var open by remember(show) { mutableStateOf(show || !prefs.getBoolean(key, false)) }
    if (!open) return
    fun close() {
        prefs.edit().putBoolean(key, true).apply()
        open = false
        onClosed()
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(tr("Entendido")) } },
    )
}
