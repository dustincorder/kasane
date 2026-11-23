package cord.kasane.poc

import cord.kasane.music.provider.verification.VerificationException
import cord.kasane.music.provider.verification.VerificationRuntime
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import netscape.javascript.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** JavaFX's bundled WebKit as a verification runtime (fallback candidate). Engine without a visible stage. */
class JavaFxRuntime(private val bundle: String) : VerificationRuntime, AutoCloseable {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val ids = AtomicLong(1)
    private val ready = CompletableDeferred<Unit>()
    private lateinit var engine: WebEngine
    private val dataDir = kotlin.io.path.createTempDirectory("kasane-fx").toFile()
    @Volatile private var ua = ""

    /** Called from JavaScript; public so JavaFX's bridge can see it. */
    inner class Bridge { fun reply(id: String, payload: String) { id.toLongOrNull()?.let { pending.remove(it)?.complete(payload) } } }
    private val bridge = Bridge() // kept strongly reachable: JavaFX holds only a weak reference

    init {
        Platform.setImplicitExit(false)
        runCatching { Platform.startup {} }
        Platform.runLater {
            engine = WebEngine()
            engine.userDataDirectory = dataDir
            ua = engine.userAgent
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) {
                    (engine.executeScript("window") as JSObject).setMember("KasaneHost", bridge)
                    engine.executeScript("globalThis.__kasaneReply = (id, p) => KasaneHost.reply(String(id), p);")
                    runCatching { engine.executeScript(bundle) }.onFailure { ready.completeExceptionally(it) }
                    ready.complete(Unit)
                }
            }
            engine.loadContent(PAGE)
        }
    }

    override val userAgent: String get() = ua

    override suspend fun call(method: String, args: JSONArray): Any? {
        try { withTimeout(20_000) { ready.await() } } catch (e: TimeoutCancellationException) { throw VerificationException("javafx did not load") }
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        val script = "globalThis.KasaneVerifier ? KasaneVerifier.run($id, ${JSONObject.quote(method)}, $args) : " +
            "__kasaneReply($id, JSON.stringify({ ok: false, error: 'verification-runtime-missing' }));"
        Platform.runLater { runCatching { engine.executeScript(script) }.onFailure { reply.complete(JSONObject().put("ok", false).put("error", it.toString()).toString()) } }
        val payload = try { withTimeout(45_000) { reply.await() } } catch (e: TimeoutCancellationException) {
            throw VerificationException("verification runtime did not respond")
        } finally { pending.remove(id) }
        val result = JSONObject(payload)
        if (!result.optBoolean("ok")) throw VerificationException(result.optString("error", "unknown error"))
        return result.opt("value")
    }

    override fun close() {
        Platform.runLater { engine.load("about:blank") }
        Platform.exit()
        dataDir.deleteRecursively()
    }

    private companion object {
        const val PAGE = "<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'none'; script-src 'unsafe-eval' 'unsafe-inline'\"></head><body></body></html>"
    }
}
