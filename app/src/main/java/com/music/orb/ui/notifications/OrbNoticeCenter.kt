package com.music.orb.ui.notifications

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.random.Random

enum class OrbNoticeIcon {
    QUEUE,
    PLAY_NEXT,
    PLAYLIST,
    LIBRARY,
    DOWNLOAD,
    INFO,
}

data class OrbNotice(
    val title: String,
    val message: String? = null,
    val icon: OrbNoticeIcon = OrbNoticeIcon.INFO,
    val styleSeed: Int = Random.nextInt(),
)

/**
 * Small process-local queue for action feedback. A Channel is used instead of
 * collectLatest so rapid actions are shown in order rather than replacing one
 * another mid-bounce.
 */
object OrbNoticeCenter {
    private val channel = Channel<OrbNotice>(capacity = Channel.UNLIMITED)
    val notices: Flow<OrbNotice> = channel.receiveAsFlow()

    fun post(notice: OrbNotice) {
        channel.trySend(notice)
    }
}
