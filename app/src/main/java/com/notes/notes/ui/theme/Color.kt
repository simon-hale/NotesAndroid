package com.notes.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.notes.notes.core.ThemePalette
import com.notes.notes.core.ThemeSettings

@Immutable
data class NotesExtraColors(
    val brand: Color,
    val brandSoft: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val backgroundOrb: Color,
    val panelTop: Color,
    val panelBottom: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val floatingBarTop: Color,
    val floatingBarBottom: Color,
    val borderStrong: Color,
    val textMuted: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val modalSurface: Color,
    val modalScrim: Color,
)

@Immutable
data class NotesThemeBundle(
    val material: ColorScheme,
    val extra: NotesExtraColors,
)

private data class PaletteSeed(
    val accentLight: Color,
    val accentDark: Color,
    val softLight: Color,
    val softDark: Color,
)

private val DefaultExtraColors = NotesExtraColors(
    brand = Color(0xFF3390EC),
    brandSoft = Color(0x143390EC),
    backgroundTop = Color(0xFFF2F5F8),
    backgroundBottom = Color(0xFFF2F5F8),
    backgroundOrb = Color(0x143390EC),
    panelTop = Color.White,
    panelBottom = Color.White,
    glassTop = Color.White,
    glassBottom = Color.White,
    floatingBarTop = Color.White,
    floatingBarBottom = Color.White,
    borderStrong = Color(0xFFDDE4EC),
    textMuted = Color(0xFF72869A),
    success = Color(0xFF36B37E),
    danger = Color(0xFFE15A63),
    warning = Color(0xFFF0A63A),
    modalSurface = Color(0xFFF8FAFC),
    modalScrim = Color(0x3317212B),
)

val LocalNotesExtraColors = staticCompositionLocalOf { DefaultExtraColors }

fun buildNotesThemeBundle(
    settings: ThemeSettings,
    systemInDarkTheme: Boolean,
): NotesThemeBundle {
    val seed = paletteSeed(settings.palette)
    val isDark = settings.mode.resolveIsDark(systemInDarkTheme)
    val accent = if (isDark) seed.accentDark else seed.accentLight
    val softAccent = if (isDark) seed.softDark else seed.softLight

    val material = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF08111B),
            primaryContainer = Color(0xFF21384B),
            onPrimaryContainer = Color(0xFFEAF5FF),
            secondary = accent,
            onSecondary = Color(0xFF08111B),
            tertiary = Color(0xFF88CAFF),
            background = Color(0xFF0E1621),
            onBackground = Color(0xFFE9F1F8),
            surface = Color(0xFF17212B),
            onSurface = Color(0xFFE9F1F8),
            surfaceVariant = Color(0xFF1E2A37),
            onSurfaceVariant = Color(0xFFA0B2C3),
            outline = Color(0xFF314454),
            error = Color(0xFFFF7A7A),
            onError = Color(0xFF26090B),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE7F2FD),
            onPrimaryContainer = Color(0xFF133C63),
            secondary = accent,
            onSecondary = Color.White,
            tertiary = Color(0xFF5CA8F5),
            background = Color(0xFFF2F5F8),
            onBackground = Color(0xFF17212B),
            surface = Color.White,
            onSurface = Color(0xFF17212B),
            surfaceVariant = Color(0xFFF7F9FB),
            onSurfaceVariant = Color(0xFF6D7F92),
            outline = Color(0xFFD8E1E8),
            error = Color(0xFFE15A63),
            onError = Color.White,
        )
    }

    val extra = if (isDark) {
        NotesExtraColors(
            brand = accent,
            brandSoft = softAccent,
            backgroundTop = Color(0xFF0E1621),
            backgroundBottom = Color(0xFF0E1621),
            backgroundOrb = softAccent,
            panelTop = Color(0xFF17212B),
            panelBottom = Color(0xFF17212B),
            glassTop = Color(0xFF1B2734),
            glassBottom = Color(0xFF1B2734),
            floatingBarTop = Color(0xFF17212B),
            floatingBarBottom = Color(0xFF17212B),
            borderStrong = Color(0xFF2A3A4A),
            textMuted = Color(0xFF9FB1C4),
            success = Color(0xFF43C38B),
            danger = Color(0xFFFF7A7A),
            warning = Color(0xFFF2B64F),
            modalSurface = Color(0xFF141D27),
            modalScrim = Color(0x8A000000),
        )
    } else {
        NotesExtraColors(
            brand = accent,
            brandSoft = softAccent,
            backgroundTop = Color(0xFFF2F5F8),
            backgroundBottom = Color(0xFFF2F5F8),
            backgroundOrb = softAccent,
            panelTop = Color.White,
            panelBottom = Color.White,
            glassTop = Color.White,
            glassBottom = Color.White,
            floatingBarTop = Color.White,
            floatingBarBottom = Color.White,
            borderStrong = Color(0xFFDDE4EC),
            textMuted = Color(0xFF72869A),
            success = Color(0xFF36B37E),
            danger = Color(0xFFE15A63),
            warning = Color(0xFFF0A63A),
            modalSurface = Color(0xFFF8FAFC),
            modalScrim = Color(0x3317212B),
        )
    }
    return NotesThemeBundle(material = material, extra = extra)
}

private fun paletteSeed(palette: ThemePalette): PaletteSeed = when (palette) {
    ThemePalette.BLUE -> PaletteSeed(
        accentLight = Color(0xFF3390EC),
        accentDark = Color(0xFF65B3FF),
        softLight = Color(0x143390EC),
        softDark = Color(0x2065B3FF),
    )
    ThemePalette.EMERALD -> PaletteSeed(
        accentLight = Color(0xFF2DA784),
        accentDark = Color(0xFF61D0AA),
        softLight = Color(0x142DA784),
        softDark = Color(0x2061D0AA),
    )
    ThemePalette.AMBER -> PaletteSeed(
        accentLight = Color(0xFFD8921E),
        accentDark = Color(0xFFFFC96B),
        softLight = Color(0x14D8921E),
        softDark = Color(0x20FFC96B),
    )
    ThemePalette.ROSE -> PaletteSeed(
        accentLight = Color(0xFFD4668D),
        accentDark = Color(0xFFFFA9C5),
        softLight = Color(0x14D4668D),
        softDark = Color(0x20FFA9C5),
    )
    ThemePalette.SAGE -> PaletteSeed(
        accentLight = Color(0xFF739160),
        accentDark = Color(0xFFA7C695),
        softLight = Color(0x14739160),
        softDark = Color(0x20A7C695),
    )
    ThemePalette.ALMOND -> PaletteSeed(
        accentLight = Color(0xFFB28B49),
        accentDark = Color(0xFFE3C88F),
        softLight = Color(0x14B28B49),
        softDark = Color(0x20E3C88F),
    )
}
