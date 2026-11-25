package cord.kasane.music
import cord.kasane.music.auth.AccountState
import cord.kasane.music.auth.SessionMode
import cord.kasane.music.auth.SessionPolicy
import cord.kasane.music.playback.PlayThreshold
import cord.kasane.music.playback.WatchReport
import kotlin.test.*

class SessionAndHistoryPolicyTest {
    // Onboarding shows only while unfinished AND without a live session — a signed-in user
    // never sees the first-run flow again, even if the install's flag was lost.
    @Test fun onboardingSkipsWhenSessionLive() {
        assertTrue(SessionPolicy.showsOnboarding(onboardingDone = false, AccountState.Guest))
        assertFalse(SessionPolicy.showsOnboarding(onboardingDone = true, AccountState.Guest))
        assertFalse(SessionPolicy.showsOnboarding(onboardingDone = false,
            AccountState.SignedIn(cord.kasane.music.auth.AccountIdentity("n", "e", null))))
    }

    // "Continue as a guest" is sticky: a stored account session must not resurrect.
    @Test fun guestModeBlocksSessionRestore() {
        assertFalse(SessionPolicy.restoresStoredSession(SessionMode.GUEST))
        assertTrue(SessionPolicy.restoresStoredSession(SessionMode.UNDECIDED))
        assertTrue(SessionPolicy.restoresStoredSession(SessionMode.ACCOUNT))
    }

    // A listen counts after 30 s, or half the track for short songs — never at load time.
    @Test fun playThreshold() {
        assertFalse(PlayThreshold.counts(0.0, 200.0))
        assertFalse(PlayThreshold.counts(29.9, 200.0))
        assertTrue(PlayThreshold.counts(30.0, 200.0))
        assertTrue(PlayThreshold.counts(15.0, 28.0))   // 28 s song: half = 14 s
        assertFalse(PlayThreshold.counts(13.9, 28.0))
        assertFalse(PlayThreshold.counts(20.0, 0.0))   // unknown duration → 30 s bar
    }

    // The watch ping carries only the keys YouTube expects to fill per watch.
    @Test fun watchReportFillsMissingKeys() {
        val url = WatchReport.url("https://www.youtube.com/api/stats/watchtime?ns=yt&cpn=abc",
            positionSec = 31.6, durationSec = 211.2, elapsedSec = 33.05)!!
        assertTrue(url.contains("pos=31") && url.contains("len=211"))
        assertTrue(url.contains("cmt=31.600") && url.contains("rt=33.050") && url.contains("ver=2"))
        // Keys the service already set are preserved, not duplicated.
        val kept = WatchReport.url("https://www.youtube.com/api/stats/watchtime?pos=5&ver=2", 30.0, 200.0)!!
        assertEquals(1, kept.split("pos=").size - 1)
        assertNull(WatchReport.url("javascript:alert(1)", 30.0, 200.0))
    }
}
