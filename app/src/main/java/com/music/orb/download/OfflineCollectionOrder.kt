package com.music.orb.download

/** Never order a downloaded collection by completion time, file name or map iteration. */
internal fun <T> orderedOfflineItems(ids: List<String>, availableById: Map<String, T>): List<T> =
    ids.mapNotNull(availableById::get)
