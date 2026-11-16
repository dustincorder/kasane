package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import kotlin.test.*

class AccountContractTest {
    @Test fun sessionRemainsRedactedAcrossPortableBoundary() {
        val session = AccountSession("SAPISID=fixture-private-value", authUser = 2, channelId = "fixture-channel")
        assertFalse(session.toString().contains("fixture-private-value"))
        assertFalse(session.toString().contains("fixture-channel"))
        assertEquals(2, session.authUser)
    }
    @Test fun invalidSessionCannotEnterEitherPlatform() {
        assertFailsWith<IllegalArgumentException> { AccountSession("SAPISID=fixture\nInjected: header") }
        assertFailsWith<IllegalArgumentException> { AccountSession("OTHER=fixture") }
        assertFailsWith<IllegalArgumentException> { AccountSession("SAPISID=fixture", authUser = -1) }
    }
}
