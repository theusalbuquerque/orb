package com.music.orb.data

import com.music.orb.data.innertube.InnertubeParser
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InnertubeArtistSearchIdentityTest {
    @Test
    fun topResultCanCarryCollaboratorLabelWithVANESSAChannel() {
        // A mixed YouTube Music "Top result" may pair a song collaborator's
        // display label with the actual artist's browse destination. The
        // repository must verify the landing header before publishing the card.
        val response = Json.parseToJsonElement(
            """{
                "contents": [{
                    "musicCardShelfRenderer": {
                        "title": {"runs": [{
                            "text": "Ben Harper",
                            "navigationEndpoint": {"browseEndpoint": {
                                "browseId": "UC_VANESSA_DA_MATA",
                                "browseEndpointContextSupportedConfigs": {
                                    "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}
                                }
                            }}
                        }]},
                        "subtitle": {"runs": [{"text": "Artist"}]}
                    }
                }]
            }""".trimIndent(),
        ).jsonObject
        val card = InnertubeParser.parseSearch(response)
            .filterIsInstance<SearchResult.Browse>().single().item
        assertEquals("Ben Harper", card.title)
        assertEquals("UC_VANESSA_DA_MATA", card.browseId)
        assertEquals(
            ArtistProfileIdentity.SearchMismatch.USE_LANDING_IDENTITY,
            ArtistProfileIdentity.searchMismatch("vanessa da mata", card.title, "Vanessa da Mata"),
        )
    }

    @Test
    fun titleLinkedArtistWithoutPageTypeStillWinsOverCollaborator() {
        val response = Json.parseToJsonElement(
            """{
                "contents": [{
                    "musicResponsiveListItemRenderer": {
                        "navigationEndpoint": {
                            "browseEndpoint": {
                                "browseId": "UC_BEN_HARPER",
                                "browseEndpointContextSupportedConfigs": {
                                    "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}
                                }
                            }
                        },
                        "flexColumns": [{
                            "musicResponsiveListItemFlexColumnRenderer": {
                                "text": {"runs": [{
                                    "text": "Vanessa da Mata",
                                    "navigationEndpoint": {"browseEndpoint": {
                                        "browseId": "UC_VANESSA_DA_MATA"
                                    }}
                                }]}
                            }
                        }]
                    }
                }]
            }""".trimIndent(),
        ).jsonObject
        val item = InnertubeParser.parseSearch(response)
            .filterIsInstance<SearchResult.Browse>()
            .single().item
        assertEquals("Vanessa da Mata", item.title)
        assertEquals("UC_VANESSA_DA_MATA", item.browseId)
        assertEquals(BrowseType.ARTIST, item.type)
    }

    @Test
    fun artistTitleLinkWinsOverIncorrectParentCollaboratorEndpoint() {
        // Regression: a visible Vanessa da Mata row must not open a parent
        // renderer's Ben Harper browseId when the title has her own endpoint.
        val response = Json.parseToJsonElement(
            """{
                "contents": [{
                    "musicResponsiveListItemRenderer": {
                        "navigationEndpoint": {
                            "browseEndpoint": {
                                "browseId": "UC_BEN_HARPER",
                                "browseEndpointContextSupportedConfigs": {
                                    "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}
                                }
                            }
                        },
                        "flexColumns": [{
                            "musicResponsiveListItemFlexColumnRenderer": {
                                "text": {"runs": [{
                                    "text": "Vanessa da Mata",
                                    "navigationEndpoint": {
                                        "browseEndpoint": {
                                            "browseId": "UC_VANESSA_DA_MATA",
                                            "browseEndpointContextSupportedConfigs": {
                                                "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}
                                            }
                                        }
                                    }
                                }]}
                            }
                        }]
                    }
                }]
            }""".trimIndent(),
        ).jsonObject
        val result = InnertubeParser.parseSearch(response)
            .filterIsInstance<SearchResult.Browse>()
            .single().item
        assertEquals(BrowseType.ARTIST, result.type)
        assertEquals("Vanessa da Mata", result.title)
        assertEquals("UC_VANESSA_DA_MATA", result.browseId)
    }
}
