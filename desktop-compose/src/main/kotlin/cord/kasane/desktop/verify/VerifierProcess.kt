package cord.kasane.desktop.verify

import cord.kasane.music.provider.verification.VerificationException
import cord.kasane.music.provider.verification.VerificationRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Verification through the `--verifier` helper process. Started on the first call, stopped after [idleMs]
 * without calls (WebKit uses ~400 MB while alive) and on [close]; a crashed helper is replaced on the next call.
 * The browser engine never runs in the JVM that hosts the Compose window.
 */
class VerifierProcess(private val scope: CoroutineScope, private val idleMs: Long = 180_000) : VerificationRuntime, AutoCloseable {
    private class Helper(val process: Process, val userAgent: String)

    private val lock = Mutex()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    @Volatile private var helper: Helper? = null
    @Volatile private var lastUserAgent = ""
    private var idleJob: Job? = null

    override val userAgent: String get() = helper?.userAgent ?: lastUserAgent

    private fun command(): List<String> {
        val javaBin = File(System.getProperty("java.home"), "bin/java")
        return if (javaBin.canExecute()) listOf(javaBin.path, "-cp", System.getProperty("java.class.path"), "cord.kasane.desktop.MainKt", "--verifier")
        // Packaged app: the runtime has no java launcher, the app launcher itself takes the flag.
        else listOf(ProcessHandle.current().info().command().orElseThrow { VerificationException("cannot locate the app launcher") }, "--verifier")
    }

    private suspend fun ensureHelper(): Helper = helper?.takeIf { it.process.isAlive } ?: lock.withLock {
        helper?.takeIf { it.process.isAlive } ?: withContext(Dispatchers.IO) {
            val cmd = command()
            val process = ProcessBuilder(cmd).apply {
                // The packaged launcher acts as a plain `java` launcher when it sees its own marker variables
                // (inherited from this app), and would reject --verifier.
                environment().keys.removeIf { it.contains("JPACKAGE", ignoreCase = true) }
            }.redirectError(System.getenv("KASANE_VERIFIER_LOG")?.let { ProcessBuilder.Redirect.appendTo(File(it)) } ?: ProcessBuilder.Redirect.DISCARD).start()
            val reader = process.inputStream.bufferedReader()
            val line = runCatching { withTimeout(30_000) { reader.readLine() } }.getOrNull()
            val first = runCatching { JSONObject(line ?: "{}") }.getOrDefault(JSONObject())
            if (!first.optBoolean("ready")) {
                process.destroyForcibly()
                throw VerificationException("system WebKitGTK unavailable: ${first.optString("error", "helper did not start (${cmd.first().substringAfterLast('/')}: ${line?.take(80) ?: "no output"})")}")
            }
            thread(name = "verifier-replies", isDaemon = true) {
                for (line in reader.lineSequence()) {
                    val reply = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    pending.remove(reply.optLong("id"))?.complete(reply)
                }
                pending.values.forEach { it.complete(JSONObject().put("ok", false).put("error", "verification helper exited")) }
            }
            Helper(process, first.optString("userAgent")).also { helper = it; lastUserAgent = it.userAgent }
        }
    }

    override suspend fun call(method: String, args: JSONArray): Any? {
        val h = ensureHelper()
        idleJob?.cancel()
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        withContext(Dispatchers.IO) {
            synchronized(h) {
                h.process.outputStream.write((JSONObject().put("id", id).put("method", method).put("args", args).toString() + "\n").toByteArray())
                h.process.outputStream.flush()
            }
        }
        val result = try { withTimeout(50_000) { reply.await() } } finally { pending.remove(id) }
        idleJob = scope.launch { delay(idleMs); stopHelper() }
        if (!result.optBoolean("ok")) throw VerificationException(result.optString("error", "unknown error"))
        return result.opt("value").takeIf { it != JSONObject.NULL }
    }

    private fun stopHelper() {
        val h = helper ?: return
        helper = null
        runCatching { h.process.outputStream.close() }
        if (!h.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) h.process.destroyForcibly()
    }

    override fun close() { idleJob?.cancel(); stopHelper() }
}
