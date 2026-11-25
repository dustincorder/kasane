package cord.kasane.desktop

import com.sun.jna.NativeLibrary
import kotlin.system.exitProcess

/**
 * Exit handling for the packaged app. The jpackage launcher crashes in its native teardown — it calls
 * a pure virtual `Logger::log` after the logger is already destructed ("pure virtual method called"),
 * so every exit through `exit()` dumps core and KDE reports a crash for `kasane-compose`. It also
 * crashes a forked child on startup, so the launcher's own binary must not be used at all — the
 * installed `.desktop` entry and the helper spawns run the bundled `lib/runtime/bin/java` instead.
 * `exitDesktop` covers the case of the ELF still being run directly: `_exit` skips all atexit handlers
 * and static destructors, so the launcher's teardown never runs (JVM shutdown hooks do not run either —
 * all Java-side cleanup must already have happened). Detected by `jpackage.app-version`, which only
 * the packaged launcher sets; under `java` and dev runs the normal `exitProcess` path is taken.
 */
private val packaged = System.getProperty("jpackage.app-version") != null

internal fun exitDesktop(code: Int): Nothing {
    if (packaged) {
        System.out.flush()
        System.err.flush()
        runCatching { NativeLibrary.getInstance("c").getFunction("_exit").invokeInt(arrayOf(code)) }
        Runtime.getRuntime().halt(code)
    }
    exitProcess(code)
}

/** Kills this process and every descendant (the JVM, GTK/WebKit children) with SIGKILL. */
internal fun Process.killTree() {
    val descendants = descendants().toList()
    destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

/**
 * Asks the process to exit gracefully (SIGTERM) — JVM shutdown hooks and native GTK/WebKit
 * teardown run — escalating to [killTree] only when it ignores the request within [graceMs].
 * Graceful is safe here: helpers are spawned as plain `java` processes (the packaged ELF
 * launcher, whose teardown aborts, is out of the chain). Returns false when it had to escalate.
 */
internal fun Process.stopTree(graceMs: Long = 2_000): Boolean {
    if (!isAlive) return true
    val children = descendants().toList()
    destroy()
    if (runCatching { waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
        // Gone — release any stray children it left behind (they normally die with the parent).
        children.filter { it.isAlive }.forEach { it.destroy() }
        return true
    }
    killTree()
    waitFor()
    return false
}
