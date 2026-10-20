package app.nouri.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nouri.core.model.ConfidenceLevel
import app.nouri.data.prefs.ThemeMode

/** Warm, food-friendly palette: herb green primary, terracotta secondary, cream surfaces. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6B4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE8D9),
    onPrimaryContainer = Color(0xFF0B2E1E),
    secondary = Color(0xFFA9531D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADCC8),
    onSecondaryContainer = Color(0xFF3A1704),
    tertiary = Color(0xFF5B5FA8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1E0FF),
    onTertiaryContainer = Color(0xFF161A60),
    background = Color(0xFFFBF8F4),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFBF8F4),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF2ECE4),
    onSurfaceVariant = Color(0xFF5F574E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F3ED),
    surfaceContainer = Color(0xFFF4EEE7),
    surfaceContainerHigh = Color(0xFFEEE7DF),
    surfaceContainerHighest = Color(0xFFE8E1D8),
    outline = Color(0xFF8C8177),
    outlineVariant = Color(0xFFD8CDC1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD1AE),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF1E4D37),
    onPrimaryContainer = Color(0xFFCFE8D9),
    secondary = Color(0xFFF0A273),
    onSecondary = Color(0xFF4A2205),
    secondaryContainer = Color(0xFF6A3412),
    onSecondaryContainer = Color(0xFFFADCC8),
    tertiary = Color(0xFFC1C2FF),
    onTertiary = Color(0xFF2A2E76),
    tertiaryContainer = Color(0xFF42468E),
    onTertiaryContainer = Color(0xFFE1E0FF),
    background = Color(0xFF14120F),
    onBackground = Color(0xFFECE6DE),
    surface = Color(0xFF14120F),
    onSurface = Color(0xFFECE6DE),
    surfaceVariant = Color(0xFF2A2621),
    onSurfaceVariant = Color(0xFFCFC5BA),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF1C1A17),
    surfaceContainer = Color(0xFF211E1A),
    surfaceContainerHigh = Color(0xFF2B2824),
    surfaceContainerHighest = Color(0xFF36322D),
    outline = Color(0xFF9A8F84),
    outlineVariant = Color(0xFF4A443D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Colours outside the Material roles: macros and confidence. Chosen to stay distinguishable for common colour-vision deficiencies and are never the only signal. */
@Immutable
data class NouriExtraColors(
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val fibre: Color,
    val higherConfidence: Color,
    val moderateConfidence: Color,
    val lowConfidence: Color,
    val simulatedBanner: Color,
    val onSimulatedBanner: Color,
) {
    fun confidence(level: ConfidenceLevel): Color = when (level) {
        ConfidenceLevel.HIGHER -> higherConfidence
        ConfidenceLevel.MODERATE -> moderateConfidence
        ConfidenceLevel.LOW -> lowConfidence
    }
}

private val LightExtras = NouriExtraColors(
    protein = Color(0xFF3F6FC2),
    carbs = Color(0xFFC98A1E),
    fat = Color(0xFFB4561F),
    fibre = Color(0xFF2F6B4F),
    higherConfidence = Color(0xFF2F6B4F),
    moderateConfidence = Color(0xFF9A6A00),
    lowConfidence = Color(0xFFB3261E),
    simulatedBanner = Color(0xFFE1E0FF),
    onSimulatedBanner = Color(0xFF161A60),
)

private val DarkExtras = NouriExtraColors(
    protein = Color(0xFF8FB4F2),
    carbs = Color(0xFFF2C26B),
    fat = Color(0xFFF0A273),
    fibre = Color(0xFF8FD1AE),
    higherConfidence = Color(0xFF8FD1AE),
    moderateConfidence = Color(0xFFF2C26B),
    lowConfidence = Color(0xFFF2B8B5),
    simulatedBanner = Color(0xFF42468E),
    onSimulatedBanner = Color(0xFFE1E0FF),
)

private val LocalExtraColors = staticCompositionLocalOf { LightExtras }

private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

private val NouriTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Body, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Body, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = Body),
        bodyMedium = base.bodyMedium.copy(fontFamily = Body),
        bodySmall = base.bodySmall.copy(fontFamily = Body),
        labelLarge = base.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.SemiBold),
    )
}

/** Large tabular numerals for calorie and macro read-outs. */
val NumberStyle = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp)

private val NouriShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NouriTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = colors, typography = NouriTypography, shapes = NouriShapes, content = content)
    }
}

object NouriTheme {
    val extra: NouriExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}
