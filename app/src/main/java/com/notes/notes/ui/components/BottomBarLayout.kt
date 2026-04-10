package com.notes.notes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppStrings
import com.notes.notes.core.AppTab
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Immutable
data class BottomBarLayoutMetrics(
    val buttonSize: Dp = 56.dp,
    val railPadding: Dp = 10.dp,
    val screenBottomGap: Dp = 8.dp,
    val contentSeparationGap: Dp = 12.dp,
    val cornerRadius: Dp = 34.dp,
) {
    val railHeight: Dp
        get() = buttonSize + railPadding + railPadding

    fun reservedContentSpace(contentHeight: Dp = railHeight): Dp =
        contentHeight + screenBottomGap + contentSeparationGap
}

@Immutable
data class BottomBarLayoutPadding(
    val contentBottom: Dp,
    val snackbarBottom: Dp,
)

internal val DefaultBottomBarMetrics = BottomBarLayoutMetrics()

@Composable
fun BottomBarLayout(
    visible: Boolean,
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    metrics: BottomBarLayoutMetrics = DefaultBottomBarMetrics,
    content: @Composable BoxScope.(BottomBarLayoutPadding) -> Unit,
) {
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reservedSpace = metrics.reservedContentSpace()
    val padding = BottomBarLayoutPadding(
        contentBottom = if (visible) reservedSpace else 0.dp,
        snackbarBottom = if (visible) reservedSpace + navigationBarPadding else navigationBarPadding,
    )

    Box(modifier = modifier.fillMaxSize()) {
        content(padding)

        if (visible) {
            BottomBarHost(
                currentTab = currentTab,
                onSelectTab = onSelectTab,
                strings = strings,
                metrics = metrics,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BottomBarHost(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    strings: AppStrings,
    metrics: BottomBarLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    val extraColors = LocalNotesExtraColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = metrics.contentSeparationGap, bottom = metrics.screenBottomGap),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(metrics.cornerRadius),
            color = extraColors.panelTop.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extraColors.borderStrong.copy(alpha = 0.9f)),
            shadowElevation = 18.dp,
        ) {
            FloatingBottomBar(
                currentTab = currentTab,
                onSelectTab = onSelectTab,
                strings = strings,
                modifier = Modifier.padding(horizontal = metrics.railPadding, vertical = metrics.railPadding),
            )
        }
    }
}
