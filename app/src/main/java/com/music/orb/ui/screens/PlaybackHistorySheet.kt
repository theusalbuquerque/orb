package com.music.orb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.RecentPlaybackEntry
import com.music.orb.ui.components.ProgressiveActionSheetItem
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackHistorySheet(
    entries: List<RecentPlaybackEntry>,
    onSongClick: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = groupHistoryByDay(
        entries = entries,
        todayLabel = stringResource(R.string.playback_history_today),
        yesterdayLabel = stringResource(R.string.playback_history_yesterday),
    )
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Keep enough space above the glass for the floating title, as on other Orb sheets.
    val maxHistoryHeight = LocalConfiguration.current.screenHeightDp.dp * 0.80f
    val sheetColor = orbOverlayGlassBaseColor()

    // Use the same fixed, frosted Orb sheet host as other action sheets.
    // Its handle owns dismissal while the list retains independent scrolling.
    com.music.orb.ui.components.RegisterSheetBackdrop()
    com.music.orb.ui.components.OrbModalBottomSheet(
        onDismissRequest = onDismiss,
        floatingTitle = stringResource(R.string.playback_history_title),
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        navigationBarInsetInContent = true,
        dragHandle = null,
    ) {
        com.music.orb.ui.components.OrbGlassSheetSurface(
            pageColor = sheetColor,
            cornerRadius = 36.dp,
            // The shared glass surface includes the navigation-bar inset.
            // Avoid a second opaque background, which hides its blur/tint.
            modifier = Modifier.heightIn(max = maxHistoryHeight),
        ) {
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.playback_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 56.dp),
                )
            } else {
                LazyColumn(
                    state = historyListState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    var nextRowIndex = 0
                    groups.forEach { group ->
                        val headerIndex = nextRowIndex++
                        item(key = "history-day-" + group.key) {
                            ProgressiveActionSheetItem(
                                index = headerIndex,
                                itemKey = "history-day-" + group.key,
                                modifier = com.music.orb.ui.components.verticalEdgeMotion(historyListState, "history-day-" + group.key),
                            ) {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 7.dp),
                                )
                            }
                        }
                        val firstEntryIndex = nextRowIndex
                        nextRowIndex += group.entries.size
                        itemsIndexed(
                            items = group.entries,
                            key = { _, entry -> entry.eventId },
                        ) { entryIndex, entry ->
                            ProgressiveActionSheetItem(
                                index = firstEntryIndex + entryIndex,
                                itemKey = entry.eventId,
                                modifier = com.music.orb.ui.components.verticalEdgeMotion(historyListState, entry.eventId),
                            ) {
                                PlaybackHistoryRow(
                                    entry = entry,
                                    onClick = { onSongClick(entry.song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackHistoryRow(
    entry: RecentPlaybackEntry,
    onClick: () -> Unit,
) {
    val timeFormatter = rememberHistoryTimeFormatter()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.song.thumbnailUrl.artworkAt(224),
            contentDescription = entry.song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.size(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
        Icon(
            imageVector = when (entry.deviceType) {
                "phone" -> Icons.Rounded.PhoneAndroid
                "tablet" -> Icons.Rounded.TabletAndroid
                "computer" -> Icons.Rounded.Computer
                else -> Icons.Rounded.Devices
            },
            contentDescription = when (entry.deviceType) {
                "phone" -> stringResource(R.string.history_device_phone)
                "tablet" -> stringResource(R.string.history_device_tablet)
                "computer" -> stringResource(R.string.history_device_computer)
                "multiple" -> stringResource(R.string.history_device_multiple)
                else -> stringResource(R.string.history_device_unknown)
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = timeFormatter.format(Date(entry.playedAtMs)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class HistoryDayGroup(
    val key: String,
    val label: String,
    val entries: List<RecentPlaybackEntry>,
)

private fun groupHistoryByDay(
    entries: List<RecentPlaybackEntry>,
    todayLabel: String,
    yesterdayLabel: String,
): List<HistoryDayGroup> {
    if (entries.isEmpty()) return emptyList()

    val zone = TimeZone.getDefault()
    val today = Calendar.getInstance(zone).toCalendarDay()

    return entries
        .sortedByDescending { it.playedAtMs }
        .groupBy { entry ->
            Calendar.getInstance(zone).apply {
                timeInMillis = entry.playedAtMs
            }.toCalendarDay()
        }
        .entries
        .sortedByDescending { it.key }
        .map { (date, dayEntries) ->
            val label = when {
                date == today -> todayLabel
                date == today.minusDays(1) -> yesterdayLabel
                else -> {
                    val formatter = DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault())
                    formatter.timeZone = zone
                    formatter.format(
                        Calendar.getInstance(zone).apply {
                            set(date.year, date.month, date.day, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.time,
                    )
                }
            }
            HistoryDayGroup(
                key = date.key(),
                label = label,
                entries = dayEntries,
            )
        }
}

private data class CalendarDay(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CalendarDay> {
    override fun compareTo(other: CalendarDay): Int =
        compareValuesBy(this, other, CalendarDay::year, CalendarDay::month, CalendarDay::day)

    fun key(): String = year.toString() + "-" + month + "-" + day

    fun minusDays(days: Int): CalendarDay {
        val calendar = Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -days)
        }
        return calendar.toCalendarDay()
    }
}

private fun Calendar.toCalendarDay(): CalendarDay =
    CalendarDay(
        get(Calendar.YEAR),
        get(Calendar.MONTH),
        get(Calendar.DAY_OF_MONTH),
    )

private fun rememberHistoryTimeFormatter(): DateFormat =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
