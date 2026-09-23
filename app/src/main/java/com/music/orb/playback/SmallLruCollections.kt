package com.music.orb.playback

import java.util.LinkedHashMap

/**
 * Tiny synchronized access-order collections for short-lived playback/analysis metadata.
 *
 * Android's audio pipeline touches these from the playback thread, coroutine workers and
 * analyzer executors. Keeping the implementation here avoids turning every caller into its
 * own locking policy while still giving caches a hard upper bound.
 */
internal class SmallLruMap<K, V>(
    private val maxEntries: Int,
    private val onEvicted: ((K, V) -> Unit)? = null,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val lock = Any()
    private val map = LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true)

    operator fun get(key: K): V? = synchronized(lock) { map[key] }

    fun containsKey(key: K): Boolean = synchronized(lock) { map.containsKey(key) }

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun put(key: K, value: V): V? {
        var evicted: Pair<K, V>? = null
        val previous = synchronized(lock) {
            val old = map.put(key, value)
            if (map.size > maxEntries) {
                val iterator = map.entries.iterator()
                if (iterator.hasNext()) {
                    val eldest = iterator.next()
                    evicted = eldest.key to eldest.value
                    iterator.remove()
                }
            }
            old
        }
        evicted?.let { (evictedKey, evictedValue) -> onEvicted?.invoke(evictedKey, evictedValue) }
        return previous
    }

    fun putIfAbsent(key: K, value: V): V? {
        var evicted: Pair<K, V>? = null
        val previous = synchronized(lock) {
            map[key]?.let { return@synchronized it }
            map[key] = value
            if (map.size > maxEntries) {
                val iterator = map.entries.iterator()
                if (iterator.hasNext()) {
                    val eldest = iterator.next()
                    evicted = eldest.key to eldest.value
                    iterator.remove()
                }
            }
            null
        }
        evicted?.let { (evictedKey, evictedValue) -> onEvicted?.invoke(evictedKey, evictedValue) }
        return previous
    }

    fun update(key: K, transform: (V?) -> V): V {
        var evicted: Pair<K, V>? = null
        val updated = synchronized(lock) {
            val value = transform(map[key])
            map[key] = value
            if (map.size > maxEntries) {
                val iterator = map.entries.iterator()
                if (iterator.hasNext()) {
                    val eldest = iterator.next()
                    evicted = eldest.key to eldest.value
                    iterator.remove()
                }
            }
            value
        }
        evicted?.let { (evictedKey, evictedValue) -> onEvicted?.invoke(evictedKey, evictedValue) }
        return updated
    }

    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }

    fun clear() = synchronized(lock) { map.clear() }

    fun size(): Int = synchronized(lock) { map.size }
}

internal class SmallLruSet<K>(maxEntries: Int) {
    private val values = SmallLruMap<K, Unit>(maxEntries)

    fun add(value: K): Boolean = values.putIfAbsent(value, Unit) == null

    fun remove(value: K): Boolean = values.remove(value) != null

    operator fun contains(value: K): Boolean = values[value] != null

    fun clear() = values.clear()

    fun size(): Int = values.size()
}
