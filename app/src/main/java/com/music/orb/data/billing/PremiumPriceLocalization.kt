package com.music.orb.data.billing

import android.content.Context
import android.content.res.Configuration
import com.music.orb.R
import com.music.orb.data.settings.OrbAppLanguage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Orb checkout currently charges BRL. A foreign-currency amount shown to users is
 * an informational FX estimate, NEVER the value submitted to Mercado Pago.
 */
internal object PremiumPriceLocalization {
    private const val BASE_CURRENCY = "BRL"
    private const val RATES_URL = "https://open.er-api.com/v6/latest/BRL"
    private const val CACHE_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_QUOTE_AGE_SECONDS = 7L * 24 * 60 * 60

    internal data class FxQuote(val currencyCode: String, val unitsPerBrl: Double)
    private data class FxSnapshot(val fetchedAt: Long, val rates: JSONObject)

    @Volatile private var cachedRates: FxSnapshot? = null

    fun languageTag(context: Context): String = OrbAppLanguage.effectiveTag(context)

    fun localCurrency(tag: String): String = runCatching {
        Currency.getInstance(Locale.forLanguageTag(tag)).currencyCode
    }.getOrDefault(BASE_CURRENCY)

    fun format(amount: Double, currency: String, localeTag: String): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(localeTag))
        formatter.currency = Currency.getInstance(currency)
        // Explicitly set the currency's ISO fraction digits, not those inherited
        // from the user's local currency in case they are different.
        val digits = formatter.currency.defaultFractionDigits.coerceAtLeast(0)
        formatter.minimumFractionDigits = digits
        formatter.maximumFractionDigits = digits
        return formatter.format(amount)
    }

    fun formatBrl(amount: Double): String = format(amount, BASE_CURRENCY, "pt-BR")

    /** Resolve strings using the *chosen* Orb region, even if a Compose host
     * still holds an older configuration after changing the app language. */
    fun localizedString(context: Context, resource: Int, vararg args: Any): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag(context)))
        return context.createConfigurationContext(configuration).getString(resource, *args)
    }

    fun monthlyText(context: Context, price: String): String =
        localizedString(context, R.string.premium_monthly_price_format, price)

    /** Call on Dispatchers.IO. Returns null when the source is unavailable or stale;
     * never guesses a currency conversion from a static or outdated rate. */
    @Synchronized
    fun quote(tag: String): FxQuote? {
        val currency = localCurrency(tag)
        if (currency == BASE_CURRENCY) return FxQuote(BASE_CURRENCY, 1.0)
        val snapshot = cachedRates?.takeIf {
            System.currentTimeMillis() - it.fetchedAt in 0..CACHE_MILLIS
        } ?: fetchRates()?.also { cachedRates = it } ?: return null
        val rate = snapshot.rates.optDouble(currency, Double.NaN)
        if (!rate.isFinite() || rate <= 0.0) return null
        return FxQuote(currency, rate)
    }

    private fun fetchRates(): FxSnapshot? = runCatching {
        val connection = URL(RATES_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = true
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText().take(96_000))
            }
            if (json.optString("result") != "success" ||
                json.optString("base_code") != BASE_CURRENCY) return@runCatching null
            val lastUpdated = json.optLong("time_last_update_unix", 0L)
            val age = System.currentTimeMillis() / 1000 - lastUpdated
            if (lastUpdated <= 0L || age !in 0..MAX_QUOTE_AGE_SECONDS) return@runCatching null
            FxSnapshot(System.currentTimeMillis(), json.getJSONObject("rates"))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
