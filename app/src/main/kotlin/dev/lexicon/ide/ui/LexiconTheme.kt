package dev.lexicon.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.ThemeMode
import dev.lexicon.ide.R

val EditorMono = FontFamily(Font(R.font.jetbrains_mono))
val ReadingSerif = FontFamily(Font(R.font.source_serif))

@Composable
internal fun LexiconTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val activity = LocalContext.current.findActivity()
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val targetColors = if (dark) darkColorScheme(
        primary = Color(0xFFBD93F9), onPrimary = Color(0xFF282A36),
        primaryContainer = Color(0xFF514367), onPrimaryContainer = Color(0xFFF8F8F2),
        secondary = Color(0xFFFF79C6), onSecondary = Color(0xFF282A36),
        secondaryContainer = Color(0xFF514367), onSecondaryContainer = Color(0xFFF8F8F2),
        tertiary = Color(0xFFF1FA8C), onTertiary = Color(0xFF282A36),
        background = if (settings.warmPaper) Color(0xFF2D2825) else Color(0xFF282A36),
        surface = if (settings.warmPaper) Color(0xFF2D2825) else Color(0xFF282A36),
        surfaceContainer = if (settings.warmPaper) Color(0xFF39322D) else Color(0xFF343644),
        surfaceContainerLowest = Color(0xFF20222C), surfaceContainerLow = Color(0xFF2D2F3D),
        surfaceContainerHigh = Color(0xFF383B4C), surfaceContainerHighest = Color(0xFF44475A),
        surfaceTint = Color(0xFFBD93F9),
        surfaceVariant = if (settings.warmPaper) Color(0xFF38312C) else Color(0xFF333544),
        onSurface = Color(0xFFF8F8F2), onBackground = Color(0xFFF8F8F2),
        onSurfaceVariant = Color(0xFFA7ABC0), outline = Color(0xFF8B90AA),
        outlineVariant = Color(0xFF4B4E60), error = Color(0xFFFF8B8B),
    ) else lightColorScheme(
        // Claude's terra-cotta #D97757 is preserved as tertiary; controls use an AA-safe shade.
        primary = Color(0xFFA34F35), onPrimary = Color.White,
        primaryContainer = Color(0xFFF3DFD5), onPrimaryContainer = Color(0xFF642D1D),
        secondary = Color(0xFF8A5037), onSecondary = Color.White, tertiary = Color(0xFFD97757),
        secondaryContainer = Color(0xFFEAD8CF), onSecondaryContainer = Color(0xFF593A2E),
        background = Color(0xFFFAF9F5), surface = Color(0xFFFAF9F5),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F3ED),
        surfaceContainer = Color(0xFFF0EDE6), surfaceContainerHigh = Color(0xFFEDEAE1),
        surfaceContainerHighest = Color(0xFFE5E1D8), surfaceTint = Color(0xFFD97757),
        surfaceVariant = Color(0xFFF0EDE6), onSurface = Color(0xFF141413),
        onBackground = Color(0xFF141413), onSurfaceVariant = Color(0xFF62615B),
        outline = Color(0xFF79786F), outlineVariant = Color(0xFFD9D7CE), error = Color(0xFFAA3027),
    )
    // Small color transitions remove the flash when the user changes theme. Compose honors
    // the system animator duration scale, including zero-duration reduced animation settings.
    @Composable fun transition(color: Color, label: String): Color {
        val value by animateColorAsState(color, tween(240), label = label)
        return value
    }
    val colors = targetColors.copy(
        primary = transition(targetColors.primary, "primary"),
        onPrimary = transition(targetColors.onPrimary, "onPrimary"),
        primaryContainer = transition(targetColors.primaryContainer, "primaryContainer"),
        onPrimaryContainer = transition(targetColors.onPrimaryContainer, "onPrimaryContainer"),
        secondary = transition(targetColors.secondary, "secondary"),
        background = transition(targetColors.background, "background"),
        surface = transition(targetColors.surface, "surface"),
        surfaceContainerLow = transition(targetColors.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainer = transition(targetColors.surfaceContainer, "surfaceContainer"),
        surfaceVariant = transition(targetColors.surfaceVariant, "surfaceVariant"),
        onSurface = transition(targetColors.onSurface, "onSurface"),
        onBackground = transition(targetColors.onBackground, "onBackground"),
        onSurfaceVariant = transition(targetColors.onSurfaceVariant, "onSurfaceVariant"),
        outline = transition(targetColors.outline, "outline"),
        outlineVariant = transition(targetColors.outlineVariant, "outlineVariant"),
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 34.sp),
            headlineSmall = TextStyle(fontFamily = ReadingSerif, fontWeight = FontWeight.Normal, fontSize = 23.sp, lineHeight = 29.sp),
            titleLarge = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 29.sp),
            titleMedium = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 25.sp),
            titleSmall = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontFamily = EditorMono, fontSize = 16.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontFamily = EditorMono, fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontFamily = EditorMono, fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontFamily = EditorMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = EditorMono, fontSize = 11.sp, lineHeight = 17.sp),
            labelSmall = TextStyle(fontFamily = EditorMono, fontSize = 10.sp, lineHeight = 16.sp),
        ), content = content,
    )
}
