package com.music.orb.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthorizationPolicyTest {
    @Test fun malformedPrivateRequestDoesNotRevokeGoogleAuthorization() {
        assertFalse(YouTubeAuthorizationPolicy.isPrivateOAuthRejection(400))
    }

    @Test fun unauthorizedAndForbiddenPrivateRequestsTriggerFallback() {
        assertTrue(YouTubeAuthorizationPolicy.isPrivateOAuthRejection(401))
        assertTrue(YouTubeAuthorizationPolicy.isPrivateOAuthRejection(403))
        assertFalse(YouTubeAuthorizationPolicy.isPrivateOAuthRejection(429))
        assertFalse(YouTubeAuthorizationPolicy.isPrivateOAuthRejection(500))
    }

    @Test fun privateRetryIsTemporary() {
        val now = 10_000L
        assertEquals(now + 600_000L, YouTubeAuthorizationPolicy.nextPrivateRetryAt(now))
        assertEquals(Long.MAX_VALUE,
            YouTubeAuthorizationPolicy.nextPrivateRetryAt(Long.MAX_VALUE - 1L))
    }
}
