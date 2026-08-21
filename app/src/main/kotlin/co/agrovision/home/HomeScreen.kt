package co.agrovision.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.agrovision.R
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.designsystem.theme.AgroColors
import co.agrovision.core.designsystem.theme.AgroTheme

/**
 * Pantalla de inicio.
 *
 * ## Por qué existe, si §6.4 dice que el primer contacto es la cámara
 *
 * Sigue siéndolo en intención: **«Analizar» ocupa el centro de la pantalla y es
 * imposible no verlo.** Lo que cambia es que la app deja de ser un callejón sin
 * salida cuando el escaneo no puede dar respuesta —sin modelo instalado, sin luz
 * suficiente, o cuando el productor solo quiere consultar una ficha.
 *
 * La regla que sigue vigente y no se negocia: **aquí no hay inicio de sesión.**
 * Todo lo de esta pantalla funciona sin cuenta y sin señal.
 */
@Composable
fun HomeScreen(
    pendingCount: Int,
    catalogCount: Int,
    onAnalyze: () -> Unit,
    onCatalog: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(AgroTheme.dimensions.gutter),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        Text(
            text = stringResource(R.string.home_saludo),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = AgroTheme.dimensions.gutter),
        )
        Text(
            text = stringResource(R.string.home_subtitulo),
            style = MaterialTheme.typography.bodyLarge,
            color = AgroTheme.semantic.mutedText,
        )

        AnalyzeCard(onClick = onAnalyze)

        Row(horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight)) {
            ShortcutCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.home_catalogo),
                subtitle = stringResource(R.string.home_catalogo_detalle, catalogCount),
                onClick = onCatalog,
                modifier = Modifier.weight(1f),
            )
            ShortcutCard(
                icon = Icons.Filled.History,
                title = stringResource(R.string.home_historial),
                subtitle = if (pendingCount > 0) {
                    stringResource(R.string.home_historial_pendientes, pendingCount)
                } else {
                    stringResource(R.string.home_historial_detalle)
                },
                onClick = onHistory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * La acción principal. Un solo botón, enorme, imposible de no encontrar.
 *
 * El flujo de un solo botón es un requisito derivado del perfil de usuario
 * (§2.1: alfabetización digital variable, muchos operan principalmente con
 * WhatsApp), no una simplificación estética.
 */
@Composable
private fun AnalyzeCard(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.home_analizar_descripcion)

    // Latido lentísimo. Es el único movimiento de la pantalla y existe para
    // señalar dónde tocar, no para decorar: 2,4 s por ciclo y un 3 % de escala,
    // por debajo del umbral de distracción y respetuoso con la batería.
    val transition = rememberInfiniteTransition(label = "latido")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "escala",
    )

    Card(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AgroTheme.dimensions.gutterWide),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        ) {
            Box(
                modifier = Modifier
                    .size(LEAF_CIRCLE)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                LeafMark(modifier = Modifier.size(LEAF_MARK))
            }

            Text(
                text = stringResource(R.string.home_analizar),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.home_analizar_ayuda),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AgroTheme.dimensions.gutterWide),
            )
        }
    }
}

/**
 * Hoja de café dibujada con `Path`, no un PNG.
 *
 * Escala a cualquier densidad sin pesar nada y sin necesitar cinco variantes en
 * `mipmap-*`. En una app con presupuesto de 40 MB, cada activo que no se empaqueta
 * cuenta (§3.2 RNF).
 *
 * La nervadura central y las secundarias son lo que la hace legible como hoja de
 * café y no como una gota: la lámina es elíptica y acuminada, con nervios
 * marcados.
 */
@Composable
private fun LeafMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val leaf = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.92f, h * 0.26f, w * 0.92f, h * 0.68f, w * 0.5f, h * 0.94f)
            cubicTo(w * 0.08f, h * 0.68f, w * 0.08f, h * 0.26f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(path = leaf, color = Color.White)

        // Nervio central
        drawLine(
            color = AgroColors.Brand600,
            start = Offset(w * 0.5f, h * 0.12f),
            end = Offset(w * 0.5f, h * 0.9f),
            strokeWidth = w * 0.035f,
        )
        // Nervios secundarios, alternos
        listOf(0.30f, 0.46f, 0.62f).forEachIndexed { index, y ->
            val spread = 0.30f - index * 0.06f
            drawLine(
                color = AgroColors.Brand600,
                start = Offset(w * 0.5f, h * y),
                end = Offset(w * (0.5f + spread), h * (y + 0.12f)),
                strokeWidth = w * 0.022f,
            )
            drawLine(
                color = AgroColors.Brand600,
                start = Offset(w * 0.5f, h * (y + 0.06f)),
                end = Offset(w * (0.5f - spread), h * (y + 0.18f)),
                strokeWidth = w * 0.022f,
            )
        }
    }
}

@Composable
private fun ShortcutCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(AgroTheme.dimensions.cardRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(AgroTheme.dimensions.gutter),
            verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
    }
}

private val LEAF_CIRCLE = 120.dp
private val LEAF_MARK = 76.dp
private const val BREATH_MS = 2_400
