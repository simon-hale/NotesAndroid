package com.notes.notes.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notes.notes.core.HtmlPreviewStyle
import com.notes.notes.core.PreviewContent
import com.notes.notes.core.ThemePalette
import kotlinx.coroutines.CancellationException
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.security.MessageDigest

class PreviewRepository(
    private val context: Context,
    private val backendService: NotesBackendService,
) {

    private val markdownExtensions: List<Extension> = listOf(
        AutolinkExtension.create(),
        TablesExtension.create(),
        StrikethroughExtension.create(),
    )

    private val markdownParser = Parser.builder()
        .extensions(markdownExtensions)
        .build()

    private val markdownRenderer = HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(true)
        .build()

    suspend fun loadPreview(
        descriptor: FilePreviewDescriptor,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
        markdownLoadFailed: String,
    ): LoadedPreview {
        return when (normalizePreviewType(descriptor.type)) {
            PreviewType.PDF -> loadPdfPreview(descriptor.url, fileName)
            PreviewType.MARKDOWN -> loadMarkdownPreview(
                url = descriptor.url,
                fileName = fileName,
                isDarkTheme = isDarkTheme,
                themePalette = themePalette,
                markdownLoadFailed = markdownLoadFailed,
            )
            PreviewType.WORD -> loadWordPreview(
                url = descriptor.url,
                fileName = fileName,
                isDarkTheme = isDarkTheme,
                themePalette = themePalette,
                officePreviewHint = officePreviewHint,
            )
            PreviewType.EXCEL -> loadExcelPreview(
                url = descriptor.url,
                fileName = fileName,
                isDarkTheme = isDarkTheme,
                themePalette = themePalette,
                officePreviewHint = officePreviewHint,
            )
            PreviewType.PPT -> loadPptPreview(
                url = descriptor.url,
                fileName = fileName,
                isDarkTheme = isDarkTheme,
                themePalette = themePalette,
                officePreviewHint = officePreviewHint,
            )
            PreviewType.UNSUPPORTED -> LoadedPreview(
                content = PreviewContent.Error(fileName, "Unsupported file type."),
            )
        }
    }

    private suspend fun loadPdfPreview(url: String, fileName: String): LoadedPreview {
        return loadCachedBinaryPreview(url, "pdf") { file ->
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pageCount = PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            descriptor.close()
            LoadedPreview(
                content = PreviewContent.Pdf(
                    title = fileName,
                    filePath = file.absolutePath,
                    pageCount = pageCount,
                ),
                cacheFiles = listOf(file.absolutePath),
            )
        }
    }

    private suspend fun loadMarkdownPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        markdownLoadFailed: String,
    ): LoadedPreview {
        val markdownText = try {
            backendService.downloadText(url)
        } catch (_: Throwable) {
            return LoadedPreview(
                content = PreviewContent.Error(fileName, markdownLoadFailed),
            )
        }
        val html = markdownRenderer.render(markdownParser.parse(markdownText))
        return LoadedPreview(
            content = PreviewContent.Html(
                title = fileName,
                html = wrapHtmlDocument(
                    title = fileName,
                    body = html,
                    isDarkTheme = isDarkTheme,
                    themePalette = themePalette,
                    style = HtmlPreviewStyle.MARKDOWN,
                ),
                style = HtmlPreviewStyle.MARKDOWN,
            ),
        )
    }

    private suspend fun loadWordPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(url, "docx") { file ->
            val bodyHtml = XWPFDocument(file.inputStream()).use { document ->
                buildString {
                    append("<div class=\"preview-note\">${escapeHtml(officePreviewHint)}</div>")
                    document.bodyElements.forEach { element ->
                        when (element) {
                            is XWPFParagraph -> append(renderParagraph(element))
                            is XWPFTable -> append(renderTable(element))
                        }
                    }
                }
            }
            LoadedPreview(
                content = PreviewContent.Html(
                    title = fileName,
                    html = wrapHtmlDocument(
                        title = fileName,
                        body = bodyHtml,
                        isDarkTheme = isDarkTheme,
                        themePalette = themePalette,
                        style = HtmlPreviewStyle.DOCUMENT,
                    ),
                    style = HtmlPreviewStyle.DOCUMENT,
                ),
                cacheFiles = listOf(file.absolutePath),
            )
        }
    }

    private suspend fun loadExcelPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(url, "xlsx") { file ->
            val formatter = DataFormatter()
            val bodyHtml = WorkbookFactory.create(file, null, true).use { workbook ->
                buildString {
                    append("<div class=\"preview-note\">${escapeHtml(officePreviewHint)}</div>")
                    workbook.forEachIndexed { index, sheet ->
                        append("<section class=\"sheet-block\">")
                        append("<h2>${escapeHtml(sheet.sheetName.ifBlank { "Sheet ${index + 1}" })}</h2>")
                        append("<div class=\"table-scroll\"><table><tbody>")
                        val lastRow = minOf(sheet.lastRowNum, MAX_SHEET_ROWS - 1)
                        val lastColumn = minOf(sheet.getRow(0)?.lastCellNum?.toInt()?.coerceAtLeast(0) ?: 0, MAX_SHEET_COLUMNS)
                        for (rowIndex in 0..lastRow) {
                            val row = sheet.getRow(rowIndex)
                            append("<tr>")
                            for (columnIndex in 0 until lastColumn) {
                                val cellText = formatter.formatCellValue(row?.getCell(columnIndex)).orEmpty()
                                append("<td>${escapeHtml(cellText)}</td>")
                            }
                            append("</tr>")
                        }
                        append("</tbody></table></div>")
                        if (sheet.lastRowNum + 1 > MAX_SHEET_ROWS) {
                            append("<p class=\"truncated-note\">Only the first $MAX_SHEET_ROWS rows are shown.</p>")
                        }
                        append("</section>")
                    }
                }
            }
            LoadedPreview(
                content = PreviewContent.Html(
                    title = fileName,
                    html = wrapHtmlDocument(
                        title = fileName,
                        body = bodyHtml,
                        isDarkTheme = isDarkTheme,
                        themePalette = themePalette,
                        style = HtmlPreviewStyle.DOCUMENT,
                    ),
                    style = HtmlPreviewStyle.DOCUMENT,
                ),
                cacheFiles = listOf(file.absolutePath),
            )
        }
    }

    private suspend fun loadPptPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(url, "pptx") { file ->
            val bodyHtml = XMLSlideShow(file.inputStream()).use { slideShow ->
                buildString {
                    append("<div class=\"preview-note\">${escapeHtml(officePreviewHint)}</div>")
                    slideShow.slides.forEachIndexed { index, slide ->
                        append("<section class=\"slide-block\">")
                        append("<h2>Slide ${index + 1}</h2>")
                        val texts = slide.shapes.filterIsInstance<XSLFTextShape>()
                            .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
                        if (texts.isEmpty()) {
                            append("<p class=\"muted\">No text content on this slide.</p>")
                        } else {
                            append("<ul class=\"slide-list\">")
                            texts.forEach { text ->
                                append("<li>${escapeHtml(text)}</li>")
                            }
                            append("</ul>")
                        }
                        append("</section>")
                    }
                }
            }
            LoadedPreview(
                content = PreviewContent.Html(
                    title = fileName,
                    html = wrapHtmlDocument(
                        title = fileName,
                        body = bodyHtml,
                        isDarkTheme = isDarkTheme,
                        themePalette = themePalette,
                        style = HtmlPreviewStyle.DOCUMENT,
                    ),
                    style = HtmlPreviewStyle.DOCUMENT,
                ),
                cacheFiles = listOf(file.absolutePath),
            )
        }
    }

    private fun renderParagraph(paragraph: XWPFParagraph): String {
        val text = paragraph.text?.trim().orEmpty()
        if (text.isBlank()) return ""
        val style = paragraph.style.orEmpty().lowercase()
        val tag = when {
            style.contains("title") -> "h1"
            style.contains("heading1") || style == "1" -> "h1"
            style.contains("heading2") || style == "2" -> "h2"
            style.contains("heading3") || style == "3" -> "h3"
            else -> "p"
        }
        return "<$tag>${escapeHtml(text)}</$tag>"
    }

    private fun renderTable(table: XWPFTable): String {
        return buildString {
            append("<div class=\"table-scroll\"><table><tbody>")
            table.rows.forEach { row ->
                append("<tr>")
                row.tableCells.forEach { cell ->
                    append("<td>${escapeHtml(cell.text.orEmpty())}</td>")
                }
                append("</tr>")
            }
            append("</tbody></table></div>")
        }
    }

    private fun wrapHtmlDocument(
        title: String,
        body: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        style: HtmlPreviewStyle,
    ): String {
        val colors = when (style) {
            HtmlPreviewStyle.MARKDOWN -> previewColors(isDarkTheme, themePalette)
            HtmlPreviewStyle.DOCUMENT -> previewColors(isDarkTheme = false, palette = themePalette)
                .copy(background = "#FFFFFF", surface = "#FFFFFF")
        }
        val pageCss = when (style) {
            HtmlPreviewStyle.MARKDOWN -> """
                .page {
                  max-width: 960px;
                  margin: 0 auto;
                  padding: 18px 14px 28px;
                }
                .article {
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 24px;
                  padding: 22px 20px;
                  box-shadow: 0 1px 2px var(--shadow);
                }
            """.trimIndent()
            HtmlPreviewStyle.DOCUMENT -> """
                .page {
                  max-width: none;
                  margin: 0;
                  padding: 0;
                }
                .article {
                  background: var(--surface);
                  border: none;
                  border-radius: 0;
                  padding: 0;
                  box-shadow: none;
                }
            """.trimIndent()
        }
        return """
            <!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>${escapeHtml(title)}</title>
                <style>
                  :root {
                    color-scheme: ${if (style == HtmlPreviewStyle.MARKDOWN && isDarkTheme) "dark" else "light"};
                    --bg: ${colors.background};
                    --bg-soft: ${colors.backgroundSoft};
                    --surface: ${colors.surface};
                    --surface-strong: ${colors.surfaceStrong};
                    --text: ${colors.text};
                    --heading: ${colors.heading};
                    --muted: ${colors.textMuted};
                    --accent: ${colors.accent};
                    --border: ${colors.border};
                    --shadow: ${colors.shadow};
                  }
                  * { box-sizing: border-box; }
                  html, body {
                    margin: 0;
                    padding: 0;
                    background: var(--bg);
                    color: var(--text);
                    font-family: "Noto Sans SC", "SF Pro Text", "Segoe UI", sans-serif;
                  }
                  body {
                    line-height: 1.6;
                    font-size: 15px;
                  }
                  $pageCss
                  h1, h2, h3, h4, h5, h6 {
                    margin-top: 0;
                    color: var(--heading);
                    line-height: 1.35;
                  }
                  h1 { font-size: 1.34rem; margin-bottom: 0.7rem; }
                  h2 { font-size: 1.14rem; margin-top: 1.25rem; margin-bottom: 0.55rem; }
                  h3 { font-size: 1rem; margin-top: 1rem; margin-bottom: 0.45rem; }
                  p, li, td {
                    color: var(--text);
                  }
                  strong, b {
                    color: var(--heading);
                  }
                  a {
                    color: var(--accent);
                    text-decoration: none;
                  }
                  code, pre {
                    font-family: "JetBrains Mono", "Cascadia Mono", monospace;
                  }
                  pre {
                    overflow: auto;
                    padding: 12px;
                    border-radius: 12px;
                    background: var(--surface-strong);
                    border: 1px solid var(--border);
                  }
                  table {
                    width: 100%;
                    border-collapse: collapse;
                    background: var(--surface-strong);
                    border-radius: 12px;
                    overflow: hidden;
                  }
                  td, th {
                    min-width: 84px;
                    padding: 8px 10px;
                    border: 1px solid var(--border);
                    vertical-align: top;
                  }
                  blockquote {
                    margin: 0;
                    padding: 10px 12px;
                    border-left: 4px solid var(--accent);
                    background: var(--bg-soft);
                    border-radius: 12px;
                  }
                  img {
                    max-width: 100%;
                  }
                  .preview-note, .truncated-note, .muted {
                    margin: 0 0 14px;
                    padding: 10px 12px;
                    border-radius: 12px;
                    background: var(--surface);
                    border: 1px solid var(--border);
                    color: var(--muted);
                  }
                  .table-scroll {
                    overflow-x: auto;
                    margin: 12px 0;
                    border-radius: 12px;
                  }
                  .sheet-block, .slide-block {
                    margin-top: 14px;
                    padding: 14px;
                    border-radius: 14px;
                    background: var(--surface-strong);
                    border: 1px solid var(--border);
                  }
                  .slide-list {
                    padding-left: 20px;
                    margin: 0;
                  }
                </style>
              </head>
              <body>
                <main class="page">
                  <article class="article">
                    $body
                  </article>
                </main>
              </body>
            </html>
        """.trimIndent()
    }

    fun deleteCacheFiles(paths: Collection<String>) {
        PreviewCacheCleaner.deleteFiles(context, paths)
    }

    fun clearAllPreviewCache() {
        PreviewCacheCleaner.clearAll(context)
    }

    private fun cacheFile(url: String, extension: String): File {
        return File(previewCacheDir(), "${url.md5()}.$extension")
    }

    private suspend fun ensureCachedDownload(url: String, destination: File, forceDownload: Boolean = false) {
        if (!forceDownload && destination.exists() && destination.length() > 0L) {
            return
        }
        backendService.downloadToFile(url, destination)
    }

    private suspend fun <T> loadCachedBinaryPreview(
        url: String,
        extension: String,
        parser: (File) -> T,
    ): T {
        val file = cacheFile(url, extension)
        val hadCachedFile = file.exists() && file.length() > 0L
        ensureCachedDownload(url, file)
        return runCatching {
            parser(file)
        }.recoverCatching { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            if (!hadCachedFile) {
                throw throwable
            }
            deleteCacheFiles(listOf(file.absolutePath))
            ensureCachedDownload(url, file, forceDownload = true)
            parser(file)
        }.getOrThrow()
    }

    private fun previewCacheDir(): File {
        return File(context.cacheDir, "preview-cache").apply { mkdirs() }
    }

    private fun normalizePreviewType(raw: String): PreviewType = when (raw.lowercase()) {
        "pdf" -> PreviewType.PDF
        "md", "markdown" -> PreviewType.MARKDOWN
        "docx" -> PreviewType.WORD
        "xlsx", "xls" -> PreviewType.EXCEL
        "pptx" -> PreviewType.PPT
        else -> PreviewType.UNSUPPORTED
    }

    private fun previewColors(isDarkTheme: Boolean, palette: ThemePalette): PreviewColors {
        val accent = when (palette) {
            ThemePalette.BLUE -> "#4F9CFF"
            ThemePalette.EMERALD -> "#27B47E"
            ThemePalette.AMBER -> "#D8921E"
            ThemePalette.ROSE -> "#D65B8D"
            ThemePalette.SAGE -> "#6E8A63"
            ThemePalette.ALMOND -> "#B28B49"
        }
        return if (isDarkTheme) {
            PreviewColors(
                background = "#0B1118",
                backgroundSoft = "#111A24",
                surface = "#101923",
                surfaceStrong = "#16212C",
                text = "#F4F8FC",
                heading = "#FFFFFF",
                textMuted = "#C1CDD8",
                accent = accent,
                border = "#233241",
                shadow = "rgba(5, 10, 18, 0.35)",
            )
        } else {
            PreviewColors(
                background = "#EEF2F5",
                backgroundSoft = "#F6F8FA",
                surface = "#FFFFFF",
                surfaceStrong = "#F6F9FB",
                text = "#17212B",
                heading = "#111A24",
                textMuted = "#5F6E80",
                accent = accent,
                border = "#D6E1EC",
                shadow = "rgba(23, 33, 43, 0.08)",
            )
        }
    }

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\n", "<br/>")

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class PreviewColors(
        val background: String,
        val backgroundSoft: String,
        val surface: String,
        val surfaceStrong: String,
        val text: String,
        val heading: String,
        val textMuted: String,
        val accent: String,
        val border: String,
        val shadow: String,
    )

    private enum class PreviewType {
        PDF,
        MARKDOWN,
        WORD,
        EXCEL,
        PPT,
        UNSUPPORTED,
    }

    private companion object {
        const val MAX_SHEET_ROWS = 200
        const val MAX_SHEET_COLUMNS = 40
    }
}

data class LoadedPreview(
    val content: PreviewContent,
    val cacheFiles: List<String> = emptyList(),
)
