package com.music.orb.ui.components

import com.music.orb.R
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGreetingLocalizationTest {
    @Test fun usesMorningAfterFive() {
        assertEquals(R.string.expressive_greeting_overnight, homeGreetingResForHour(0))
        assertEquals(R.string.expressive_greeting_overnight, homeGreetingResForHour(4))
        assertEquals(R.string.expressive_greeting_morning, homeGreetingResForHour(5))
        assertEquals(R.string.expressive_greeting_morning, homeGreetingResForHour(11))
    }

    @Test fun usesAfternoonAndEveningAtCorrectHours() {
        assertEquals(R.string.expressive_greeting_afternoon, homeGreetingResForHour(12))
        assertEquals(R.string.expressive_greeting_afternoon, homeGreetingResForHour(17))
        assertEquals(R.string.expressive_greeting_evening, homeGreetingResForHour(18))
        assertEquals(R.string.expressive_greeting_evening, homeGreetingResForHour(23))
    }
}
