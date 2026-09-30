package com.notes.notes.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppStrings
import com.notes.notes.core.AppTab
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private const val SCREEN_SOURCE_Z_INDEX = 0f

@Immutable
data class BottomBarLayoutMetrics(
    /*
     * Enlarged from the previous compact version.
     *
     * 3 × 60dp tab slots + 2 × 7dp rail padding = 194dp resting width.
     * Resting height = 74dp.
     */
    val itemTouchSize: Dp = 60.dp,
    val lensVisualSize: Dp = 60.dp,
    val railPadding: Dp = 7.dp,

    val pressedRailExtraWidth: Dp = 30.dp,

    val screenBottomGap: Dp = 8.dp,
    val contentSeparationGap: Dp = 10.dp,
) {
    val railHeight: Dp
        get() = itemTouchSize + railPadding + railPadding

    fun railWidth(itemCount: Int): Dp =
        itemTouchSize * itemCount.toFloat() +
                railPadding +
                railPadding

    fun reservedContentSpace(
        contentHeight: Dp = railHeight,
    ): Dp =
        contentHeight +
                screenBottomGap +
                contentSeparationGap
}

@Immutable
data class BottomBarLayoutPadding(
    val contentBottom: Dp,
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
    val reservedSpace =
        metrics.reservedContentSpace()

    val padding =
        BottomBarLayoutPadding(
            contentBottom =
                if (visible) {
                    reservedSpace
                } else {
                    0.dp
                },
        )

    /*
     * One shared HazeState is intentional.
     *
     * z = 0 -> real screen content.
     * z = 1 -> rendered bottom rail + tab icons.
     *
     * The moving lens is not itself a source, so when it consumes
     * HazeInput.Sources(state) it sees the already-composited scene:
     *
     * screen -> glass rail -> icons -> liquid lens.
     */
    val hazeState = rememberHazeState()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { base ->
                    if (visible) {
                        base.hazeSource(
                            state = hazeState,
                            zIndex = SCREEN_SOURCE_Z_INDEX,
                            key = "notes-screen-content",
                        )
                    } else {
                        base
                    }
                },
        ) {
            content(padding)
        }

        if (visible) {
            BottomBarHost(
                currentTab = currentTab,
                onSelectTab = onSelectTab,
                strings = strings,
                hazeState = hazeState,
                metrics = metrics,
                modifier = Modifier.align(
                    Alignment.BottomCenter
                ),
            )
        }
    }
}

@Composable
private fun BottomBarHost(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    strings: AppStrings,
    hazeState: HazeState,
    metrics: BottomBarLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars
            )
            .padding(
                top = metrics.contentSeparationGap,
                bottom = metrics.screenBottomGap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassBottomBar(
            currentTab = currentTab,
            onSelectTab = onSelectTab,
            strings = strings,
            hazeState = hazeState,
            metrics = metrics,
        )
    }
}