package com.music.orb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.RecentPlaybackEntry
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackHistorySheet(
    entries: List<RecentPlaybackEntry>,
    onSongClick: (Song) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val groups = groupHistoryByDay(
        entries = entries,
        todayLabel = stringResource(R.string.playback_history_today),
        yesterdayLabel = stringResource(R.string.playback_history_yesterday),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.playback_history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.playback_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 56.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 8.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "history-day-" + group.key) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(
                                    start = 6.dp,
                                    top = 14.dp,
                                    bottom = 7.dp,
                                ),
                            )
                        }
                        items(
                            items = group.entries,
                            key = { it.sessionKey() },
                        ) { entry ->
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
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

    val zone = java.util.TimeZone.getDefault()
    val today = java.util.Calendar.getInstance(zone).toCalendarDay()

    return entries
        .sortedByDescending { it.playedAtMs }
        .groupBy { entry ->
            java.util.Calendar.getInstance(zone).apply {
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
                        java.util.Calendar.getInstance(zone).apply {
                            set(date.year, date.month, date.day, 12, 0, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
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
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_MONTH, -days)
        }
        return calendar.toCalendarDay()
    }
}

private fun java.util.Calendar.toCalendarDay(): CalendarDay =
    CalendarDay(
        get(java.util.Calendar.YEAR),
        get(java.util.Calendar.MONTH),
        get(java.util.Calendar.DAY_OF_MONTH),
    )

private fun rememberHistoryTimeFormatter(): DateFormat =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
