package com.music.orb.download

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.orb.data.DebugLog as Log
import com.music.orb.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * App-managed offline storage for Orb downloads.
 *
 * New downloads intentionally do NOT go into the public Music/BitChord folder.
 * They live in Orb's app-specific external files directory instead, so Android's
 * media scanner and other players do not treat them as ordinary user files and
 * Orb can always remove them without a MediaStore ownership/permission round-trip.
 *
 * Older builds did publish files into Music/BitChord. Legacy helpers are kept
 * only so "Remove all downloads" can clean those files up after an update.
 */
object DownloadStore {

    private const val TAG = "BitChord"

    /** Historical public subfolder name, retained for legacy cleanup only. */
    const val FOLDER = "BitChord"

    private const val MANAGED_FOLDER = "offline"

    /** Files already opened successfully by Android's extractor this process. */
    private val validatedAudioUris = ConcurrentHashMap.newKeySet<String>()

    /** App-specific storage never needs WRITE_EXTERNAL_STORAGE. */
    fun needsLegacyPermission(): Boolean = false

    // ---- Naming -------------------------------------------------------------

    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_STEM_CHARS = 120

    class Storable(val extension: String, val mimeType: String)

    fun storable(codec: String?): Storable? = when (codec?.lowercase()?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "aac", "mp4a", "m4a", "mp4" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    // ---- Managed location ---------------------------------------------------

    private fun managedRoot(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "music")
        return File(base, MANAGED_FOLDER)
    }

    private fun managedFile(context: Context, name: String): File =
        File(managedRoot(context), name)

    /** URI of a completed app-managed file with this name, if valid. */
    fun existing(context: Context, name: String): Uri? {
        val candidate = managedFile(context, name)
        if (!candidate.exists() || !candidate.isFile) return null
        val uri = Uri.fromFile(candidate)
        if (isPlayableAudio(context, uri)) return uri
        Log.w(TAG, "$name exists in managed storage but is not playable; deleting it")
        delete(context, uri)
        return null
    }

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    fun isPlayableAudio(context: Context, uri: Uri, force: Boolean = false): Boolean {
        val key = uri.toString()
        if (!force && key in validatedAudioUris) return true

        val valid = runCatching {
            val extractor = MediaExtractor()
            try {
                if (uri.scheme == "file") {
                    extractor.setDataSource(requireNotNull(uri.path))
                } else {
                    extractor.setDataSource(context, uri, null)
                }
                (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/", ignoreCase = true) == true
                }
            } finally {
                extractor.release()
            }
        }.onFailure {
            Log.w(TAG, "audio validation failed for $uri: ${it.message}")
        }.getOrDefault(false)

        if (valid) validatedAudioUris.add(key) else validatedAudioUris.remove(key)
        return valid
    }

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        validatedAudioUris.remove(uri.toString())
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return@runCatching false
            !file.exists() || file.delete()
        } else {
            context.contentResolver.delete(uri, null, null) > 0 || !exists(context, uri)
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    // ---- Writing ------------------------------------------------------------

    class Pending internal constructor(
        val uri: Uri,
        val name: String,
        private val part: File,
        private val target: File,
    ) {
        fun openStream(): OutputStream = part.outputStream()

        fun commit(): Uri {
            if (target.exists() && !target.delete()) {
                error("Could not replace existing $name")
            }
            if (!part.renameTo(target)) error("Could not finish writing $name")
            return Uri.fromFile(target)
        }

        fun abort() {
            part.delete()
        }
    }

    /** Reserve a private, app-managed destination for one completed offline track. */
    fun begin(context: Context, name: String, mimeType: String): Pending {
        // mimeType is intentionally retained in the API: callers still resolve and
        // validate a real storable audio format before we reach this layer.
        @Suppress("UNUSED_VARIABLE")
        val validatedMime = mimeType

        val folder = managedRoot(context)
        if (!folder.exists() && !folder.mkdirs()) {
            error("Could not create Orb offline storage")
        }
        val target = managedFile(context, name)
        val part = File(folder, "$name.part")
        if (part.exists()) part.delete()
        return Pending(Uri.fromFile(target), name, part, target)
    }

    /**
     * Removes every file from Orb's current app-managed download directory,
     * including interrupted .part files and orphaned files no longer in prefs.
     */
    fun deleteAllManaged(context: Context): Int {
        val root = managedRoot(context)
        if (!root.exists()) return 0
        var removed = 0
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            if (file.isFile) {
                validatedAudioUris.remove(Uri.fromFile(file).toString())
                if (file.delete()) removed++
            } else if (file.isDirectory) {
                file.delete()
            }
        }
        root.delete()
        return removed
    }

    /**
     * Cleans files created by older Orb builds in the public Music/BitChord
     * folder. These are no longer used for new downloads.
     */
    fun deleteAllLegacyPublic(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteLegacyMediaStoreRows(context)
        } else {
            @Suppress("DEPRECATION")
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                FOLDER,
            )
            if (!folder.exists()) 0 else {
                var removed = 0
                folder.walkBottomUp().forEach { file ->
                    if (file == folder) return@forEach
                    if (file.isFile && file.delete()) removed++
                    else if (file.isDirectory) file.delete()
                }
                folder.delete()
                removed
            }
        }
    }

    private fun deleteLegacyMediaStoreRows(context: Context): Int {
        var removed = 0
        runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$FOLDER%")
            val ids = mutableListOf<Long>()
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val relative = cursor.getString(pathCol).orEmpty()
                    // Avoid deleting an unrelated folder merely because its name
                    // contains the same letters; require a real BitChord path segment.
                    val segments = relative.replace('\\', '/').split('/').filter { it.isNotBlank() }
                    if (segments.any { it.equals(FOLDER, ignoreCase = true) }) {
                        ids += cursor.getLong(idCol)
                    }
                }
            }
            ids.forEach { id ->
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                if (delete(context, uri)) removed++
            }
        }.onFailure {
            Log.w(TAG, "legacy public download cleanup failed: ${it.message}")
        }
        return removed
    }
}
