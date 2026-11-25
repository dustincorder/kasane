package cord.kasane.music.auth

/**
 * What the user chose on the sign-in screen, persisted per install (not per account):
 * an explicit GUEST stays guest across restarts — a stored account session must NOT
 * silently come back just because one exists in the keychain. Signing in flips the
 * mode to ACCOUNT; signing out returns it to UNDECIDED.
 */
enum class SessionMode { UNDECIDED, GUEST, ACCOUNT }

/** Where each client persists the choice (settings file on desktop, prefs on Android). */
interface SessionModeStore {
    fun read(): SessionMode
    fun write(mode: SessionMode)
}

/**
 * Onboarding/session gates shared by both clients:
 * - onboarding shows only until it is finished AND while no account session is live —
 *   a user who already signed in never sees the first-run flow again, even if the
 *   install's `onboardingDone` flag was lost or never set;
 * - a stored session is restored only in UNDECIDED/ACCOUNT mode — "Continue as a guest"
 *   must not resurrect the account.
 */
object SessionPolicy {
    fun showsOnboarding(onboardingDone: Boolean, state: AccountState): Boolean =
        !onboardingDone && state !is AccountState.SignedIn

    fun restoresStoredSession(mode: SessionMode): Boolean = mode != SessionMode.GUEST
}

/** Where a sign-in window attempt stands; the UI explains the wait instead of a dead button. */
enum class SignInPhase {
    /** Helper process spawning and the browser engine booting — can take tens of seconds cold. */
    STARTING,
    /** The sign-in window is on screen and waiting for the user. */
    WINDOW_OPEN,
    /** A session was captured and is being validated. */
    VALIDATING,
}
