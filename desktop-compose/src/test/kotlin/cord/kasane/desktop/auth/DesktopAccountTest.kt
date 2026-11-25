package cord.kasane.desktop.auth

import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.AccountState
import cord.kasane.music.auth.SessionStore
import cord.kasane.music.provider.CatalogFailure
import cord.kasane.music.provider.CatalogFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAccountTest {
    private class MemStore : SessionStore {
        var saved: AccountSession? = null
        override fun read() = saved
        override fun write(session: AccountSession) { saved = session }
        override fun clear() { saved = null }
    }

    private val identity = AccountIdentity("Test User", null, "UC123")
    private val captured = cord.kasane.desktop.login.CapturedLogin(
        cookie = "SAPISID=secret; SID=abc; OTHER=drop", visitorData = "v1", dataSyncId = "d1", authUser = 0)

    private class MemModeStore : cord.kasane.music.auth.SessionModeStore {
        var mode = cord.kasane.music.auth.SessionMode.UNDECIDED
        override fun read() = mode
        override fun write(mode: cord.kasane.music.auth.SessionMode) { this.mode = mode }
    }

    private fun loginAttempt(result: kotlinx.coroutines.Deferred<cord.kasane.desktop.login.CapturedLogin?>, cancel: () -> Unit = {}) =
        DesktopAccount.LoginAttempt(result,
            kotlinx.coroutines.flow.MutableStateFlow(cord.kasane.music.auth.SignInPhase.WINDOW_OPEN), cancel)

    private fun account(scope: kotlinx.coroutines.CoroutineScope, store: MemStore,
        validate: suspend (AccountSession) -> AccountIdentity = { identity },
        attempt: DesktopAccount.LoginAttempt? = null,
        modeStore: MemModeStore? = null) =
        DesktopAccount(scope, store, validate,
            { attempt ?: loginAttempt(CompletableDeferred(captured)) }, modeStore)

    @Test
    fun `sign-in validates then persists and signs in`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        var validated: AccountSession? = null
        val a = account(backgroundScope, store, validate = { validated = it; identity })
        assertEquals(AccountState.Guest, a.state.value)
        a.signIn().join()
        
        val state = a.state.value
        assertIs<AccountState.SignedIn>(state)
        assertEquals("Test User", state.identity.name)
        assertNotNull(store.saved)
        val session = a.session()!!
        assertTrue(session.cookie.contains("SAPISID=secret"))
        assertTrue(!session.cookie.contains("OTHER")) // filtered to the auth list
    }

    @Test
    fun `repeated sign-in joins the in-flight attempt`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        val a = account(backgroundScope, store)
        val first = a.signIn()
        a.signIn().join() // joins the same attempt instead of hanging
        assertIs<AccountState.SignedIn>(a.state.value)
    }

    @Test
    fun `cancelled window returns to guest without storing`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        val a = account(backgroundScope, store,
            attempt = loginAttempt(CompletableDeferred<cord.kasane.desktop.login.CapturedLogin?>(null).also { it.complete(null) }))
        a.signIn().join()
        
        assertEquals(AccountState.Guest, a.state.value)
        assertNull(store.saved)
    }

    @Test
    fun `restore signs in when the stored session validates`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore().apply { saved = AccountSession(cookie = "SAPISID=x", visitorData = "v") }
        val a = account(backgroundScope, store)
        a.restore()
        assertIs<AccountState.SignedIn>(a.state.value)
        assertNotNull(a.session())
    }

    @Test
    fun `restore marks expired when the stored session fails`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore().apply { saved = AccountSession(cookie = "SAPISID=x") }
        val a = account(backgroundScope, store,
            validate = { throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED) })
        a.restore()
        assertEquals(AccountState.Expired, a.state.value)
        assertNull(store.saved)
    }

    @Test
    fun `failed validation reports unavailable and stores nothing`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        val a = account(backgroundScope, store, validate = { throw java.io.IOException("network down") })
        a.signIn().join()
        
        val state = a.state.value
        assertIs<AccountState.Unavailable>(state)
        assertEquals("network down", state.message)
        assertNull(store.saved)
    }

    @Test
    fun `sign-out clears session and store`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        val a = account(backgroundScope, store)
        a.signIn().join()
        
        a.signOut()
        assertEquals(AccountState.Guest, a.state.value)
        assertNull(a.session())
        assertNull(store.saved)
    }

    // "Continue as a guest" is sticky: a stored session does not resurrect on the next launch.
    @Test
    fun `guest mode blocks session restore`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore().apply { saved = AccountSession(cookie = "SAPISID=x") }
        val modes = MemModeStore().apply { mode = cord.kasane.music.auth.SessionMode.GUEST }
        val a = account(backgroundScope, store, modeStore = modes)
        a.restore()
        assertEquals(AccountState.Guest, a.state.value)
        assertNull(a.session())
    }

    @Test
    fun `chooseGuest pins guest mode and keeps the user unsigned`() = runTest(UnconfinedTestDispatcher()) {
        val modes = MemModeStore()
        val a = account(backgroundScope, MemStore(), modeStore = modes)
        a.chooseGuest()
        assertEquals(cord.kasane.music.auth.SessionMode.GUEST, modes.mode)
        assertEquals(AccountState.Guest, a.state.value)
    }

    @Test
    fun `sign-in flips the mode to account`() = runTest(UnconfinedTestDispatcher()) {
        val modes = MemModeStore()
        val a = account(backgroundScope, MemStore(), modeStore = modes)
        a.signIn().join()
        assertEquals(cord.kasane.music.auth.SessionMode.ACCOUNT, modes.mode)
        a.signOut()
        assertEquals(cord.kasane.music.auth.SessionMode.UNDECIDED, modes.mode)
    }

    @Test
    fun `signed-in headers contain cookie and sapisidhash`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemStore()
        val a = account(backgroundScope, store)
        a.signIn().join()
        
        val headers = a.signedInHeaders()!!
        assertTrue(headers["Cookie"]!!.contains("SAPISID=secret"))
        assertTrue(headers["Authorization"]!!.matches(Regex("SAPISIDHASH \\d+_[0-9a-f]{40}")))
        assertEquals("0", headers["X-Goog-AuthUser"])
        assertEquals("v1", headers["X-Goog-Visitor-Id"])
    }
}
