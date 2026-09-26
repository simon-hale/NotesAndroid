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
import com.notes.notes.core.DownloadRingState
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
import com.notes.notes.data.DownloadRoundProgress
import com.notes.notes.data.DownloadTaskProgress
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
import kotlinx.coroutines.Job
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
import kotlin.plus

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

    /**
     * Prevents transfer reconciliation from recreating work while the current account is being torn
     * down by logout, password change, account deletion or rejected automatic login.
     */
    private var sessionResetInProgress = false

    /**
     * WorkManager and TransferStore collectors can both notice the same orphaned METADATA_PENDING record.
     * Only one recovery pass may enqueue replacement work at a time.
     */
    private var metadataRecoveryInProgress = false

    private val handledDownloadWorkIds = mutableSetOf<UUID>()
    private val downloadWorkStates = mutableMapOf<UUID, WorkInfo.State>()

    /**
     * Byte accounting of the download round the drawer ring shows.
     *
     * It lives in the ViewModel, so a configuration change keeps the round; only a killed process ends
     * it, and the downloads that are really active when the app returns simply start a new round.
     */
    private val downloadRoundProgress = DownloadRoundProgress()

    /** Latest transfer projection waiting for its coalesced publish, if any. */
    private var pendingTransferUi: PendingTransferUi? = null
    private var transferUiFlushJob: Job? = null
    private var lastTransferUiPublishAt = 0L

    init {
        viewModelScope.launch {
            val initial = preferencesStore.preferences.first()
            applyStoredPreferences(initial)
            launch {
                preferencesStore.preferences.collect { applyStoredPreferences(it) }
            }
            cleanupStaleUploadUriPermissions()
            cleanupStaleDownloadDestinations()
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
        // One upload batch must be resolved before files for the next one are picked. A file-picker
        // callback can arrive after the sheet was closed or after a batch started, so the rule cannot
        // live in the UI alone. Two independent barriers apply: `isUploading` covers the window in
        // which the batch was accepted but its transfer records are not persisted yet, and
        // `ownedUploadTransfers()` covers persisted, paused and metadata-pending batches. The
        // ViewModel's own transfer state is authoritative for the second one, because the UI mirror in
        // DiskScreenState is only updated asynchronously by the store collector. Foreign-account
        // records are filtered out by `ownedUploadTransfers()` and never block the signed-in user.
        if (_uiState.value.disk.isUploading || ownedUploadTransfers().isNotEmpty()) {
            sendWarningMessage(strings().transfers.resolveBatchFirst)
            return
        }

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

            if (
                state.disk.transferActionBusy ||
                state.disk.isUploading
            ) {
                return@launch
            }

// An existing transfer record always belongs to the current unresolved batch. This includes the
// all-paused case and METADATA_PENDING, so a second batch can never be created before the first one
// has completely disappeared.
            if (ownedUploadTransfers().isNotEmpty()) {
                sendWarningMessage(strings.transfers.resolveBatchFirst)
                return@launch
            }

            val session = activeSession() ?: return@launch
            val path = currentPath() ?: return@launch
            val candidates = state.disk.uploadCandidates

            if (candidates.isEmpty()) {
                sendWarningMessage(strings.fileDisk.noFileSelected)
                return@launch
            }

            val pathString = state.disk.paths.joinToString(separator = "") { "${it.id}/" }
            val language = state.settings.language
            val batchId = "batch-${System.currentTimeMillis()}"
            val createdAt = System.currentTimeMillis()
            val fileBytes = candidates.map { it.sizeBytes.coerceAtLeast(1L) }
            val batchTotalBytes = fileBytes.sum()

            // A new batch is decided here, so the "one batch at a time" rule has to take effect before
            // the first suspending step below: until the transfers are persisted and mirrored, a stale
            // or very fast file-picker callback could otherwise still append candidates to a second
            // batch. Nothing has been created yet, so a failure on the startup path rolls this back.
            _uiState.update { it.copy(disk = it.disk.copy(isUploading = true, uploadProgress = 0f)) }

            // The transfer target is frozen here: every later credential refresh and metadata commit
            // reuses these values, never the directory the user happens to be browsing later.
            val transfers = try {
                withContext(Dispatchers.IO) {
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
            } catch (cancellation: CancellationException) {
                rollBackUploadStartup()
                throw cancellation
            } catch (throwable: Throwable) {
                rollBackUploadStartup()
                sendThrowableMessage(throwable)
                return@launch
            }

            // Marked before the record is stored: until the work item shows up, a missing work item
            // must not be mistaken for "stopped".
            markRecentlyEnqueued(transfers.map { it.transferId })
            // Persisted before the work is enqueued, so a crash cannot leave untracked uploads.
            try {
                transferStore.addUploads(transfers)
            } catch (cancellation: CancellationException) {
                rollBackUploadStartup()
                throw cancellation
            } catch (throwable: Throwable) {
                // Nothing was persisted, so no batch exists and no partial in-memory state was set.
                rollBackUploadStartup()
                sendThrowableMessage(throwable)
                return@launch
            }
            // Mirrored immediately so permission cleanup never believes these documents are unused.
            val newTransferIds =
                transfers.mapTo(mutableSetOf(), UploadTransfer::transferId)

            /*
             * The DataStore collector may already have projected these records before execution
             * returns here. Replace by transferId instead of blindly appending, otherwise the UI
             * briefly renders the batch twice.
             */
            uploadTransfers =
                uploadTransfers.filterNot { current ->
                    current.transferId in newTransferIds
                } + transfers
            uploadBatchId = batchId
            uploadBatchTotalBytes = batchTotalBytes
            // WorkManager owns the transfer from here on, so it survives backgrounding and process death.
            val batch = try {
                UploadWork.enqueue(
                    context = getApplication(),
                    accessToken = session.accessToken,
                    transfers = transfers,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // The transfer records already exist, but WorkManager failed to take ownership.
                // Stop any work that may have been partially enqueued, then expose the batch as PAUSED
                // so the user can explicitly resume it later.
                val transferIds = transfers.map(UploadTransfer::transferId)

                runCatching {
                    awaitWorkCancellations(
                        UploadWork.cancelTransfers(
                            getApplication(),
                            transferIds,
                        )
                    )
                }

                transfers.forEach { transfer ->
                    transferStore.updateUpload(transfer.transferId) { current ->
                        if (current.phase == UploadPhase.TRANSFERRING) {
                            current.copy(phase = UploadPhase.PAUSED)
                        } else {
                            current
                        }
                    }
                }

                val failedIds = transferIds.toSet()
                uploadTransfers = uploadTransfers.map { current ->
                    if (
                        current.transferId in failedIds &&
                        current.phase == UploadPhase.TRANSFERRING
                    ) {
                        current.copy(phase = UploadPhase.PAUSED)
                    } else {
                        current
                    }
                }

                rollBackUploadStartup()
                refreshTransferUi()
                sendThrowableMessage(throwable)
                return@launch
            }
            uploadBatchWorkIds = batch?.workIds.orEmpty()
        }
    }

    /**
     * Undoes the optimistic uploading state of a batch that could not be established.
     *
     * Only the progress flags are restored: persisted transfers, their mirror and the frozen batch
     * identity are never discarded here, because they are exactly what makes a failed batch
     * recoverable.
     */
    private fun rollBackUploadStartup() {
        _uiState.update { it.copy(disk = it.disk.copy(isUploading = false, uploadProgress = 0f)) }
    }

    /** Pauses exactly one upload. The remote multipart upload and local checkpoint are preserved. */
    fun pauseUpload(transferId: String) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) return@launch

            val transfer = ownedUploadTransfers()
                .firstOrNull { it.transferId == transferId }
                ?: return@launch

            if (transfer.phase != UploadPhase.TRANSFERRING) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updated = transferStore.updateUpload(transferId) { current ->
                    if (current.phase == UploadPhase.TRANSFERRING) {
                        current.copy(phase = UploadPhase.PAUSED)
                    } else {
                        current
                    }
                } ?: return@launch

                uploadTransfers = uploadTransfers.map { current ->
                    if (current.transferId == transferId) {
                        updated
                    } else {
                        current
                    }
                }
                refreshTransferUi()

                awaitWorkCancellations(
                    UploadWork.cancelTransfers(
                        getApplication(),
                        listOf(transferId),
                    )
                )

                sendInfoMessage(strings().transfers.paused)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    /** Resumes exactly one paused upload from its own persisted checkpoint. */
    fun resumeUpload(transferId: String) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) return@launch

            val session = activeSession() ?: return@launch

            val transfer = ownedUploadTransfers()
                .firstOrNull { it.transferId == transferId }
                ?: return@launch

            if (transfer.phase != UploadPhase.PAUSED) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updated = transferStore.updateUpload(transferId) { current ->
                    if (current.phase == UploadPhase.PAUSED) {
                        current.copy(phase = UploadPhase.TRANSFERRING)
                    } else {
                        current
                    }
                } ?: return@launch

                uploadTransfers = uploadTransfers.map { current ->
                    if (current.transferId == transferId) {
                        updated
                    } else {
                        current
                    }
                }
                refreshTransferUi()

                uploadBatchId = updated.batchId
                uploadBatchTotalBytes = updated.batchTotalBytes

                markRecentlyEnqueued(listOf(transferId))

                try {
                    val enqueued = UploadWork.enqueue(
                        context = getApplication(),
                        accessToken = session.accessToken,
                        transfers = listOf(updated),
                    )

                    if (enqueued != null) {
                        uploadBatchWorkIds =
                            uploadBatchWorkIds + enqueued.workIds
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // WorkManager did not take ownership. Restore PAUSED so the UI never claims that a
                    // transfer is running when nothing can actually continue it.
                    val paused = transferStore.updateUpload(transferId) { current ->
                        if (current.phase == UploadPhase.TRANSFERRING) {
                            current.copy(phase = UploadPhase.PAUSED)
                        } else {
                            current
                        }
                    }

                    if (paused != null) {
                        uploadTransfers = uploadTransfers.map { current ->
                            if (current.transferId == transferId) {
                                paused
                            } else {
                                current
                            }
                        }
                    }

                    refreshTransferUi()
                    sendThrowableMessage(throwable)
                }
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    /**
     * Deletes every safely destructible upload owned by the active account.
     *
     * METADATA_PENDING is deliberately preserved: its OSS object is already complete and deleting the
     * recovery record would orphan that object without a backend file row.
     */
    fun deleteAllUploads() {
        if (_uiState.value.disk.transferActionBusy) return

        cancelUploadTransfers(
            ownedUploadTransfers()
        )
    }

    /** Destructive cancel of a single transfer, for example from its row in the upload sheet. */
    fun cancelUpload(transferId: String) {
        if (_uiState.value.disk.transferActionBusy) return

        cancelUploadTransfers(
            ownedUploadTransfers().filter { it.transferId == transferId }
        )
    }

    /**
     * Destructively deletes the requested unfinished uploads.
     *
     * The final phase decision is performed atomically by TransferStore rather than trusting the UI
     * snapshot passed into this method. A transfer that reached METADATA_PENDING immediately before the
     * delete operation is therefore preserved automatically.
     */
    private fun cancelUploadTransfers(
        targets: List<UploadTransfer>,
    ) {
        val requestedIds =
            targets.mapTo(
                mutableSetOf(),
                UploadTransfer::transferId,
            )

        if (requestedIds.isEmpty()) {
            return
        }

        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                /*
                 * Important: the store re-checks each transfer's CURRENT phase inside the same atomic
                 * DataStore edit that removes it.
                 *
                 * If CompleteMultipartUpload just succeeded and the worker changed the record to
                 * METADATA_PENDING, that record is not returned and is not deleted.
                 */
                val removed =
                    transferStore.removeCancellableUploads(
                        requestedIds
                    )

                if (removed.isEmpty()) {
                    // Most commonly this means every requested item became METADATA_PENDING.
                    refreshTransferUi()
                    return@launch
                }

                val removedIds =
                    removed.mapTo(
                        mutableSetOf(),
                        UploadTransfer::transferId,
                    )

                val removedSourceUris =
                    removed.mapTo(
                        mutableSetOf(),
                        UploadTransfer::sourceUri,
                    )

                /*
                 * Update the in-memory mirror immediately instead of waiting for the DataStore collector,
                 * otherwise deleted rows can briefly flash back into the sheet.
                 */
                uploadTransfers =
                    uploadTransfers.filterNot { transfer ->
                        transfer.transferId in removedIds
                    }

                /*
                 * Delete means "do not upload this source again". Remove its original picker candidate too.
                 */
                _uiState.update { state ->
                    state.copy(
                        disk = state.disk.copy(
                            uploadCandidates =
                                state.disk.uploadCandidates.filterNot { candidate ->
                                    candidate.uriString in removedSourceUris
                                }
                        )
                    )
                }

                removedIds.forEach { transferId ->
                    recentlyEnqueuedTransfers.remove(
                        transferId
                    )
                }

                refreshTransferUi()

                /*
                 * Stop OSS SDK activity before AbortMultipartUpload.
                 */
                awaitWorkCancellations(
                    UploadWork.cancelTransfers(
                        context = getApplication(),
                        transferIds = removedIds,
                    )
                )

                /*
                 * Permission cleanup is idempotent. A URI still required by another transfer is retained.
                 */
                val permissionFailures =
                    releaseUnusedUploadPermissions()

                /*
                 * One failed remote abort must not stop cleanup of other files.
                 * abortAndCleanCheckpoint() always deletes the local checkpoint in finally.
                 */
                val deferredRemoteCleanup =
                    withContext(Dispatchers.IO) {
                        removed.filterNot { transfer ->
                            abortAndCleanCheckpoint(
                                transfer
                            )
                        }
                    }

                if (permissionFailures.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "One or more upload source permissions could not be released",
                        permissionFailures.first(),
                    )
                }

                if (deferredRemoteCleanup.isNotEmpty()) {
                    sendWarningMessage(
                        uploadDeleteDeferredCleanupMessage(
                            deferredRemoteCleanup.size
                        )
                    )
                } else {
                    sendInfoMessage(
                        strings().transfers.transferCanceled
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(
                    TAG,
                    "Unable to delete upload transfers",
                    throwable,
                )
                sendThrowableMessage(throwable)
            } finally {
                setTransferActionBusy(false)
            }
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
     * Tries to remove the unfinished multipart upload and always drops local resumable state.
     *
     * Returns true when there was no remote multipart state to clean, or AbortMultipartUpload succeeded.
     * A false result means local deletion is still complete, while abandoned remote parts are left for
     * the bucket's incomplete-multipart lifecycle rule.
     */
    private suspend fun abortAndCleanCheckpoint(
        transfer: UploadTransfer,
    ): Boolean {
        var remoteClean = true

        try {
            if (
                transfer.phase != UploadPhase.METADATA_PENDING &&
                transfer.uploadId.isNotBlank() &&
                transfer.objectKey.isNotBlank()
            ) {
                remoteClean =
                    withTimeoutOrNull(
                        CANCEL_ABORT_TIMEOUT_MILLIS
                    ) {
                        try {
                            val accessToken =
                                currentAccessToken()
                                    ?: return@withTimeoutOrNull false

                            val ticket =
                                backendService.requestOssSts(
                                    accessToken = accessToken,
                                    pathString = transfer.pathString,
                                    filename = transfer.displayName,
                                    parentId = transfer.parentId,
                                    language =
                                        AppLanguage.fromCode(
                                            transfer.language
                                        ),
                                    usage =
                                        OSS_USAGE_SINGLE_FILE_UPLOAD,
                                )

                            /*
                             * WorkManager cancellation was already issued. Leave a short window for the
                             * SDK task to release its checkpoint before aborting and deleting it.
                             */
                            delay(
                                CHECKPOINT_RELEASE_DELAY_MILLIS
                            )

                            transferRepository.abortMultipartUpload(
                                ticket = ticket,
                                objectKey = transfer.objectKey,
                                uploadId = transfer.uploadId,
                                credentialProvider =
                                    TransferStsCredentialProvider(
                                        transfer = transfer,
                                        accessToken = accessToken,
                                        backendService = backendService,
                                        firstTicket = ticket,
                                    ),
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            Log.w(
                                TAG,
                                "Best-effort multipart abort failed for ${transfer.displayName}",
                                throwable,
                            )
                            false
                        }
                    } ?: false
            }
        } finally {
            /*
             * Local resumable state must disappear regardless of:
             * - STS failure
             * - network failure
             * - OSS abort failure
             * - abort timeout
             *
             * Any directory the OS temporarily refuses to remove is also covered by the existing orphan
             * checkpoint sweep on a later app startup.
             */
            transferRepository.deleteCheckpointDirectory(
                transfer.checkpointDir
            )
        }

        return remoteClean
    }

    private fun observeUploadWork() {
        viewModelScope.launch {
            UploadWork.workInfos(getApplication())
                .collect(::applyUploadWorkInfos)
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

        // Covers completion/source-loss cleanup even when the corresponding WorkInfo transition happened
        // while the process was dead. Only documents still referenced by candidates/transfers/live work
        // retain a persistable SAF permission.
        releaseUnusedUploadPermissions()
        records
            // Another account's notices are not this session's to surface or to clear.
            .filter { isOwnedByActiveSession(it.accountKey) }
            .forEach { record ->
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
        records
            // Another account's notices are not this session's to surface or to clear.
            .filter { isOwnedByActiveSession(it.accountKey) }
            .forEach { record ->
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

    /**
     * Mirrors the persisted transfers plus live work state into the disk UI.
     *
     * Only records owned by the active session are projected: another account's transfers stay in the
     * store, untouched and invisible, until their owner signs in again.
     */
    private fun refreshTransferUi() {
        val uploadEntries = uploadTransfers
            .filter { isOwnedByActiveSession(it.accountKey) }
            .map { record ->
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
        /*
         * The round accounting runs after the transfer mirror and the WorkManager snapshots are up to
         * date and produces the ring together with the rows, so one publish always describes one
         * consistent instant: never a green progress followed by a paused state.
         */
        val now = System.currentTimeMillis()
        val liveDownloadTransferIds = downloadWorkInfos
            .filterNot { it.state.isFinished }
            .mapNotNullTo(mutableSetOf(), DownloadWork::transferId)
        val downloadEntries = mutableListOf<DownloadTransferEntry>()
        val downloadTasks = mutableListOf<DownloadTaskProgress>()
        downloadTransfers
            .filter { isOwnedByActiveSession(it.accountKey) }
            .forEach { record ->
                val info = downloadWorkInfos.firstOrNull { info ->
                    !info.state.isFinished && DownloadWork.transferId(info) == record.transferId
                }
                downloadEntries += DownloadTransferEntry(
                    transferId = record.transferId,
                    fileName = record.fileName,
                    phase = record.phase,
                    downloadedBytes = record.downloadedBytes,
                    totalBytes = record.totalBytes,
                    waitingForNetwork = info != null && info.state == WorkInfo.State.ENQUEUED,
                )
                downloadTasks += DownloadTaskProgress(
                    transferId = record.transferId,
                    // The running worker publishes byte progress more often than it persists the
                    // record, so those live values are the fresher pair of the two.
                    totalBytes = info?.progress
                        ?.getLong(DownloadWorker.KEY_PROGRESS_TOTAL_BYTES, record.totalBytes)
                        ?: record.totalBytes,
                    downloadedBytes = info?.progress
                        ?.getLong(DownloadWorker.KEY_PROGRESS_DOWNLOADED_BYTES, record.downloadedBytes)
                        ?: record.downloadedBytes,
                    // Waiting for network or a worker slot still counts as active: the task has not
                    // stopped transferring, it just has not been scheduled yet.
                    active = record.transferId in liveDownloadTransferIds ||
                        (
                            record.phase == DownloadPhase.TRANSFERRING &&
                                isWithinEnqueueGrace(record.transferId, now)
                            ),
                    paused = record.phase == DownloadPhase.PAUSED,
                )
            }
        val round = downloadRoundProgress.reduce(downloadTasks)

        // Enqueue grace entries of settled transfers are no longer needed.
        val knownTransferIds =
            uploadTransfers.mapTo(
                mutableSetOf(),
                UploadTransfer::transferId,
            ).apply {
                downloadTransfers.forEach { transfer ->
                    add(transfer.transferId)
                }
            }

        val liveWorkTransferIds = buildSet {
            uploadWorkInfos
                .filterNot { it.state.isFinished }
                .mapNotNullTo(this, UploadWork::transferId)

            downloadWorkInfos
                .filterNot { it.state.isFinished }
                .mapNotNullTo(this, DownloadWork::transferId)
        }

        /*
         * Do not immediately remove a freshly-created enqueue marker merely because neither
         * TransferStore nor WorkManager has delivered its asynchronous update yet.
         *
         * That exact race used to make a brand-new download look orphaned and reconcile it
         * from TRANSFERRING to PAUSED before its Worker could start.
         */
        recentlyEnqueuedTransfers.entries.removeAll { entry ->
            entry.key !in knownTransferIds &&
                    entry.key !in liveWorkTransferIds &&
                    now - entry.value >= ENQUEUE_GRACE_MILLIS
        }
        publishTransferUi(
            uploadEntries = uploadEntries,
            downloadEntries = downloadEntries,
            ring = round.ring,
            immediate = round.structural ||
                rowsChangedBeyondBytes(uploadEntries, downloadEntries),
        )
    }

    /**
     * True when the projected rows differ from what is on screen in anything but their byte counters,
     * which is exactly what a pause, a resume, a queued or a removed transfer looks like.
     */
    private fun rowsChangedBeyondBytes(
        uploadEntries: List<UploadTransferEntry>,
        downloadEntries: List<DownloadTransferEntry>,
    ): Boolean {
        val published = _uiState.value.disk
        if (published.uploadTransfers.size != uploadEntries.size) return true
        if (published.downloadTransfers.size != downloadEntries.size) return true
        if (published.uploadTransfers.zip(uploadEntries).any { (current, next) ->
                current.transferId != next.transferId ||
                    current.displayName != next.displayName ||
                    current.phase != next.phase ||
                    current.fileBytes != next.fileBytes ||
                    current.waitingForNetwork != next.waitingForNetwork
            }
        ) {
            return true
        }
        return published.downloadTransfers.zip(downloadEntries).any { (current, next) ->
            current.transferId != next.transferId ||
                current.fileName != next.fileName ||
                current.phase != next.phase ||
                current.totalBytes != next.totalBytes ||
                current.waitingForNetwork != next.waitingForNetwork
        }
    }

    private data class PendingTransferUi(
        val uploadEntries: List<UploadTransferEntry>,
        val downloadEntries: List<DownloadTransferEntry>,
        val ring: DownloadRingState,
    )

    /**
     * Publishes the transfer projection, coalescing plain byte-progress refreshes.
     *
     * Chunk-level byte updates arrive far more often than the ring has to be redrawn, so a pure
     * progress publish is merged with the next one inside
     * [DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MILLIS]. Everything that changes what the state *means* — a
     * round starting or ending, a member joining, completing, failing, being cancelled, or a pause and
     * resume — arrives with `immediate` set and is published at once, replacing any pending update, so
     * no intermediate percentage can reach the screen.
     */
    private fun publishTransferUi(
        uploadEntries: List<UploadTransferEntry>,
        downloadEntries: List<DownloadTransferEntry>,
        ring: DownloadRingState,
        immediate: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTransferUiPublishAt
        if (immediate || elapsed >= DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MILLIS) {
            cancelPendingTransferUi()
            lastTransferUiPublishAt = now
            applyTransferUi(uploadEntries, downloadEntries, ring)
            return
        }

        pendingTransferUi = PendingTransferUi(uploadEntries, downloadEntries, ring)
        if (transferUiFlushJob != null) return

        transferUiFlushJob = viewModelScope.launch {
            delay(DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MILLIS - elapsed)
            transferUiFlushJob = null
            val pending = pendingTransferUi ?: return@launch
            pendingTransferUi = null
            lastTransferUiPublishAt = System.currentTimeMillis()
            applyTransferUi(pending.uploadEntries, pending.downloadEntries, pending.ring)
        }
    }

    private fun cancelPendingTransferUi() {
        transferUiFlushJob?.cancel()
        transferUiFlushJob = null
        pendingTransferUi = null
    }

    private fun applyTransferUi(
        uploadEntries: List<UploadTransferEntry>,
        downloadEntries: List<DownloadTransferEntry>,
        ring: DownloadRingState,
    ) {
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    uploadTransfers = uploadEntries,
                    downloadTransfers = downloadEntries,
                    downloadRing = ring,
                )
            )
        }
    }

    /**
     * Restores metadata-only upload work that disappeared from WorkManager.
     *
     * METADATA_PENDING means the OSS object already exists. Re-enqueuing the transfer is safe because
     * UploadWorker detects this phase and performs only /api/file/insert/; it never uploads the object
     * again.
     */
    private suspend fun recoverMetadataPendingUploads() {
        /*
         * Never recreate background work while this account is deliberately being torn down.
         *
         * Also serialize recovery passes: both WorkManager and TransferStore observers can notice the same
         * missing worker nearly simultaneously.
         */
        if (
            sessionResetInProgress ||
            metadataRecoveryInProgress
        ) {
            return
        }

        metadataRecoveryInProgress = true

        try {
            val session =
                activeSession()
                    ?: return

            val candidates =
                transferStore.uploadsOnce()
                    .filter { transfer ->
                        isOwnedByActiveSession(
                            transfer.accountKey
                        ) &&
                                transfer.phase ==
                                UploadPhase.METADATA_PENDING
                    }

            if (candidates.isEmpty()) {
                return
            }

            /*
             * Read WorkManager directly instead of relying on the asynchronously updated ViewModel mirror.
             * A metadata worker that already exists must never be unnecessarily replaced.
             */
            val liveTransferIds =
                UploadWork.workInfos(
                    getApplication()
                )
                    .first()
                    .filterNot { info ->
                        info.state.isFinished
                    }
                    .mapNotNull(
                        UploadWork::transferId
                    )
                    .toSet()

            val missingWork =
                candidates.filter { transfer ->
                    transfer.transferId !in
                            liveTransferIds
                }

            if (missingWork.isEmpty()) {
                return
            }

            val transferIds =
                missingWork.map(
                    UploadTransfer::transferId
                )

            markRecentlyEnqueued(
                transferIds
            )

            try {
                val enqueued =
                    UploadWork.enqueue(
                        context = getApplication(),
                        accessToken =
                            session.accessToken,
                        transfers = missingWork,
                    )

                if (enqueued != null) {
                    uploadBatchWorkIds =
                        uploadBatchWorkIds +
                                enqueued.workIds
                }
            } catch (cancellation: CancellationException) {
                transferIds.forEach { transferId ->
                    recentlyEnqueuedTransfers.remove(
                        transferId
                    )
                }
                throw cancellation
            } catch (throwable: Throwable) {
                /*
                 * Keep every METADATA_PENDING record. A future reconciliation/login can retry metadata-only
                 * recovery without ever uploading the OSS object again.
                 */
                transferIds.forEach { transferId ->
                    recentlyEnqueuedTransfers.remove(
                        transferId
                    )
                }

                Log.w(
                    TAG,
                    "Unable to restore metadata-pending upload work",
                    throwable,
                )
            }
        } finally {
            metadataRecoveryInProgress = false
        }
    }

    private fun reconcileUploadPhases() {
        /*
         * WorkManager cancellation itself emits new snapshots. While a session reset is deliberately
         * cancelling work, those snapshots must not be interpreted as missing work requiring recovery.
         */
        if (sessionResetInProgress) {
            return
        }

        val now =
            System.currentTimeMillis()

        val liveTransferIds =
            uploadWorkInfos
                .filterNot { info ->
                    info.state.isFinished
                }
                .mapNotNull(
                    UploadWork::transferId
                )
                .toSet()

        val ownedTransfers =
            ownedUploadTransfers()

        /*
         * A normal unfinished multipart transfer whose WorkManager item disappeared cannot safely be
         * assumed to still be running. Preserve all resumable state and expose it as PAUSED.
         */
        ownedTransfers
            .filter { transfer ->
                transfer.phase ==
                        UploadPhase.TRANSFERRING &&
                        transfer.transferId !in
                        liveTransferIds
            }
            .filter { transfer ->
                isEnqueueSettled(
                    transfer.transferId,
                    now,
                )
            }
            .forEach { record ->
                viewModelScope.launch {
                    transferStore.updateUpload(
                        record.transferId
                    ) { current ->
                        if (
                            current.phase ==
                            UploadPhase.TRANSFERRING
                        ) {
                            current.copy(
                                phase =
                                    UploadPhase.PAUSED
                            )
                        } else {
                            current
                        }
                    }
                }
            }

        /*
         * METADATA_PENDING is different: no user intervention is useful here because the object has
         * already been uploaded. If its Worker disappeared, automatically restore metadata-only work.
         */
        val orphanedMetadata =
            ownedTransfers.any { transfer ->
                transfer.phase ==
                        UploadPhase.METADATA_PENDING &&
                        transfer.transferId !in
                        liveTransferIds &&
                        isEnqueueSettled(
                            transfer.transferId,
                            now,
                        )
            }

        if (orphanedMetadata) {
            viewModelScope.launch {
                recoverMetadataPendingUploads()
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
            // A foreign owner's record is not this session's to recover.
            .filter { isOwnedByActiveSession(it.accountKey) }
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

    /** Read-only counterpart of [isEnqueueSettled] for the transfer projection. */
    private fun isWithinEnqueueGrace(transferId: String, now: Long): Boolean {
        val enqueuedAt = recentlyEnqueuedTransfers[transferId] ?: return false
        return now - enqueuedAt < ENQUEUE_GRACE_MILLIS
    }

    private fun setTransferActionBusy(busy: Boolean) {
        _uiState.update { it.copy(disk = it.disk.copy(transferActionBusy = busy)) }
    }

    /**
     * True when the active session may see and operate on a transfer owned by [accountKey].
     *
     * A transfer owned by another account is never projected into the UI and never mutated, its
     * record and OSS checkpoint included; it stays isolated until its owner signs in again, or until
     * an explicit logout for that owner removes it. Records without an owner key come from an older
     * app version and belong to whoever is signed in.
     */
    private fun isOwnedByActiveSession(accountKey: String): Boolean {
        val session = _uiState.value.session
        if (!session.isLoggedIn || session.username.isBlank()) return false
        return accountKey.isBlank() || accountKey == session.username
    }

    /** Records the active session owns, i.e. the only ones it is allowed to act on. */
    private fun ownedUploadTransfers(): List<UploadTransfer> =
        uploadTransfers.filter { isOwnedByActiveSession(it.accountKey) }

    /** Records belonging to another account: left completely untouched for their owner. */
    private fun foreignUploadTransfers(): List<UploadTransfer> =
        uploadTransfers.filterNot { isOwnedByActiveSession(it.accountKey) }

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
            /*
             * The round bookkeeping is updated before the UI is refreshed, so the ring and the transfer
             * rows of this pass are computed from the same facts.
             *
             * A downloaded or failed attempt is over, so its enqueue grace is dropped as well: an
             * attempt that fails right after it started must not keep looking active for the rest of the
             * grace window. A cancelled attempt keeps its marker, because that is what a resume relies
             * on until its replacement work item shows up.
             */
            val transferId = DownloadWork.transferId(info)
            when (info.outputData.getString(DownloadWorker.KEY_RESULT_OUTCOME)) {
                DownloadWorker.OUTCOME_SUCCESS -> {
                    if (transferId != null) {
                        // A finished download keeps its place in the running round at its full size.
                        downloadRoundProgress.onTaskCompleted(transferId)
                        recentlyEnqueuedTransfers.remove(transferId)
                    }
                    loadDownloadedFiles()
                }

                DownloadWorker.OUTCOME_FAILED -> {
                    if (transferId != null) {
                        // A failure is not a pause: it must never be reported as "everything is paused".
                        downloadRoundProgress.onTaskFailed(transferId)
                        recentlyEnqueuedTransfers.remove(transferId)
                    }
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
     * Documents of pending work items, of persisted transfers (including transfers owned by another
     * account) and of the current upload candidates are kept, so a paused or failed file stays
     * resumable. Returns the release failures of this pass; the cleanup itself is idempotent.
     */
    private fun releaseUnusedUploadPermissions(): List<Throwable> {
        val neededUris = buildSet {
            _uiState.value.disk.uploadCandidates.forEach { candidate -> add(candidate.uriString) }
            // A metadata-pending upload no longer reads its document: only its recovery record stays.
            addAll(UploadTransfer.sourceDocumentsInUse(uploadTransfers))
            uploadWorkInfos.filterNot { it.state.isFinished }
                .forEach { info -> UploadWork.sourceUri(info)?.let(::add) }
        }
        return UploadUriPermissionManager.persistedReadPermissionUris(getApplication())
            .filterNot { uriString -> uriString in neededUris }
            .mapNotNull { uriString -> releaseUploadCandidatePermission(uriString) }
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

        val displayName =
            info.outputData.getString(UploadWorker.KEY_RESULT_DISPLAY_NAME).orEmpty()

        val uriString =
            info.outputData.getString(UploadWorker.KEY_RESULT_URI).orEmpty()

        val candidate = _uiState.value.disk.uploadCandidates
            .firstOrNull {
                uriString.isNotEmpty() &&
                        it.uriString == uriString
            }

        val outcome =
            info.outputData.getString(UploadWorker.KEY_RESULT_OUTCOME)

        if (outcome == UploadWorker.OUTCOME_CANCELED) {
            return
        }

        val errorKind =
            info.outputData.getString(UploadWorker.KEY_RESULT_ERROR_KIND)

        /*
         * The Worker has already discarded this transfer:
         *   - best-effort AbortMultipartUpload
         *   - checkpoint deletion
         *   - TransferStore removal
         *
         * Complete the client-side cleanup by removing the picker item and releasing its SAF grant.
         */
        if (
            outcome == UploadWorker.OUTCOME_FAILED &&
            errorKind == UploadWorker.ERROR_KIND_SOURCE_UNAVAILABLE
        ) {
            val permissionFailures = mutableListOf<Throwable>()

            when {
                candidate != null -> {
                    removeUploadCandidateInternal(candidate)
                        ?.let(permissionFailures::add)
                }

                uriString.isNotBlank() -> {
                    releaseUploadCandidatePermission(uriString)
                        ?.let(permissionFailures::add)
                }
            }

            permissionFailures += releaseUnusedUploadPermissions()

            if (permissionFailures.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Unable to release one or more upload source permissions",
                    permissionFailures.first(),
                )
            }

            sendErrorMessage(
                uploadSourceUnavailableMessage(displayName)
            )
            return
        }

        val succeeded =
            info.state == WorkInfo.State.SUCCEEDED &&
                    outcome == UploadWorker.OUTCOME_SUCCESS

        if (succeeded) {
            val releaseFailure = if (candidate != null) {
                removeUploadCandidateInternal(candidate)
            } else if (uriString.isNotBlank()) {
                releaseUploadCandidatePermission(uriString)
            } else {
                null
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

        if (
            info.state != WorkInfo.State.SUCCEEDED &&
            info.state != WorkInfo.State.FAILED
        ) {
            return
        }

        if (
            candidate == null &&
            uriString.isNotEmpty() &&
            info.state == WorkInfo.State.FAILED
        ) {
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

    private fun uploadSourceUnavailableMessage(fileName: String): String =
        when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN -> {
                if (fileName.isBlank()) {
                    "本地源文件已被删除或无法访问，未完成上传已清理"
                } else {
                    "本地文件“$fileName”已被删除或无法访问，未完成上传已清理"
                }
            }

            AppLanguage.EN_US -> {
                if (fileName.isBlank()) {
                    "The local source file is missing or inaccessible. The unfinished upload was cleaned up."
                } else {
                    "The local file \"$fileName\" is missing or inaccessible. The unfinished upload was cleaned up."
                }
            }
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
            // The resumable task is preserved and must be cancelled explicitly; retrying would only
            // ask the backend for the same moved target again.
            UploadWorker.ERROR_KIND_TARGET_CHANGED -> strings.transfers.uploadTargetChanged
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
    private fun startDownload(
        fileId: Long,
        fileName: String,
    ) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val session = activeSession() ?: return@launch

            /*
             * Repeated taps on the same cloud file while it already has an unfinished transfer do not
             * create another local destination.
             */
            val existing = downloadTransfers.firstOrNull {
                it.fileId == fileId &&
                        isOwnedByActiveSession(it.accountKey)
            }

            if (existing != null) {
                if (existing.phase == DownloadPhase.PAUSED) {
                    resumeDownload(existing.transferId)
                }
                return@launch
            }

            setTransferActionBusy(true)

            var transfer: DownloadTransfer? = null

            try {
                val reservedNames = downloadTransfers
                    .asSequence()
                    .filter { isOwnedByActiveSession(it.accountKey) }
                    .map { it.fileName }
                    .toSet()

                transfer = withContext(Dispatchers.IO) {
                    val destination =
                        transferRepository.createPendingDownloadDestination(
                            fileName = fileName,
                            reservedNames = reservedNames,
                        )

                    DownloadTransfer(
                        transferId = UUID.randomUUID().toString(),
                        accountKey = session.username,
                        fileId = fileId,
                        // Persist the actual local name, including "(1)" if needed.
                        fileName = destination.displayName,
                        destinationUri = destination.uri.toString(),
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        etag = "",
                        language = _uiState.value.settings.language.code,
                        phase = DownloadPhase.TRANSFERRING,
                        notice = TransferNotice.NONE,
                        createdAt = System.currentTimeMillis(),
                    )
                }

                markRecentlyEnqueued(listOf(transfer.transferId))

                try {
                    transferStore.addDownload(transfer)
                } catch (throwable: Throwable) {
                    withContext(Dispatchers.IO) {
                        transferRepository.deleteDownloadDestination(
                            transfer.destinationUri
                        )
                    }
                    throw throwable
                }

                downloadTransfers =
                    downloadTransfers.filterNot { current ->
                        current.transferId == transfer.transferId
                    } + transfer
                refreshTransferUi()

                try {
                    DownloadWork.enqueue(
                        context = getApplication(),
                        accessToken = session.accessToken,
                        transfers = listOf(transfer),
                    )
                } catch (throwable: Throwable) {
                    transferStore.removeDownload(transfer.transferId)
                    downloadTransfers = downloadTransfers.filterNot {
                        it.transferId == transfer.transferId
                    }
                    refreshTransferUi()

                    withContext(Dispatchers.IO) {
                        transferRepository.deleteDownloadDestination(
                            transfer.destinationUri
                        )
                    }

                    throw throwable
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                sendThrowableMessage(throwable)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    /** Pauses a download without losing its partial bytes; the pending MediaStore item is kept. */
    fun pauseDownload(transferId: String) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val transfer = downloadTransfers
                .firstOrNull { it.transferId == transferId }
                ?: return@launch

            if (!isOwnedByActiveSession(transfer.accountKey)) {
                return@launch
            }

            if (transfer.phase != DownloadPhase.TRANSFERRING) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updated = transferStore.updateDownload(transferId) { current ->
                    if (current.phase == DownloadPhase.TRANSFERRING) {
                        current.copy(phase = DownloadPhase.PAUSED)
                    } else {
                        current
                    }
                } ?: return@launch

                downloadTransfers = downloadTransfers.map { current ->
                    if (current.transferId == transferId) {
                        updated
                    } else {
                        current
                    }
                }
                refreshTransferUi()

                awaitDownloadCancellation(
                    DownloadWork.cancelTransfer(
                        getApplication(),
                        transferId,
                    )
                )

                sendInfoMessage(strings().transfers.paused)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    /** Resumes a paused download. The worker always asks for a new presigned URL before continuing. */
    fun resumeDownload(transferId: String) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val transfer = downloadTransfers
                .firstOrNull { it.transferId == transferId }
                ?: return@launch

            val session = activeSession() ?: return@launch

            if (!isOwnedByActiveSession(transfer.accountKey)) {
                return@launch
            }

            if (transfer.phase != DownloadPhase.PAUSED) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updated = transferStore.updateDownload(transferId) { current ->
                    if (current.phase == DownloadPhase.PAUSED) {
                        current.copy(phase = DownloadPhase.TRANSFERRING)
                    } else {
                        current
                    }
                } ?: return@launch

                downloadTransfers = downloadTransfers.map { current ->
                    if (current.transferId == transferId) {
                        updated
                    } else {
                        current
                    }
                }
                refreshTransferUi()

                markRecentlyEnqueued(listOf(transferId))

                try {
                    DownloadWork.enqueue(
                        context = getApplication(),
                        accessToken = session.accessToken,
                        transfers = listOf(updated),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    val paused = transferStore.updateDownload(transferId) { current ->
                        if (current.phase == DownloadPhase.TRANSFERRING) {
                            current.copy(phase = DownloadPhase.PAUSED)
                        } else {
                            current
                        }
                    }

                    if (paused != null) {
                        downloadTransfers = downloadTransfers.map { current ->
                            if (current.transferId == transferId) {
                                paused
                            } else {
                                current
                            }
                        }
                    }

                    refreshTransferUi()
                    sendThrowableMessage(throwable)
                }
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    /** Destructive cancel: the unfinished MediaStore item and the transfer state are deleted. */
    fun cancelDownload(transferId: String) {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val transfer = downloadTransfers
                .firstOrNull { it.transferId == transferId }
                ?: return@launch

            if (!isOwnedByActiveSession(transfer.accountKey)) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                cancelDownloadInternal(transfer)
                sendInfoMessage(strings().transfers.transferCanceled)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    private suspend fun cancelDownloadInternal(transfer: DownloadTransfer) {
        /*
         * Stop the writer first. Unlike upload, there is no remote multipart state here and therefore no
         * reason to delete the persistent record before the worker has finished unwinding.
         *
         * If the process dies during this sequence, keeping the record is safer than leaving an
         * untracked pending MediaStore row.
         */
        awaitDownloadCancellation(
            DownloadWork.cancelTransfer(
                getApplication(),
                transfer.transferId,
            )
        )

        val removed =
            transferStore.removeDownload(transfer.transferId)
                ?: transfer

        // A destructive cancel removes the transfer and its partial file, so it also leaves the round.
        downloadRoundProgress.onTaskCancelled(transfer.transferId)

        downloadTransfers = downloadTransfers.filterNot {
            it.transferId == transfer.transferId
        }
        refreshTransferUi()

        withContext(Dispatchers.IO) {
            transferRepository.deleteDownloadDestination(
                removed.destinationUri
            )
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
            val session =
                activeSession()
                    ?: return@launch

            if (curPassword.isEmpty()) {
                sendWarningMessage(
                    requiredFieldsMessage()
                )
                return@launch
            }

            /*
             * Serialize account deletion with transfer actions.
             *
             * This closes the opposite race as well:
             * - deleteAccount starts first -> uploadSelectedFiles/resume sees transferActionBusy and stops;
             * - uploadSelectedFiles starts first -> it sets isUploading before its first suspension, so the
             *   unresolved-upload check below catches it.
             */
            if (_uiState.value.disk.transferActionBusy) {
                sendWarningMessage(
                    accountDeletionTransferBusyMessage()
                )
                return@launch
            }

            setTransferActionBusy(true)

            try {
                /*
                 * The backend deletes OSS objects by enumerating existing File rows.
                 *
                 * Any UploadTransfer is therefore unsafe during account deletion:
                 *
                 * TRANSFERRING:
                 *   CompleteMultipartUpload could succeed while account deletion is running.
                 *
                 * PAUSED:
                 *   Its unfinished multipart state still belongs to this account.
                 *
                 * METADATA_PENDING:
                 *   The complete OSS object exists but has no File row yet.
                 *
                 * The direct TransferStore read is authoritative and also covers records that have not yet
                 * reached the current UI mirror.
                 */
                if (
                    hasUnresolvedUploads(
                        session
                    )
                ) {
                    sendErrorMessage(
                        uploadBlocksAccountDeletionMessage()
                    )
                    return@launch
                }

                setAccountBusy(true)

                try {
                    backendService.deleteAccount(
                        accessToken =
                            session.accessToken,
                        curPassword =
                            curPassword,
                        language =
                            uiState.value.settings.language,
                    )

                    preferencesStore
                        .clearCredentials()

                    resetSessionAndContent()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    sendThrowableMessage(
                        throwable
                    )
                } finally {
                    setAccountBusy(false)
                }
            } finally {
                /*
                 * resetSessionAndContent() also resets DiskScreenState after successful deletion, so this is
                 * intentionally idempotent.
                 */
                setTransferActionBusy(false)
            }
        }
    }

    /**
     * True when account deletion could race any upload owned by this account.
     *
     * isUploading additionally covers the tiny startup window after the UI has committed to creating a
     * batch but before its UploadTransfer records have reached TransferStore.
     */
    private suspend fun hasUnresolvedUploads(
        session: SessionState,
    ): Boolean {
        if (_uiState.value.disk.isUploading) {
            return true
        }

        return transferStore
            .uploadsOnce()
            .any { transfer ->
                transfer.accountKey.isBlank() ||
                        transfer.accountKey ==
                        session.username
            }
    }

    /**
     * Only an explicit authentication rejection proves that the saved credential is no longer usable.
     *
     * Connectivity failures and server-side 5xx responses are transient and must never trigger
     * destructive transfer cleanup.
     */
    private fun Throwable.isAuthenticationFailure(): Boolean =
        this is NotesServiceException.Http &&
                (
                        statusCode == 401 ||
                                statusCode == 403
                        )

    private suspend fun bootstrap(
        savedUsername: String,
        savedAccessToken: String,
    ) {
        if (
            savedUsername.isBlank() ||
            savedAccessToken.isBlank()
        ) {
            _uiState.update {
                it.copy(
                    bootstrapping = false
                )
            }
            return
        }

        try {
            backendService.autoLogin(
                savedAccessToken
            )

            onLoginSucceeded(
                username = savedUsername,
                accessToken = savedAccessToken,
                welcomeBack = false,
            )
        } catch (throwable: Throwable) {
            if (
                throwable.isAuthenticationFailure()
            ) {
                /*
                 * The server explicitly rejected the saved credential.
                 *
                 * Remove all destructible state owned by that account. METADATA_PENDING recovery records
                 * are deliberately retained because their complete OSS objects cannot safely be discarded.
                 *
                 * Remote multipart abort is not attempted here because the credential was just proven
                 * invalid. Local state is still removed and abandoned incomplete OSS parts are left to the
                 * bucket lifecycle rule.
                 */
                preferencesStore.clearCredentials()

                resetSessionAndContent(
                    departingAccountOverride =
                        savedUsername,
                    attemptRemoteAbort = false,
                )
            } else {
                /*
                 * Network outage, timeout, 5xx, malformed temporary response, etc.
                 *
                 * Do NOT destroy credentials, checkpoints, partial downloads or transfer records merely
                 * because the server could not be reached during app startup.
                 */
                Log.w(
                    TAG,
                    "Auto-login temporarily unavailable; preserving local session recovery state",
                    throwable,
                )

                _uiState.update { state ->
                    state.copy(
                        bootstrapping = false,
                        session =
                            SessionState(),
                    )
                }
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
        // Transfers are projected per account: the records of the account that just signed in become
        // visible now, and another account's records stay hidden.
        refreshTransferUi()
        recoverMetadataPendingUploads()
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

    /**
     * Ends one account's local session and removes all destructible transfer state.
     *
     * [departingAccountOverride] is used during startup when a saved credential was rejected before a
     * SessionState could be established.
     *
     * [attemptRemoteAbort] must be false when the credential has already been proven invalid. Local
     * cleanup still proceeds; abandoned incomplete OSS parts are then handled by the bucket lifecycle.
     *
     * METADATA_PENDING upload records are always preserved as recovery state.
     */
    private suspend fun resetSessionAndContent(
        departingAccountOverride: String? = null,
        attemptRemoteAbort: Boolean = true,
    ) {
        if (sessionResetInProgress) {
            return
        }

        sessionResetInProgress = true

        try {
            val cacheFilesToDelete =
                _uiState.value.reading.activeCacheFiles

            val departingAccount =
                departingAccountOverride
                    ?.takeIf { it.isNotBlank() }
                    ?: _uiState.value.session.username

            /*
             * Capture the current in-memory token before SessionState is reset. This is useful for normal
             * local logout. During rejected auto-login cleanup attemptRemoteAbort is false.
             */
            val departingAccessToken =
                _uiState.value.session.accessToken
                    .takeIf { it.isNotBlank() }

            /*
             * Read from persistent storage rather than from the UI mirror, because startup cleanup may happen
             * before collectors have projected these records.
             */
            val uploadSnapshot =
                transferStore.uploadsOnce()
                    .filter { transfer ->
                        belongsToDepartingAccount(
                            transfer.accountKey,
                            departingAccount,
                        )
                    }

            val downloadSnapshot =
                transferStore.downloadsOnce()
                    .filter { transfer ->
                        belongsToDepartingAccount(
                            transfer.accountKey,
                            departingAccount,
                        )
                    }

            uploadWorkStates.clear()
            overwriteWarnedUploads.clear()
            handledUploadWorkIds.clear()
            handledDownloadWorkIds.clear()
            recentlyEnqueuedTransfers.clear()

            uploadBatchId = null
            uploadBatchTotalBytes = 0L
            uploadBatchWorkIds = emptySet()

            /*
             * Stop every departing WorkManager item first, including metadata-only uploads.
             *
             * The atomic DataStore phase check happens only after workers have been asked to stop, which
             * greatly narrows the completion race.
             */
            awaitWorkCancellations(
                UploadWork.cancelTransfers(
                    getApplication(),
                    uploadSnapshot.map(
                        UploadTransfer::transferId
                    ),
                )
            )

            downloadSnapshot.forEach { transfer ->
                awaitDownloadCancellation(
                    DownloadWork.cancelTransfer(
                        getApplication(),
                        transfer.transferId,
                    )
                )
            }

            /*
             * Re-check CURRENT upload phase inside DataStore.
             *
             * A transfer that changed to METADATA_PENDING while cancellation was racing is preserved rather
             * than accidentally removed.
             */
            val removedUploads =
                transferStore.removeCancellableUploads(
                    uploadSnapshot.map(
                        UploadTransfer::transferId
                    )
                )

            /*
             * Downloads have no equivalent metadata-only state. The returned list matters: a download that
             * completed and removed its record just before cancellation is now a completed user file and must
             * not be deleted as though it were still a temporary download.
             */
            val removedDownloads =
                transferStore.removeDownloads(
                    downloadSnapshot.map(
                        DownloadTransfer::transferId
                    )
                )

            /*
             * Reload the authoritative store after mutation. This retains foreign-account transfers and any
             * newly preserved METADATA_PENDING record.
             */
            uploadTransfers =
                transferStore.uploadsOnce()

            downloadTransfers =
                transferStore.downloadsOnce()

            val preservedMetadataUploads =
                uploadTransfers.filter { transfer ->
                    belongsToDepartingAccount(
                        transfer.accountKey,
                        departingAccount,
                    ) &&
                            transfer.phase ==
                            UploadPhase.METADATA_PENDING
                }

            withContext(Dispatchers.IO) {
                /*
                 * Normal logout may still have a usable token and can clean the remote multipart immediately.
                 *
                 * Rejected auto-login skips this because the token is known invalid. Local deletion remains
                 * authoritative and the incomplete-multipart lifecycle rule handles any remote leftovers.
                 */
                if (
                    attemptRemoteAbort &&
                    !departingAccessToken.isNullOrBlank()
                ) {
                    withTimeoutOrNull(
                        LOGOUT_ABORT_TIMEOUT_MILLIS
                    ) {
                        removedUploads.forEach { transfer ->
                            if (
                                transfer.uploadId.isBlank() ||
                                transfer.objectKey.isBlank()
                            ) {
                                return@forEach
                            }

                            try {
                                val ticket =
                                    backendService.requestOssSts(
                                        accessToken =
                                            departingAccessToken,
                                        pathString =
                                            transfer.pathString,
                                        filename =
                                            transfer.displayName,
                                        parentId =
                                            transfer.parentId,
                                        language =
                                            AppLanguage.fromCode(
                                                transfer.language
                                            ),
                                        usage =
                                            OSS_USAGE_SINGLE_FILE_UPLOAD,
                                    )

                                transferRepository.abortMultipartUpload(
                                    ticket = ticket,
                                    objectKey =
                                        transfer.objectKey,
                                    uploadId =
                                        transfer.uploadId,
                                    credentialProvider =
                                        TransferStsCredentialProvider(
                                            transfer =
                                                transfer,
                                            accessToken =
                                                departingAccessToken,
                                            backendService =
                                                backendService,
                                            firstTicket =
                                                ticket,
                                        ),
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (throwable: Throwable) {
                                Log.w(
                                    TAG,
                                    "Best-effort multipart cleanup failed while ending session for ${transfer.displayName}",
                                    throwable,
                                )
                            }
                        }
                    }
                }

                /*
                 * Local resumable state always disappears for transfers that were actually removed.
                 */
                removedUploads.forEach { transfer ->
                    transferRepository
                        .deleteCheckpointDirectory(
                            transfer.checkpointDir
                        )
                }

                /*
                 * A metadata-pending record no longer needs its OSS checkpoint. Keep only the tiny recovery
                 * record itself.
                 */
                preservedMetadataUploads.forEach { transfer ->
                    transferRepository
                        .deleteCheckpointDirectory(
                            transfer.checkpointDir
                        )
                }

                /*
                 * Delete only destinations whose persistent transfer records were actually removed.
                 *
                 * If a download completed just before cancellation and its worker already removed the record,
                 * its published local file is deliberately left untouched.
                 */
                removedDownloads.forEach { transfer ->
                    transferRepository
                        .deleteDownloadDestination(
                            transfer.destinationUri
                        )
                }
            }

            /*
             * The ring only ever describes the downloads of the session that is ending. Its round and
             * any coalesced projection still waiting to be published must not survive into the next one.
             */
            cancelPendingTransferUi()
            downloadRoundProgress.reset()

            /*
             * Reset every user-visible account/session state. Theme/language stay because they are device
             * preferences rather than authenticated account content.
             */
            _uiState.update { state ->
                state.copy(
                    bootstrapping = false,
                    accountBusy = false,
                    session = SessionState(),
                    currentTab = AppTab.ACCOUNT,
                    tabBackStack = emptyList(),
                    settingsSubPage =
                        SettingsSubPage.ROOT,
                    disk = DiskScreenState(),
                    reading =
                        ReadingScreenState(),
                )
            }

            /*
             * Work snapshots from the departed session must no longer keep SAF permissions alive.
             */
            uploadWorkInfos = emptyList()
            downloadWorkInfos = emptyList()

            /*
             * Source permissions of deleted transfers and picker candidates are now released. Foreign
             * transfers still present in uploadTransfers continue protecting their own source documents.
             */
            val releaseFailures =
                releaseUnusedUploadPermissions()

            cleanupPreviewCacheFiles(
                cacheFilesToDelete
            )

            previewRepository
                .clearAllPreviewCache()

            invalidateDiskRequests()

            if (releaseFailures.isNotEmpty()) {
                sendErrorMessage(
                    bulkReleaseUploadPermissionFailedMessage(
                        count =
                            releaseFailures.size,
                        throwable =
                            releaseFailures.first(),
                    )
                )
            }
        } finally {
            /*
             * Re-enable normal reconciliation even when one of the best-effort cleanup operations throws.
             */
            sessionResetInProgress = false
        }
    }

    /**
     * True when a record belongs to the session that is being ended.
     *
     * Ownerless records come from an older app version and are cleaned up together with the account
     * that is currently active, because nothing else can attribute or recover them.
     */
    private fun belongsToDepartingAccount(accountKey: String, departingAccount: String): Boolean =
        accountKey.isBlank() || accountKey == departingAccount

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
        // A persisted read permission is kept only while some upload still has to read the document,
        // while a pending work item needs it, or while it is still a selected candidate. A
        // metadata-pending transfer already uploaded everything and does not count as a consumer.
        val stillNeeded = uriString in UploadTransfer.sourceDocumentsInUse(uploadTransfers) ||
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

        // Recoverable transfers and uploads restored by WorkManager still need their document. A
        // metadata-pending record is recovery state only: it no longer consumes the SAF permission.
        if (UploadTransfer.sourceDocumentsInUse(transfers).isNotEmpty()) return
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

    /**
     * Reconciles pending MediaStore downloads against the persistent DownloadTransfer store.
     *
     * A referenced pending destination is resumable and must stay. An unreferenced pending destination
     * cannot be resumed by any worker and is safe to remove.
     */
    private suspend fun cleanupStaleDownloadDestinations() {
        val referencedDestinationUris =
            transferStore.downloadsOnce()
                .mapTo(
                    mutableSetOf(),
                    DownloadTransfer::destinationUri,
                )

        val failureCount =
            withContext(Dispatchers.IO) {
                transferRepository
                    .cleanupOrphanPendingDownloadDestinations(
                        referencedDestinationUris
                    )
            }

        if (failureCount > 0) {
            /*
             * Do not bother the user during startup for a hidden temporary-file cleanup failure.
             * The same rows remain pending and are retried automatically next launch.
             */
            Log.w(
                TAG,
                "Unable to clean $failureCount orphan pending download destination(s)",
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

    private fun uploadBlocksAccountDeletionMessage(): String =
        when (
            _uiState.value.settings.language
        ) {
            AppLanguage.ZH_CN ->
                "仍有未完成的上传任务，请先完成或删除这些上传任务后再删除账户"

            AppLanguage.EN_US ->
                "Unfinished uploads remain. Finish or delete them before deleting this account."
        }

    private fun accountDeletionTransferBusyMessage(): String =
        when (
            _uiState.value.settings.language
        ) {
            AppLanguage.ZH_CN ->
                "当前传输操作尚未结束，请稍后再删除账户"

            AppLanguage.EN_US ->
                "A transfer operation is still in progress. Try deleting the account again when it finishes."
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

    private fun uploadDeleteDeferredCleanupMessage(
        count: Int,
    ): String =
        when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN ->
                "已删除上传任务并清理本地断点，但有 $count 个 OSS 分片任务未能立即清理，将由存储生命周期规则继续兜底。"

            AppLanguage.EN_US ->
                "$count upload(s) were removed locally, but their OSS multipart data could not be cleaned immediately and will be handled by the storage lifecycle rule."
        }

    private fun downloadDeleteCleanupFailedMessage(
        count: Int,
    ): String =
        when (_uiState.value.settings.language) {
            AppLanguage.ZH_CN ->
                "下载任务已停止，但有 $count 个本地临时文件未能确认删除，可稍后再次清理。"

            AppLanguage.EN_US ->
                "The downloads were stopped, but $count local temporary file(s) could not be confirmed as deleted."
        }

    /**
     * Deletes every unfinished download owned by the active account.
     *
     * Each WorkManager writer is stopped first, then persistent state is removed, and finally every
     * pending MediaStore destination is deleted.
     */
    fun deleteAllDownloads() {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val targets =
                downloadTransfers.filter { transfer ->
                    isOwnedByActiveSession(
                        transfer.accountKey
                    )
                }

            if (targets.isEmpty()) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val targetIds =
                    targets.mapTo(
                        mutableSetOf(),
                        DownloadTransfer::transferId,
                    )

                /*
                 * Issue every cancellation before waiting for any one of them. This prevents a slow worker
                 * from delaying cancellation of the remaining downloads.
                 */
                val cancellationOperations =
                    targets.map { transfer ->
                        DownloadWork.cancelTransfer(
                            getApplication(),
                            transfer.transferId,
                        )
                    }

                withContext(Dispatchers.IO) {
                    cancellationOperations.forEach { operation ->
                        DownloadWork.awaitCancellation(
                            operation,
                            WORK_CANCELLATION_TIMEOUT_MILLIS,
                        )
                    }
                }

                /*
                 * Now no normal worker should still own the destinations. Batch-remove the persistent
                 * records atomically so process death cannot leave only half the list deleted.
                 */
                transferStore.removeDownloads(
                    targetIds
                )

                targetIds.forEach(downloadRoundProgress::onTaskCancelled)

                downloadTransfers =
                    downloadTransfers.filterNot { transfer ->
                        transfer.transferId in targetIds
                    }

                targetIds.forEach { transferId ->
                    recentlyEnqueuedTransfers.remove(transferId)
                }

                refreshTransferUi()

                /*
                 * Use the snapshot captured before cancellation rather than the records returned by the
                 * store. A very fast worker may have completed and removed its own record during the
                 * cancellation race; Delete all still means that destination should not remain locally.
                 */
                val cleanupFailures =
                    withContext(Dispatchers.IO) {
                        targets.filterNot { transfer ->
                            transferRepository.deleteDownloadDestination(
                                transfer.destinationUri
                            )
                        }
                    }

                if (cleanupFailures.isNotEmpty()) {
                    sendWarningMessage(
                        downloadDeleteCleanupFailedMessage(
                            cleanupFailures.size
                        )
                    )
                } else {
                    sendInfoMessage(
                        strings().transfers.transferCanceled
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(
                    TAG,
                    "Unable to delete all downloads",
                    throwable,
                )
                sendThrowableMessage(throwable)
            } finally {
                setTransferActionBusy(false)
            }
        }
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

        /**
         * Coalescing window for plain byte-progress publishes. State transitions are published
         * immediately and never wait for this window.
         */
        const val DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MILLIS = 80L

        /** A destructive cancel is best effort; the UI must never wait on it for long. */
        const val CANCEL_ABORT_TIMEOUT_MILLIS = 15_000L

        /** Logout waits for aborts only briefly: local state is cleaned either way. */
        const val LOGOUT_ABORT_TIMEOUT_MILLIS = 10_000L

        /** Lets a stopped SDK task release its checkpoint file before the directory is deleted. */
        const val CHECKPOINT_RELEASE_DELAY_MILLIS = 300L

        /** Upper bound for waiting until WorkManager persisted a cancellation. */
        const val WORK_CANCELLATION_TIMEOUT_MILLIS = 5_000L
    }

    fun pauseAllUploads() {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val targets =
                ownedUploadTransfers().filter {
                    it.phase == UploadPhase.TRANSFERRING
                }

            if (targets.isEmpty()) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updatedById = mutableMapOf<String, UploadTransfer>()

                targets.forEach { transfer ->
                    transferStore.updateUpload(transfer.transferId) { current ->
                        if (current.phase == UploadPhase.TRANSFERRING) {
                            current.copy(
                                phase = UploadPhase.PAUSED
                            )
                        } else {
                            current
                        }
                    }?.let { updated ->
                        updatedById[updated.transferId] = updated
                    }
                }

                uploadTransfers =
                    uploadTransfers.map { current ->
                        updatedById[current.transferId] ?: current
                    }

                refreshTransferUi()

                awaitWorkCancellations(
                    UploadWork.cancelTransfers(
                        getApplication(),
                        targets.map(UploadTransfer::transferId),
                    )
                )

                sendInfoMessage(strings().transfers.paused)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    fun resumeAllUploads() {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val session = activeSession() ?: return@launch

            val targets =
                ownedUploadTransfers().filter {
                    it.phase == UploadPhase.PAUSED
                }

            if (targets.isEmpty()) {
                return@launch
            }

            setTransferActionBusy(true)

            val resumed = mutableListOf<UploadTransfer>()

            try {
                targets.forEach { transfer ->
                    transferStore.updateUpload(transfer.transferId) { current ->
                        if (current.phase == UploadPhase.PAUSED) {
                            current.copy(
                                phase = UploadPhase.TRANSFERRING
                            )
                        } else {
                            current
                        }
                    }?.let(resumed::add)
                }

                val resumedById =
                    resumed.associateBy(UploadTransfer::transferId)

                uploadTransfers =
                    uploadTransfers.map { current ->
                        resumedById[current.transferId] ?: current
                    }

                refreshTransferUi()

                if (resumed.isEmpty()) {
                    return@launch
                }

                uploadBatchId = resumed.first().batchId
                uploadBatchTotalBytes = resumed.first().batchTotalBytes

                markRecentlyEnqueued(
                    resumed.map(UploadTransfer::transferId)
                )

                val batch = UploadWork.enqueue(
                    context = getApplication(),
                    accessToken = session.accessToken,
                    transfers = resumed,
                )

                uploadBatchWorkIds =
                    uploadBatchWorkIds + batch?.workIds.orEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val ids =
                    resumed.mapTo(
                        mutableSetOf(),
                        UploadTransfer::transferId,
                    )

                awaitWorkCancellations(
                    UploadWork.cancelTransfers(
                        getApplication(),
                        ids,
                    )
                )

                val pausedById = mutableMapOf<String, UploadTransfer>()

                ids.forEach { transferId ->
                    transferStore.updateUpload(transferId) { current ->
                        if (current.phase == UploadPhase.TRANSFERRING) {
                            current.copy(
                                phase = UploadPhase.PAUSED
                            )
                        } else {
                            current
                        }
                    }?.let { paused ->
                        pausedById[transferId] = paused
                    }
                }

                uploadTransfers =
                    uploadTransfers.map { current ->
                        pausedById[current.transferId] ?: current
                    }

                refreshTransferUi()
                sendThrowableMessage(throwable)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    fun pauseAllDownloads() {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val targets =
                downloadTransfers.filter { transfer ->
                    isOwnedByActiveSession(transfer.accountKey) &&
                            transfer.phase == DownloadPhase.TRANSFERRING
                }

            if (targets.isEmpty()) {
                return@launch
            }

            setTransferActionBusy(true)

            try {
                val updatedById =
                    mutableMapOf<String, DownloadTransfer>()

                targets.forEach { transfer ->
                    transferStore.updateDownload(transfer.transferId) { current ->
                        if (current.phase == DownloadPhase.TRANSFERRING) {
                            current.copy(
                                phase = DownloadPhase.PAUSED
                            )
                        } else {
                            current
                        }
                    }?.let { updated ->
                        updatedById[updated.transferId] = updated
                    }
                }

                downloadTransfers =
                    downloadTransfers.map { current ->
                        updatedById[current.transferId] ?: current
                    }

                refreshTransferUi()

                targets.forEach { transfer ->
                    awaitDownloadCancellation(
                        DownloadWork.cancelTransfer(
                            getApplication(),
                            transfer.transferId,
                        )
                    )
                }

                sendInfoMessage(strings().transfers.paused)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

    fun resumeAllDownloads() {
        viewModelScope.launch {
            if (_uiState.value.disk.transferActionBusy) {
                return@launch
            }

            val session = activeSession() ?: return@launch

            val targets =
                downloadTransfers.filter { transfer ->
                    isOwnedByActiveSession(transfer.accountKey) &&
                            transfer.phase == DownloadPhase.PAUSED
                }

            if (targets.isEmpty()) {
                return@launch
            }

            setTransferActionBusy(true)

            val resumed = mutableListOf<DownloadTransfer>()

            try {
                targets.forEach { transfer ->
                    transferStore.updateDownload(transfer.transferId) { current ->
                        if (current.phase == DownloadPhase.PAUSED) {
                            current.copy(
                                phase = DownloadPhase.TRANSFERRING
                            )
                        } else {
                            current
                        }
                    }?.let(resumed::add)
                }

                val resumedById =
                    resumed.associateBy(DownloadTransfer::transferId)

                downloadTransfers =
                    downloadTransfers.map { current ->
                        resumedById[current.transferId] ?: current
                    }

                refreshTransferUi()

                if (resumed.isEmpty()) {
                    return@launch
                }

                markRecentlyEnqueued(
                    resumed.map(DownloadTransfer::transferId)
                )

                DownloadWork.enqueue(
                    context = getApplication(),
                    accessToken = session.accessToken,
                    transfers = resumed,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val ids =
                    resumed.mapTo(
                        mutableSetOf(),
                        DownloadTransfer::transferId,
                    )

                val pausedById =
                    mutableMapOf<String, DownloadTransfer>()

                ids.forEach { transferId ->
                    runCatching {
                        awaitDownloadCancellation(
                            DownloadWork.cancelTransfer(
                                getApplication(),
                                transferId,
                            )
                        )
                    }

                    transferStore.updateDownload(transferId) { current ->
                        if (current.phase == DownloadPhase.TRANSFERRING) {
                            current.copy(
                                phase = DownloadPhase.PAUSED
                            )
                        } else {
                            current
                        }
                    }?.let { paused ->
                        pausedById[transferId] = paused
                    }
                }

                downloadTransfers =
                    downloadTransfers.map { current ->
                        pausedById[current.transferId] ?: current
                    }

                refreshTransferUi()
                sendThrowableMessage(throwable)
            } finally {
                setTransferActionBusy(false)
            }
        }
    }

}
