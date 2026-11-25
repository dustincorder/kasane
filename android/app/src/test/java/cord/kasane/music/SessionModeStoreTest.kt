package cord.kasane.music

import cord.kasane.music.auth.SessionMode
import cord.kasane.music.auth.SessionPolicy
import cord.kasane.music.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [AppSettings] implements the shared [SessionMode] store: guest stays guest across restarts. */
@RunWith(RobolectricTestRunner::class)
class SessionModeStoreTest {
    private fun settings() = AppSettings(RuntimeEnvironment.getApplication())

    @Test fun sessionModeDefaultsToUndecided() {
        assertEquals(SessionMode.UNDECIDED, settings().sessionMode())
    }

    @Test fun sessionModeRoundTrips() {
        val s = settings()
        SessionMode.entries.forEach { mode ->
            s.setSessionMode(mode)
            assertEquals(mode, s.sessionMode())
            assertEquals(mode, s.read()) // SessionModeStore interface
        }
    }

    @Test fun guestModeBlocksStoredSessionRestore() {
        val s = settings()
        s.setSessionMode(SessionMode.GUEST)
        assertFalse(SessionPolicy.restoresStoredSession(s.sessionMode()))
        s.setSessionMode(SessionMode.ACCOUNT)
        assertTrue(SessionPolicy.restoresStoredSession(s.sessionMode()))
    }

    @Test fun guestHistoryPreferenceIsSeparateFromAccount() {
        val s = settings()
        s.setSaveListeningHistory(true)
        s.setGuestSaveHistory(false)
        assertTrue(s.historyRecordingEnabled(signedIn = true))
        assertFalse(s.historyRecordingEnabled(signedIn = false))
        s.setGuestSaveHistory(true)
        s.setSaveListeningHistory(false)
        assertFalse(s.historyRecordingEnabled(signedIn = true))
        assertTrue(s.historyRecordingEnabled(signedIn = false))
    }
}
