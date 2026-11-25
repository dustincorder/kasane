package cord.kasane.desktop.library

import cord.kasane.music.model.Track
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guest history is memory-only and never bleeds into a signed-in account's persisted feed. */
class LocalLibraryTest {
    private fun track(id: String) = Track(id, "Title $id", "Artist")

    @Test fun guestPlaysStayInMemory() {
        val file = createTempDirectory("lib").resolve("library.json").toFile()
        val library = LocalLibrary(file)
        library.setAccount(null)
        library.recordPlayed(track("g1"))
        library.recordPlayed(track("g2"))
        assertEquals(listOf("g2", "g1"), library.history.value.map { it.track.id })
        assertFalse(file.exists()) // nothing on disk for a guest
    }

    @Test fun accountHistoryPersistsAndReloads() {
        val file = createTempDirectory("lib").resolve("library.json").toFile()
        val library = LocalLibrary(file)
        library.setAccount("UC-channel")
        library.recordPlayed(track("a1"), now = 1000)
        library.recordPlayed(track("a2"), now = 2000)
        assertTrue(file.exists())

        val reloaded = LocalLibrary(file)
        reloaded.setAccount("UC-channel")
        assertEquals(listOf("a2", "a1"), reloaded.history.value.map { it.track.id })
        assertEquals(2, reloaded.history.value[0].plays + reloaded.history.value[1].plays)
    }

    @Test fun guestAndAccountFeedsDoNotMix() {
        val file = createTempDirectory("lib").resolve("library.json").toFile()
        val library = LocalLibrary(file)
        library.setAccount("UC-channel")
        library.recordPlayed(track("a1"))
        library.setAccount(null)
        library.recordPlayed(track("g1"))
        library.setAccount("UC-channel")
        assertEquals(listOf("a1"), library.history.value.map { it.track.id })
        library.setAccount(null)
        assertEquals(listOf("g1"), library.history.value.map { it.track.id })
    }

    @Test fun aDifferentAccountSeesItsOwnHistory() {
        val file = createTempDirectory("lib").resolve("library.json").toFile()
        val library = LocalLibrary(file)
        library.setAccount("UC-one"); library.recordPlayed(track("one"))
        library.setAccount("UC-two"); library.recordPlayed(track("two"))
        library.setAccount("UC-one")
        assertEquals(listOf("one"), library.history.value.map { it.track.id })
        library.setAccount("UC-two")
        assertEquals(listOf("two"), library.history.value.map { it.track.id })
    }

    @Test fun replaysMergeIntoOneEntryWithAPlayCount() {
        val file = createTempDirectory("lib").resolve("library.json").toFile()
        val library = LocalLibrary(file)
        library.setAccount("UC")
        library.recordPlayed(track("x"), now = 100)
        library.recordPlayed(track("x"), now = 200)
        assertEquals(1, library.history.value.size)
        assertEquals(2, library.history.value.single().plays)
        assertEquals(200L, library.history.value.single().playedAt)
        assertEquals(listOf("x"), library.topPlayed(limit = 1).map { it.id })
    }
}
