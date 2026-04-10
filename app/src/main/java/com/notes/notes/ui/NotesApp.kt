package com.notes.notes.ui

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notes.notes.core.AppTab
import com.notes.notes.core.NotesUiState
import com.notes.notes.core.SettingsSubPage
import com.notes.notes.core.stringsFor
import com.notes.notes.ui.components.BottomBarLayout
import com.notes.notes.ui.components.LoadingOverlay
import com.notes.notes.ui.components.NotesSnackbarHost
import com.notes.notes.ui.components.notesSnackbarVisuals
import com.notes.notes.ui.screens.AccountScreen
import com.notes.notes.ui.screens.DiskScreen
import com.notes.notes.ui.screens.ReadingScreen
import com.notes.notes.ui.theme.LocalNotesExtraColors
import com.notes.notes.ui.theme.NotesTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun NotesApp(viewModel: NotesAppViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NotesTheme(settings = uiState.settings.theme) {
        NotesAppContent(
            viewModel = viewModel,
            uiState = uiState,
        )
    }
}

@Composable
private fun NotesAppContent(
    viewModel: NotesAppViewModel,
    uiState: NotesUiState,
) {
    val strings = stringsFor(uiState.settings.language)
    val extraColors = LocalNotesExtraColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val isBottomBarVisible = uiState.currentTab != AppTab.READING || uiState.reading.isBottomBarVisible
    val canNavigateBack =
        (uiState.currentTab == AppTab.ACCOUNT && uiState.settingsSubPage != SettingsSubPage.ROOT) ||
            (uiState.currentTab == AppTab.DISK && uiState.disk.paths.size > 1) ||
            uiState.tabBackStack.isNotEmpty()

    LaunchedEffect(viewModel) {
        var snackbarJob: Job? = null
        viewModel.messages.collect { message ->
            snackbarJob?.cancel()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob = launch {
                snackbarHostState.showSnackbar(
                    visuals = notesSnackbarVisuals(
                        message = message.message,
                        tone = message.tone,
                    )
                )
            }
        }
    }

    BackHandler(enabled = canNavigateBack) {
        viewModel.navigateBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = extraColors.backgroundTop,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            BottomBarLayout(
                visible = isBottomBarVisible,
                currentTab = uiState.currentTab,
                onSelectTab = viewModel::setCurrentTab,
                strings = strings,
            ) { layoutPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(extraColors.backgroundTop)
                ) {
                    AnimatedContent(
                        targetState = uiState.currentTab,
                        modifier = Modifier.fillMaxSize(),
                        label = "notes-tab-switch",
                    ) { tab ->
                        when (tab) {
                            AppTab.DISK -> DiskScreen(
                                uiState = uiState,
                                viewModel = viewModel,
                                contentBottomPadding = layoutPadding.contentBottom,
                            )
                            AppTab.READING -> ReadingScreen(uiState = uiState, viewModel = viewModel)
                            AppTab.ACCOUNT -> AccountScreen(
                                uiState = uiState,
                                viewModel = viewModel,
                                contentBottomPadding = layoutPadding.contentBottom,
                            )
                        }
                    }

                    NotesSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = statusBarTopPadding + 14.dp),
                    )
                }
            }

            if (uiState.bootstrapping) {
                LoadingOverlay(
                    title = strings.common.loading,
                    subtitle = strings.nav.title,
                )
            }
        }
    }
}
