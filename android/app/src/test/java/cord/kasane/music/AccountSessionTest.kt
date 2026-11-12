package cord.kasane.music

import cord.kasane.music.auth.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
    private class MemoryStore : SessionStore {
        var value: AccountSession? = null
        override fun read() = value
        override fun write(session: AccountSession) { value = session }
        override fun clear() { value = null }
    }
    private val session get() = AccountSession("SAPISID=mock-cookie; LOGIN_INFO=mock-login", 0, "UCmock", "delegated", "mock-visitor", "delegated||user")
    private val identity = AccountIdentity("Test channel", "https://example.test/avatar.jpg", "UCmock")

    @Test fun guestHasNoCredentials() = runTest {
        val repository = AccountRepository(MemoryStore()) { identity }
        repository.restore()
        assertEquals(AccountState.Guest, repository.state.value)
        assertNull(repository.session())
    }

    @Test fun sessionIsStoredOnlyAfterAuthenticatedIdentityValidation() = runTest {
        val store = MemoryStore()
        val repository = AccountRepository(store) { assertNull(store.value); identity }
        repository.accept(session)
        assertEquals(AccountState.SignedIn(identity), repository.state.value)
        assertNotNull(store.value)
    }

    @Test fun sessionRestoresAfterRepositoryRecreation() = runTest {
        val store = MemoryStore()
        AccountRepository(store) { identity }.accept(session)
        val restored = AccountRepository(store) { identity }
        restored.restore()
        assertEquals(AccountState.SignedIn(identity), restored.state.value)
    }

    @Test fun serverRejectedSessionIsDeleted() = runTest {
        val store = MemoryStore().apply { value = session }
        val repository = AccountRepository(store) { throw SessionExpiredException() }
        repository.restore()
        assertEquals(AccountState.Expired, repository.state.value)
        assertNull(store.value)
    }

    @Test fun networkErrorDoesNotBecomeSignedInOrPersistSession() = runTest {
        val store = MemoryStore()
        val repository = AccountRepository(store) { throw java.io.IOException("offline") }
        repository.accept(session)
        assertTrue(repository.state.value is AccountState.Unavailable)
        assertNull(repository.session())
        assertNull(store.value)
    }

    @Test fun canceledSignInRemainsGuest() = runTest {
        val repository = AccountRepository(MemoryStore()) { identity }
        repository.beginSignIn()
        assertEquals(AccountState.SigningIn, repository.state.value)
        repository.cancelSignIn()
        assertEquals(AccountState.Guest, repository.state.value)
    }

    @Test fun cancellationDuringValidationPreventsPersistence() = runTest {
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

    @Test fun logoutClearsSessionButNotIndependentLocalData() = runTest {
        val store = MemoryStore()
        val repository = AccountRepository(store) { identity }
        repository.accept(session)
        repository.signOut()
        assertNull(store.value)
        assertEquals(AccountState.Guest, repository.state.value)
    }

    @Test fun sessionStringNeverContainsCookieValues() {
        assertFalse(session.toString().contains("mock-cookie"))
        assertFalse(session.toString().contains("mock-login"))
    }

    @Test fun captureRequiresAuthenticatedCookieAndLoggedInYtcfg() {
        val config = """{"loggedIn":true,"visitorData":"visitor","dataSyncId":"delegated||user","authUser":"2","pageId":"UCchannel"}"""
        val session = WebSessionParser.capture("SAPISID=s; PREF=ignore; LOGIN_INFO=login", config)
        assertNotNull(session)
        assertEquals("SAPISID=s; LOGIN_INFO=login", session?.cookie)
        assertEquals(2, session?.authUser)
        assertEquals("visitor", session?.visitorData)
        assertNull(WebSessionParser.capture("PREF=guest", config))
        assertNull(WebSessionParser.capture("SAPISID=s", """{"loggedIn":false,"visitorData":"v"}"""))
    }

    @Test fun authenticatedYoutubeCookiesCanBeCapturedBeforeMusicPageLoads() {
        val captured = WebSessionParser.captureCookies("SAPISID=secret; PREF=ignore; __Secure-3PAPISID=also-secret")
        assertNotNull(captured)
        assertEquals("SAPISID=secret; __Secure-3PAPISID=also-secret", captured?.cookie)
        assertNull(captured?.visitorData)
        assertEquals(0, captured?.authUser)
        assertNull(WebSessionParser.captureCookies("SID=google-only; PREF=ignore"))
        assertNull(WebSessionParser.captureCookies("SAPISID=x", visitorData = "bad visitor"))
    }

    @Test fun trustedNavigationRequiresExactHttpsHost() {
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://music.youtube.com/watch?v=x"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://accounts.google.com/ServiceLogin"))
        assertTrue(WebSessionParser.isGoogleAccountUrl("https://accounts.google.com/signin/v2/challenge"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://accounts.google.com.ua/profile"))
        assertTrue(WebSessionParser.isGoogleAccountUrl("https://accounts.google.com.ua/profile/address"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://accounts.google.co.uk/signin"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://accounts.google.co.jp/signin"))
        assertTrue(WebSessionParser.isGoogleAccountUrl("https://accounts.google.com.br/signin"))
        assertFalse(WebSessionParser.isGoogleAccountUrl("https://accounts.google.com.attacker.test/profile"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("https://accounts.gogole.com.ua/profile"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("https://accounts.google.com.ua.attacker.test/profile"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://ogs.google.com/widget/account"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://gds.google.com/signin/challenge"))
        assertTrue(WebSessionParser.isTrustedHttpsUrl("https://support.google.com/accounts/answer"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("http://music.youtube.com"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("https://music.youtube.com.attacker.test"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("https://attacker-music.youtube.com"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("intent://accounts.google.com"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("file:///etc/passwd"))
        assertFalse(WebSessionParser.isTrustedHttpsUrl("https://user@music.youtube.com"))
    }

    @Test fun multipleBrandChannelsAreParsedWithoutChoosingOne() {
        val response = org.json.JSONObject("""{"items":[{"accountItemRenderer":{"accountName":{"simpleText":"Personal"},"isSelected":true,"pageId":"UCpersonal"}},{"accountItemRenderer":{"accountName":{"simpleText":"Artist channel"},"accountByline":{"simpleText":"Brand account"},"pageId":"UCbrand"}}]}""")
        val channels = WebSessionParser.parseChannels(response)
        assertEquals(2, channels.size)
        assertEquals(setOf("UCpersonal", "UCbrand"), channels.mapNotNull { it.pageId }.toSet())
        assertTrue(channels.first().selected)
    }
}
