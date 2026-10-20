package com.music.orb.playback

/** Classify task-removal callbacks without coupling the policy to Android types. */
internal object OrbLockscreenTaskRemovalPolicy {
    fun isTransientLockscreenRemoval(
        removedComponent: String?,
        lockscreenComponent: String,
        recentlyFinished: Boolean,
    ): Boolean =
        // An explicit lockscreen root always wins, even if an OEM delivers
        // onTaskRemoved after the short unlock grace period.
        removedComponent == lockscreenComponent ||
            // With a missing component, a recent lockscreen exit is the only
            // safe evidence that this callback belongs to its temporary task.
            (removedComponent == null && recentlyFinished)

    /**
     * The callback can belong to a secondary Orb task, and some OEMs don't
     * populate rootIntent.component at all. Never interpret an unidentified
     * removal as a deliberate swipe of the actual application task: doing so
     * releases both ExoPlayers and interrupts background playback.
     *
     * The main-task policy remains unchanged when Android supplies its root.
     */
    fun isConfirmedMainTaskRemoval(
        removedComponent: String?,
        mainComponent: String,
    ): Boolean = removedComponent == mainComponent

    /** Only Premium opt-in keeps an actively playing/buffering session after a main-task swipe. */
    fun shouldPreserveMainTaskPlayback(
        premiumEntitled: Boolean,
        optedIn: Boolean,
        playbackOngoing: Boolean,
        playbackRequested: Boolean,
    ): Boolean = premiumEntitled && optedIn && (playbackOngoing || playbackRequested)
}
