package cord.kasane.music.auth

/** The InnerTube session was rejected; sign-in is required again. Shared so both
 *  clients' catalog layers can surface one error type (Desktop maps
 *  `CatalogFailure(SESSION_EXPIRED)` to this). */
class SessionExpiredException(message: String = "Your YouTube Music session expired. Sign in again.") :
    java.io.IOException(message)
