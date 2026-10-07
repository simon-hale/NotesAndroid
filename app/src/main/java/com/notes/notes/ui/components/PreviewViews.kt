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
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
                             * Keep a reference before returning from the IO
                             * dispatcher. If cancellation happens while switching
                             * back to the producer context, finally can still
                             * close the renderer.
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
                    /*
                     * Cleanup itself must survive cancellation, while the
                     * potentially blocking PdfRenderer close still belongs on IO.
                     */
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

    var zoom by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            MIN_PDF_ZOOM
        )
    }

    /*
     * `zoom` controls the visual/layout width immediately.
     *
     * `renderScale` changes only after the pinch settles, so PdfRenderer is
     * not asked to rerasterize every frame of the gesture.
     */
    var renderScale by
    remember(
        filePath
    ) {
        mutableFloatStateOf(
            MIN_PDF_ZOOM
        )
    }

    /*
     * A new document always starts fit-to-width at the first page.
     */
    LaunchedEffect(
        filePath
    ) {
        zoom =
            MIN_PDF_ZOOM

        renderScale =
            MIN_PDF_ZOOM

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
     * Debounce expensive native rasterization and quantize the requested
     * resolution. A slow pinch therefore produces at most a few useful
     * resolution levels rather than dozens of nearly identical bitmaps.
     */
    LaunchedEffect(
        zoom
    ) {
        delay(
            PDF_RENDER_SETTLE_MILLIS
        )

        renderScale =
            quantizePdfRenderScale(
                zoom
            )
    }

    /*
     * Returning to fit-width also returns horizontal position to the left
     * edge, avoiding a stale pan offset if the user zooms in again later.
     */
    LaunchedEffect(
        zoom <=
                MIN_PDF_ZOOM +
                PDF_ZOOM_EPSILON
    ) {
        if (
            zoom <=
            MIN_PDF_ZOOM +
            PDF_ZOOM_EPSILON
        ) {
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
                /*
                 * Only intercept an actual multi-touch gesture.
                 *
                 * A normal one-finger gesture is deliberately left
                 * unconsumed:
                 *   vertical -> LazyColumn
                 *   horizontal while zoomed -> horizontalScroll
                 */
                .pointerInput(
                    filePath
                ) {
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
                                multiTouchStarted =
                                    true

                                val zoomChange =
                                    event.calculateZoom()

                                if (
                                    zoomChange.isFinite() &&
                                    zoomChange >
                                    0f
                                ) {
                                    zoom =
                                        (
                                                zoom *
                                                        zoomChange
                                                )
                                            .coerceIn(
                                                MIN_PDF_ZOOM,
                                                MAX_PDF_ZOOM,
                                            )
                                }

                                event.changes
                                    .forEach {
                                        it.consume()
                                    }
                            } else if (
                                multiTouchStarted
                            ) {
                                /*
                                 * Once a gesture became a pinch, consume the
                                 * remainder until every finger is lifted.
                                 *
                                 * Otherwise the last remaining finger could
                                 * suddenly turn into a LazyColumn scroll.
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

        val contentWidth =
            with(
                density
            ) {
                (
                        viewportWidthPx
                            .toFloat() *
                                zoom
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
                            zoom >
                                    MIN_PDF_ZOOM +
                                    PDF_ZOOM_EPSILON,
                    ),
        ) {
            LazyColumn(
                state =
                    listState,
                modifier =
                    Modifier
                        .width(
                            contentWidth
                        )
                        .fillMaxHeight(),
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
                     * produceState retains its previous state while the
                     * keyed producer restarts, so an existing lower-
                     * resolution bitmap remains visible while the sharper
                     * version is rendered in the background.
                     */
                    val bitmap =
                        produceState<Bitmap?>(
                            initialValue =
                                null,
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

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
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
                                    Modifier
                                        .fillMaxWidth()
                                        .height(
                                            200.dp
                                        ),
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
                                    Modifier
                                        .fillMaxWidth(),
                                contentScale =
                                    ContentScale
                                        .FillWidth,
                            )
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
     * PdfRenderer owns this ParcelFileDescriptor after construction
     * succeeds. Do not close it separately from renderer.close().
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

    private var closed = false

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

    @Synchronized
    fun render(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap? {
        /*
         * A queued render may acquire this monitor after close() has already
         * closed PdfRenderer. Treat that render as cancelled instead of touching
         * the closed native renderer.
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
                val requestedScale =
                    targetWidthPx
                        .toDouble() /
                            page.width
                                .toDouble()

                /*
                 * ARGB_8888 uses four bytes per pixel.
                 *
                 * Full-page high-resolution rendering is intentionally bounded.
                 * This prevents an unusually tall/wide PDF page or a large zoom
                 * level from allocating an enormous bitmap.
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
                 * close() cannot run concurrently because both methods use the
                 * same monitor, so reaching here guarantees the holder is still
                 * open.
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
        if (closed) return

        /*
         * Flip the state while holding the same monitor used by render().
         * Any render waiting behind this close will see closed == true.
         */
        closed = true

        /*
         * Do not recycle the Bitmap objects manually. A Compose Image may still
         * briefly hold a reference after the cache releases it.
         */
        renderedPages.evictAll()

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