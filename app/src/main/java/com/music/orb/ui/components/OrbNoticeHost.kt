package com.music.orb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.music.orb.ui.notifications.OrbNotice
import com.music.orb.ui.notifications.OrbNoticeCenter
import com.music.orb.ui.notifications.OrbNoticeIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private data class NoticePalette(
    val container: Color,
    val content: Color,
)

@Composable
fun OrbNoticeHost(
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<OrbNotice?>(null) }
    var visible by remember { mutableStateOf(false) }
    var dismissToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        OrbNoticeCenter.notices.collect { notice ->
            current = notice
            visible = true
            val token = ++dismissToken
            delay(3_200L)
            if (token == dismissToken) {
                visible = false
                delay(380L)
                if (token == dismissToken) current = null
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(100f)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible && current != null,
            enter = slideInVertically(
                initialOffsetY = { -it - 36 },
                animationSpec = spring(
                    dampingRatio = 0.50f,
                    stiffness = 250f,
                ),
            ) + fadeIn(tween(120)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = 0.58f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = slideOutVertically(
                targetOffsetY = { -it / 2 },
                animationSpec = tween(220),
            ) + fadeOut(tween(180)),
        ) {
            current?.let { notice ->
                OrbNoticeCard(
                    notice = notice,
                    onDismiss = {
                        dismissToken += 1
                        visible = false
                        current = null
                    },
                )
            }
        }
    }
}

@Composable
private fun OrbNoticeCard(
    notice: OrbNotice,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = when ((notice.styleSeed ushr 1) % 4) {
        0 -> NoticePalette(scheme.surfaceContainerHigh, scheme.onSurface)
        1 -> NoticePalette(scheme.primaryContainer, scheme.onPrimaryContainer)
        2 -> NoticePalette(scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> NoticePalette(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
    val icon: ImageVector = when (notice.icon) {
        OrbNoticeIcon.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
        OrbNoticeIcon.PLAY_NEXT -> Icons.Rounded.SkipNext
        OrbNoticeIcon.PLAYLIST -> Icons.Rounded.PlaylistAdd
        OrbNoticeIcon.LIBRARY -> Icons.Rounded.LibraryAdd
        OrbNoticeIcon.DOWNLOAD -> Icons.Rounded.Download
        OrbNoticeIcon.INFO -> Icons.Rounded.Info
    }

    Surface(
        color = palette.container,
        contentColor = palette.content,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.content.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.content,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.content,
                    maxLines = 1,
                )
                notice.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.content.copy(alpha = 0.76f),
                        maxLines = 2,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = null,
                    tint = palette.content.copy(alpha = 0.76f),
                )
            }
        }
    }
}
