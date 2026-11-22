package cord.kasane.music.provider.verification

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hosts `kasane-verifier.js` in an offscreen System WebView on the app-owned
 * `appassets.androidplatform.net` origin. Network loads are blocked; native code
 * passes in every challenge, token and player script. Nothing is persisted.
 */
class WebViewVerificationRuntime(private val context: Context) : VerificationRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val ids = AtomicLong(1)
    private val startLock = Mutex()
    @Volatile private var view: WebView? = null

    // Google issues an integrity token that GVS rejects when the challenge requests carry OkHttp's User-Agent.
    override val userAgent: String by lazy { android.webkit.WebSettings.getDefaultUserAgent(context) }

    private inner class Bridge {
        @JavascriptInterface
        fun reply(id: String, payload: String) {
            id.toLongOrNull()?.let { pending.remove(it)?.complete(payload) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureView(): WebView = view ?: startLock.withLock {
        view ?: withContext(Dispatchers.Main) {
            val bundle = withContext(Dispatchers.IO) {
                context.assets.open(BUNDLE).bufferedReader().use { it.readText() }
            }
            val loaded = CompletableDeferred<Unit>()
            val created = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
                }
                addJavascriptInterface(Bridge(), "KasaneHost")
                loadDataWithBaseURL(ORIGIN, PAGE, "text/html", "UTF-8", null)
            }
            try {
                withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
            } catch (e: Exception) {
                created.destroy()
                throw VerificationException("verification webview did not load")
            }
            created.evaluateJavascript("globalThis.__kasaneReply = (id, p) => KasaneHost.reply(String(id), p);", null)
            created.evaluateJavascript(bundle, null)
            view = created
            created
        }
    }

    override suspend fun call(method: String, args: JSONArray): Any? {
        val webView = ensureView()
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        val script = "globalThis.KasaneVerifier ? KasaneVerifier.run($id, ${JSONObject.quote(method)}, $args) : " +
            "__kasaneReply($id, JSON.stringify({ ok: false, error: 'verification-runtime-missing' }));"
        main.post { webView.evaluateJavascript(script, null) }
        val payload = try {
            withTimeout(CALL_TIMEOUT_MS) { reply.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw VerificationException("verification runtime did not respond")
        } finally {
            pending.remove(id)
        }
        val result = JSONObject(payload)
        if (!result.optBoolean("ok")) throw VerificationException(result.optString("error", "unknown error"))
        return result.opt("value")
    }

    companion object {
        const val BUNDLE = "kasane-verifier.js"
        private const val ORIGIN = "https://appassets.androidplatform.net/"
        private const val PAGE = "<!doctype html><html><head><title>Kasane verification</title></head><body></body></html>"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val CALL_TIMEOUT_MS = 45_000L
    }
}
