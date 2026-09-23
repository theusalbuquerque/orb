package com.music.orb.data.sources.sflx

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.orb.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Host-side implementation of signedSession@3/sessionRefresh@1/sessionGrant@1.
 *
 * The session secret remains in Android encrypted storage. signedSession@3 keeps
 * provider retry semantics in the host, re-signs every replay with a fresh
 * timestamp/nonce and correlates browser grants with opaque callback state.
 */
internal class SflxSignedSession(
    context: Context,
    private val extensionId: String,
    private val config: SflxSignedSessionConfig,
    private val permissions: SflxPermissions,
) {
    private val appContext = context.applicationContext
    private val scope = listOf(
        config.namespace.trim().lowercase(Locale.US),
        config.baseUrl.trim().lowercase(Locale.US),
        config.appVersion.trim().lowercase(Locale.US),
        config.platform.trim().lowercase(Locale.US),
    ).joinToString("\n")
    private val scopeHash = sha256Hex(scope.toByteArray(Charsets.UTF_8)).take(16)
    private val recordKey = "session_$scopeHash"
    private val prefs = signedSessionPreferences(appContext)
    private val stateLock = lockFor(scopeHash)

    data class FetchResult(val json: JSONObject)

    fun signedFetch(
        methodRaw: String,
        path: String,
        body: String?,
        headersJson: String,
    ): FetchResult = synchronized(stateLock) {
        val method = methodRaw.trim().uppercase(Locale.US).ifBlank { "GET" }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val extraHeaders = parseHeaders(headersJson)

        var record = loadRecord()

        // Once a gateway explicitly requires verification, don't keep sending
        // signed requests with the same known-blocked generation. Bootstrap can
        // either return a challenge or silently provision a replacement session.
        if (generationIsBlocked(record)) {
            val authUrl = bootstrap(record)
            record = loadRecord()
            if (!authUrl.isNullOrBlank()) {
                return@synchronized FetchResult(verificationRequired(authUrl))
            }
            if (!isUsable(record)) {
                return@synchronized FetchResult(failureResponse("signed session is not authenticated"))
            }
            clearBlockedGeneration()
        }

        if (!isUsable(record)) {
            record.clearSession()
            saveRecord(record)
            val authUrl = bootstrap(record)
            record = loadRecord()
            if (!isUsable(record)) {
                return@synchronized FetchResult(
                    if (!authUrl.isNullOrBlank()) verificationRequired(authUrl)
                    else failureResponse("signed session is not authenticated"),
                )
            }
        } else if (shouldRefresh(record)) {
            runCatching { refresh(record) }
                .onFailure { TrackLog.w(TAG, "SFLX signed-session refresh failed: ${it.message}") }
            record = loadRecord()
        }

        var sessionRetries = 0
        var providerRetries = 0
        var requestAuthRetryUsed = false
        var finalResult: FetchResult? = null

        while (finalResult == null) {
            val response = doSignedRequest(record, method, path, bodyBytes, extraHeaders)
            val contract = responseContract(response)

            if (isProviderSameOperationRetry(response, contract)) {
                if (providerRetries >= MAX_PROVIDER_RETRIES) {
                    finalResult = FetchResult(responseToJson(response, contract))
                    continue
                }
                providerRetries++
                Thread.sleep(retryDelayMillis(response, contract))
                continue
            }

            // REQUEST_AUTH_INVALID must not destroy a valid session. It can be a
            // stale response if another runtime just exchanged a grant; retry once
            // with a newer stored generation when one exists.
            if (isRequestAuthInvalid(response, contract)) {
                val latest = loadRecord()
                if (!requestAuthRetryUsed && isUsable(latest) && !sameSession(latest, record)) {
                    requestAuthRetryUsed = true
                    record = latest
                    continue
                }
                finalResult = FetchResult(responseToJson(response, contract))
                continue
            }

            val gatewayAction = gatewayAction(response, contract)
            if (gatewayAction == null) {
                finalResult = FetchResult(responseToJson(response, contract))
                continue
            }

            // If a grant exchange completed while this request was in flight,
            // prefer the newer generation instead of invalidating it.
            val latest = loadRecord()
            if (isUsable(latest) && !sameSession(latest, record)) {
                if (sessionRetries >= MAX_SESSION_RETRIES) {
                    finalResult = FetchResult(failureResponse("signed-session retry limit reached"))
                    continue
                }
                sessionRetries++
                record = latest
                continue
            }

            when (gatewayAction) {
                GatewayAction.BOOTSTRAP_SESSION -> {
                    if (sameSession(latest, record)) {
                        clearBlockedGeneration()
                        latest.clearSession()
                        saveRecord(latest)
                    }
                }

                GatewayAction.VERIFY -> {
                    if (sameSession(latest, record)) blockGeneration(record)
                }
            }

            val authUrl = bootstrap(latest)
            if (!authUrl.isNullOrBlank()) {
                finalResult = FetchResult(verificationRequired(authUrl))
                continue
            }

            // Bootstrap is allowed to provision a session directly. Retry the
            // original operation once with that generation before returning the
            // gateway response to the extension.
            val bootstrapped = loadRecord()
            if (isUsable(bootstrapped) && sessionRetries < MAX_SESSION_RETRIES) {
                sessionRetries++
                record = bootstrapped
                clearBlockedGeneration()
                continue
            }

            finalResult = FetchResult(responseToJson(response, contract))
        }
        requireNotNull(finalResult)
    }

    /**
     * Exchanges the one-time browser grant. The JavaScript API calls this with no
     * argument, so an Android callback can stage the grant in [SflxSessionBridge]
     * before invoking the same contract.
     */
    fun completeGrant(grantRaw: String = ""): JSONObject = synchronized(stateLock) {
        val inlineGrant = grantRaw.trim()
        if (inlineGrant.isNotBlank()) {
            SflxSessionBridge.rememberPendingGrant(extensionId, inlineGrant)
        }
        val grant = inlineGrant.ifBlank { SflxSessionBridge.pendingGrant(extensionId).orEmpty() }
        val current = loadRecord()

        // The extension may call completeGrant() after MainActivity already
        // exchanged the callback. Treat that as idempotent success.
        if (grant.isBlank()) {
            return@synchronized if (isUsable(current)) {
                JSONObject().put("success", true)
            } else {
                failure("no pending grant")
            }
        }
        require(grant.length <= MAX_GRANT_LENGTH) { "SFLX session grant is too large" }

        val grantHash = sha256Hex(grant.toByteArray(Charsets.UTF_8))
        if (completedGrantHashes[scopeHash] == grantHash && isUsable(current)) {
            SflxSessionBridge.clearPendingGrant(extensionId)
            return@synchronized JSONObject().put("success", true)
        }

        val endpoint = resolveSignedUrl(config.endpoints.exchange)
        val payload = JSONObject()
            .put("grant", grant)
            .put("install_id", current.installId)
            .put("app_version", config.appVersion)
            .put("platform", config.platform)
            .toString()

        var lastError = "session exchange failed"
        for (attempt in 0 until MAX_EXCHANGE_ATTEMPTS) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "SpotiFLAC-Mobile/${config.appVersion}")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val result = httpClient.newCall(request).execute().use { response ->
                ExchangeHttpResult(
                    code = response.code,
                    successful = response.isSuccessful,
                    body = response.body?.string().orEmpty(),
                    retryAfter = response.header("Retry-After"),
                )
            }

            if (result.successful) {
                val json = runCatching { JSONObject(result.body) }
                    .getOrElse { error("Invalid SFLX session exchange response") }
                val sessionId = json.optString("session_id").trim()
                val sessionSecret = json.optString("session_secret").trim()
                val expiresAt = json.optString("expires_at").trim()
                require(sessionId.isNotBlank() && sessionSecret.isNotBlank() && expiresAt.isNotBlank()) {
                    "SFLX session exchange response is missing session fields"
                }
                current.sessionId = sessionId
                current.sessionSecret = sessionSecret
                current.expiresAt = expiresAt
                saveRecord(current)
                completedGrantHashes[scopeHash] = grantHash
                clearBlockedGeneration()
                SflxSessionBridge.clearPendingGrant(extensionId)
                SflxSessionBridge.clearVerification()
                return@synchronized JSONObject().put("success", true)
            }

            lastError = "session exchange failed: HTTP ${result.code}"
            if (result.code == 429 && attempt + 1 < MAX_EXCHANGE_ATTEMPTS) {
                Thread.sleep(retryDelayMillis(result.retryAfter))
                continue
            }
            break
        }

        failure(lastError)
    }

    fun status(): JSONObject = synchronized(stateLock) {
        val record = loadRecord()
        JSONObject()
            .put("authenticated", isUsable(record) && !generationIsBlocked(record))
            .put("verification_required", generationIsBlocked(record))
            .put("expires_at", record.expiresAt)
            .put("install_id", record.installId)
            .put("session_id", record.sessionId)
            .put("app_version", config.appVersion)
            .put("platform", config.platform)
    }

    fun clear(): JSONObject = synchronized(stateLock) {
        val record = loadRecord().also { it.clearSession() }
        saveRecord(record)
        clearBlockedGeneration()
        SflxSessionBridge.clearPendingGrant(extensionId)
        SflxSessionBridge.clearVerification()
        JSONObject().put("success", true)
    }

    /**
     * Returns a browser verification URL, or null when bootstrap silently issued
     * a usable session.
     */
    private fun bootstrap(record: Record): String? {
        val endpoint = resolveSignedUrl(config.endpoints.bootstrap)
            .newBuilder()
            .addQueryParameter("app_version", config.appVersion)
            .addQueryParameter("install_id", record.installId)
            .build()

        var lastFailure: Throwable? = null
        var responseBody: String? = null
        for (attempt in 0 until BOOTSTRAP_ATTEMPTS) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "SpotiFLAC-Mobile/${config.appVersion}")
                .get()
                .build()
            val result = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) {
                        "signed-session bootstrap returned HTTP ${response.code}"
                    }
                    response.body?.string().orEmpty()
                }
            }
            if (result.isSuccess) {
                responseBody = result.getOrThrow()
                break
            }
            lastFailure = result.exceptionOrNull()
            if (attempt + 1 < BOOTSTRAP_ATTEMPTS) httpClient.connectionPool.evictAll()
        }
        val text = responseBody ?: throw (lastFailure ?: IllegalStateException("signed-session bootstrap failed"))
        val json = runCatching { JSONObject(text) }
            .getOrElse { error("Invalid SFLX signed-session bootstrap response") }

        val sessionId = json.optString("session_id").trim()
        val sessionSecret = json.optString("session_secret").trim()
        val expiresAt = json.optString("expires_at").trim()
        if (sessionId.isNotBlank() && sessionSecret.isNotBlank() && expiresAt.isNotBlank()) {
            record.sessionId = sessionId
            record.sessionSecret = sessionSecret
            record.expiresAt = expiresAt
            saveRecord(record)
            clearBlockedGeneration()
            SflxSessionBridge.clearVerification()
            return null
        }

        val rawAuthUrl = firstNonBlank(
            json.optString("auth_url"),
            json.optString("challenge_url"),
            json.optString("challenge_id").takeIf { it.isNotBlank() }?.let(::buildChallengeUrl),
        ) ?: error("signed-session bootstrap did not return a session or verification challenge")
        val authUrl = ensureVerificationState(rawAuthUrl)

        val parsed = authUrl.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps) { "SFLX verification URL is not HTTPS" }
        require(!isPrivateLiteral(parsed.host)) { "SFLX verification URL points to a private/local address" }
        SflxSessionBridge.requestVerification(extensionId, authUrl)
        return authUrl
    }

    /** signedSession@3 binds every browser callback to an opaque one-time host state. */
    private fun ensureVerificationState(rawUrl: String): String {
        val parsed = Uri.parse(rawUrl)
        val existing = parsed.getQueryParameter("state")?.trim().orEmpty()
        if (existing.isNotBlank()) return rawUrl
        val state = SflxSessionBridge.newCallbackState(extensionId)
        return parsed.buildUpon().appendQueryParameter("state", state).build().toString()
    }

    private fun buildChallengeUrl(challengeId: String): String {
        val callbackState = SflxSessionBridge.newCallbackState(extensionId)
        val callback = Uri.parse(config.callbackUrl).buildUpon()
            .appendQueryParameter("cb_version", "v2grant")
            .appendQueryParameter("state", callbackState)
            .build()
            .toString()
        return resolveSignedUrl(config.endpoints.challenge)
            .newBuilder()
            .addQueryParameter("id", challengeId)
            .addQueryParameter("cb", callback)
            .build()
            .toString()
    }

    private fun refresh(record: Record) {
        if (config.endpoints.refresh.isBlank()) return
        val body = JSONObject()
            .put("install_id", record.installId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = doSignedRequest(record, "POST", config.endpoints.refresh, body, emptyMap())
        if (!response.successful) return
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return
        var changed = false
        json.optString("session_id").trim().takeIf { it.isNotBlank() }?.let {
            record.sessionId = it
            changed = true
        }
        json.optString("session_secret").trim().takeIf { it.isNotBlank() }?.let {
            record.sessionSecret = it
            changed = true
        }
        json.optString("expires_at").trim().takeIf { it.isNotBlank() && it != record.expiresAt }?.let {
            record.expiresAt = it
            changed = true
        }
        if (changed) saveRecord(record)
    }

    private fun doSignedRequest(
        record: Record,
        method: String,
        requestPath: String,
        body: ByteArray,
        extraHeaders: Map<String, String>,
    ): SessionResponse {
        require(record.sessionId.isNotBlank() && record.sessionSecret.isNotBlank()) {
            "signed session is not authenticated"
        }
        val url = resolveSignedUrl(requestPath)
        val now = System.currentTimeMillis()
        val timestamp = utcTimestamp(now)
        val nonce = randomHex(12)
        val bodyHash = sha256Hex(body)
        val window = now / 1000L / config.timeWindowSeconds.coerceAtLeast(1)
        val rollingInput = "$window:${record.sessionId}"
        val rollingBytes = hmacSha256(
            record.sessionSecret.toByteArray(Charsets.UTF_8),
            rollingInput.toByteArray(Charsets.UTF_8),
        )
        val rollingKey = Base64.encodeToString(
            rollingBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val signingInput = listOf(
            config.schemeLabel,
            method,
            url.encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            record.sessionId,
            config.appVersion,
            config.platform,
        ).joinToString("\n")
        val signature = Base64.encodeToString(
            hmacSha256(
                rollingKey.toByteArray(Charsets.UTF_8),
                signingInput.toByteArray(Charsets.UTF_8),
            ),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "SpotiFLAC-Mobile/${config.appVersion}")
            .header("${config.headerPrefix}Session", record.sessionId)
            .header("${config.headerPrefix}Timestamp", timestamp)
            .header("${config.headerPrefix}Nonce", nonce)
            .header("${config.headerPrefix}Body-SHA256", bodyHash)
            .header("${config.headerPrefix}Signature", signature)
            .header("${config.headerPrefix}App-Version", config.appVersion)
            .header("${config.headerPrefix}Platform", config.platform)
        if (body.isNotEmpty()) builder.header("Content-Type", "application/json")
        extraHeaders.forEach { (name, value) ->
            if (!name.equals("Host", true) && !name.equals("Content-Length", true)) {
                builder.header(name, value)
            }
        }
        val requestBody = if (method in BODY_METHODS) body.toRequestBody(JSON_MEDIA_TYPE) else null
        val request = builder.method(method, requestBody).build()

        return httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val responseHeaders = JSONObject()
            response.headers.names().forEach { name ->
                val values = response.headers.values(name)
                responseHeaders.put(name, if (values.size == 1) values[0] else JSONArray(values))
            }
            SessionResponse(
                code = response.code,
                successful = response.isSuccessful,
                url = response.request.url.toString(),
                body = text,
                headers = responseHeaders,
                retryAfter = response.header("Retry-After"),
            )
        }
    }

    private fun responseContract(response: SessionResponse): JSONObject? = runCatching {
        val value = JSONObject(response.body)
        if (value.has("code") || value.has("origin") || value.has("action")) value else null
    }.getOrNull()

    private fun responseToJson(response: SessionResponse, contract: JSONObject?): JSONObject =
        JSONObject()
            .put("statusCode", response.code)
            .put("status", response.code)
            .put("ok", response.successful)
            .put("url", response.url)
            .put("body", response.body)
            .put("headers", response.headers)
            .put("retryAfterSeconds", retryAfterSeconds(response.retryAfter, contract))
            .apply {
                if (contract != null) {
                    put("error", contract.optString("error"))
                    put("code", contract.optString("code"))
                    put("origin", contract.optString("origin"))
                    put("action", contract.optString("action"))
                    put("retryable", contract.optBoolean("retryable", false))
                    put("retryMode", firstNonBlank(contract.optString("retry_mode"), contract.optString("retryMode")).orEmpty())
                }
            }

    private fun verificationRequired(authUrl: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("needsVerification", true)
        .put("error", "VERIFY_REQUIRED")
        .put("open_auth_url", authUrl)
        .put("auth_url", authUrl)

    private fun failureResponse(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", message)

    private fun gatewayAction(response: SessionResponse, contract: JSONObject?): GatewayAction? {
        if (contract == null || !contract.optString("origin").equals("gateway", true)) return null
        val code = contract.optString("code").trim().uppercase(Locale.US)
        val action = contract.optString("action").trim().lowercase(Locale.US)
        return when {
            response.code == 401 && code == "SESSION_INVALID" && action == "bootstrap_session" ->
                GatewayAction.BOOTSTRAP_SESSION

            response.code == 428 && code == "VERIFY_REQUIRED" && action == "verify" ->
                GatewayAction.VERIFY

            else -> null
        }
    }

    private fun isProviderSameOperationRetry(response: SessionResponse, contract: JSONObject?): Boolean {
        val value = contract ?: return false
        return response.code == 503 &&
            value.optString("origin").equals("provider", true) &&
            value.optString("code").equals("PROVIDER_UNAVAILABLE", true) &&
            value.optBoolean("retryable", false) &&
            firstNonBlank(value.optString("retry_mode"), value.optString("retryMode"))
                .equals("same_operation", true)
    }

    private fun isRequestAuthInvalid(response: SessionResponse, contract: JSONObject?): Boolean {
        val value = contract ?: return false
        return response.code == 403 &&
            value.optString("origin").equals("gateway", true) &&
            value.optString("code").equals("REQUEST_AUTH_INVALID", true) &&
            value.optString("action").isBlank()
    }

    private fun resolveSignedUrl(endpoint: String): HttpUrl {
        val base = (config.baseUrl.trimEnd('/') + "/").toHttpUrlOrNull()
            ?: error("Invalid SFLX signed-session base URL")
        require(base.isHttps) { "SFLX signed-session base URL must use HTTPS" }
        val resolved = if (endpoint.startsWith("https://")) {
            endpoint.toHttpUrlOrNull()
        } else {
            base.resolve(endpoint.trimStart('/'))
        } ?: error("Invalid SFLX signed-session endpoint")
        require(resolved.isHttps) { "SFLX signed-session endpoint must use HTTPS" }
        require(hostAllowed(resolved.host, permissions.network)) {
            "SFLX signed-session network permission denied for ${resolved.host}"
        }
        require(!isPrivateLiteral(resolved.host)) {
            "SFLX signed-session private/local network access is blocked"
        }
        return resolved
    }

    private fun loadRecord(): Record {
        val parsed = prefs.getString(recordKey, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                Record(
                    installId = json.optString("install_id"),
                    sessionId = json.optString("session_id"),
                    sessionSecret = json.optString("session_secret"),
                    expiresAt = json.optString("expires_at"),
                )
            }.getOrNull()
        }
        val record = parsed ?: Record()
        if (!INSTALL_ID_REGEX.matches(record.installId)) {
            record.installId = randomHex(16)
            saveRecord(record)
        }
        return record
    }

    private fun saveRecord(record: Record) {
        val json = JSONObject()
            .put("install_id", record.installId)
            .put("session_id", record.sessionId)
            .put("session_secret", record.sessionSecret)
            .put("expires_at", record.expiresAt)
        prefs.edit().putString(recordKey, json.toString()).apply()
    }

    private fun isUsable(record: Record): Boolean {
        if (record.sessionId.isBlank() || record.sessionSecret.isBlank()) return false
        val expires = parseTime(record.expiresAt) ?: return true
        return System.currentTimeMillis() < expires
    }

    private fun shouldRefresh(record: Record): Boolean {
        if (config.endpoints.refresh.isBlank()) return false
        val expires = parseTime(record.expiresAt) ?: return false
        val remaining = expires - System.currentTimeMillis()
        return remaining in 1..REFRESH_SKEW_MS
    }

    private fun sameSession(a: Record, b: Record): Boolean =
        a.sessionId.isNotBlank() && a.sessionId == b.sessionId && a.sessionSecret == b.sessionSecret

    private fun sessionGeneration(record: Record): String {
        if (record.sessionId.isBlank() || record.sessionSecret.isBlank()) return ""
        return sha256Hex("${record.sessionId}\n${record.sessionSecret}".toByteArray(Charsets.UTF_8))
    }

    private fun blockGeneration(record: Record) {
        val generation = sessionGeneration(record)
        if (generation.isNotBlank()) blockedGenerations[scopeHash] = generation
    }

    private fun generationIsBlocked(record: Record): Boolean {
        val generation = sessionGeneration(record)
        return generation.isNotBlank() && blockedGenerations[scopeHash] == generation
    }

    private fun clearBlockedGeneration() {
        blockedGenerations.remove(scopeHash)
    }

    private fun parseTime(value: String): Long? {
        if (value.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val values = LinkedHashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key)
            if (value.isNotBlank()) values[key] = value
        }
        return values
    }

    private fun retryDelayMillis(response: SessionResponse, contract: JSONObject?): Long {
        val seconds = retryAfterSeconds(response.retryAfter, contract).takeIf { it > 0 } ?: 1
        return seconds.coerceAtMost(MAX_RETRY_DELAY_SECONDS) * 1000L
    }

    private fun retryDelayMillis(retryAfter: String?): Long {
        val seconds = parseRetryAfterSeconds(retryAfter).takeIf { it > 0 } ?: 1
        return seconds.coerceAtMost(MAX_RETRY_DELAY_SECONDS) * 1000L
    }

    private fun retryAfterSeconds(raw: String?, contract: JSONObject? = null): Int {
        val header = parseRetryAfterSeconds(raw)
        val fromContract = contract?.optInt("retry_after_seconds", 0)?.coerceAtLeast(0) ?: 0
        return if (header > 0) header else fromContract
    }

    private fun parseRetryAfterSeconds(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        value.toIntOrNull()?.coerceAtLeast(0)?.let { return it }
        if (value.isBlank()) return 0
        val dateMillis = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(value)?.time
        }.getOrNull() ?: return 0
        return ((dateMillis - System.currentTimeMillis() + 999L) / 1000L)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private enum class GatewayAction { BOOTSTRAP_SESSION, VERIFY }

    private data class Record(
        var installId: String = "",
        var sessionId: String = "",
        var sessionSecret: String = "",
        var expiresAt: String = "",
    ) {
        fun clearSession() {
            sessionId = ""
            sessionSecret = ""
            expiresAt = ""
        }
    }

    private data class SessionResponse(
        val code: Int,
        val successful: Boolean,
        val url: String,
        val body: String,
        val headers: JSONObject,
        val retryAfter: String?,
    )

    private data class ExchangeHttpResult(
        val code: Int,
        val successful: Boolean,
        val body: String,
        val retryAfter: String?,
    )

    private fun failure(message: String): JSONObject = JSONObject()
        .put("success", false)
        .put("error", message)

    private companion object {
        private const val TAG = "SflxSignedSession"
        private const val REFRESH_SKEW_MS = 60L * 60L * 1000L
        private const val BOOTSTRAP_ATTEMPTS = 2
        private const val MAX_EXCHANGE_ATTEMPTS = 3
        private const val MAX_SESSION_RETRIES = 1
        private const val MAX_PROVIDER_RETRIES = 2
        // signedSession@3 honors Retry-After (including HTTP-date form). Higher
        // playback layers still own their own timeout/cancellation budget.
        private const val MAX_RETRY_DELAY_SECONDS = 300
        private const val MAX_GRANT_LENGTH = 16 * 1024
        private val INSTALL_ID_REGEX = Regex("^[0-9a-f]{32}$")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val secureRandom = SecureRandom()
        private val locks = ConcurrentHashMap<String, Any>()
        private val blockedGenerations = ConcurrentHashMap<String, String>()
        private val completedGrantHashes = ConcurrentHashMap<String, String>()

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        private fun lockFor(scopeHash: String): Any {
            val candidate = Any()
            return locks.putIfAbsent(scopeHash, candidate) ?: candidate
        }

        private fun utcTimestamp(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(epochMillis))

        private fun randomHex(bytes: Int): String {
            val value = ByteArray(bytes)
            secureRandom.nextBytes(value)
            return value.joinToString("") { "%02x".format(it) }
        }

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(message)

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }

        private fun hostAllowed(host: String, allowed: List<String>): Boolean {
            val normalized = host.lowercase(Locale.US).trimEnd('.')
            return allowed.any { rule ->
                val candidate = rule.lowercase(Locale.US).trim().trimEnd('.')
                if (candidate.startsWith("*.")) {
                    normalized.endsWith(".${candidate.removePrefix("*.")}")
                } else {
                    normalized == candidate
                }
            }
        }

        private fun isPrivateLiteral(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]").lowercase(Locale.US)
            if (h == "localhost" || h == "::1") return true
            val parts = h.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 10 || parts[0] == 127 ||
                (parts[0] == 169 && parts[1] == 254) ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31)
        }

        private fun signedSessionPreferences(context: Context): SharedPreferences = runCatching {
            EncryptedSharedPreferences.create(
                context,
                "orb_sflx_signed_sessions",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            TrackLog.w(TAG, "Encrypted signed-session storage unavailable: ${it.message}")
            context.getSharedPreferences("orb_sflx_signed_sessions_plain", Context.MODE_PRIVATE)
        }
    }
}

/**
 * Activity/runtime hand-off for browser verification. Runtime code can request
 * verification without starting an Activity from playback/background threads.
 */
internal object SflxSessionBridge {
    private const val TAG = "SflxSessionBridge"
    private const val REEMIT_THROTTLE_MS = 30_000L
    private const val CALLBACK_STATE_TTL_MS = 20L * 60L * 1000L

    private data class CallbackState(val providerId: String, val expiresAt: Long)

    private val _verificationUrl = MutableStateFlow<String?>(null)
    val verificationUrl: StateFlow<String?> = _verificationUrl.asStateFlow()

    private val pendingGrants = ConcurrentHashMap<String, String>()
    private val callbackStates = ConcurrentHashMap<String, CallbackState>()

    @Volatile
    private var activeVerificationProvider: String? = null

    @Volatile
    private var lastUrl: String? = null

    @Volatile
    private var lastEmitAt: Long = 0L

    fun newCallbackState(extensionId: String): String {
        cleanupExpiredStates()
        val state = java.util.UUID.randomUUID().toString().replace("-", "")
        callbackStates[state] = CallbackState(
            providerId = extensionId.trim(),
            expiresAt = System.currentTimeMillis() + CALLBACK_STATE_TTL_MS,
        )
        activeVerificationProvider = extensionId.trim()
        return state
    }

    fun requestVerification(extensionId: String, url: String) {
        cleanupExpiredStates()
        val provider = extensionId.trim()
        if (provider.isNotBlank()) activeVerificationProvider = provider
        // signedSession@3 may have the gateway mint the state itself. Remember
        // that opaque value rather than assuming state == extension id.
        runCatching { Uri.parse(url).getQueryParameter("state")?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && provider.isNotBlank() }
            ?.let { state ->
                callbackStates[state] = CallbackState(
                    providerId = provider,
                    expiresAt = System.currentTimeMillis() + CALLBACK_STATE_TTL_MS,
                )
            }
        requestVerification(url)
    }

    /** Backward-compatible entry point for older call sites. */
    fun requestVerification(url: String) {
        val now = System.currentTimeMillis()
        if (url == lastUrl && now - lastEmitAt < REEMIT_THROTTLE_MS) return
        lastUrl = url
        lastEmitAt = now
        _verificationUrl.value = url
    }

    fun markVerificationOpened(url: String) {
        if (_verificationUrl.value == url) _verificationUrl.value = null
    }

    fun clearVerification() {
        _verificationUrl.value = null
        lastUrl = null
        lastEmitAt = 0L
        activeVerificationProvider = null
        cleanupExpiredStates()
    }

    fun rememberPendingGrant(extensionId: String, grant: String) {
        val normalizedId = extensionId.trim()
        val normalizedGrant = grant.trim()
        if (normalizedId.isNotBlank() && normalizedGrant.isNotBlank()) {
            pendingGrants[normalizedId] = normalizedGrant
        }
    }

    fun pendingGrant(extensionId: String): String? = pendingGrants[extensionId.trim()]

    fun clearPendingGrant(extensionId: String) {
        pendingGrants.remove(extensionId.trim())
    }

    fun isCallback(uri: Uri?): Boolean =
        uri?.scheme.equals("spotiflac", true) && uri?.host.equals("session-grant", true)

    suspend fun completeCallback(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(isCallback(uri)) { "Not an SFLX session callback" }
            val callbackError = firstCallbackValue(uri, "error")
            if (!callbackError.isNullOrBlank()) error("SFLX verification failed: $callbackError")

            val grant = firstCallbackValue(uri, "grant", "session_grant")
                ?: error("SFLX verification callback did not include a grant")
            val state = firstCallbackValue(uri, "state")
            cleanupExpiredStates()

            val providerId = when {
                !state.isNullOrBlank() -> {
                    callbackStates.remove(state)?.providerId
                        ?: legacyProviderId(context.applicationContext, state)
                        ?: error("SFLX verification callback state is unknown or expired")
                }
                !activeVerificationProvider.isNullOrBlank() -> activeVerificationProvider!!
                else -> soleInstalledSignedSessionProvider(context.applicationContext)
                    ?: SflxPackageManager.TIDAL_PROVIDER_ID
            }

            val installed = SflxPackageManager.loadInstalled(context.applicationContext, providerId)
                ?: error("$providerId SFLX is not installed")
            val config = installed.manifest.signedSession
                ?: error("Installed $providerId SFLX does not declare signedSession")

            rememberPendingGrant(installed.manifest.name, grant)
            val manager = SflxSignedSession(
                context = context.applicationContext,
                extensionId = installed.manifest.name,
                config = config,
                permissions = installed.manifest.permissions,
            )
            val result = manager.completeGrant()
            require(result.optBoolean("success", false)) {
                result.optString("error", "Could not complete SFLX session verification")
            }
            clearVerification()
            TrackLog.d(TAG, "$providerId SFLX signed-session verification completed")
        }
    }

    private fun legacyProviderId(context: Context, state: String): String? {
        // signedSession@1 used the provider id itself as state. Keep old callbacks
        // functional while v3 uses opaque correlation state.
        return runCatching { SflxPackageManager.loadInstalled(context, state)?.manifest?.name }.getOrNull()
    }

    private fun soleInstalledSignedSessionProvider(context: Context): String? {
        val ids = listOf(SflxPackageManager.TIDAL_PROVIDER_ID, SflxPackageManager.QOBUZ_PROVIDER_ID)
            .filter { id ->
                runCatching { SflxPackageManager.loadInstalled(context, id)?.manifest?.signedSession != null }
                    .getOrDefault(false)
            }
        return ids.singleOrNull()
    }

    private fun cleanupExpiredStates() {
        val now = System.currentTimeMillis()
        callbackStates.entries.filter { it.value.expiresAt <= now }.forEach { callbackStates.remove(it.key, it.value) }
    }

    private fun firstCallbackValue(uri: Uri, vararg keys: String): String? {
        for (key in keys) {
            val value = uri.getQueryParameter(key)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        val fragment = uri.fragment?.trim().orEmpty()
        if (fragment.isNotBlank()) {
            val fragmentUri = Uri.parse("orb://callback?$fragment")
            for (key in keys) {
                val value = fragmentUri.getQueryParameter(key)?.trim()
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }
}

