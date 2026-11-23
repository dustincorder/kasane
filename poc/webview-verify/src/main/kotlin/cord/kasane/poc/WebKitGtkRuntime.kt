package cord.kasane.poc

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import cord.kasane.music.provider.verification.VerificationException
import cord.kasane.music.provider.verification.VerificationRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * The system WebKitGTK (webkit2gtk-4.1) as a verification runtime, called through JNA; nothing is bundled.
 * One offscreen web view on a dedicated GTK thread, an ephemeral website-data manager (no cookies, storage or
 * cache on disk), a CSP that forbids every network request from the page, and a script-message bridge for
 * replies. [close] destroys the view and context, which ends WebKit's web and network processes.
 */
class WebKitGtkRuntime(private val bundle: String) : VerificationRuntime, AutoCloseable {
    private val gtk = NativeLibrary.getInstance("gtk-3")
    private val gobject = NativeLibrary.getInstance("gobject-2.0")
    private val glib = NativeLibrary.getInstance("glib-2.0")
    private val webkit = NativeLibrary.getInstance("webkit2gtk-4.1")
    private val jsc = NativeLibrary.getInstance("javascriptcoregtk-4.1")
    private fun f(lib: NativeLibrary, name: String): Function = lib.getFunction(name)

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val ids = AtomicLong(1)
    private val ready = CompletableDeferred<Unit>()
    private var view: Pointer? = null
    private var window: Pointer? = null
    private var loop: Pointer? = null
    @Volatile private var ua: String = ""

    // JNA callbacks must stay strongly reachable for as long as GTK may call them.
    private val onMessage = object : Callback {
        @Suppress("unused") fun callback(manager: Pointer?, result: Pointer?, data: Pointer?) {
            val value = f(webkit, "webkit_javascript_result_get_js_value").invokePointer(arrayOf(result)) ?: return
            val text = f(jsc, "jsc_value_to_string").invokePointer(arrayOf(value)) ?: return
            val json = text.getString(0, "UTF-8")
            f(glib, "g_free").invokeVoid(arrayOf(text))
            runCatching {
                val o = JSONObject(json)
                pending.remove(o.getLong("id"))?.complete(o.getString("payload"))
            }
        }
    }
    private val onLoad = object : Callback {
        @Suppress("unused") fun callback(view: Pointer?, event: Int, data: Pointer?) { if (event == 3) ready.complete(Unit) }
    }
    private val drain = object : Callback {
        @Suppress("unused") fun callback(data: Pointer?): Int {
            while (true) (tasks.poll() ?: break).invoke()
            return 0 // G_SOURCE_REMOVE
        }
    }

    private val thread = Thread({
        // Headless use: no compositing, no GPU/DMA-BUF renderer (an offscreen verifier draws nothing).
        val libc = NativeLibrary.getInstance("c")
        for ((k, v) in listOf("WEBKIT_DISABLE_COMPOSITING_MODE" to "1", "WEBKIT_DISABLE_DMABUF_RENDERER" to "1", "GDK_DISABLE" to "gl"))
            libc.getFunction("setenv").invokeInt(arrayOf(k, v, 1))
        f(gtk, "gtk_init").invokeVoid(arrayOf(null, null))
        val manager = f(webkit, "webkit_website_data_manager_new_ephemeral").invokePointer(emptyArray())
        val context = f(webkit, "webkit_web_context_new_with_website_data_manager").invokePointer(arrayOf(manager))
        val content = f(webkit, "webkit_user_content_manager_new").invokePointer(emptyArray())
        f(webkit, "webkit_user_content_manager_register_script_message_handler").invokeInt(arrayOf(content, "kasane"))
        f(gobject, "g_signal_connect_data").invokeLong(arrayOf(content, "script-message-received::kasane", onMessage, null, null, 0))
        val v = f(gobject, "g_object_new").invokePointer(arrayOf(
            f(webkit, "webkit_web_view_get_type").invokeLong(emptyArray()),
            "web-context", context, "user-content-manager", content, null))
        val settings = f(webkit, "webkit_web_view_get_settings").invokePointer(arrayOf(v))
        f(webkit, "webkit_settings_set_enable_javascript").invokeVoid(arrayOf(settings, 1))
        f(webkit, "webkit_settings_set_allow_file_access_from_file_urls").invokeVoid(arrayOf(settings, 0))
        f(webkit, "webkit_settings_set_enable_html5_local_storage").invokeVoid(arrayOf(settings, 0))
        f(webkit, "webkit_settings_set_enable_html5_database").invokeVoid(arrayOf(settings, 0))
        f(webkit, "webkit_settings_set_enable_media").invokeVoid(arrayOf(settings, 0))
        f(webkit, "webkit_settings_set_enable_webgl").invokeVoid(arrayOf(settings, 0))
        f(webkit, "webkit_settings_set_hardware_acceleration_policy").invokeVoid(arrayOf(settings, 1)) // NEVER
        ua = f(webkit, "webkit_settings_get_user_agent").invokePointer(arrayOf(settings))?.getString(0) ?: ""
        val w = f(gtk, "gtk_offscreen_window_new").invokePointer(emptyArray())
        f(gtk, "gtk_container_add").invokeVoid(arrayOf(w, v))
        f(gtk, "gtk_widget_show_all").invokeVoid(arrayOf(w))
        f(gobject, "g_signal_connect_data").invokeLong(arrayOf(v, "load-changed", onLoad, null, null, 0))
        f(webkit, "webkit_web_view_load_html").invokeVoid(arrayOf(v, PAGE, ORIGIN))
        view = v; window = w
        f(gobject, "g_object_unref").invokeVoid(arrayOf(content))
        f(gobject, "g_object_unref").invokeVoid(arrayOf(context))
        f(gobject, "g_object_unref").invokeVoid(arrayOf(manager))
        val l = f(glib, "g_main_loop_new").invokePointer(arrayOf(null, 0))
        loop = l
        f(glib, "g_main_loop_run").invokeVoid(arrayOf(l))
    }, "kasane-webkitgtk").apply { isDaemon = true; start() }

    private fun onGtk(block: () -> Unit) {
        tasks.add(block)
        f(glib, "g_idle_add").invokeInt(arrayOf(drain, null))
    }

    private fun evaluate(script: String) = onGtk {
        f(webkit, "webkit_web_view_evaluate_javascript").invokeVoid(arrayOf(view, script, -1L, null, null, null, null, null))
    }

    private var started = false
    private suspend fun ensureStarted() {
        if (started) return
        try { withTimeout(20_000) { ready.await() } } catch (e: TimeoutCancellationException) { throw VerificationException("webkitgtk did not load") }
        evaluate("globalThis.__kasaneReply = (id, p) => window.webkit.messageHandlers.kasane.postMessage(JSON.stringify({ id: String(id), payload: p }));")
        evaluate(bundle)
        started = true
    }

    override val userAgent: String get() = ua

    override suspend fun call(method: String, args: JSONArray): Any? {
        ensureStarted()
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        evaluate("globalThis.KasaneVerifier ? KasaneVerifier.run($id, ${JSONObject.quote(method)}, $args) : " +
            "__kasaneReply($id, JSON.stringify({ ok: false, error: 'verification-runtime-missing' }));")
        val payload = try { withTimeout(45_000) { reply.await() } } catch (e: TimeoutCancellationException) {
            throw VerificationException("verification runtime did not respond")
        } finally { pending.remove(id) }
        val result = JSONObject(payload)
        if (!result.optBoolean("ok")) throw VerificationException(result.optString("error", "unknown error"))
        return result.opt("value")
    }

    override fun close() {
        val done = CountDownLatch(1)
        onGtk {
            window?.let { f(gtk, "gtk_widget_destroy").invokeVoid(arrayOf(it)) }
            view = null; window = null
            loop?.let { f(glib, "g_main_loop_quit").invokeVoid(arrayOf(it)) }
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        thread.join(5_000)
    }

    companion object {
        private const val ORIGIN = "https://verifier.kasane.invalid/"
        // connect-src/img-src/etc. 'none': the page cannot reach the network; the native side passes all data in.
        private const val PAGE = "<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'none'; script-src 'unsafe-eval' 'unsafe-inline'\"><title>Kasane verification</title></head><body></body></html>"
    }
}
