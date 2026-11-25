package cord.kasane.desktop

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The packaged jpackage launcher aborts in native teardown and dumps core on every exit, so helper
 * processes are stopped with [killTree] (SIGKILL — no teardown, no dump). This test covers the tree
 * semantics: the spawned process and its descendants must all be dead afterwards.
 */
class ProcessKillTreeTest {
    @Test fun `killTree kills the process and its descendants`() {
        val process = ProcessBuilder("sh", "-c", "sleep 60 & wait").start()
        var child: ProcessHandle? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (child == null && System.nanoTime() < deadline) {
            child = process.descendants().findFirst().orElse(null)
            Thread.sleep(20)
        }
        assertNotNull(child)
        assertTrue(child.isAlive())

        process.killTree()

        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val deadline2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (child.isAlive() && System.nanoTime() < deadline2) Thread.sleep(20)
        assertFalse(child.isAlive())
    }
}
