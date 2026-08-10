package com.notes.notes.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.NotesUiState
import com.notes.notes.core.SettingsSubPage
import com.notes.notes.core.ThemeMode
import com.notes.notes.core.ThemePalette
import com.notes.notes.core.label
import com.notes.notes.core.stringsFor
import com.notes.notes.ui.NotesAppViewModel
import com.notes.notes.ui.components.ActionChip
import com.notes.notes.ui.components.GlassPanel
import com.notes.notes.ui.components.InfoPill
import com.notes.notes.ui.components.NotesAlertDialog
import com.notes.notes.ui.components.ScreenHeader
import com.notes.notes.ui.components.SecondaryActionButton
import com.notes.notes.ui.components.SectionDivider
import com.notes.notes.ui.components.SectionListCard
import com.notes.notes.ui.components.SectionRow
import com.notes.notes.ui.components.SelectionCheck
import com.notes.notes.ui.components.SettingsCard
import com.notes.notes.ui.components.UserInitialAvatar
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Composable
fun AccountScreen(
    uiState: NotesUiState,
    viewModel: NotesAppViewModel,
    contentBottomPadding: Dp,
) {
    val strings = stringsFor(uiState.settings.language)
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteCurPassword by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val showingAccountDetails =
        uiState.session.isLoggedIn && uiState.settingsSubPage == SettingsSubPage.ACCOUNT

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight + contentBottomPadding)
                    .padding(top = 16.dp, bottom = contentBottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(
                    title = if (showingAccountDetails) strings.account.currentAccount else strings.nav.account,
                    trailing = {
                        if (showingAccountDetails) {
                            ActionChip(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                onClick = viewModel::closeSettingsSubPage,
                            )
                        } else {
                            ActionChip(
                                icon = themeModeIcon(uiState.settings.theme.mode),
                                onClick = viewModel::toggleThemeMode,
                            )
                        }
                    },
                )

                if (uiState.session.isLoggedIn) {
                    AccountSummaryCard(
                        uiState = uiState,
                        onClick = if (showingAccountDetails) {
                            viewModel::closeSettingsSubPage
                        } else {
                            viewModel::openSettingsAccountPage
                        },
                        showLogoutButton = showingAccountDetails,
                        onLogout = if (showingAccountDetails) viewModel::logout else null,
                    )
                } else {
                    AuthEntryCard(uiState = uiState, viewModel = viewModel)
                }

                if (!showingAccountDetails) {
                    SettingsCard(
                        title = strings.account.languagePanelTitle,
                        icon = Icons.Outlined.Language,
                    ) {
                        SectionListCard {
                            AppLanguage.entries.forEachIndexed { index, language ->
                                SectionRow(
                                    title = language.label(strings),
                                    selected = uiState.settings.language == language,
                                    onClick = { viewModel.setLanguage(language) },
                                    trailing = { SelectionCheck(uiState.settings.language == language) },
                                )
                                if (index < AppLanguage.entries.lastIndex) {
                                    SectionDivider()
                                }
                            }
                        }
                    }

                    SettingsCard(
                        title = strings.theme.settingsTitle,
                        icon = Icons.Outlined.Palette,
                    ) {
                        Text(
                            text = strings.theme.mode,
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalNotesExtraColors.current.textMuted,
                        )
                        SectionListCard {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SectionRow(
                                    title = mode.label(strings),
                                    selected = uiState.settings.theme.mode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    trailing = { SelectionCheck(uiState.settings.theme.mode == mode) },
                                )
                                if (index < ThemeMode.entries.lastIndex) {
                                    SectionDivider()
                                }
                            }
                        }
                        Text(
                            text = strings.theme.palette,
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalNotesExtraColors.current.textMuted,
                        )
                        SectionListCard {
                            ThemePalette.entries.forEachIndexed { index, palette ->
                                SectionRow(
                                    title = palette.label(strings),
                                    selected = uiState.settings.theme.palette == palette,
                                    onClick = { viewModel.setThemePalette(palette) },
                                    trailing = { SelectionCheck(uiState.settings.theme.palette == palette) },
                                )
                                if (index < ThemePalette.entries.lastIndex) {
                                    SectionDivider()
                                }
                            }
                        }
                    }
                }

                if (showingAccountDetails) {
                    ChangePasswordCard(uiState = uiState, viewModel = viewModel)

                    SettingsCard(
                        title = strings.account.deleteAccount,
                        description = strings.deleteAccount.warning,
                        icon = Icons.Outlined.Delete,
                    ) {
                        OutlinedTextField(
                            value = deleteCurPassword,
                            onValueChange = { deleteCurPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.deleteAccount.curPassword) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { showDeleteConfirm = true },
                            enabled = !uiState.accountBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(strings.deleteAccount.confirmFirst)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        NotesAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteAccount(deleteCurPassword)
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                ) {
                    Text(strings.deleteAccount.confirmSecond)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.common.cancel)
                }
            },
            title = { Text(strings.deleteAccount.modalTitle) },
            text = { Text(strings.deleteAccount.modalBody) },
        )
    }
}

@Composable
private fun AccountSummaryCard(
    uiState: NotesUiState,
    onClick: (() -> Unit)? = null,
    showLogoutButton: Boolean = false,
    onLogout: (() -> Unit)? = null,
) {
    val strings = stringsFor(uiState.settings.language)
    GlassPanel(onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserInitialAvatar(text = uiState.session.username, size = 56.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = uiState.session.username,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.account.currentAccount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showLogoutButton && onLogout != null) {
                    SecondaryActionButton(
                        label = strings.account.logoutButton,
                        onClick = onLogout,
                    )
                }
            }
        }
    }
}

private fun themeModeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun AuthEntryCard(uiState: NotesUiState, viewModel: NotesAppViewModel) {
    val strings = stringsFor(uiState.settings.language)
    var loginMode by rememberSaveable { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    GlassPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                InfoPill(
                    label = strings.common.login,
                    highlighted = loginMode,
                    onClick = { loginMode = true },
                )
                InfoPill(
                    label = strings.common.register,
                    highlighted = !loginMode,
                    onClick = { loginMode = false },
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.auth.username) },
                leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.auth.password) },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            null,
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
            )

            if (loginMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text(strings.auth.autoLogin, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { viewModel.login(username, password, rememberMe) },
                    enabled = !uiState.accountBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                ) {
                    if (uiState.accountBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.auth.loginButton)
                    }
                }
            } else {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.auth.confirmPassword) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(
                    onClick = { viewModel.register(username, password, confirmPassword) },
                    enabled = !uiState.accountBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                ) {
                    if (uiState.accountBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.auth.registerButton)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangePasswordCard(uiState: NotesUiState, viewModel: NotesAppViewModel) {
    val strings = stringsFor(uiState.settings.language)
    var curPassword by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmedPassword by rememberSaveable { mutableStateOf("") }

    SettingsCard(
        title = strings.account.changePassword,
        description = strings.changePassword.warning,
        icon = Icons.Outlined.Lock,
    ) {
        OutlinedTextField(
            value = curPassword,
            onValueChange = { curPassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.changePassword.curPassword) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.changePassword.newPassword) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = confirmedPassword,
            onValueChange = { confirmedPassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.changePassword.confirmPassword) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            onClick = { viewModel.changePassword(curPassword, password, confirmedPassword) },
            enabled = !uiState.accountBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        ) {
            if (uiState.accountBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(strings.changePassword.submit)
            }
        }
    }
}
