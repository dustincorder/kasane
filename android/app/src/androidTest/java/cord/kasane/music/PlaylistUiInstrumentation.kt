package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.Track
import cord.kasane.music.provider.*
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.CompletableDeferred

/** Real modal/back/insets interactions with a test provider, isolated emulator only. */
class PlaylistUiInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun <T> main(block:()->T):T {
        var result:T?=null;var error:Throwable?=null
        runOnMainSync{try{result=block()}catch(e:Throwable){error=e}}
        error?.let{throw it};@Suppress("UNCHECKED_CAST")return result as T
    }
    private fun waitFor(label:String,condition:()->Boolean){
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(50)}
        error("Timed out: $label")
    }
    private fun clickable(text:String):AccessibilityNodeInfo? {
        val root=uiAutomation.rootInActiveWindow ?: return null
        val pending=java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while(pending.isNotEmpty()) {
            val candidate=pending.removeFirst()
            if(candidate.text?.toString()?.contains(text)==true || candidate.contentDescription?.toString()?.contains(text)==true) {
                var node:AccessibilityNodeInfo?=candidate
                repeat(5){if(node?.isClickable==true)return node;node=node?.parent}
            }
            repeat(candidate.childCount){index->candidate.getChild(index)?.let(pending::add)}
        }
        return null
    }
    override fun onStart(){
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish") && targetContext.packageName=="cord.kasane.music.dev")
        var activity:Activity?=null;var view:ComposeView?=null;var picker:PlaylistPicker?=null;var phase="start"
        val signed=mutableStateOf(true);val gate=CompletableDeferred<Unit>()
        var writes=0;var invalidated=0;var underlayBacks=0
        val track=Track("dQw4w9WgXcQ","Test track with a long title that wraps safely","Test artist")
        val provider=object:AccountPlaylistProvider{
            override suspend fun playlistDestinations(account:AccountSession,videoId:String)=listOf(
                PlaylistDestination("PLtarget","Choose this destination","PRIVATE",null,false),
                PlaylistDestination("PLexists","Already contains this song","PUBLIC",null,true))
            override suspend fun addVideoToPlaylist(account:AccountSession,playlistId:String,videoId:String){writes++;gate.await()}
        }
        try{
            activity=startActivitySync(android.content.Intent(targetContext,cord.kasane.music.playback.PlaybackProbeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            main{
                picker=PlaylistPicker(AccountSession("SAPISID=emulator-fixture"),provider,{invalidated++})
                view=ComposeView(activity!!).apply{setContent{
                    MaterialTheme(colorScheme=KasaneDarkColorScheme,typography=KasaneTypography){
                        androidx.activity.compose.BackHandler{underlayBacks++}
                        CompositionLocalProvider(LocalPlaylistPicker provides if(signed.value)picker else null){
                            Surface(Modifier.fillMaxSize(),color=Gray950){
                                Column{Text("Player underneath");TrackRow(track,onClick={});PlaylistPickerHost()}
                            }
                        }
                    }
                }}
                activity!!.setContentView(view)
                picker!!.open(track)
            }
            phase="sheet"
            waitFor("loaded sheet"){main{picker!!.state.value.visible && !picker!!.state.value.loading}}
            waitForIdleSync();Thread.sleep(500)
            waitFor("refresh accessibility node"){clickable("Refresh playlists")!=null}
            val refresh=clickable("Refresh playlists") ?: error("Refresh action missing")
            val bounds=android.graphics.Rect();refresh.getBoundsInScreen(bounds)
            val safeBottom=main{
                val window=activity!!.windowManager.currentWindowMetrics
                window.bounds.height()-window.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            }
            check(bounds.bottom<=safeBottom && bounds.height()>0)
            val bitmap=uiAutomation.takeScreenshot()
            java.io.File(targetContext.cacheDir,"playlist-picker-ui.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            check(writes==0)
            phase="back"
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("back closes only sheet"){main{!picker!!.state.value.visible}}
            check(underlayBacks==0)
            main{picker!!.open(track)}
            waitFor("reopened"){main{!picker!!.state.value.loading}}
            waitForIdleSync();Thread.sleep(400)
            phase="add"
            check(clickable("Choose this destination")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            waitFor("pending write"){main{picker!!.state.value.pendingPlaylist=="PLtarget"}}
            clickable("Choose this destination")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100);check(writes==1)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("dismiss during write"){main{!picker!!.state.value.visible}}
            gate.complete(Unit)
            waitFor("confirmed write invalidates metadata"){main{invalidated==1 && picker!!.state.value.pendingPlaylist==null}}
            phase="account_transitions"
            repeat(60){main{signed.value=!signed.value};waitForIdleSync();Thread.sleep(20)}
            finish(Activity.RESULT_OK,Bundle().apply{putString("stream","PLAYLIST UI PASSED: native click, safe navigation inset, Back, one write, dismiss during write, 60 Guest/account transitions\n")})
        }catch(e:Throwable){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","PLAYLIST UI FAILED: phase=$phase type=${e.javaClass.simpleName} detail=${e.message?.take(160)}\n")})}
        finally{main{picker?.close();view?.disposeComposition();activity?.finish()}}
    }
}
