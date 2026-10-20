package com.music.orb.data.billing

import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.OrbSupabase
import io.github.jan.supabase.auth.auth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class PremiumCheckout(
    val reference: String,
    val amount: Double,
    val requiresConfirmation: Boolean,
    val url: String?,
)

internal class BillingException(
    val status: Int,
    val code: String? = null,
    val providerStatus: Int? = null,
    val providerCode: String? = null,
    cause: Throwable? = null,
) : Exception("Billing request failed (HTTP $status, code=${code ?: "unknown"})", cause)

internal object BillingApi {
    private const val BASE_URL = "https://orb-4mrh.onrender.com"

    fun fetchMonthlyPrice(): Double? = runCatching {
        val plans = request("/providers", authenticated = false).optJSONObject("plans")
        plans?.optDouble("monthlyPrice", Double.NaN)?.takeIf { it.isFinite() && it > 0.0 }
    }.getOrNull()

    @Synchronized
    fun refreshSubscription(): Boolean = runCatching { ServerControlApi.refresh().premium }
        .getOrElse { applyEntitlement(request("/subscription")) }

    @Synchronized
    fun checkout(): PremiumCheckout {
        val response = request("/subscription/checkout", JSONObject().put("plan", "monthly"))
        val amount = response.getDouble("amount")
        check(amount.isFinite() && amount >= 0.0)
        return PremiumCheckout(
            reference = response.getString("checkoutRef"),
            amount = amount,
            requiresConfirmation = response.optBoolean("requiresConfirmation"),
            url = response.optString("checkoutUrl").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    @Synchronized
    fun confirm(checkout: PremiumCheckout): Boolean = applyEntitlement(
        request("/subscription/confirm", JSONObject().put("checkout_ref", checkout.reference)),
    )

    @Synchronized
    fun cancelSubscription(): Boolean {
        val response = request("/subscription/cancel", JSONObject())
        // A successful cancellation does not necessarily revoke paid Premium now.
        if (response.optString("status") != "canceled") throw BillingException(502, code = "CANCEL_NOT_CONFIRMED")
        return applyEntitlement(response)
    }

    /** Real Mercado Pago ZDA, not an unverified local "purchase" flag.
     * These endpoints are separate from regular paid subscriber billing. */
    fun ownerZeroStatus(): Boolean {
        val response = request("/owner-zda/status")
        AppSettings.setOwnerPremiumTestSubscribed(response.optBoolean("subscribed", false))
        return response.optBoolean("subscribed", false)
    }

    fun ownerZeroCheckout(): PremiumCheckout {
        val response = request("/owner-zda/start", JSONObject())
        check(response.optDouble("amount", -1.0) == 0.0)
        val url = response.optString("checkoutUrl").takeIf { it.startsWith("https://") }
            ?: throw BillingException(502)
        return PremiumCheckout(
            reference = response.getString("checkoutRef"),
            amount = 0.0,
            requiresConfirmation = false,
            url = url,
        )
    }

    fun ownerZeroCancel(): Boolean {
        val response = request("/owner-zda/cancel", JSONObject())
        check(!response.optBoolean("subscribed", true))
        AppSettings.setOwnerPremiumTestSubscribed(false)
        return false
    }

    private fun applyEntitlement(response: JSONObject): Boolean {
        if (response.getString("userId") != OrbSupabase.client.auth.currentUserOrNull()?.id) {
            throw BillingException(401)
        }
        return response.getBoolean("premium").also(AppSettings::setPremiumEntitled)
    }

    private fun request(path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject {
        val session = if (authenticated && OrbSupabase.configured) OrbSupabase.client.auth.currentSessionOrNull() else null
        if (authenticated && session == null) throw BillingException(401)
        val userId = if (authenticated) OrbSupabase.client.auth.currentUserOrNull()?.id else null
        val connection = (URL("$BASE_URL/api/billing$path").openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            session?.let { setRequestProperty("Authorization", "Bearer ${it.accessToken}") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            body?.let { payload -> connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) } }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Error payloads may contain diagnostic codes; never expose raw provider data.
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                val detail = runCatching {
                    JSONObject(errorBody ?: "{}").optJSONObject("detail")
                }.getOrNull()
                fun diagnosticText(key: String): String? = detail?.optString(key)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
                val exception = BillingException(
                    status = status,
                    code = diagnosticText("code"),
                    providerStatus = detail?.optInt("providerStatus", -1)?.takeIf { it in 100..599 },
                    providerCode = diagnosticText("providerCode"),
                )
                android.util.Log.w(
                    "OrbBilling",
                    "HTTP ${exception.status}, code=${exception.code ?: "unknown"}, " +
                        "providerStatus=${exception.providerStatus ?: -1}, " +
                        "providerCode=${exception.providerCode ?: "unknown"}",
                )
                throw exception
            }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (authenticated && (userId == null || response.optString("userId") != userId ||
                    OrbSupabase.client.auth.currentUserOrNull()?.id != userId)) throw BillingException(401)
            response
        } catch (exception: java.io.IOException) {
            android.util.Log.w("OrbBilling", "Network failure: ${exception.javaClass.simpleName}")
            throw BillingException(status = 0, code = "NETWORK_ERROR", cause = exception)
        } finally {
            connection.disconnect()
        }
    }
}
