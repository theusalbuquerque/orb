package com.music.orb.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.music.orb.ui.components.OrbGlassButton as Button
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.R
import com.music.orb.data.model.*
import com.music.orb.data.spotify.*
import com.music.orb.ui.components.PAGE_GUTTER
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// Adapted from BitChord v1.8 SpotifyLibraryScreen (GPL-3.0); see notice.
private const val SPOTIFY_LOGIN_LAYOUT_FIX = """
    (function () {
        if (document.getElementById('orb-spotify-login-layout')) return;
        var style = document.createElement('style');
        style.id = 'orb-spotify-login-layout';
        style.textContent =
            'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
            'body > div { height: auto !important; min-height: 100% !important; }' +
            'main { position: static !important; height: auto !important; min-height: 100dvh !important; max-height: none !important; overflow: visible !important; }';
        (document.head || document.documentElement).appendChild(style);
    })();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savingSession by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var pendingCookie by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        kotlinx.coroutines.delay(30_000)
        if (loading) { loading = false; error = true }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogGlassColor = orbOverlayGlassBaseColor()
        Surface(
            Modifier.fillMaxSize().orbGlassSurface(dialogGlassColor, cornerRadius = 0.dp),
            color = Color.Transparent,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Spotify", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.profile_cancel)) }
                }
                Text(stringResource(R.string.spotify_session_note), modifier = Modifier.padding(16.dp))
                if (pendingCookie != null) Button(
                    onClick = {
                        val cookieToSave = pendingCookie ?: return@Button
                        savingSession = true
                        scope.launch {
                            try { SpotifySession.init(context); SpotifySession.save(cookieToSave); onClose() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = true }
                            finally { savingSession = false }
                        }
                    }, enabled = !savingSession, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.spotify_connect)) }
                if (error) Text(stringResource(R.string.spotify_connection_error), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (error) TextButton(onClick = {
                    error = false; loading = true; retry++
                }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.spotify_retry)) }
                key(retry) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { WebView(it).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            settings.isAlgorithmicDarkeningAllowed = false
                        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                            @Suppress("DEPRECATION")
                            settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                        }
                        setBackgroundColor(android.graphics.Color.rgb(18, 18, 18))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Spotify's login expects a full browser identity rather than Android's wv UA.
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        settings.loadsImagesAutomatically = true
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, progress: Int) { if (!error) loading = progress < 100 }
                        }
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                // Auth can navigate through external identity providers and consent pages.
                                if (request.url.scheme == "https") return false
                                if (request.isForMainFrame) { error = true; loading = false }
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                error = false
                            }
                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, failure: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) {
                                    loading = false; error = true
                                    android.util.Log.w("OrbSpotifyLogin", "Main page load failed: ${failure.errorCode}")
                                }
                            }
                            override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                                if (request.isForMainFrame) {
                                    loading = false; error = true
                                    android.util.Log.w("OrbSpotifyLogin", "Main page HTTP ${response.statusCode}")
                                }
                            }
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                loading = false; error = true
                                view.stopLoading()
                                return true
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                val pageHost = android.net.Uri.parse(url.orEmpty()).host.orEmpty()
                                if (pageHost == "spotify.com" || pageHost.endsWith(".spotify.com")) {
                                    view.evaluateJavascript(SPOTIFY_LOGIN_LAYOUT_FIX, null)
                                }
                                if (!error) view.evaluateJavascript("(document.body && document.body.innerText.trim().length > 0) || document.querySelector('input,button,iframe') !== null") { populated ->
                                    if (populated == "false") error = true
                                }
                                val host = android.net.Uri.parse(url.orEmpty()).host.orEmpty()
                                if (host != "open.spotify.com") return
                                val cookie = CookieManager.getInstance().getCookie("https://open.spotify.com")
                                    ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("sp_dc=") }
                                    ?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: return
                                pendingCookie = cookie
                            }
                        }
                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    } },
                    onReset = null,
                    onRelease = { it.stopLoading(); it.destroy() },
                )
                }
            }
        }
    }
}

@Composable
fun SpotifyLibraryShelf(onItemClick: (ShelfItem) -> Unit, query: String = "") {
    val context = LocalContext.current
    val connected by SpotifySession.connected.collectAsStateWithLifecycle()
    var ready by remember { mutableStateOf(false) }
    var initFailed by remember { mutableStateOf(false) }
    val library by SpotifyLibraryShelfStore.state.collectAsStateWithLifecycle()
    val playlists = library.playlists
    val loadingLibrary = library.loading
    val failed = library.failed
    val liked = stringResource(R.string.spotify_liked_songs)
    val shelfTitle = stringResource(R.string.spotify_from_section)
    LaunchedEffect(Unit) {
        try { SpotifySession.init(context); ready = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { initFailed = true }
    }
    if (initFailed) Text(stringResource(R.string.spotify_connection_error), modifier = Modifier.padding(PAGE_GUTTER))
    LaunchedEffect(ready, connected) {
        if (ready && connected) SpotifyLibraryShelfStore.load()
    }
    if (ready && connected) {
        if (failed) {
            Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
                Text(stringResource(R.string.spotify_connection_error))
                TextButton(onClick = { com.music.orb.data.canvas.SpotifyToken.invalidate(); SpotifyLibraryShelfStore.load(force = true) }) { Text(stringResource(R.string.spotify_retry)) }
            }
        } else if (loadingLibrary) {
            Row(Modifier.padding(PAGE_GUTTER)) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.spotify_loading)) }
        } else {
            val matching = playlists.filter {
                query.isBlank() || (if (it.id == SpotifyLibrary.LIKED_ID) liked else it.name)
                    .contains(query, ignoreCase = true) || it.owner.orEmpty().contains(query, ignoreCase = true)
            }
            if (matching.isNotEmpty()) Shelf(
                shelf = HomeShelf(shelfTitle, matching.map {
                    ShelfItem(title = if (it.id == SpotifyLibrary.LIKED_ID) liked else it.name,
                        subtitle = it.owner.orEmpty(), thumbnailUrl = it.imageUrl, videoId = null,
                        browseId = SPOTIFY_PAGE_PREFIX + it.id, type = BrowseType.PLAYLIST)
                }),
                onItemClick = onItemClick,
                expressive = com.music.orb.ui.flavor.OrbFlavorUi.expressive,
            )
        }
    }
}
