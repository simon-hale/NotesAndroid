package com.notes.notes.core

import androidx.compose.runtime.Immutable

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(raw: String?): ThemeMode = when (raw) {
            SYSTEM.storageValue -> SYSTEM
            DARK.storageValue -> DARK
            LIGHT.storageValue -> LIGHT
            else -> SYSTEM
        }
    }

    fun resolveIsDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }
}

enum class ThemePalette(val storageValue: String) {
    BLUE("blue"),
    EMERALD("emerald"),
    AMBER("amber"),
    ROSE("rose"),
    SAGE("sage"),
    ALMOND("almond");

    companion object {
        fun fromStorage(raw: String?): ThemePalette = entries.firstOrNull { it.storageValue == raw } ?: BLUE
    }
}

enum class AppTab {
    DISK,
    READING,
    ACCOUNT,
}

enum class SettingsSubPage {
    ROOT,
    ACCOUNT,
}

enum class SortKey {
    NAME,
    CREATED,
    UPDATED,
}

enum class SortDirection {
    ASC,
    DESC,
}

@Immutable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.BLUE,
)

@Immutable
data class SessionState(
    val username: String = "",
    val accessToken: String = "",
    val isLoggedIn: Boolean = false,
)

@Immutable
data class AppSettingsState(
    val language: AppLanguage = AppLanguage.ZH_CN,
    val theme: ThemeSettings = ThemeSettings(),
)

@Immutable
data class PathSegment(
    val level: Int,
    val id: Long,
    val name: String,
)

@Immutable
data class DirectoryEntry(
    val id: Long,
    val name: String,
)

@Immutable
data class FileEntry(
    val id: Long,
    val name: String,
    val type: String,
    val creationTime: String,
    val lastModifiedTime: String,
)

@Immutable
data class DownloadedFileEntry(
    val id: Long,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String,
)

@Immutable
data class UploadCandidate(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
)

@Immutable
data class SelectedFile(
    val id: Long,
    val name: String,
)

@Immutable
data class DiskScreenState(
    val isLoading: Boolean = false,
    val paths: List<PathSegment> = emptyList(),
    val directories: List<DirectoryEntry> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val downloadedFiles: List<DownloadedFileEntry> = emptyList(),
    val isLoadingDownloadedFiles: Boolean = false,
    val downloadedFilesError: String = "",
    val directorySortDirection: SortDirection = SortDirection.ASC,
    val sortKey: SortKey = SortKey.NAME,
    val sortDirection: SortDirection = SortDirection.ASC,
    val statusMessage: String = "",
    val errorMessage: String = "",
    val uploadCandidates: List<UploadCandidate> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
)

@Immutable
sealed interface PreviewContent {
    data object Empty : PreviewContent
    data class Html(
        val title: String,
        val html: String,
        val style: HtmlPreviewStyle,
    ) : PreviewContent

    data class Pdf(
        val title: String,
        val filePath: String,
        val pageCount: Int,
    ) : PreviewContent

    data class Error(
        val title: String,
        val message: String,
    ) : PreviewContent
}

enum class HtmlPreviewStyle {
    MARKDOWN,
    DOCUMENT,
}

enum class MessageTone {
    SUCCESS,
    ERROR,
    WARNING,
    INFO,
}

@Immutable
data class UiMessage(
    val message: String,
    val tone: MessageTone = MessageTone.INFO,
)

@Immutable
data class ReadingScreenState(
    val selectedFile: SelectedFile? = null,
    val displayedFile: SelectedFile? = null,
    val isRefreshing: Boolean = false,
    val isBottomBarVisible: Boolean = true,
    val content: PreviewContent = PreviewContent.Empty,
    val activeCacheFiles: List<String> = emptyList(),
)

@Immutable
data class NotesUiState(
    val bootstrapping: Boolean = true,
    val accountBusy: Boolean = false,
    val settings: AppSettingsState = AppSettingsState(),
    val session: SessionState = SessionState(),
    val currentTab: AppTab = AppTab.DISK,
    val tabBackStack: List<AppTab> = emptyList(),
    val settingsSubPage: SettingsSubPage = SettingsSubPage.ROOT,
    val disk: DiskScreenState = DiskScreenState(),
    val reading: ReadingScreenState = ReadingScreenState(),
)
