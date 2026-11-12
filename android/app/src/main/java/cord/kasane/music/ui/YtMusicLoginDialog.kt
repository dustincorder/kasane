package cord.kasane.music.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cord.kasane.music.auth.AccountChannel
import cord.kasane.music.auth.CapturedWebSession
import cord.kasane.music.auth.WebSessionParser
import cord.kasane.music.ui.theme.AccentRose
import cord.kasane.music.ui.theme.Gray400
import cord.kasane.music.ui.theme.Gray850
import cord.kasane.music.ui.theme.Gray950
import cord.kasane.music.ui.theme.White
import cord.kasane.music.ui.theme.KasaneDarkColorScheme
import cord.kasane.music.ui.theme.KasaneTypography

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val MUSIC_ORIGIN = "https://music.youtube.com"
private val YTCFG_PROBE = """(function(){try{var c=window.ytcfg;if(!c||!c.get)return null;function g(k){var v=c.get(k);return v==null?null:String(v)}return {loggedIn:!!c.get('LOGGED_IN'),visitorData:g('VISITOR_DATA'),dataSyncId:g('DATASYNC_ID'),authUser:g('SESSION_INDEX')}}catch(e){return null}})()"""

@Composable
fun YtMusicLoginDialog(
    channels: List<AccountChannel>?,
    isCheckingChannels: Boolean,
    errorMessage: String?,
    onCapture: (CapturedWebSession) -> Unit,
    onConfirmChannel: (AccountChannel) -> Unit,
    onRetryChannels: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pageReady by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var sessionCaptured by remember { mutableStateOf(false) }
    var didAutoCapture by remember { mutableStateOf(false) }
    var didCookieCapture by remember { mutableStateOf(false) }
    val currentOnCapture by rememberUpdatedState(onCapture)
    val currentOnConfirmChannel by rememberUpdatedState(onConfirmChannel)
    var loginTimeout by remember { mutableStateOf<Runnable?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose {
            loginTimeout?.let(mainHandler::removeCallbacks)
            webView?.apply { stopLoading(); webChromeClient = null; webViewClient = WebViewClient(); destroy() }
        }
    }
    LaunchedEffect(channels) {
        if (channels != null) {
            webView?.apply { stopLoading(); destroy() }
            webView = null
            loginTimeout?.let(mainHandler::removeCallbacks)
            loginTimeout = null
            if (channels.size == 1) currentOnConfirmChannel(channels.single())
        }
    }
    LaunchedEffect(sessionCaptured) {
        if (sessionCaptured) {
            webView?.apply { stopLoading(); destroy() }
            webView = null
            loginTimeout?.let(mainHandler::removeCallbacks)
            loginTimeout = null
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Gray950) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close sign in", tint = White) }
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to YouTube Music", color = White, style = MaterialTheme.typography.titleMedium)
                        Text("Google sign in stays inside this temporary secure web view.", color = Gray400, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (channels == null && sessionCaptured) {
                    Text("YouTube Music session detected. Checking your channel…", color = Gray400,
                        modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                    if (isCheckingChannels) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
                    errorMessage?.let { Text(it, color = AccentRose, modifier = Modifier.padding(horizontal = 20.dp)) }
                    if (!isCheckingChannels && errorMessage != null) {
                        TextButton(onClick = onRetryChannels, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Retry channel check") }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                } else if (channels == null) {
                    if (progress) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
                    (pageError ?: errorMessage)?.let { Text(it, color = AccentRose, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            if (android.os.Build.VERSION.SDK_INT >= 27) WebView.startSafeBrowsing(ctx) { }
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
                                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                                    callback?.invoke(origin, false, false)
                                }
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
                            }
                            webViewClient = object : WebViewClient() {
                                private val handler = Handler(Looper.getMainLooper())
                                private var deadline: Runnable? = null
                                private var attempts = 0
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (WebSessionParser.isTrustedHttpsUrl(url)) return false
                                    val blockedHost = request.url.host?.takeIf { it.length <= 253 } ?: "unknown host"
                                    val blockedScheme = request.url.scheme?.takeIf { it.length <= 24 } ?: "unknown scheme"
                                    android.util.Log.w("KasaneWebAuth", "Blocked navigation scheme=$blockedScheme host=$blockedHost")
                                    pageError = "Google tried to open a $blockedScheme page on $blockedHost, which Kasane does not allow. Retry or cancel sign in."
                                    pageReady = false
                                    progress = false
                                    view.stopLoading()
                                    return true
                                }
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    pageReady = false
                                    progress = true
                                    pageError = null
                                    if (!url.isNullOrBlank() && !WebSessionParser.isTrustedHttpsUrl(url)) {
                                        val blockedUri = runCatching { java.net.URI(url) }.getOrNull()
                                        val blockedHost = blockedUri?.host?.takeIf { it.length <= 253 } ?: "unknown host"
                                        val blockedScheme = blockedUri?.scheme?.takeIf { it.length <= 24 } ?: "unknown scheme"
                                        android.util.Log.w("KasaneWebAuth", "Blocked page start scheme=$blockedScheme host=$blockedHost")
                                        pageError = "Google tried to open a $blockedScheme page on $blockedHost, which Kasane does not allow. Retry or cancel sign in."
                                        progress = false
                                        view.stopLoading()
                                    }
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                                    progress = false
                                    if (url == null || !WebSessionParser.isTrustedHttpsUrl(url)) return
                                    if (url.startsWith(MUSIC_ORIGIN)) {
                                        attempts = 0
                                        fun checkPage() {
                                            if (!WebSessionParser.isTrustedHttpsUrl(view.url) || view.url?.startsWith(MUSIC_ORIGIN) != true) return
                                            view.evaluateJavascript(YTCFG_PROBE) { raw ->
                                                val cookie = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                                                // ytcfg can omit visitorData/loggedIn on a warm WebView restore even
                                                // while valid account cookies are present. Let InnerTube verify them.
                                                val fullSession = WebSessionParser.capture(cookie, raw)
                                                val captured = fullSession ?: WebSessionParser.captureCookies(cookie)
                                                pageReady = captured != null
                                                if (fullSession != null && !didAutoCapture) {
                                                    didAutoCapture = true
                                                    sessionCaptured = true
                                                    currentOnCapture(fullSession)
                                                } else if (fullSession == null && captured != null && !didCookieCapture) {
                                                    // Apply available cookies immediately, but keep the WebView alive
                                                    // until YTM supplies channel context or the API validates identity.
                                                    didCookieCapture = true
                                                    currentOnCapture(captured)
                                                }
                                                if (!didAutoCapture && attempts++ < 8) handler.postDelayed({ checkPage() }, 900)
                                            }
                                        }
                                        checkPage()
                                    } else if (WebSessionParser.isGoogleAccountUrl(url)) {
                                        // Google may show optional profile setup after authentication. If YouTube's
                                        // own scoped auth cookies are already present, hand them to InnerTube now;
                                        // do not wait for the personalized Music home page to render.
                                        attempts = 0
                                        fun checkYtCookies() {
                                            if (!WebSessionParser.isGoogleAccountUrl(view.url) || didCookieCapture || didAutoCapture) return
                                            val captured = WebSessionParser.captureCookies(
                                                CookieManager.getInstance().getCookie(MUSIC_ORIGIN))
                                            if (captured != null) {
                                                didCookieCapture = true
                                                currentOnCapture(captured)
                                            } else if (attempts++ < 20) {
                                                handler.postDelayed({ checkYtCookies() }, 500)
                                            }
                                        }
                                        checkYtCookies()
                                        // Google occasionally returns a 200 refusal page. Read only a fixed phrase check on this trusted origin.
                                        view.evaluateJavascript("(function(){var t=(document.body&&document.body.innerText||'').toLowerCase();return t.includes('this browser or app may not be secure')||t.includes('this browser may not be secure')})()") { raw ->
                                            if (raw == "true") pageError = "Google refused sign in inside this embedded browser. Kasane will not bypass that restriction. You can cancel and continue in Guest Mode."
                                        }
                                    }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame && response.statusCode in listOf(400, 403, 405)) {
                                        pageError = "Google or YouTube refused this embedded sign-in page (HTTP ${response.statusCode}). Kasane will not bypass the restriction."
                                        pageReady = false
                                        progress = false
                                    }
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame && pageError?.contains("which Kasane does not allow") != true) {
                                        pageError = "Could not load the sign-in page. Check your connection and retry."
                                        pageReady = false
                                        progress = false
                                    }
                                }
                                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
                                override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) { callback.backToSafety(true) }
                                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                    pageError = "The secure sign-in view stopped unexpectedly. Close it and try again."
                                    return true
                                }
                            }
                            webView = this
                            val timeout = Runnable {
                                pageReady = false
                                pageError = "This sign-in view expired after 15 minutes. Close it and start again."
                                stopLoading()
                            }
                            loginTimeout = timeout
                            mainHandler.postDelayed(timeout, 15 * 60 * 1000L)
                            loadUrl(LOGIN_URL)
                        }
                    }, modifier = Modifier.weight(1f).fillMaxWidth(), update = { _: WebView -> })
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Continue on the official Google and YouTube pages. Kasane never asks for your password.", color = Gray400, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (pageError != null) TextButton(onClick = {
                            pageError = null
                            progress = true
                            pageReady = false
                            webView?.loadUrl(LOGIN_URL)
                        }) { Text("Retry") }
                        Button(enabled = pageReady && !isCheckingChannels, onClick = {
                            val view = webView
                            if (view == null || !WebSessionParser.isTrustedHttpsUrl(view.url) || view.url?.startsWith(MUSIC_ORIGIN) != true) {
                                pageError = "Open YouTube Music and finish sign in before checking the account."
                            } else {
                                val cookie = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                                view.evaluateJavascript(YTCFG_PROBE) { raw ->
                                    val capturedSession = WebSessionParser.capture(cookie, raw)
                                        ?: WebSessionParser.captureCookies(cookie)
                                    if (capturedSession == null) pageError = "No signed-in YouTube Music session was found. Finish sign in on the page and try again."
                                    else {
                                        didAutoCapture = true
                                        sessionCaptured = true
                                        currentOnCapture(capturedSession)
                                    }
                                }
                            }
                        }) { Text("Check account") }
                    }
                } else {
                    Text(
                        if (channels.size == 1) "Connecting account…" else "Choose the YouTube channel Kasane should use",
                        color = White, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    if (channels.size == 1) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (isCheckingChannels) CircularProgressIndicator(color = AccentRose)
                            errorMessage?.let { Text(it, color = AccentRose, modifier = Modifier.padding(vertical = 12.dp)) }
                            if (!isCheckingChannels && errorMessage != null) {
                                Button(onClick = { currentOnConfirmChannel(channels.single()) }) { Text("Retry") }
                            }
                        }
                    } else if (isCheckingChannels) Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = AccentRose); Text("Checking your YouTube session…", color = Gray400, modifier = Modifier.padding(12.dp))
                    } else {
                        errorMessage?.let { Text(it, color = AccentRose, modifier = Modifier.padding(horizontal = 16.dp)) }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(channels, key = { channel -> channel.pageId ?: channel.dataSyncId ?: channel.title }) { channel ->
                                val key = channel.pageId ?: channel.dataSyncId ?: channel.title
                                Row(Modifier.fillMaxWidth().clickable { selectedKey = key }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedKey == key, onClick = { selectedKey = key })
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(channel.title, color = White)
                                        channel.subtitle?.let { Text(it, color = Gray400, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                    if (channels.size > 1) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onRetryChannels, enabled = !isCheckingChannels) { Text("Retry") }
                            Button(
                                enabled = !isCheckingChannels && selectedKey != null,
                                colors = ButtonDefaults.buttonColors(disabledContainerColor = Gray850, disabledContentColor = Gray400),
                                onClick = {
                                    channels.firstOrNull { (it.pageId ?: it.dataSyncId ?: it.title) == selectedKey }?.let(onConfirmChannel)
                                }) { Text("Confirm channel") }
                        }
                    }
                }
            }
        }
    }
}

/** Clears cookies belonging to the app's WebView store; it does not sign out of Chrome or other apps. */
fun clearYtMusicWebSession(onComplete: (() -> Unit)? = null) {
    CookieManager.getInstance().removeAllCookies { onComplete?.invoke() }
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun LoginDialogPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            YtMusicLoginDialog(
                channels = listOf(
                    AccountChannel("Kasane Preview", "YouTube channel", "UCpreview", "preview||user", null, false),
                    AccountChannel("Kasane Artist", "Brand account", "UCartistpreview", "artist||user", null, false),
                ),
                isCheckingChannels = false,
                errorMessage = null,
                onCapture = {},
                onConfirmChannel = {},
                onRetryChannels = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun SingleChannelLoginPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            YtMusicLoginDialog(
                channels = listOf(AccountChannel("Kasane Preview", "YouTube channel", "UCpreview", "preview||user", null, true)),
                isCheckingChannels = true,
                errorMessage = null,
                onCapture = {},
                onConfirmChannel = {},
                onRetryChannels = {},
                onDismiss = {},
            )
        }
    }
}
