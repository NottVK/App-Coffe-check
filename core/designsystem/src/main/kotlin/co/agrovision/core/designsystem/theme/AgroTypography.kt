package co.agrovision.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Tipografía (§11.3).
 *
 * | Rol | Familia | Por qué |
 * |---|---|---|
 * | Títulos | Archivo Expanded 600 | Presencia en encabezados sin robar espacio a los datos. Solo H1 y H2. |
 * | Interfaz | Archivo 400/500/600 | Legible en tamaños densos. |
 * | Datos | IBM Plex Mono 400/500 | Confianzas, versiones, coordenadas. **Cifras tabulares alineadas.** |
 *
 * La monoespaciada para datos no es preferencia estética. Una lista de
 * confianzas —`0.91`, `0.07`, `0.02`— con cifras de ancho variable obliga a leer
 * número por número; con cifras tabulares la columna se escanea de un vistazo.
 *
 * ⚠️ **Falta añadir los archivos de fuente.** Hasta que estén en
 * `core/designsystem/src/main/res/font/`, las tres familias caen a las del
 * sistema y la app se ve correcta pero genérica. Ver README §Tipografía.
 */
object AgroFonts {

    /**
     * Sustituir por `FontFamily(Font(R.font.archivo_expanded_semibold, FontWeight.SemiBold))`
     * al añadir el archivo.
     */
    val Display: FontFamily = FontFamily.SansSerif

    val Sans: FontFamily = FontFamily.SansSerif

    /** Sustituir por IBM Plex Mono. Debe tener `tnum` activo por defecto. */
    val Mono: FontFamily = FontFamily.Monospace
}

/**
 * Escala en razón 1.2 desde 14 px base — densa, de herramienta de trabajo.
 *
 * En el móvil se sube el cuerpo a 16 sp respecto a los 14 de la consola web:
 * la consola se usa sentado frente a un monitor, esto se usa de pie en el lote,
 * a pleno sol y con el brazo estirado.
 */
internal object AgroTextSizes {
    val Xs = 12.sp
    val Sm = 14.sp
    val Base = 16.sp
    val Lg = 19.sp
    val Xl = 23.sp
    val Xxl = 28.sp
    val Display = 34.sp
}

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val AgroTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Display,
        lineHeight = 40.sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineLarge = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Xxl,
        lineHeight = 34.sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineMedium = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Xl,
        lineHeight = 28.sp,
        lineHeightStyle = TightLineHeight,
    ),
    titleLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Lg,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = AgroTextSizes.Base,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Base,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = AgroTextSizes.Xs,
        lineHeight = 16.sp,
    ),
    // Datos: confianzas, versiones de modelo, coordenadas, identificadores.
    bodySmall = TextStyle(
        fontFamily = AgroFonts.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Xs,
        lineHeight = 16.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AgroFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 18.sp,
    ),
)
