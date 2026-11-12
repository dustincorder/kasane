package cord.kasane.music.auth

/** Sensitive InnerTube web session. Never log or stringify the stored values. */
class AccountSession(
    val cookie: String,
    val authUser: Int = 0,
    val channelId: String? = null,
    val delegatedSessionId: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
) {
    init {
        require(authUser >= 0)
        require(cookie.isNotBlank() && cookie.all { it.code in 32..126 })
        require(cookie.split(';').any {
            val name = it.trim().substringBefore('=')
            name == "SAPISID" || name == "__Secure-3PAPISID" || name == "__Secure-1PAPISID"
        })
    }
    override fun toString() = "AccountSession([redacted])"
}

data class AccountIdentity(val name: String, val avatar: String?, val channelId: String?)
sealed interface AccountState {
    data object Guest : AccountState
    data object Checking : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val identity: AccountIdentity) : AccountState
    data object Expired : AccountState
    data class Unavailable(val message: String) : AccountState
}
interface SessionStore {
    fun read(): AccountSession?
    fun write(session: AccountSession)
    fun clear()
}
