package com.music.orb.playback

import androidx.media3.common.Player
import com.music.orb.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shuffle as an edit to the queue, not a playback mode.
 *
 * ExoPlayer's own `shuffleModeEnabled` leaves the queue exactly as it is and
 * draws the next track from a hidden random order, so the queue panel shows
 * one running order while the player follows another — the user sees the album
 * listed in order and hears it jumping about. Toggling shuffle here rearranges
 * the queue itself and leaves playback strictly sequential: what the queue
 * shows is what plays, in that order.
 *
 * The order the queue was in beforehand is kept so the toggle can be undone.
 * The player's own shuffle mode is deliberately never enabled — it would
 * randomise on top of the order set here.
 *
 * AutoPlay's tracks are shuffled among themselves and stay below the ones the
 * user queued, which is where the player's AutoPlay section shows them: a
 * shuffle is not a reason for a mix to start cutting in front of the album.
 */
object QueueShuffle {

    private val _enabled = MutableStateFlow(false)

    /** Whether the queue is currently held in shuffled order. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Media ids in their pre-shuffle order. Empty while shuffle is off. */
    private var original: List<String> = emptyList()

    /**
     * One-shot intent used only when a NEW queue is about to be created from an
     * album/playlist Shuffle button. The live [enabled] state must not double as
     * this intent: otherwise Shuffle from the previous queue leaks into a later
     * album/playlist started with its normal Play button.
     */
    private var shuffleNextQueue = false

    fun toggle(player: Player) {
        shuffleNextQueue = false
        if (_enabled.value) restore(player) else shuffle(player)
    }

    /**
     * Turns shuffle on without touching the current queue — for the Shuffle
     * button on an album or playlist page, where the queue it applies to is the
     * one about to replace this one. [playSongs] builds that one shuffled.
     */
    fun enableForNextQueue() {
        original = emptyList()
        shuffleNextQueue = true
        // Reflect the button press immediately in Now Playing while the new
        // queue is resolving. playSongs() will consume the one-shot request.
        _enabled.value = true
    }

    /** Whether the next replacement queue was explicitly launched through Shuffle. */
    fun isNextQueueShuffleRequested(): Boolean = shuffleNextQueue

    /**
     * Reasserts the visible Shuffle state after a replacement shuffled queue has
     * actually been installed in Media3. The queue itself is already physically
     * shuffled; this flag exists so Now Playing mirrors how the current session
     * was launched instead of relying on ExoPlayer's native shuffle mode (which
     * intentionally stays disabled).
     */
    fun confirmActiveQueueShuffled() {
        shuffleNextQueue = false
        _enabled.value = true
    }

    /**
     * Consumes the one-shot Shuffle request for a replacement queue.
     *
     * No request means the new queue was started through ordinary Play, so its
     * Shuffle state is reset even if the queue that is being replaced happened
     * to be shuffled. This is intentionally different from toggling Shuffle on
     * the CURRENT queue, where [enabled] continues to describe that live queue.
     */
    fun consumeForNewQueue(): Boolean {
        val requested = shuffleNextQueue
        shuffleNextQueue = false
        original = emptyList()
        _enabled.value = requested
        return requested
    }

    /** Clearing the queue leaves one current item, so there is no shuffled order left to own. */
    fun onQueueCleared() {
        original = emptyList()
        shuffleNextQueue = false
        _enabled.value = false
    }

    /**
     * Order for a queue started while Shuffle is on. The picked track leads
     * that the opening preflight may replace with a better first track. The
     * original list is remembered so turning Shuffle off can restore it.
     */
    fun startingOrder(songs: List<Song>, startIndex: Int): List<Song> {
        original = songs.map { it.videoId }
        val rest = songs.filterIndexed { i, _ -> i != startIndex }.shuffled()
        return listOf(songs[startIndex]) + rest
    }

    /**
     * Rearranges everything after the playing track. That track keeps playing,
     * and whatever sits above it stays there — those have had their turn.
     */
    private fun shuffle(player: Player) {
        original = player.queueIds()
        val from = player.currentMediaItemIndex + 1
        val autoplay = player.autoplayIds()
        val (mix, own) = original.drop(from).partition { it in autoplay }
        applyOrder(player, from, own.shuffled() + mix.shuffled())
        _enabled.value = true
    }

    /** Puts the tracks still to come back into the order they were queued in. */
    private fun restore(player: Player) {
        val from = player.currentMediaItemIndex + 1
        val upcoming = player.queueIds().drop(from).toMutableList()
        // Each track still queued goes back to where it stood in the old order.
        // Whatever is left over was queued after the shuffle and was never part
        // of that order, so it keeps its place at the end.
        val restored = original.filter { upcoming.remove(it) } + upcoming
        applyOrder(player, from, sections(restored, player.autoplayIds()))
        original = emptyList()
        _enabled.value = false
    }

    /** [ids] with AutoPlay's tracks moved below the user's, order otherwise kept. */
    private fun sections(ids: List<String>, autoplay: Set<String>): List<String> =
        ids.filterNot { it in autoplay } + ids.filter { it in autoplay }

    /**
     * Rearranges the live queue from [from] onwards into [target], one move at
     * a time. Moving items leaves the playing track's own source untouched;
     * setting the queue afresh would restart it — and re-resolve its stream.
     */
    private fun applyOrder(player: Player, from: Int, target: List<String>) {
        moves(player.queueIds(), from, target).forEach { (at, to) ->
            player.moveMediaItem(at, to)
        }
    }

    /**
     * The moves that take [current] into [target] from [from] onwards, each a
     * `from index to index` pair as [Player.moveMediaItem] takes them — the
     * item at the first index lands on the second, the rest shifting along.
     *
     * Only the positions [target] names are placed; anything it doesn't
     * mention is left to trail behind them, so a queue edited from under this
     * comes out rearranged as far as it can be rather than not at all.
     */
    internal fun moves(
        current: List<String>,
        from: Int,
        target: List<String>,
    ): List<Pair<Int, Int>> {
        val ids = current.toMutableList()
        val out = mutableListOf<Pair<Int, Int>>()
        target.forEachIndexed { offset, id ->
            val to = from + offset
            if (ids.getOrNull(to) == id) return@forEachIndexed
            val at = (to + 1 until ids.size).firstOrNull { ids[it] == id }
                ?: return@forEachIndexed
            out += at to to
            ids.add(to, ids.removeAt(at))
        }
        return out
    }

    private fun Player.queueIds(): List<String> =
        (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    private fun Player.autoplayIds(): Set<String> =
        (0 until mediaItemCount)
            .filter { getMediaItemAt(it).fromAutoplay }
            .mapTo(mutableSetOf()) { getMediaItemAt(it).mediaId }
}
