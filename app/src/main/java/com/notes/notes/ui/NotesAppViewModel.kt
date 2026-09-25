package com.notes.notes.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.AppSettingsState
import com.notes.notes.core.AppTab
import com.notes.notes.core.DirectoryEntry
import com.notes.notes.core.DiskScreenState
import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadTransfer
import com.notes.notes.core.DownloadTransferEntry
import com.notes.notes.core.DownloadedFileEntry
import com.notes.notes.core.FileEntry
import com.notes.notes.core.MessageTone
import com.notes.notes.core.NotesUiState
import com.notes.notes.core.PathSegment
import com.notes.notes.core.PreviewContent
import com.notes.notes.core.ReadingScreenState
import com.notes.notes.core.SelectedFile
import com.notes.notes.core.SessionState
import com.notes.notes.core.SettingsSubPage
import com.notes.notes.core.SortDirection
import com.notes.notes.core.SortKey
import com.notes.notes.core.ThemeMode
import com.notes.notes.core.ThemePalette
import com.notes.notes.core.ThemeSettings
import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UiMessage
import com.notes.notes.core.UploadCandidate
import com.notes.notes.core.UploadPhase
import com.notes.notes.core.UploadTransfer
import com.notes.notes.core.UploadTransferEntry
import com.notes.notes.core.stringsFor
import com.notes.notes.data.AppPreferencesStore
import com.notes.notes.data.DirectoryListing
import com.notes.notes.data.DownloadWork
import com.notes.notes.data.DownloadWorker
import com.notes.notes.data.FileTransferRepository
import com.notes.notes.data.NotesBackendService
import com.notes.notes.data.NotesServiceException
import com.notes.notes.data.PreviewRepository
import com.notes.notes.data.TransferAccount
import com.notes.notes.data.TransferStsCredentialProvider
import com.notes.notes.data.TransferStore
import com.notes.notes.data.UploadUriPermissionManager
import com.notes.notes.data.UploadWork
import com.notes.notes.data.UploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class NotesAppViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesStore = AppPreferencesStore(application)
    private val backendService = NotesBackendService()
    private val previewRepository = PreviewRepository(application, backendService)
    private val transferRepository = FileTransferRepository(application)
    private val transferStore = TransferStore(application)

    private val _uiState = MutableStateFlow(
        NotesUiState(
            settings = AppSettingsState(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val messageChannel = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var latestDiskRequestToken = 0L

    /** Last lifecycle state seen for every observed upload work, keyed by WorkManager id. */
    private val uploadWorkStates = mutableMapOf<UUID, WorkInfo.State>()
    private val overwriteWarnedUploads = mutableSetOf<UUID>()

    /** Upload batch currently reflected in the UI, used for byte weighted progress across files. */
    private var uploadBatchId: String? = null
    private var uploadBatchTotalBytes = 0L

    /** Work items this ViewModel enqueued itself, plus the ones already reported to the user. */
    private var uploadBatchWorkIds: Set<UUID> = emptySet()
    private val handledUploadWorkIds = mutableSetOf<UUID>()

    /** Persisted transfer records mirrored into memory; the UI and cleanup rules read these. */
    private var uploadTransfers: List<UploadTransfer> = emptyList()
    private var downloadTransfers: List<DownloadTransfer> = emptyList()

    /** Latest WorkManager snapshots, used to tell "running" from "waiting for network" and "gone". */
    private var uploadWorkInfos: List<WorkInfo> = emptyList()
    private var downloadWorkInfos: List<WorkInfo> = emptyList()

    /**
     * Transfers this ViewModel just enqueued. Until their work item shows up, a missing work item must
     * not be mistaken for "stopped", or a freshly started transfer would immediately look paused.
     */
    private val recentlyEnqueuedTransfers = mutableMapOf<String, Long>()

    private val handledDownloadWorkIds = mutableSetOf<UUID>()
    private val downloadWorkStates = mutableMapOf<UUID, WorkInfo.State>()

    init {
        viewModelScope.launch {
            val initial = preferencesStore.preferences.first()
            applyStoredPreferences(initial)
            launch {
                preferencesStore.preferences.collect { applyStoredPreferences(it) }
            }
            cleanupStaleUploadUriPermissions()
            bootstrap(initial.savedUsername, initial.savedAccessToken)
        }
        observeUploadWork()
        observeDownloadWork()
        observeTransferRecords()
    }

    fun setCurrentTab(tab: AppTab) {
        _uiState.update { state ->
            if (state.currentTab == tab) {
                state
            } else {
                state.copy(
                    currentTab = tab,
                    tabBackStack = state.tabBackStack + state.currentTab,
                    reading = resetReadingBottomBarIfNeeded(tab, state.reading),
                )
            }
        }
    }

    fun toggleReadingBottomBar() {
        _uiState.update { state ->
            if (state.currentTab != AppTab.READING) {
                state
            } else {
                state.copy(
                    reading = state.reading.copy(
                        isBottomBarVisible = !state.reading.isBottomBarVisible,
                    )
                )
            }
        }
    }

    fun openSettingsAccountPage() {
        _uiState.update { it.copy(settingsSubPage = SettingsSubPage.ACCOUNT) }
    }

    fun closeSettingsSubPage() {
        _uiState.update { it.copy(settingsSubPage = SettingsSubPage.ROOT) }
    }

    fun navigateBack(): Boolean {
        val state = _uiState.value
        return when {
            state.currentTab == AppTab.ACCOUNT && state.settingsSubPage != SettingsSubPage.ROOT -> {
                closeSettingsSubPage()
                true
            }

            state.currentTab == AppTab.DISK && state.disk.paths.size > 1 -> {
                val previousPath = state.disk.paths[state.disk.paths.lastIndex - 1]
                jumpToPath(previousPath)
                true
            }

            state.tabBackStack.isNotEmpty() -> {
                val targetTab = state.tabBackStack.last()
                val nextStack = state.tabBackStack.dropLast(1)
                _uiState.update {
                    it.copy(
                        currentTab = targetTab,
                        tabBackStack = nextStack,
                        reading = resetReadingBottomBarIfNeeded(targetTab, it.reading),
                    )
                }
                true
            }

            else -> false
        }
    }

    fun toggleThemeMode() {
        val nextMode = when (_uiState.value.settings.theme.mode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(nextMode)
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            preferencesStore.setLanguage(language)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesStore.setThemeMode(mode)
        }
    }

    fun setThemePalette(palette: ThemePalette) {
        viewModelScope.launch {
            preferencesStore.setThemePalette(palette)
        }
    }

    fun openRootDirectory() {
        viewModelScope.launch {
            loadDiskPage(
                target = DiskTarget.Root(currentRootPath()?.let(::listOf)),
                showLoading = true,
                replaceVisibleContent = true,
            )
        }
    }

    fun refreshCurrentDirectory() {
        viewModelScope.launch {
            loadDiskPage(
                target = currentDiskTarget(),
                showLoading = true,
                replaceVisibleContent = false,
            )
        }
    }

    fun openDirectory(directory: DirectoryEntry) {
        viewModelScope.launch {
            val currentPaths = _uiState.value.disk.paths
            val nextLevel = currentPaths.size
            val nextPaths = currentPaths + PathSegment(
                level = nextLevel,
                id = directory.id,
                name = directory.name,
            )
            loadDiskPage(
                target = DiskTarget.Directory(
                    directoryId = directory.id,
                    displayedPaths = nextPaths,
                ),
                showLoading = true,
                replaceVisibleContent = true,
            )
        }
    }

    fun jumpToPath(pathSegment: PathSegment) {
        viewModelScope.launch {
            loadDiskPage(
                target = diskTargetForPath(pathSegment),
                showLoading = true,
                replaceVisibleContent = true,
            )
        }
    }

    fun applySort(sortKey: SortKey, direction: SortDirection) {
        viewModelScope.launch {
            applySortState(sortKey = sortKey, sortDirection = direction)
        }
    }

    fun selectReadingFile(file: FileEntry) {
        _uiState.update {
            it.copy(
                reading = it.reading.copy(
                    selectedFile = SelectedFile(file.id, file.name),
                    isRefreshing = false,
                )
            )
        }
        sendInfoMessage(strings().fileDisk.selected)
    }

    fun chooseUploadCandidates(candidates: List<UploadCandidate>) {
        val existingUris = _uiState.value.disk.uploadCandidates
            .mapTo(mutableSetOf(), UploadCandidate::uriString)
        val newCandidates = candidates.filter { candidate ->
            existingUris.add(candidate.uriString)
        }

        val permissionFailures = newCandidates.mapNotNull { candidate ->
            UploadUriPermissionManager.persistReadPermission(
                context = getApplication(),
                uri = Uri.parse(candidate.uriString),
            )?.let { throwable -> candidate.displayName to throwable }
        }

        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    uploadCandidates = state.disk.uploadCandidates + newCandidates
                )
            )
        }

        if (permissionFailures.isNotEmpty()) {
            sendErrorMessage(
                persistUploadPermissionFailedMessage(
                    fileNames = permissionFailures.map { it.first },
                    throwable = permissionFailures.first().second,
                )
            )
        }
    }

    fun loadDownloadedFiles() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    disk = it.disk.copy(
                        isLoadingDownloadedFiles = true,
                        downloadedFilesError = "",
                    )
                )
            }
            runCatching {
                transferRepository.listDownloadedFiles()
            }.onSuccess { files ->
                _uiState.update {
                    it.copy(
                        disk = it.disk.copy(
                            downloadedFiles = files,
                            isLoadingDownloadedFiles = false,
                            downloadedFilesError = "",
                        )
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        disk = it.disk.copy(
                            downloadedFiles = emptyList(),
                            isLoadingDownloadedFiles = false,
                            downloadedFilesError = toUserMessage(throwable),
                        )
                    )
                }
            }
        }
    }

    fun deleteDownloadedFile(file: DownloadedFileEntry) {
        viewModelScope.launch {
            runCatching {
                transferRepository.deleteDownloadedFile(file)
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        disk = state.disk.copy(
                            downloadedFiles = state.disk.downloadedFiles.filterNot { it.id == file.id }
                        )
                    )
                }
                sendSuccessMessage(strings().fileDisk.deleted)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun removeUploadCandidate(candidate: UploadCandidate) {
        if (_uiState.value.disk.isUploading || _uiState.value.disk.transferActionBusy) return
        val releaseFailure = removeUploadCandidateInternal(candidate)
        if (releaseFailure != null) {
            sendErrorMessage(
                releaseUploadPermissionFailedMessage(
                    fileName = candidate.displayName,
                    throwable = releaseFailure,
                )
            )
        }
    }

    fun clearUploadCandidates() {
        val state = _uiState.value

        if (state.disk.isUploading || state.disk.transferActionBusy) return

        val candidates = state.disk.uploadCandidates
        if (candidates.isEmpty()) return

        _uiState.update { currentState ->
            currentState.copy(
                disk = currentState.disk.copy(
                    uploadCandidates = emptyList(),
                    uploadProgress = 0f,
                )
            )
        }

        // Clearing the selection is not a transfer cancel: documents that a transfer still needs keep
        // their persisted read permission.
        val releaseFailures = releaseUploadCandidatePermissions(candidates)

        if (releaseFailures.isNotEmpty()) {
            sendErrorMessage(
                bulkReleaseUploadPermissionFailedMessage(
                    count = releaseFailures.size,
                    throwable = releaseFailures.first(),
                )
            )
        }
    }

    private fun removeUploadCandidateInternal(candidate: UploadCandidate): Throwable? {
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    uploadCandidates = state.disk.uploadCandidates.filterNot { it.uriString == candidate.uriString }
                )
            )
        }
        // Only now can the permission be released: a paused or queued transfer of the same document
        // still needs it.
        return releaseUploadCandidatePermission(candidate.uriString)
    }

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val strings = strings()
            if (name.isBlank()) {
                sendWarningMessage(strings.fileDisk.emptyText)
                return@launch
            }
            if (name == "root" || name == "root_parent") {
                sendWarningMessage(strings.fileDisk.invalidDirectoryName)
                return@launch
            }
            val session = activeSession() ?: return@launch
            val parent = currentPath() ?: return@launch
            runCatching {
                backendService.createDirectory(
                    accessToken = session.accessToken,
                    parentId = parent.id,
                    name = name,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                refreshCurrentDirectory()
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun renameDirectory(directory: DirectoryEntry, newName: String) {
        viewModelScope.launch {
            val strings = strings()
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                sendWarningMessage(strings.fileDisk.emptyText)
                return@launch
            }
            if (trimmed == "root" || trimmed == "root_parent") {
                sendWarningMessage(strings.fileDisk.invalidDirectoryName)
                return@launch
            }
            val session = activeSession() ?: return@launch
            runCatching {
                backendService.renameDirectory(
                    accessToken = session.accessToken,
                    directoryId = directory.id,
                    name = trimmed,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                refreshCurrentDirectory()
                sendSuccessMessage(strings.fileDisk.renamed)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun renameFile(file: FileEntry, newName: String) {
        viewModelScope.launch {
            val strings = strings()
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                sendWarningMessage(strings.fileDisk.emptyText)
                return@launch
            }
            val session = activeSession() ?: return@launch
            val parent = currentPath() ?: return@launch
            runCatching {
                backendService.renameFile(
                    accessToken = session.accessToken,
                    parentId = parent.id,
                    fileId = file.id,
                    newName = trimmed,
                    language = uiState.value.settings.language,
                )
            }.onSuccess { warningMessage ->
                refreshCurrentDirectory()
                _uiState.update { state ->
                    val reading = state.reading
                    state.copy(
                        reading = reading.copy(
                            selectedFile = reading.selectedFile
                                ?.takeIf { it.id == file.id }
                                ?.copy(name = trimmed)
                                ?: reading.selectedFile,
                            displayedFile = reading.displayedFile
                                ?.takeIf { it.id == file.id }
                                ?.copy(name = trimmed)
                                ?: reading.displayedFile,
                        )
                    )
                }
                if (warningMessage.isNullOrBlank()) {
                    sendSuccessMessage(strings.fileDisk.renamed)
                } else {
                    sendWarningMessage(warningMessage)
                }
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun deleteDirectory(directory: DirectoryEntry) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            runCatching {
                backendService.deleteDirectory(
                    accessToken = session.accessToken,
                    directoryId = directory.id,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                refreshCurrentDirectory()
                sendSuccessMessage(strings().fileDisk.deleted)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun deleteFile(file: FileEntry) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            runCatching {
                backendService.deleteFile(
                    accessToken = session.accessToken,
                    fileId = file.id,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                val readingBeforeDelete = _uiState.value.reading
                val cacheFilesToDelete = if (readingBeforeDelete.displayedFile?.id == file.id) {
                    readingBeforeDelete.activeCacheFiles
                } else {
                    emptyList()
                }
                _uiState.update { state ->
                    val reading = state.reading
                    val deletedSelected = reading.selectedFile?.id == file.id
                    val deletedDisplayed = reading.displayedFile?.id == file.id
                    when {
                        deletedDisplayed -> {
                            state.copy(
                                reading = reading.copy(
                                    selectedFile = when {
                                        deletedSelected -> null
                                        reading.selectedFile != null -> reading.selectedFile
                                        else -> null
                                    },
                                    displayedFile = null,
                                    isRefreshing = false,
                                    content = PreviewContent.Empty,
                                    activeCacheFiles = emptyList(),
                                )
                            )
                        }

                        deletedSelected -> {
                            state.copy(
                                reading = reading.copy(
                                    selectedFile = reading.displayedFile,
                                )
                            )
                        }

                        else -> state
                    }
                }
                cleanupPreviewCacheFiles(cacheFilesToDelete)
                refreshCurrentDirectory()
                sendSuccessMessage(strings().fileDisk.deleted)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun uploadSelectedFiles() {
        viewModelScope.launch {
            val strings = strings()
            val state = _uiState.value
            if (state.disk.transferActionBusy) return@launch
            if (state.disk.uploadTransfers.any { it.phase == UploadPhase.TRANSFERRING }) return@launch
            val session = activeSession() ?: return@launch
            val path = currentPath() ?: return@launch
            val candidates = state.disk.uploadCandidates
            if (candidates.isEmpty()) {
                sendWarningMessage(strings.fileDisk.noFileSelected)
                return@launch
            }
            // Resume anything left over instead of starting a second parallel batch.
            if (state.disk.uploadTransfers.isNotEmpty()) {
                resumeUploads()
                return@launch
            }

            val pathString = state.disk.paths.joinToString(separator = "") { "${it.id}/" }
            val language = state.settings.language
            val batchId = "batch-${System.currentTimeMillis()}"
            val createdAt = System.currentTimeMillis()
            val fileBytes = candidates.map { it.sizeBytes.coerceAtLeast(1L) }
            val batchTotalBytes = fileBytes.sum()

            // The transfer target is frozen here: every later credential refresh and metadata commit
            // reuses these values, never the directory the user happens to be browsing later.
            val transfers = withContext(Dispatchers.IO) {
                candidates.mapIndexed { index, candidate ->
                    val sourceUri = Uri.parse(candidate.uriString)
                    val transferId = UUID.randomUUID().toString()
                    UploadTransfer(
                        transferId = transferId,
                        accountKey = session.username,
                        sourceUri = candidate.uriString,
                        displayName = candidate.displayName,
                        expectedSize = candidate.sizeBytes,
                        lastModified = transferRepository.resolveSourceLastModified(sourceUri),
                        parentId = path.id,
                        pathString = pathString,
                        language = language.code,
                        batchId = batchId,
                        batchTotalBytes = batchTotalBytes,
                        fileBytes = fileBytes[index],
                        checkpointDir = transferRepository.checkpointDirectoryPath(transferId),
                        phase = UploadPhase.TRANSFERRING,
                        bucket = "",
                        region = "",
                        objectKey = "",
                        uploadId = "",
                        notice = TransferNotice.NONE,
                        createdAt = createdAt,
                        transferredBytes = 0L,
                    )
                }
            }

            // Marked before the record is stored: until the work item shows up, a missing work item
            // must not be mistaken for "stopped".
            markRecentlyEnqueued(transfers.map { it.transferId })
            // Persisted before the work is enqueued, so a crash cannot leave untracked uploads.
            transferStore.addUploads(transfers)
            // Mirrored immediately so permission cleanup never believes these documents are unused.
            uploadTransfers = uploadTransfers + transfers
            uploadBatchId = batchId
            uploadBatchTotalBytes = batchTotalBytes
            _uiState.update { it.copy(disk = it.disk.copy(isUploading = true, uploadProgress = 0f)) }
            // WorkManager owns the transfer from here on, so it survives backgrounding and process death.
            val batch = UploadWork.enqueue(
                context = getApplication(),
                accessToken = session.accessToken,
                transfers = transfers,
            )
            uploadBatchWorkIds = batch?.workIds.orEmpty()
        }
    }

    /** Pauses every transfer of the current upload batch. Pausing never aborts the multipart upload. */
    fun pauseUploads() {
        viewModelScope.launch {
            val targets = uploadTransfers.filter { it.phase == UploadPhase.TRANSFERRING }
            if (targets.isEmpty()) return@launch
            setTransferActionBusy(true)
            // The phase is persisted before the work is stopped, so the worker can never report the
            // transfer as failed for a pause the user asked for.
            targets.forEach { transfer ->
                transferStore.updateUpload(transfer.transferId) { current ->
                    if (current.phase == UploadPhase.TRANSFERRING) {
                        current.copy(phase = UploadPhase.PAUSED)
                    } else {
                        current
                    }
                }
            }
            UploadWork.cancelTransfers(getApplication(), targets.map { it.transferId })
                .let { operations -> awaitWorkCancellations(operations) }
            setTransferActionBusy(false)
            sendInfoMessage(strings().transfers.paused)
        }
    }

    /** Resumes paused uploads and retries metadata-only transfers of the current account. */
    fun resumeUploads() {
        viewModelScope.launch {
            val candidates = uploadTransfers.filter { it.phase != UploadPhase.TRANSFERRING }
            if (candidates.isEmpty()) return@launch
            val session = activeSession() ?: return@launch
            setTransferActionBusy(true)

            val owned = candidates.filter { ownsTransfer(session, it.accountKey) }
            val foreign = candidates.filterNot { ownsTransfer(session, it.accountKey) }
            if (foreign.isNotEmpty()) {
                // Another account's transfers must never run under this session.
                transferStore.removeUploads(foreign.map { it.transferId })
                UploadWork.cancelTransfers(getApplication(), foreign.map { it.transferId })
                releaseUnusedUploadPermissions()
            }
            if (owned.isEmpty()) {
                setTransferActionBusy(false)
                return@launch
            }

            owned.filter { it.phase == UploadPhase.PAUSED }.forEach { transfer ->
                transferStore.updateUpload(transfer.transferId) { current ->
                    if (current.phase == UploadPhase.PAUSED) {
                        current.copy(phase = UploadPhase.TRANSFERRING)
                    } else {
                        current
                    }
                }
            }
            // Metadata-only transfers stay METADATA_PENDING: their object is already complete.
            uploadBatchId = owned.first().batchId
            uploadBatchTotalBytes = owned.first().batchTotalBytes
            markRecentlyEnqueued(owned.map { it.transferId })
            val batch = UploadWork.enqueue(                context = getApplication(),
                accessToken = session.accessToken,
                transfers = owned,
            )
            uploadBatchWorkIds = batch?.workIds.orEmpty()
            setTransferActionBusy(false)
        }
    }

    /**
     * Destructive cancel of the current upload batch.
     *
     * Unlike pause this aborts the multipart upload, deletes the SDK checkpoint and drops the transfer
     * record. A transfer whose object is already complete keeps its object: nothing is deleted from OSS.
     */
    fun cancelUploads() {
        cancelUploadTransfers(uploadTransfers)
    }

    /** Destructive cancel of a single transfer, for example from its row in the upload sheet. */
    fun cancelUpload(transferId: String) {
        cancelUploadTransfers(uploadTransfers.filter { it.transferId == transferId })
    }

    private fun cancelUploadTransfers(targets: List<UploadTransfer>) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            setTransferActionBusy(true)
            // State is removed first so the finishing worker cannot commit metadata for a cancelled task.
            val removed = transferStore.removeUploads(targets.map(UploadTransfer::transferId))
            // Mirrored immediately so the permission cleanup below sees the up-to-date need list.
            val removedIds = removed.mapTo(mutableSetOf(), UploadTransfer::transferId)
            uploadTransfers = uploadTransfers.filterNot { it.transferId in removedIds }
            // Waiting for the cancellation to be persisted guarantees the stopped worker no longer
            // holds the checkpoint directory that is deleted below.
            awaitWorkCancellations(
                UploadWork.cancelTransfers(getApplication(), removed.map(UploadTransfer::transferId))
            )
            releaseUnusedUploadPermissions()
            withContext(Dispatchers.IO) {
                // Best effort only: an unreachable backend must never block the UI or a logout.
                withTimeoutOrNull(CANCEL_ABORT_TIMEOUT_MILLIS) {
                    removed.forEach { transfer -> abortAndCleanCheckpoint(transfer) }
                }
            }
            setTransferActionBusy(false)
            sendInfoMessage(strings().transfers.transferCanceled)
        }
    }

    /** Blocks on a background dispatcher until WorkManager persisted the requested cancellations. */
    private suspend fun awaitWorkCancellations(operations: List<androidx.work.Operation>) {
        if (operations.isEmpty()) return
        withContext(Dispatchers.IO) {
            UploadWork.awaitCancellations(operations, WORK_CANCELLATION_TIMEOUT_MILLIS)
        }
    }

    /**
     * Aborts the multipart upload of [transfer] when it is still abortable, then removes its checkpoint.
     * The bucket lifecycle policy cleans up parts that could not be aborted here.
     */
    private suspend fun abortAndCleanCheckpoint(transfer: UploadTransfer) {
        if (transfer.phase != UploadPhase.METADATA_PENDING &&
            transfer.uploadId.isNotBlank() &&
            transfer.objectKey.isNotBlank()
        ) {
            try {
                val accessToken = currentAccessToken()
                if (accessToken != null) {
                    val ticket = backendService.requestOssSts(
                        accessToken = accessToken,
                        pathString = transfer.pathString,
                        filename = transfer.displayName,
                        parentId = transfer.parentId,
                        language = AppLanguage.fromCode(transfer.language),
                        usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
                    )
                    // Let the stopped SDK task release the checkpoint file before it is deleted.
                    delay(CHECKPOINT_RELEASE_DELAY_MILLIS)
                    transferRepository.abortMultipartUpload(
                        ticket = ticket,
                        objectKey = transfer.objectKey,
                        uploadId = transfer.uploadId,
                        credentialProvider = TransferStsCredentialProvider(
                            transfer = transfer,
                            accessToken = accessToken,
                            backendService = backendService,
                            firstTicket = ticket,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Local state is removed either way; the lifecycle policy handles abandoned parts.
                Log.w(TAG, "Best-effort multipart abort failed for ${transfer.displayName}", throwable)
            }
        }
        transferRepository.deleteCheckpointDirectory(transfer.checkpointDir)
    }

    private fun observeUploadWork() {
        viewModelScope.launch {
            UploadWork.workInfos(getApplication()).collect(::applyUploadWorkInfos)
        }
    }

    private fun observeDownloadWork() {
        viewModelScope.launch {
            DownloadWork.workInfos(getApplication()).collect(::applyDownloadWorkInfos)
        }
    }

    private fun observeTransferRecords() {
        viewModelScope.launch {
            transferStore.uploads.collect(::applyUploadRecords)
        }
        viewModelScope.launch {
            transferStore.downloads.collect(::applyDownloadRecords)
        }
    }

    private fun applyUploadRecords(records: List<UploadTransfer>) {
        val previous = uploadTransfers.associateBy(UploadTransfer::transferId)
        uploadTransfers = records
        records.forEach { record ->
            if (record.notice == TransferNotice.NONE) return@forEach
            if (previous[record.transferId]?.notice == record.notice) return@forEach
            val message = when (record.notice) {
                TransferNotice.SOURCE_CHANGED -> strings().transfers.uploadSourceChanged
                TransferNotice.RESTARTED_REMOTE_CHANGED -> strings().transfers.downloadRestartedRemoteChanged
                TransferNotice.RESTARTED_PARTIAL_MISSING -> strings().transfers.partialDownloadMissingRestarted
                TransferNotice.RESTARTED_RANGE_IGNORED -> strings().transfers.rangeIgnoredRestarted
                TransferNotice.NONE -> return@forEach
            }
            sendWarningMessage(message)
            viewModelScope.launch {
                transferStore.updateUpload(record.transferId) { it.copy(notice = TransferNotice.NONE) }
            }
        }
        refreshTransferUi()
        reconcileUploadPhases()
    }

    private fun applyDownloadRecords(records: List<DownloadTransfer>) {
        val previous = downloadTransfers.associateBy(DownloadTransfer::transferId)
        downloadTransfers = records
        records.forEach { record ->
            if (record.notice == TransferNotice.NONE) return@forEach
            if (previous[record.transferId]?.notice == record.notice) return@forEach
            val message = when (record.notice) {
                TransferNotice.RESTARTED_REMOTE_CHANGED -> strings().transfers.downloadRestartedRemoteChanged
                TransferNotice.RESTARTED_PARTIAL_MISSING -> strings().transfers.partialDownloadMissingRestarted
                TransferNotice.RESTARTED_RANGE_IGNORED -> strings().transfers.rangeIgnoredRestarted
                else -> return@forEach
            }
            sendWarningMessage(message)
            viewModelScope.launch {
                transferStore.updateDownload(record.transferId) { it.copy(notice = TransferNotice.NONE) }
            }
        }
        refreshTransferUi()
        reconcileDownloadPhases()
    }

    /** Mirrors the persisted transfers plus live work state into the disk UI. */
    private fun refreshTransferUi() {
        val uploadEntries = uploadTransfers.map { record ->
            val info = uploadWorkInfos.firstOrNull { info ->
                !info.state.isFinished && UploadWork.transferId(info) == record.transferId
            }
            val liveBytes = info?.progress?.let { data ->
                val fraction = data.getFloat(UploadWorker.KEY_PROGRESS_FILE_PROGRESS, 0f)
                val fileBytes = data.getLong(UploadWorker.KEY_PROGRESS_FILE_BYTES, record.fileBytes)
                (fileBytes * fraction).toLong()
            } ?: 0L
            UploadTransferEntry(
                transferId = record.transferId,
                displayName = record.displayName,
                phase = record.phase,
                fileBytes = record.fileBytes,
                transferredBytes = maxOf(liveBytes, record.transferredBytes),
                waitingForNetwork = info != null && info.state == WorkInfo.State.ENQUEUED,
            )
        }
        val downloadEntries = downloadTransfers.map { record ->
            val info = downloadWorkInfos.firstOrNull { info ->
                !info.state.isFinished && DownloadWork.transferId(info) == record.transferId
            }
            DownloadTransferEntry(
                transferId = record.transferId,
                fileName = record.fileName,
                phase = record.phase,
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes,
                waitingForNetwork = info != null && info.state == WorkInfo.State.ENQUEUED,
            )
        }
        // Enqueue grace entries of settled transfers are no longer needed.
        val knownTransferIds = uploadTransfers.mapTo(mutableSetOf(), UploadTransfer::transferId)
        downloadTransfers.forEach { knownTransferIds += it.transferId }
        recentlyEnqueuedTransfers.keys.retainAll(knownTransferIds)
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    uploadTransfers = uploadEntries,
                    downloadTransfers = downloadEntries,
                )
            )
        }
    }

    /**
     * A transfer recorded as transferring without any live work item can only continue if the user
     * resumes it, for example after the system stopped the worker while the app was gone.
     */
    private fun reconcileUploadPhases() {
        val now = System.currentTimeMillis()
        val liveTransferIds = uploadWorkInfos
            .filterNot { it.state.isFinished }
            .mapNotNull(UploadWork::transferId)
            .toSet()
        uploadTransfers
            .filter { it.phase == UploadPhase.TRANSFERRING && it.transferId !in liveTransferIds }
            .filter { isEnqueueSettled(it.transferId, now) }
            .forEach { record ->
                viewModelScope.launch {
                    transferStore.updateUpload(record.transferId) { current ->
                        if (current.phase == UploadPhase.TRANSFERRING) {
                            current.copy(phase = UploadPhase.PAUSED)
                        } else {
                            current
                        }
                    }
                }
            }
    }

    private fun reconcileDownloadPhases() {
        val now = System.currentTimeMillis()
        val liveTransferIds = downloadWorkInfos
            .filterNot { it.state.isFinished }
            .mapNotNull(DownloadWork::transferId)
            .toSet()
        downloadTransfers
            .filter { it.phase == DownloadPhase.TRANSFERRING && it.transferId !in liveTransferIds }
            .filter { isEnqueueSettled(it.transferId, now) }
            .forEach { record ->
                viewModelScope.launch {
                    transferStore.updateDownload(record.transferId) { current ->
                        if (current.phase == DownloadPhase.TRANSFERRING) {
                            current.copy(phase = DownloadPhase.PAUSED)
                        } else {
                            current
                        }
                    }
                }
            }
    }

    private fun markRecentlyEnqueued(transferIds: Collection<String>) {
        val now = System.currentTimeMillis()
        transferIds.forEach { transferId ->
            recentlyEnqueuedTransfers[transferId] = now
        }
    }

    private fun isEnqueueSettled(transferId: String, now: Long): Boolean {
        val enqueuedAt = recentlyEnqueuedTransfers[transferId] ?: return true
        if (now - enqueuedAt < ENQUEUE_GRACE_MILLIS) return false
        recentlyEnqueuedTransfers.remove(transferId)
        return true
    }

    private fun setTransferActionBusy(busy: Boolean) {
        _uiState.update { it.copy(disk = it.disk.copy(transferActionBusy = busy)) }
    }

    private fun ownsTransfer(session: SessionState, accountKey: String): Boolean =
        accountKey.isBlank() || session.username.isBlank() || accountKey == session.username

    private suspend fun currentAccessToken(): String? {
        val stored = runCatching { preferencesStore.preferences.first() }.getOrNull()
        val storedToken = stored?.savedAccessToken.orEmpty()
        if (storedToken.isNotBlank()) return storedToken
        return _uiState.value.session.accessToken.takeIf { it.isNotBlank() }
    }

    /**
     * Mirrors the WorkManager upload queue into the disk UI: overall progress, per-file results and the
     * overwrite warning.
     *
     * Work items of the batch this ViewModel enqueued are reported exactly once, even when the first
     * observed state is already final (a tiny or empty file can finish before it is ever seen running).
     * Work restored from an earlier session is only reported when a live state change is observed, so
     * old success and error messages are never replayed.
     */
    private fun applyUploadWorkInfos(infos: List<WorkInfo>) {
        uploadWorkInfos = infos
        infos.forEach { info ->
            val previousState = uploadWorkStates.put(info.id, info.state)
            val belongsToCurrentBatch = info.id in uploadBatchWorkIds
            val changedWhileObserved = previousState != null && previousState != info.state
            if (info.state.isFinished && (belongsToCurrentBatch || changedWhileObserved)) {
                handleUploadWorkFinishedOnce(info)
            }
        }
        releaseUnusedUploadPermissions()

        val unfinished = infos.filterNot { it.state.isFinished }
        val runningWork = unfinished.firstOrNull { it.state == WorkInfo.State.RUNNING }
        trackUploadBatch(infos, runningWork)
        if (runningWork != null &&
            runningWork.progress.getBoolean(UploadWorker.KEY_PROGRESS_OVERWRITE_SAME_NAME, false) &&
            overwriteWarnedUploads.add(runningWork.id)
        ) {
            sendWarningMessage(strings().fileDisk.overwriteSameName)
        }

        val uploadProgress = batchUploadProgress(infos, runningWork)
        val wasUploading = _uiState.value.disk.isUploading
        val isUploading = unfinished.isNotEmpty()
        // A paused batch keeps its last progress instead of jumping back to zero.
        val keepProgress = isUploading || uploadTransfers.isNotEmpty()
        if (wasUploading != isUploading || uploadProgress != null) {
            _uiState.update { state ->
                state.copy(
                    disk = state.disk.copy(
                        isUploading = isUploading,
                        uploadProgress = uploadProgress
                            ?: if (keepProgress) state.disk.uploadProgress else 0f,
                    )
                )
            }
        }

        if (wasUploading && !isUploading) {
            refreshCurrentDirectory()
        }
        if (!isUploading) {
            uploadWorkStates.clear()
            overwriteWarnedUploads.clear()
            handledUploadWorkIds.clear()
            uploadBatchId = null
            uploadBatchTotalBytes = 0L
            uploadBatchWorkIds = emptySet()
        }
        refreshTransferUi()
        reconcileUploadPhases()
    }

    private fun applyDownloadWorkInfos(infos: List<WorkInfo>) {
        downloadWorkInfos = infos
        infos.forEach { info ->
            val previousState = downloadWorkStates.put(info.id, info.state)
            val changedWhileObserved = previousState != null && previousState != info.state
            if (!info.state.isFinished || !changedWhileObserved) return@forEach
            if (!handledDownloadWorkIds.add(info.id)) return@forEach
            when (info.outputData.getString(DownloadWorker.KEY_RESULT_OUTCOME)) {
                DownloadWorker.OUTCOME_SUCCESS -> {
                    sendSuccessMessage(strings().fileDisk.downloadCompleted)
                    loadDownloadedFiles()
                }

                DownloadWorker.OUTCOME_FAILED -> {
                    sendErrorMessage(downloadWorkFailureMessage(info.outputData))
                }

                else -> Unit
            }
        }
        if (infos.any { !it.state.isFinished }) {
            handledDownloadWorkIds.clear()
        }
        refreshTransferUi()
        reconcileDownloadPhases()
    }

    private fun downloadWorkFailureMessage(data: Data): String {
        val strings = strings()
        return when (data.getString(DownloadWorker.KEY_RESULT_ERROR_KIND)) {
            DownloadWorker.ERROR_KIND_BUSINESS ->
                data.getString(DownloadWorker.KEY_RESULT_ERROR_DETAIL).orEmpty().ifBlank { strings.common.networkError }

            DownloadWorker.ERROR_KIND_HTTP ->
                strings.httpErrorMessage(data.getInt(DownloadWorker.KEY_RESULT_ERROR_STATUS, 0))

            DownloadWorker.ERROR_KIND_MISSING_BASE_URL -> strings.common.baseUrlMissing
            DownloadWorker.ERROR_KIND_FOREGROUND -> strings.common.networkError
            else -> strings.common.networkError
        }
    }

    private fun handleUploadWorkFinishedOnce(info: WorkInfo) {
        if (!handledUploadWorkIds.add(info.id)) return
        handleUploadWorkFinished(info)
    }

    /**
     * Releases persisted read permissions that no upload needs any more, for example the document of a
     * file that finished while the previous process was gone and is therefore never replayed in the UI.
     *
     * Documents of pending work items, of persisted transfers and of the current upload candidates are
     * kept, so a paused or failed file stays resumable. This cleanup is silent and idempotent.
     */
    private fun releaseUnusedUploadPermissions() {
        val neededUris = buildSet {
            _uiState.value.disk.uploadCandidates.forEach { candidate -> add(candidate.uriString) }
            uploadTransfers.forEach { transfer -> add(transfer.sourceUri) }
            uploadWorkInfos.filterNot { it.state.isFinished }
                .forEach { info -> UploadWork.sourceUri(info)?.let(::add) }
        }
        UploadUriPermissionManager.persistedReadPermissionUris(getApplication())
            .filterNot { uriString -> uriString in neededUris }
            .forEach { uriString -> releaseUploadCandidatePermission(uriString) }
    }

    /**
     * Remembers which batch the UI is showing and how many bytes it holds. The running work publishes
     * both values, which also restores them when the queue survived a process restart.
     */
    private fun trackUploadBatch(infos: List<WorkInfo>, runningWork: WorkInfo?) {
        runningWork?.progress?.getString(UploadWorker.KEY_PROGRESS_BATCH_ID)
            ?.takeIf(String::isNotEmpty)
            ?.let { batchId ->
                uploadBatchId = batchId
                uploadBatchTotalBytes = runningWork.progress
                    .getLong(UploadWorker.KEY_PROGRESS_BATCH_TOTAL_BYTES, uploadBatchTotalBytes)
            }
        val batchId = uploadBatchId ?: return
        if (uploadBatchTotalBytes > 0L) return

        infos.firstOrNull { info ->
            info.outputData.getString(UploadWorker.KEY_RESULT_BATCH_ID) == batchId
        }?.let { info ->
            uploadBatchTotalBytes = info.outputData.getLong(UploadWorker.KEY_RESULT_BATCH_TOTAL_BYTES, 0L)
        }
    }

    /**
     * Byte weighted progress across the batch, as in the web client: a file only counts as completed
     * once its OSS upload and the backend bookkeeping both succeeded, and the last percent is kept back
     * so a failing file is never displayed as a finished one.
     */
    private fun batchUploadProgress(infos: List<WorkInfo>, runningWork: WorkInfo?): Float? {
        val batchId = uploadBatchId ?: return null
        if (uploadBatchTotalBytes <= 0L) return null

        val completedBytes = infos
            .filter { info ->
                info.state == WorkInfo.State.SUCCEEDED &&
                    info.outputData.getString(UploadWorker.KEY_RESULT_BATCH_ID) == batchId &&
                    info.outputData.getString(UploadWorker.KEY_RESULT_OUTCOME) == UploadWorker.OUTCOME_SUCCESS
            }
            .sumOf { info -> info.outputData.getLong(UploadWorker.KEY_RESULT_FILE_BYTES, 0L) }

        val currentFileBytes = runningWork?.let { info ->
            val fileBytes = info.progress.getLong(UploadWorker.KEY_PROGRESS_FILE_BYTES, 0L)
            val fileProgress = info.progress.getFloat(UploadWorker.KEY_PROGRESS_FILE_PROGRESS, 0f)
            (fileBytes * fileProgress).toLong()
        } ?: 0L

        val ratio = (completedBytes + currentFileBytes).toFloat() / uploadBatchTotalBytes.toFloat()
        return ratio.coerceIn(0f, MAX_UPLOAD_PROGRESS_BEFORE_COMPLETION)
    }

    private fun handleUploadWorkFinished(info: WorkInfo) {
        val strings = strings()
        val language = _uiState.value.settings.language
        val displayName = info.outputData.getString(UploadWorker.KEY_RESULT_DISPLAY_NAME).orEmpty()
        val uriString = info.outputData.getString(UploadWorker.KEY_RESULT_URI).orEmpty()
        val candidate = _uiState.value.disk.uploadCandidates
            .firstOrNull { uriString.isNotEmpty() && it.uriString == uriString }
        val outcome = info.outputData.getString(UploadWorker.KEY_RESULT_OUTCOME)
        // Paused or cancelled work reports nothing: the transfer record carries that state instead.
        if (outcome == UploadWorker.OUTCOME_CANCELED) return

        val succeeded = info.state == WorkInfo.State.SUCCEEDED &&
            outcome == UploadWorker.OUTCOME_SUCCESS
        if (succeeded) {
            // Removing the candidate also releases the persisted read permission when it is unused.
            val releaseFailure = if (candidate != null) {
                removeUploadCandidateInternal(candidate)
            } else {
                releaseUploadCandidatePermission(uriString)
            }
            releaseUnusedUploadPermissions()
            if (releaseFailure == null) {
                sendSuccessMessage(
                    strings.format(
                        strings.fileDisk.uploadSuccessTemplate,
                        language.asLocale(),
                        displayName,
                    )
                )
            } else {
                sendErrorMessage(
                    uploadSucceededButReleaseFailedMessage(
                        fileName = displayName,
                        throwable = releaseFailure,
                    )
                )
            }
            return
        }

        if (info.state != WorkInfo.State.SUCCEEDED && info.state != WorkInfo.State.FAILED) return

        // A failed file stays in the pending list, so its read permission is still needed for a retry.
        if (candidate == null && uriString.isNotEmpty() && info.state == WorkInfo.State.FAILED) {
            releaseUploadCandidatePermission(uriString)
        }
        if (displayName.isNotEmpty()) {
            sendErrorMessage(
                strings.format(
                    strings.fileDisk.uploadFailedTemplate,
                    language.asLocale(),
                    displayName,
                )
            )
        }
        sendErrorMessage(uploadWorkFailureMessage(info.outputData))
    }

    private fun uploadWorkFailureMessage(data: Data): String {
        val strings = strings()
        return when (data.getString(UploadWorker.KEY_RESULT_ERROR_KIND)) {
            UploadWorker.ERROR_KIND_BUSINESS ->
                data.getString(UploadWorker.KEY_RESULT_ERROR_DETAIL).orEmpty().ifBlank { strings.common.networkError }

            UploadWorker.ERROR_KIND_HTTP ->
                strings.httpErrorMessage(data.getInt(UploadWorker.KEY_RESULT_ERROR_STATUS, 0))

            UploadWorker.ERROR_KIND_MISSING_BASE_URL -> strings.common.baseUrlMissing
            UploadWorker.ERROR_KIND_FOREGROUND -> uploadBackgroundUnavailableMessage()
            else -> strings.common.networkError
        }
    }

    private fun uploadBackgroundUnavailableMessage(): String = when (_uiState.value.settings.language) {
        AppLanguage.ZH_CN -> "无法在后台保持上传，请保持应用在前台后重试"
        AppLanguage.EN_US -> "The upload could not keep running in the background. Keep the app open and retry."
    }

    fun downloadFile(file: FileEntry) {
        startDownload(fileId = file.id, fileName = file.name)
    }

    fun downloadSelectedReadingFile() {
        viewModelScope.launch {
            val targetFile = _uiState.value.reading.displayedFile ?: _uiState.value.reading.selectedFile
            if (targetFile == null) {
                sendWarningMessage(strings().reading.selectFileFirst)
                return@launch
            }
            startDownload(fileId = targetFile.id, fileName = targetFile.name)
        }
    }

    /**
     * Starts one persistent download.
     *
     * The MediaStore entry is created up front with `IS_PENDING = 1`, and the presigned URL is only
     * requested by the worker: it is short lived and must never be persisted as transfer state.
     */
    private fun startDownload(fileId: Long, fileName: String) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            if (_uiState.value.disk.transferActionBusy) return@launch

            val existing = downloadTransfers.firstOrNull {
                it.fileId == fileId && ownsTransfer(session, it.accountKey)
            }
            if (existing != null) {
                resumeDownload(existing.transferId)
                return@launch
            }

            setTransferActionBusy(true)
            val transfer = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadTransfer(
                        transferId = UUID.randomUUID().toString(),
                        accountKey = session.username,
                        fileId = fileId,
                        fileName = fileName,
                        destinationUri = transferRepository.createPendingDownloadDestination(fileName).toString(),
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        etag = "",
                        language = _uiState.value.settings.language.code,
                        phase = DownloadPhase.TRANSFERRING,
                        notice = TransferNotice.NONE,
                        createdAt = System.currentTimeMillis(),
                    )
                }
            }.getOrElse { throwable ->
                setTransferActionBusy(false)
                sendThrowableMessage(throwable)
                return@launch
            }

            markRecentlyEnqueued(listOf(transfer.transferId))
            transferStore.addDownload(transfer)
            downloadTransfers = downloadTransfers + transfer
            DownloadWork.enqueue(getApplication(), session.accessToken, listOf(transfer))
            setTransferActionBusy(false)
            emitMessage(strings().fileDisk.downloadStarted, MessageTone.INFO)
        }
    }

    /** Pauses a download without losing its partial bytes; the pending MediaStore item is kept. */
    fun pauseDownload(transferId: String) {
        viewModelScope.launch {
            val transfer = downloadTransfers.firstOrNull { it.transferId == transferId } ?: return@launch
            if (transfer.phase != DownloadPhase.TRANSFERRING) return@launch
            setTransferActionBusy(true)
            transferStore.updateDownload(transferId) { current ->
                if (current.phase == DownloadPhase.TRANSFERRING) {
                    current.copy(phase = DownloadPhase.PAUSED)
                } else {
                    current
                }
            }
            awaitDownloadCancellation(DownloadWork.cancelTransfer(getApplication(), transferId))
            setTransferActionBusy(false)
            sendInfoMessage(strings().transfers.paused)
        }
    }

    /** Resumes a paused download. The worker always asks for a new presigned URL before continuing. */
    fun resumeDownload(transferId: String) {
        viewModelScope.launch {
            val transfer = downloadTransfers.firstOrNull { it.transferId == transferId } ?: return@launch
            val session = activeSession() ?: return@launch
            if (!ownsTransfer(session, transfer.accountKey)) {
                cancelDownloadInternal(transfer)
                return@launch
            }
            setTransferActionBusy(true)
            transferStore.updateDownload(transferId) { current ->
                if (current.phase == DownloadPhase.PAUSED) {
                    current.copy(phase = DownloadPhase.TRANSFERRING)
                } else {
                    current
                }
            }
            markRecentlyEnqueued(listOf(transferId))
            DownloadWork.enqueue(                context = getApplication(),
                accessToken = session.accessToken,
                transfers = listOf(transfer.copy(phase = DownloadPhase.TRANSFERRING)),
            )
            setTransferActionBusy(false)
        }
    }

    /** Destructive cancel: the unfinished MediaStore item and the transfer state are deleted. */
    fun cancelDownload(transferId: String) {
        viewModelScope.launch {
            val transfer = downloadTransfers.firstOrNull { it.transferId == transferId } ?: return@launch
            setTransferActionBusy(true)
            cancelDownloadInternal(transfer)
            setTransferActionBusy(false)
            sendInfoMessage(strings().transfers.transferCanceled)
        }
    }

    private suspend fun cancelDownloadInternal(transfer: DownloadTransfer) {
        transferStore.removeDownload(transfer.transferId)
        awaitDownloadCancellation(DownloadWork.cancelTransfer(getApplication(), transfer.transferId))
        withContext(Dispatchers.IO) {
            transferRepository.deleteDownloadDestination(transfer.destinationUri)
        }
    }

    /** Blocks on a background dispatcher until WorkManager persisted the requested cancellation. */
    private suspend fun awaitDownloadCancellation(operation: androidx.work.Operation) {
        withContext(Dispatchers.IO) {
            DownloadWork.awaitCancellation(operation, WORK_CANCELLATION_TIMEOUT_MILLIS)
        }
    }

    fun refreshReadingPreview(isDarkTheme: Boolean) {
        viewModelScope.launch {
            val strings = strings()
            val readingBeforeRefresh = _uiState.value.reading
            val selectedFile = readingBeforeRefresh.selectedFile ?: readingBeforeRefresh.displayedFile
            val session = activeSession() ?: return@launch
            if (selectedFile == null) {
                sendWarningMessage(strings.reading.selectFileFirst)
                return@launch
            }
            _uiState.update { it.copy(reading = it.reading.copy(isRefreshing = true)) }
            runCatching {
                val descriptor = backendService.getFilePreview(
                    accessToken = session.accessToken,
                    fileId = selectedFile.id,
                    language = uiState.value.settings.language,
                )
                previewRepository.loadPreview(
                    descriptor = descriptor,
                    fileName = selectedFile.name,
                    isDarkTheme = isDarkTheme,
                    themePalette = _uiState.value.settings.theme.palette,
                    officePreviewHint = strings.reading.officePreviewFallback,
                    markdownLoadFailed = strings.reading.markdownLoadFailed,
                )
            }.onSuccess { loadedPreview ->
                _uiState.update { state ->
                    state.copy(
                        reading = state.reading.copy(
                            selectedFile = selectedFile,
                            displayedFile = selectedFile,
                            isRefreshing = false,
                            content = loadedPreview.content,
                            activeCacheFiles = loadedPreview.cacheFiles,
                        )
                    )
                }
                cleanupPreviewCacheFiles(
                    readingBeforeRefresh.activeCacheFiles.filterNot { it in loadedPreview.cacheFiles }
                )
            }.onFailure { throwable ->
                val message = when (throwable) {
                    is NotesServiceException.Business -> throwable.errorMessage
                    is NotesServiceException.Http -> strings.httpErrorMessage(throwable.statusCode)
                    is NotesServiceException.MissingBaseUrl -> strings.common.baseUrlMissing
                    else -> strings.reading.previewLoadFailed
                }
                _uiState.update {
                    val reading = it.reading
                    it.copy(
                        reading = if (reading.displayedFile != null && reading.content !is PreviewContent.Empty) {
                            reading.copy(isRefreshing = false)
                        } else {
                            reading.copy(
                                isRefreshing = false,
                                content = PreviewContent.Error(selectedFile.name, message),
                            )
                        }
                    )
                }
                if (readingBeforeRefresh.displayedFile == null || readingBeforeRefresh.content is PreviewContent.Empty) {
                    _uiState.update {
                        it.copy(
                            reading = it.reading.copy(
                                displayedFile = null,
                                activeCacheFiles = emptyList(),
                            )
                        )
                    }
                }
                sendErrorMessage(message)
            }
        }
    }

    fun login(username: String, password: String, rememberMe: Boolean) {
        viewModelScope.launch {
            val normalizedUsername = username.trim()
            if (normalizedUsername.isBlank() || password.isEmpty()) {
                sendWarningMessage(requiredFieldsMessage())
                return@launch
            }
            setAccountBusy(true)
            runCatching {
                val token = backendService.login(username = normalizedUsername, password = password)
                if (rememberMe) {
                    preferencesStore.saveCredentials(normalizedUsername, token)
                } else {
                    preferencesStore.clearCredentials()
                }
                onLoginSucceeded(normalizedUsername, token, welcomeBack = true)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable, authRequest = true)
            }
            setAccountBusy(false)
        }
    }

    fun register(username: String, password: String, confirmedPassword: String) {
        viewModelScope.launch {
            val normalizedUsername = username.trim()
            if (normalizedUsername.isBlank() || password.isEmpty() || confirmedPassword.isEmpty()) {
                sendWarningMessage(requiredFieldsMessage())
                return@launch
            }
            if (password != confirmedPassword) {
                sendWarningMessage(strings().changePassword.mismatch)
                return@launch
            }
            setAccountBusy(true)
            runCatching {
                backendService.register(
                    username = normalizedUsername,
                    password = password,
                    confirmedPassword = confirmedPassword,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                val message = registrationCompletedMessage()
                sendSuccessMessage(message)
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
            setAccountBusy(false)
        }
    }

    fun logout() {
        viewModelScope.launch {
            preferencesStore.clearCredentials()
            resetSessionAndContent()
        }
    }
    fun logoutAll() {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch

            setAccountBusy(true)

            runCatching {
                backendService.logoutAll(session.accessToken)
            }.onSuccess {
                preferencesStore.clearCredentials()
                resetSessionAndContent()
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }

            setAccountBusy(false)
        }
    }

    fun changePassword(curPassword: String, newPassword: String, confirmedPassword: String) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            if (curPassword.isEmpty() || newPassword.isEmpty() || confirmedPassword.isEmpty()) {
                sendWarningMessage(requiredFieldsMessage())
                return@launch
            }
            if (newPassword != confirmedPassword) {
                sendWarningMessage(strings().changePassword.mismatch)
                return@launch
            }
            setAccountBusy(true)
            runCatching {
                backendService.changePassword(
                    accessToken = session.accessToken,
                    curPassword = curPassword,
                    password = newPassword,
                    confirmedPassword = confirmedPassword,
                    language = uiState.value.settings.language,
                )
                preferencesStore.clearCredentials()
                resetSessionAndContent()
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
            setAccountBusy(false)
        }
    }

    fun deleteAccount(curPassword: String) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            if (curPassword.isEmpty()) {
                sendWarningMessage(requiredFieldsMessage())
                return@launch
            }
            setAccountBusy(true)
            runCatching {
                backendService.deleteAccount(
                    accessToken = session.accessToken,
                    curPassword = curPassword,
                    language = uiState.value.settings.language,
                )
                preferencesStore.clearCredentials()
                resetSessionAndContent()
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
            setAccountBusy(false)
        }
    }

    private suspend fun bootstrap(savedUsername: String, savedAccessToken: String) {
        if (savedUsername.isBlank() || savedAccessToken.isBlank()) {
            _uiState.update { it.copy(bootstrapping = false) }
            return
        }
        runCatching {
            backendService.autoLogin(savedAccessToken)
            onLoginSucceeded(savedUsername, savedAccessToken, welcomeBack = false)
        }.onFailure {
            preferencesStore.clearCredentials()
            _uiState.update { state ->
                state.copy(
                    bootstrapping = false,
                    session = SessionState(),
                )
            }
        }
    }

    private suspend fun onLoginSucceeded(username: String, accessToken: String, welcomeBack: Boolean) {
        val cacheFilesToDelete = _uiState.value.reading.activeCacheFiles
        cleanupPreviewCacheFiles(cacheFilesToDelete)
        invalidateDiskRequests()
        _uiState.update { state ->
            state.copy(
                bootstrapping = true,
                session = SessionState(
                    username = username,
                    accessToken = accessToken,
                    isLoggedIn = true,
                ),
                currentTab = AppTab.DISK,
                tabBackStack = emptyList(),
                settingsSubPage = SettingsSubPage.ROOT,
                reading = ReadingScreenState(),
            )
        }
        if (welcomeBack) {
            sendSuccessMessage(strings().fileDisk.welcomeBack)
        }
        loadDiskPage(
            target = DiskTarget.Root(),
            showLoading = false,
            replaceVisibleContent = false,
        )
        _uiState.update { it.copy(bootstrapping = false) }
    }

    private suspend fun loadDiskPage(
        target: DiskTarget,
        showLoading: Boolean,
        replaceVisibleContent: Boolean,
    ) {
        val session = activeSession() ?: return
        val requestToken = beginDiskLoad(
            displayedPaths = target.displayedPaths,
            showLoading = showLoading,
            replaceVisibleContent = replaceVisibleContent,
        )
        fetchDiskPage(target, session)
            .onSuccess { result ->
                if (!isActiveDiskRequest(requestToken)) return@onSuccess
                updateDirectoryState(
                    paths = result.paths,
                    listing = result.listing,
                    preserveDirectorySortDirection = _uiState.value.disk.directorySortDirection,
                    preserveSortKey = _uiState.value.disk.sortKey,
                    preserveSortDirection = _uiState.value.disk.sortDirection,
                )
            }
            .onFailure { throwable ->
                if (!isActiveDiskRequest(requestToken)) return@onFailure
                markDiskLoadFailed(throwable)
            }
    }

    private fun beginDiskLoad(
        displayedPaths: List<PathSegment>? = null,
        showLoading: Boolean,
        replaceVisibleContent: Boolean,
    ): Long {
        val requestToken = ++latestDiskRequestToken
        if (!showLoading && displayedPaths == null && !replaceVisibleContent) {
            return requestToken
        }
        _uiState.update { state ->
            val disk = state.disk
            state.copy(
                disk = disk.copy(
                    isLoading = showLoading,
                    errorMessage = "",
                    statusMessage = "",
                    paths = displayedPaths ?: disk.paths,
                    directories = if (replaceVisibleContent) emptyList() else disk.directories,
                    files = if (replaceVisibleContent) emptyList() else disk.files,
                )
            )
        }
        return requestToken
    }

    private fun markDiskLoadFailed(throwable: Throwable) {
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    isLoading = false,
                    errorMessage = toUserMessage(throwable),
                )
            )
        }
    }

    private suspend fun fetchDiskPage(target: DiskTarget, session: SessionState): Result<LoadedDiskPage> = when (target) {
        is DiskTarget.Root -> {
            runCatching {
                backendService.loadRoot(
                    accessToken = session.accessToken,
                    language = uiState.value.settings.language,
                )
            }.map { result ->
                LoadedDiskPage(
                    paths = listOf(PathSegment(level = 0, id = result.rootId, name = "root")),
                    listing = result.listing,
                )
            }
        }

        is DiskTarget.Directory -> {
            runCatching {
                backendService.loadDirectory(
                    accessToken = session.accessToken,
                    parentId = target.directoryId,
                    language = uiState.value.settings.language,
                )
            }.map { listing ->
                LoadedDiskPage(
                    paths = target.displayedPaths,
                    listing = listing,
                )
            }
        }
    }

    private fun currentDiskTarget(): DiskTarget {
        val currentPaths = _uiState.value.disk.paths
        val currentPath = currentPaths.lastOrNull()
        return when {
            currentPath == null -> DiskTarget.Root()
            currentPath.level == 0 && currentPaths.size == 1 -> DiskTarget.Root(currentPaths)
            else -> DiskTarget.Directory(
                directoryId = currentPath.id,
                displayedPaths = currentPaths,
            )
        }
    }

    private fun diskTargetForPath(pathSegment: PathSegment): DiskTarget {
        val nextPaths = _uiState.value.disk.paths.filter { it.level <= pathSegment.level }
        return if (pathSegment.level == 0) {
            DiskTarget.Root(nextPaths.ifEmpty { listOf(pathSegment) })
        } else {
            DiskTarget.Directory(
                directoryId = pathSegment.id,
                displayedPaths = nextPaths,
            )
        }
    }

    private fun currentRootPath(): PathSegment? = _uiState.value.disk.paths.firstOrNull()?.takeIf { it.level == 0 }

    private fun invalidateDiskRequests() {
        latestDiskRequestToken++
    }

    private fun isActiveDiskRequest(requestToken: Long): Boolean = requestToken == latestDiskRequestToken

    private sealed interface DiskTarget {
        val displayedPaths: List<PathSegment>?

        data class Root(
            override val displayedPaths: List<PathSegment>? = null,
        ) : DiskTarget

        data class Directory(
            val directoryId: Long,
            override val displayedPaths: List<PathSegment>,
        ) : DiskTarget
    }

    private data class LoadedDiskPage(
        val paths: List<PathSegment>,
        val listing: DirectoryListing,
    )

    private suspend fun applySortState(sortKey: SortKey, sortDirection: SortDirection) {
        val state = _uiState.value
        val nextDirectorySort = if (sortKey == SortKey.NAME) sortDirection else state.disk.directorySortDirection
        val sorted = withContext(Dispatchers.Default) {
            sortListing(
                directories = state.disk.directories,
                files = state.disk.files,
                directorySortDirection = nextDirectorySort,
                fileSortKey = sortKey,
                fileSortDirection = sortDirection,
                language = state.settings.language,
            )
        }
        _uiState.update {
            it.copy(
                disk = it.disk.copy(
                    directorySortDirection = nextDirectorySort,
                    sortKey = sortKey,
                    sortDirection = sortDirection,
                    directories = sorted.first,
                    files = sorted.second,
                )
            )
        }
    }

    private suspend fun updateDirectoryState(
        paths: List<PathSegment>,
        listing: DirectoryListing,
        preserveDirectorySortDirection: SortDirection,
        preserveSortKey: SortKey,
        preserveSortDirection: SortDirection,
    ) {
        val language = _uiState.value.settings.language
        val sorted = withContext(Dispatchers.Default) {
            sortListing(
                directories = listing.directories,
                files = listing.files,
                directorySortDirection = preserveDirectorySortDirection,
                fileSortKey = preserveSortKey,
                fileSortDirection = preserveSortDirection,
                language = language,
            )
        }
        val statusMessage = when {
            listing.directories.isEmpty() && listing.files.isEmpty() -> strings().fileDisk.directoryEmpty
            else -> ""
        }
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    isLoading = false,
                    errorMessage = "",
                    statusMessage = statusMessage,
                    paths = paths,
                    directories = sorted.first,
                    files = sorted.second,
                )
            )
        }
    }

    private fun sortListing(
        directories: List<DirectoryEntry>,
        files: List<FileEntry>,
        directorySortDirection: SortDirection,
        fileSortKey: SortKey,
        fileSortDirection: SortDirection,
        language: AppLanguage,
    ): Pair<List<DirectoryEntry>, List<FileEntry>> {
        val collator = Collator.getInstance(language.asLocale())
        val sortedDirectories = directories.sortedWith { left, right ->
            val result = collator.compare(left.name, right.name).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
            if (directorySortDirection == SortDirection.DESC) result * -1 else result
        }
        val sortedFiles = when (fileSortKey) {
            SortKey.NAME -> files.sortedWith { left, right ->
                val result = collator.compare(left.name, right.name).takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
                if (fileSortDirection == SortDirection.DESC) result * -1 else result
            }
            SortKey.CREATED -> sortFilesByTimestamp(
                files = files,
                fileSortDirection = fileSortDirection,
                timestampSelector = FileEntry::creationTime,
                collator = collator,
            )
            SortKey.UPDATED -> sortFilesByTimestamp(
                files = files,
                fileSortDirection = fileSortDirection,
                timestampSelector = FileEntry::lastModifiedTime,
                collator = collator,
            )
        }
        return sortedDirectories to sortedFiles
    }

    private fun sortFilesByTimestamp(
        files: List<FileEntry>,
        fileSortDirection: SortDirection,
        timestampSelector: (FileEntry) -> String,
        collator: Collator,
    ): List<FileEntry> {
        val prepared = files.map { file ->
            TimestampSortableFile(
                file = file,
                timestamp = normalizeTimestamp(timestampSelector(file)),
            )
        }
        val comparator = Comparator<TimestampSortableFile> { left, right ->
            val result = left.timestamp.compareTo(right.timestamp).takeIf { it != 0 }
                ?: collator.compare(left.file.name, right.file.name).takeIf { it != 0 }
                ?: left.file.id.compareTo(right.file.id)
            if (fileSortDirection == SortDirection.DESC) result * -1 else result
        }
        return prepared.sortedWith(comparator).map(TimestampSortableFile::file)
    }

    private data class TimestampSortableFile(
        val file: FileEntry,
        val timestamp: Long,
    )

    private fun normalizeTimestamp(value: String): Long {
        if (value.isBlank()) return Long.MIN_VALUE
        val normalized = value.trim().replace(" ", "T")
        val parsed = runCatching {
            LocalDateTime.parse(normalized)
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse(
                normalized,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            )
        }.getOrNull()
        return parsed?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    }

    private suspend fun activeSession(): SessionState? {
        val session = _uiState.value.session
        if (session.isLoggedIn && session.accessToken.isNotBlank()) return session
        sendWarningMessage(strings().auth.loginFirst)
        _uiState.update {
            it.copy(
                currentTab = AppTab.ACCOUNT,
                settingsSubPage = SettingsSubPage.ROOT,
                reading = resetReadingBottomBarIfNeeded(AppTab.ACCOUNT, it.reading),
            )
        }
        return null
    }

    private fun currentPath(): PathSegment? = _uiState.value.disk.paths.lastOrNull()

    private fun resetReadingBottomBarIfNeeded(
        targetTab: AppTab,
        reading: ReadingScreenState,
    ): ReadingScreenState = if (targetTab == AppTab.READING || reading.isBottomBarVisible) {
        reading
    } else {
        reading.copy(isBottomBarVisible = true)
    }

    private fun applyStoredPreferences(stored: com.notes.notes.data.StoredPreferences) {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    language = stored.language,
                    theme = ThemeSettings(
                        mode = stored.themeMode,
                        palette = stored.themePalette,
                    ),
                )
            )
        }
    }

    private fun setAccountBusy(busy: Boolean) {
        _uiState.update { it.copy(accountBusy = busy) }
    }

    private suspend fun resetSessionAndContent() {
        val cacheFilesToDelete = _uiState.value.reading.activeCacheFiles
        // Queued transfers belong to the session that just ended: their work is stopped, their remote
        // multipart uploads are aborted best-effort and their local state is deleted, so nothing can
        // ever run under the next account.
        UploadWork.cancelAll(getApplication())
        DownloadWork.cancelAll(getApplication())
        uploadWorkStates.clear()
        overwriteWarnedUploads.clear()
        handledUploadWorkIds.clear()
        handledDownloadWorkIds.clear()
        recentlyEnqueuedTransfers.clear()
        uploadBatchId = null
        uploadBatchTotalBytes = 0L
        uploadBatchWorkIds = emptySet()

        val uploads = transferStore.uploadsOnce()
        val downloads = transferStore.downloadsOnce()
        transferStore.removeUploads(uploads.map(UploadTransfer::transferId))
        transferStore.removeDownloads(downloads.map(DownloadTransfer::transferId))
        uploadTransfers = emptyList()
        downloadTransfers = emptyList()

        withContext(Dispatchers.IO) {
            withTimeoutOrNull(LOGOUT_ABORT_TIMEOUT_MILLIS) {
                uploads.forEach { transfer ->
                    runCatching {
                        if (transfer.phase == UploadPhase.METADATA_PENDING) return@runCatching
                        if (transfer.uploadId.isBlank() || transfer.objectKey.isBlank()) return@runCatching
                        val accessToken = currentAccessToken() ?: return@runCatching
                        val ticket = backendService.requestOssSts(
                            accessToken = accessToken,
                            pathString = transfer.pathString,
                            filename = transfer.displayName,
                            parentId = transfer.parentId,
                            language = AppLanguage.fromCode(transfer.language),
                            usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
                        )
                        transferRepository.abortMultipartUpload(
                            ticket = ticket,
                            objectKey = transfer.objectKey,
                            uploadId = transfer.uploadId,
                            credentialProvider = TransferStsCredentialProvider(
                                transfer = transfer,
                                accessToken = accessToken,
                                backendService = backendService,
                                firstTicket = ticket,
                            ),
                        )
                    }
                }
            }
            // Local checkpoints and unfinished MediaStore items are always removed, even when the
            // best-effort abort above timed out.
            uploads.forEach { transferRepository.deleteCheckpointDirectory(it.checkpointDir) }
            downloads.forEach { transferRepository.deleteDownloadDestination(it.destinationUri) }
        }

        val releaseFailures = UploadUriPermissionManager.releaseAllPersistedReadPermissions(getApplication())
        cleanupPreviewCacheFiles(cacheFilesToDelete)
        previewRepository.clearAllPreviewCache()
        invalidateDiskRequests()
        _uiState.update { state ->
            state.copy(
                bootstrapping = false,
                accountBusy = false,
                session = SessionState(),
                currentTab = AppTab.ACCOUNT,
                tabBackStack = emptyList(),
                settingsSubPage = SettingsSubPage.ROOT,
                disk = DiskScreenState(),
                reading = ReadingScreenState(),
            )
        }
        if (releaseFailures.isNotEmpty()) {
            sendErrorMessage(
                bulkReleaseUploadPermissionFailedMessage(
                    count = releaseFailures.size,
                    throwable = releaseFailures.first(),
                )
            )
        }
    }

    private fun cleanupPreviewCacheFiles(paths: Collection<String>) {
        if (paths.isEmpty()) return
        previewRepository.deleteCacheFiles(paths)
    }

    private fun releaseUploadCandidatePermissions(candidates: Collection<UploadCandidate>): List<Throwable> {
        return candidates.mapNotNull { candidate ->
            releaseUploadCandidatePermission(candidate.uriString)
        }
    }

    private fun releaseUploadCandidatePermission(uriString: String): Throwable? {
        // A persisted read permission is kept for as long as any transfer, pending work item or
        // selected candidate still refers to the document.
        val stillNeeded = uploadTransfers.any { it.sourceUri == uriString } ||
            uploadWorkInfos.any { !it.state.isFinished && UploadWork.sourceUri(it) == uriString } ||
            _uiState.value.disk.uploadCandidates.any { it.uriString == uriString }
        if (stillNeeded) return null

        return UploadUriPermissionManager.releaseReadPermission(
            context = getApplication(),
            uri = Uri.parse(uriString),
        )
    }

    private suspend fun cleanupStaleUploadUriPermissions() {
        val transfers = transferStore.uploadsOnce()
        // Checkpoints of transfers that no longer exist can never be resumed: drop them silently.
        withContext(Dispatchers.IO) {
            val knownTransferIds = transfers.mapTo(mutableSetOf(), UploadTransfer::transferId)
            transferRepository.orphanCheckpointDirectories(knownTransferIds)
                .forEach { directory -> runCatching { directory.deleteRecursively() } }
        }

        // Recoverable transfers and uploads restored by WorkManager still need their document.
        if (transfers.isNotEmpty()) return
        if (UploadWork.hasPendingUploads(getApplication())) return

        val failures = UploadUriPermissionManager.releaseAllPersistedReadPermissions(getApplication())
        if (failures.isNotEmpty()) {
            sendErrorMessage(
                staleUploadPermissionCleanupFailedMessage(
                    throwable = failures.first(),
                )
            )
        }
    }

    private fun persistUploadPermissionFailedMessage(fileNames: List<String>, throwable: Throwable): String {
        val fileLabel = summarizeFileNames(fileNames)
        val detail = throwableSummary(throwable)
        return when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> "已加入列表，但文件读取权限持久化失败：$fileLabel。$detail"
            AppLanguage.EN_US -> "Added to the queue, but failed to persist file read permission for $fileLabel. $detail"
        }
    }

    private fun releaseUploadPermissionFailedMessage(fileName: String, throwable: Throwable): String {
        val detail = throwableSummary(throwable)
        return when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> "文件读取权限清理失败：$fileName。$detail"
            AppLanguage.EN_US -> "Failed to release file read permission for $fileName. $detail"
        }
    }

    private fun uploadSucceededButReleaseFailedMessage(fileName: String, throwable: Throwable): String {
        val detail = throwableSummary(throwable)
        return when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> "$fileName 上传成功，但文件读取权限清理失败。$detail"
            AppLanguage.EN_US -> "$fileName uploaded successfully, but its file read permission could not be released. $detail"
        }
    }

    private fun bulkReleaseUploadPermissionFailedMessage(count: Int, throwable: Throwable): String {
        val detail = throwableSummary(throwable)
        return when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> "有 $count 个文件读取权限清理失败。$detail"
            AppLanguage.EN_US -> "Failed to release $count file read permission(s). $detail"
        }
    }

    private fun staleUploadPermissionCleanupFailedMessage(throwable: Throwable): String {
        val detail = throwableSummary(throwable)
        return when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> "上次残留的文件读取权限清理失败。$detail"
            AppLanguage.EN_US -> "Failed to clean up stale file read permissions from the previous session. $detail"
        }
    }

    private fun summarizeFileNames(fileNames: List<String>): String {
        if (fileNames.isEmpty()) return "-"
        return if (fileNames.size == 1) {
            fileNames.first()
        } else {
            "${fileNames.first()} +${fileNames.size - 1}"
        }
    }

    private fun throwableSummary(throwable: Throwable): String {
        val raw = throwable.localizedMessage
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        return raw.replace('\n', ' ').take(120)
    }

    private suspend fun emitMessage(message: String, tone: MessageTone = MessageTone.INFO) {
        messageChannel.send(UiMessage(message = message, tone = tone))
    }

    private fun strings() = stringsFor(_uiState.value.settings.language)

    private fun toUserMessage(throwable: Throwable, authRequest: Boolean = false): String {
        val strings = strings()
        return when (throwable) {
            is NotesServiceException.Business -> throwable.errorMessage
            is NotesServiceException.Http -> strings.httpErrorMessage(throwable.statusCode, authRequest)
            is NotesServiceException.MissingBaseUrl -> strings.common.baseUrlMissing
            else -> strings.common.networkError
        }
    }

    private fun sendMessage(message: String, tone: MessageTone = MessageTone.INFO) {
        viewModelScope.launch {
            messageChannel.send(UiMessage(message = message, tone = tone))
        }
    }

    private fun sendSuccessMessage(message: String) = sendMessage(message, MessageTone.SUCCESS)

    private fun sendErrorMessage(message: String) = sendMessage(message, MessageTone.ERROR)

    private fun sendWarningMessage(message: String) = sendMessage(message, MessageTone.WARNING)

    private fun sendInfoMessage(message: String) = sendMessage(message, MessageTone.INFO)

    private fun sendThrowableMessage(throwable: Throwable, authRequest: Boolean = false) {
        sendErrorMessage(toUserMessage(throwable, authRequest))
    }

    private fun requiredFieldsMessage(): String = when (_uiState.value.settings.language) {
        AppLanguage.ZH_CN -> "请输入完整信息"
        AppLanguage.EN_US -> "Please fill in all required fields."
    }

    private fun registrationCompletedMessage(): String = when (_uiState.value.settings.language) {
        AppLanguage.ZH_CN -> "注册成功，请切换到登录"
        AppLanguage.EN_US -> "Registration succeeded. Switch back to Login."
    }

    private fun blankFieldMessage(): String = when (_uiState.value.settings.language) {
        AppLanguage.ZH_CN -> "请输入完整信息"
        AppLanguage.EN_US -> "Please fill in all required fields."
    }

    private fun registrationSuccessMessage(): String = when (_uiState.value.settings.language) {
        AppLanguage.ZH_CN -> "注册成功，请切换到登录"
        AppLanguage.EN_US -> "Registration succeeded. Switch back to Login."
    }

    private companion object {
        const val TAG = "NotesAppViewModel"

        /**
         * Keeps visible progress below completion while a batch is running: the web client reserves its
         * last percent for the backend insert, so a failing file is never shown as finished.
         */
        const val MAX_UPLOAD_PROGRESS_BEFORE_COMPLETION = 0.99f

        const val OSS_USAGE_SINGLE_FILE_UPLOAD = "SINGLE_FILE_UPLOAD"

        /** How long a just-enqueued transfer may be missing from the WorkManager snapshot. */
        const val ENQUEUE_GRACE_MILLIS = 5_000L

        /** A destructive cancel is best effort; the UI must never wait on it for long. */
        const val CANCEL_ABORT_TIMEOUT_MILLIS = 15_000L

        /** Logout waits for aborts only briefly: local state is cleaned either way. */
        const val LOGOUT_ABORT_TIMEOUT_MILLIS = 10_000L

        /** Lets a stopped SDK task release its checkpoint file before the directory is deleted. */
        const val CHECKPOINT_RELEASE_DELAY_MILLIS = 300L

        /** Upper bound for waiting until WorkManager persisted a cancellation. */
        const val WORK_CANCELLATION_TIMEOUT_MILLIS = 5_000L
    }
}
