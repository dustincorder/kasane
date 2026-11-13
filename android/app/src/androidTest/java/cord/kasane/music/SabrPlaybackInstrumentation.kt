package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import cord.kasane.music.provider.*
import cord.kasane.music.provider.sabr.*
import cord.kasane.music.playback.TransportMediaSource

/** Explicit disposable emulator runner. Never targets the user's production package. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SabrPlaybackInstrumentation:Instrumentation() {
    private var live=false
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);live=arguments?.getString("live")=="true";start()}
    private fun <T> main(block:()->T):T {
        var result:T?=null;var error:Throwable?=null
        runOnMainSync {try {result=block()}catch(e:Throwable){error=e}}
        error?.let {throw it};@Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitFor(label:String,timeout:Long=20000,condition:()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) {if(main(condition)) return;Thread.sleep(50)}
        throw AssertionError("Timed out: $label")
    }
    override fun onStart() {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish") && targetContext.packageName=="cord.kasane.music.dev")
        var player:ExoPlayer?=null;var activity:Activity?=null;var server:SabrFixtureServer?=null
        try {
            activity=startActivitySync(android.content.Intent(targetContext,cord.kasane.music.playback.PlaybackProbeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val client=okhttp3.OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build()
            val id=if(live)"JALbemLw3G4" else "fixture0001"
            val session=if(live) {
                val stream=kotlinx.coroutines.runBlocking {HlsPlayerResolver(client,diagnostic={android.util.Log.i("KasanePlayback",it)}).resolve(id)}
                check(stream is SabrStream) {"Public track did not supply compatible SABR"};stream.session
            } else {
                server=SabrFixtureServer(context)
                val root=org.json.JSONObject("""{"videoDetails":{"videoId":"fixture0001"},"playabilityStatus":{"status":"OK"},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}},"streamingData":{"serverAbrStreamingUrl":"https://rr1.googlevideo.com/videoplayback","adaptiveFormats":[{"itag":140,"lastModified":"12345","mimeType":"audio/mp4; codecs=\"mp4a.40.2\""}]}}""")
                SabrSession(requireNotNull(SabrConfig.parse(root,id,"IOS")),client,testEndpoint=server!!.endpoint).apply {initialize()}
            }
            val network=DefaultHttpDataSource.Factory()
            main {
                player=ExoPlayer.Builder(targetContext).build().apply {
                    volume=0f
                    setMediaSource(TransportMediaSource(MediaItem.Builder().setMediaId(id).setUri("kasane://track/$id").build(),{SabrStream(session)},network,
                        playbackPositionMs={player?.currentPosition?:0L}))
                    prepare();play()
                }
            }
            val p=player!!
            waitFor("SABR decoding") {p.isPlaying && p.currentPosition>1000 || p.playerError!=null}
            main {check(p.playerError==null);check((p.audioDecoderCounters?.renderedOutputBufferCount?:0)>0)}
            waitFor("SABR crosses minute",85000) {p.currentPosition>=61000 || p.playerError!=null}
            main {check(p.playerError==null);check(p.currentPosition>=61000)}
            if(live) {
                waitFor("real track EOF or protection failure",180000) {p.playbackState==Player.STATE_ENDED || p.playerError!=null}
                sendStatus(0,Bundle().apply{putString("stream","PUBLIC SABR result positionMs=${main {p.currentPosition}} media3Code=${main {p.playerError?.errorCode ?: 0}}\n")})
                main {check(p.playerError==null);check(p.playbackState==Player.STATE_ENDED)}
                finish(Activity.RESULT_OK,Bundle().apply{putString("stream","PUBLIC SABR full playback passed: $id\n")})
                return
            }
            waitFor("full SABR EOF",25000) {p.playbackState==Player.STATE_ENDED || p.playerError!=null}
            main {check(p.playerError==null);check(p.currentPosition>=76800)}
            main {p.seekTo(20000);p.play()}
            waitFor("SABR seek back") {p.isPlaying && p.currentPosition in 20000..28000 || p.playerError!=null}
            main {check(p.playerError==null);p.pause()}
            val paused=main {p.currentPosition};Thread.sleep(500)
            main {check(kotlin.math.abs(p.currentPosition-paused)<150);p.play()}
            waitFor("SABR resume") {p.isPlaying && p.currentPosition>paused+300}
            main {p.seekTo(64000)}
            waitFor("SABR seek forward") {p.isPlaying && p.currentPosition in 64000..72000 || p.playerError!=null}
            main {
                check(p.playerError==null)
                p.addMediaSource(androidx.media3.exoplayer.hls.HlsMediaSource.Factory(network).createMediaSource(
                    MediaItem.Builder().setMediaId("next").setUri(server!!.endpoint.substringBeforeLast('/')+"/hls/media.m3u8").build()))
                check(p.mediaItemCount==2);p.seekToNextMediaItem()
            }
            waitFor("next after SABR") {p.currentMediaItem?.mediaId=="next" && p.isPlaying || p.playerError!=null}
            main {check(p.playerError==null);check(p.mediaItemCount==2)}
            finish(Activity.RESULT_OK,Bundle().apply{putString("stream","SABR MEDIA3 PASSED: full 76.8s, >60s, AAC decoded, EOF, seek back/forward, pause/resume, next, queue retained\n")})
        } catch(e:Throwable) {
            // Never serialize network exception messages (they may contain signed URLs).
            finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","SABR MEDIA3 FAILED: ${e.javaClass.simpleName}\n")})
        } finally {main {player?.release();activity?.finish()};server?.close()}
    }
}
