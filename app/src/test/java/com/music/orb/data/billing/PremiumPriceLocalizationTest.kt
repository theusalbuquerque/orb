package com.music.orb.data.billing

import com.music.orb.data.settings.OrbAppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regional currency must follow the chosen app country, not the text language. */
class PremiumPriceLocalizationTest {
    @Test fun supportedRegionsResolveNativeCurrency() {
        val expected = mapOf(
            "pt-BR" to "BRL", "pt-PT" to "EUR",
            "en-US" to "USD", "en-GB" to "GBP", "en-CA" to "CAD",
            "es-ES" to "EUR", "es-MX" to "MXN", "es-CO" to "COP",
            "fr-FR" to "EUR", "fr-CA" to "CAD",
            "de-DE" to "EUR", "it-IT" to "EUR",
            "ru-RU" to "RUB", "th-TH" to "THB", "hi-IN" to "INR",
        )
        expected.forEach { (tag, currency) ->
            assertEquals(tag, currency, PremiumPriceLocalization.localCurrency(tag))
        }
    }

    @Test fun countryFlagsComeFromRegionNotLanguage() {
        assertEquals("🇧🇷", OrbAppLanguage.flagForRegion("pt-BR"))
        assertEquals("🇵🇹", OrbAppLanguage.flagForRegion("pt-PT"))
        assertEquals("🇨🇦", OrbAppLanguage.flagForRegion("fr-CA"))
        assertEquals("🇲🇽", OrbAppLanguage.flagForRegion("es-MX"))
    }
}
