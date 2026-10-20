package com.music.orb.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Persisted locally and mirrored into the signed-in Orb account by AccountSettingsSync. */
object OrbAppLanguage {
    private const val PREFS = "orb_app_language"
    private const val KEY = "selected_locale"

    data class Region(val tag: String, val label: String)
    data class Language(val code: String, val label: String, val regions: List<Region>)

    val languages = listOf(
        Language("pt", "Português", listOf(Region("pt-BR", "Brasil"), Region("pt-PT", "Portugal"))),
        Language("en", "English", listOf(Region("en-US", "United States"), Region("en-GB", "United Kingdom"), Region("en-CA", "Canada"))),
        Language("es", "Español", listOf(Region("es-ES", "España"), Region("es-MX", "México"), Region("es-CO", "Colombia"))),
        Language("de", "Deutsch", listOf(Region("de-DE", "Deutschland"))),
        Language("fr", "Français", listOf(Region("fr-FR", "France"), Region("fr-CA", "Canada"))),
        Language("ru", "Русский", listOf(Region("ru-RU", "Россия"))),
        Language("th", "ไทย", listOf(Region("th-TH", "ประเทศไทย"))),
        Language("hi", "हिन्दी", listOf(Region("hi-IN", "भारत"))),
        Language("it", "Italiano", listOf(Region("it-IT", "Italia"))),
    )

    private val supported = languages.flatMap { language -> language.regions.map { it.tag } }.toSet()

    fun isSupportedChoice(tag: String): Boolean = tag.isEmpty() || tag in supported

    fun choice(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, "").orEmpty().takeIf { it in supported }.orEmpty()

    fun setChoice(context: Context, tag: String) {
        require(tag.isEmpty() || tag in supported) { "Unsupported Orb locale: $tag" }
        val storage = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (storage.getString(KEY, "").orEmpty() == tag) return
        storage.edit().putString(KEY, tag).commit()
        AccountSettingsSync.notifyAccountPreferenceChanged()
    }

    private fun deviceLocale(): Locale {
        val configuration = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= 24) configuration.locales[0] else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
    }

    /** Unlisted device locales keep the default English resources. */
    fun deviceTag(): String {
        val locale = deviceLocale()
        val exactTag = locale.toLanguageTag()
        if (exactTag in supported) return exactTag
        return when (locale.language.lowercase(Locale.ROOT)) {
            "pt" -> if (locale.country.equals("PT", ignoreCase = true)) "pt-PT" else "pt-BR"
            "en" -> when (locale.country.uppercase(Locale.ROOT)) {
                "GB" -> "en-GB"
                "CA" -> "en-CA"
                else -> "en-US"
            }
            "es" -> when (locale.country.uppercase(Locale.ROOT)) {
                "MX" -> "es-MX"
                "CO" -> "es-CO"
                else -> "es-ES"
            }
            "fr" -> if (locale.country.equals("CA", true)) "fr-CA" else "fr-FR"
            "de" -> "de-DE"
            "ru" -> "ru-RU"
            "th" -> "th-TH"
            "hi" -> "hi-IN"
            "it" -> "it-IT"
            else -> "en-US"
        }
    }

    fun effectiveTag(context: Context): String = choice(context).ifEmpty { deviceTag() }

    /** Unicode regional indicator flag, derived from the region in the locale tag. */
    fun flagForRegion(tag: String): String {
        val country = Locale.forLanguageTag(tag).country.uppercase(Locale.ROOT)
        if (country.length != 2 || country.any { it !in 'A'..'Z' }) return ""
        return buildString {
            country.forEach { letter ->
                append(String(Character.toChars(0x1F1E6 + (letter - 'A'))))
            }
        }
    }

    fun labelFor(tag: String): String {
        val match = languages.firstNotNullOfOrNull { language ->
            language.regions.firstOrNull { it.tag == tag }?.let { "${language.label} · ${it.label}" }
        }
        return match ?: tag
    }

    /** Used at Context attachment, before any Compose stringResource lookups. */
    fun wrap(base: Context): Context {
        val chosen = Locale.forLanguageTag(effectiveTag(base))
        val configuration = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(chosen))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = chosen
        }
        configuration.setLayoutDirection(chosen)
        Locale.setDefault(chosen)
        return base.createConfigurationContext(configuration)
    }
}
