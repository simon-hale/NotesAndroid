@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.notes.notes.ui.screens

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.AppTab
import com.notes.notes.core.DirectoryEntry
import com.notes.notes.core.DownloadedFileEntry
import com.notes.notes.core.FileEntry
import com.notes.notes.core.FileDiskStrings
import com.notes.notes.core.NotesUiState
import com.notes.notes.core.SortDirection
import com.notes.notes.core.SortKey
import com.notes.notes.core.ThemeMode
import com.notes.notes.core.UploadCandidate
import com.notes.notes.core.label
import com.notes.notes.core.stringsFor
import com.notes.notes.ui.NotesAppViewModel
import com.notes.notes.ui.components.ActionChip
import com.notes.notes.ui.components.BreadcrumbBar
import com.notes.notes.ui.components.GlassPanel
import com.notes.notes.ui.components.InfoPill
import com.notes.notes.ui.components.LoadingCard
import com.notes.notes.ui.components.LoginReminderCard
import com.notes.notes.ui.components.NotesAlertDialog
import com.notes.notes.ui.components.NotesModalBottomSheet
import com.notes.notes.ui.components.PrimaryActionButton
import com.notes.notes.ui.components.RowActionButton
import com.notes.notes.ui.components.ScreenHeader
import com.notes.notes.ui.components.SecondaryActionButton
import com.notes.notes.ui.components.SectionDivider
import com.notes.notes.ui.components.SectionListCard
import com.notes.notes.ui.components.SectionRow
import com.notes.notes.ui.components.SelectionCheck
import com.notes.notes.ui.components.StatusCard
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Composable
fun DiskScreen(
    uiState: NotesUiState,
    viewModel: NotesAppViewModel,
    contentBottomPadding: Dp,
) {
    val strings = stringsFor(uiState.settings.language)
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    var sortSheetVisible by rememberSaveable { mutableStateOf(false) }
    var uploadSheetVisible by rememberSaveable { mutableStateOf(false) }
    var downloadedFilesSheetVisible by rememberSaveable { mutableStateOf(false) }
    var createFolderDraft by rememberSaveable { mutableStateOf("") }
    var renameDirectoryTarget by remember { mutableStateOf<DirectoryEntry?>(null) }
    var renameFileTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteDirectoryTarget by remember { mutableStateOf<DirectoryEntry?>(null) }
    var deleteFileTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteDownloadedFileTarget by remember { mutableStateOf<DownloadedFileEntry?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val candidates = uris.mapNotNull { uri -> resolveUploadCandidate(context, uri) }
        viewModel.chooseUploadCandidates(candidates)
    }

    val viewportPaddingKey = remember(
        uiState.session.isLoggedIn,
        uiState.disk.isLoading,
        uiState.disk.errorMessage,
        uiState.disk.statusMessage,
        uiState.disk.paths.size,
        uiState.disk.directories.size,
        uiState.disk.files.size,
    ) {
        DiskViewportPaddingKey(
            isLoggedIn = uiState.session.isLoggedIn,
            isLoading = uiState.disk.isLoading,
            errorMessage = uiState.disk.errorMessage,
            statusMessage = uiState.disk.statusMessage,
            pathCount = uiState.disk.paths.size,
            directoryCount = uiState.disk.directories.size,
            fileCount = uiState.disk.files.size,
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        val viewportExtension = rememberLazyViewportExtensionPadding(
            listState = listState,
            viewportHeight = maxHeight,
            contentKey = viewportPaddingKey,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = contentBottomPadding + viewportExtension,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = strings.nav.disk,
                    trailing = {
                        ActionChip(
                            icon = Icons.Outlined.Inventory2,
                            onClick = {
                                downloadedFilesSheetVisible = true
                                viewModel.loadDownloadedFiles()
                            },
                        )
                        ActionChip(
                            icon = diskThemeModeIcon(uiState.settings.theme.mode),
                            onClick = viewModel::toggleThemeMode,
                        )
                    },
                )
            }

            item {
                GlassPanel {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BreadcrumbBar(
                            paths = uiState.disk.paths,
                            rootLabel = strings.fileDisk.root,
                            onOpenRoot = viewModel::openRootDirectory,
                            onPathClick = viewModel::jumpToPath,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionChip(icon = Icons.Outlined.UploadFile, onClick = { uploadSheetVisible = true })
                                ActionChip(icon = Icons.Outlined.SwapVert, onClick = { sortSheetVisible = true })
                                ActionChip(icon = Icons.Outlined.Refresh, onClick = viewModel::refreshCurrentDirectory)
                            }
                        }
                    }
                }
            }

            if (!uiState.session.isLoggedIn) {
                item {
                    LoginReminderCard(
                        message = strings.auth.loginFirst,
                        actionLabel = strings.common.login,
                        onAction = { viewModel.setCurrentTab(AppTab.ACCOUNT) },
                    )
                }
            } else {
                when {
                    uiState.disk.errorMessage.isNotBlank() -> {
                        item {
                            StatusCard(
                                title = strings.fileDisk.directoryLoadFailed,
                                body = uiState.disk.errorMessage,
                                actionLabel = strings.common.retry,
                                onAction = viewModel::refreshCurrentDirectory,
                            )
                        }
                    }

                    uiState.disk.isLoading -> {
                        item {
                            LoadingCard(strings.fileDisk.directoryLoading)
                        }
                    }

                    uiState.disk.statusMessage.isNotBlank() &&
                        uiState.disk.directories.isEmpty() &&
                        uiState.disk.files.isEmpty() -> {
                        item {
                            StatusCard(
                                title = strings.fileDisk.directoryEmpty,
                                body = strings.reading.refreshHint,
                            )
                        }
                    }
                }

                if (!uiState.disk.isLoading && (uiState.disk.directories.isNotEmpty() || uiState.disk.files.isNotEmpty())) {
                    item {
                        SectionListCard {
                            uiState.disk.directories.forEachIndexed { index, directory ->
                                DirectoryCard(
                                    directory = directory,
                                    onOpen = { viewModel.openDirectory(directory) },
                                    onRename = {
                                        renameDirectoryTarget = directory
                                        renameDraft = directory.name
                                    },
                                    onDelete = { deleteDirectoryTarget = directory },
                                )
                                if (index < uiState.disk.directories.lastIndex || uiState.disk.files.isNotEmpty()) {
                                    SectionDivider()
                                }
                            }
                            uiState.disk.files.forEachIndexed { index, file ->
                                FileCard(
                                    file = file,
                                    selected = uiState.reading.selectedFile?.id == file.id,
                                    onSelect = { viewModel.selectReadingFile(file) },
                                    onRename = {
                                        renameFileTarget = file
                                        renameDraft = file.name
                                    },
                                    onDelete = { deleteFileTarget = file },
                                    onDownload = { viewModel.downloadFile(file) },
                                )
                                if (index < uiState.disk.files.lastIndex) {
                                    SectionDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sortSheetVisible) {
        SortBottomSheet(
            uiState = uiState,
            onDismiss = { sortSheetVisible = false },
            onApply = { key, direction ->
                viewModel.applySort(key, direction)
                sortSheetVisible = false
            },
        )
    }

    if (downloadedFilesSheetVisible) {
        DownloadedFilesBottomSheet(
            uiState = uiState,
            onDismiss = { downloadedFilesSheetVisible = false },
            onRetry = viewModel::loadDownloadedFiles,
            onOpenFile = { file -> openDownloadedFile(context, file, strings.common.unknownError) },
            onDeleteFile = { file -> deleteDownloadedFileTarget = file },
        )
    }

    if (uploadSheetVisible) {
        UploadBottomSheet(
            folderName = createFolderDraft,
            onFolderNameChange = { createFolderDraft = it },
            uploadCandidates = uiState.disk.uploadCandidates,
            isUploading = uiState.disk.isUploading,
            uploadProgress = uiState.disk.uploadProgress,
            onDismiss = { uploadSheetVisible = false },
            onCreateFolder = {
                viewModel.createDirectory(createFolderDraft)
                createFolderDraft = ""
            },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onRemoveCandidate = viewModel::removeUploadCandidate,
            onClearCandidates = viewModel::clearUploadCandidates,
            onUpload = viewModel::uploadSelectedFiles,
            language = uiState.settings.language,
            strings = strings,
        )
    }

    renameDirectoryTarget?.let { directory ->
        RenameDialog(
            title = strings.fileDisk.changeDirectoryTitle,
            label = strings.fileDisk.renameNewName,
            hint = strings.fileDisk.renameDirectoryHint,
            value = renameDraft,
            onValueChange = { renameDraft = it },
            onDismiss = { renameDirectoryTarget = null },
            onConfirm = {
                viewModel.renameDirectory(directory, renameDraft)
                renameDirectoryTarget = null
            },
            strings = strings,
        )
    }

    renameFileTarget?.let { file ->
        RenameDialog(
            title = strings.fileDisk.changeFileTitle,
            label = strings.fileDisk.renameNewName,
            hint = strings.fileDisk.renameFileHint,
            value = renameDraft,
            onValueChange = { renameDraft = it },
            onDismiss = { renameFileTarget = null },
            onConfirm = {
                viewModel.renameFile(file, renameDraft)
                renameFileTarget = null
            },
            strings = strings,
        )
    }

    deleteDirectoryTarget?.let { directory ->
        DeleteDialog(
            title = strings.fileDisk.deleteDirectory,
            description = strings.fileDisk.deleteDirectoryPrompt,
            targetName = directory.name,
            onDismiss = { deleteDirectoryTarget = null },
            onConfirm = {
                viewModel.deleteDirectory(directory)
                deleteDirectoryTarget = null
            },
            strings = strings,
        )
    }

    deleteFileTarget?.let { file ->
        DeleteDialog(
            title = strings.fileDisk.deleteFile,
            description = strings.fileDisk.deleteFilePrompt,
            targetName = file.name,
            onDismiss = { deleteFileTarget = null },
            onConfirm = {
                viewModel.deleteFile(file)
                deleteFileTarget = null
            },
            strings = strings,
        )
    }

    deleteDownloadedFileTarget?.let { file ->
        DeleteDialog(
            title = strings.fileDisk.deleteFile,
            description = strings.fileDisk.deleteDownloadedFilePrompt,
            targetName = file.name,
            onDismiss = { deleteDownloadedFileTarget = null },
            onConfirm = {
                viewModel.deleteDownloadedFile(file)
                deleteDownloadedFileTarget = null
            },
            strings = strings,
        )
    }
}

@Composable
private fun DirectoryCard(
    directory: DirectoryEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionRow(
        title = directory.name,
        icon = Icons.Outlined.Folder,
        emphasizeIconBackground = true,
        scrollableTitle = true,
        onClick = onOpen,
        trailing = {
            RowActionButton(icon = Icons.Outlined.Edit, onClick = onRename)
            RowActionButton(
                icon = Icons.Outlined.Delete,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            )
        },
    )
}

@Composable
private fun FileCard(
    file: FileEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    val (icon, iconTint) = fileVisual(file)
    SectionRow(
        title = file.name,
        subtitle = fileMetadata(file),
        icon = if (selected) Icons.Rounded.Check else icon,
        iconTint = iconTint,
        selected = selected,
        scrollableTitle = true,
        onClick = onSelect,
        trailing = {
            RowActionButton(icon = Icons.Outlined.Download, onClick = onDownload)
            RowActionButton(icon = Icons.Outlined.Edit, onClick = onRename)
            RowActionButton(
                icon = Icons.Outlined.Delete,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
                containerColor = LocalNotesExtraColors.current.danger.copy(alpha = 0.12f),
            )
        },
    )
}

@Composable
private fun SortBottomSheet(
    uiState: NotesUiState,
    onDismiss: () -> Unit,
    onApply: (SortKey, SortDirection) -> Unit,
) {
    val strings = stringsFor(uiState.settings.language)
    NotesModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.fileDisk.sortOptionsTitle, style = MaterialTheme.typography.titleLarge)
            SectionListCard {
                SortOption(strings.fileDisk.sortNameAscending, uiState.disk.sortKey == SortKey.NAME && uiState.disk.directorySortDirection == SortDirection.ASC) {
                    onApply(SortKey.NAME, SortDirection.ASC)
                }
                SectionDivider()
                SortOption(strings.fileDisk.sortNameDescending, uiState.disk.sortKey == SortKey.NAME && uiState.disk.directorySortDirection == SortDirection.DESC) {
                    onApply(SortKey.NAME, SortDirection.DESC)
                }
                SectionDivider()
                SortOption(strings.fileDisk.sortCreationNewest, uiState.disk.sortKey == SortKey.CREATED && uiState.disk.sortDirection == SortDirection.DESC) {
                    onApply(SortKey.CREATED, SortDirection.DESC)
                }
                SectionDivider()
                SortOption(strings.fileDisk.sortCreationOldest, uiState.disk.sortKey == SortKey.CREATED && uiState.disk.sortDirection == SortDirection.ASC) {
                    onApply(SortKey.CREATED, SortDirection.ASC)
                }
                SectionDivider()
                SortOption(strings.fileDisk.sortModifiedNewest, uiState.disk.sortKey == SortKey.UPDATED && uiState.disk.sortDirection == SortDirection.DESC) {
                    onApply(SortKey.UPDATED, SortDirection.DESC)
                }
                SectionDivider()
                SortOption(strings.fileDisk.sortModifiedOldest, uiState.disk.sortKey == SortKey.UPDATED && uiState.disk.sortDirection == SortDirection.ASC) {
                    onApply(SortKey.UPDATED, SortDirection.ASC)
                }
            }
        }
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    SectionRow(
        title = label,
        selected = selected,
        onClick = onClick,
        trailing = { SelectionCheck(selected) },
    )
}

@Composable
private fun DownloadedFilesBottomSheet(
    uiState: NotesUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenFile: (DownloadedFileEntry) -> Unit,
    onDeleteFile: (DownloadedFileEntry) -> Unit,
) {
    val strings = stringsFor(uiState.settings.language)
    NotesModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Download/Notes", style = MaterialTheme.typography.titleLarge)
            when {
                uiState.disk.isLoadingDownloadedFiles -> {
                    LoadingCard(strings.fileDisk.directoryLoading)
                }

                uiState.disk.downloadedFilesError.isNotBlank() -> {
                    StatusCard(
                        title = "Download/Notes",
                        body = uiState.disk.downloadedFilesError,
                        actionLabel = strings.common.retry,
                        onAction = onRetry,
                    )
                }

                uiState.disk.downloadedFiles.isEmpty() -> {
                    Text(
                        text = strings.fileDisk.directoryEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalNotesExtraColors.current.textMuted,
                    )
                }

                else -> {
                    SectionListCard {
                        uiState.disk.downloadedFiles.forEachIndexed { index, file ->
                            val (icon, iconTint) = downloadedFileVisual(file)
                            SectionRow(
                                title = file.name,
                                subtitle = downloadedFileMetadata(file, uiState.settings.language),
                                icon = icon,
                                iconTint = iconTint,
                                scrollableTitle = true,
                                onClick = { onOpenFile(file) },
                                trailing = {
                                    RowActionButton(
                                        icon = Icons.Outlined.Delete,
                                        onClick = { onDeleteFile(file) },
                                        tint = MaterialTheme.colorScheme.error,
                                        containerColor = LocalNotesExtraColors.current.danger.copy(alpha = 0.12f),
                                    )
                                },
                            )
                            if (index < uiState.disk.downloadedFiles.lastIndex) {
                                SectionDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class UploadSubPage {
    CREATE_FOLDER,
    UPLOAD_FILES,
}

@Composable
private fun UploadBottomSheet(
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    uploadCandidates: List<UploadCandidate>,
    isUploading: Boolean,
    uploadProgress: Float,
    onDismiss: () -> Unit,
    onCreateFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveCandidate: (UploadCandidate) -> Unit,
    onClearCandidates: () -> Unit,
    onUpload: () -> Unit,
    language: AppLanguage,
    strings: com.notes.notes.core.AppStrings,
) {
    var currentPage by rememberSaveable { mutableStateOf(UploadSubPage.CREATE_FOLDER) }

    NotesModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (currentPage == UploadSubPage.CREATE_FOLDER) {
                        strings.fileDisk.createFolderTitle
                    } else {
                        strings.fileDisk.uploadFilesTitle
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfoPill(
                        label = strings.fileDisk.uploadTypeDirectory,
                        highlighted = currentPage == UploadSubPage.CREATE_FOLDER,
                        onClick = { currentPage = UploadSubPage.CREATE_FOLDER },
                    )
                    InfoPill(
                        label = strings.fileDisk.uploadTypeFile,
                        highlighted = currentPage == UploadSubPage.UPLOAD_FILES,
                        onClick = { currentPage = UploadSubPage.UPLOAD_FILES },
                    )
                }
            }

            if (currentPage == UploadSubPage.CREATE_FOLDER) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = onFolderNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.fileDisk.dirName) },
                        singleLine = true,
                    )
                    PrimaryActionButton(
                        label = strings.common.create,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = folderName.isNotBlank(),
                        onClick = onCreateFolder,
                    )
                }
            } else {
                if (uploadCandidates.isEmpty()) {
                    GlassPanel {
                        Text(
                            text = strings.fileDisk.emptySelectionHint,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalNotesExtraColors.current.textMuted,
                        )
                    }
                } else {
                    SectionListCard {
                        uploadCandidates.forEachIndexed { index, candidate ->
                            SectionRow(
                                title = candidate.displayName,
                                subtitle = if (candidate.sizeBytes > 0) {
                                    formatFileSize(candidate.sizeBytes, language)
                                } else {
                                    null
                                },
                                icon = Icons.AutoMirrored.Outlined.Article,
                                trailing = {
                                    RowActionButton(
                                        icon = Icons.Outlined.Delete,
                                        onClick = { onRemoveCandidate(candidate) },
                                        tint = MaterialTheme.colorScheme.error,
                                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    )
                                },
                            )
                            if (index < uploadCandidates.lastIndex) {
                                SectionDivider()
                            }
                        }
                    }
                }
                if (isUploading) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                val hasUploadCandidates = uploadCandidates.isNotEmpty()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryActionButton(
                        label = strings.fileDisk.pickFiles,
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading,
                        onClick = onPickFiles,
                    )

                    if (hasUploadCandidates) {
                        SecondaryActionButton(
                            label = strings.fileDisk.clearSelectedFiles,
                            modifier = Modifier.weight(1f),
                            enabled = !isUploading,
                            onClick = onClearCandidates,
                        )
                    }

                    PrimaryActionButton(
                        label = strings.common.upload,
                        modifier = Modifier.weight(
                            if (hasUploadCandidates) 2f else 1f
                        ),
                        enabled = !isUploading && hasUploadCandidates,
                        onClick = onUpload,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    title: String,
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    strings: com.notes.notes.core.AppStrings,
) {
    NotesAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onConfirm) { Text(strings.common.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.common.cancel) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    singleLine = true,
                )
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    title: String,
    description: String,
    targetName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    strings: com.notes.notes.core.AppStrings,
) {
    NotesAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onConfirm) { Text(strings.common.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.common.cancel) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description)
                Text(strings.fileDisk.deleteDialogWarning, style = MaterialTheme.typography.bodySmall)
                Text(targetName, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

private fun resolveUploadCandidate(context: android.content.Context, uri: Uri): UploadCandidate? {
    val cursor = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    ) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val displayName = it.run {
            val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) getString(index) else null
        } ?: uri.lastPathSegment ?: return null
        val sizeBytes = it.run {
            val index = getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0) getLong(index) else 0L
        }
        return UploadCandidate(uri.toString(), displayName, sizeBytes)
    }
}

@Composable
private fun rememberLazyViewportExtensionPadding(
    listState: LazyListState,
    viewportHeight: Dp,
    contentKey: Any,
): Dp {
    val density = LocalDensity.current
    val viewportHeightPx = remember(density, viewportHeight) {
        with(density) { viewportHeight.roundToPx() }
    }
    var extensionPx by remember(contentKey, viewportHeightPx) { mutableStateOf(0) }
    val layoutInfo = listState.layoutInfo
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
    val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

    LaunchedEffect(
        contentKey,
        viewportHeightPx,
        isAtTop,
        layoutInfo.totalItemsCount,
        lastVisibleItem?.index,
        lastVisibleItem?.offset,
        lastVisibleItem?.size,
    ) {
        if (!isAtTop || lastVisibleItem == null || layoutInfo.totalItemsCount == 0) {
            return@LaunchedEffect
        }
        if (lastVisibleItem.index == layoutInfo.totalItemsCount - 1) {
            extensionPx = (viewportHeightPx - (lastVisibleItem.offset + lastVisibleItem.size)).coerceAtLeast(0)
        }
    }

    return remember(density, extensionPx) {
        with(density) { extensionPx.toDp() }
    }
}

private data class DiskViewportPaddingKey(
    val isLoggedIn: Boolean,
    val isLoading: Boolean,
    val errorMessage: String,
    val statusMessage: String,
    val pathCount: Int,
    val directoryCount: Int,
    val fileCount: Int,
)

private fun formatFileSize(sizeBytes: Long, language: AppLanguage): String = when {
    sizeBytes >= 1024L * 1024L * 1024L -> String.format(language.asLocale(), "%.2f GB", sizeBytes / (1024f * 1024f * 1024f))
    sizeBytes >= 1024L * 1024L -> String.format(language.asLocale(), "%.2f MB", sizeBytes / (1024f * 1024f))
    sizeBytes >= 1024L -> String.format(language.asLocale(), "%.1f KB", sizeBytes / 1024f)
    else -> "$sizeBytes B"
}

private fun downloadedFileMetadata(file: DownloadedFileEntry, language: AppLanguage): String {
    val pieces = buildList {
        if (file.mimeType.isNotBlank()) add(file.mimeType)
        if (file.sizeBytes > 0) add(formatFileSize(file.sizeBytes, language))
    }
    return pieces.joinToString("\n")
}

private fun diskSortSummary(strings: FileDiskStrings, nameLabel: String, uiState: NotesUiState): String {
    val keyLabel = when (uiState.disk.sortKey) {
        SortKey.NAME -> nameLabel
        SortKey.CREATED -> strings.creationTime
        SortKey.UPDATED -> strings.lastModified
    }
    val direction = if (uiState.disk.sortKey == SortKey.NAME) {
        uiState.disk.directorySortDirection
    } else {
        uiState.disk.sortDirection
    }
    val directionLabel = when (direction) {
        SortDirection.ASC -> strings.sortAscending
        SortDirection.DESC -> strings.sortDescending
    }
    return stringsFor(uiState.settings.language).format(
        strings.sortByColumnWithDirectionTemplate,
        uiState.settings.language.asLocale(),
        keyLabel,
        directionLabel,
    )
}

private fun fileMetadata(file: FileEntry): String {
    val pieces = buildList {
        if (file.type.isNotBlank()) add(file.type.uppercase())
        if (file.creationTime.isNotBlank()) add(file.creationTime)
        if (file.lastModifiedTime.isNotBlank()) add(file.lastModifiedTime)
    }
    return pieces.joinToString("\n")
}

private fun fileVisual(file: FileEntry): Pair<ImageVector, Color> = when (file.type.lowercase()) {
    "pdf" -> Icons.Outlined.PictureAsPdf to Color(0xFFE35D6A)
    "md" -> Icons.AutoMirrored.Outlined.Article to Color(0xFF3390EC)
    else -> Icons.Outlined.Description to Color(0xFF7A8DA1)
}

private fun downloadedFileVisual(file: DownloadedFileEntry): Pair<ImageVector, Color> {
    val extension = file.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "pdf" -> Icons.Outlined.PictureAsPdf to Color(0xFFE35D6A)
        "md", "markdown" -> Icons.AutoMirrored.Outlined.Article to Color(0xFF3390EC)
        else -> Icons.Outlined.Description to Color(0xFF7A8DA1)
    }
}

private fun openDownloadedFile(
    context: android.content.Context,
    file: DownloadedFileEntry,
    fallbackMessage: String,
) {
    val uri = Uri.parse(file.contentUri)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, file.name)
    runCatching {
        context.startActivity(chooser)
    }.onFailure {
        if (it is ActivityNotFoundException) {
            Toast.makeText(context, fallbackMessage, Toast.LENGTH_SHORT).show()
        } else {
            throw it
        }
    }
}

private fun diskThemeModeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}
