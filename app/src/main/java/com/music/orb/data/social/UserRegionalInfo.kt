package com.music.orb.data.social

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Coarse regional metadata for account localization/analytics. No GPS, address,
 * coordinates or precise-location permission is used.
 */
data class UserRegionalInfo(
    val countryCode: String?,
    val languageCode: String,
    val localeTag: String,
) {
    companion object {
        fun detect(context: Context): UserRegionalInfo {
            val configuration = context.resources.configuration
            val locale = configuration.locales[0] ?: Locale.getDefault()
            val language = locale.language
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z]{2,3}")) }
                ?: "und"
            val localeTag = locale.toLanguageTag().takeIf { it.isNotBlank() } ?: language

            val telephony = context.getSystemService(TelephonyManager::class.java)
            val sim = runCatching { telephony?.simCountryIso }
                .getOrNull().normalizedCountry()
            val network = runCatching { telephony?.networkCountryIso }
                .getOrNull().normalizedCountry()
            val configured = locale.country.normalizedCountry()

            return UserRegionalInfo(
                countryCode = sim ?: network ?: configured,
                languageCode = language,
                localeTag = localeTag,
            )
        }

        private fun String?.normalizedCountry(): String? = this
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
    }
}
