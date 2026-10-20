package com.music.orb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistProfileIdentityTest {
    @Test fun sameArtistWithCaseAndAccents() {
        assertTrue(ArtistProfileIdentity.matches("Vanessa da Mata", "vanéssa DA mata"))
        assertTrue(ArtistProfileIdentity.matches("Beyoncé", "Beyonce"))
    }

    @Test fun differentArtistAndCollaborationMustNotMatch() {
        assertFalse(ArtistProfileIdentity.matches("Vanessa da Mata", "Ben Harper"))
        assertFalse(ArtistProfileIdentity.matches("Vanessa da Mata", "Vanessa da Mata & Ben Harper"))
        assertFalse(ArtistProfileIdentity.matches("Michael Jackson", "Michael!"))
        assertFalse(ArtistProfileIdentity.matches("Anitta", "Anitta Souza"))
    }

    @Test fun searchResultLabeledBenHarperButVanessaPageIsCorrected() {
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.USE_LANDING_IDENTITY,
            ArtistProfileIdentity.searchMismatch("Vanessa da Mata", "Ben Harper", "Vanessa da Mata"),
        )
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.USE_LANDING_IDENTITY,
            ArtistProfileIdentity.searchMismatch("vanessa", "Ben Harper", "Vanessa da Mata"),
        )
    }

    @Test fun wronglyLinkedVanessaCardRecoversRatherThanRenames() {
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.RECOVER_CARD_IDENTITY,
            ArtistProfileIdentity.searchMismatch("Vanessa da Mata", "Vanessa da Mata", "Ben Harper"),
        )
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.NONE,
            ArtistProfileIdentity.searchMismatch("Ben Harper", "Ben Harper", "Ben Harper"),
        )
    }

    @Test fun unrelatedQueryUsesActualPageIdentity() {
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.USE_LANDING_IDENTITY,
            ArtistProfileIdentity.searchMismatch("música popular brasileira", "Ben Harper", "Vanessa da Mata"),
        )
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.NONE,
            ArtistProfileIdentity.searchMismatch("Vanessa", "Ben Harper", null),
        )
    }

    @Test fun mislabelledFeaturedVanessaDetectedBySecondIndependentBenChannel() {
        assertEquals("Vanessa da Mata", ArtistProfileIdentity.featuredQueryCorrection(
            "vanessa da mata", "Ben Harper", "UC_VANESSA",
            listOf("Ben Harper" to "UC_REAL_BEN", "Seu Jorge" to "UC_JORGE"),
            emptyList(),
        ))
    }

    @Test fun mislabelledFeaturedVanessaDetectedByMultiplePrimaryCreditsInAllSearch() {
        assertEquals("Vanessa da Mata", ArtistProfileIdentity.featuredQueryCorrection(
            "vanessa da mata", "Ben Harper", "UC_VANESSA",
            emptyList(),
            listOf("Vanessa Da Mata", "Vanessa da Mata", "Vanessa da Mata feat. Ben Harper"),
        ))
    }

    @Test fun genuineBenHarperRemainsUnchanged() {
        assertEquals(null, ArtistProfileIdentity.featuredQueryCorrection(
            "ben harper", "Ben Harper", "UC_REAL_BEN",
            listOf("Ben Harper" to "UC_OTHER"),
            listOf("Ben Harper", "Ben Harper"),
        ))
    }

    @Test fun unrelatedOrPartialQueriesCannotRelabelArtists() {
        assertEquals(null, ArtistProfileIdentity.featuredQueryCorrection(
            "vanessa", "Ben Harper", "UC_REAL_BEN",
            listOf("Ben Harper" to "UC_OTHER"), emptyList(),
        ))
        assertEquals(null, ArtistProfileIdentity.featuredQueryCorrection(
            "vanessa da mata", "Ben Harper", "UC_REAL_BEN",
            emptyList(), listOf("Ben Harper", "Ben Harper"),
        ))
    }

    @Test fun missingMetadataMustNotProveIdentity() {
        assertFalse(ArtistProfileIdentity.matches("Vanessa da Mata", null))
        assertFalse(ArtistProfileIdentity.matches("Vanessa da Mata", ""))
        assertFalse(ArtistProfileIdentity.matches("", "Ben Harper"))
    }
}
