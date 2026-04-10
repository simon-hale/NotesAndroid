package com.notes.notes.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.notes.notes.core.ThemeSettings

@Composable
fun NotesTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit,
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val bundle = buildNotesThemeBundle(settings, systemInDarkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.isNavigationBarContrastEnforced = false
            val controller = WindowCompat.getInsetsController(window, view)
            val isDark = settings.mode.resolveIsDark(systemInDarkTheme)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalNotesExtraColors provides bundle.extra) {
        MaterialTheme(
            colorScheme = bundle.material,
            typography = Typography,
            content = content,
        )
    }
}
