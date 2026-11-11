package cord.kasane.music.auth

/** Never log, interpolate or persist this object outside SessionStore. */
class AccountSession(val cookie: String, val authUser: Int = 0, val channelId: String? = null, val delegatedSessionId: String? = null) {
    init {
        require(authUser >= 0)
        require(cookie.all { it.code in 32..126 })
        require(cookie.split(';').any { it.trim().startsWith("SAPISID=") || it.trim().startsWith("__Secure-3PAPISID=") })
    }
    override fun toString() = "AccountSession([redacted])"
}
data class AccountIdentity(val name: String, val avatar: String?, val channelId: String?)
sealed interface AccountState {
    data object Guest : AccountState
    data object Checking : AccountState
    data class SignedIn(val identity: AccountIdentity) : AccountState
    data object Expired : AccountState
    data class Unavailable(val message: String) : AccountState
}
interface SessionStore {
    fun read(): AccountSession?
    fun write(session: AccountSession)
    fun clear()
}
