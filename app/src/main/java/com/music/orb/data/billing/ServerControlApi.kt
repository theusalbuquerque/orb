package com.music.orb.data.billing

import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.OrbSupabase
import io.github.jan.supabase.auth.auth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ServerControlSnapshot(
    val premium: Boolean,
    val plan: String?,
    val source: String?,
    val expiresAt: String?,
    val automix25Enabled: Boolean,
    val orbSwitchEnabled: Boolean,
    val premiumCardEnabled: Boolean,
    val roomsEnabled: Boolean,
    val losslessMobileEnabled: Boolean,
    val refreshIntervalSeconds: Long,
)

/**
 * Server-authoritative Orb account/config snapshot.
 *
 * The client never receives a service-role key and never writes entitlements.
 * It only sends the current Supabase access token to Orb's backend, which
 * resolves the authenticated user and returns the server-controlled snapshot.
 */
internal object ServerControlApi {
    private const val BASE_URL = "https://orb-4mrh.onrender.com"

    @Synchronized
    fun refresh(): ServerControlSnapshot {
        val response = request("/api/control/bootstrap")
        val currentUser = OrbSupabase.client.auth.currentUserOrNull()?.id
        if (currentUser == null || response.optString("userId") != currentUser) {
            throw BillingException(401)
        }

        val entitlement = response.optJSONObject("entitlement") ?: JSONObject()
        val config = response.optJSONObject("config") ?: JSONObject()
        val snapshot = ServerControlSnapshot(
            premium = entitlement.optBoolean("premium", false),
            plan = entitlement.optString("plan").takeIf { it.isNotBlank() && it != "null" },
            source = entitlement.optString("source").takeIf { it.isNotBlank() && it != "null" },
            expiresAt = entitlement.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
            automix25Enabled = config.optBoolean("automix25Enabled", true),
            orbSwitchEnabled = config.optBoolean("orbSwitchEnabled", true),
            premiumCardEnabled = config.optBoolean("premiumCardEnabled", true),
            roomsEnabled = config.optBoolean("roomsEnabled", true),
            losslessMobileEnabled = config.optBoolean("losslessMobileEnabled", true),
            refreshIntervalSeconds = config.optLong("refreshIntervalSeconds", 60L).coerceIn(30L, 900L),
        )
        AppSettings.applyServerControl(snapshot)
        return snapshot
    }

    private fun request(path: String): JSONObject {
        if (!OrbSupabase.configured) throw BillingException(503)
        val session = OrbSupabase.client.auth.currentSessionOrNull() ?: throw BillingException(401)
        val userId = OrbSupabase.client.auth.currentUserOrNull()?.id ?: throw BillingException(401)
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        }
        return try {
            if (connection.responseCode !in 200..299) throw BillingException(connection.responseCode)
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (response.optString("userId") != userId || OrbSupabase.client.auth.currentUserOrNull()?.id != userId) {
                throw BillingException(401)
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
