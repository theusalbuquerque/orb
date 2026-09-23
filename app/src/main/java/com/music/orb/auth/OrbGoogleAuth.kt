package com.music.orb.auth

import android.accounts.Account as AndroidAccount
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.music.orb.BuildConfig
import com.music.orb.data.DebugLog as Log
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.innertube.Innertube
import com.music.orb.data.model.Account
import com.music.orb.data.model.YouTubeAccountIdentity
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One Google identity for Orb, Supabase and YouTube account authorization. */
data class OrbGoogleIdentity(
    val uniqueId: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
) {
    fun asAccount(): Account = Account(
        name = displayName?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "Google",
        email = email.orEmpty(),
        thumbnailUrl = avatarUrl,
    )
}

/**
 * Native Google account bridge.
 *
 * Authentication and authorization are deliberately separate internally:
 * Credential Manager returns the Google ID token that Supabase validates,
 * while AuthorizationClient grants the short-lived `youtube` access token.
 * The user starts both through one Orb sign-in action and the same selected
 * Google account becomes the default for the authorization request.
 */
object OrbGoogleAuth {
    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

    private const val TAG = "OrbGoogleAuth"
    private const val TOKEN_CACHE_MS = 50L * 60L * 1000L
    private const val TOKEN_REFRESH_GUARD_MS = 2L * 60L * 1000L

    private lateinit var appContext: Context
    private lateinit var store: AuthStore
    private val tokenMutex = Mutex()

    @Volatile
    private var oauthRejectedUntilMs: Long = 0L

    private val _youtubeCompatibilityRequired = MutableStateFlow(false)
    val youtubeCompatibilityRequired: StateFlow<Boolean> =
        _youtubeCompatibilityRequired.asStateFlow()


    private val _youtubeAccount = MutableStateFlow<Account?>(null)
    val youtubeAccount: StateFlow<Account?> = _youtubeAccount.asStateFlow()

    fun init(context: Context, authStore: AuthStore) {
        appContext = context.applicationContext
        store = authStore
        Innertube.delegatedPageId = store.youtubePageId
        Innertube.authUserIndex = store.youtubeAuthUserIndex
        _youtubeAccount.value = store.storedYoutubeAccount()
    }

    val configured: Boolean
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() && OrbSupabase.configured

    val isSignedIn: Boolean
        get() = ::store.isInitialized && store.isSignedIn

    fun storedIdentity(): OrbGoogleIdentity? {
        if (!::store.isInitialized) return null
        val id = store.googleUniqueId ?: return null
        return OrbGoogleIdentity(
            uniqueId = id,
            email = store.googleEmail,
            displayName = store.googleDisplayName,
            avatarUrl = store.googleAvatarUrl,
        )
    }

    fun storedAccount(): Account? = storedIdentity()?.asAccount()


    fun storedYoutubeAccount(): Account? =
        if (::store.isInitialized) store.storedYoutubeAccount() else null

    /**
     * Opens Credential Manager, validates the ID token with Supabase, and saves
     * only public profile metadata locally. The YouTube scope is requested in a
     * separate [beginYoutubeAuthorization] call immediately afterwards.
     */
    suspend fun signInIdentity(activity: ComponentActivity): OrbGoogleIdentity {
        check(configured) {
            "Google sign-in is not configured. Set ORB_GOOGLE_WEB_CLIENT_ID and the Supabase Google provider."
        }

        // A pre-migration cookie is useful as an Innertube compatibility path,
        // but never let a cookie belonging to account A silently follow a new
        // Credential Manager login for account B.
        val legacyEmail = if (store.hasLegacyYouTubeSession) {
            runCatching { YtMusicRepository.account().getOrNull()?.email }
                .getOrNull()
                ?.trim()
                ?.takeIf(::looksLikeGoogleEmail)
        } else null

        val nonce = createSignInNonce()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(nonce.hashed)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = CredentialManager.create(activity).getCredential(
            request = request,
            context = activity,
        )
        val custom = response.credential as? CustomCredential
            ?: error("Google returned an unexpected credential type.")
        check(custom.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google returned an unexpected credential subtype."
        }
        val credential = GoogleIdTokenCredential.createFrom(custom.data)

        OrbSupabase.client.auth.signInWith(IDToken) {
            idToken = credential.idToken
            provider = Google
            // Supabase hashes this raw value and checks the nonce claim that
            // Google received above as SHA-256. This blocks ID-token replay.
            this.nonce = nonce.raw
        }

        val identity = OrbGoogleIdentity(
            uniqueId = credential.uniqueId,
            email = credential.email,
            displayName = credential.displayName,
            avatarUrl = credential.profilePictureUri?.toString(),
        )
        store.saveGoogleIdentity(
            uniqueId = identity.uniqueId,
            email = identity.email,
            displayName = identity.displayName,
            avatarUrl = identity.avatarUrl,
        )
        AppSettings.setCurrentAccountEmail(identity.email)

        if (
            legacyEmail != null &&
            !identity.email.isNullOrBlank() &&
            !legacyEmail.equals(identity.email, ignoreCase = true)
        ) {
            Log.w(TAG, "Discarding legacy YouTube cookie because it belongs to another Google account")
            store.cookie = null
            store.clearYoutubeIdentity()
            Innertube.cookie = null
            Innertube.delegatedPageId = null
            Innertube.authUserIndex = 0
            _youtubeAccount.value = null
        }

        // Google/Supabase identity is now established, but that alone is not a
        // YouTube Music account session. accountSignedIn only becomes true once
        // AuthorizationClient returns a usable YouTube credential (or a verified
        // legacy Music cookie is attached).
        Innertube.accountSignedIn = store.hasLegacyYouTubeSession || !store.youtubeAccessToken.isNullOrBlank()
        _youtubeCompatibilityRequired.value = false
        return identity
    }

    /** First-time use may return a resolution PendingIntent for user consent. */
    suspend fun beginYoutubeAuthorization(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context)
            .authorize(youtubeAuthorizationRequest())
            .awaitResult()

    /** Completes the PendingIntent launched by MainActivity. */
    fun finishYoutubeAuthorization(context: Context, data: Intent): AuthorizationResult =
        Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)

    fun acceptYoutubeAuthorization(result: AuthorizationResult): String {
        val token = requireNotNull(result.accessToken) {
            "Google authorized YouTube access but did not return an access token."
        }
        store.saveYoutubeAccessToken(
            token = token,
            expiresAtMs = System.currentTimeMillis() + TOKEN_CACHE_MS,
        )
        oauthRejectedUntilMs = 0L
        _youtubeCompatibilityRequired.value = false
        Innertube.accountSignedIn = true
        return token
    }


    /**
     * Resolve the YouTube Music profile behind the same Google authorization.
     * This is deliberately a probe of account_menu rather than a second login:
     * if OAuth is accepted the profile is remembered immediately; if the private
     * endpoint refuses the bearer, Innertube flips the compatibility flag.
     */
    suspend fun refreshYoutubeAccount(): Account? {
        if (!::store.isInitialized || !store.isSignedIn) return null
        Innertube.delegatedPageId = store.youtubePageId
        Innertube.authUserIndex = store.youtubeAuthUserIndex
        val account = YtMusicRepository.account().getOrNull() ?: return null
        val resolved = if (account.handle.isBlank()) {
            val rememberedHandle = store.storedYoutubeIdentity()?.handle.orEmpty()
            if (rememberedHandle.isNotBlank()) account.copy(handle = rememberedHandle) else account
        } else {
            account
        }
        store.saveYoutubeAccount(resolved)
        _youtubeAccount.value = resolved
        _youtubeCompatibilityRequired.value = false
        Innertube.accountSignedIn = true
        return resolved
    }

    /**
     * Lists the YouTube identities available under the browser-linked Google
     * account. YouTube Music exposes primary and Brand/channel identities from
     * account/accounts_list; no second Google login is required.
     *
     * A browser session is intentionally required here. OAuth can identify the
     * default YouTube profile when accepted by Innertube, but Brand-account
     * routing (`pageId`) comes from the private Music account surface.
     */
    suspend fun availableYoutubeIdentities(): List<YouTubeAccountIdentity> {
        if (!::store.isInitialized || !store.isSignedIn || !store.hasLegacyYouTubeSession) {
            return emptyList()
        }
        Innertube.cookie = store.cookie
        Innertube.delegatedPageId = store.youtubePageId
        Innertube.authUserIndex = store.youtubeAuthUserIndex
        val current = store.storedYoutubeIdentity()
        return YtMusicRepository.accounts()
            .getOrElse {
                Log.w(TAG, "Could not list YouTube identities: ${it.message}")
                emptyList()
            }
            .map { identity ->
                identity.copy(
                    isSelected = current?.stableKey == identity.stableKey ||
                        (current == null && identity.isSelected),
                )
            }
    }

    /**
     * Changes only the YouTube channel/profile, never the Orb/Supabase user.
     * The delegated page id is persisted and sent as X-Goog-PageId plus
     * context.user.onBehalfOfUser on later Innertube requests.
     */
    suspend fun selectYoutubeIdentity(identity: YouTubeAccountIdentity): Account {
        check(::store.isInitialized && store.isSignedIn) { "Connect your Google account to Orb first." }
        check(store.hasLegacyYouTubeSession) { "Link YouTube Music before choosing a channel." }

        val previousIdentity = store.storedYoutubeIdentity()
        val previousAccount = store.storedYoutubeAccount()
        val previousPageId = store.youtubePageId
        val previousAuthUser = store.youtubeAuthUserIndex

        return try {
            Innertube.cookie = store.cookie
            Innertube.delegatedPageId = identity.pageId
            Innertube.authUserIndex = previousAuthUser
            val resolved = YtMusicRepository.account().getOrThrow().let { account ->
                if (account.handle.isBlank() && identity.handle.isNotBlank()) {
                    account.copy(handle = identity.handle)
                } else {
                    account
                }
            }

            store.saveYoutubeRouting(identity.pageId, previousAuthUser)
            store.saveYoutubeIdentity(identity.copy(isSelected = true))
            store.saveYoutubeAccount(resolved)
            _youtubeAccount.value = resolved
            _youtubeCompatibilityRequired.value = false
            Innertube.accountSignedIn = true
            resolved
        } catch (error: Throwable) {
            store.saveYoutubeRouting(previousPageId, previousAuthUser)
            if (previousIdentity != null) store.saveYoutubeIdentity(previousIdentity)
            if (previousAccount != null) store.saveYoutubeAccount(previousAccount)
            Innertube.delegatedPageId = previousPageId
            Innertube.authUserIndex = previousAuthUser
            _youtubeAccount.value = previousAccount
            throw error
        }
    }

    /** Open the browser flow deliberately when channel routing cannot be resolved natively. */
    fun requestYoutubeChannelSwitch() {
        if (!::store.isInitialized || !store.isSignedIn) return
        _youtubeCompatibilityRequired.value = true
    }

    /**
     * Token provider used by Innertube and PlaybackService. Google Play services
     * silently returns a fresh token after the first grant; if interaction is
     * unexpectedly required while in background, null lets Innertube fall back
     * to a verified legacy browser session when one exists.
     */
    suspend fun youtubeAccessToken(): String? {
        if (!::store.isInitialized || !store.isSignedIn) return null
        val now = System.currentTimeMillis()
        if (now < oauthRejectedUntilMs) return null

        cachedYoutubeToken(now)?.let { return it }
        return tokenMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (lockedNow < oauthRejectedUntilMs) return@withLock null
            cachedYoutubeToken(lockedNow)?.let { return@withLock it }

            val result = runCatching {
                Identity.getAuthorizationClient(appContext)
                    .authorize(youtubeAuthorizationRequest())
                    .awaitResult()
            }.onFailure {
                Log.w(TAG, "Could not refresh YouTube OAuth token: ${it.message}")
            }.getOrNull() ?: return@withLock null

            if (result.hasResolution()) return@withLock null
            runCatching { acceptYoutubeAuthorization(result) }
                .onFailure { Log.w(TAG, "YouTube authorization returned no token: ${it.message}") }
                .getOrNull()
        }
    }

    /** Called by Innertube when the private Music endpoint rejects a bearer. */
    suspend fun onYoutubeOAuthRejected(token: String) {
        if (!::store.isInitialized) return
        // One rejection is enough for this process. Retrying the same private
        // Innertube endpoint every few minutes only adds latency; a new process
        // (or a fresh explicit sign-in) probes OAuth again in case Google changed.
        oauthRejectedUntilMs = Long.MAX_VALUE
        store.clearYoutubeAccessToken()
        // The Orb identity remains valid, but the YouTube account session does
        // not. If a verified browser cookie exists it can keep account features
        // alive; otherwise reads must behave as anonymous until compatibility
        // is linked instead of pretending the account is authenticated.
        Innertube.accountSignedIn = store.hasLegacyYouTubeSession
        _youtubeCompatibilityRequired.value = !store.hasLegacyYouTubeSession
        runCatching {
            Identity.getAuthorizationClient(appContext)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .awaitResult()
        }.onFailure {
            Log.w(TAG, "Could not clear rejected Google token from cache: ${it.message}")
        }
    }

    /** Keep the Orb/Supabase identity, but ask the UI to repair YouTube access. */
    fun requestYoutubeCompatibility() {
        if (!::store.isInitialized) return
        _youtubeCompatibilityRequired.value = store.isSignedIn && !store.hasLegacyYouTubeSession
    }

    /** Called when the legacy browser cookie itself is no longer accepted. */
    fun onLegacyYoutubeSessionRejected() {
        if (!::store.isInitialized) return
        store.cookie = null
        store.clearYoutubeIdentity()
        Innertube.cookie = null
        Innertube.delegatedPageId = null
        Innertube.authUserIndex = 0
        _youtubeAccount.value = null
        Innertube.accountSignedIn = !store.youtubeAccessToken.isNullOrBlank()
        _youtubeCompatibilityRequired.value = store.isSignedIn
    }

    /**
     * Existing WebView is retained only for Innertube compatibility, not Orb login.
     * The browser session is accepted only when YouTube Music reports the same
     * Google e-mail selected by Credential Manager; this prevents one Orb social
     * identity from silently operating another person's YouTube Music account.
     */
    suspend fun acceptLegacyYoutubeSession(session: YouTubeBrowserSession) {
        check(::store.isInitialized && store.isSignedIn) { "Connect your Google account to Orb first." }
        val previousCookie = store.cookie
        val previousInnertubeCookie = Innertube.cookie
        val previousPageId = Innertube.delegatedPageId
        val previousAuthUser = Innertube.authUserIndex

        // Apply the routing chosen inside the real YouTube Music page *before*
        // asking account_menu who is active. Brand channels share cookies with
        // the parent Google account; X-Goog-PageId/onBehalfOfUser is what makes
        // the selected channel distinct.
        Innertube.cookie = session.cookieHeader
        Innertube.delegatedPageId = session.delegatedPageId
        Innertube.authUserIndex = session.authUserIndex
        try {
            val youtubeAccount = YtMusicRepository.account().getOrThrow()
            val expectedEmail = store.googleEmail
            val reported = youtubeAccount.email.trim()
            if (
                looksLikeGoogleEmail(reported) &&
                !expectedEmail.isNullOrBlank() &&
                !reported.equals(expectedEmail, ignoreCase = true)
            ) {
                error(
                    "This YouTube Music session belongs to $reported, " +
                        "but Orb is connected to $expectedEmail. Use the same Google account."
                )
            }

            store.cookie = session.cookieHeader
            store.saveYoutubeRouting(session.delegatedPageId, session.authUserIndex)
            store.saveYoutubeAccount(youtubeAccount)
            store.saveYoutubeIdentity(
                YouTubeAccountIdentity(
                    name = youtubeAccount.name,
                    handle = youtubeAccount.handle.ifBlank { youtubeAccount.email.takeIf { it.startsWith('@') }.orEmpty() },
                    thumbnailUrl = youtubeAccount.thumbnailUrl,
                    pageId = session.delegatedPageId,
                    isSelected = true,
                ),
            )
            _youtubeAccount.value = youtubeAccount
            Innertube.cookie = session.cookieHeader
            Innertube.accountSignedIn = true
            _youtubeCompatibilityRequired.value = false
        } catch (error: Throwable) {
            store.cookie = previousCookie
            Innertube.cookie = previousInnertubeCookie
            Innertube.delegatedPageId = previousPageId
            Innertube.authUserIndex = previousAuthUser
            _youtubeCompatibilityRequired.value = true
            throw error
        }
    }

    @Deprecated("Use acceptLegacyYoutubeSession so channel routing is preserved.")
    suspend fun acceptLegacyYoutubeCookie(cookie: String) =
        acceptLegacyYoutubeSession(YouTubeBrowserSession(cookieHeader = cookie))

    suspend fun signOut(clearLegacyYouTubeSession: Boolean = true) {
        if (!::store.isInitialized) return
        val legacyCookie = if (clearLegacyYouTubeSession) null else store.cookie
        val legacyPageId = if (clearLegacyYouTubeSession) null else store.youtubePageId
        val legacyAuthUser = if (clearLegacyYouTubeSession) 0 else store.youtubeAuthUserIndex
        val legacyAccount = if (clearLegacyYouTubeSession) null else store.storedYoutubeAccount()
        val legacyIdentity = if (clearLegacyYouTubeSession) null else store.storedYoutubeIdentity()
        // RLS needs the live Supabase token to delete this row, so clear it first.
        runCatching { SocialRepository.clearNowPlaying() }
        if (OrbSupabase.configured) {
            runCatching { OrbSupabase.client.auth.signOut() }
        }
        runCatching {
            CredentialManager.create(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        }
        store.signOut()
        AppSettings.setCurrentAccountEmail(null)
        if (legacyCookie != null) {
            store.cookie = legacyCookie
            store.saveYoutubeRouting(legacyPageId, legacyAuthUser)
            legacyIdentity?.let(store::saveYoutubeIdentity)
            legacyAccount?.let(store::saveYoutubeAccount)
        }
        oauthRejectedUntilMs = 0L
        _youtubeCompatibilityRequired.value = false
        _youtubeAccount.value = legacyAccount
        Innertube.cookie = legacyCookie
        Innertube.delegatedPageId = legacyPageId
        Innertube.authUserIndex = legacyAuthUser
        Innertube.accountSignedIn = false
    }

    private fun looksLikeGoogleEmail(value: String): Boolean =
        value.contains('@') && !value.startsWith('@') && value.substringAfter('@').contains('.')

    private fun cachedYoutubeToken(now: Long): String? =
        store.youtubeAccessToken?.takeIf {
            store.youtubeAccessTokenExpiresAtMs > now + TOKEN_REFRESH_GUARD_MS
        }

    private fun youtubeAuthorizationRequest(): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_SCOPE)))
        // AuthorizationClient can otherwise choose any eligible Google account
        // on a multi-account device. Bind the grant to the exact account that
        // Credential Manager just established as Orb's identity.
        store.googleEmail?.takeIf { it.isNotBlank() }?.let { email ->
            builder.setAccount(AndroidAccount(email, "com.google"))
        }
        return builder.build()
    }

    private data class SignInNonce(val raw: String, val hashed: String)

    private fun createSignInNonce(): SignInNonce {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hashed = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return SignInNonce(raw = raw, hashed = hashed)
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
}
