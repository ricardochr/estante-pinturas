package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colores de pigmento para dar vida a la interfaz: cada herramienta tiene el suyo. */
object Pigment {
    val Crimson = listOf(Color(0xFFE5394F), Color(0xFFB0124A))
    val Tangerine = listOf(Color(0xFFFF9A3D), Color(0xFFE2522B))
    val Gold = listOf(Color(0xFFF5C33B), Color(0xFFDA8A12))
    val Emerald = listOf(Color(0xFF2FC27E), Color(0xFF0E8A63))
    val Teal = listOf(Color(0xFF21C1C4), Color(0xFF0A7F9E))
    val Cobalt = listOf(Color(0xFF4D7CFF), Color(0xFF2843C4))
    val Violet = listOf(Color(0xFF9A6BFF), Color(0xFF5B30C8))
    val Magenta = listOf(Color(0xFFF2569E), Color(0xFFA9257A))
    val Slate = listOf(Color(0xFF6E7A90), Color(0xFF3F4757))
    val Night = listOf(Color(0xFF2B2F4A), Color(0xFF151827))

    /** Arcoíris de pintura para la franja de la marca. */
    val Rainbow = listOf(Crimson[0], Tangerine[0], Gold[0], Emerald[0], Teal[0], Cobalt[0], Violet[0], Magenta[0])
}

fun diagonal(colors: List<Color>): Brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(900f, 900f))

/**
 * Tarjeta con degradado de pigmento y manchas de pintura translúcidas de fondo.
 * El contenido va en blanco.
 */
@Composable
fun PigmentCard(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(diagonal(colors))
            .drawBehind {
                // Manchas de pintura: dos círculos claros y uno oscuro en las esquinas
                drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.95f, size.height * 0.05f))
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.minDimension * 0.28f, center = Offset(size.width * 0.70f, size.height * 0.95f))
                drawCircle(Color.Black.copy(alpha = 0.08f), radius = size.minDimension * 0.35f, center = Offset(size.width * 0.02f, size.height * 1.0f))
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/** Ícono dentro de un círculo translúcido (sobre degradados) o del color dado (sobre fondos claros). */
@Composable
fun IconBubble(icon: ImageVector, size: Dp = 40.dp, bubble: Color = Color.White.copy(alpha = 0.22f), tint: Color = Color.White) {
    Box(Modifier.size(size).clip(CircleShape).background(bubble), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** Gotas de pintura de colores: el logotipo de Gotario. */
@Composable
fun BrandDrops(dot: Dp = 9.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Pigment.Crimson[0], Pigment.Gold[0], Pigment.Emerald[0], Pigment.Cobalt[0], Pigment.Magenta[0]).forEach {
            Box(Modifier.size(dot).clip(CircleShape).background(it))
        }
    }
}

