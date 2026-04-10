package com.notes.notes.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppTab
import com.notes.notes.core.HtmlPreviewStyle
import com.notes.notes.core.NotesUiState
import com.notes.notes.core.PreviewContent
import com.notes.notes.core.ThemeMode
import com.notes.notes.core.stringsFor
import com.notes.notes.ui.NotesAppViewModel
import com.notes.notes.ui.components.ActionChip
import com.notes.notes.ui.components.HtmlPreviewView
import com.notes.notes.ui.components.LoginReminderCard
import com.notes.notes.ui.components.PdfPreviewView
import com.notes.notes.ui.components.ScreenHeader
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Composable
fun ReadingScreen(uiState: NotesUiState, viewModel: NotesAppViewModel) {
    val strings = stringsFor(uiState.settings.language)
    val displayedName = uiState.reading.displayedFile?.name
    val selectedName = uiState.reading.selectedFile?.name
    val readingTitle = displayedName ?: selectedName
    val systemInDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = uiState.settings.theme.mode.resolveIsDark(systemInDarkTheme)
    val extraColors = LocalNotesExtraColors.current
    val hasLoadedPreview = uiState.reading.content is PreviewContent.Html || uiState.reading.content is PreviewContent.Pdf
    val previewSurfaceColor = when (val content = uiState.reading.content) {
        is PreviewContent.Html -> {
            if (content.style == HtmlPreviewStyle.MARKDOWN && isDarkTheme) {
                Color(0xFF0B1118)
            } else {
                Color.White
            }
        }

        is PreviewContent.Pdf -> Color.White
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = strings.nav.read,
            subtitle = readingTitle,
            scrollableSubtitle = true,
            trailing = {
                ActionChip(icon = Icons.Outlined.Download, onClick = viewModel::downloadSelectedReadingFile)
                ActionChip(icon = Icons.Outlined.Refresh, onClick = { viewModel.refreshReadingPreview(isDarkTheme) })
                ActionChip(
                    icon = if (uiState.reading.isBottomBarVisible) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    onClick = viewModel::toggleReadingBottomBar,
                )
                ActionChip(icon = Icons.Outlined.Home, onClick = { viewModel.setCurrentTab(AppTab.DISK) })
            },
        )

        if (!uiState.session.isLoggedIn) {
            LoginReminderCard(
                message = strings.auth.loginFirst,
                actionLabel = strings.common.login,
                onAction = { viewModel.setCurrentTab(AppTab.ACCOUNT) },
            )
            return
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = if (hasLoadedPreview) extraColors.panelTop else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extraColors.borderStrong),
            shadowElevation = 0.dp,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize(),
                shape = RoundedCornerShape(22.dp),
                color = previewSurfaceColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        uiState.reading.isRefreshing -> {
                            PreviewStatus(
                                title = strings.reading.loadingPreview,
                                body = strings.reading.refreshHint,
                                loading = true,
                                isDarkTheme = isDarkTheme,
                            )
                        }

                        uiState.reading.content is PreviewContent.Empty -> {
                            PreviewStatus(
                                title = readingTitle ?: strings.reading.emptyState,
                                body = if (selectedName == null) {
                                    strings.reading.selectFileFirst
                                } else {
                                    strings.reading.refreshToLoad
                                },
                                isDarkTheme = isDarkTheme,
                            )
                        }

                        uiState.reading.content is PreviewContent.Error -> {
                            val content = uiState.reading.content as PreviewContent.Error
                            PreviewStatus(
                                title = content.title,
                                body = content.message,
                                isDarkTheme = isDarkTheme,
                            )
                        }

                        uiState.reading.content is PreviewContent.Html -> {
                            val content = uiState.reading.content as PreviewContent.Html
                            HtmlPreviewView(
                                html = content.html,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        uiState.reading.content is PreviewContent.Pdf -> {
                            val content = uiState.reading.content as PreviewContent.Pdf
                            PdfPreviewView(
                                filePath = content.filePath,
                                pageCount = content.pageCount,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStatus(
    title: String,
    body: String,
    loading: Boolean = false,
    isDarkTheme: Boolean = false,
) {
    val titleColor = if (isDarkTheme) Color(0xFFF4F8FC) else Color(0xFF17212B)
    val bodyColor = if (isDarkTheme) Color(0xFFC1CDD8) else Color(0xFF6C7A89)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.4.dp,
                )
            } else {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
        )
        Text(
            text = body,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
        )
    }
}
