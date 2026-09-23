package com.music.orb.ui

import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.reachedListenThreshold
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Canonical listening ranking used by both Stats and Profile.
 *
 * Ordering is intentionally identical everywhere:
 * 1) valid play count (descending)
 * 2) total effective listening time (descending)
 * 3) display title (ascending, deterministic final tie-break)
 */
internal data class ListeningRankedItem(
    val identity: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val listenedMs: Long,
    val plays: Int,
)

internal fun currentMonthStartInstant(
    now: ZonedDateTime = ZonedDateTime.now(),
): Instant = now
    .withDayOfMonth(1)
    .toLocalDate()
    .atStartOfDay(now.zone)
    .toInstant()

internal fun currentMonthListeningActivity(
    activity: List<ListeningActivity>,
    now: ZonedDateTime = ZonedDateTime.now(),
): List<ListeningActivity> {
    val start = currentMonthStartInstant(now)
    val end = now.toInstant()
    return activity.filter { row ->
        row.reachedListenThreshold() &&
            row.startedInstantForRanking()?.let { it >= start && it < end } == true
    }
}

internal fun rankListeningArtists(
    activity: List<ListeningActivity>,
    limit: Int = Int.MAX_VALUE,
): List<ListeningRankedItem> = activity
    .asSequence()
    .filter { it.reachedListenThreshold() }
    .flatMap { row ->
        ArtistCreditResolver.creditsForCounting(row.artist)
            .asSequence()
            .map { artist -> artist to row }
    }
    .groupBy { (artist, _) -> artist.normalizeArtistMatchKey() }
    .filterKeys { it.isNotBlank() }
    .map { (identity, creditedRows) ->
        val artist = creditedRows.first().first
        val rows = creditedRows.map { it.second }
        ListeningRankedItem(
            identity = identity,
            title = artist,
            listenedMs = rows.sumOf { it.effectivePlayedMs() },
            plays = rows.size,
        )
    }
    .sortedWith(LISTENING_RANK_COMPARATOR)
    .take(limit.coerceAtLeast(0))

internal fun rankListeningTracks(
    activity: List<ListeningActivity>,
    limit: Int = Int.MAX_VALUE,
): List<ListeningRankedItem> = activity
    .asSequence()
    .filter { it.reachedListenThreshold() }
    .groupBy { row ->
        row.videoId.takeIf { it.isNotBlank() }
            ?: "${row.title.trim().lowercase(Locale.ROOT)}\u001f${row.artist.trim().lowercase(Locale.ROOT)}"
    }
    .map { (identity, rows) ->
        val lead = rows.first()
        ListeningRankedItem(
            identity = identity,
            title = lead.title,
            subtitle = lead.artist,
            artworkUrl = rows.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) },
            listenedMs = rows.sumOf { it.effectivePlayedMs() },
            plays = rows.size,
        )
    }
    .sortedWith(LISTENING_RANK_COMPARATOR)
    .take(limit.coerceAtLeast(0))

internal fun rankListeningAlbums(
    activity: List<ListeningActivity>,
    limit: Int = Int.MAX_VALUE,
): List<ListeningRankedItem> = activity
    .asSequence()
    .filter { it.reachedListenThreshold() && !it.album.isNullOrBlank() }
    .groupBy { row ->
        "${row.album!!.trim().lowercase(Locale.ROOT)}\u001f${row.artist.trim().lowercase(Locale.ROOT)}"
    }
    .map { (identity, rows) ->
        val lead = rows.first()
        ListeningRankedItem(
            identity = identity,
            title = lead.album!!.trim(),
            subtitle = lead.artist,
            artworkUrl = rows.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) },
            listenedMs = rows.sumOf { it.effectivePlayedMs() },
            plays = rows.size,
        )
    }
    .sortedWith(LISTENING_RANK_COMPARATOR)
    .take(limit.coerceAtLeast(0))

private val LISTENING_RANK_COMPARATOR =
    compareByDescending<ListeningRankedItem> { it.plays }
        .thenByDescending { it.listenedMs }
        .thenBy { it.title.lowercase(Locale.ROOT) }

internal fun String.normalizeArtistMatchKey(): String =
    lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

internal fun ListeningActivity.effectivePlayedMs(): Long {
    playedMs?.takeIf { it > 0L }?.let { return it }
    durationMs?.takeIf { it > 0L }?.let { return it }
    val start = runCatching { Instant.parse(startedAt) }.getOrNull()
    val finish = runCatching { Instant.parse(finishedAt) }.getOrNull()
    return if (start != null && finish != null && finish > start) {
        Duration.between(start, finish).toMillis().coerceAtLeast(0L)
    } else {
        0L
    }
}

private fun ListeningActivity.startedInstantForRanking(): Instant? =
    runCatching { Instant.parse(startedAt) }.getOrNull()
