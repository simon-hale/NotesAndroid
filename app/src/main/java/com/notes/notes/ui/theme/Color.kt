package com.notes.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.notes.notes.core.ThemePalette
import com.notes.notes.core.ThemeSettings

@Immutable
data class NotesExtraColors(
    val backgroundTop: Color,
    val panelTop: Color,
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
)

/** Palette-independent scaffolding of a colour scheme, shared by [NotesExtraColors]. */
private data class SchemeColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
    val onError: Color,
)

/** Brand colour roles that Material 3 derives from the active palette accent. */
private data class BrandColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiary: Color,
)

private val LightScheme = SchemeColors(
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

private val DarkScheme = SchemeColors(
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

/** Fallback used when content is composed outside [NotesTheme]: the default blue light palette. */
private val DefaultExtraColors = buildNotesThemeBundle(ThemeSettings(), systemInDarkTheme = false).extra

val LocalNotesExtraColors = staticCompositionLocalOf { DefaultExtraColors }

fun buildNotesThemeBundle(
    settings: ThemeSettings,
    systemInDarkTheme: Boolean,
): NotesThemeBundle {
    val palette = paletteSeed(settings.palette)
    val isDark = settings.mode.resolveIsDark(systemInDarkTheme)
    val accent = if (isDark) palette.accentDark else palette.accentLight
    val scheme = if (isDark) DarkScheme else LightScheme
    val material = brandColorScheme(scheme, isDark, brandColors(accent, isDark))

    val extra = if (isDark) {
        NotesExtraColors(
            backgroundTop = DarkScheme.background,
            panelTop = DarkScheme.surface,
            borderStrong = Color(0xFF2A3A4A),
            textMuted = Color(0xFF9FB1C4),
            success = Color(0xFF43C38B),
            danger = DarkScheme.error,
            warning = Color(0xFFF2B64F),
            modalSurface = Color(0xFF141D27),
            modalScrim = Color(0x8A000000),
        )
    } else {
        NotesExtraColors(
            backgroundTop = LightScheme.background,
            panelTop = LightScheme.surface,
            borderStrong = Color(0xFFDDE4EC),
            textMuted = Color(0xFF72869A),
            success = Color(0xFF36B37E),
            danger = LightScheme.error,
            warning = Color(0xFFF0A63A),
            modalSurface = Color(0xFFF8FAFC),
            modalScrim = Color(0x3317212B),
        )
    }
    return NotesThemeBundle(material = material, extra = extra)
}

/**
 * Assembles a colour scheme: the Material 3 factory provides every role, then the brand roles
 * follow the active accent and the remaining roles follow the palette-independent scaffolding.
 */
private fun brandColorScheme(
    scheme: SchemeColors,
    isDark: Boolean,
    brand: BrandColors,
): ColorScheme {
    val material = if (isDark) {
        darkColorScheme(
            primary = brand.primary,
            onPrimary = brand.onPrimary,
            primaryContainer = brand.primaryContainer,
            onPrimaryContainer = brand.onPrimaryContainer,
            secondary = brand.primary,
            onSecondary = brand.onPrimary,
            tertiary = brand.tertiary,
        )
    } else {
        lightColorScheme(
            primary = brand.primary,
            onPrimary = brand.onPrimary,
            primaryContainer = brand.primaryContainer,
            onPrimaryContainer = brand.onPrimaryContainer,
            secondary = brand.primary,
            onSecondary = brand.onPrimary,
            tertiary = brand.tertiary,
        )
    }
    return material.withScaffolding(scheme)
}

/** Applies the palette-independent [scheme] scaffolding to the remaining Material 3 roles. */
private fun ColorScheme.withScaffolding(scheme: SchemeColors): ColorScheme = copy(
    background = scheme.background,
    onBackground = scheme.onBackground,
    surface = scheme.surface,
    onSurface = scheme.onSurface,
    surfaceVariant = scheme.surfaceVariant,
    onSurfaceVariant = scheme.onSurfaceVariant,
    outline = scheme.outline,
    error = scheme.error,
    onError = scheme.onError,
)

/**
 * Maps an accent colour onto the Material 3 brand roles so that every palette tint — filled
 * buttons, highlighted pills and banners — follows the selected theme instead of staying blue.
 * The default blue palette therefore keeps reproducing the original scheme exactly.
 */
private fun brandColors(accent: Color, isDark: Boolean): BrandColors {
    val containerTarget = if (isDark) Color.Black else Color.White
    return BrandColors(
        primary = accent,
        onPrimary = onAccentLabel(accent, isDark),
        primaryContainer = accent.blend(containerTarget, if (isDark) 0.72f else 0.88f),
        onPrimaryContainer = accent.blend(if (isDark) Color.White else Color.Black, if (isDark) 0.14f else 0.76f),
        tertiary = accent.blend(Color.White, if (isDark) 0.34f else 0.2f),
    )
}

/**
 * Label colour for a filled accent surface: white on the deep accents of the light scheme, dark
 * ink on the bright ones of the dark scheme.
 */
private fun onAccentLabel(accent: Color, isDark: Boolean): Color =
    if (isDark && accent.luminance() > 0.25f) Color(0xFF08111B) else Color.White

/** Blends [this] towards [target]; `0` keeps the colour, `1` returns the target. */
private fun Color.blend(target: Color, fraction: Float): Color {
    val ratio = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * ratio,
        green = green + (target.green - green) * ratio,
        blue = blue + (target.blue - blue) * ratio,
        alpha = alpha,
    )
}

private fun paletteSeed(palette: ThemePalette): PaletteSeed = when (palette) {
    ThemePalette.BLUE -> PaletteSeed(
        accentLight = Color(0xFF3390EC),
        accentDark = Color(0xFF65B3FF),
    )
    ThemePalette.SAGE -> PaletteSeed(
        accentLight = Color(0xFF739160),
        accentDark = Color(0xFFA7C695),
    )
    ThemePalette.ALMOND -> PaletteSeed(
        accentLight = Color(0xFFB28B49),
        accentDark = Color(0xFFE3C88F),
    )
}
