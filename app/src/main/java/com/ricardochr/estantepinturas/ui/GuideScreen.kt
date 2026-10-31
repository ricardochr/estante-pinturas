package com.ricardochr.estantepinturas.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricardochr.estantepinturas.DILUTION_TABLE
import com.ricardochr.estantepinturas.GUIDE
import com.ricardochr.estantepinturas.GuideArticle
import com.ricardochr.estantepinturas.matchesWords
import com.ricardochr.estantepinturas.norm
import com.ricardochr.estantepinturas.queryWords

/** Guía de técnicas: secciones con artículos que se abren al tocarlos, y tabla de diluciones. */
@Composable
fun GuideScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val words = queryWords(query)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(query, { query = it }, "Buscar técnica: veladura, ojos, aerógrafo…")
            }
        }
        if (words.isEmpty()) {
            item { DilutionTableCard() }
        }
        GUIDE.forEach { section ->
            val articles = section.articles.filter { a ->
                words.isEmpty() || matchesWords(norm(a.title + " " + a.summary + " " + a.steps.joinToString(" ") + " " + a.tip + " " + a.tools), words)
            }
            if (articles.isNotEmpty()) {
                item(key = "h-" + section.title) {
                    Text(
                        section.title.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(articles, key = { "a-" + it.title }) { a ->
                    ArticleCard(a, expanded = open == a.title || words.isNotEmpty()) {
                        open = if (open == a.title) null else a.title
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(a: GuideArticle, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onToggle),
    ) {
        Column(
            Modifier
                .padding(14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(a.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                a.steps.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (a.tools.isNotBlank()) {
                    Text("Necesitas: ${a.tools}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (a.tip.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("Truco: ${a.tip}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DilutionTableCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabla de diluciones", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "Pintura : diluyente, por marca. ★ = indicación del fabricante; el resto es práctica habitual. Cada pintura de tu inventario tiene su propia guía.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                DILUTION_TABLE.forEach { (use, rows) ->
                    Text(use, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    rows.forEach { (kind, ratio) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(kind, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(ratio, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                    }
                }
                Text(
                    "Ajusta a ojo: para aerógrafo, consistencia de leche descremada; para veladura, casi como tinta. Un bote viejo o espeso necesita más diluyente.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
