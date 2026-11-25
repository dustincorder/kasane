package cord.kasane.music.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


class AccountRepository(
    private val store: SessionStore,
    private val validate: suspend (AccountSession) -> AccountIdentity,
) {
    private val mutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var identity: AccountIdentity? = null
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Guest)
    val state = mutableState.asStateFlow()
    /** Sign-in progress for the UI: the wait is explained instead of a dead button (shared SignInPhase). */
    private val mutablePhase = MutableStateFlow<cord.kasane.music.auth.SignInPhase?>(null)
    val signInPhase = mutablePhase.asStateFlow()
    @Volatile private var currentSession: AccountSession? = null
    fun session(): AccountSession? = currentSession

    suspend fun restore() = mutex.withLock {
        val stored = try { withContext(Dispatchers.IO) { store.read() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.value = AccountState.Expired; return@withLock }
        if (stored != null) checkSession(stored)
    }

    fun beginSignIn() {
        mutablePhase.value = cord.kasane.music.auth.SignInPhase.STARTING
        mutableState.value = AccountState.SigningIn
    }
    /** The sign-in window reached the user; only then does the button text change meaning. */
    fun signInWindowOpen() {
        if (mutableState.value == AccountState.SigningIn) mutablePhase.value = cord.kasane.music.auth.SignInPhase.WINDOW_OPEN
    }
    fun failSignIn(message: String) {
        mutablePhase.value = null
        mutableState.value = AccountState.Unavailable(message)
    }

    /** Persists only after an authenticated account_menu response identifies the selected channel. */
    suspend fun accept(session: AccountSession) = mutex.withLock { checkSession(session) }

    private suspend fun checkSession(session: AccountSession) {
        val ticket = generation.incrementAndGet()
        // A background restore also validates — only an active sign-in flow reports the phase.
        if (mutablePhase.value != null) mutablePhase.value = cord.kasane.music.auth.SignInPhase.VALIDATING
        mutableState.value = AccountState.Checking
        try {
            val verified = validate(session)
            if (ticket != generation.get()) return
            withContext(Dispatchers.IO) { store.write(session) }
            if (ticket != generation.get()) { withContext(Dispatchers.IO) { store.clear() }; return }
            currentSession = session
            identity = verified
            mutablePhase.value = null
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
                mutablePhase.value = null
                mutableState.value = AccountState.Expired
            } else {
                mutablePhase.value = null
            mutableState.value = AccountState.Unavailable("The selected YouTube channel could not be verified. Your previous session is still available.")
            }
        } catch (e: Exception) {
            if (ticket != generation.get()) return
            mutablePhase.value = null
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
        mutablePhase.value = null
        mutableState.value = identity?.let(AccountState::SignedIn) ?: AccountState.Guest
    }

    suspend fun expire() = mutex.withLock {
        withContext(Dispatchers.IO) { store.clear() }
        currentSession = null
        identity = null
        mutableState.value = AccountState.Expired
    }
}
