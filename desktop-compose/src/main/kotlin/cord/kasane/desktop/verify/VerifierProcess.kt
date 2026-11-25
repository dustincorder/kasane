package cord.kasane.desktop.verify

import cord.kasane.desktop.stopTree
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
 *
 * The resolver caches player-script and integrity state for hours, so a helper that restarts (idle
 * stop or crash) must hold the same runtime state: the last arguments of the state-setting calls
 * (`attest` → `integrity` → `loadPlayer`) are kept here and replayed into a fresh helper before it
 * serves its first request.
 */
class VerifierProcess(private val scope: CoroutineScope, private val idleMs: Long = 180_000) : VerificationRuntime, AutoCloseable {
    private class Helper(val process: Process, val userAgent: String)

    private val lock = Mutex()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    @Volatile private var helper: Helper? = null
    @Volatile private var lastUserAgent = ""
    private var idleJob: Job? = null
    /** Cold starts take tens of seconds (helper + WebKit boot); UI reads this to explain the wait. */
    val readiness = kotlinx.coroutines.flow.MutableStateFlow(cord.kasane.music.provider.verification.VerificationReadiness.IDLE)
    /** Last successful args of each runtime-state call, in application order. */
    private val priming = LinkedHashMap<String, JSONArray>()

    override val userAgent: String get() = helper?.userAgent ?: lastUserAgent

    private fun command(): List<String> {
        val javaBin = File(System.getProperty("java.home"), "bin/java")
        return if (javaBin.canExecute()) listOf(javaBin.path, "-cp", System.getProperty("java.class.path"), "cord.kasane.desktop.MainKt", "--verifier")
        // Packaged app: the runtime has no java launcher, the app launcher itself takes the flag.
        else listOf(ProcessHandle.current().info().command().orElseThrow { VerificationException("cannot locate the app launcher") }, "--verifier")
    }

    private suspend fun ensureHelper(): Helper = helper?.takeIf { it.process.isAlive } ?: lock.withLock {
        helper?.takeIf { it.process.isAlive } ?: withContext(Dispatchers.IO) {
            readiness.value = cord.kasane.music.provider.verification.VerificationReadiness.STARTING
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
                process.stopTree()
                readiness.value = cord.kasane.music.provider.verification.VerificationReadiness.UNAVAILABLE
                throw VerificationException("system WebKitGTK unavailable: ${first.optString("error", "helper did not start (${cmd.first().substringAfterLast('/')}: ${line?.take(80) ?: "no output"})")}")
            }
            // Replay the saved runtime state before the reader thread takes over the stream.
            val replay = synchronized(priming) { LinkedHashMap(priming) }
            for ((method, args) in replay) {
                val reply = callSynchronously(process, reader, method, args)
                if (!reply.optBoolean("ok")) {
                    process.stopTree()
                    synchronized(priming) { priming.clear() }
                    readiness.value = cord.kasane.music.provider.verification.VerificationReadiness.UNAVAILABLE
                    throw VerificationException("verification helper rejected its saved state ($method)")
                }
            }
            thread(name = "verifier-replies", isDaemon = true) {
                for (line in reader.lineSequence()) {
                    val reply = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    pending.remove(reply.optLong("id"))?.complete(reply)
                }
                pending.values.forEach { it.complete(JSONObject().put("ok", false).put("error", "verification helper exited")) }
            }
            readiness.value = cord.kasane.music.provider.verification.VerificationReadiness.READY
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
        if (!result.optBoolean("ok")) throw VerificationException(result.optString("error", "unknown error")
            .removePrefix("YouTube playback verification failed: "))
        if (method in PRIMING_METHODS) synchronized(priming) { priming[method] = args }
        return result.opt("value").takeIf { it != JSONObject.NULL }
    }

    /** One request/reply round trip while the helper's reply thread is not running yet. */
    private fun callSynchronously(process: Process, reader: java.io.BufferedReader, method: String, args: JSONArray): JSONObject {
        process.outputStream.write((JSONObject().put("id", 0).put("method", method).put("args", args).toString() + "\n").toByteArray())
        process.outputStream.flush()
        return runCatching { JSONObject(reader.readLine() ?: "{}") }.getOrDefault(JSONObject())
    }

    private fun stopHelper() {
        val h = helper ?: return
        helper = null
        readiness.value = cord.kasane.music.provider.verification.VerificationReadiness.IDLE
        // The helper is a plain `java` process (the packaged launcher is out of the spawn chain),
        // so a graceful stop is clean — SIGKILL only covers a hung one.
        h.process.stopTree()
    }

    override fun close() { idleJob?.cancel(); stopHelper() }

    private companion object {
        /** Calls whose result is runtime state the helper must keep, replayed after a restart. */
        val PRIMING_METHODS = setOf("attest", "integrity", "loadPlayer")
    }
}
