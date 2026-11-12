package cord.kasane.music.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SessionExpiredException : java.io.IOException("Your YouTube Music session expired. Sign in again.")

class AccountRepository(
    private val store: SessionStore,
    private val validate: suspend (AccountSession) -> AccountIdentity,
) {
    private val mutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var identity: AccountIdentity? = null
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Guest)
    val state = mutableState.asStateFlow()
    @Volatile private var currentSession: AccountSession? = null
    fun session(): AccountSession? = currentSession

    suspend fun restore() = mutex.withLock {
        val stored = try { withContext(Dispatchers.IO) { store.read() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.value = AccountState.Expired; return@withLock }
        if (stored != null) checkSession(stored)
    }

    fun beginSignIn() { mutableState.value = AccountState.SigningIn }
    fun failSignIn(message: String) {
        mutableState.value = AccountState.Unavailable(message)
    }

    /** Persists only after an authenticated account_menu response identifies the selected channel. */
    suspend fun accept(session: AccountSession) = mutex.withLock { checkSession(session) }

    private suspend fun checkSession(session: AccountSession) {
        val ticket = generation.incrementAndGet()
        mutableState.value = AccountState.Checking
        try {
            val verified = validate(session)
            if (ticket != generation.get()) return
            withContext(Dispatchers.IO) { store.write(session) }
            if (ticket != generation.get()) { withContext(Dispatchers.IO) { store.clear() }; return }
            currentSession = session
            identity = verified
            mutableState.value = AccountState.SignedIn(verified)
        } catch (e: CancellationException) {
            if (ticket != generation.get()) return
            mutableState.value = identity?.let(AccountState::SignedIn) ?: AccountState.Guest
            throw e
        } catch (e: SessionExpiredException) {
            if (ticket != generation.get()) return
            if (currentSession == null || currentSession === session) {
                withContext(Dispatchers.IO) { store.clear() }
                currentSession = null
                identity = null
                mutableState.value = AccountState.Expired
            } else {
                mutableState.value = AccountState.Unavailable("The selected YouTube channel could not be verified. Your previous session is still available.")
            }
        } catch (e: Exception) {
            if (ticket != generation.get()) return
            mutableState.value = AccountState.Unavailable(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not verify the YouTube Music session. Check your connection and try again."
            )
        }
    }

    suspend fun signOut() = mutex.withLock {
        withContext(Dispatchers.IO) { store.clear() }
        currentSession = null
        identity = null
        mutableState.value = AccountState.Guest
    }

    fun cancelSignIn() {
        generation.incrementAndGet()
        mutableState.value = identity?.let(AccountState::SignedIn) ?: AccountState.Guest
    }

    suspend fun expire() = mutex.withLock {
        withContext(Dispatchers.IO) { store.clear() }
        currentSession = null
        identity = null
        mutableState.value = AccountState.Expired
    }
}
