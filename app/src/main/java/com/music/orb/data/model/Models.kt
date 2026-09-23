package com.music.orb.data.model

/** A playable YouTube Music track. */
data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String? = null,
    /** Browse ids lifted from the row, used by the long-press actions. */
    val artistId: String? = null,
    val albumId: String? = null,
    /** Names the album page header, which [albumId] alone can't. */
    val albumName: String? = null,
    /** A music-video upload rather than the catalogue track. */
    val isVideo: Boolean = false,
    /**
     * This track's identity *within one playlist*, which is not its [videoId]:
     * the same song added twice is two entries with two set-video-ids, and
     * removing one of them is only expressible in those terms. Present only on
     * rows parsed from a playlist page, which is the only place a removal can
     * be asked for from.
     */
    val setVideoId: String? = null,
    /**
     * Queued by AutoPlay or by a station's own mix rather than asked for — the
     * player groups these under the AutoPlay heading and keeps them at the
     * bottom of the queue, below anything the user picked.
     */
    val fromAutoplay: Boolean = false,
    /**
     * Explicit content or file URI for local device tracks or downloaded audio.
     */
    val localUri: String? = null,
    /**
     * Real filesystem path backing [localUri], when MediaStore exposes one.
     * Lets playback swap a content:// row for a raw file:// path on formats
     * that need it — see [com.music.orb.playback.toMediaItem].
     */
    val localPath: String? = null,
    /**
     * What a non-YouTube source says it can serve this recording at, as one of
     * `LOSSLESS`, `HIGH` or `LOW` — null for every row that didn't come from
     * one.
     *
     * Carried on the row rather than discovered at stream time because it is
     * the only thing that distinguishes two catalogues holding the same track,
     * and the choice between them has to be made *before* either is asked for
     * a URL. Without it the picker was blind: a Deezer row and a 16-bit FLAC
     * row looked identical, the FLAC lost a tie-break on artist spelling, and
     * the track played as a 128kbps MP3.
     */
    val sourceQuality: String? = null,
    /**
     * The listener explicitly chose this track's position in the future queue
     * (for example with Play next or by dragging it). Shuffle may reorder the
     * surrounding queue, but never this item or anything across it.
     */
    val queuePinned: Boolean = false,
    /** YouTube/source metadata marks this recording as explicit content. */
    val isExplicit: Boolean = false,
    /**
     * Release year when a catalogue exposes it. Primarily used as recording
     * identity during cross-source matching; null means the source did not
     * state a trustworthy release year, never "current year".
     */
    val releaseYear: Int? = null,
    /**
     * Whether [isExplicit] was actually stated by the source. YouTube rows
     * normally know this, while lightweight TIDAL search responses sometimes
     * omit the flag entirely. An omitted flag is unknown, not a clean master.
     * Kept at the end to preserve positional Song constructor compatibility.
     */
    val sourceExplicitKnown: Boolean = true,
    /** Playlist context when this track was launched from a playlist detail page.
     * Used only as metadata: Stats persists this attribution on the listener's own
     * RLS-protected listening_activity row; no audio or stream data is transferred. */
    val sourcePlaylistId: String? = null,
    val sourcePlaylistTitle: String? = null,
    val sourcePlaylistArtworkUrl: String? = null,
    /**
     * Every artist credit that YouTube Music explicitly links for this track.
     *
     * [artistId] remains the primary/legacy shortcut, while this list preserves
     * collaborations such as "Nu Aspect, Arkaden, & Sam Welch" as independent
     * navigation targets instead of making the whole byline open the first artist.
     */
    val artistLinks: List<ArtistLink> = emptyList(),
)

/**
 * Artwork at a given pixel size.
 *
 * YouTube serves every size from one URL via a `w<n>-h<n>` hint, so the size
 * an image is fetched at is the caller's to choose, and worth choosing in both
 * directions. Up: the size YouTube advertises is far short of what a
 * full-screen player draws, and the source images run to about 1400px, so
 * asking for more is free and sharper. Down: a row thumbnail left at the
 * advertised size costs an order of magnitude more bytes than the square it
 * fills — 84kB against 7.8kB, measured on the same cover.
 *
 * Video thumbnails carry no hint and are returned unchanged.
 */
fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)

/** As [Song.artworkAt], for artwork that isn't a track's. */
fun String?.artworkAt(px: Int): String? = this?.replace(SIZE_HINT, "w$px-h$px")

private val SIZE_HINT = Regex("""w\d+-h\d+""")

/**
 * Artwork for a list row — 52dp at most, so about 140px on a 3x screen.
 * Rounded up, and one value for every row in the app rather than one per
 * row height, so they share a cache entry instead of each fetching its own.
 */
const val ROW_ART_PX = 160

/** Artwork for a shelf card: 166dp wide, so a little under 450px at 3x. */
const val CARD_ART_PX = 480

/** Artwork for a page header, drawn near enough full width. */
const val HEADER_ART_PX = 720

/**
 * Artwork handed to the media session — the lock screen, the notification,
 * Android Auto. Generous because those surfaces draw it large and take one
 * copy: unlike a list row, nothing goes back for a better one later.
 */
const val NOTIFICATION_ART_PX = 544

enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }

/** A non-track search result: album, artist or playlist. */
data class BrowseItem(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
    /** Explicit badge exposed by YouTube Music for this album/release. */
    val isExplicit: Boolean = false,
)

/** Search rows are heterogeneous once filters other than "Songs" are used. */
sealed interface SearchResult {
    data class Track(val song: Song) : SearchResult
    data class Browse(val item: BrowseItem) : SearchResult
}

enum class SearchFilter(val label: String, val params: String?) {
    SONGS("Songs", "EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgWKAQIoAWoKEAkQChAFEAMQBA=="),
}

/** A card in a home-feed carousel: either a track (videoId) or an album/playlist (browseId). */
data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
    /** Explicit badge exposed by the catalogue for this track/release. */
    val isExplicit: Boolean = false,
    /** What a browse card opens; ALBUM also covers singles and EPs. */
    val type: BrowseType = BrowseType.OTHER,
    /** Current position when this card belongs to an ordered YouTube chart. */
    val rank: Int? = null,
    /** Playback metadata preserved for local/offline recent cards. */
    val localUri: String? = null,
    val localPath: String? = null,
    val sourceQuality: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
    val fromAutoplay: Boolean = false,
    val queuePinned: Boolean = false,
    val releaseYear: Int? = null,
    val sourceExplicitKnown: Boolean = true,
    val sourcePlaylistId: String? = null,
    val sourcePlaylistTitle: String? = null,
    val sourcePlaylistArtworkUrl: String? = null,
)

/** The signed-in Google account, as YouTube Music reports it. */
data class Account(
    val name: String,
    val email: String,
    val thumbnailUrl: String?,
    /** YouTube channel handle when this account comes from YouTube Music. */
    val handle: String = "",
)

/**
 * One YouTube identity available under the signed-in Google account.
 *
 * [pageId] is YouTube's delegated/Brand Account id. A null page id is the
 * Google account's primary YouTube identity. When a Brand Account is selected, Innertube sends this id as
 * context.user.onBehalfOfUser; browser-cookie requests also carry X-Goog-PageId.
 */
data class YouTubeAccountIdentity(
    val name: String,
    val handle: String = "",
    val thumbnailUrl: String? = null,
    val pageId: String? = null,
    val isSelected: Boolean = false,
) {
    val stableKey: String
        get() = pageId ?: "primary:${handle.ifBlank { name }}"
}

data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    /** YouTube's "strapline" — the grey line Apple Music runs under a heading. */
    val subtitle: String = "",
    /**
     * Optional destination behind the shelf header's "more" affordance.
     *
     * Artist pages only inline a small first slice of Albums / Singles. Keeping
     * the header browse id lets the repository page the complete shelf after the
     * visible landing page has rendered, without making page opening wait for it.
     */
    val browseId: String? = null,
    /** Optional browse params paired with [browseId] (artist discography filters often need it). */
    val browseParams: String? = null,
)

/** A page of the Home feed, plus the token for the next one — null once exhausted. */
data class HomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String?,
)

/**
 * The signed-in library, as YouTube Music splits it: the auto-generated Liked
 * Music playlist, the tracks explicitly added to the library, and a shelf per
 * saved music collection (playlists, albums, artists and subscriptions).
 */
data class LibraryPage(
    val likedSongs: List<Song>,
    val librarySongs: List<Song>,
    val shelves: List<HomeShelf>,
) {
    val isEmpty: Boolean
        get() = likedSongs.isEmpty() && librarySongs.isEmpty() && shelves.isEmpty()
}

/** One artist explicitly linked by a detail-page header. */
data class ArtistLink(
    val artistId: String,
    val name: String,
)

/** A browsed album / artist / playlist page. */
data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: UiState<List<Song>>,
    val type: BrowseType = BrowseType.OTHER,
    /** Explicit hint carried from the card/search result so the header knows it immediately. */
    val isExplicit: Boolean = false,
    /** Artist header audience text, e.g. "29.1M monthly audience". */
    val monthlyAudience: String? = null,
    /** Artists that the release/playlist header itself links to. */
    val headerArtists: List<ArtistLink> = emptyList(),
    /** Albums / singles carousels, populated for artist pages. */
    val sections: List<HomeShelf> = emptyList(),
    /**
     * Continuation for a partially loaded album/playlist. Keeping the token on
     * the page makes progressive loading resumable if the user leaves before a
     * very long playlist has finished appending and then opens it again.
     */
    val continuation: String? = null,
    /**
     * Tracks YouTube offers to round out a playlist but that were never
     * added — see [com.music.orb.data.innertube.InnertubeParser.parsePlaylistShelf].
     * Shown as their own section with a button to actually add them, rather
     * than folded into [songs] where they'd read as the user's own picks.
     */
    val suggestedSongs: List<Song> = emptyList(),
    /**
     * Whether this release can be saved to the library and whether it already
     * is — null when the page doesn't offer it at all. Only ever set for an
     * album or playlist fetched with a session; see [LibraryState].
     */
    val library: LibraryState? = null,
)

/**
 * Whether an album or playlist is in the library, and the id that changes that.
 *
 * YouTube has no "save" verb for a release: a saved album is a *liked* one, and
 * what gets liked is the playlist behind the page rather than the browse id the
 * page was fetched with — an `MPREb…` album is backed by an `OLAK5uy_…`
 * playlist, and liking the browse id does nothing at all. So the id has to be
 * read off the page rather than derived from what was asked for.
 */
data class LibraryState(
    val playlistId: String,
    val saved: Boolean,
)

/** Parsed artist landing page. */
data class ArtistPage(
    val songs: List<Song>,
    /** Playlist holding the artist's full song list, when the page links one. */
    val moreSongsBrowseId: String?,
    val sections: List<HomeShelf>,
    /** The artist's own picture, off the page header. */
    val thumbnailUrl: String? = null,
    /** The single artist this page is for, as the header bills them. */
    val name: String? = null,
    /** Header-provided monthly audience, kept with its label for localization. */
    val monthlyAudience: String? = null,
)

/**
 * A track's thumbs rating on the signed-in account.
 *
 * [INDIFFERENT] is YouTube's own word for "neither", and is a real state
 * rather than the absence of one — clearing a like is a request in its own
 * right (`like/removelike`), not the omission of one.
 */
enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

/** Who can see a playlist. YouTube's own three values, sent verbatim. */
enum class PlaylistPrivacy(val label: String, val apiValue: String) {
    PRIVATE("Private", "PRIVATE"),
    UNLISTED("Unlisted", "UNLISTED"),
    PUBLIC("Public", "PUBLIC"),
}

/**
 * One of the account's own playlists, as the picker lists them.
 *
 * [playlistId] is the raw id (no `VL`), because that is what the edit endpoint
 * takes; [browseId] is the same playlist addressed as a page. Keeping both
 * spares every caller from remembering which prefix each side wants.
 */
data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
) {
    val browseId: String get() = "VL$playlistId"
}

/** Contributor metadata exposed by YouTube Music's TRACK_CREDITS page. */
data class SongCreditSection(
    /** Localized section title supplied by YouTube Music, e.g. Performed by. */
    val title: String,
    /** People or organizations credited in this section, in source order. */
    val names: List<String>,
)

data class SongCredits(
    val sections: List<SongCreditSection>,
)

/**
 * The per-track state that only YouTube can answer: its rating, and whether it
 * is in the library.
 *
 * Library membership is not addressable by video id — it is toggled with an
 * opaque feedback token that YouTube mints per row and per direction, so the
 * tokens have to be fetched before the action can be offered at all. Both
 * arrive together on the watch queue's own menu, which is why this is one
 * lookup rather than two.
 */
data class SongMenu(
    /**
     * The rating YouTube states on this row, or null when the row states
     * none — which is common, and is *not* the same as INDIFFERENT. A watch
     * queue frequently renders without a like button at all, and reading that
     * silence as "not liked" is how a liked song ends up claiming it isn't.
     */
    val likeStatus: LikeStatus?,
    val inLibrary: Boolean,
    val addToLibraryToken: String?,
    val removeFromLibraryToken: String?,
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
