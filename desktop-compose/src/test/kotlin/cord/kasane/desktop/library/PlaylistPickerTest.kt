package cord.kasane.desktop.library

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.Track
import cord.kasane.music.provider.AccountPlaylistProvider
import cord.kasane.music.provider.CatalogFailure
import cord.kasane.music.provider.CatalogFailureReason
import cord.kasane.music.provider.PlaylistDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors Android's PlaylistPickerTest: membership is confirmed, never assumed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPickerTest {
    private val track = Track("abcdefghijk", "Song", "Artist")
    private val session = AccountSession("SAPISID=x")

    private class FakeProvider(var destinations: List<PlaylistDestination> = emptyList()) : AccountPlaylistProvider {
        var writes = 0
        var added = false
        var writeError: Exception? = null
        var readError: Exception? = null
        override suspend fun playlistDestinations(account: AccountSession, videoId: String): List<PlaylistDestination> {
            readError?.let { throw it }
            return destinations.map { if (it.id == "p1" && added) it.copy(alreadyAdded = true) else it }
        }
        override suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String) {
            writes++
            writeError?.let { throw it }
            added = true
        }
    }

    private fun destinations() = listOf(
        PlaylistDestination("p1", "Mix", "PRIVATE", null, alreadyAdded = false),
        PlaylistDestination("p2", "Done", "PRIVATE", null, alreadyAdded = true))

    @Test fun openLoadsDestinations() = runTest(UnconfinedTestDispatcher()) {
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, FakeProvider(destinations()))
        picker.open(track)
        val s = picker.state.value
        assertTrue(s.visible)
        assertFalse(s.loading)
        assertEquals(listOf("p1", "p2"), s.options.map { it.id })
        assertNull(s.error)
    }

    @Test fun aConfirmedWriteClosesThePicker() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(destinations())
        val messages = mutableListOf<String>()
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, provider)
        backgroundScope.launch { picker.messages.collect { messages += it } }
        picker.open(track)
        picker.add("p1")
        assertFalse(picker.state.value.visible)
        assertTrue(provider.added)
        assertEquals(1, provider.writes)
        assertEquals(listOf("Added to Mix"), messages)
    }

    @Test fun aLostResponseIsConfirmedByMembershipRead() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(destinations())
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, provider)
        picker.open(track)
        provider.writeError = java.io.IOException("lost acknowledgement")
        provider.added = true // the write actually landed
        picker.add("p1")
        assertFalse(picker.state.value.visible) // confirmed on re-read, no second write
        assertEquals(1, provider.writes)
    }

    @Test fun anUnconfirmedWriteDemandsRefresh() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(destinations())
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, provider)
        picker.open(track)
        provider.writeError = java.io.IOException("network")
        picker.add("p1")
        val s = picker.state.value
        assertTrue(s.refreshRequired)
        assertTrue(s.error!!.contains("confirm"))
        picker.refresh() // recoverable via refresh
        assertFalse(picker.state.value.refreshRequired)
        assertNull(picker.state.value.error)
    }

    @Test fun aSessionExpiryStopsConfirmReads() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(destinations())
        provider.writeError = CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, provider)
        picker.open(track)
        picker.add("p1")
        val s = picker.state.value
        assertTrue(s.error!!.contains("expired"))
        assertTrue(s.refreshRequired)
    }

    @Test fun guestsCannotOpen() = runTest(UnconfinedTestDispatcher()) {
        val messages = mutableListOf<String>()
        val picker = DesktopPlaylistPicker(backgroundScope, { null }, FakeProvider(destinations()))
        backgroundScope.launch { picker.messages.collect { messages += it } }
        picker.open(track)
        assertFalse(picker.state.value.visible)
        assertEquals(listOf("Sign in to add songs to playlists."), messages)
    }

    @Test fun nonYouTubeTracksNeverOpen() = runTest(UnconfinedTestDispatcher()) {
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, FakeProvider(destinations()))
        picker.open(Track("local-file", "Local", "Me", isLocal = true, sourceProvider = "local"))
        picker.open(Track("short", "Bad id", "Me"))
        assertFalse(picker.state.value.visible)
    }

    @Test fun dismissNeverCancelsAWrite() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(destinations())
        val picker = DesktopPlaylistPicker(backgroundScope, { session }, provider)
        picker.open(track)
        picker.add("p1")
        picker.dismiss() // the write already completed on Unconfined; state stays consistent
        assertTrue(provider.added)
        assertFalse(picker.state.value.visible)
    }
}
