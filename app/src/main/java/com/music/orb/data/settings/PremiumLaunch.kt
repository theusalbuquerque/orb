package com.music.orb.data.settings

import java.time.LocalDateTime

object PremiumLaunch {
    /**
     * Target launch date and time: November 1, 2026 at 08:00 AM in the user's local time zone.
     */
    private val LAUNCH_DATE_TIME: LocalDateTime = LocalDateTime.of(2026, 11, 1, 8, 0)

    /** Owner/developer: entitlement behavior remains unchanged. */
    const val DEVELOPER_EMAIL = "theusalbuquerque98@gmail.com"

    /** Invitation to view and purchase Premium early; never grants entitlement. */
    private const val EARLY_OFFER_EMAIL = "theusalboficial@gmail.com"

    /** Offer visibility, not a Premium entitlement or permission to use locked features. */
    fun isLaunchTime(
        email: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean = isDeveloper(email) || isEarlyOfferAccount(email) || isPublicLaunchTime(now)

    /** Early offer account can subscribe normally, but starts as a free account. */
    fun isEarlyOfferAccount(email: String?): Boolean =
        email?.trim()?.lowercase() == EARLY_OFFER_EMAIL


    /** Public launch uses the device's LOCAL wall clock, not server UTC or a fixed timezone. */
    fun isPublicLaunchTime(now: LocalDateTime = LocalDateTime.now()): Boolean =
        !now.isBefore(LAUNCH_DATE_TIME)

    /** A non-subscriber can view Premium controls after launch but cannot use them. */
    fun isLockedAfterLaunch(
        premiumEntitled: Boolean,
        isOwner: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean = isPublicLaunchTime(now) && !premiumEntitled && !isOwner

    /** Returns true if the account matches the developer account. */
    fun isDeveloper(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase().orEmpty()
        return normalized == DEVELOPER_EMAIL
    }
}
