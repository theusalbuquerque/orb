package com.music.orb.playback

import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.Song
import com.music.orb.data.settings.LikeStatusStore
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.social.OrbSongRatingMutation
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.innertube.Innertube
import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Locale
import java.time.Instant

/** Notification actions use the same account-scoped rating store and YouTube outbox as the app. */
internal object NotificationRatings {
    fun accountKey(): String? = OrbGoogleAuth.storedAccount()?.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: SocialRepository.currentUserId()
    private fun recordingKey(song: Song): String? {
        fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val title = normalized(song.title).takeIf { it.isNotBlank() } ?: return null
        val artist = normalized(song.artist).takeIf { it.isNotBlank() } ?: return null
        val parts = song.durationText?.split(':')?.map { it.toIntOrNull() }
        val seconds = if (parts != null && parts.size in 2..3 && parts.all { it != null }) parts.fold(0) { total, part -> total * 60 + requireNotNull(part) } else null
        return "rec:v1:$title|$artist|${seconds?.let { ((it + 2) / 5) * 5 } ?: "?"}"
    }
    fun liked(song: Song): Boolean {
        val ratings = LikeStatusStore.load(accountKey())
        return (ratings[song.videoId] ?: recordingKey(song)?.let { ratings[it] }) == LikeStatus.LIKE
    }
    /** Persist synchronously before starting remote work; a quick second tap sees the new state. */
    fun toggle(song: Song): Pair<String, LikeStatus>? {
        val key = accountKey() ?: return null
        val wasLiked = liked(song)
        val status = if (wasLiked) LikeStatus.INDIFFERENT else LikeStatus.LIKE
        val updates = buildMap { put(song.videoId, status); recordingKey(song)?.let { put(it, status) } }
        LikeStatusStore.setMany(key, updates)
        com.music.orb.data.spotify.LinkedLikesSync.remember(key, song)
        LikeStatusStore.queueYoutube(key, song.videoId, status, forgetLibraryOnSuccess = wasLiked)
        return key to status
    }
    suspend fun sync(song: Song, account: String, status: LikeStatus) {
        val stored = LikeStatusStore.loadRecords(account)[song.videoId] ?: return
        if (stored.status != status) return
        val timestamp = stored.updatedAtMs
        for (attempt in 0..3) {
            if (accountKey() != account || LikeStatusStore.loadRecords(account)[song.videoId]?.updatedAtMs != timestamp) return
            try {
                SocialRepository.setMySongRating(OrbSongRatingMutation(
                    videoId = song.videoId, recordingKey = recordingKey(song), status = status.name,
                    title = song.title, artist = song.artist, album = song.albumName, durationText = song.durationText,
                    updatedAt = Instant.ofEpochMilli(timestamp).toString(),
                ))
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (attempt < 3) delay(750L * (attempt + 1)) }
        }
        if (accountKey() != account || !AppSettings.useYouTubeMusicLibrary.value || !Innertube.hasAccountSession) return
        val pending = LikeStatusStore.pendingYoutube(account)[song.videoId] ?: return
        if (pending.status != status) return
        YtMusicRepository.rate(song.videoId, status).onSuccess {
            LikeStatusStore.clearYoutubeIfMatches(account, song.videoId, pending)
        }
    }
}
