package com.notes.notes.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notes.notes.core.AppTab
import com.notes.notes.core.MessageTone
import com.notes.notes.core.PathSegment
import com.notes.notes.ui.theme.LocalNotesExtraColors

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extraColors = LocalNotesExtraColors.current
    if (onClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = extraColors.panelTop,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extraColors.borderStrong),
            shadowElevation = 0.dp,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = extraColors.panelTop,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extraColors.borderStrong),
            shadowElevation = 0.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    scrollableSubtitle: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    if (scrollableSubtitle) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalNotesExtraColors.current.textMuted,
                                softWrap = false,
                            )
                        }
                    } else {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalNotesExtraColors.current.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

@Composable
fun InfoPill(
    label: String,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val extraColors = LocalNotesExtraColors.current
    val shape = RoundedCornerShape(999.dp)
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val content = @Composable {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(1.dp, if (highlighted) containerColor else extraColors.borderStrong),
            shadowElevation = 0.dp,
            content = content,
        )
    } else {
        Surface(
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(1.dp, if (highlighted) containerColor else extraColors.borderStrong),
            content = content,
        )
    }
}

@Composable
private fun SectionRowContent(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    resolvedIconBackground: Color?,
    tint: Color,
    scrollableTitle: Boolean,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBubble(
                icon = icon,
                background = resolvedIconBackground,
                tint = tint,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (scrollableTitle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                    )
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalNotesExtraColors.current.textMuted,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

@Composable
private fun ClickableSectionRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    resolvedIconBackground: Color?,
    tint: Color,
    scrollableTitle: Boolean,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        SectionRowContent(
            title = title,
            subtitle = subtitle,
            icon = icon,
            resolvedIconBackground = resolvedIconBackground,
            tint = tint,
            scrollableTitle = scrollableTitle,
            trailing = trailing,
        )
    }
}

@Composable
private fun StaticSectionRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    resolvedIconBackground: Color?,
    tint: Color,
    scrollableTitle: Boolean,
    trailing: @Composable RowScope.() -> Unit,
) {
    SectionRowContent(
        title = title,
        subtitle = subtitle,
        icon = icon,
        resolvedIconBackground = resolvedIconBackground,
        tint = tint,
        scrollableTitle = scrollableTitle,
        trailing = trailing,
    )
}

@Composable
fun SettingsCard(
    title: String,
    description: String? = null,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(
                    icon = icon,
                    background = null,
                    size = 42.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalNotesExtraColors.current.textMuted,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun SectionHeading(title: String, action: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = LocalNotesExtraColors.current.textMuted,
        )
        if (!action.isNullOrBlank()) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = LocalNotesExtraColors.current.textMuted,
            )
        }
    }
}

@Composable
fun ActionChip(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(42.dp),
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, iconRingColor()),
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    GlassPanel {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalNotesExtraColors.current.textMuted,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun LoadingCard(message: String) {
    GlassPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.4.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun LoginReminderCard(message: String, actionLabel: String, onAction: () -> Unit) {
    GlassPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ThemeSummaryPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    InfoPill(
        label = label,
        icon = icon,
        highlighted = true,
        onClick = onClick,
    )
}

@Composable
fun BreadcrumbBar(
    paths: List<PathSegment>,
    rootLabel: String,
    onOpenRoot: () -> Unit,
    onPathClick: (PathSegment) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BreadcrumbItem(
            label = rootLabel,
            icon = Icons.Outlined.Home,
            highlighted = paths.size <= 1,
            onClick = onOpenRoot,
        )
        paths.drop(1).forEach { path ->
            Text(
                text = ">",
                style = MaterialTheme.typography.labelMedium,
                color = LocalNotesExtraColors.current.textMuted,
            )
            BreadcrumbItem(
                label = path.name,
                highlighted = path == paths.lastOrNull(),
                onClick = { onPathClick(path) },
            )
        }
    }
}

@Composable
private fun BreadcrumbItem(
    label: String,
    icon: ImageVector? = null,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val extraColors = LocalNotesExtraColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) neutralIconLayerColor() else Color.Transparent,
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onSurface
        } else {
            extraColors.textMuted
        },
        border = if (highlighted) BorderStroke(1.dp, extraColors.borderStrong) else null,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FloatingBottomBar(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    strings: com.notes.notes.core.AppStrings,
) {
    val extraColors = LocalNotesExtraColors.current
    val items = listOf(
        Triple(AppTab.DISK, Icons.Rounded.FolderOpen, strings.nav.disk),
        Triple(AppTab.READING, Icons.AutoMirrored.Rounded.Article, strings.nav.read),
        Triple(AppTab.ACCOUNT, Icons.Rounded.ManageAccounts, strings.nav.account),
    )
    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { (tab, icon, _) ->
            val selected = currentTab == tab
            val containerColor = animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                } else {
                    extraColors.panelTop.copy(alpha = 0.18f)
                },
                label = "floating-bar-container",
            )
            val contentColor = animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    extraColors.textMuted
                },
                label = "floating-bar-content",
            )
            val borderColor = animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    Color.White.copy(alpha = 0.22f)
                },
                label = "floating-bar-border",
            )
            Surface(
                modifier = Modifier.size(DefaultBottomBarMetrics.buttonSize),
                onClick = { onSelectTab(tab) },
                shape = CircleShape,
                color = containerColor.value,
                contentColor = contentColor.value,
                border = BorderStroke(1.dp, borderColor.value),
                shadowElevation = 0.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(if (selected) 24.dp else 22.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingOverlay(title: String, subtitle: String) {
    val extraColors = LocalNotesExtraColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extraColors.modalScrim)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(28.dp),
            color = extraColors.modalSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extraColors.borderStrong),
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.4.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = extraColors.textMuted,
                    )
                }
            }
        }        
    }
}

@Composable
fun SectionListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
fun SectionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackground: Color? = null,
    emphasizeIconBackground: Boolean = false,
    selected: Boolean = false,
    scrollableTitle: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val resolvedIconBackground = when {
        selected -> weakEmphasisIconLayerColor()
        emphasizeIconBackground -> iconBackground ?: neutralIconLayerColor()
        else -> iconBackground
    }
    if (onClick != null) {
        ClickableSectionRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            resolvedIconBackground = resolvedIconBackground,
            tint = if (selected) MaterialTheme.colorScheme.primary else iconTint,
            scrollableTitle = scrollableTitle,
            onClick = onClick,
            trailing = trailing,
        )
    } else {
        StaticSectionRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            resolvedIconBackground = resolvedIconBackground,
            tint = if (selected) MaterialTheme.colorScheme.primary else iconTint,
            scrollableTitle = scrollableTitle,
            trailing = trailing,
        )
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
        color = LocalNotesExtraColors.current.borderStrong,
        thickness = 1.dp,
    )
}

@Composable
fun RowActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = tint,
        border = BorderStroke(1.dp, iconRingColor()),
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun InlineBanner(
    message: String,
    highlighted: Boolean = false,
) {
    val extraColors = LocalNotesExtraColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            extraColors.textMuted
        },
        border = BorderStroke(1.dp, extraColors.borderStrong),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun NotesSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        NotesSnackbar(data = data)
    }
}

@Composable
private fun NotesSnackbar(data: SnackbarData) {
    val extraColors = LocalNotesExtraColors.current
    val tone = (data.visuals as? NotesSnackbarVisuals)?.tone ?: MessageTone.INFO
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accentColor = when (tone) {
        MessageTone.SUCCESS -> extraColors.success
        MessageTone.ERROR -> extraColors.danger
        MessageTone.WARNING -> extraColors.warning
        MessageTone.INFO -> MaterialTheme.colorScheme.primary
    }
    val backgroundColor = when (tone) {
        MessageTone.SUCCESS -> accentColor.copy(alpha = if (isDarkSurface) 0.22f else 0.14f)
        MessageTone.ERROR -> accentColor.copy(alpha = if (isDarkSurface) 0.22f else 0.14f)
        MessageTone.WARNING, MessageTone.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
    }
    val borderColor = when (tone) {
        MessageTone.SUCCESS, MessageTone.ERROR -> accentColor.copy(alpha = if (isDarkSurface) 0.34f else 0.24f)
        MessageTone.WARNING, MessageTone.INFO -> extraColors.borderStrong.copy(alpha = 0.82f)
    }
    val icon = when (tone) {
        MessageTone.SUCCESS -> Icons.Rounded.CheckCircle
        MessageTone.ERROR -> Icons.Rounded.ErrorOutline
        MessageTone.WARNING -> Icons.Rounded.WarningAmber
        MessageTone.INFO -> Icons.Rounded.Info
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxSnackbarWidth = maxWidth * (2f / 3f)
        val maxMessageWidth = (maxSnackbarWidth - 90.dp).coerceAtLeast(120.dp)

        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = maxSnackbarWidth),
            shape = RoundedCornerShape(20.dp),
            color = backgroundColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor.copy(alpha = 0.92f),
                )
                Text(
                    text = data.visuals.message,
                    modifier = Modifier.widthIn(max = maxMessageWidth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    modifier = Modifier.size(28.dp),
                    onClick = data::dismiss,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = extraColors.textMuted,
                    )
                }
            }
        }
    }
}

private enum class SnackbarTone {
    SUCCESS,
    ERROR,
    WARNING,
    INFO,
}

private fun resolveSnackbarTone(message: String): SnackbarTone {
    val value = message.lowercase()
    return when {
        listOf(
            "失败",
            "错误",
            "无法",
            "不能",
            "非空",
            "不存在",
            "不允许",
            "为空",
            "未授权",
            "无权",
            "error",
            "failed",
            "unauthorized",
            "forbidden",
            "cannot",
            "unable",
            "invalid",
            "not allowed",
            "denied",
        ).any(value::contains) -> SnackbarTone.ERROR
        listOf("警告", "谨慎", "warning", "注意").any(value::contains) -> SnackbarTone.WARNING
        listOf("成功", "完成", "已", "欢迎", "success", "completed", "download started").any(value::contains) -> SnackbarTone.SUCCESS
        else -> SnackbarTone.INFO
    }
}

data class NotesSnackbarVisuals(
    override val message: String,
    val tone: MessageTone,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

fun notesSnackbarVisuals(
    message: String,
    tone: MessageTone,
): SnackbarVisuals = NotesSnackbarVisuals(
    message = message,
    tone = tone,
)

@Composable
fun PrimaryActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(label)
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(label)
    }
}

@Composable
fun UserInitialAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val label = text.trim().take(1).ifBlank { "N" }.uppercase()
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
fun SelectionCheck(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun IconBubble(
    icon: ImageVector,
    background: Color?,
    tint: Color,
    size: Dp = 40.dp,
) {
    if (background == null) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(size / 2.1f),
                tint = tint,
            )
        }
        return
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = background,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(size / 2.1f),
            )
        }
    }
}

@Composable
private fun neutralIconLayerColor(): Color {
    val surfaceIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (surfaceIsDark) {
        LocalNotesExtraColors.current.borderStrong.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun weakEmphasisIconLayerColor(): Color {
    val surfaceIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return MaterialTheme.colorScheme.primary.copy(alpha = if (surfaceIsDark) 0.24f else 0.12f)
}

@Composable
private fun iconRingColor(): Color {
    val surfaceIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return LocalNotesExtraColors.current.borderStrong.copy(alpha = if (surfaceIsDark) 0.96f else 0.88f)
}
