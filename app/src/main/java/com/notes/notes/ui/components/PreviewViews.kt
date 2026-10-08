package com.notes.notes.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.notes.notes.ui.theme.LocalNotesExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.abs

@Composable
fun HtmlPreviewView(
    html: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,

        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled =
                    false

                settings.loadsImagesAutomatically =
                    true

                settings.allowFileAccess =
                    false

                settings.domStorageEnabled =
                    false

                settings.cacheMode =
                    WebSettings.LOAD_NO_CACHE

                settings.builtInZoomControls =
                    true

                settings.displayZoomControls =
                    false

                setBackgroundColor(
                    android.graphics.Color.TRANSPARENT
                )

                webChromeClient =
                    WebChromeClient()

                webViewClient =
                    WebViewClient()
            }
        },

        onReset = { webView ->
            webView.onPause()
        },

        update = { webView ->
            if (active) {
                webView.onResume()
            } else {
                webView.onPause()
            }

            if (
                webView.tag != html
            ) {
                webView.tag =
                    html

                webView.loadDataWithBaseURL(
                    null,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },

        onRelease = { webView ->
            webView.tag =
                null

            webView.stopLoading()
            webView.onPause()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        },
    )
}

@Composable
fun PdfPreviewView(
    filePath: String,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val holder =
        produceState<PdfRendererHolder?>(
            initialValue = null,
            key1 = filePath,
        ) {
            var openedHolder: PdfRendererHolder? =
                null

            try {
                val createdHolder =
                    withContext(
                        Dispatchers.IO
                    ) {
                        PdfRendererHolder(
                            File(filePath)
                        ).also { holder ->
                            /*
                             * Keep a reference before returning from IO so the
                             * renderer can still be closed if cancellation
                             * happens during dispatcher hand-off.
                             */
                            openedHolder =
                                holder
                        }
                    }

                value =
                    createdHolder

                awaitCancellation()
            } finally {
                val holderToClose =
                    openedHolder

                if (
                    holderToClose != null
                ) {
                    withContext(
                        NonCancellable
                    ) {
                        withContext(
                            Dispatchers.IO
                        ) {
                            holderToClose.close()
                        }
                    }
                }
            }
        }.value

    if (
        holder == null
    ) {
        Box(
            modifier =
                modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        return
    }

    val density =
        LocalDensity.current

    val borderColor =
        LocalNotesExtraColors
            .current
            .borderStrong

    val listState =
        rememberLazyListState()

    val horizontalScrollState =
        rememberScrollState()

    var viewportWidthPx by
    remember(
        filePath
    ) {
        mutableIntStateOf(
            0
        )
    }

    /*
     * This is the real PDF layout zoom.
     *
     * IMPORTANT:
     * It does NOT change while a pinch is active.
     *
     * LazyColumn therefore keeps exactly the same measured page geometry for
     * the whole gesture. The final zoom is committed once when all fingers
     * are released.
     */
    var committedZoom by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            MIN_PDF_ZOOM
        )
    }

    /*
     * Actual PdfRenderer resolution.
     *
     * It follows committedZoom only after the usual settle delay. It remains
     * capped at MAX_PDF_RASTER_SCALE independently of the 3x visual layout.
     */
    var renderScale by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            MIN_PDF_ZOOM
        )
    }

    var isPinching by
    remember(
        filePath
    ) {
        mutableStateOf(
            false
        )
    }

    /*
     * Temporary draw-only transform used during an active pinch.
     *
     * graphicsLayer does not participate in layout measurement, which is the
     * important difference from the previous implementation.
     */
    var gestureScale by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            1f
        )
    }

    var gestureTranslationX by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            0f
        )
    }

    var gestureTranslationY by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            0f
        )
    }

    /*
     * Final horizontal scroll requested by the most recently committed pinch.
     *
     * ScrollState.maxValue updates only after committedZoom has reached layout,
     * so retain the logical target independently until the new range exists.
     */
    var requestedHorizontalScrollPx by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            0f
        )
    }

    /*
     * Final vertical page anchor used for the one real layout transition at
     * gesture end.
     */
    var pendingVerticalAnchorIndex by
    remember(
        filePath
    ) {
        mutableIntStateOf(
            -1
        )
    }

    var pendingVerticalScrollOffsetPx by
    remember(
        filePath
    ) {
        mutableIntStateOf(
            0
        )
    }

    /*
     * Guarantees that every completed pinch receives one final correction,
     * even when it happens to end on the same page/zoom as the previous one.
     */
    var commitSequence by
    remember(
        filePath
    ) {
        mutableIntStateOf(
            0
        )
    }

    /*
     * A new document always starts fit-to-width at page 1.
     */
    LaunchedEffect(
        filePath
    ) {
        isPinching =
            false

        committedZoom =
            MIN_PDF_ZOOM

        renderScale =
            MIN_PDF_ZOOM

        gestureScale =
            1f

        gestureTranslationX =
            0f

        gestureTranslationY =
            0f

        requestedHorizontalScrollPx =
            0f

        pendingVerticalAnchorIndex =
            -1

        pendingVerticalScrollOffsetPx =
            0

        commitSequence =
            0

        horizontalScrollState
            .scrollTo(
                0
            )

        if (
            pageCount > 0
        ) {
            listState.scrollToItem(
                0
            )
        }
    }

    /*
     * Expensive native rasterization starts only after the committed layout
     * zoom has settled.
     *
     * Since committedZoom never changes inside the active gesture, rapid
     * pinching can no longer repeatedly restart page rendering.
     */
    LaunchedEffect(
        committedZoom
    ) {
        delay(
            PDF_RENDER_SETTLE_MILLIS
        )

        renderScale =
            quantizePdfRenderScale(
                committedZoom
            )
    }

    /*
     * The horizontal scroll range changes only after committedZoom has been
     * measured. Re-run whenever that range changes and converge on the final
     * logical target.
     */
    val horizontalMaxScrollPx =
        horizontalScrollState
            .maxValue

    LaunchedEffect(
        filePath,
        committedZoom,
        horizontalMaxScrollPx,
        isPinching,
    ) {
        if (
            isPinching
        ) {
            return@LaunchedEffect
        }

        val target =
            requestedHorizontalScrollPx
                .roundToInt()
                .coerceIn(
                    0,
                    horizontalMaxScrollPx,
                )

        if (
            horizontalScrollState.value !=
            target
        ) {
            horizontalScrollState
                .scrollTo(
                    target
                )
        }
    }

    /*
     * requestScrollToItem() performs the layout-time anchor request when the
     * gesture ends.
     *
     * Once the new committed zoom has actually reached measurement, perform
     * one final exact scroll. No vertical correction is performed per pinch
     * frame anymore.
     */
    LaunchedEffect(
        filePath,
        commitSequence,
    ) {
        if (
            commitSequence <=
            0
        ) {
            return@LaunchedEffect
        }

        val anchorIndex =
            pendingVerticalAnchorIndex

        if (
            anchorIndex !in
            0 until pageCount
        ) {
            return@LaunchedEffect
        }

        withFrameNanos {
            // Allow committedZoom to participate in one real measurement pass.
        }

        listState.scrollToItem(
            index =
                anchorIndex,
            scrollOffset =
                pendingVerticalScrollOffsetPx,
        )

        pendingVerticalAnchorIndex =
            -1
    }

    /*
     * Fit-to-width never has horizontal overflow.
     */
    LaunchedEffect(
        committedZoom <=
                MIN_PDF_ZOOM +
                PDF_ZOOM_EPSILON,
        isPinching,
    ) {
        if (
            !isPinching &&
            committedZoom <=
            MIN_PDF_ZOOM +
            PDF_ZOOM_EPSILON
        ) {
            requestedHorizontalScrollPx =
                0f

            horizontalScrollState
                .scrollTo(
                    0
                )
        }
    }

    Box(
        modifier =
            modifier
                .clipToBounds()
                .onSizeChanged { size ->
                    viewportWidthPx =
                        size.width
                }
                .pointerInput(
                    filePath
                ) {
                    try {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed =
                                    false,
                                pass =
                                    PointerEventPass
                                        .Initial,
                            )

                            var multiTouchStarted =
                                false

                            var gestureBaseReady =
                                false

                            /*
                             * One gesture uses one immutable real-layout base.
                             *
                             * None of these values are rebuilt from an
                             * intermediate LazyColumn remeasure because there
                             * are no intermediate remeasures anymore.
                             */
                            var gestureBaseZoom =
                                committedZoom

                            var gestureBaseHorizontalScroll =
                                horizontalScrollState
                                    .value
                                    .toFloat()

                            var gestureBaseCentroidX =
                                0f

                            var gestureBaseCentroidY =
                                0f

                            var gestureAnchorIndex =
                                -1

                            var gestureAnchorLocalY =
                                0f

                            /*
                             * If the fingers begin inside the fixed 8dp
                             * inter-page gap, keep that out-of-page component
                             * unscaled for the eventual layout commit.
                             */
                            var gestureAnchorFixedYOffset =
                                0f

                            var gestureScaleValue =
                                1f

                            var lastCentroidX =
                                0f

                            var lastCentroidY =
                                0f

                            /*
                             * Once a third pointer participates, freeze this
                             * gesture until all pointers are released.
                             *
                             * Re-basing a draw-only transform while the real
                             * layout remains unchanged introduces more edge
                             * cases than it solves. Standard two-finger PDF
                             * interaction remains deterministic.
                             */
                            var pointerMembershipInvalid =
                                false

                            while (true) {
                                val event =
                                    awaitPointerEvent(
                                        PointerEventPass
                                            .Initial
                                    )

                                val pressedPointers =
                                    event.changes
                                        .count {
                                            it.pressed
                                        }

                                if (
                                    pressedPointers >=
                                    2
                                ) {
                                    if (
                                        !multiTouchStarted
                                    ) {
                                        multiTouchStarted =
                                            true

                                        isPinching =
                                            true

                                        gestureBaseReady =
                                            false

                                        pointerMembershipInvalid =
                                            pressedPointers !=
                                                    2

                                        event.changes
                                            .forEach {
                                                it.consume()
                                            }

                                        continue
                                    }

                                    if (
                                        pressedPointers !=
                                        2
                                    ) {
                                        pointerMembershipInvalid =
                                            true
                                    }

                                    val stablePointerCount =
                                        event.changes
                                            .count {
                                                it.pressed &&
                                                        it.previousPressed
                                            }

                                    if (
                                        !pointerMembershipInvalid &&
                                        pressedPointers ==
                                        2 &&
                                        stablePointerCount ==
                                        2
                                    ) {
                                        val previousCentroid =
                                            event.calculateCentroid(
                                                useCurrent =
                                                    false
                                            )

                                        val currentCentroid =
                                            event.calculateCentroid(
                                                useCurrent =
                                                    true
                                            )

                                        val zoomChange =
                                            event.calculateZoom()

                                        if (
                                            previousCentroid.x.isFinite() &&
                                            previousCentroid.y.isFinite() &&
                                            currentCentroid.x.isFinite() &&
                                            currentCentroid.y.isFinite() &&
                                            zoomChange.isFinite() &&
                                            zoomChange >
                                            0f
                                        ) {
                                            /*
                                             * Establish the immutable anchor
                                             * exactly once for this pinch.
                                             */
                                            if (
                                                !gestureBaseReady
                                            ) {
                                                val visibleItems =
                                                    listState
                                                        .layoutInfo
                                                        .visibleItemsInfo

                                                val anchorItem =
                                                    visibleItems
                                                        .firstOrNull { item ->
                                                            val top =
                                                                item.offset
                                                                    .toFloat()

                                                            val bottom =
                                                                (
                                                                        item.offset +
                                                                                item.size
                                                                        )
                                                                    .toFloat()

                                                            previousCentroid.y >=
                                                                    top &&
                                                                    previousCentroid.y <=
                                                                    bottom
                                                        }
                                                        ?: visibleItems
                                                            .minByOrNull { item ->
                                                                val top =
                                                                    item.offset
                                                                        .toFloat()

                                                                val bottom =
                                                                    (
                                                                            item.offset +
                                                                                    item.size
                                                                            )
                                                                        .toFloat()

                                                                when {
                                                                    previousCentroid.y <
                                                                            top ->
                                                                        top -
                                                                                previousCentroid.y

                                                                    previousCentroid.y >
                                                                            bottom ->
                                                                        previousCentroid.y -
                                                                                bottom

                                                                    else ->
                                                                        0f
                                                                }
                                                            }

                                                if (
                                                    anchorItem != null &&
                                                    anchorItem.size >
                                                    0
                                                ) {
                                                    gestureBaseReady =
                                                        true

                                                    gestureBaseZoom =
                                                        committedZoom

                                                    gestureBaseHorizontalScroll =
                                                        horizontalScrollState
                                                            .value
                                                            .toFloat()

                                                    gestureBaseCentroidX =
                                                        previousCentroid.x

                                                    gestureBaseCentroidY =
                                                        previousCentroid.y

                                                    gestureAnchorIndex =
                                                        anchorItem.index

                                                    val rawAnchorLocalY =
                                                        previousCentroid.y -
                                                                anchorItem
                                                                    .offset
                                                                    .toFloat()

                                                    gestureAnchorLocalY =
                                                        rawAnchorLocalY
                                                            .coerceIn(
                                                                0f,
                                                                anchorItem
                                                                    .size
                                                                    .toFloat(),
                                                            )

                                                    gestureAnchorFixedYOffset =
                                                        rawAnchorLocalY -
                                                                gestureAnchorLocalY

                                                    gestureScaleValue =
                                                        1f

                                                    lastCentroidX =
                                                        previousCentroid.x

                                                    lastCentroidY =
                                                        previousCentroid.y
                                                }
                                            }

                                            if (
                                                gestureBaseReady
                                            ) {
                                                /*
                                                 * Accumulate zoom only as a
                                                 * visual transform.
                                                 *
                                                 * Clamp using the final logical
                                                 * PDF zoom range, not an
                                                 * arbitrary graphics-layer range.
                                                 */
                                                val minGestureScale =
                                                    MIN_PDF_ZOOM /
                                                            gestureBaseZoom

                                                val maxGestureScale =
                                                    MAX_PDF_ZOOM /
                                                            gestureBaseZoom

                                                gestureScaleValue =
                                                    (
                                                            gestureScaleValue *
                                                                    zoomChange
                                                            )
                                                        .coerceIn(
                                                            minGestureScale,
                                                            maxGestureScale,
                                                        )

                                                lastCentroidX =
                                                    currentCentroid.x

                                                lastCentroidY =
                                                    currentCentroid.y

                                                /*
                                                 * The graphics layer belongs
                                                 * to the full-width LazyColumn
                                                 * child of horizontalScroll.
                                                 *
                                                 * Its local horizontal origin
                                                 * is therefore displaced by the
                                                 * real horizontal scroll value,
                                                 * which must be included when
                                                 * keeping the centroid fixed.
                                                 */
                                                gestureTranslationX =
                                                    currentCentroid.x +
                                                            gestureBaseHorizontalScroll -
                                                            (
                                                                    gestureBaseCentroidX +
                                                                            gestureBaseHorizontalScroll
                                                                    ) *
                                                            gestureScaleValue

                                                /*
                                                 * Vertically the LazyColumn
                                                 * layer itself remains at y=0;
                                                 * scrolling occurs inside it.
                                                 */
                                                gestureTranslationY =
                                                    currentCentroid.y -
                                                            gestureBaseCentroidY *
                                                            gestureScaleValue

                                                gestureScale =
                                                    gestureScaleValue
                                            }
                                        }
                                    }

                                    /*
                                     * Multi-touch transformation is owned
                                     * entirely by this pointer handler.
                                     */
                                    event.changes
                                        .forEach {
                                            it.consume()
                                        }
                                } else if (
                                    multiTouchStarted
                                ) {
                                    /*
                                     * Once a gesture became multi-touch,
                                     * consume its remainder so the final
                                     * finger cannot suddenly become a normal
                                     * LazyColumn drag.
                                     */
                                    event.changes
                                        .forEach {
                                            it.consume()
                                        }
                                }

                                if (
                                    event.changes
                                        .none {
                                            it.pressed
                                        }
                                ) {
                                    break
                                }
                            }

                            /*
                             * ------------------------------------------------
                             * One-time real layout commit
                             * ------------------------------------------------
                             *
                             * The entire pinch above changed only draw
                             * properties. Now convert the final visual
                             * transform into one real LazyColumn layout.
                             */
                            if (
                                multiTouchStarted &&
                                gestureBaseReady
                            ) {
                                val finalZoom =
                                    (
                                            gestureBaseZoom *
                                                    gestureScaleValue
                                            )
                                        .coerceIn(
                                            MIN_PDF_ZOOM,
                                            MAX_PDF_ZOOM,
                                        )

                                val finalScaleFromBase =
                                    finalZoom /
                                            gestureBaseZoom

                                val scaledAnchorLocalY =
                                    gestureAnchorLocalY *
                                            finalScaleFromBase +
                                            gestureAnchorFixedYOffset

                                val requestedVerticalOffset =
                                    (
                                            scaledAnchorLocalY -
                                                    lastCentroidY
                                            )
                                        .roundToInt()

                                val maxHorizontalScroll =
                                    (
                                            viewportWidthPx
                                                .toFloat() *
                                                    (
                                                            finalZoom -
                                                                    MIN_PDF_ZOOM
                                                            )
                                            )
                                        .coerceAtLeast(
                                            0f
                                        )

                                val targetHorizontalScroll =
                                    (
                                            (
                                                    gestureBaseHorizontalScroll +
                                                            gestureBaseCentroidX
                                                    ) *
                                                    finalScaleFromBase -
                                                    lastCentroidX
                                            )
                                        .coerceIn(
                                            0f,
                                            maxHorizontalScroll,
                                        )

                                pendingVerticalAnchorIndex =
                                    gestureAnchorIndex

                                pendingVerticalScrollOffsetPx =
                                    requestedVerticalOffset

                                requestedHorizontalScrollPx =
                                    targetHorizontalScroll

                                /*
                                 * Tell LazyColumn which page must survive the
                                 * one real zoom-induced remeasure.
                                 */
                                listState
                                    .requestScrollToItem(
                                        index =
                                            gestureAnchorIndex,
                                        scrollOffset =
                                            requestedVerticalOffset,
                                    )

                                /*
                                 * Move as far horizontally as the old range
                                 * allows. If zooming in requires a larger
                                 * range, the LaunchedEffect above completes
                                 * the correction after remeasure.
                                 */
                                val horizontalDelta =
                                    targetHorizontalScroll -
                                            horizontalScrollState
                                                .value
                                                .toFloat()

                                if (
                                    horizontalDelta !=
                                    0f
                                ) {
                                    horizontalScrollState
                                        .dispatchRawDelta(
                                            horizontalDelta
                                        )
                                }

                                /*
                                 * These writes are observed together by the
                                 * next Compose frame:
                                 *
                                 * visual transform -> identity
                                 * committed layout -> final zoom
                                 *
                                 * so there is no deliberate intermediate
                                 * frame that double-applies the scale.
                                 */
                                committedZoom =
                                    finalZoom

                                commitSequence +=
                                    1
                            }

                            gestureScale =
                                1f

                            gestureTranslationX =
                                0f

                            gestureTranslationY =
                                0f

                            isPinching =
                                false
                        }
                    } finally {
                        /*
                         * Covers document replacement and composition disposal.
                         */
                        gestureScale =
                            1f

                        gestureTranslationX =
                            0f

                        gestureTranslationY =
                            0f

                        isPinching =
                            false
                    }
                },
    ) {
        if (
            viewportWidthPx <=
            0
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier.align(
                        Alignment.Center
                    )
            )

            return@Box
        }

        /*
         * Only committedZoom participates in layout.
         *
         * gestureScale is deliberately absent from this calculation.
         */
        val contentWidth =
            with(
                density
            ) {
                (
                        viewportWidthPx
                            .toFloat() *
                                committedZoom
                        )
                    .toDp()
            }

        val targetWidthPx =
            (
                    viewportWidthPx
                        .toFloat() *
                            renderScale
                    )
                .roundToInt()
                .coerceAtLeast(
                    1
                )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(
                        state =
                            horizontalScrollState,
                        enabled =
                            committedZoom >
                                    MIN_PDF_ZOOM +
                                    PDF_ZOOM_EPSILON,
                    ),
        ) {
            /*
             * This wrapper is the only object transformed during pinch.
             *
             * Its measured width/height remain completely unchanged; only its
             * draw layer is scaled/translated.
             */
            Box(
                modifier =
                    Modifier
                        .width(
                            contentWidth
                        )
                        .fillMaxHeight()
                        .graphicsLayer {
                            transformOrigin =
                                TransformOrigin(
                                    pivotFractionX =
                                        0f,
                                    pivotFractionY =
                                        0f,
                                )

                            scaleX =
                                gestureScale

                            scaleY =
                                gestureScale

                            translationX =
                                gestureTranslationX

                            translationY =
                                gestureTranslationY

                            clip =
                                false
                        },
            ) {
                LazyColumn(
                    state =
                        listState,
                    modifier =
                        Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            0.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        ),
                ) {
                    items(
                        count =
                            pageCount,
                        key = { pageIndex ->
                            "$filePath:$pageIndex"
                        },
                    ) { pageIndex ->
                        /*
                         * Reuse an already rendered page immediately when
                         * possible.
                         *
                         * If the exact target width is unavailable, the closest
                         * cached resolution is used temporarily while a sharper
                         * page is produced in the background.
                         */
                        val bitmap =
                            produceState<Bitmap?>(
                                initialValue =
                                    holder.findCachedBitmap(
                                        pageIndex =
                                            pageIndex,
                                        targetWidthPx =
                                            targetWidthPx,
                                    ),
                                key1 =
                                    filePath,
                                key2 =
                                    pageIndex,
                                key3 =
                                    targetWidthPx,
                            ) {
                                value =
                                    withContext(
                                        Dispatchers.Default
                                    ) {
                                        holder.render(
                                            pageIndex =
                                                pageIndex,
                                            targetWidthPx =
                                                targetWidthPx,
                                        )
                                    }
                            }.value

                        /*
                         * The page container itself always reserves a PDF-like
                         * aspect ratio.
                         *
                         * Page 0's real ratio is available as the document
                         * default immediately. Once a particular page has been
                         * rendered, its own exact ratio replaces that default.
                         *
                         * Consequently a cache miss no longer collapses the
                         * item to a fixed 200dp spinner and then expands it when
                         * the Bitmap arrives.
                         */
                        val pageAspectRatio =
                            holder.pageAspectRatio(
                                pageIndex
                            )

                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        pageAspectRatio
                                    ),
                            shape =
                                RoundedCornerShape(
                                    14.dp
                                ),
                            color =
                                Color.White,
                            border =
                                BorderStroke(
                                    1.dp,
                                    borderColor,
                                ),
                        ) {
                            if (
                                bitmap == null
                            ) {
                                Box(
                                    modifier =
                                        Modifier.fillMaxSize(),
                                    contentAlignment =
                                        Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                Image(
                                    bitmap =
                                        bitmap
                                            .asImageBitmap(),
                                    contentDescription =
                                        null,
                                    modifier =
                                        Modifier.fillMaxSize(),
                                    contentScale =
                                        ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PdfRendererHolder(
    file: File,
) {
    /*
     * PdfRenderer owns this ParcelFileDescriptor once construction succeeds.
     */
    private val renderer:
            PdfRenderer =
        ParcelFileDescriptor
            .open(
                file,
                ParcelFileDescriptor
                    .MODE_READ_ONLY,
            )
            .let { descriptor ->
                try {
                    PdfRenderer(
                        descriptor
                    )
                } catch (
                    throwable: Throwable
                ) {
                    descriptor.close()
                    throw throwable
                }
            }

    private var closed =
        false

    /*
     * Read the first page ratio once while the holder is created on IO.
     *
     * Most PDFs use a consistent page size, so this gives unseen lazy pages a
     * far better placeholder geometry than an arbitrary fixed 200dp height.
     * Mixed-size PDFs are corrected to their exact ratio after each page is
     * first rendered.
     */
    private val defaultPageAspectRatio:
            Float =
        if (
            renderer.pageCount >
            0
        ) {
            renderer
                .openPage(
                    0
                )
                .use { page ->
                    page.width
                        .toFloat() /
                            page.height
                                .toFloat()
                }
        } else {
            1f
        }

    /*
     * Tiny metadata cache; unlike Bitmap caching this has negligible memory
     * cost even for a large document.
     */
    private val pageAspectRatios =
        mutableMapOf<Int, Float>()
            .apply {
                if (
                    renderer.pageCount >
                    0
                ) {
                    put(
                        0,
                        defaultPageAspectRatio,
                    )
                }
            }

    private val renderedPages =
        object :
            LruCache<
                    PdfRenderKey,
                    Bitmap,
                    >(
                PDF_BITMAP_CACHE_KIB
            ) {

            override fun sizeOf(
                key: PdfRenderKey,
                value: Bitmap,
            ): Int {
                val kib =
                    (
                            value
                                .allocationByteCount
                                .toLong() +
                                    1023L
                            ) /
                            1024L

                return kib
                    .coerceAtMost(
                        Int.MAX_VALUE
                            .toLong()
                    )
                    .toInt()
            }
        }

    /*
     * Returns the exact page ratio once known, otherwise the first page's real
     * ratio as a stable placeholder.
     */
    @Synchronized
    fun pageAspectRatio(
        pageIndex: Int,
    ): Float {
        return pageAspectRatios[
            pageIndex
        ] ?: defaultPageAspectRatio
    }

    /*
     * Prefer an exact cached raster.
     *
     * When the requested zoom resolution is new, keep showing the closest
     * existing raster instead of replacing the page with a spinner while
     * PdfRenderer catches up.
     *
     * snapshot() does not keep extra cache ownership: it is only used to find
     * an already-LRU-managed Bitmap.
     */
    @Synchronized
    fun findCachedBitmap(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap? {
        val exactKey =
            PdfRenderKey(
                pageIndex =
                    pageIndex,
                targetWidthPx =
                    targetWidthPx,
            )

        renderedPages
            .get(
                exactKey
            )
            ?.let {
                return it
            }

        return renderedPages
            .snapshot()
            .entries
            .asSequence()
            .filter { entry ->
                entry.key.pageIndex ==
                        pageIndex &&
                        !entry.value.isRecycled
            }
            .minByOrNull { entry ->
                abs(
                    entry.key.targetWidthPx -
                            targetWidthPx
                )
            }
            ?.value
    }

    @Synchronized
    fun render(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap? {
        /*
         * A queued render may acquire this monitor after close() has already
         * closed PdfRenderer.
         */
        if (
            closed
        ) {
            return null
        }

        val cacheKey =
            PdfRenderKey(
                pageIndex =
                    pageIndex,
                targetWidthPx =
                    targetWidthPx,
            )

        renderedPages
            .get(
                cacheKey
            )
            ?.let {
                return it
            }

        renderer
            .openPage(
                pageIndex
            )
            .use { page ->
                /*
                 * Record the real geometry before allocating the Bitmap.
                 */
                pageAspectRatios[
                    pageIndex
                ] =
                    page.width
                        .toFloat() /
                            page.height
                                .toFloat()

                val requestedScale =
                    targetWidthPx
                        .toDouble() /
                            page.width
                                .toDouble()

                /*
                 * ARGB_8888 uses four bytes per pixel.
                 *
                 * Keep the existing hard single-page memory boundary.
                 */
                val pagePixels =
                    page.width
                        .toDouble() *
                            page.height
                                .toDouble()

                val maxScaleByMemory =
                    sqrt(
                        PDF_MAX_BITMAP_PIXELS
                            .toDouble() /
                                pagePixels
                    )

                val actualScale =
                    minOf(
                        requestedScale,
                        maxScaleByMemory,
                    )

                val bitmapWidth =
                    (
                            page.width *
                                    actualScale
                            )
                        .roundToInt()
                        .coerceAtLeast(
                            1
                        )

                val bitmapHeight =
                    (
                            page.height *
                                    actualScale
                            )
                        .roundToInt()
                        .coerceAtLeast(
                            1
                        )

                val bitmap =
                    Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        Bitmap.Config
                            .ARGB_8888,
                    )

                bitmap.eraseColor(
                    android.graphics.Color
                        .WHITE
                )

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page
                        .RENDER_MODE_FOR_DISPLAY,
                )

                /*
                 * render() and close() share the same monitor, so the renderer
                 * cannot be closed while this Bitmap is being produced.
                 */
                renderedPages.put(
                    cacheKey,
                    bitmap,
                )

                return bitmap
            }
    }

    @Synchronized
    fun close() {
        if (
            closed
        ) {
            return
        }

        closed =
            true

        /*
         * Do not recycle manually. Compose may still hold a short-lived
         * reference to an evicted Bitmap.
         */
        renderedPages.evictAll()

        pageAspectRatios.clear()

        renderer.close()
    }
}

private data class PdfRenderKey(
    val pageIndex: Int,
    val targetWidthPx: Int,
)

private fun quantizePdfRenderScale(
    zoom: Float,
): Float {
    val capped =
        zoom.coerceIn(
            MIN_PDF_ZOOM,
            MAX_PDF_RASTER_SCALE,
        )

    val steps =
        (
                capped /
                        PDF_RENDER_SCALE_STEP
                )
            .roundToInt()

    return (
            steps *
                    PDF_RENDER_SCALE_STEP
            )
        .coerceIn(
            MIN_PDF_ZOOM,
            MAX_PDF_RASTER_SCALE,
        )
}

private const val MIN_PDF_ZOOM =
    1f

/*
 * 3x is plenty for this lightweight preview. Only the first 2x is actually
 * rerasterized; the final 2x -> 3x range is GPU interpolation so bitmap
 * memory cannot grow without bound.
 */
private const val MAX_PDF_ZOOM =
    3f

private const val MAX_PDF_RASTER_SCALE =
    2f

private const val PDF_RENDER_SCALE_STEP =
    0.25f

private const val PDF_RENDER_SETTLE_MILLIS =
    150L

private const val PDF_ZOOM_EPSILON =
    0.001f

/*
 * Cache remains deliberately modest. LazyColumn normally keeps only one or
 * a few page images alive near the viewport.
 */
private const val PDF_BITMAP_CACHE_KIB =
    24 * 1024

/*
 * Hard limit for a single rendered PDF page.
 *
 * ARGB_8888 = 4 bytes/pixel.
 */
private const val PDF_MAX_BITMAP_BYTES =
    24L * 1024L * 1024L

private const val PDF_ARGB_BYTES_PER_PIXEL =
    4L

private const val PDF_MAX_BITMAP_PIXELS =
    PDF_MAX_BITMAP_BYTES /
            PDF_ARGB_BYTES_PER_PIXEL