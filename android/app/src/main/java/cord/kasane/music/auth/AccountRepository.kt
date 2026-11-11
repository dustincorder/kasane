package cord.kasane.music.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionExpiredException : java.io.IOException("Your YouTube Music session expired. Sign in again.")

class AccountRepository(private val store: SessionStore,
    private val validate: suspend (AccountSession) -> AccountIdentity) {
    private val mutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var identity: AccountIdentity? = null
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Guest)
    val state = mutableState.asStateFlow()
    @Volatile private var currentSession: AccountSession? = null
    fun session(): AccountSession? = currentSession
    suspend fun restore() = mutex.withLock {
        try {
            val stored = store.read() ?: return@withLock
            checkSession(stored)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { mutableState.value = AccountState.Unavailable("Session could not be restored. Try again.") }
    }
    /** Called only by a future verified authorization adapter, never by a browser-return intent. */
    suspend fun accept(session: AccountSession) = mutex.withLock { checkSession(session) }
    private suspend fun checkSession(session: AccountSession) {
        val ticket = generation.incrementAndGet()
        mutableState.value = AccountState.Checking
        try {
            val verified = validate(session)
            if (ticket != generation.get()) return
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.write(session) }
            if (ticket != generation.get()) { store.clear(); return }
            currentSession = session
            identity = verified
            mutableState.value = AccountState.SignedIn(verified)
        } catch (e: CancellationException) {
            if (ticket == generation.get()) mutableState.value = identity?.let { AccountState.SignedIn(it) } ?: AccountState.Guest
            throw e
        } catch (e: SessionExpiredException) {
            if (ticket != generation.get()) return
            store.clear(); currentSession = null; mutableState.value = AccountState.Expired
        } catch (e: Exception) {
            if (ticket != generation.get()) return
            currentSession = null
            mutableState.value = AccountState.Unavailable("Could not verify YouTube Music. Check your connection and retry.")
        }
    }
    suspend fun signOut() = mutex.withLock {
        generation.incrementAndGet()
        store.clear()
        currentSession = null
        identity = null
        mutableState.value = AccountState.Guest
    }
    fun cancelSignIn() {
        generation.incrementAndGet()
        mutableState.value = if (currentSession != null) identity?.let { AccountState.SignedIn(it) } ?: AccountState.Guest else AccountState.Guest
    }
    suspend fun expire() = mutex.withLock {
        store.clear(); currentSession = null; mutableState.value = AccountState.Expired
    }
}

object SignInAvailability {
    const val reason = "Kasane cannot sign in to a YouTube Music account yet. Google's supported Android sign-in returns OAuth tokens, but the current YouTube Music catalog provider requires a separate web session that Google does not expose through that flow. Kasane does not read browser cookies or collect Google credentials. Search, browsing, local favorites, history, and guest playback remain available."
}
