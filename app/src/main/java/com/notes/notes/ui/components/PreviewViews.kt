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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.notes.notes.ui.theme.LocalNotesExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HtmlPreviewView(html: String, modifier: Modifier = Modifier) {
    val holder = remember { HtmlPreviewWebViewHolder() }
    DisposableEffect(holder) {
        onDispose { holder.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = false
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                holder.webView = this
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}

@Composable
fun PdfPreviewView(filePath: String, pageCount: Int, modifier: Modifier = Modifier) {
    val holder = remember(filePath) { PdfRendererHolder(File(filePath)) }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { (configuration.screenWidthDp.dp - 32.dp).roundToPx() }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = pageCount, key = { it }) { pageIndex ->
            val bitmap = produceState<Bitmap?>(initialValue = null, filePath, pageIndex, targetWidthPx) {
                value = withContext(Dispatchers.IO) {
                    holder.render(pageIndex, targetWidthPx)
                }
            }.value
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, LocalNotesExtraColors.current.borderStrong),
            ) {
                if (bitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}

private class PdfRendererHolder(private val file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val renderedPages = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Synchronized
    fun render(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val cacheKey = "$pageIndex@$targetWidthPx"
        renderedPages.get(cacheKey)?.let { return it }
        renderer.openPage(pageIndex).use { page ->
            val scale = targetWidthPx.toFloat() / page.width.toFloat()
            val bitmap = Bitmap.createBitmap(
                targetWidthPx,
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            renderedPages.put(cacheKey, bitmap)
            return bitmap
        }
    }

    @Synchronized
    fun close() {
        val cachedBitmaps = renderedPages.snapshot().values.toList()
        renderedPages.evictAll()
        cachedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        renderer.close()
        descriptor.close()
    }
}

private class HtmlPreviewWebViewHolder {
    var webView: WebView? = null

    fun release() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
    }
}
