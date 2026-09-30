@file:OptIn(
    dev.chrisbanes.haze.ExperimentalHazeApi::class
)

package com.notes.notes.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalViewConfiguration
import com.notes.notes.core.AppStrings
import com.notes.notes.core.AppTab
import com.notes.notes.ui.theme.LocalNotesExtraColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.ChromaticAberrationMode
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BAR_SOURCE_Z_INDEX = 1f

private const val RELEASE_PROJECTION_SECONDS = 0.07f

private const val MAX_RELEASE_PROJECTION_IN_TABS = 0.72f

private const val LIQUID_PRESS_ACTIVATION_DELAY_MS = 90L

@Immutable
private data class LiquidBottomBarItem(
    val tab: AppTab,
    val icon: ImageVector,
    val label: String,
)

@Composable
internal fun LiquidGlassBottomBar(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    strings: AppStrings,
    hazeState: HazeState,
    metrics: BottomBarLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    val items = remember(
        strings.nav.disk,
        strings.nav.read,
        strings.nav.account,
    ) {
        listOf(
            LiquidBottomBarItem(
                tab = AppTab.DISK,
                icon = Icons.Rounded.FolderOpen,
                label = strings.nav.disk,
            ),
            LiquidBottomBarItem(
                tab = AppTab.READING,
                icon = Icons.AutoMirrored.Rounded.Article,
                label = strings.nav.read,
            ),
            LiquidBottomBarItem(
                tab = AppTab.ACCOUNT,
                icon = Icons.Rounded.ManageAccounts,
                label = strings.nav.account,
            ),
        )
    }

    val selectedIndex =
        items.indexOfFirst {
            it.tab == currentTab
        }.coerceAtLeast(0)

    val selectedIndexState =
        rememberUpdatedState(selectedIndex)

    val onSelectTabState =
        rememberUpdatedState(onSelectTab)

    val colors =
        MaterialTheme.colorScheme

    val extraColors =
        LocalNotesExtraColors.current

    val isDark =
        colors.surface.luminance() < 0.5f

    val density =
        LocalDensity.current

    val touchSlopPx =
        LocalViewConfiguration.current.touchSlop

    val itemWidthPx = with(density) {
        metrics.itemTouchSize.toPx()
    }

    val railPaddingPx = with(density) {
        metrics.railPadding.toPx()
    }

    val lensSizePx = with(density) {
        metrics.lensVisualSize.toPx()
    }

    val pressedRailExtraWidthPx = with(density) {
        metrics.pressedRailExtraWidth.toPx()
    }

    /*
     * Explicit vertical centering.
     *
     * The parent container below is now TopStart rather than CenterStart,
     * so this value is the one and only vertical positioning offset.
     */
    val lensTopPx = with(density) {
        (
                (
                        metrics.railHeight -
                                metrics.lensVisualSize
                        ) / 2f
                ).toPx()
    }

    /*
     * No artificial upward translation is required anymore.
     *
     * The pressed lens grows around its own center and therefore protrudes
     * above and below the rail symmetrically.
     */
    val lensLiftPx = 0f

    val velocityLeadPx = with(density) {
        4.dp.toPx()
    }

    val firstAnchorPx =
        railPaddingPx +
                itemWidthPx / 2f

    val lastAnchorPx =
        firstAnchorPx +
                itemWidthPx *
                items.lastIndex

    fun anchorFor(index: Int): Float =
        firstAnchorPx +
                itemWidthPx * index

    /*
     * Exact source semantics are important here.
     *
     * The base bar becomes z=1 while the real screen is z=0.
     * A Glass inside the z=1 bar source consumes only z<1.
     * A lens outside all sources consumes the complete z0+z1 scene.
     */
    val exactInput = remember(hazeState) {
        HazeInput.Sources(hazeState)
    }

    /*
     * Direct manipulation stays synchronous.
     *
     * This avoids Animatable.snapTo() inside AwaitPointerEventScope and
     * gives one exact coordinate per pointer sample.
     */
    var centerX by remember(
        firstAnchorPx,
        itemWidthPx,
    ) {
        mutableFloatStateOf(
            anchorFor(selectedIndex)
        )
    }

    /*
     * 0 -> settled glass.
     * 1 -> full optical liquid lens.
     */
    val dragProgress = remember {
        Animatable(0f)
    }

    /*
     * Signed normalized horizontal velocity.
     * Used for viscous stretching and moving glare.
     */
    var velocityDeformation by remember {
        mutableFloatStateOf(0f)
    }

    /*
     * Arrival squeeze after the lens reaches its target.
     */
    val settlePulse = remember {
        Animatable(0f)
    }

    var highlightedIndex by remember {
        mutableIntStateOf(selectedIndex)
    }

    var gestureActive by remember {
        mutableStateOf(false)
    }

    var settling by remember {
        mutableStateOf(false)
    }

    var settleJob by remember {
        mutableStateOf<Job?>(null)
    }

    val animationScope =
        rememberCoroutineScope()

    LaunchedEffect(
        selectedIndex,
        gestureActive,
        settling,
        firstAnchorPx,
        itemWidthPx,
    ) {
        if (!gestureActive && !settling) {
            highlightedIndex =
                selectedIndex

            centerX =
                anchorFor(selectedIndex)
        }
    }

    fun settleTo(
        targetIndex: Int,
        initialVelocity: Float = 0f,
        commitSelection: Boolean = false,
    ) {
        settleJob?.cancel()

        settling = true
        gestureActive = false

        highlightedIndex =
            targetIndex

        val targetX =
            anchorFor(targetIndex)

        /*
         * The release decision is already final at this point.
         *
         * Navigation must not wait for the optical material to finish settling.
         * The page changes immediately; all visual cleanup runs independently
         * afterwards.
         */
        if (
            commitSelection &&
            selectedIndexState.value != targetIndex
        ) {
            onSelectTabState.value(
                items[targetIndex].tab
            )
        }

        settleJob =
            animationScope.launch {
                try {
                    /*
                     * All release effects settle in parallel.
                     *
                     * There is deliberately no second "arrival phase":
                     * release -> destination is one continuous motion.
                     */
                    coroutineScope {
                        /*
                         * Position:
                         *
                         * Much stiffer than the previous 620 spring.
                         * Keep release velocity so a flick still feels physically
                         * continuous, but remove most of the visible waiting/bounce.
                         */
                        launch {
                            val position =
                                Animatable(centerX)

                            position.animateTo(
                                targetValue =
                                    targetX,
                                animationSpec =
                                    spring(
                                        dampingRatio =
                                            0.86f,
                                        stiffness =
                                            1100f,
                                    ),
                                initialVelocity =
                                    initialVelocity,
                            ) {
                                centerX =
                                    value
                            }
                        }

                        /*
                         * Kill the expensive Quality motion lens quickly.
                         *
                         * It still visually melts back into the settled lens instead
                         * of disappearing in one frame, but it no longer owns a
                         * separate release stage.
                         */
                        launch {
                            dragProgress.animateTo(
                                targetValue =
                                    0f,
                                animationSpec =
                                    spring(
                                        dampingRatio =
                                            0.95f,
                                        stiffness =
                                            1400f,
                                    ),
                            )
                        }

                        /*
                         * Remove the velocity stretch at the same time.
                         */
                        launch {
                            val deformation =
                                Animatable(
                                    velocityDeformation
                                )

                            deformation.animateTo(
                                targetValue =
                                    0f,
                                animationSpec =
                                    spring(
                                        dampingRatio =
                                            0.88f,
                                        stiffness =
                                            950f,
                                    ),
                            ) {
                                velocityDeformation =
                                    value
                            }
                        }

                        /*
                         * Very small release feedback.
                         *
                         * The previous heavy path waited until everything had
                         * finished and then started another full pulse. That created
                         * another visible intermediate state.
                         *
                         * Now it starts immediately and stays deliberately subtle.
                         */
                        launch {
                            settlePulse.snapTo(
                                0.35f
                            )

                            settlePulse.animateTo(
                                targetValue =
                                    0f,
                                animationSpec =
                                    spring(
                                        dampingRatio =
                                            0.78f,
                                        stiffness =
                                            1000f,
                                    ),
                            )
                        }
                    }

                    /*
                     * Normalize floating states after all parallel animations end.
                     */
                    centerX =
                        targetX

                    velocityDeformation =
                        0f
                } finally {
                    settling =
                        false
                }
            }
    }

    /*
     * Fast tap path.
     *
     * A normal tap never needs the expensive Surface-refraction motion lens.
     * Only move/pulse the lightweight settled lens and commit navigation
     * immediately.
     */
    fun tapTo(
        targetIndex: Int,
    ) {
        settleJob?.cancel()

        gestureActive = false
        settling = true

        highlightedIndex =
            targetIndex

        val targetX =
            anchorFor(targetIndex)

        val shouldCommit =
            selectedIndexState.value != targetIndex

        settleJob =
            animationScope.launch {
                try {
                    coroutineScope {
                        /*
                         * Defensive cleanup in case a new tap interrupts a previous
                         * liquid animation.
                         */
                        launch {
                            dragProgress.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.92f,
                                    stiffness = 1200f,
                                ),
                            )
                        }

                        launch {
                            val deformation =
                                Animatable(
                                    velocityDeformation
                                )

                            deformation.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.85f,
                                    stiffness = 900f,
                                ),
                            ) {
                                velocityDeformation =
                                    value
                            }
                        }

                        /*
                         * Move only the inexpensive settled capsule.
                         */
                        launch {
                            val position =
                                Animatable(centerX)

                            position.animateTo(
                                targetValue = targetX,
                                animationSpec = spring(
                                    dampingRatio = 0.78f,
                                    stiffness = 900f,
                                ),
                            ) {
                                centerX = value
                            }
                        }

                        /*
                         * Small click feedback.
                         */
                        launch {
                            settlePulse.snapTo(0.55f)

                            settlePulse.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.65f,
                                    stiffness = 850f,
                                ),
                            )
                        }
                    }
                } finally {
                    settling = false
                }
            }

        /*
         * There is no Quality motion lens on this path, so navigation can happen
         * immediately without contending with the expensive optical effect.
         */
        if (shouldCommit) {
            onSelectTabState.value(
                items[targetIndex].tab
            )
        }
    }

    val railShape = remember {
        RoundedCornerShape(percent = 50)
    }

    val lensShape = remember {
        RoundedCornerShape(percent = 50)
    }

    /*
     * Base rail:
     *
     * Still true Haze Glass, but intentionally much calmer than the moving
     * lens. It becomes source z=1 together with the real tab icons.
     */
    val railStyle = remember(
        railShape,
        isDark,
        extraColors.panelTop,
    ) {
        GlassStyle.regular then GlassStyle {
            shape(railShape)

            optics(
                refractionStrength = 0.58f,
                refractionHeightFraction = 0.22f,
                refractionDisplacement = 10.dp,
                depth = 0.90f,
                blurRadius = 18.dp,
                refractionFoldStrength = 0.08f,
                refractionDetailIntensity = 0.35f,
                refractionProfile =
                    RefractionProfile.Edge(18.dp),
            )

            /*
             * Dark mode must not become another black translucent slab.
             *
             * Use a very small luminous base instead. The real backdrop remains
             * dominant, but the material itself is now visible against a dark
             * screen.
             */
            backgroundColor(
                if (isDark) {
                    Color.White.copy(alpha = 0.055f)
                } else {
                    extraColors.panelTop.copy(
                        alpha = 0.12f
                    )
                }
            )

            tint(
                if (isDark) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.White.copy(alpha = 0.14f)
                }
            )

            specularIntensity(
                if (isDark) {
                    0.78f
                } else {
                    0.68f
                }
            )

            edgeShadow(
                Color.Black.copy(
                    alpha =
                        if (isDark) {
                            0.16f
                        } else {
                            0.12f
                        }
                )
            )

            ambientResponse(
                if (isDark) {
                    0.50f
                } else {
                    0.30f
                }
            )

            edgeSoftness(0.65.dp)

            surfaceProfile(
                SurfaceProfile.Circle
            )

            chromaticAberrationStrength(0.035f)

            chromaticAberrationMode(
                ChromaticAberrationMode.Simple
            )

            contrast(
                if (isDark) {
                    0.07f
                } else {
                    0.05f
                }
            )

            whitePoint(
                if (isDark) {
                    0.13f
                } else {
                    0.14f
                }
            )

            chromaMultiplier(1.10f)
            contentNormalBlend(0.14f)

            specularExponent(18f)
            fresnelExponent(2.7f)
        }
    }

    val settledLensStyle = remember(
        lensShape,
        isDark,
    ) {
        GlassStyle.clear then GlassStyle {
            shape(lensShape)

            /*
             * Settled state is clear glass rather than frosted glass.
             *
             * Strong optical distortion is reserved for the pressed/moving
             * material.
             */
            optics(
                refractionStrength = 0.16f,
                refractionHeightFraction = 0.18f,
                refractionDisplacement = 2.dp,
                depth = 0.35f,
                blurRadius = 1.75.dp,
                refractionFoldStrength = 0.025f,
                refractionDetailIntensity = 0.18f,
                refractionProfile =
                    RefractionProfile.Edge(8.dp),
            )

            backgroundColor(
                if (isDark) {
                    Color.White.copy(alpha = 0.025f)
                } else {
                    Color.Transparent
                }
            )

            tint(
                if (isDark) {
                    Color.White.copy(alpha = 0.085f)
                } else {
                    Color.Black.copy(alpha = 0.055f)
                }
            )

            specularIntensity(
                if (isDark) {
                    0.62f
                } else {
                    0.48f
                }
            )

            edgeShadow(
                Color.Black.copy(
                    alpha =
                        if (isDark) {
                            0.14f
                        } else {
                            0.10f
                        }
                )
            )

            ambientResponse(
                if (isDark) {
                    0.45f
                } else {
                    0.28f
                }
            )

            edgeSoftness(0.55.dp)

            surfaceProfile(
                SurfaceProfile.Circle
            )

            chromaticAberrationStrength(0.035f)

            chromaticAberrationMode(
                ChromaticAberrationMode.Simple
            )

            contrast(0.035f)

            whitePoint(
                if (isDark) {
                    0.12f
                } else {
                    0.10f
                }
            )

            chromaMultiplier(1.04f)
            contentNormalBlend(0.08f)

            specularExponent(20f)
            fresnelExponent(2.8f)
        }
    }

    val motionLensStyle = remember(
        lensShape,
        isDark,
        colors.primary,
    ) {
        GlassStyle {
            shape(lensShape)

            /*
             * Keep the tuned narrow / strong refraction.
             */
            optics(
                refractionStrength = 1.0f,
                refractionHeightFraction = 0.15f,
                refractionDisplacement = 25.dp,

                depth = 0.18f,
                blurRadius = 0.90.dp,

                refractionFoldStrength = 0.11f,
                refractionDetailIntensity = 0.82f,

                refractionProfile =
                    RefractionProfile.Surface,
            )

            /*
             * Use the same clear base as light mode.
             */
            backgroundColor(
                Color.Transparent
            )

            /*
             * Dark mode becomes neutral instead of receiving the theme colour.
             *
             * The alpha remains identical to light mode.
             */
            tint(
                if (isDark) {
                    Color.White.copy(
                        alpha = 0.055f
                    )
                } else {
                    colors.primary.copy(
                        alpha = 0.055f
                    )
                }
            )

            specularIntensity(1f)

            edgeShadow(
                Color.Black.copy(
                    alpha = 0.22f
                )
            )

            /*
             * Same response as light mode.
             */
            ambientResponse(0.68f)

            edgeSoftness(0.25.dp)

            surfaceProfile(
                SurfaceProfile.Circle
            )

            /*
             * Dark mode: neutral glass.
             * Light mode: retain the existing spectral optical effect.
             */
            chromaticAberrationStrength(
                if (isDark) {
                    0f
                } else {
                    0.52f
                }
            )

            chromaticAberrationMode(
                ChromaticAberrationMode.Full
            )

            lightPosition(
                Alignment.TopCenter
            )

            alpha(1f)

            contrast(0.10f)

            /*
             * Same white point as light mode.
             */
            whitePoint(0.16f)

            chromaMultiplier(1.28f)
            contentNormalBlend(0.18f)

            specularExponent(12f)
            fresnelExponent(2.0f)
        }
    }

    val baseRailWidth =
        metrics.railWidth(items.size)

    val currentRailWidth =
        baseRailWidth +
                metrics.pressedRailExtraWidth *
                dragProgress.value

    Box(
        modifier = modifier
            .width(currentRailWidth)
            .height(metrics.railHeight)

            /*
             * The parent owns the whole drag gesture.
             */
            .pointerInput(
                items.size,
                firstAnchorPx,
                lastAnchorPx,
                itemWidthPx,
                pressedRailExtraWidthPx,
                touchSlopPx,
            ) {
                awaitEachGesture {
                    val down =
                        awaitFirstDown(
                            requireUnconsumed = false,
                        )

                    settleJob?.cancel()
                    settleJob = null

                    settling = false
                    gestureActive = true

                    animationScope.launch {
                        settlePulse.snapTo(0f)
                    }

                    val velocityTracker =
                        VelocityTracker()

                    velocityTracker.addPosition(
                        down.uptimeMillis,
                        down.position,
                    )

                    /*
                     * The outer rail still grows symmetrically, so pointer coordinates
                     * continue to be converted back to resting-rail coordinates.
                     */
                    fun currentSideExpansionPx(): Float =
                        pressedRailExtraWidthPx *
                                dragProgress.value /
                                2f

                    fun toBaseRailX(
                        localX: Float,
                    ): Float =
                        localX -
                                currentSideExpansionPx()

                    fun currentOverDragPx(): Float =
                        pressedRailExtraWidthPx *
                                0.44f *
                                dragProgress.value

                    var lastX =
                        toBaseRailX(
                            down.position.x
                        )

                    lastX =
                        lastX.coerceIn(
                            firstAnchorPx -
                                    currentOverDragPx(),
                            lastAnchorPx +
                                    currentOverDragPx(),
                        )

                    /*
                     * Do NOT move the expensive optical lens yet.
                     *
                     * This position is only used to decide whether the gesture later
                     * becomes a hold/drag.
                     */
                    val pressStartX =
                        lastX

                    velocityDeformation = 0f

                    val downIndex =
                        nearestTabIndex(
                            positionPx = lastX,
                            firstAnchorPx =
                                firstAnchorPx,
                            tabWidthPx =
                                itemWidthPx,
                            tabCount =
                                items.size,
                        )

                    if (
                        highlightedIndex !=
                        downIndex
                    ) {
                        highlightedIndex =
                            downIndex
                    }

                    /*
                     * If the user interrupts an already-running liquid settle, continue
                     * from that material state instead of forcing a visual reset.
                     */
                    var liquidActivated =
                        dragProgress.value > 0.02f

                    var activationJob: Job? =
                        null

                    fun activateLiquid(
                        positionX: Float,
                    ) {
                        if (liquidActivated) {
                            return
                        }

                        liquidActivated = true

                        activationJob?.cancel()
                        activationJob = null

                        /*
                         * Direct manipulation starts only when liquid mode really becomes
                         * active.
                         */
                        centerX =
                            positionX

                        animationScope.launch {
                            dragProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = 0.74f,
                                    stiffness = 760f,
                                ),
                            )
                        }
                    }

                    if (liquidActivated) {
                        /*
                         * Continue an interrupted liquid interaction.
                         */
                        centerX =
                            lastX
                    } else {
                        /*
                         * Holding activates the optical lens after a short delay.
                         *
                         * A quick tap normally finishes before this job wakes up and
                         * therefore never creates the expensive motion lens.
                         */
                        activationJob =
                            animationScope.launch {
                                delay(
                                    LIQUID_PRESS_ACTIVATION_DELAY_MS
                                )

                                if (
                                    gestureActive &&
                                    !liquidActivated
                                ) {
                                    liquidActivated = true

                                    centerX =
                                        lastX

                                    dragProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            dampingRatio = 0.74f,
                                            stiffness = 760f,
                                        ),
                                    )
                                }
                            }
                    }

                    var released = false
                    var releaseVelocityX = 0f

                    while (true) {
                        val event =
                            awaitPointerEvent()

                        val change =
                            event.changes
                                .firstOrNull {
                                    it.id == down.id
                                }
                                ?: break

                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position,
                        )

                        val overDrag =
                            currentOverDragPx()

                        lastX =
                            toBaseRailX(
                                change.position.x
                            )
                                .coerceIn(
                                    firstAnchorPx -
                                            overDrag,
                                    lastAnchorPx +
                                            overDrag,
                                )

                        if (!change.pressed) {
                            releaseVelocityX =
                                velocityTracker
                                    .calculateVelocity()
                                    .x

                            released = true
                            break
                        }

                        /*
                         * Moving farther than the system touch slop means this is a real
                         * drag. Activate liquid mode immediately rather than waiting for
                         * the hold timeout.
                         */
                        if (
                            !liquidActivated &&
                            abs(
                                lastX -
                                        pressStartX
                            ) >= touchSlopPx
                        ) {
                            activateLiquid(
                                lastX
                            )
                        }

                        /*
                         * Only the true liquid path performs per-frame optical motion.
                         *
                         * A normal tap therefore avoids these state writes as well.
                         */
                        if (liquidActivated) {
                            centerX =
                                lastX

                            val velocityX =
                                velocityTracker
                                    .calculateVelocity()
                                    .x

                            velocityDeformation =
                                (
                                        velocityX /
                                                (
                                                        itemWidthPx *
                                                                17f
                                                        )
                                        )
                                    .coerceIn(
                                        -1f,
                                        1f,
                                    )
                        }

                        val nearest =
                            nearestTabIndex(
                                positionPx = lastX,
                                firstAnchorPx =
                                    firstAnchorPx,
                                tabWidthPx =
                                    itemWidthPx,
                                tabCount =
                                    items.size,
                            )

                        if (
                            highlightedIndex !=
                            nearest
                        ) {
                            highlightedIndex =
                                nearest
                        }

                        change.consume()
                    }

                    /*
                     * Prevent the delayed hold job from waking up after the finger has
                     * already been released.
                     */
                    activationJob?.cancel()
                    activationJob = null

                    val fallbackIndex =
                        selectedIndexState.value
                            .coerceIn(
                                0,
                                items.lastIndex,
                            )

                    if (released) {
                        if (liquidActivated) {
                            /*
                             * Drag / hold path:
                             *
                             * Keep velocity projection, then use the heavy settle path.
                             */
                            val projectionPx =
                                (
                                        releaseVelocityX *
                                                RELEASE_PROJECTION_SECONDS
                                        )
                                    .coerceIn(
                                        -itemWidthPx *
                                                MAX_RELEASE_PROJECTION_IN_TABS,
                                        itemWidthPx *
                                                MAX_RELEASE_PROJECTION_IN_TABS,
                                    )

                            val projectedX =
                                (
                                        lastX +
                                                projectionPx
                                        )
                                    .coerceIn(
                                        firstAnchorPx,
                                        lastAnchorPx,
                                    )

                            val targetIndex =
                                nearestTabIndex(
                                    positionPx =
                                        projectedX,
                                    firstAnchorPx =
                                        firstAnchorPx,
                                    tabWidthPx =
                                        itemWidthPx,
                                    tabCount =
                                        items.size,
                                )

                            highlightedIndex =
                                targetIndex

                            val settleVelocity =
                                releaseVelocityX
                                    .coerceIn(
                                        -itemWidthPx * 20f,
                                        itemWidthPx * 20f,
                                    )

                            settleTo(
                                targetIndex =
                                    targetIndex,
                                initialVelocity =
                                    settleVelocity,
                                commitSelection =
                                    true,
                            )
                        } else {
                            /*
                             * Normal tap path:
                             *
                             * No strong optical lens was ever created.
                             */
                            val targetIndex =
                                nearestTabIndex(
                                    positionPx =
                                        lastX.coerceIn(
                                            firstAnchorPx,
                                            lastAnchorPx,
                                        ),
                                    firstAnchorPx =
                                        firstAnchorPx,
                                    tabWidthPx =
                                        itemWidthPx,
                                    tabCount =
                                        items.size,
                                )

                            tapTo(
                                targetIndex =
                                    targetIndex,
                            )
                        }
                    } else {
                        /*
                         * Cancelled gesture.
                         */
                        highlightedIndex =
                            fallbackIndex

                        if (liquidActivated) {
                            settleTo(
                                targetIndex =
                                    fallbackIndex,
                                commitSelection =
                                    false,
                            )
                        } else {
                            gestureActive = false
                            settling = false
                            velocityDeformation = 0f
                        }
                    }
                }
            },
        contentAlignment =
            Alignment.TopStart,
    ) {
        /*
 * BASE SCENE — source z = 1.
 *
 * Modifier order is intentional:
 *
 * hazeSource
 *     ↓
 * clipped capsule scene
 *
 * The higher liquid lens therefore receives the already-clipped capsule
 * instead of the rectangular backing layer used to render it.
 */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(
                    state = hazeState,
                    zIndex = BAR_SOURCE_Z_INDEX,
                    key = "notes-bottom-bar-base",
                )
                .graphicsLayer {
                    shape = railShape
                    clip = true
                },
        ) {
            /*
             * The actual rail is also clipped independently.
             *
             * Layer expansion is deliberately disabled for the rail. Its blur is
             * completely contained by the capsule and we do not want Haze creating
             * a larger rectangular effect layer around this animated surface.
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        shape = railShape
                        clip = true
                    }
                    .hazeGlass(
                        input = exactInput,
                        style = railStyle,
                        performanceMode =
                            HazePerformanceMode.Balanced,
                        expandLayerBounds = false,
                    ),
            )

            /*
             * Source icons remain inside source z=1 so the moving optical lens can
             * refract the real icon pixels.
             */
            Row(
                modifier = Modifier
                    .width(
                        metrics.itemTouchSize *
                                items.size.toFloat()
                    )
                    .height(metrics.itemTouchSize)
                    .align(Alignment.Center)
                    .selectableGroup(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                items.forEachIndexed {
                        index,
                        item,
                    ->

                    val highlighted =
                        highlightedIndex == index

                    val actualSelected =
                        currentTab == item.tab

                    val iconColor by
                    animateColorAsState(
                        targetValue =
                            if (highlighted) {
                                colors.primary
                            } else {
                                extraColors.textMuted
                            },
                        label =
                            "liquid-bottom-bar-source-icon",
                    )

                    Box(
                        modifier = Modifier
                            .size(metrics.itemTouchSize)
                            .semantics(
                                mergeDescendants = true,
                            ) {
                                role = Role.Tab

                                selected =
                                    actualSelected

                                contentDescription =
                                    item.label

                                onClick {
                                    onSelectTab(
                                        item.tab
                                    )
                                    true
                                }
                            },
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        Icon(
                            imageVector =
                                item.icon,
                            contentDescription =
                                null,
                            tint =
                                iconColor,
                            modifier =
                                Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        /*
 * SETTLED LENS
 *
 * No external Compose shadow.
 * The glass itself supplies edge lighting and depth.
 */
        Box(
            modifier = Modifier
                .size(
                    metrics.lensVisualSize
                )
                .graphicsLayer {
                    val motion =
                        dragProgress.value
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val pulse =
                        settlePulse.value
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val railSideExpansion =
                        pressedRailExtraWidthPx *
                                dragProgress.value /
                                2f

                    translationX =
                        railSideExpansion +
                                centerX -
                                lensSizePx / 2f

                    translationY =
                        lensTopPx

                    scaleX =
                        1f +
                                pulse * 0.10f

                    scaleY =
                        1f -
                                pulse * 0.045f

                    alpha =
                        (1f - motion)
                            .coerceIn(
                                0f,
                                1f,
                            )

                    shape = lensShape
                    clip = true
                }
                .hazeGlass(
                    input = exactInput,
                    style = settledLensStyle,
                    performanceMode =
                        HazePerformanceMode.Balanced,
                    expandLayerBounds = false,
                ),
        )

        /*
 * FULL LIQUID OPTICAL LENS
 *
 * The moving lens keeps the highest rendering quality because this is
 * the visually important interactive optical surface.
 */
        Box(
            modifier = Modifier
                .size(
                    metrics.lensVisualSize
                )
                .graphicsLayer {
                    val progress =
                        dragProgress.value
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val velocity =
                        velocityDeformation
                            .coerceIn(
                                -1f,
                                1f,
                            )

                    val speed =
                        abs(velocity)

                    val railSideExpansion =
                        pressedRailExtraWidthPx *
                                progress /
                                2f

                    scaleX =
                        1f +
                                progress * 0.34f +
                                progress *
                                speed *
                                0.26f

                    scaleY =
                        1f +
                                progress * 0.28f -
                                progress *
                                speed *
                                0.03f

                    translationX =
                        railSideExpansion +
                                centerX -
                                lensSizePx / 2f +
                                velocity *
                                velocityLeadPx *
                                progress

                    translationY =
                        lensTopPx -
                                lensLiftPx *
                                progress

                    alpha =
                        progress

                    shape = lensShape
                    clip = true
                }
                .hazeGlass(
                    input = exactInput,
                    style = motionLensStyle,
                    performanceMode =
                        HazePerformanceMode.Quality,
                    expandLayerBounds = true,
                ),
        )

        /*
 * FOREGROUND ICONS
 *
 * At rest, icons must sit above the settled glass exactly like ordinary
 * interface content placed on top of a glass material.
 *
 * During interaction this foreground copy fades away, exposing the icon
 * pixels captured in the z=1 source underneath. The moving liquid lens can
 * then genuinely refract and distort those source pixels.
 *
 * dragProgress:
 *
 * 0 -> foreground icons fully visible and perfectly sharp
 * 1 -> foreground icons invisible; optical source icons are used instead
 */
        Row(
            modifier = Modifier
                .width(
                    metrics.itemTouchSize *
                            items.size.toFloat()
                )
                .height(metrics.itemTouchSize)
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha =
                        (
                                1f -
                                        dragProgress.value
                                )
                            .coerceIn(
                                0f,
                                1f,
                            )
                },
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            items.forEachIndexed {
                    index,
                    item,
                ->

                val highlighted =
                    highlightedIndex == index

                val iconColor by
                animateColorAsState(
                    targetValue =
                        if (highlighted) {
                            colors.primary
                        } else {
                            extraColors.textMuted
                        },
                    label =
                        "liquid-bottom-bar-foreground-icon",
                )

                Box(
                    modifier =
                        Modifier.size(
                            metrics.itemTouchSize
                        ),
                    contentAlignment =
                        Alignment.Center,
                ) {
                    Icon(
                        imageVector =
                            item.icon,
                        contentDescription =
                            null,
                        tint =
                            iconColor,
                        modifier =
                            Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

private fun nearestTabIndex(
    positionPx: Float,
    firstAnchorPx: Float,
    tabWidthPx: Float,
    tabCount: Int,
): Int {
    if (tabCount <= 1) {
        return 0
    }

    return (
            (
                    positionPx -
                            firstAnchorPx
                    ) /
                    tabWidthPx
            )
        .roundToInt()
        .coerceIn(
            0,
            tabCount - 1,
        )
}