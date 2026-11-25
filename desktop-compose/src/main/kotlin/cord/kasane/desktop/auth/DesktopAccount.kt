package cord.kasane.desktop.auth

import cord.kasane.desktop.login.CapturedLogin
import cord.kasane.desktop.login.LoginProcess
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.AccountState
import cord.kasane.music.auth.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class SessionExpiredException : IOException("Your YouTube Music session expired. Sign in again.")

/**
 * Sign-in state and the active InnerTube session — the desktop counterpart of Android's
 * `AccountRepository`. A captured session is persisted only after `validate` (an authenticated
 * account_menu call) confirms it. [store] is null when the Secret Service is unreachable;
 * the session then lives in memory for the app run only and [persisted] lets the UI say so.
 */
class DesktopAccount(
    private val scope: CoroutineScope,
    private val store: SessionStore?,
    private val validate: suspend (AccountSession) -> AccountIdentity,
    private val startLogin: () -> LoginAttempt = { LoginProcess().let { it.start(); LoginAttempt(it.result, it.phase, it::cancel) } },
    /** Explicit guest/account choice; GUEST keeps a stored session from auto-restoring. */
    private val modeStore: cord.kasane.music.auth.SessionModeStore? = null,
) {
    /** One sign-in window attempt: its result, live phase and a way to close it early. */
    data class LoginAttempt(
        val result: kotlinx.coroutines.Deferred<CapturedLogin?>,
        val phase: kotlinx.coroutines.flow.StateFlow<cord.kasane.music.auth.SignInPhase>,
        val cancel: () -> Unit,
    )
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var identity: AccountIdentity? = null
    private var activeLogin: LoginAttempt? = null
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Guest)
    val state = mutableState.asStateFlow()
    /** Live phase of the sign-in window (helper boot, window open, validating); null when idle. */
    private val mutableSignInPhase = MutableStateFlow<cord.kasane.music.auth.SignInPhase?>(null)
    val signInPhase = mutableSignInPhase.asStateFlow()
    @Volatile private var currentSession: AccountSession? = null
    fun session(): AccountSession? = currentSession
    val persisted: Boolean get() = store != null

    suspend fun restore() = mutex.withLock {
        if (!cord.kasane.music.auth.SessionPolicy.restoresStoredSession(modeStore?.read() ?: cord.kasane.music.auth.SessionMode.UNDECIDED)) return@withLock
        val stored = try { withContext(Dispatchers.IO) { store?.read() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.value = AccountState.Expired; return@withLock }
        if (stored != null) checkSession(stored)
    }

    /** "Continue as a guest" — persists the choice and invalidates any in-flight session restore. */
    fun chooseGuest() {
        modeStore?.write(cord.kasane.music.auth.SessionMode.GUEST)
        generation.incrementAndGet()
        mutableState.value = AccountState.Guest
    }

    private var signInJob: kotlinx.coroutines.Job? = null

    /** Opens the sign-in window; returns the in-flight attempt's job (a repeated call joins it). */
    fun signIn(): kotlinx.coroutines.Job {
        signInJob?.let { if (it.isActive) return it }
        mutableState.value = AccountState.SigningIn
        mutableSignInPhase.value = cord.kasane.music.auth.SignInPhase.STARTING
        val login = startLogin()
        activeLogin = login
        return scope.launch {
            val phaseForward = launch { login.phase.collect { mutableSignInPhase.value = it } }
            val captured = try { login.result.await() }
            catch (e: CancellationException) { login.cancel(); throw e }
            catch (e: Exception) { failSignIn(e.message ?: "The sign-in window could not start."); return@launch }
            finally { phaseForward.cancel() }
            if (captured == null) { cancelSignIn(); return@launch }
            mutableSignInPhase.value = cord.kasane.music.auth.SignInPhase.VALIDATING
            val session = try { captured.toSession() }
            catch (e: Exception) { failSignIn("The captured sign-in session is incomplete. Try again."); return@launch }
            mutex.withLock { checkSession(session) }
            mutableSignInPhase.value = null
        }.also { signInJob = it }
    }

    fun cancelSignIn() {
        generation.incrementAndGet()
        activeLogin?.cancel()
        activeLogin = null
        mutableSignInPhase.value = null
        mutableState.value = identity?.let(AccountState::SignedIn) ?: AccountState.Guest
    }

    fun failSignIn(message: String) {
        mutableSignInPhase.value = null
        mutableState.value = AccountState.Unavailable(message)
    }

    suspend fun signOut() = mutex.withLock {
        withContext(Dispatchers.IO) { store?.clear() }
        currentSession = null
        identity = null
        mutableState.value = AccountState.Guest
        modeStore?.write(cord.kasane.music.auth.SessionMode.UNDECIDED)
    }

    suspend fun expire() = mutex.withLock { expireLocked() }

    private suspend fun expireLocked() {
        withContext(Dispatchers.IO) { store?.clear() }
        currentSession = null
        identity = null
        mutableState.value = AccountState.Expired
    }

    /** Same validation-then-persist ordering as Android's AccountRepository.checkSession. */
    private suspend fun checkSession(session: AccountSession) {
        val ticket = generation.incrementAndGet()
        mutableState.value = AccountState.Checking
        try {
            val verified = validate(session)
            if (ticket != generation.get()) return
            withContext(Dispatchers.IO) { store?.write(session) }
            if (ticket != generation.get()) { withContext(Dispatchers.IO) { store?.clear() }; return }
            currentSession = session
            identity = verified
            mutableState.value = AccountState.SignedIn(verified)
            modeStore?.write(cord.kasane.music.auth.SessionMode.ACCOUNT)
        } catch (e: CancellationException) {
            if (ticket != generation.get()) return
            mutableState.value = identity?.let(AccountState::SignedIn) ?: AccountState.Guest
            throw e
        } catch (e: cord.kasane.music.provider.CatalogFailure) {
            if (ticket != generation.get()) return
            if (e.reason == cord.kasane.music.provider.CatalogFailureReason.SESSION_EXPIRED &&
                (currentSession == null || currentSession === session)) expireLocked()
            else mutableState.value = AccountState.Unavailable(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not verify the YouTube Music session. Try again.")
        } catch (e: SessionExpiredException) {
            if (ticket != generation.get()) return
            if (currentSession == null || currentSession === session) {
                expireLocked()
            } else {
                mutableState.value = AccountState.Unavailable("The selected YouTube channel could not be verified. Your previous session is still available.")
            }
        } catch (e: Exception) {
            if (ticket != generation.get()) return
            mutableState.value = AccountState.Unavailable(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not verify the YouTube Music session. Check your connection and try again.")
        }
    }

    /** The signed-in headers the verified playback resolver retries age-restricted tracks with. */
    fun signedInHeaders(): Map<String, String>? {
        val account = currentSession ?: return null
        val cookies = account.cookie.split(';').mapNotNull { value ->
            value.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val secret = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"] ?: return null
        val origin = "https://music.youtube.com"
        val time = System.currentTimeMillis() / 1000
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest("$time $secret $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return buildMap {
            put("Cookie", account.cookie)
            put("Authorization", "SAPISIDHASH ${time}_$hash")
            put("X-Goog-AuthUser", account.authUser.toString())
            account.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            account.channelId?.let { put("X-Goog-PageId", it) }
        }
    }

    private fun CapturedLogin.toSession(): AccountSession {
        val cookie = cookie.split(';').mapNotNull { raw ->
            val name = raw.trim().substringBefore('=')
            raw.trim().takeIf { name in AUTH_COOKIE_NAMES && it.contains('=') }
        }.distinctBy { it.substringBefore('=') }.joinToString("; ")
        return AccountSession(cookie = cookie, authUser = authUser, visitorData = visitorData, dataSyncId = dataSyncId)
    }

    private companion object {
        /** Same allow-list as Android's WebSessionParser: auth-scoped cookies only. */
        val AUTH_COOKIE_NAMES = setOf(
            "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID", "SID", "HSID", "SSID",
            "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "__Secure-1PSIDTS", "__Secure-3PSIDTS",
            "__Secure-1PSIDCC", "__Secure-3PSIDCC", "LSID", "__Host-1PLSID", "__Host-3PLSID",
        )
    }
}
