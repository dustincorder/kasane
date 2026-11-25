package cord.kasane.desktop.auth

import cord.kasane.music.auth.AccountSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.asserter

/** Live round-trip against the running Secret Service; skipped where the service is absent. */
class SecretServiceStoreTest {
    private val store = SecretServiceStore(item = "test-session")

    private fun availableStore(): SecretServiceStore {
        if (runCatching { store.probe() }.getOrNull() == SecretServiceStore.Availability.UNAVAILABLE) {
            println("Secret Service is not reachable on this machine — skipping")
            return store
        }
        store.clear()
        return store
    }

    @Test
    fun `write read clear round trip`() {
        val s = availableStore()
        if (s.probe() == SecretServiceStore.Availability.UNAVAILABLE) return
        val session = AccountSession(cookie = "SAPISID=secret-value; SID=abc", authUser = 0, visitorData = "visitor-1")
        s.write(session)
        val read = s.read()
        assertEquals(session.cookie, read?.cookie)
        assertEquals(session.authUser, read?.authUser)
        assertEquals("visitor-1", read?.visitorData)
        s.clear()
        assertNull(s.read())
    }

    @Test
    fun `stored item is invisible to a different item name`() {
        val s = availableStore()
        if (s.probe() == SecretServiceStore.Availability.UNAVAILABLE) return
        s.write(AccountSession(cookie = "SAPISID=secret-value"))
        assertNull(SecretServiceStore(item = "other-item").read())
        s.clear()
    }

    @Test
    fun `read of missing item returns null`() {
        val s = availableStore()
        if (s.probe() == SecretServiceStore.Availability.UNAVAILABLE) return
        s.clear()
        assertNull(s.read())
    }
}
