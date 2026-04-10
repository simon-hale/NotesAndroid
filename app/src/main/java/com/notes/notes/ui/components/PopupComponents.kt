package com.notes.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Composable
fun NotesAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val extraColors = LocalNotesExtraColors.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(28.dp),
        containerColor = extraColors.modalSurface,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = extraColors.textMuted,
        tonalElevation = 0.dp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extraColors = LocalNotesExtraColors.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = extraColors.modalSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = extraColors.modalScrim,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(extraColors.borderStrong.copy(alpha = 0.95f)),
                )
            }
        },
        content = content,
    )
}
