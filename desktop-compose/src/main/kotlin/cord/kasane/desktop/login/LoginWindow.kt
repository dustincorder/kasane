package cord.kasane.desktop.login

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.json.JSONObject

/**
 * The visible Google sign-in window inside the `--login` helper process: system WebKitGTK via JNA,
 * nothing bundled. An ephemeral website-data manager keeps cookies and storage in memory only —
 * the captured session leaves this process once, over stdout, and the desktop itself decides where
 * to persist it (Secret Service). One JSON result line is printed, then the process exits:
 * {"ok":true,"cookie":"…","config":{…}} on success or {"ok":false,"error":"…"} otherwise.
 */
class LoginWindow : AutoCloseable {
    private val gtk = NativeLibrary.getInstance("gtk-3")
    private val gobject = NativeLibrary.getInstance("gobject-2.0")
    private val glib = NativeLibrary.getInstance("glib-2.0")
    private val webkit = NativeLibrary.getInstance("webkit2gtk-4.1")
    private val jsc = NativeLibrary.getInstance("javascriptcoregtk-4.1")
    private val soup = NativeLibrary.getInstance("soup-3.0")
    private fun f(lib: NativeLibrary, name: String): Function = lib.getFunction(name)

    private val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private var loop: Pointer? = null
    private var window: Pointer? = null
    private var view: Pointer? = null
    private var cookieManager: Pointer? = null
    @Volatile private var reported = false
    private val out = System.out

    private fun emit(result: JSONObject) {
        synchronized(out) {
            if (reported) return
            reported = true
            out.println(result.toString())
            out.flush()
        }
        tasks.add { loop?.let { f(glib, "g_main_loop_quit").invokeVoid(arrayOf(it)) } }
        f(glib, "g_idle_add").invokeInt(arrayOf(drain, null))
    }

    /** Stage markers on stderr only — never cookie values or other secrets. */
    private fun stage(name: String) = System.err.println("kasane-login: $name")

    /**
     * A progress line for the parent process ({"phase":"…"}) — not the result, so [reported] does
     * not gate it and the result line still completes the attempt. Lets the UI explain the wait
     * (WebKit boot can take tens of seconds cold) instead of showing a dead button.
     */
    private fun emitPhase(phase: String) {
        synchronized(out) {
            if (reported) return
            out.println(JSONObject().put("phase", phase).toString())
            out.flush()
        }
    }

    private val drain = object : Callback {
        @Suppress("unused") fun callback(data: Pointer?): Int {
            while (true) (tasks.poll() ?: break).invoke()
            return 0 // G_SOURCE_REMOVE
        }
    }

    private val onDestroy = object : Callback {
        @Suppress("unused") fun callback(widget: Pointer?, data: Pointer?) {
            stage("window-closed")
            emit(JSONObject().put("ok", false).put("error", "cancelled"))
        }
    }

    /**
     * The capture signal is the cookie jar, not the page: Google sets SAPISID-family cookies during
     * the redirect chain, so the WebKitCookieManager `changed` signal plus a sweep after every
     * finished load catches the session the moment it lands — independent of SPA timing or ytcfg.
     */
    private val onCookiesChanged = object : Callback {
        @Suppress("unused") fun callback(manager: Pointer?, data: Pointer?) = checkJar("cookies-changed")
    }

    private val onLoad = object : Callback {
        @Suppress("unused") fun callback(view: Pointer?, event: Int, data: Pointer?) {
            if (event != 3) return // WEBKIT_LOAD_FINISHED
            checkJar("load-finished")
        }
    }

    private var checkingJar = false
    private fun checkJar(why: String) {
        if (reported || checkingJar) return
        checkingJar = true
        stage("check-jar:$why")
        f(webkit, "webkit_cookie_manager_get_cookies")
            .invokeVoid(arrayOf(cookieManager, COOKIE_URL, null, onCookies, null))
    }

    private val onCookies = object : Callback {
        @Suppress("unused") fun callback(manager: Pointer?, result: Pointer?, data: Pointer?) {
            checkingJar = false
            if (reported) return
            // webkit2gtk-4.1 (soup3) returns a GList of SoupCookie*, not a header string as in the
            // soup2 API — walk it and rebuild the "name=value; name=value" header ourselves.
            val list = f(webkit, "webkit_cookie_manager_get_cookies_finish").invokePointer(arrayOf(manager, result, null))
            val names = mutableListOf<String>()
            val header = StringBuilder()
            var node = list
            while (node != null) {
                val cookie = node.getPointer(0)
                if (cookie != null) {
                    val name = f(soup, "soup_cookie_get_name").invokePointer(arrayOf(cookie))?.getString(0, "UTF-8")
                    val value = f(soup, "soup_cookie_get_value").invokePointer(arrayOf(cookie))?.getString(0, "UTF-8")
                    if (name != null && value != null) {
                        if (header.isNotEmpty()) header.append("; ")
                        header.append(name).append('=').append(value)
                        names += name
                    }
                }
                node = node.getPointer(Native.POINTER_SIZE.toLong())
            }
            if (list != null) f(glib, "g_list_free_full").invokeVoid(arrayOf(list, f(soup, "soup_cookie_free")))
            stage("cookies:${names.size} sapisid=${names.any { it == "SAPISID" || it == "__Secure-3PAPISID" || it == "__Secure-1PAPISID" }}")
            if (names.none { it == "SAPISID" || it == "__Secure-3PAPISID" || it == "__Secure-1PAPISID" }) return
            pendingCookie = header.toString()
            // ytcfg is a best-effort source of visitorData/dataSyncId/authUser, never a gate:
            // whatever the probe returns within 3 s enriches the result, the cookie alone is enough.
            f(webkit, "webkit_web_view_evaluate_javascript").invokeVoid(arrayOf(view, YTCFG_PROBE, -1L, null, null, null, onEval, null))
            f(glib, "g_timeout_add").invokeInt(arrayOf(3_000, timeoutEmit, null))
        }
    }

    private var pendingCookie: String? = null

    private val timeoutEmit = object : Callback {
        @Suppress("unused") fun callback(data: Pointer?): Int {
            if (!reported && pendingCookie != null) {
                stage("emit:ok-fallback")
                emit(JSONObject().put("ok", true).put("cookie", pendingCookie).put("config", JSONObject()))
            }
            return 0 // G_SOURCE_REMOVE
        }
    }

    private val onEval = object : Callback {
        @Suppress("unused") fun callback(v: Pointer?, result: Pointer?, data: Pointer?) {
            val cookie = pendingCookie ?: return
            var config = JSONObject()
            // webkit2gtk-4.1: evaluate_javascript_finish returns the JSCValue directly.
            val value = f(webkit, "webkit_web_view_evaluate_javascript_finish").invokePointer(arrayOf(v, result, null))
            if (value != null && f(jsc, "jsc_value_is_string").invokeInt(arrayOf(value)) == 1) {
                val text = f(jsc, "jsc_value_to_string").invokePointer(arrayOf(value))
                config = runCatching { JSONObject(text?.getString(0, "UTF-8")) }.getOrDefault(JSONObject())
                if (text != null) f(glib, "g_free").invokeVoid(arrayOf(text))
            }
            if (value != null) f(jsc, "jsc_value_unref").invokeVoid(arrayOf(value))
            stage("emit:ok")
            emit(JSONObject().put("ok", true).put("cookie", cookie).put("config", config))
        }
    }

    private val thread = Thread({
        stage("start")
        f(gtk, "gtk_init").invokeVoid(arrayOf(null, null))
        val manager = f(webkit, "webkit_website_data_manager_new_ephemeral").invokePointer(emptyArray())
        val context = f(webkit, "webkit_web_context_new_with_website_data_manager").invokePointer(arrayOf(manager))
        cookieManager = f(webkit, "webkit_web_context_get_cookie_manager").invokePointer(arrayOf(context))
        f(gobject, "g_signal_connect_data").invokeLong(arrayOf(cookieManager, "changed", onCookiesChanged, null, null, 0))
        val v = f(gobject, "g_object_new").invokePointer(arrayOf(
            f(webkit, "webkit_web_view_get_type").invokeLong(emptyArray()), "web-context", context, null))
        val settings = f(webkit, "webkit_web_view_get_settings").invokePointer(arrayOf(v))
        f(webkit, "webkit_settings_set_enable_javascript").invokeVoid(arrayOf(settings, 1))
        val w = f(gtk, "gtk_window_new").invokePointer(arrayOf(0)) // GTK_WINDOW_TOPLEVEL
        f(gtk, "gtk_window_set_title").invokeVoid(arrayOf(w, "Sign in to YouTube Music"))
        f(gtk, "gtk_window_set_default_size").invokeVoid(arrayOf(w, 480, 780))
        f(gtk, "gtk_container_add").invokeVoid(arrayOf(w, v))
        f(gobject, "g_signal_connect_data").invokeLong(arrayOf(w, "destroy", onDestroy, null, null, 0))
        f(gobject, "g_signal_connect_data").invokeLong(arrayOf(v, "load-changed", onLoad, null, null, 0))
        f(gtk, "gtk_widget_show_all").invokeVoid(arrayOf(w))
        f(gtk, "gtk_window_present").invokeVoid(arrayOf(w))
        emitPhase("window-open")
        f(webkit, "webkit_web_view_load_uri").invokeVoid(arrayOf(v, LOGIN_URL))
        view = v; window = w
        f(gobject, "g_object_unref").invokeVoid(arrayOf(context))
        f(gobject, "g_object_unref").invokeVoid(arrayOf(manager))
        val l = f(glib, "g_main_loop_new").invokePointer(arrayOf(null, 0))
        loop = l
        f(glib, "g_main_loop_run").invokeVoid(arrayOf(l))
    }, "kasane-login").apply { isDaemon = false; start() }

    /** Runs the GTK loop on its thread until the window is closed or a session is captured. */
    fun await() = thread.join()

    override fun close() {
        if (!reported) emit(JSONObject().put("ok", false).put("error", "cancelled"))
        thread.join(5_000)
    }

    companion object {
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
        private const val COOKIE_URL = "https://music.youtube.com"
        /** Same probe as Android's YtMusicLoginDialog: returns a JSON string or null. */
        private const val YTCFG_PROBE = """(function(){try{var c=window.ytcfg;if(!c||!c.get)return null;function g(k){var v=c.get(k);return v==null?null:String(v)}return JSON.stringify({loggedIn:!!c.get('LOGGED_IN'),visitorData:g('VISITOR_DATA'),dataSyncId:g('DATASYNC_ID'),authUser:g('SESSION_INDEX')})}catch(e){return null}})()"""
    }
}
