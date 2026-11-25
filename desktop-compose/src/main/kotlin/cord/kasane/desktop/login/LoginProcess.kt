package cord.kasane.desktop.login

import cord.kasane.desktop.stopTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One Google sign-in attempt in the `--login` helper process: a WebKitGTK window with an ephemeral
 * profile. [result] completes with the captured cookies and ytcfg fields, null when the user closed
 * the window, or fails when the helper could not start. The browser engine never runs in this JVM.
 */
class LoginProcess {
    private var process: Process? = null
    val result = CompletableDeferred<CapturedLogin?>()
    /** Live sign-in phase; helper-boot lines arrive before the window appears (cold start can take tens of seconds). */
    val phase = kotlinx.coroutines.flow.MutableStateFlow(cord.kasane.music.auth.SignInPhase.STARTING)

    fun start() {
        val javaBin = File(System.getProperty("java.home"), "bin/java")
        val cmd = if (javaBin.canExecute()) listOf(javaBin.path, "-cp", System.getProperty("java.class.path"), "cord.kasane.desktop.MainKt", "--login")
        else listOf(ProcessHandle.current().info().command().orElseThrow { IOException("cannot locate the app launcher") }, "--login")
        val p = ProcessBuilder(cmd).apply {
            environment().keys.removeIf { it.contains("JPACKAGE", ignoreCase = true) }
            redirectError(System.getenv("KASANE_LOGIN_LOG")?.let { ProcessBuilder.Redirect.appendTo(File(it)) } ?: ProcessBuilder.Redirect.DISCARD)
        }.start()
        process = p
        Thread({
            var finished = false
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parsed = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val phaseName = parsed.optString("phase")
                    if (phaseName.isNotEmpty()) {
                        runCatching {
                            cord.kasane.music.auth.SignInPhase.valueOf(phaseName.uppercase().replace('-', '_'))
                        }.getOrNull()?.let { phase.value = it }
                        continue
                    }
                    val captured = parsed.toCapturedLogin()
                    if (captured != null) result.complete(captured) else result.complete(null)
                    finished = true
                    break
                }
            }
            if (!finished && !result.isCompleted) {
                result.completeExceptionally(IOException("The sign-in window could not start (a display is required)."))
            }
            p.waitFor()
        }, "kasane-login-watch").apply { isDaemon = true }.start()
    }

    /** Closes the sign-in window early; [result] completes with null. */
    fun cancel() {
        // The helper is a plain `java` process — a graceful stop runs its GTK teardown cleanly;
        // SIGKILL the tree only covers a hung one.
        process?.stopTree()
        if (!result.isCompleted) result.complete(null)
    }
}
