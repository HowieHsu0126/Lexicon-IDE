package dev.lexicon.ide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.ReaderFont
import dev.lexicon.domain.ReaderPreferences
import dev.lexicon.domain.ReadingComfort
import dev.lexicon.ide.R

val ReadingOpenDyslexic = FontFamily(Font(R.font.open_dyslexic_regular))

@Composable
internal fun readerBackground(prefs: ReaderPreferences): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    if (prefs.comfort == ReadingComfort.HIGH_CONTRAST) return if (dark) Color(0xFF171820) else Color.White
    if (prefs.comfort == ReadingComfort.NIGHT_PAPER) return Color(0xFF2D2825)
    return when (prefs.background) {
        "paper" -> if (dark) Color(0xFF2D2825) else Color(0xFFF4EDDF)
        "sage" -> if (dark) Color(0xFF26312C) else Color(0xFFEDF2E9)
        "mist" -> if (dark) Color(0xFF252E35) else Color(0xFFEEF2F4)
        else -> scheme.background
    }
}

@Composable
internal fun readerTextStyle(prefs: ReaderPreferences): TextStyle {
    val dark = readerBackground(prefs).luminance() < 0.5f
    val color = when (prefs.comfort) {
        ReadingComfort.HIGH_CONTRAST -> if (dark) Color.White else Color(0xFF111111)
        ReadingComfort.LOW_STRESS -> if (dark) Color(0xFFE7E5E0) else Color(0xFF343630)
        ReadingComfort.NIGHT_PAPER -> Color(0xFFE7DCCF)
        else -> if (dark) Color(0xFFF1EFE8) else Color(0xFF20201D)
    }
    val size = prefs.sizeSp.takeIf { it.isFinite() }?.coerceIn(14f, 32f) ?: 18f
    val line = prefs.lineHeight.takeIf { it.isFinite() }?.coerceIn(1.2f, 3f) ?: 1.8f
    val spacing = prefs.letterSpacingSp.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: 0f
    return TextStyle(color = color, fontSize = size.sp, lineHeight = (size * line).sp,
        letterSpacing = spacing.sp, fontFamily = when (prefs.font) {
            ReaderFont.MONO -> EditorMono
            ReaderFont.SERIF -> ReadingSerif
            ReaderFont.SYSTEM -> FontFamily.SansSerif
            ReaderFont.OPEN_DYSLEXIC -> ReadingOpenDyslexic
        })
}
