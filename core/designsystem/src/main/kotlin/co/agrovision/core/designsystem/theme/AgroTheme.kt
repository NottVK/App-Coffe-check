package co.agrovision.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.agrovision.core.common.AgroConstants

/**
 * Tres apariencias, no dos.
 *
 * La tercera —modo exterior— no es un capricho: el contexto de uso descrito en
 * §2.1 es «a pleno sol, de pie en el lote, con una mano, guantes o manos
 * sucias». Bajo luz solar directa la pantalla de un teléfono de gama baja pierde
 * buena parte de su contraste efectivo, y una interfaz que se lee perfectamente
 * en una oficina se vuelve ilegible ahí.
 */
enum class AgroAppearance {
    LIGHT,
    DARK,
    OUTDOOR,
}

@Immutable
data class AgroDimensions(
    val minTouchTarget: Dp = AgroConstants.Ui.MIN_TOUCH_TARGET_DP.dp,
    /** El disparador de la cámara. Mucho mayor que el mínimo de accesibilidad:
     *  se acciona con una mano, sin mirar, posiblemente con guantes. */
    val primaryAction: Dp = AgroConstants.Ui.PRIMARY_ACTION_DP.dp,
    val gutter: Dp = 16.dp,
    val gutterTight: Dp = 8.dp,
    val gutterWide: Dp = 24.dp,
    val cardRadius: Dp = 12.dp,
    val severityNotchHeight: Dp = 3.dp,
    val severityNotchHeightProminent: Dp = 5.dp,
    val borderWidth: Dp = 1.dp,
)

@Immutable
data class AgroSemanticColors(
    val severity: SeverityRamp,
    val spark: androidx.compose.ui.graphics.Color,
    val dataText: androidx.compose.ui.graphics.Color,
    val mutedText: androidx.compose.ui.graphics.Color,
    val divider: androidx.compose.ui.graphics.Color,
    /**
     * Fondo obligatorio detrás de cualquier imagen de espécimen. Neutro por
     * contrato: si alguien pone marca aquí, la percepción del tono de la lesión
     * se desplaza (§11.1).
     */
    val specimenBackdrop: androidx.compose.ui.graphics.Color,
)

val LocalAgroDimensions = staticCompositionLocalOf { AgroDimensions() }

val LocalAgroSemanticColors = staticCompositionLocalOf {
    AgroSemanticColors(
        severity = SeverityRamp.Standard,
        spark = AgroColors.Spark400,
        dataText = AgroColors.Bench900,
        mutedText = AgroColors.Bench600,
        divider = AgroColors.Bench200,
        specimenBackdrop = AgroColors.Bench100,
    )
}

val LocalAgroAppearance = staticCompositionLocalOf { AgroAppearance.LIGHT }

private val LightScheme = lightColorScheme(
    primary = AgroColors.Brand500,
    onPrimary = AgroColors.Bench50,
    primaryContainer = AgroColors.Brand400,
    onPrimaryContainer = AgroColors.Bench950,
    secondary = AgroColors.Bench700,
    onSecondary = AgroColors.Bench50,
    background = AgroColors.Bench50,
    onBackground = AgroColors.Bench900,
    surface = AgroColors.Bench50,
    onSurface = AgroColors.Bench900,
    surfaceVariant = AgroColors.Bench100,
    onSurfaceVariant = AgroColors.Bench600,
    outline = AgroColors.Bench300,
    outlineVariant = AgroColors.Bench200,
    error = AgroColors.Error,
    onError = AgroColors.Bench50,
)

private val DarkScheme = darkColorScheme(
    primary = AgroColors.Brand400,
    onPrimary = AgroColors.Bench950,
    primaryContainer = AgroColors.Brand600,
    onPrimaryContainer = AgroColors.DarkText,
    secondary = AgroColors.Bench300,
    onSecondary = AgroColors.Bench950,
    background = AgroColors.DarkSurface,
    onBackground = AgroColors.DarkText,
    surface = AgroColors.DarkSurface,
    onSurface = AgroColors.DarkText,
    surfaceVariant = AgroColors.DarkSurfaceElevated,
    onSurfaceVariant = AgroColors.DarkTextMuted,
    outline = AgroColors.Bench600,
    outlineVariant = AgroColors.Bench700,
    error = AgroColors.Sev3,
    onError = AgroColors.Bench950,
)

/**
 * Modo exterior: blanco puro contra casi negro.
 *
 * Se sacrifica el refinamiento del banco neutro a cambio del contraste máximo
 * que la pantalla puede dar. Es un intercambio deliberado y solo aplica cuando
 * el sensor de luz detecta condiciones de exterior o el productor lo fija a mano.
 */
private val OutdoorScheme = lightColorScheme(
    primary = AgroColors.BrandOutdoor,
    onPrimary = AgroColors.OutdoorBackground,
    primaryContainer = AgroColors.BrandOutdoor,
    onPrimaryContainer = AgroColors.OutdoorBackground,
    secondary = AgroColors.Bench900,
    onSecondary = AgroColors.OutdoorBackground,
    background = AgroColors.OutdoorBackground,
    onBackground = AgroColors.OutdoorText,
    surface = AgroColors.OutdoorBackground,
    onSurface = AgroColors.OutdoorText,
    surfaceVariant = AgroColors.Bench100,
    onSurfaceVariant = AgroColors.Bench900,
    outline = AgroColors.Bench700,
    outlineVariant = AgroColors.Bench400,
    error = AgroColors.Sev4Outdoor,
    onError = AgroColors.OutdoorBackground,
)

@Composable
fun AgroTheme(
    appearance: AgroAppearance = if (isSystemInDarkTheme()) AgroAppearance.DARK else AgroAppearance.LIGHT,
    content: @Composable () -> Unit,
) {
    val scheme = when (appearance) {
        AgroAppearance.LIGHT -> LightScheme
        AgroAppearance.DARK -> DarkScheme
        AgroAppearance.OUTDOOR -> OutdoorScheme
    }

    val semantic = when (appearance) {
        AgroAppearance.LIGHT -> AgroSemanticColors(
            severity = SeverityRamp.Standard,
            spark = AgroColors.Spark400,
            dataText = AgroColors.Bench900,
            mutedText = AgroColors.Bench600,
            divider = AgroColors.Bench200,
            specimenBackdrop = AgroColors.Bench100,
        )

        AgroAppearance.DARK -> AgroSemanticColors(
            severity = SeverityRamp.Dark,
            spark = AgroColors.Spark400,
            dataText = AgroColors.DarkText,
            mutedText = AgroColors.DarkTextMuted,
            divider = AgroColors.Bench700,
            specimenBackdrop = AgroColors.DarkSurfaceElevated,
        )

        AgroAppearance.OUTDOOR -> AgroSemanticColors(
            severity = SeverityRamp.Outdoor,
            spark = AgroColors.Spark400,
            dataText = AgroColors.OutdoorText,
            mutedText = AgroColors.Bench700,
            divider = AgroColors.Bench400,
            specimenBackdrop = AgroColors.Bench100,
        )
    }

    val dimensions = when (appearance) {
        // En exterior, bordes más gruesos: la separación por color deja de
        // funcionar cuando el sol lava la pantalla, y la geometría no.
        AgroAppearance.OUTDOOR -> AgroDimensions(borderWidth = 2.dp)
        else -> AgroDimensions()
    }

    CompositionLocalProvider(
        LocalAgroDimensions provides dimensions,
        LocalAgroSemanticColors provides semantic,
        LocalAgroAppearance provides appearance,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AgroTypography,
            content = content,
        )
    }
}

/** Atajos de lectura. Evitan `LocalAgroSemanticColors.current` repartido por
 *  todos los Composables. */
object AgroTheme {
    val dimensions: AgroDimensions
        @Composable @ReadOnlyComposable get() = LocalAgroDimensions.current

    val semantic: AgroSemanticColors
        @Composable @ReadOnlyComposable get() = LocalAgroSemanticColors.current

    val appearance: AgroAppearance
        @Composable @ReadOnlyComposable get() = LocalAgroAppearance.current
}
