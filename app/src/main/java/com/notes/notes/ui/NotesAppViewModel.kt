package com.notes.notes.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.AppSettingsState
import com.notes.notes.core.AppTab
import com.notes.notes.core.DirectoryEntry
import com.notes.notes.core.DiskScreenState
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
import com.notes.notes.core.UiMessage
import com.notes.notes.core.UploadCandidate
import com.notes.notes.core.stringsFor
import com.notes.notes.data.AppPreferencesStore
import com.notes.notes.data.DirectoryListing
import com.notes.notes.data.FileTransferRepository
import com.notes.notes.data.NotesBackendService
import com.notes.notes.data.NotesServiceException
import com.notes.notes.data.PreviewRepository
import com.notes.notes.data.UploadUriPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotesAppViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesStore = AppPreferencesStore(application)
    private val backendService = NotesBackendService()
    private val previewRepository = PreviewRepository(application, backendService)
    private val transferRepository = FileTransferRepository(application)

    private val _uiState = MutableStateFlow(
        NotesUiState(
            settings = AppSettingsState(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val messageChannel = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var latestDiskRequestToken = 0L

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

    private fun removeUploadCandidateInternal(candidate: UploadCandidate): Throwable? {
        val releaseFailure = releaseUploadCandidatePermission(candidate.uriString)
        _uiState.update { state ->
            state.copy(
                disk = state.disk.copy(
                    uploadCandidates = state.disk.uploadCandidates.filterNot { it.uriString == candidate.uriString }
                )
            )
        }
        return releaseFailure
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
                    username = session.username,
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
                    username = session.username,
                    parentId = parent.id,
                    fileId = file.id,
                    newName = trimmed,
                    language = uiState.value.settings.language,
                )
            }.onSuccess {
                refreshCurrentDirectory()
                _uiState.update { state ->
                    val reading = state.reading
                    state.copy(
                        reading = reading.copy(
                            selectedFile = reading.selectedFile?.takeIf { it.id == file.id }?.copy(name = trimmed)
                                ?: reading.selectedFile,
                            displayedFile = reading.displayedFile?.takeIf { it.id == file.id }?.copy(name = trimmed)
                                ?: reading.displayedFile,
                        )
                    )
                }
                sendSuccessMessage(strings.fileDisk.renamed)
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
                    username = session.username,
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
            val session = activeSession() ?: return@launch
            val path = currentPath() ?: return@launch
            val candidates = _uiState.value.disk.uploadCandidates
            if (candidates.isEmpty()) {
                sendWarningMessage(strings.fileDisk.noFileSelected)
                return@launch
            }
            val pathString = _uiState.value.disk.paths.joinToString(separator = "") { "${it.name}/" }
            _uiState.update { it.copy(disk = it.disk.copy(isUploading = true, uploadProgress = 0f)) }
            val totalFiles = candidates.size.coerceAtLeast(1)
            candidates.forEachIndexed { index, candidate ->
                val fileName = candidate.displayName
                runCatching {
                    val uri = Uri.parse(candidate.uriString)
                    val tempFile = transferRepository.copyUriToTemporaryFile(uri, candidate.displayName)
                    try {
                        val sts = backendService.requestOssSts(
                            accessToken = session.accessToken,
                            username = session.username,
                            pathString = pathString,
                            filename = fileName,
                            parentId = path.id,
                            language = uiState.value.settings.language,
                        )
                        if (sts.overwriteSameName) {
                            sendWarningMessage(strings.fileDisk.overwriteSameName)
                        }
                        transferRepository.uploadToOss(sts, tempFile) { current, total ->
                            val fileProgress = if (total <= 0) 0f else current.toFloat() / total.toFloat()
                            val overall = (index + fileProgress) / totalFiles.toFloat()
                            _uiState.update { state ->
                                state.copy(disk = state.disk.copy(uploadProgress = overall))
                            }
                        }
                        backendService.insertFileInfo(
                            accessToken = session.accessToken,
                            username = session.username,
                            pathString = pathString,
                            filename = fileName,
                            parentId = path.id,
                            language = uiState.value.settings.language,
                        )
                    } finally {
                        tempFile.delete()
                    }
                }.onSuccess {
                    val releaseFailure = removeUploadCandidateInternal(candidate)
                    if (releaseFailure == null) {
                        sendSuccessMessage(
                            strings.format(
                                strings.fileDisk.uploadSuccessTemplate,
                                _uiState.value.settings.language.asLocale(),
                                fileName,
                            )
                        )
                    } else {
                        sendErrorMessage(
                            uploadSucceededButReleaseFailedMessage(
                                fileName = fileName,
                                throwable = releaseFailure,
                            )
                        )
                    }
                }.onFailure { throwable ->
                    sendErrorMessage(
                        strings.format(
                            strings.fileDisk.uploadFailedTemplate,
                            _uiState.value.settings.language.asLocale(),
                            fileName,
                        )
                    )
                    sendThrowableMessage(throwable)
                }
            }
            _uiState.update {
                it.copy(disk = it.disk.copy(isUploading = false, uploadProgress = 0f))
            }
            refreshCurrentDirectory()
        }
    }

    fun downloadFile(file: FileEntry) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            runCatching {
                val descriptor = backendService.getFilePreview(
                    accessToken = session.accessToken,
                    username = session.username,
                    fileId = file.id,
                    language = uiState.value.settings.language,
                )
                emitMessage(strings().fileDisk.downloadStarted, MessageTone.INFO)
                transferRepository.downloadToDownloads(descriptor.url, file.name)
                emitMessage(strings().fileDisk.downloadCompleted, MessageTone.SUCCESS)
            }.onSuccess {
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
        }
    }

    fun downloadSelectedReadingFile() {
        viewModelScope.launch {
            val targetFile = _uiState.value.reading.displayedFile ?: _uiState.value.reading.selectedFile
            if (targetFile == null) {
                sendWarningMessage(strings().reading.selectFileFirst)
                return@launch
            }
            val session = activeSession() ?: return@launch
            runCatching {
                val descriptor = backendService.getFilePreview(
                    accessToken = session.accessToken,
                    username = session.username,
                    fileId = targetFile.id,
                    language = uiState.value.settings.language,
                )
                emitMessage(strings().fileDisk.downloadStarted, MessageTone.INFO)
                transferRepository.downloadToDownloads(descriptor.url, targetFile.name)
                emitMessage(strings().fileDisk.downloadCompleted, MessageTone.SUCCESS)
            }.onSuccess {
            }.onFailure { throwable ->
                sendThrowableMessage(throwable)
            }
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
                    username = session.username,
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
            if (normalizedUsername.isBlank() || password.isBlank()) {
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
            if (normalizedUsername.isBlank() || password.isBlank() || confirmedPassword.isBlank()) {
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

    fun changePassword(newPassword: String, confirmedPassword: String) {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            if (newPassword.isBlank() || confirmedPassword.isBlank()) {
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
                    username = session.username,
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

    fun deleteAccount() {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            setAccountBusy(true)
            runCatching {
                backendService.deleteAccount(
                    accessToken = session.accessToken,
                    username = session.username,
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
                    username = session.username,
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
                    username = session.username,
                    parentId = target.directoryId,
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
        val releaseFailures = releaseUploadCandidatePermissions(_uiState.value.disk.uploadCandidates)
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
        return UploadUriPermissionManager.releaseReadPermission(
            context = getApplication(),
            uri = Uri.parse(uriString),
        )
    }

    private fun cleanupStaleUploadUriPermissions() {
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
}
