package co.agrovision.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agrovision.R
import co.agrovision.core.designsystem.component.LeafCluster
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.component.SeverityStrip
import co.agrovision.core.designsystem.component.SeverityStripDensity
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.feature.catalog.indicativeSeverity
import java.time.LocalTime

/**
 * Pantalla de inicio.
 *
 * ## Por qué existe, si §6.4 dice que el primer contacto es la cámara
 *
 * Sigue siéndolo en intención: **«Analizar» es el bloque más grande y el único
 * en lima: es imposible no verlo.** Lo que cambia es que la app deja de ser un
 * callejón sin salida cuando el escaneo no puede dar respuesta —sin modelo
 * instalado, sin luz suficiente, o cuando el productor solo quiere consultar una
 * ficha.
 *
 * La regla que sigue vigente y no se negocia: **aquí no hay inicio de sesión.**
 * Todo lo de esta pantalla funciona sin cuenta y sin señal.
 *
 * ## Composición
 *
 * Un mosaico arriba (Analizar, fichas, historial) y, debajo, las fotos reales
 * del catálogo: el productor reconoce una plaga por cómo se ve, y ver las fotos
 * desde el inicio le enseña qué puede preguntarle a la app.
 */
@Composable
fun HomeScreen(
    catalogCount: Int,
    featured: List<PestTaxon>,
    onAnalyze: () -> Unit,
    onCatalog: () -> Unit,
    onPestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = AgroTheme.dimensions.gutterWide),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        TopRow(modifier = Modifier.padding(start = gutter, end = gutter, top = gutter))

        Column(
            modifier = Modifier.padding(horizontal = gutter),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(greetingRes()),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.home_subtitulo),
                style = MaterialTheme.typography.bodyLarge,
                color = AgroTheme.semantic.mutedText,
            )
        }

        Bento(
            catalogCount = catalogCount,
            onAnalyze = onAnalyze,
            onCatalog = onCatalog,
            modifier = Modifier.padding(horizontal = gutter),
        )

        if (featured.isNotEmpty()) {
            FeaturedPests(pests = featured, onPestClick = onPestClick, onSeeAll = onCatalog)
        }

    }
}

/** Saludo según la hora. Es lo primero que se lee y suena a persona, no a menú. */
private fun greetingRes(): Int {
    val hour = LocalTime.now().hour
    return when {
        hour < 12 -> R.string.home_saludo_manana
        hour < 19 -> R.string.home_saludo_tarde
        else -> R.string.home_saludo_noche
    }
}

/** Marca a la izquierda. */
@Composable
private fun TopRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_coffecheck),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/**
 * Mosaico: Analizar a la izquierda, más ancho; las fichas a la derecha. El
 * tamaño ya dice qué importa más, antes de leer nada. El historial no está
 * aquí: tiene su pestaña en la barra inferior.
 */
@Composable
private fun Bento(
    catalogCount: Int,
    onAnalyze: () -> Unit,
    onCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnalyzeTile(
            onClick = onAnalyze,
            modifier = Modifier
                .fillMaxWidth(BENTO_WIDTH_FRACTION)
                .height(BENTO_HEIGHT),
        )
    }
}

/**
 * La acción principal. El bloque más grande y el único en lima.
 *
 * El flujo de un solo botón es un requisito derivado del perfil de usuario
 * (§2.1: alfabetización digital variable, muchos operan principalmente con
 * WhatsApp), no una simplificación estética. Todo el bloque es tocable: con
 * guantes se acierta a lo grande.
 */
@Composable
private fun AnalyzeTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.home_analizar_descripcion)
    val semantic = AgroTheme.semantic

    // Latido lentísimo en el anillo de la cámara. Es el único movimiento de la
    // pantalla y existe para señalar dónde tocar, no para decorar.
    val transition = rememberInfiniteTransition(label = "latido")
    val ring by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "anillo",
    )

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(semantic.accent)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        LeafCluster(
            leaf = semantic.onAccent.copy(alpha = 0.07f),
            leafStrong = semantic.onAccent.copy(alpha = 0.12f),
            accent = semantic.onAccent.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 30.dp, y = 6.dp)
                .size(160.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(CAMERA_DOT)
                        .scale(ring)
                        .border(2.dp, semantic.onAccent.copy(alpha = 0.3f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(CAMERA_DOT)
                        .clip(CircleShape)
                        .background(semantic.onAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = semantic.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.home_analizar),
                    style = MaterialTheme.typography.headlineMedium,
                    color = semantic.onAccent,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.home_analizar_ayuda),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.onAccent.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Fichas: el número grande dice «hay con qué consultar» sin leer la etiqueta. */
@Composable
private fun CatalogTile(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val semantic = AgroTheme.semantic
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(semantic.heroStart, semantic.heroEnd)))
            .border(1.dp, semantic.heroBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        TileHeader(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tint = semantic.accent,
            arrowBackground = semantic.accent,
            arrowTint = semantic.onAccent,
        )
        // La cifra, centrada en el espacio que queda bajo la cabecera.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = COUNT_SIZE,
                    lineHeight = COUNT_SIZE,
                ),
                color = semantic.accent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.home_catalogo_unidad),
                style = MaterialTheme.typography.labelLarge,
                color = semantic.heroContent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TileHeader(icon: ImageVector, tint: Color, arrowBackground: Color, arrowTint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(arrowBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = arrowTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Carrusel con las fotos reales del catálogo.
 *
 * El nombre va DEBAJO de la foto y no encima con un degradado: cualquier color
 * sobre la imagen desplaza el tono percibido de la lesión (§11.1).
 */
@Composable
private fun FeaturedPests(
    pests: List<PestTaxon>,
    onPestClick: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val gutter = AgroTheme.dimensions.gutter
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_plagas_comunes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_ver_todas),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pests, key = { it.id }) { pest ->
                PestCard(pest = pest, onClick = { onPestClick(pest.id) })
            }
        }
    }
}

@Composable
private fun PestCard(pest: PestTaxon, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(PEST_CARD_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .background(AgroTheme.semantic.card)
            .clickable(onClick = onClick),
    ) {
        LocalImage(
            source = pest.imageRefs.first(),
            contentDescription = null,
            targetPx = 384,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        SeverityStrip(
            level = pest.kind.indicativeSeverity(),
            density = SeverityStripDensity.COMPACT,
        )
        Text(
            text = pest.commonName,
            style = MaterialTheme.typography.labelLarge,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Hoja de café dibujada con `Path`, no un PNG.
 *
 * Escala a cualquier densidad sin pesar nada y sin necesitar cinco variantes en
 * `mipmap-*`. En una app con presupuesto de 40 MB, cada activo que no se empaqueta
 * cuenta (§3.2 RNF).
 */
@Composable
private fun LeafMark(leaf: Color, vein: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        path.reset()
        path.apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.92f, h * 0.26f, w * 0.92f, h * 0.68f, w * 0.5f, h * 0.94f)
            cubicTo(w * 0.08f, h * 0.68f, w * 0.08f, h * 0.26f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(path = path, color = leaf)

        // Nervio central
        drawLine(
            color = vein,
            start = Offset(w * 0.5f, h * 0.16f),
            end = Offset(w * 0.5f, h * 0.86f),
            strokeWidth = w * 0.07f,
        )
    }
}

private val BENTO_HEIGHT = 180.dp
private const val BENTO_WIDTH_FRACTION = 0.8f
private val TILE_GAP = 12.dp
private val COUNT_SIZE = 64.sp
private val CAMERA_DOT = 56.dp
private val PEST_CARD_WIDTH = 150.dp
private const val BREATH_MS = 2_400
