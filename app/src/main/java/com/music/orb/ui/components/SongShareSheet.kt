package com.music.orb.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import com.music.orb.ui.components.OrbGlassFilledTonalIconButton as FilledTonalIconButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.music.orb.R
import com.music.orb.data.model.Song
import com.music.orb.ui.SongShareStoryRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongShareSheet(
    song: Song,
    onDismiss: () -> Unit,
    shareLinkOverride: String? = null,
    floatingTitleOverride: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var artwork by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    var uri by remember(song.videoId) { mutableStateOf<Uri?>(null) }
    var sharing by remember(song.videoId) { mutableStateOf(false) }
    var storyUri by remember(song.videoId) { mutableStateOf<Uri?>(null) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val snack = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.song_share_failed)
    LaunchedEffect(song) {
        try {
            artwork = SongShareStoryRenderer.render(context, song, cardOnly = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    RegisterSheetBackdrop()
    val sheetGlassColor = MaterialTheme.colorScheme.primaryContainer
    val sheetContentColor = orbOverlayGlassContentColor(sheetGlassColor)
    var sheetEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sheetEntered = true }
    val sheetEntrance by animateFloatAsState(
        targetValue = if (sheetEntered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "songShareSheetEntrance",
    )
    ModalBottomSheet(
        navigationBarInsetInContent = true,
        floatingTitle = floatingTitleOverride ?: stringResource(R.string.orb_sheet_share_song),
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = null,
    ) {
        CompositionLocalProvider(LocalContentColor provides sheetContentColor) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 0.82f + 0.18f * sheetEntrance
                        translationY = (1f - sheetEntrance) * 18.dp.toPx()
                        val scale = 0.985f + 0.015f * sheetEntrance
                        scaleX = scale
                        scaleY = scale
                    }
                    .orbSheetPanelElevation(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                    .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                    .orbGlassSurface(
                        sheetGlassColor,
                        cornerRadius = 36.dp,
                        backdropBlur = 56.dp,
                        overlayStrength = true,
                        overlayOpacity = 0.48f,
                        lightweightSheetMaterial = true,
                        sheetTintMode = OrbSheetTintMode.BACKDROP,
                    )
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(sheetContentColor.copy(alpha = 0.42f)),
                    )
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = sheetContentColor.copy(alpha = 0.12f),
                            contentColor = sheetContentColor,
                        ),
                    ) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.share_close))
                    }
                }
                Spacer(Modifier.height(6.dp))
            val maxArtHeight = (configuration.screenHeightDp - 240).coerceAtLeast(160).coerceAtMost(420).dp
            Box(Modifier.height(maxArtHeight).aspectRatio(872f / 1108f),
                contentAlignment = Alignment.Center) {
                artwork?.let { Image(it.asImageBitmap(), song.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: if (failed) Text(errorText, color = sheetContentColor, modifier = Modifier.padding(20.dp))
                    else CircularProgressIndicator(color = sheetContentColor)
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val labels = listOf(stringResource(R.string.share_copy_link), "Instagram\nStories", "WhatsApp\nStatus", "Threads\nPost", stringResource(R.string.share_more))
                val packages = listOf<String?>(null, "com.instagram.android", "com.whatsapp", "com.instagram.barcelona", null)
                labels.forEachIndexed { index, label ->
                    val appIcon = remember(packages[index]) {
                        packages[index]?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                    }
                    val enabled = !sharing && (index == 0 || artwork != null)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val targetColor = when (index % 3) {
                            1 -> MaterialTheme.colorScheme.secondaryContainer
                            2 -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                        val targetContent = when (index % 3) {
                            1 -> MaterialTheme.colorScheme.onSecondaryContainer
                            2 -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                        val actionModifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = enabled) {
                                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                                if (index == 0) {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText(song.title, shareLinkOverride ?: SongShareStoryRenderer.link(song)))
                                } else scope.launch {
                                    sharing = true
                                    try {
                                        val exportUri = if (index in 1..3) {
                                            storyUri ?: SongShareStoryRenderer.render(context, song, story = true).let { bitmap ->
                                                try { SongShareStoryRenderer.prepare(context, bitmap) }
                                                finally { bitmap.recycle() }
                                            }.also { storyUri = it }
                                        } else {
                                            uri ?: SongShareStoryRenderer.render(context, song).let { bitmap ->
                                                try { SongShareStoryRenderer.prepare(context, bitmap) }
                                                finally { bitmap.recycle() }
                                            }.also { uri = it }
                                        }
                                        SongShareStoryRenderer.sharePrepared(context, song, exportUri, packages[index], shareLinkOverride)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        snack.showSnackbar(errorText)
                                    } finally { sharing = false }
                                }
                            }
                        if (appIcon != null && index in 1..3) {
                            // Real app icons already own their visual identity. Keep the touch
                            // target but do not place a Material container behind them.
                            Box(actionModifier, contentAlignment = Alignment.Center) {
                                Image(appIcon, label, Modifier.size(42.dp))
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = targetColor,
                                contentColor = targetContent,
                                modifier = actionModifier,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (index == 0) Icons.Rounded.Link else Icons.Rounded.MoreHoriz,
                                        label,
                                        Modifier.size(25.dp),
                                        tint = targetContent.copy(alpha = if (enabled) 1f else .4f),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = sheetContentColor,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
                SnackbarHost(snack)
                Spacer(Modifier.height(18.dp))
                Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
