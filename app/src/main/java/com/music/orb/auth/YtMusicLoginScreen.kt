package com.music.orb.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONTokener
import org.json.JSONObject

private const val MUSIC_ORIGIN = "https://music.youtube.com"

/**
 * Browser credential used only when a private YouTube Music endpoint refuses
 * the native Google OAuth bearer. [delegatedPageId] and [authUserIndex] are the
 * identity-routing values the real YouTube web client uses for Brand/channel
 * accounts, so Orb can reopen the same channel on later requests.
 */
data class YouTubeBrowserSession(
    val cookieHeader: String,
    val delegatedPageId: String? = null,
    val authUserIndex: Int = 0,
)

/**
 * YouTube Music compatibility link.
 *
 * This is not a second Orb account. Credential Manager already established the
 * Google identity; this WebView is opened only if the private Music Innertube
 * surface needs its browser session, or when the listener explicitly wants to
 * switch the YouTube channel used by Orb.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginScreen(
    onSessionCaptured: (YouTubeBrowserSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    private var captured = false
                    private var capturing = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val webView = view ?: return
                        if (captured || capturing || url?.startsWith(MUSIC_ORIGIN) != true) return
                        val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                        if (cookies.isNullOrBlank() || "SAPISID" !in cookies) return

                        // The active YouTube channel is not encoded in the cookie.
                        // YouTube routes Brand/channel identities with these ytcfg
                        // values, so capture them from the authenticated Music page.
                        capturing = true
                        webView.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var cfg = window.ytcfg;
                                var get = cfg && cfg.get ? function(k) { return cfg.get(k); } : function(k) {
                                  return (window.yt && window.yt.config_) ? window.yt.config_[k] : null;
                                };
                                return JSON.stringify({
                                  pageId: get('DELEGATED_SESSION_ID') || null,
                                  authUser: get('SESSION_INDEX') == null ? 0 : Number(get('SESSION_INDEX'))
                                });
                              } catch (e) {
                                return JSON.stringify({pageId:null, authUser:0});
                              }
                            })();
                            """.trimIndent(),
                        ) { raw ->
                            capturing = false
                            if (!captured) {
                                val routing = parseRouting(raw)
                                captured = true
                                onSessionCaptured(
                                    YouTubeBrowserSession(
                                        cookieHeader = cookies,
                                        delegatedPageId = routing.first,
                                        authUserIndex = routing.second,
                                    ),
                                )
                            }
                        }
                    }
                }

                loadUrl(
                    "https://accounts.google.com/ServiceLogin" +
                        "?ltmpl=music&service=youtube&passive=true" +
                        "&continue=https%3A%2F%2Fmusic.youtube.com%2F",
                )
            }
        },
    )
}

private fun parseRouting(rawJavascriptResult: String?): Pair<String?, Int> = runCatching {
    val raw = rawJavascriptResult.orEmpty()
    val decoded = when (val value = JSONTokener(raw).nextValue()) {
        is String -> value
        is JSONObject -> value.toString()
        else -> raw
    }
    val json = JSONObject(decoded)
    val pageId = json.optString("pageId").takeIf { it.isNotBlank() && it != "null" }
    val authUser = json.optInt("authUser", 0).coerceAtLeast(0)
    pageId to authUser
}.getOrDefault(null to 0)
