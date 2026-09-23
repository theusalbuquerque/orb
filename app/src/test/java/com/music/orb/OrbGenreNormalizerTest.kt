package com.music.orb

import com.music.orb.data.canvas.normalizeOrbGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbGenreNormalizerTest {
    @Test
    fun preservesCatalogSubgenresInsteadOfFlatteningThem() {
        assertEquals("Dance Pop", normalizeOrbGenre("dance pop"))
        assertEquals("Electropop", normalizeOrbGenre("electropop"))
        assertEquals("Pop Rock", normalizeOrbGenre("pop rock"))
        assertEquals("Alternative R&B", normalizeOrbGenre("alternative r&b"))
        assertEquals("Brazilian Funk", normalizeOrbGenre("brazilian funk"))
        assertEquals("Sertanejo Universitario", normalizeOrbGenre("sertanejo universitario"))
    }

    @Test
    fun keepsBroadElectronicGenresDistinct() {
        assertEquals("Electronic", normalizeOrbGenre("electronic"))
        assertEquals("Electronica", normalizeOrbGenre("electronica"))
        assertEquals("Dance", normalizeOrbGenre("dance"))
        assertEquals("EDM", normalizeOrbGenre("edm"))
        assertEquals("House", normalizeOrbGenre("house"))
    }

    @Test
    fun normalizesCommonSpotifyAndAppleAliases() {
        assertEquals("K-Pop", normalizeOrbGenre("kpop"))
        assertEquals("Hip-Hop", normalizeOrbGenre("hip hop"))
        assertEquals("Hip-Hop/Rap", normalizeOrbGenre("hip-hop / rap"))
        assertEquals("R&B/Soul", normalizeOrbGenre("r&b / soul"))
        assertEquals("MPB", normalizeOrbGenre("musica popular brasileira"))
    }

    @Test
    fun acceptsNewCatalogGenresWithoutAnAllowListUpdate() {
        assertEquals("Afrobeats", normalizeOrbGenre("afrobeats"))
        assertEquals("Baile Funk", normalizeOrbGenre("baile funk"))
        assertEquals("Neo Soul", normalizeOrbGenre("neo soul"))
        assertEquals("UK Garage", normalizeOrbGenre("uk garage"))
    }

    @Test
    fun ignoresOnlyGenericNonGenreLabels() {
        assertNull(normalizeOrbGenre("Music"))
        assertNull(normalizeOrbGenre("All Genres"))
        assertNull(normalizeOrbGenre("unknown"))
    }
}
