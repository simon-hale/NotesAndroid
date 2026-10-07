package com.notes.notes.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notes.notes.core.HtmlPreviewStyle
import com.notes.notes.core.PreviewContent
import com.notes.notes.core.ThemePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.autolink.AutolinkType
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.security.MessageDigest

class PreviewRepository(
    private val context: Context,
    private val backendService: NotesBackendService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /*
 * Apache POI builds an in-memory object model for DOCX/XLSX/PPTX.
 *
 * Only one Office document is allowed to enter that object-model parsing
 * stage at a time. A newly selected Office file may still download while an
 * older parser finishes, but two large POI models will not coexist merely
 * because the user switched previews quickly.
 */
    private val officePreviewMutex = Mutex()

    /*
     * commonmark-java 0.27+ also recognizes bare "www." links when using
     * AutolinkExtension.create().
     *
     * Explicit URL + EMAIL keeps the old 0.24 behavior while allowing us to
     * upgrade to 0.30 for its parser-performance and nesting protections.
     */
    private val markdownExtensions: List<Extension> =
        listOf(
            AutolinkExtension
                .builder()
                .linkTypes(
                    AutolinkType.URL,
                    AutolinkType.EMAIL,
                )
                .build(),
            TablesExtension
                .builder()
                .maxCells(
                    MAX_MARKDOWN_TABLE_CELLS
                )
                .build(),
            StrikethroughExtension.create(),
        )

    private val markdownParser =
        Parser
            .builder()
            .extensions(
                markdownExtensions
            )
            .build()

    private val markdownRenderer =
        HtmlRenderer
            .builder()
            .extensions(
                markdownExtensions
            )
            .escapeHtml(true)
            .build()

    suspend fun loadPreview(
        descriptor: FilePreviewDescriptor,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
        markdownLoadFailed: String,
        previewTruncated: String,
        previewTooLarge: String,
    ): LoadedPreview {
        return try {
            when (
                normalizePreviewType(
                    descriptor.type
                )
            ) {
                PreviewType.PDF ->
                    loadPdfPreview(
                        url =
                            descriptor.url,
                        fileName =
                            fileName,
                    )

                PreviewType.MARKDOWN ->
                    loadMarkdownPreview(
                        url =
                            descriptor.url,
                        fileName =
                            fileName,
                        isDarkTheme =
                            isDarkTheme,
                        themePalette =
                            themePalette,
                        markdownLoadFailed =
                            markdownLoadFailed,
                        previewTruncated =
                            previewTruncated,
                    )

                PreviewType.WORD ->
                    loadWordPreview(
                        url =
                            descriptor.url,
                        fileName =
                            fileName,
                        isDarkTheme =
                            isDarkTheme,
                        themePalette =
                            themePalette,
                        officePreviewHint =
                            officePreviewHint,
                        previewTruncated =
                            previewTruncated,
                        previewTooLarge =
                            previewTooLarge,
                    )

                PreviewType.EXCEL ->
                    loadExcelPreview(
                        url =
                            descriptor.url,
                        fileName =
                            fileName,
                        isDarkTheme =
                            isDarkTheme,
                        themePalette =
                            themePalette,
                        officePreviewHint =
                            officePreviewHint,
                        previewTruncated =
                            previewTruncated,
                        previewTooLarge =
                            previewTooLarge,
                    )

                PreviewType.PPT ->
                    loadPptPreview(
                        url =
                            descriptor.url,
                        fileName =
                            fileName,
                        isDarkTheme =
                            isDarkTheme,
                        themePalette =
                            themePalette,
                        officePreviewHint =
                            officePreviewHint,
                        previewTruncated =
                            previewTruncated,
                        previewTooLarge =
                            previewTooLarge,
                    )

                PreviewType.UNSUPPORTED ->
                    LoadedPreview(
                        content =
                            PreviewContent.Error(
                                fileName,
                                "Unsupported file type.",
                            ),
                    )
            }
        } catch (
            cancellation:
            CancellationException
        ) {
            throw cancellation
        } catch (
            _: DownloadSizeLimitExceededException
        ) {
            LoadedPreview(
                content =
                    PreviewContent.Error(
                        title =
                            fileName,
                        message =
                            previewTooLarge,
                    ),
            )
        }
    }

    private suspend fun loadPdfPreview(
        url: String,
        fileName: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(
            url = url,
            extension = "pdf",
        ) { file ->
            withContext(
                ioDispatcher
            ) {
                /*
                 * Keep this lightweight open instead of moving pageCount into
                 * PdfPreviewView:
                 *
                 * besides reading pageCount it validates an existing cached
                 * PDF, allowing loadCachedBinaryPreview() to discard and
                 * download a stale/corrupt cache entry once.
                 */
                val descriptor =
                    ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )

                val renderer =
                    try {
                        PdfRenderer(
                            descriptor
                        )
                    } catch (
                        throwable: Throwable
                    ) {
                        /*
                         * PdfRenderer only owns the descriptor after its
                         * constructor succeeds.
                         */
                        descriptor.close()
                        throw throwable
                    }

                val pageCount =
                    renderer.use {
                        it.pageCount
                    }

                LoadedPreview(
                    content =
                        PreviewContent.Pdf(
                            title =
                                fileName,
                            filePath =
                                file.absolutePath,
                            pageCount =
                                pageCount,
                        ),
                    cacheFiles =
                        listOf(
                            file.absolutePath
                        ),
                )
            }
        }
    }

    private suspend fun loadMarkdownPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        markdownLoadFailed: String,
        previewTruncated: String,
    ): LoadedPreview {
        val downloaded =
            try {
                backendService
                    .downloadTextPreview(
                        url = url,
                        maxChars =
                            MAX_MARKDOWN_CHARS,
                    )
            } catch (
                cancellation:
                CancellationException
            ) {
                throw cancellation
            } catch (
                _: Throwable
            ) {
                return LoadedPreview(
                    content =
                        PreviewContent.Error(
                            fileName,
                            markdownLoadFailed,
                        ),
                )
            }

        return try {
            withContext(
                defaultDispatcher
            ) {
                val markdownHtml =
                    markdownRenderer.render(
                        markdownParser.parse(
                            downloaded.text
                        )
                    )

                val bodyHtml =
                    if (
                        downloaded.truncated
                    ) {
                        buildString {
                            append(
                                markdownHtml
                            )
                            append(
                                renderTruncatedNote(
                                    previewTruncated
                                )
                            )
                        }
                    } else {
                        markdownHtml
                    }

                LoadedPreview(
                    content =
                        PreviewContent.Html(
                            title =
                                fileName,
                            html =
                                wrapHtmlDocument(
                                    title =
                                        fileName,
                                    body =
                                        bodyHtml,
                                    isDarkTheme =
                                        isDarkTheme,
                                    themePalette =
                                        themePalette,
                                    style =
                                        HtmlPreviewStyle
                                            .MARKDOWN,
                                ),
                            style =
                                HtmlPreviewStyle
                                    .MARKDOWN,
                        ),
                )
            }
        } catch (
            cancellation:
            CancellationException
        ) {
            throw cancellation
        } catch (
            _: Throwable
        ) {
            LoadedPreview(
                content =
                    PreviewContent.Error(
                        fileName,
                        markdownLoadFailed,
                    ),
            )
        }
    }

    private suspend fun loadWordPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
        previewTruncated: String,
        previewTooLarge: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(
            url =
                url,
            extension =
                "docx",
            maxDownloadBytes =
                MAX_OFFICE_PREVIEW_FILE_BYTES,
        ) { file ->
            withContext(
                ioDispatcher
            ) {
                /*
                 * Waiting for this Mutex is cancellable. Once acquired, the POI
                 * constructor itself is synchronous, so the next Office preview
                 * waits instead of building another large POI model concurrently.
                 */
                officePreviewMutex.withLock {
                    val parsingContext =
                        currentCoroutineContext()

                    parsingContext.ensureActive()

                    oversizedOfficePreview(
                        file =
                            file,
                        fileName =
                            fileName,
                        previewTooLarge =
                            previewTooLarge,
                    )?.let {
                        return@withLock it
                    }

                    openWordDocument(
                        file
                    ).use { document ->
                        /*
                         * XWPFDocument construction itself cannot be cooperatively
                         * interrupted. If cancellation happened while POI was
                         * opening the package, stop immediately afterwards instead
                         * of continuing into HTML generation.
                         */
                        parsingContext.ensureActive()

                        val budget =
                            BoundedHtmlBuilder(
                                MAX_OFFICE_HTML_CHARS
                            )

                        budget.append(
                            renderPreviewNote(
                                officePreviewHint
                            )
                        )

                        val elements =
                            document.bodyElements

                        val elementCount =
                            minOf(
                                elements.size,
                                MAX_WORD_BODY_ELEMENTS,
                            )

                        for (
                        index in
                        0 until elementCount
                        ) {
                            /*
                             * Word documents can contain many body elements.
                             * Observe cancellation between elements so a stale
                             * preview stops generating HTML promptly.
                             */
                            parsingContext.ensureActive()

                            val html =
                                when (
                                    val element =
                                        elements[index]
                                ) {
                                    is XWPFParagraph ->
                                        renderParagraph(
                                            element
                                        )

                                    is XWPFTable ->
                                        renderTable(
                                            table =
                                                element,
                                            previewTruncated =
                                                previewTruncated,
                                        )

                                    else ->
                                        ""
                                }

                            if (
                                html.isNotEmpty() &&
                                !budget.append(
                                    html
                                )
                            ) {
                                break
                            }
                        }

                        if (
                            elements.size >
                            MAX_WORD_BODY_ELEMENTS
                        ) {
                            budget.markTruncated()
                        }

                        parsingContext.ensureActive()

                        LoadedPreview(
                            content =
                                PreviewContent.Html(
                                    title =
                                        fileName,
                                    html =
                                        wrapHtmlDocument(
                                            title =
                                                fileName,
                                            body =
                                                finishBudget(
                                                    budget =
                                                        budget,
                                                    previewTruncated =
                                                        previewTruncated,
                                                ),
                                            isDarkTheme =
                                                isDarkTheme,
                                            themePalette =
                                                themePalette,
                                            style =
                                                HtmlPreviewStyle
                                                    .DOCUMENT,
                                        ),
                                    style =
                                        HtmlPreviewStyle
                                            .DOCUMENT,
                                ),
                            cacheFiles =
                                listOf(
                                    file.absolutePath
                                ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadExcelPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
        previewTruncated: String,
        previewTooLarge: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(
            url =
                url,
            extension =
                "xlsx",
            maxDownloadBytes =
                MAX_OFFICE_PREVIEW_FILE_BYTES,
        ) { file ->
            withContext(
                ioDispatcher
            ) {
                officePreviewMutex.withLock {
                    val parsingContext =
                        currentCoroutineContext()

                    parsingContext.ensureActive()

                    oversizedOfficePreview(
                        file =
                            file,
                        fileName =
                            fileName,
                        previewTooLarge =
                            previewTooLarge,
                    )?.let {
                        return@withLock it
                    }

                    val formatter =
                        DataFormatter()

                    WorkbookFactory
                        .create(
                            file,
                            null,
                            true,
                        )
                        .use { workbook ->
                            /*
                             * WorkbookFactory.create() is synchronous. If this
                             * preview became stale while POI was constructing the
                             * workbook, stop before walking sheets and cells.
                             */
                            parsingContext.ensureActive()

                            val budget =
                                BoundedHtmlBuilder(
                                    MAX_OFFICE_HTML_CHARS
                                )

                            budget.append(
                                renderPreviewNote(
                                    officePreviewHint
                                )
                            )

                            val sheetCount =
                                minOf(
                                    workbook.numberOfSheets,
                                    MAX_SHEET_COUNT,
                                )

                            for (
                            index in
                            0 until sheetCount
                            ) {
                                /*
                                 * renderSheet() is already bounded to a limited
                                 * number of rows/columns. Checking between sheets
                                 * is enough to make stale work terminate promptly
                                 * without adding cancellation checks to every cell.
                                 */
                                parsingContext.ensureActive()

                                val section =
                                    renderSheet(
                                        sheet =
                                            workbook.getSheetAt(
                                                index
                                            ),
                                        index =
                                            index,
                                        formatter =
                                            formatter,
                                        previewTruncated =
                                            previewTruncated,
                                    )

                                if (
                                    !budget.append(
                                        section
                                    )
                                ) {
                                    break
                                }
                            }

                            if (
                                workbook.numberOfSheets >
                                MAX_SHEET_COUNT
                            ) {
                                budget.markTruncated()
                            }

                            parsingContext.ensureActive()

                            LoadedPreview(
                                content =
                                    PreviewContent.Html(
                                        title =
                                            fileName,
                                        html =
                                            wrapHtmlDocument(
                                                title =
                                                    fileName,
                                                body =
                                                    finishBudget(
                                                        budget =
                                                            budget,
                                                        previewTruncated =
                                                            previewTruncated,
                                                    ),
                                                isDarkTheme =
                                                    isDarkTheme,
                                                themePalette =
                                                    themePalette,
                                                style =
                                                    HtmlPreviewStyle
                                                        .DOCUMENT,
                                            ),
                                        style =
                                            HtmlPreviewStyle
                                                .DOCUMENT,
                                    ),
                                cacheFiles =
                                    listOf(
                                        file.absolutePath
                                    ),
                            )
                        }
                }
            }
        }
    }

    private suspend fun loadPptPreview(
        url: String,
        fileName: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        officePreviewHint: String,
        previewTruncated: String,
        previewTooLarge: String,
    ): LoadedPreview {
        return loadCachedBinaryPreview(
            url =
                url,
            extension =
                "pptx",
            maxDownloadBytes =
                MAX_OFFICE_PREVIEW_FILE_BYTES,
        ) { file ->
            withContext(
                ioDispatcher
            ) {
                officePreviewMutex.withLock {
                    val parsingContext =
                        currentCoroutineContext()

                    parsingContext.ensureActive()

                    oversizedOfficePreview(
                        file =
                            file,
                        fileName =
                            fileName,
                        previewTooLarge =
                            previewTooLarge,
                    )?.let {
                        return@withLock it
                    }

                    openSlideShow(
                        file
                    ).use { slideShow ->
                        /*
                         * XMLSlideShow construction is synchronous. Observe any
                         * cancellation that happened while POI was opening it
                         * before generating slide HTML.
                         */
                        parsingContext.ensureActive()

                        val budget =
                            BoundedHtmlBuilder(
                                MAX_OFFICE_HTML_CHARS
                            )

                        budget.append(
                            renderPreviewNote(
                                officePreviewHint
                            )
                        )

                        val slides =
                            slideShow.slides

                        val slideCount =
                            minOf(
                                slides.size,
                                MAX_PPT_SLIDES,
                            )

                        for (
                        index in
                        0 until slideCount
                        ) {
                            parsingContext.ensureActive()

                            val slideHtml =
                                renderSlide(
                                    slide =
                                        slides[index],
                                    index =
                                        index,
                                    previewTruncated =
                                        previewTruncated,
                                )

                            if (
                                !budget.append(
                                    slideHtml
                                )
                            ) {
                                break
                            }
                        }

                        if (
                            slides.size >
                            MAX_PPT_SLIDES
                        ) {
                            budget.markTruncated()
                        }

                        parsingContext.ensureActive()

                        LoadedPreview(
                            content =
                                PreviewContent.Html(
                                    title =
                                        fileName,
                                    html =
                                        wrapHtmlDocument(
                                            title =
                                                fileName,
                                            body =
                                                finishBudget(
                                                    budget =
                                                        budget,
                                                    previewTruncated =
                                                        previewTruncated,
                                                ),
                                            isDarkTheme =
                                                isDarkTheme,
                                            themePalette =
                                                themePalette,
                                            style =
                                                HtmlPreviewStyle
                                                    .DOCUMENT,
                                        ),
                                    style =
                                        HtmlPreviewStyle
                                            .DOCUMENT,
                                ),
                            cacheFiles =
                                listOf(
                                    file.absolutePath
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun openWordDocument(
        file: File,
    ): XWPFDocument {
        val packageFile =
            OPCPackage.open(
                file,
                PackageAccess.READ,
            )

        return try {
            XWPFDocument(
                packageFile
            )
        } catch (
            throwable: Throwable
        ) {
            runCatching {
                packageFile.close()
            }

            throw throwable
        }
    }

    private fun openSlideShow(
        file: File,
    ): XMLSlideShow {
        val packageFile =
            OPCPackage.open(
                file,
                PackageAccess.READ,
            )

        return try {
            XMLSlideShow(
                packageFile
            )
        } catch (
            throwable: Throwable
        ) {
            runCatching {
                packageFile.close()
            }

            throw throwable
        }
    }

    private fun oversizedOfficePreview(
        file: File,
        fileName: String,
        previewTooLarge: String,
    ): LoadedPreview? {
        if (
            file.length() <=
            MAX_OFFICE_PREVIEW_FILE_BYTES
        ) {
            return null
        }

        return LoadedPreview(
            content =
                PreviewContent.Error(
                    title =
                        fileName,
                    message =
                        previewTooLarge,
                ),
            /*
             * Keep the file in preview-cache for the lifetime of this
             * preview. It is still removed when another preview replaces it,
             * on session cleanup, or by the 24-hour stale-cache sweep.
             */
            cacheFiles =
                listOf(
                    file.absolutePath
                ),
        )
    }

    private fun renderParagraph(
        paragraph: XWPFParagraph,
    ): String {
        val rawText =
            paragraph.text
                ?.trim()
                .orEmpty()

        if (
            rawText.isBlank()
        ) {
            return ""
        }

        val text =
            limitPlainText(
                raw =
                    rawText,
                maxChars =
                    MAX_WORD_PARAGRAPH_CHARS,
            )

        val style =
            paragraph.style
                .orEmpty()
                .lowercase()

        val tag =
            when {
                style.contains(
                    "title"
                ) ->
                    "h1"

                style.contains(
                    "heading1"
                ) ||
                        style == "1" ->
                    "h1"

                style.contains(
                    "heading2"
                ) ||
                        style == "2" ->
                    "h2"

                style.contains(
                    "heading3"
                ) ||
                        style == "3" ->
                    "h3"

                else ->
                    "p"
            }

        return "<$tag>${escapeHtml(text)}</$tag>"
    }

    private fun renderTable(
        table: XWPFTable,
        previewTruncated: String,
    ): String {
        val rows =
            table.rows

        val rowCount =
            minOf(
                rows.size,
                MAX_WORD_TABLE_ROWS,
            )

        val rowBudget =
            BoundedHtmlBuilder(
                MAX_OFFICE_FRAGMENT_HTML_CHARS
            )

        var contentTruncated =
            rows.size >
                    MAX_WORD_TABLE_ROWS

        for (
        rowIndex in
        0 until rowCount
        ) {
            val cells =
                rows[rowIndex]
                    .tableCells

            val columnCount =
                minOf(
                    cells.size,
                    MAX_WORD_TABLE_COLUMNS,
                )

            if (
                cells.size >
                MAX_WORD_TABLE_COLUMNS
            ) {
                contentTruncated =
                    true
            }

            val rowHtml =
                buildString {
                    append("<tr>")

                    for (
                    columnIndex in
                    0 until columnCount
                    ) {
                        val cellText =
                            limitPlainText(
                                raw =
                                    cells[columnIndex]
                                        .text
                                        .orEmpty(),
                                maxChars =
                                    MAX_WORD_TABLE_CELL_CHARS,
                            )

                        append("<td>")
                        append(
                            escapeHtml(
                                cellText
                            )
                        )
                        append("</td>")
                    }

                    append("</tr>")
                }

            if (
                !rowBudget.append(
                    rowHtml
                )
            ) {
                contentTruncated =
                    true
                break
            }
        }

        return buildString {
            append(
                "<div class=\"table-scroll\"><table><tbody>"
            )
            append(
                rowBudget.content()
            )
            append(
                "</tbody></table></div>"
            )

            if (
                contentTruncated ||
                rowBudget.truncated
            ) {
                append(
                    renderTruncatedNote(
                        previewTruncated
                    )
                )
            }
        }
    }

    private fun renderSheet(
        sheet: Sheet,
        index: Int,
        formatter: DataFormatter,
        previewTruncated: String,
    ): String {
        val lastRow =
            minOf(
                sheet.lastRowNum,
                MAX_SHEET_ROWS - 1,
            )

        var maxColumnCount =
            0

        if (
            lastRow >= 0
        ) {
            for (
            rowIndex in
            0..lastRow
            ) {
                val columnCount =
                    sheet.getRow(
                        rowIndex
                    )
                        ?.lastCellNum
                        ?.toInt()
                        ?.coerceAtLeast(
                            0
                        )
                        ?: 0

                maxColumnCount =
                    maxOf(
                        maxColumnCount,
                        columnCount,
                    )
            }
        }

        val columnCount =
            minOf(
                maxColumnCount,
                MAX_SHEET_COLUMNS,
            )

        val rowBudget =
            BoundedHtmlBuilder(
                MAX_OFFICE_FRAGMENT_HTML_CHARS
            )

        var fragmentTruncated =
            sheet.lastRowNum + 1 >
                    MAX_SHEET_ROWS ||
                    maxColumnCount >
                    MAX_SHEET_COLUMNS

        if (
            lastRow >= 0 &&
            columnCount > 0
        ) {
            for (
            rowIndex in
            0..lastRow
            ) {
                val row =
                    sheet.getRow(
                        rowIndex
                    )

                val rowHtml =
                    buildString {
                        append("<tr>")

                        for (
                        columnIndex in
                        0 until columnCount
                        ) {
                            val cellText =
                                formatter
                                    .formatCellValue(
                                        row?.getCell(
                                            columnIndex
                                        )
                                    )
                                    .orEmpty()

                            append("<td>")
                            append(
                                escapeHtml(
                                    limitPlainText(
                                        raw =
                                            cellText,
                                        maxChars =
                                            MAX_SHEET_CELL_CHARS,
                                    )
                                )
                            )
                            append("</td>")
                        }

                        append("</tr>")
                    }

                if (
                    !rowBudget.append(
                        rowHtml
                    )
                ) {
                    fragmentTruncated =
                        true
                    break
                }
            }
        }

        return buildString {
            append(
                "<section class=\"sheet-block\">"
            )
            append("<h2>")
            append(
                escapeHtml(
                    sheet.sheetName
                        .ifBlank {
                            "Sheet ${index + 1}"
                        }
                )
            )
            append("</h2>")
            append(
                "<div class=\"table-scroll\"><table><tbody>"
            )
            append(
                rowBudget.content()
            )
            append(
                "</tbody></table></div>"
            )

            if (
                fragmentTruncated ||
                rowBudget.truncated
            ) {
                append(
                    renderTruncatedNote(
                        previewTruncated
                    )
                )
            }

            append("</section>")
        }
    }

    private fun renderSlide(
        slide: XSLFSlide,
        index: Int,
        previewTruncated: String,
    ): String {
        val textShapes =
            slide.shapes
                .filterIsInstance<
                        XSLFTextShape
                        >()

        val shapeCount =
            minOf(
                textShapes.size,
                MAX_PPT_TEXT_SHAPES_PER_SLIDE,
            )

        val itemBudget =
            BoundedHtmlBuilder(
                MAX_OFFICE_FRAGMENT_HTML_CHARS
            )

        var fragmentTruncated =
            textShapes.size >
                    MAX_PPT_TEXT_SHAPES_PER_SLIDE

        for (
        shapeIndex in
        0 until shapeCount
        ) {
            val rawText =
                textShapes[
                    shapeIndex
                ]
                    .text
                    ?.trim()
                    .orEmpty()

            if (
                rawText.isBlank()
            ) {
                continue
            }

            val item =
                buildString {
                    append("<li>")
                    append(
                        escapeHtml(
                            limitPlainText(
                                raw =
                                    rawText,
                                maxChars =
                                    MAX_PPT_TEXT_CHARS,
                            )
                        )
                    )
                    append("</li>")
                }

            if (
                !itemBudget.append(
                    item
                )
            ) {
                fragmentTruncated =
                    true
                break
            }
        }

        return buildString {
            append(
                "<section class=\"slide-block\">"
            )

            append("<h2>Slide ")
            append(
                index + 1
            )
            append("</h2>")

            if (
                itemBudget.content()
                    .isEmpty()
            ) {
                append(
                    "<p class=\"muted\">No text content on this slide.</p>"
                )
            } else {
                append(
                    "<ul class=\"slide-list\">"
                )
                append(
                    itemBudget.content()
                )
                append("</ul>")
            }

            if (
                fragmentTruncated ||
                itemBudget.truncated
            ) {
                append(
                    renderTruncatedNote(
                        previewTruncated
                    )
                )
            }

            append("</section>")
        }
    }

    private fun renderPreviewNote(
        message: String,
    ): String =
        "<div class=\"preview-note\">${
            escapeHtml(
                message
            )
        }</div>"

    private fun renderTruncatedNote(
        message: String,
    ): String =
        "<p class=\"truncated-note\">${
            escapeHtml(
                message
            )
        }</p>"

    private fun finishBudget(
        budget: BoundedHtmlBuilder,
        previewTruncated: String,
    ): String =
        buildString {
            append(
                budget.content()
            )

            if (
                budget.truncated
            ) {
                append(
                    renderTruncatedNote(
                        previewTruncated
                    )
                )
            }
        }

    private fun limitPlainText(
        raw: String,
        maxChars: Int,
    ): String {
        if (
            raw.length <=
            maxChars
        ) {
            return raw
        }

        return buildString(
            maxChars + 1
        ) {
            append(
                raw,
                0,
                maxChars,
            )
            append('…')
        }
    }

    private fun wrapHtmlDocument(
        title: String,
        body: String,
        isDarkTheme: Boolean,
        themePalette: ThemePalette,
        style: HtmlPreviewStyle,
    ): String {
        val colors =
            when (style) {
                HtmlPreviewStyle.MARKDOWN ->
                    previewColors(
                        isDarkTheme,
                        themePalette,
                    )

                HtmlPreviewStyle.DOCUMENT ->
                    previewColors(
                        isDarkTheme = false,
                        palette =
                            themePalette,
                    ).copy(
                        background =
                            "#FFFFFF",
                        surface =
                            "#FFFFFF",
                    )
            }

        val pageCss =
            when (style) {
                HtmlPreviewStyle.MARKDOWN ->
                    """
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

                HtmlPreviewStyle.DOCUMENT ->
                    """
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

    suspend fun deleteCacheFiles(
        paths: Collection<String>,
    ) {
        if (
            paths.isEmpty()
        ) {
            return
        }

        withContext(
            ioDispatcher
        ) {
            PreviewCacheCleaner
                .deleteFiles(
                    context,
                    paths,
                )
        }
    }

    suspend fun clearAllPreviewCache() {
        withContext(
            ioDispatcher
        ) {
            PreviewCacheCleaner
                .clearAll(
                    context
                )
        }
    }

    private suspend fun ensureCachedDownload(
        url: String,
        destination: File,
        forceDownload: Boolean = false,
        maxDownloadBytes: Long? = null,
    ) {
        val cachedLength =
            withContext(
                ioDispatcher
            ) {
                if (
                    destination.exists()
                ) {
                    destination.length()
                } else {
                    0L
                }
            }

        if (
            !forceDownload &&
            cachedLength > 0L
        ) {
            /*
             * An oversized file may have been cached by an older app version
             * that had no preview-download limit. Do not parse or retain it.
             */
            if (
                maxDownloadBytes != null &&
                cachedLength >
                maxDownloadBytes
            ) {
                withContext(
                    ioDispatcher
                ) {
                    destination.delete()
                }

                throw DownloadSizeLimitExceededException(
                    maxDownloadBytes
                )
            }

            return
        }

        backendService
            .downloadToFile(
                url =
                    url,
                destination =
                    destination,
                maxBytes =
                    maxDownloadBytes,
            )
    }

    private suspend fun <T> loadCachedBinaryPreview(
        url: String,
        extension: String,
        maxDownloadBytes: Long? = null,
        parser: suspend (File) -> T,
    ): T {
        val cacheState =
            withContext(
                ioDispatcher
            ) {
                val file =
                    cacheFile(
                        url,
                        extension,
                    )

                CachedFileState(
                    file =
                        file,
                    existed =
                        file.exists() &&
                                file.length() >
                                0L,
                )
            }

        val file =
            cacheState.file

        ensureCachedDownload(
            url =
                url,
            destination =
                file,
            maxDownloadBytes =
                maxDownloadBytes,
        )

        return try {
            parser(
                file
            )
        } catch (
            cancellation:
            CancellationException
        ) {
            throw cancellation
        } catch (
            throwable: Exception
        ) {
            /*
             * Only a previously cached file gets one recovery attempt.
             *
             * A newly downloaded file that already failed parsing should not be
             * downloaded a second time.
             */
            if (
                !cacheState.existed
            ) {
                throw throwable
            }

            deleteCacheFiles(
                listOf(
                    file.absolutePath
                )
            )

            ensureCachedDownload(
                url =
                    url,
                destination =
                    file,
                forceDownload =
                    true,
                maxDownloadBytes =
                    maxDownloadBytes,
            )

            parser(
                file
            )
        }
    }

    private fun cacheFile(
        url: String,
        extension: String,
    ): File =
        File(
            previewCacheDir(),
            "${url.md5()}.$extension",
        )

    private fun previewCacheDir(): File =
        File(
            context.cacheDir,
            "preview-cache",
        ).apply {
            mkdirs()
        }

    private fun normalizePreviewType(
        raw: String,
    ): PreviewType =
        when (
            raw.lowercase()
        ) {
            "pdf" ->
                PreviewType.PDF

            "md",
            "markdown",
                ->
                PreviewType.MARKDOWN

            "docx" ->
                PreviewType.WORD

            "xlsx",
            "xls",
                ->
                PreviewType.EXCEL

            "pptx" ->
                PreviewType.PPT

            else ->
                PreviewType.UNSUPPORTED
        }

    private fun previewColors(
        isDarkTheme: Boolean,
        palette: ThemePalette,
    ): PreviewColors {
        val accent =
            when (palette) {
                ThemePalette.BLUE ->
                    "#4F9CFF"

                ThemePalette.SAGE ->
                    "#6E8A63"

                ThemePalette.ALMOND ->
                    "#B28B49"
            }

        return if (
            isDarkTheme
        ) {
            PreviewColors(
                background =
                    "#0B1118",
                backgroundSoft =
                    "#111A24",
                surface =
                    "#101923",
                surfaceStrong =
                    "#16212C",
                text =
                    "#F4F8FC",
                heading =
                    "#FFFFFF",
                textMuted =
                    "#C1CDD8",
                accent =
                    accent,
                border =
                    "#233241",
                shadow =
                    "rgba(5, 10, 18, 0.35)",
            )
        } else {
            PreviewColors(
                background =
                    "#EEF2F5",
                backgroundSoft =
                    "#F6F8FA",
                surface =
                    "#FFFFFF",
                surfaceStrong =
                    "#F6F9FB",
                text =
                    "#17212B",
                heading =
                    "#111A24",
                textMuted =
                    "#5F6E80",
                accent =
                    accent,
                border =
                    "#D6E1EC",
                shadow =
                    "rgba(23, 33, 43, 0.08)",
            )
        }
    }

    /*
     * Avoid six complete String.replace() passes over potentially large
     * preview text. Most ordinary text also takes the no-allocation fast path.
     */
    private fun escapeHtml(
        raw: String,
    ): String {
        var needsEscaping =
            false

        for (
        char in raw
        ) {
            if (
                char == '&' ||
                char == '<' ||
                char == '>' ||
                char == '"' ||
                char == '\'' ||
                char == '\n'
            ) {
                needsEscaping =
                    true
                break
            }
        }

        if (
            !needsEscaping
        ) {
            return raw
        }

        return buildString(
            raw.length
        ) {
            raw.forEach { char ->
                when (char) {
                    '&' ->
                        append(
                            "&amp;"
                        )

                    '<' ->
                        append(
                            "&lt;"
                        )

                    '>' ->
                        append(
                            "&gt;"
                        )

                    '"' ->
                        append(
                            "&quot;"
                        )

                    '\'' ->
                        append(
                            "&#39;"
                        )

                    '\n' ->
                        append(
                            "<br/>"
                        )

                    else ->
                        append(
                            char
                        )
                }
            }
        }
    }

    private fun String.md5(): String {
        val digest =
            MessageDigest
                .getInstance(
                    "MD5"
                )
                .digest(
                    toByteArray()
                )

        return digest.joinToString(
            ""
        ) { byte ->
            "%02x".format(
                byte
            )
        }
    }

    private class BoundedHtmlBuilder(
        private val maxChars: Int,
    ) {
        private val builder =
            StringBuilder(
                minOf(
                    maxChars,
                    64 * 1024,
                )
            )

        var truncated:
                Boolean =
            false
            private set

        fun append(
            html: String,
        ): Boolean {
            if (
                truncated
            ) {
                return false
            }

            if (
                html.length >
                maxChars -
                builder.length
            ) {
                truncated =
                    true
                return false
            }

            builder.append(
                html
            )

            return true
        }

        fun markTruncated() {
            truncated =
                true
        }

        fun content(): String =
            builder.toString()
    }

    private data class CachedFileState(
        val file: File,
        val existed: Boolean,
    )

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
        /*
         * Markdown is a lightweight preview, not an editor. Bounding the raw
         * text also bounds the AST and generated WebView DOM.
         */
        const val MAX_MARKDOWN_CHARS =
            1_000_000

        /*
         * commonmark 0.30 already defaults to one million table cells.
         * A mobile lightweight preview does not need anywhere near that many.
         */
        const val MAX_MARKDOWN_TABLE_CELLS =
            50_000

        /*
         * POI builds an in-memory object model even when its backing ZIP is
         * file-based. Refuse very large compressed Office files instead of
         * risking a large transient heap spike merely for a quick preview.
         */
        const val MAX_OFFICE_PREVIEW_FILE_BYTES =
            32L * 1024L * 1024L

        /*
         * Bounds the HTML String retained in NotesUiState and the DOM handed
         * to WebView.
         */
        const val MAX_OFFICE_HTML_CHARS =
            2_000_000

        /*
         * Bounds one large table/sheet/slide fragment before it reaches the
         * global HTML budget.
         */
        const val MAX_OFFICE_FRAGMENT_HTML_CHARS =
            750_000

        const val MAX_WORD_BODY_ELEMENTS =
            2_000

        const val MAX_WORD_PARAGRAPH_CHARS =
            8_192

        const val MAX_WORD_TABLE_ROWS =
            100

        const val MAX_WORD_TABLE_COLUMNS =
            20

        const val MAX_WORD_TABLE_CELL_CHARS =
            512

        const val MAX_SHEET_COUNT =
            20

        const val MAX_SHEET_ROWS =
            200

        const val MAX_SHEET_COLUMNS =
            40

        const val MAX_SHEET_CELL_CHARS =
            512

        const val MAX_PPT_SLIDES =
            300

        const val MAX_PPT_TEXT_SHAPES_PER_SLIDE =
            50

        const val MAX_PPT_TEXT_CHARS =
            2_048
    }
}

data class LoadedPreview(
    val content: PreviewContent,
    val cacheFiles: List<String> =
        emptyList(),
)