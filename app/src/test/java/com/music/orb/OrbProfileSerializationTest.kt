package com.music.orb

import com.music.orb.data.social.OrbProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbProfileSerializationTest {
    @Test
    fun acceptsNewSupabaseColumns() {
        val response = """{
            "id":"account-123",
            "username":"matheus",
            "display_name":"Matheus",
            "avatar_url":null,
            "recent_ratings_visibility":"followers",
            "created_at":"2026-10-22T12:00:00Z",
            "updated_at":"2026-10-22T12:00:00Z"
        }""".trimIndent()

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val profile = json.decodeFromString<OrbProfile>(response)

        assertEquals("account-123", profile.id)
        assertEquals("Matheus", profile.displayName)
        assertEquals("followers", profile.recentRatingsVisibility)
    }

    @Test
    fun nullableServerValueUsesModelDefault() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val profile = json.decodeFromString<OrbProfile>(
            """{"id":"account-123","display_name":null}"""
        )

        assertEquals("", profile.displayName)
    }
}
