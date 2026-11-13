package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionExpiredException
import cord.kasane.music.model.Track
import cord.kasane.music.provider.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistPickerTest {
    private val account=AccountSession("SAPISID=picker-test-fixture")
    private val track=Track("dQw4w9WgXcQ","A track","Artist")
    private val destination=PlaylistDestination("PLowned","My playlist","PRIVATE",null,false)
    private open inner class FakeProvider:AccountPlaylistProvider {
        var reads=0;var writes=0;var added=false
        override suspend fun playlistDestinations(account:AccountSession,videoId:String):List<PlaylistDestination> {
            reads++;return listOf(destination.copy(alreadyAdded=added))
        }
        override suspend fun addVideoToPlaylist(account:AccountSession,playlistId:String,videoId:String){writes++;added=true}
    }
    @Test fun openingAndRefreshingNeverWritesAndExplicitAddWritesOnce() = runTest {
        val provider=FakeProvider();val changed=mutableListOf<String>()
        PlaylistPicker(account,provider,{changed.add(it)},StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent()
            assertEquals(1,provider.reads);assertEquals(0,provider.writes)
            picker.add("PLowned");picker.add("PLowned");testScheduler.runCurrent()
            assertEquals(1,provider.writes);assertEquals(listOf("PLowned"),changed)
            assertFalse(picker.state.value.visible)
        }
    }
    @Test fun alreadyAddedAndUnknownRowsCannotTriggerWrites() = runTest {
        val provider=FakeProvider().apply{added=true}
        PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent()
            picker.add("PLowned");picker.add("PLnotReturned");testScheduler.runCurrent()
            assertEquals(0,provider.writes)
        }
    }
    @Test fun canceledReadCannotLeakOldTrackDestinations() = runTest {
        val gate=CompletableDeferred<Unit>()
        val provider=object:FakeProvider(){
            override suspend fun playlistDestinations(account:AccountSession,videoId:String):List<PlaylistDestination>{
                reads++;if(videoId==track.id)gate.await();return listOf(destination.copy(title=videoId))
            }
        }
        PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent()
            picker.open(Track("wzpAehzNCU8","Another","Artist"));testScheduler.runCurrent()
            gate.complete(Unit);testScheduler.runCurrent()
            assertEquals("wzpAehzNCU8",picker.state.value.options.single().title)
        }
    }
    @Test fun lostAcknowledgementChecksMembershipWithoutRepeatingWrite() = runTest {
        val provider=object:FakeProvider(){
            override suspend fun addVideoToPlaylist(account:AccountSession,playlistId:String,videoId:String){writes++;added=true;throw java.io.IOException("lost acknowledgement")}
        }
        PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent();picker.add("PLowned");testScheduler.runCurrent()
            assertEquals(1,provider.writes);assertEquals(2,provider.reads)
            assertFalse(picker.state.value.visible);assertTrue(picker.state.value.options.single().alreadyAdded)
        }
    }
    @Test fun unconfirmedWriteNeedsFreshMembershipBeforeExplicitRetry() = runTest {
        val provider=object:FakeProvider(){
            override suspend fun addVideoToPlaylist(account:AccountSession,playlistId:String,videoId:String){writes++;throw java.io.IOException("network")}
        }
        PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent();picker.add("PLowned");testScheduler.runCurrent()
            assertTrue(picker.state.value.refreshRequired);assertNotNull(picker.state.value.error)
            picker.add("PLowned");testScheduler.runCurrent();assertEquals(1,provider.writes)
            picker.refresh();testScheduler.runCurrent();assertFalse(picker.state.value.refreshRequired)
        }
    }
    @Test fun closingSheetDoesNotLoseAnAlreadyRequestedWrite() = runTest {
        val gate=CompletableDeferred<Unit>()
        val provider=object:FakeProvider(){
            override suspend fun addVideoToPlaylist(account:AccountSession,playlistId:String,videoId:String){writes++;gate.await();added=true}
        }
        var invalidated=false
        PlaylistPicker(account,provider,{invalidated=true},StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track);testScheduler.runCurrent();picker.add("PLowned");testScheduler.runCurrent()
            picker.dismiss();gate.complete(Unit);testScheduler.runCurrent()
            assertTrue(provider.added);assertTrue(invalidated);assertFalse(picker.state.value.visible)
        }
    }
    @Test fun logoutCancelsPendingReadAndDoesNotSendAMutation() = runTest {
        val provider=object:FakeProvider(){override suspend fun playlistDestinations(account:AccountSession,videoId:String):List<PlaylistDestination>{reads++;kotlinx.coroutines.awaitCancellation()}}
        val picker=PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler))
        picker.open(track);testScheduler.runCurrent();picker.close();testScheduler.runCurrent()
        picker.add("PLowned");testScheduler.runCurrent();assertEquals(0,provider.writes)
    }
    @Test fun localTracksNeverQueryTheOnlinePicker() = runTest {
        val provider=FakeProvider()
        PlaylistPicker(account,provider,dispatcher=StandardTestDispatcher(testScheduler)).use {picker->
            picker.open(track.copy(isLocal=true));testScheduler.runCurrent();assertEquals(0,provider.reads)
        }
    }
}
