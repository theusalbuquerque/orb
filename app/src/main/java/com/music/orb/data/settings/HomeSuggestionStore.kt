package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.ShelfItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Zero-network storage for the Home Suggestions hero.
 *
 * The Home screen must never have to manufacture its first visible carousel.
 * Instead we keep two durable 20-item buffers:
 *  - current: the immutable set rendered for this app session;
 *  - next: the complete set prepared silently during the current session and
 *    promoted atomically on the next real app entry.
 *
 * A larger local pool is also retained so a sparse Home response can still
 * produce a complete next buffer without opening a radio/watch-next request.
 * The two personalised source caches are retained separately as well:
 * YouTube Music's Digital Jukebox/Speed Dial and My Supermix.
 */
object HomeSuggestionStore {
    private const val PREFS = "orb_home_suggestions"
    private const val MAX_ITEMS = 20
    private const val MAX_POOL_ITEMS = 96
    private const val KEY_CURRENT = "current_v2"
    private const val KEY_NEXT = "next_v2"
    private const val KEY_POOL = "pool_v2"
    private const val KEY_JUKEBOX = "jukebox_v1"
    private const val KEY_SUPERMIX = "supermix_v1"
    private const val KEY_SUPERMIX_BROWSE_ID = "supermix_browse_id_v1"
    private const val MAX_SOURCE_ITEMS = 48
    private const val LEGACY_PREFIX = "items_"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Current carousel, safe to read synchronously during Activity.onCreate. */
    @Synchronized
    fun loadCurrent(): List<ShelfItem> {
        val storage = prefs ?: return emptyList()
        val current = clean(read(storage, KEY_CURRENT, MAX_ITEMS), MAX_ITEMS)
        if (current.isNotEmpty()) return current

        // v104 and earlier stored one list under a hashed account key. Migrate
        // the fullest legacy snapshot once so upgrading users keep an instant
        // first paint instead of falling back to a network wait.
        val migrated = storage.all.entries
            .asSequence()
            .filter { it.key.startsWith(LEGACY_PREFIX) }
            .mapNotNull { (_, value) ->
                (value as? String)?.let { clean(decode(it, MAX_ITEMS), MAX_ITEMS) }
            }
            .maxByOrNull { it.size }
            .orEmpty()
        if (migrated.isNotEmpty()) {
            write(storage, KEY_CURRENT, migrated, MAX_ITEMS, commit = true)
            mergePoolLocked(storage, migrated)
        }
        return migrated
    }

    @Synchronized
    fun loadNext(): List<ShelfItem> = prefs?.let { clean(read(it, KEY_NEXT, MAX_ITEMS), MAX_ITEMS) }.orEmpty()

    @Synchronized
    fun loadPool(): List<ShelfItem> = prefs?.let { clean(read(it, KEY_POOL, MAX_POOL_ITEMS), MAX_POOL_ITEMS) }.orEmpty()

    /**
     * Promote a previously prepared full buffer. This is intentionally a
     * synchronous commit because it runs before Compose and is tiny (~20 rows).
     */
    @Synchronized
    fun promoteNext(): List<ShelfItem> {
        val storage = prefs ?: return emptyList()
        val current = loadCurrent()
        val next = clean(read(storage, KEY_NEXT, MAX_ITEMS), MAX_ITEMS)
        if (next.size < MAX_ITEMS) return current

        write(storage, KEY_CURRENT, next, MAX_ITEMS, commit = true)
        storage.edit().remove(KEY_NEXT).commit()
        mergePoolLocked(storage, next)
        return next
    }

    /** Prepare the next session without changing what the user currently sees. */
    @Synchronized
    fun stageNext(items: List<ShelfItem>) {
        val storage = prefs ?: return
        val complete = clean(items, MAX_ITEMS)
        if (complete.size < MAX_ITEMS) return
        write(storage, KEY_NEXT, complete, MAX_ITEMS, commit = true)
        mergePoolLocked(storage, complete)
    }

    /** Atomic visible replacement used only by an explicit pull-to-refresh. */
    @Synchronized
    fun replaceCurrent(items: List<ShelfItem>) {
        val storage = prefs ?: return
        val complete = clean(items, MAX_ITEMS)
        if (complete.isEmpty()) return
        write(storage, KEY_CURRENT, complete, MAX_ITEMS, commit = true)
        mergePoolLocked(storage, complete)
    }

    /** Last song grid exposed by YouTube Music's Digital Jukebox / Speed Dial. */
    @Synchronized
    fun loadJukebox(): List<ShelfItem> =
        prefs?.let { clean(read(it, KEY_JUKEBOX, MAX_SOURCE_ITEMS), MAX_SOURCE_ITEMS) }.orEmpty()

    @Synchronized
    fun saveJukebox(items: List<ShelfItem>) {
        val storage = prefs ?: return
        val cleaned = clean(items, MAX_SOURCE_ITEMS)
        if (cleaned.isNotEmpty()) {
            write(storage, KEY_JUKEBOX, cleaned, MAX_SOURCE_ITEMS, commit = false)
            mergePoolLocked(storage, cleaned)
        }
    }

    /** Cached tracks from the account's generated My Supermix playlist. */
    @Synchronized
    fun loadSupermix(): List<ShelfItem> =
        prefs?.let { clean(read(it, KEY_SUPERMIX, MAX_SOURCE_ITEMS), MAX_SOURCE_ITEMS) }.orEmpty()

    @Synchronized
    fun saveSupermix(items: List<ShelfItem>, browseId: String? = null) {
        val storage = prefs ?: return
        val cleaned = clean(items, MAX_SOURCE_ITEMS)
        val editor = storage.edit()
        if (cleaned.isNotEmpty()) {
            editor.putString(KEY_SUPERMIX, encode(cleaned, MAX_SOURCE_ITEMS))
        }
        browseId?.takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_SUPERMIX_BROWSE_ID, it)
        }
        editor.apply()
        if (cleaned.isNotEmpty()) mergePoolLocked(storage, cleaned)
    }

    @Synchronized
    fun saveSupermixBrowseId(browseId: String) {
        val storage = prefs ?: return
        if (browseId.isBlank()) return
        storage.edit().putString(KEY_SUPERMIX_BROWSE_ID, browseId).apply()
    }

    @Synchronized
    fun loadSupermixBrowseId(): String? =
        prefs?.getString(KEY_SUPERMIX_BROWSE_ID, null)?.takeIf { it.isNotBlank() }

    /** Keep a broad local recommendation reservoir for future sessions. */
    @Synchronized
    fun mergePool(items: List<ShelfItem>) {
        val storage = prefs ?: return
        mergePoolLocked(storage, items)
    }

    private fun mergePoolLocked(storage: SharedPreferences, items: List<ShelfItem>) {
        val merged = clean(items + read(storage, KEY_POOL, MAX_POOL_ITEMS), MAX_POOL_ITEMS)
        if (merged.isNotEmpty()) write(storage, KEY_POOL, merged, MAX_POOL_ITEMS, commit = false)
    }

    private fun clean(items: List<ShelfItem>, limit: Int): List<ShelfItem> {
        val seenVideoIds = HashSet<String>()
        val seenIdentity = HashSet<String>()
        return items.asSequence()
            .filter {
                !it.videoId.isNullOrBlank() &&
                    !it.isVideo &&
                    !isVideoFrameGrab(it.thumbnailUrl)
            }
            .filter { item ->
                val videoId = item.videoId.orEmpty()
                val identity = "${normalise(item.title)}|${normalise(item.subtitle)}"
                seenVideoIds.add(videoId) && seenIdentity.add(identity)
            }
            .take(limit)
            .toList()
    }


    private fun isVideoFrameGrab(url: String?): Boolean {
        val cleanUrl = url?.lowercase(Locale.ROOT)?.substringBefore('?') ?: return false
        return cleanUrl.endsWith("/hqdefault.jpg") ||
            cleanUrl.endsWith("/mqdefault.jpg") ||
            cleanUrl.endsWith("/sddefault.jpg") ||
            cleanUrl.endsWith("/maxresdefault.jpg") ||
            cleanUrl.endsWith("/0.jpg") ||
            cleanUrl.endsWith("/1.jpg") ||
            cleanUrl.endsWith("/2.jpg") ||
            cleanUrl.endsWith("/3.jpg")
    }

    private fun normalise(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('&', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun read(storage: SharedPreferences, key: String, limit: Int): List<ShelfItem> {
        val raw = storage.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return decode(raw, limit)
    }

    private fun decode(raw: String, limit: Int): List<ShelfItem> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                fromJson(item)?.let(::add)
                if (size >= limit) break
            }
        }
    }

    private fun encode(items: List<ShelfItem>, limit: Int): String {
        val array = JSONArray()
        clean(items, limit).forEach { array.put(toJson(it)) }
        return array.toString()
    }

    private fun write(
        storage: SharedPreferences,
        key: String,
        items: List<ShelfItem>,
        limit: Int,
        commit: Boolean,
    ) {
        val cleaned = clean(items, limit)
        if (cleaned.isEmpty()) return
        val editor = storage.edit().putString(key, encode(cleaned, limit))
        if (commit) editor.commit() else editor.apply()
    }

    private fun toJson(item: ShelfItem): JSONObject = JSONObject().apply {
        put("title", item.title)
        put("subtitle", item.subtitle)
        putNullable("thumbnailUrl", item.thumbnailUrl)
        putNullable("videoId", item.videoId)
        putNullable("browseId", item.browseId)
        put("isExplicit", item.isExplicit)
        put("type", item.type.name)
        putNullable("durationText", item.durationText)
        putNullable("artistId", item.artistId)
        putNullable("albumId", item.albumId)
        putNullable("albumName", item.albumName)
        put("isVideo", item.isVideo)
        putNullable("setVideoId", item.setVideoId)
        put("fromAutoplay", item.fromAutoplay)
        put("queuePinned", item.queuePinned)
        putNullable("releaseYear", item.releaseYear)
        put("sourceExplicitKnown", item.sourceExplicitKnown)
        putNullable("sourcePlaylistId", item.sourcePlaylistId)
        putNullable("sourcePlaylistTitle", item.sourcePlaylistTitle)
        putNullable("sourcePlaylistArtworkUrl", item.sourcePlaylistArtworkUrl)
    }

    private fun fromJson(item: JSONObject): ShelfItem? {
        val videoId = item.optNullableString("videoId") ?: return null
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
        return ShelfItem(
            title = title,
            subtitle = item.optString("subtitle"),
            thumbnailUrl = item.optNullableString("thumbnailUrl"),
            videoId = videoId,
            browseId = item.optNullableString("browseId"),
            isExplicit = item.optBoolean("isExplicit", false),
            type = runCatching {
                BrowseType.valueOf(item.optString("type", BrowseType.OTHER.name))
            }.getOrDefault(BrowseType.OTHER),
            durationText = item.optNullableString("durationText"),
            artistId = item.optNullableString("artistId"),
            albumId = item.optNullableString("albumId"),
            albumName = item.optNullableString("albumName"),
            isVideo = item.optBoolean("isVideo", false),
            setVideoId = item.optNullableString("setVideoId"),
            fromAutoplay = item.optBoolean("fromAutoplay", false),
            queuePinned = item.optBoolean("queuePinned", false),
            releaseYear = if (item.isNull("releaseYear")) null else item.optInt("releaseYear"),
            sourceExplicitKnown = item.optBoolean("sourceExplicitKnown", true),
            sourcePlaylistId = item.optNullableString("sourcePlaylistId"),
            sourcePlaylistTitle = item.optNullableString("sourcePlaylistTitle"),
            sourcePlaylistArtworkUrl = item.optNullableString("sourcePlaylistArtworkUrl"),
        )
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
