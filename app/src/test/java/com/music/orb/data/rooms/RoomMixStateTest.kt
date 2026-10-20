package com.music.orb.data.rooms

import com.music.orb.playback.smart.TransitionPlan
import com.music.orb.playback.smart.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMixStateTest {
    private fun room(queueVersion: Long = 7L) = ListeningRoom(
        id = "11111111-2222-3333-4444-555555555555", title = "Session", code = "ABC123",
        maxGuests = 4, coverPath = null, coverUrl = null, canAdd = true,
        membersCanAdd = true, host = false, hostOnline = true,
        queue = emptyList(), queueVersion = queueVersion, index = 0, positionMs = 0,
        playing = true, serverMs = 0, playbackAtMs = 0, receivedAtMs = 0,
        halfRoundTripMs = 0, createdAtMs = 0, members = emptyList(),
    )

    private val a = "AAAAAAAAAAA"
    private val b = "BBBBBBBBBBB"
    private val c = "CCCCCCCCCCC"
    private val first = TransitionPlan(
        transitionStart = 100.0, transitionEnd = 110.0, fadeSeconds = 10.0,
        transitionStyle = TransitionStyle.DJ_BLEND,
    )
    private val second = first.copy(transitionStyle = TransitionStyle.INTRO_BED)

    @Test fun hostPlanOverridesLocalVersionAndCanRetainPreviousHandoff() {
        val room = room()
        val state = RoomMixState(room.id, room.queueVersion, enabled = true, version = "2.5",
            history = listOf(RoomMixRecipe(a, b, first), RoomMixRecipe(b, c, second)))
        assertEquals(first, state.planFor(room, a, b))
        assertEquals(second, state.planFor(room, b, c))
        assertNull(state.planFor(room, c, a))
    }

    @Test fun disabledHostNeverMixesEvenIfGuestEnabledLocally() {
        val room = room()
        val state = RoomMixState(room.id, room.queueVersion, enabled = false, version = "2.5",
            history = listOf(RoomMixRecipe(a, b, first)))
        assertNull(state.planFor(room, a, b))
    }

    @Test fun queueEditsInvalidateOldHostPlans() {
        val room = room()
        val state = RoomMixState(room.id, room.queueVersion, enabled = true, version = "2.0",
            history = listOf(RoomMixRecipe(a, b, first)))
        assertNull(state.planFor(room(queueVersion = 8L), a, b))
    }

    @Test fun malformedOrBlockedPlanIsNeverApplied() {
        val room = room()
        val state = RoomMixState(room.id, room.queueVersion, enabled = true, version = "2.5",
            history = listOf(RoomMixRecipe(a, b, first.copy(blocked = true))))
        assertNull(state.planFor(room, a, b))
    }
}
