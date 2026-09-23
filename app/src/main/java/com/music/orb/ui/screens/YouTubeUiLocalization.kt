package com.music.orb.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.orb.R

/**
 * Localizes only YouTube Music labels that are known to be interface metadata.
 *
 * Anything that is not recognized is returned unchanged. This is deliberate:
 * song, album, artist and user-playlist names must never be translated by the app.
 */
@Composable
internal fun localizedYouTubeSectionTitle(text: String): String {
    val clean = text.trim()
    val normalized = clean.lowercase()

    return when (normalized) {
        "recently played" -> stringResource(R.string.yt_ui_recently_played)
        "listen again" -> stringResource(R.string.yt_ui_listen_again)
        "from your library" -> stringResource(R.string.yt_ui_from_your_library)
        "albums for you" -> stringResource(R.string.yt_ui_albums_for_you)
        "albums & singles", "albums and singles" ->
            stringResource(R.string.yt_ui_albums_and_singles)
        "new albums & singles", "new albums and singles" ->
            stringResource(R.string.yt_ui_new_albums_and_singles)
        "top songs" -> stringResource(R.string.yt_ui_top_songs)
        "singles & eps", "singles and eps" -> stringResource(R.string.yt_ui_singles_and_eps)
        "other versions", "outras versões", "otras versiones" ->
            stringResource(R.string.yt_ui_other_versions)
        "featured on" -> stringResource(R.string.yt_ui_featured_on)
        "fans might also like" -> stringResource(R.string.yt_ui_fans_might_also_like)
        "monthly audience" -> stringResource(R.string.yt_ui_monthly_audience)
        "quick picks" -> stringResource(R.string.yt_ui_quick_picks)
        "mixed for you" -> stringResource(R.string.yt_ui_mixed_for_you)
        "recommended music videos" -> stringResource(R.string.yt_ui_recommended_music_videos)
        "recommended playlists" -> stringResource(R.string.yt_ui_recommended_playlists)
        "your favorites" -> stringResource(R.string.yt_ui_your_favorites)
        "forgotten favorites" -> stringResource(R.string.yt_ui_forgotten_favorites)
        "new releases" -> stringResource(R.string.yt_ui_new_releases)
        "trending" -> stringResource(R.string.yt_ui_trending)
        "moods & genres", "moods and genres" -> stringResource(R.string.yt_ui_moods_and_genres)
        "live performances" -> stringResource(R.string.yt_ui_live_performances)
        "covers and remixes", "covers & remixes" -> stringResource(R.string.yt_ui_covers_and_remixes)

        // Orb expressive Home hub section names. The repository/MainActivity
        // may emit either its stable English label or the Portuguese label used
        // by the current product spec; both resolve through resources here so
        // switching the app language never leaves a mixed-language Home page.
        HOME_RECOMMENDATIONS_SHELF_TITLE ->
            stringResource(R.string.expressive_for_you_recommendations)
        HOME_TOP_ARTISTS_SHELF_TITLE ->
            stringResource(R.string.expressive_for_you_top_artists)
        HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE ->
            stringResource(R.string.expressive_albums_new_from_favorite)
        "new albums", "novos álbuns", "nuevos álbumes" ->
            stringResource(R.string.expressive_albums_new)
        "your favorite albums", "seus álbuns favoritos", "tus álbumes favoritos" ->
            stringResource(R.string.expressive_albums_favorites)
        "albums you may like", "álbuns que você pode gostar", "álbumes que te pueden gustar" ->
            stringResource(R.string.expressive_albums_may_like)
        "tocadas recentemente", "reproducidas recientemente" ->
            stringResource(R.string.expressive_for_you_recent)
        "ouça novamente", "escuchar de nuevo" ->
            stringResource(R.string.expressive_for_you_listen_again)
        "escolhas rápidas", "selecciones rápidas" ->
            stringResource(R.string.expressive_for_you_quick_picks)
        "mixes para você", "mixes para ti" ->
            stringResource(R.string.expressive_for_you_mixes)
        "suas playlists", "tus playlists", "your playlists" ->
            stringResource(R.string.expressive_for_you_playlists)
        "da sua biblioteca", "de tu biblioteca" ->
            stringResource(R.string.expressive_for_you_library)
        "favoritos esquecidos", "favoritos olvidados" ->
            stringResource(R.string.expressive_for_you_forgotten)
        "álbuns para você", "álbumes para ti" ->
            stringResource(R.string.expressive_for_you_albums)
        "recaps" -> stringResource(R.string.expressive_for_you_recaps)

        "novos lançamentos", "nuevos lanzamientos" ->
            stringResource(R.string.expressive_releases_new)
        "released" -> "Released"
        "de artistas que você curte", "de artistas que te gustan" ->
            stringResource(R.string.expressive_releases_liked)
        "de artistas que você já ouviu", "de artistas que ya escuchaste" ->
            stringResource(R.string.expressive_releases_heard)
        "lançamentos da sua região", "lanzamientos de tu región",
        "lançamentos nacionais", "lanzamientos nacionales", "national releases" ->
            stringResource(R.string.expressive_releases_region)
        "lançamentos globais", "lanzamientos globales", "global releases",
        "lançamentos internacionais", "lanzamientos internacionales", "international releases" ->
            stringResource(R.string.expressive_releases_global)

        "top músicas", "top canciones", "top 10 nacional", "national top 10" ->
            stringResource(R.string.expressive_trending_top_songs)
        "top 10 global", "global top 10" ->
            stringResource(R.string.expressive_trending_top_global)
        "em alta", "en tendencia" -> stringResource(R.string.expressive_trending_now)
        "daily charts" -> stringResource(R.string.expressive_trending_daily)
        "weekly charts" -> stringResource(R.string.expressive_trending_weekly)

        "you may like this", "você pode gostar disso", "puede que te guste esto" ->
            stringResource(R.string.expressive_discover_may_like)

        "músicas que você nunca ouviu", "canciones que nunca escuchaste" ->
            stringResource(R.string.expressive_discover_unheard_songs)
        "artistas que você nunca ouviu", "artistas que nunca escuchaste" ->
            stringResource(R.string.expressive_discover_unheard_artists)
        "álbuns que você nunca ouviu", "álbumes que nunca escuchaste" ->
            stringResource(R.string.expressive_discover_unheard_albums)

        // Library shelves returned by YouTube Music.
        "playlists" -> stringResource(R.string.yt_ui_playlists)
        "albums" -> stringResource(R.string.yt_ui_albums)
        "artists" -> stringResource(R.string.yt_ui_artists)
        "subscriptions" -> stringResource(R.string.yt_ui_subscriptions)
        "on device" -> stringResource(R.string.yt_ui_on_device)

        else -> when {
            normalized.startsWith("because you listened to ") -> {
                val subject = clean.substring("because you listened to ".length).trim()
                stringResource(R.string.yt_ui_because_you_listened_to, subject)
            }
            normalized.startsWith("more like ") -> {
                val subject = clean.substring("more like ".length).trim()
                stringResource(R.string.yt_ui_more_like, subject)
            }
            normalized.startsWith("playlists by ") -> {
                val artist = clean.substring("playlists by ".length).trim()
                stringResource(R.string.yt_ui_playlists_by, artist)
            }
            else -> clean
        }
    }
}

/**
 * Localizes a Library card title only when it is a known YouTube-generated label.
 * User-created playlist names and music titles fall through unchanged.
 */
@Composable
internal fun localizedYouTubeLibraryItemTitle(text: String): String {
    val clean = text.trim()
    return when (clean.lowercase()) {
        "liked music" -> stringResource(R.string.yt_ui_liked_music)
        "your likes" -> stringResource(R.string.yt_ui_liked_music)
        "downloads" -> stringResource(R.string.yt_ui_downloads)
        "local music" -> stringResource(R.string.yt_ui_local_music)
        else -> clean
    }
}

/**
 * Localizes common metadata fragments returned by YouTube Music, while preserving
 * names and numbers. Example: "215K subscribers" -> "215K inscritos".
 */
@Composable
internal fun localizedYouTubeMetadata(text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return clean

    // YouTube commonly separates metadata with bullets. Translate only the
    // recognized segments and preserve every unknown segment verbatim.
    val separator = when {
        clean.contains(" • ") -> " • "
        clean.contains(" · ") -> " · "
        else -> null
    }

    if (separator != null) {
        val translatedParts = mutableListOf<String>()
        for (part in clean.split(separator)) {
            translatedParts += localizedYouTubeMetadataSegment(part.trim())
        }
        return translatedParts.joinToString(separator)
    }

    return localizedYouTubeMetadataSegment(clean)
}

@Composable
private fun localizedYouTubeMetadataSegment(segment: String): String {
    val clean = segment.trim()
    val normalized = clean.lowercase()

    return when (normalized) {
        "auto playlist" -> stringResource(R.string.yt_ui_auto_playlist)
        "album" -> stringResource(R.string.yt_ui_type_album)
        "single" -> stringResource(R.string.yt_ui_type_single)
        "ep" -> stringResource(R.string.yt_ui_type_ep)
        "playlist" -> stringResource(R.string.yt_ui_type_playlist)
        "artist" -> stringResource(R.string.yt_ui_type_artist)
        "song" -> stringResource(R.string.yt_ui_type_song)
        "songs" -> stringResource(R.string.yt_ui_type_songs)
        "track" -> stringResource(R.string.yt_ui_type_track)
        "tracks" -> stringResource(R.string.yt_ui_type_tracks)
        "subscriber" -> stringResource(R.string.yt_ui_type_subscriber)
        "subscribers" -> stringResource(R.string.yt_ui_type_subscribers)
        "monthly audience" -> stringResource(R.string.yt_ui_monthly_audience)
        else -> {
            val songs = Regex("""^(.+?)\s+songs?$""", RegexOption.IGNORE_CASE).matchEntire(clean)
            if (songs != null) {
                stringResource(R.string.yt_ui_song_count, songs.groupValues[1])
            } else {
                val tracks = Regex("""^(.+?)\s+tracks?$""", RegexOption.IGNORE_CASE).matchEntire(clean)
                if (tracks != null) {
                    stringResource(R.string.yt_ui_track_count, tracks.groupValues[1])
                } else {
                    val subscribers =
                        Regex("""^(.+?)\s+subscribers?$""", RegexOption.IGNORE_CASE).matchEntire(clean)
                    if (subscribers != null) {
                        stringResource(R.string.yt_ui_subscriber_count, subscribers.groupValues[1])
                    } else {
                        val monthlyAudience =
                            Regex("""^(.+?)\s+monthly audience$""", RegexOption.IGNORE_CASE).matchEntire(clean)
                        if (monthlyAudience != null) {
                            stringResource(R.string.yt_ui_monthly_audience_count, monthlyAudience.groupValues[1])
                        } else {
                            clean
                        }
                    }
                }
            }
        }
    }
}
