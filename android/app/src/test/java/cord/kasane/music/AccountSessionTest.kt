package cord.kasane.music

import cord.kasane.music.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
    private class MemoryStore : SessionStore {
        var value: AccountSession? = null
        override fun read() = value
        override fun write(session: AccountSession) { value = session }
        override fun clear() { value = null }
    }
    private val session get() = AccountSession("SAPISID=test-only-cookie")
    private val identity = AccountIdentity("Test listener", null, "test-channel")
    @Test fun guestHasNoCredentials() = runTest {
        val repository = AccountRepository(MemoryStore()) { identity }
        repository.restore()
        assertEquals(AccountState.Guest, repository.state.value)
        assertNull(repository.session())
    }
    @Test fun identityIsValidatedBeforeSessionIsSaved() = runTest {
        val store = MemoryStore()
        val repository = AccountRepository(store) { assertNull(store.value); identity }
        repository.accept(session)
        assertTrue(repository.state.value is AccountState.SignedIn)
        assertNotNull(store.value)
    }
    @Test fun sessionRestoresInNewRepository() = runTest {
        val store = MemoryStore()
        AccountRepository(store) { identity }.accept(session)
        val restored = AccountRepository(store) { identity }
        restored.restore()
        assertEquals(identity, (restored.state.value as AccountState.SignedIn).identity)
    }
    @Test fun expiredSessionIsDeleted() = runTest {
        val store = MemoryStore().apply { value = session }
        val repository = AccountRepository(store) { throw SessionExpiredException() }
        repository.restore()
        assertEquals(AccountState.Expired, repository.state.value)
        assertNull(store.value)
        assertNull(repository.session())
    }
    @Test fun networkErrorIsNotMistakenForSuccessfulLogin() = runTest {
        val repository = AccountRepository(MemoryStore()) { throw java.io.IOException("offline") }
        repository.accept(session)
        assertTrue(repository.state.value is AccountState.Unavailable)
        assertNull(repository.session())
    }
    @Test fun cancellationCannotCompleteSignInLater() = runTest {
        val store = MemoryStore()
        val waiting = CompletableDeferred<Unit>()
        val repository = AccountRepository(store) { waiting.await(); identity }
        val job = launch { repository.accept(session) }
        yield()
        repository.cancelSignIn()
        waiting.complete(Unit)
        job.join()
        assertEquals(AccountState.Guest, repository.state.value)
        assertNull(store.value)
    }
    @Test fun logoutClearsOnlyAccountStore() = runTest {
        val store = MemoryStore()
        val repository = AccountRepository(store) { identity }
        repository.accept(session)
        repository.signOut()
        assertNull(store.value)
        assertEquals(AccountState.Guest, repository.state.value)
    }
    @Test fun stringRepresentationNeverContainsCookies() {
        assertFalse(session.toString().contains("test-only-cookie"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsHeaderInjection() {
        AccountSession("SAPISID=test\r\nInjected: value".replace("\r", "\r").replace("\n", "\n") + '\n')
    }
}
