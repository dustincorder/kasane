package cord.kasane.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun `baked build properties reach BuildInfo`() {
        // Regression: the resource lookup must go through BuildInfo's class loader.
        // A lookup via a JDK class (bootstrap loader) silently leaves every default —
        // version stays "dev", channel stays "stable", nightly never activates.
        assertNotEquals("dev", BuildInfo.version)
        assertTrue(BuildInfo.channel == "stable" || BuildInfo.channel == "nightly")
        assertEquals(BuildInfo.channel == "nightly", BuildInfo.isNightly)
        assertEquals(if (BuildInfo.isNightly) "Kasane Nightly" else "Kasane", BuildInfo.appName)
    }
}
