package com.music.orb.data.sources.addon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces identical addon calls without tying the backend work to one caller.
 * Successful answers can be reused for [ttlMs]; failures are forgotten so the
 * next playback/prefetch attempt can recover immediately.
 */
internal class SharedCalls<T>(
    private val ttlMs: Long,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<T>(
        val work: Deferred<Result<T>>,
        val startedAtMs: Long,
    )

    private val lock = Mutex()
    private val entries = ConcurrentHashMap<String, Entry<T>>()

    suspend fun get(key: String, produce: suspend () -> Result<T>): Result<T> {
        val now = nowMs()
        val entry = lock.withLock {
            val existing = entries[key]
            val reusable = existing?.takeIf {
                it.work.isActive || (ttlMs > 0L && it.work.isCompleted && now - it.startedAtMs < ttlMs)
            }
            if (reusable != null) {
                reusable
            } else {
                if (existing != null) entries.remove(key, existing)
                trimCompletedLocked(now)
                Entry(scope.async { produce() }, now).also { entries[key] = it }
            }
        }

        val result = try {
            entry.work.await()
        } catch (failure: Throwable) {
            forgetIfSame(key, entry)
            throw failure
        }
        if (result.isFailure) forgetIfSame(key, entry)
        return result
    }

    /** Forget cache entries without cancelling work another caller may await. */
    fun clear() = entries.clear()

    private suspend fun forgetIfSame(key: String, entry: Entry<T>) {
        lock.withLock { entries.remove(key, entry) }
    }

    private fun trimCompletedLocked(now: Long) {
        entries.entries.removeIf { (_, entry) ->
            entry.work.isCompleted && (ttlMs <= 0L || now - entry.startedAtMs >= ttlMs)
        }
        if (entries.size < MAX_ENTRIES) return
        entries.entries
            .asSequence()
            .filter { !it.value.work.isActive }
            .sortedBy { it.value.startedAtMs }
            .take((entries.size - MAX_ENTRIES + 1).coerceAtLeast(0))
            .map { it.key }
            .toList()
            .forEach(entries::remove)
    }

    private companion object {
        const val MAX_ENTRIES = 128
    }
}
