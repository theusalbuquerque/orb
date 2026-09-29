package com.music.orb.data.billing

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object BillingApi {
    private const val BASE_URL = "https://orb-4mrh.onrender.com"

    fun fetchMonthlyPrice(): Double? {
        val connection = (URL("$BASE_URL/api/billing/providers").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
        }

        return try {
            if (connection.responseCode !in 200..299) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val plans = JSONObject(body).optJSONObject("plans") ?: return null
            val price = plans.optDouble("monthlyPrice", Double.NaN)

            price.takeIf { it.isFinite() && it > 0.0 }
        } finally {
            connection.disconnect()
        }
    }
}
