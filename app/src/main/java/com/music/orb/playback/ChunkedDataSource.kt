package com.music.orb.playback

import android.net.Uri
import com.music.orb.data.TrackLog
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.settings.AppSettings
import java.io.InterruptedIOException

/**
 * Fetches a stream as a run of bounded ranges instead of one open-ended read.
 *
 * googlevideo paces a continuous response down to roughly playback speed:
 * ask for a whole track in one request and it arrives at about 15kB/s, barely
 * ahead of the rate it is being consumed at. Ask for the same bytes as bounded
 * ranges and each one is served at line rate — measured on the same track,
 * over the same connection, seconds apart:
 *
 * ```
 *   GET (unbounded)               15.5 kB/s
 *   Range: bytes=0-2097151         5.7 MB/s
 * ```
 *
 * That is the difference between a player that can build a buffer and one that
 * can only just keep up, and it is why a stall never recovers into a cushion:
 * there is no spare bandwidth to catch up with.
 *
 * [AudioCache] already fetches this way — it is why read-ahead can put a whole
 * track on disk in the time between songs. This puts the player's own reads on
 * the same footing, which matters for the track being listened to right now:
 * read-ahead deliberately never touches it (Media3 locks a cache entry to one
 * writer), so without this it is the one track that always streams throttled.
 *
 * Transparent to everything above it. [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * sees one continuous stream of the length it asked for, and a request that is
 * already bounded — every read-ahead fetch — is passed straight through
 * untouched rather than chunked twice.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false

    /** Set when the request can't be improved on, and is simply forwarded. */
    private var passthrough = false
    /**
     * When a bounded cellular range is rescued by reopening the same signed URL
     * as an open-ended request, keep honoring the caller's original byte budget.
     * Without this guard a 384 KiB queue warm could silently turn into a whole-track
     * fetch just because googlevideo rejected the synthetic bounded range.
     */
    private var passthroughRemaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * The total size has to be known up front, or there is no way to say where
     * the last range ends. Every progressive googlevideo URL carries it as
     * `clen`, which costs nothing to read; anything else is forwarded as-is.
     */
    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position

        // Chunked range reads are an optimization for YouTube's googlevideo
        // carriers. Lossless sources (TIDAL/addons/modules) may expose a clen
        // query parameter too, but their signed URLs can reject the synthetic
        // byte ranges with HTTP 403. Preserve their original request instead of
        // turning a valid Lossless URL into a range request the source did not
        // authorize.
        val isGoogleVideo = dataSpec.uri.host
            ?.endsWith("googlevideo.com", ignoreCase = true) == true
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (!isGoogleVideo || dataSpec.length != C.LENGTH_UNSET.toLong() || total == null) {
            passthrough = true
            chunkOpen = true
            passthroughRemaining = dataSpec.length
            // Reported, not just thrown. Nothing but googlevideo carries `clen`,
            // so every module stream comes through this branch — and this was
            // the one open in the app whose refusal was neither logged nor
            // handed anywhere. A dead Tidal URL surfaced as a bare
            // ExoPlaybackException with not one line naming the server that
            // produced it or the status it produced.
            try {
                val opened = upstream.open(dataSpec)
                if (passthroughRemaining == C.LENGTH_UNSET.toLong()) {
                    passthroughRemaining = opened
                }
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else opened
            } catch (e: Exception) {
                // googlevideo can accept the signed URL but reject an app-synthesised
                // *bounded* range on either Wi-Fi or cellular. Before poisoning the carrier,
                // retry the exact same URL from the exact same position as an open-ended range.
                // OkHttp will send `bytes=<position>-`, which is closer to the stock player's
                // request shape and is a reliability fallback when the fast ranged path is refused.
                if (tryOpenEndedRangeRescue(dataSpec, dataSpec.length, e)) {
                    return dataSpec.length
                }
                if (e !is InterruptedIOException) {
                    // `host` is null exactly when the URL was too broken to
                    // parse, which is the case most in need of naming — so fall
                    // back to the string itself rather than logging "null".
                    val who = dataSpec.uri.host
                        ?.takeIf { it.isNotBlank() }
                        ?: dataSpec.uri.toString().take(120).ifBlank { "<empty-uri>" }
                    TrackLog.w(TAG, "$who refused the stream: ${e.message}")
                    report(dataSpec, e)
                }
                throw e
            }
        }

        passthrough = false
        bytesRemaining = (total - position).coerceAtLeast(0L)
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val effectiveChunkBytes = if (AppSettings.meteredConnection.value == true) {
            minOf(chunkBytes, METERED_CHUNK_BYTES)
        } else {
            chunkBytes
        }
        val length = minOf(effectiveChunkBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            upstream.open(spec)
        } catch (e: Exception) {
            // A bounded range refusal is not yet proof that the signed carrier is dead.
            // Some googlevideo sessions reject `bytes=a-b` while continuing to serve
            // `bytes=a-`, on Wi-Fi as well as cellular. Fall back in-place before escalating
            // the URL to StreamResolver, so playback can keep its buffer and position.
            if (tryOpenEndedRangeRescue(spec, bytesRemaining, e)) {
                chunkRemaining = 0L
                return
            }
            // Which client minted the URL is the first thing worth knowing when
            // a range is refused, and it isn't recoverable from anywhere else by
            // the time this surfaces as a playback error. A cancelled read-ahead
            // arrives here too and means nothing — see [AudioCache.prefetchQueue].
            if (e !is InterruptedIOException) {
                TrackLog.w(
                    TAG,
                    "range $position-${position + length - 1} refused for " +
                        "${spec.uri.getQueryParameter("c")}: ${e.message}",
                )
                // This is the only place a refusal of the *real* fetch is seen.
                // Left here it is just a failed track; handed back, it is the
                // one piece of evidence that retiring the client rests on.
                report(spec, e)
            }
            throw e
        }
        chunkRemaining = length
        chunkOpen = true
    }

    /**
     * Hands a refusal back to whoever minted the URL that was refused.
     *
     * [StreamResolver] sorts out whether the URL was one of its own — see
     * [StreamResolver.onPlaybackRefused]. Anything that isn't a refusal, or
     * isn't from a client it models, costs nothing here.
     */
    private fun report(spec: DataSpec, e: Exception) {
        if (e is HttpDataSource.InvalidResponseCodeException) {
            StreamResolver.onPlaybackRefused(spec.uri.toString(), e.responseCode)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return readPassthrough(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // A range that ends early is re-opened for the part that didn't
        // arrive, which is also how the step to the next range happens. The
        // attempt limit is what stops a server that has decided to send
        // nothing from spinning here forever.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
                if (passthrough) return readPassthrough(buffer, offset, length)
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun readPassthrough(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthroughRemaining == 0L) return C.RESULT_END_OF_INPUT
        val allowed = if (passthroughRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), passthroughRemaining).toInt()
        }
        if (allowed <= 0) return C.RESULT_END_OF_INPUT
        val read = upstream.read(buffer, offset, allowed)
        if (read > 0 && passthroughRemaining != C.LENGTH_UNSET.toLong()) {
            passthroughRemaining = (passthroughRemaining - read).coerceAtLeast(0L)
        }
        return read
    }

    /**
     * Compatibility escape for googlevideo's inconsistent bounded Range handling.
     * A successful rescue deliberately does not call [report]: the carrier itself
     * is usable, only the synthetic `a-b` request was refused. This intentionally
     * applies on Wi-Fi too; the same refusal pattern is visible there, and making
     * recovery depend on the network type turns an identical valid carrier into
     * a spinner on one connection and a playing stream on another.
     */
    private fun tryOpenEndedRangeRescue(
        failedSpec: DataSpec,
        remainingBudget: Long,
        failure: Exception,
    ): Boolean {
        if (failure !is HttpDataSource.InvalidResponseCodeException || failure.responseCode != 403) return false
        if (failedSpec.uri.host?.endsWith("googlevideo.com", ignoreCase = true) != true) return false
        if (failedSpec.length == C.LENGTH_UNSET.toLong()) return false

        runCatching { upstream.close() }
        val rescueSpec = failedSpec.buildUpon()
            .setPosition(failedSpec.position)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        return try {
            upstream.open(rescueSpec)
            passthrough = true
            chunkOpen = true
            passthroughRemaining = remainingBudget
            TrackLog.w(
                TAG,
                "bounded range at ${failedSpec.position} refused; " +
                    "continuing same carrier with open-ended range",
            )
            true
        } catch (rescueError: Exception) {
            if (rescueError !is InterruptedIOException) {
                TrackLog.w(
                    TAG,
                    "open-ended range rescue also failed for ${failedSpec.uri.host}: ${rescueError.message}",
                )
            }
            false
        }
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
        passthroughRemaining = C.LENGTH_UNSET.toLong()
        passthrough = false
    }

    private companion object {
        const val TAG = "Orb"

        /**
         * Smaller cellular ranges reduce first-response cost and avoid googlevideo
         * sessions that accept modest byte ranges but reject 2 MiB requests.
         */
        const val METERED_CHUNK_BYTES = 512L * 1024L

        /** Enough to ride out a truncated range, not enough to hang on a dead one. */
        const val MAX_EMPTY_RANGES = 3
    }

    /** Wraps [upstream]'s sources so everything opened through it is ranged. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }
}
