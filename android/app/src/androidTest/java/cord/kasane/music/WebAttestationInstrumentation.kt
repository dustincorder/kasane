package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.webkit.*
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Actual Google challenge in the emulator's unmodified WebView on an app-owned origin. */
class WebAttestationInstrumentation: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun <T> main(block:()->T):T {
        var result:T?=null;var failure:Throwable?=null
        runOnMainSync {try {result=block()}catch(e:Throwable){failure=e}}
        failure?.let {throw it};@Suppress("UNCHECKED_CAST") return result as T
    }
    private fun evaluate(view:WebView,script:String):String {
        val latch=CountDownLatch(1);var answer="null"
        main {
            val url=view.url.orEmpty()
            check(url=="about:blank" || android.net.Uri.parse(url).let {it.scheme=="https" && it.host=="appassets.androidplatform.net"})
            // Inline app documents may report about:blank through getUrl(); the
            // actual JS security origin must still be our application origin.
            view.evaluateJavascript("if(location.origin==='https://appassets.androidplatform.net'){ $script }"){answer=it;latch.countDown()}
        }
        check(latch.await(15,TimeUnit.SECONDS));return answer
    }
    private fun objectResult(view:WebView,script:String):JSONObject = JSONObject(JSONArray("[${evaluate(view,script)}]").getString(0))
    private fun waitFor(view:WebView,stage:String):JSONObject {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end){
            val state=objectResult(view,"globalThis.KasaneAttest.status()")
            if(state.optString("stage")==stage)return state
            if(state.optString("stage")=="failed")throw IllegalStateException("Runtime rejected challenge")
            Thread.sleep(100)
        }
        throw IllegalStateException("Runtime timeout")
    }
    private fun webContext(html:String):JSONObject {
        val match=Regex("\"INNERTUBE_CONTEXT\"\\s*:").find(html) ?: error("No web context")
        val start=html.indexOf('{',match.range.last+1);check(start>=0)
        var depth=0;var quoted=false;var escaped=false
        for(i in start until html.length){
            val c=html[i]
            if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='\"')quoted=false}
            else when(c){'\"'->quoted=true;'{'->depth++;'}'->{depth--;if(depth==0)return JSONObject(html.substring(start,i+1)).getJSONObject("client")}}
        }
        error("Incomplete web context")
    }
    override fun onStart() {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish") && targetContext.packageName=="cord.kasane.music.dev")
        var activity:Activity?=null;var web:WebView?=null;var phase="activity"
        try {
            activity=startActivitySync(android.content.Intent(targetContext,cord.kasane.music.playback.PlaybackProbeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val loaded=CountDownLatch(1)
            main {
                WebView.setDataDirectorySuffix("attestation_probe")
                WebView.setWebContentsDebuggingEnabled(false)
                web=WebView(activity!!).apply {
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=false
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs=false
                    @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs=false
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled=true;settings.setGeolocationEnabled(false)
                    settings.mediaPlaybackRequiresUserGesture=true;settings.blockNetworkLoads=true
                    webChromeClient=object:WebChromeClient(){
                        override fun onConsoleMessage(message:ConsoleMessage?)=true
                        override fun onPermissionRequest(request:PermissionRequest){request.deny()}
                    }
                    webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView,url:String){loaded.countDown()}}
                    activity!!.setContentView(this,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                    // Honest application origin. Never impersonates a YouTube document or browser UA.
                    loadDataWithBaseURL("https://appassets.androidplatform.net/","<!doctype html><html><head><title>Kasane verification</title></head><body></body></html>","text/html","UTF-8",null)
                }
            }
            check(loaded.await(20,TimeUnit.SECONDS));val view=requireNotNull(web)
            phase="bundle"
            evaluate(view,context.assets.open("attestation/probe.js").use {it.bufferedReader().readText()})
            val headers=objectResult(view,"globalThis.KasaneAttest.headers()")
            val ua=main {view.settings.userAgentString}
            val client=okhttp3.OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).build()
            fun request(endpoint:String,body:JSONArray):JSONArray {
                val builder=okhttp3.Request.Builder().url("https://www.youtube.com/api/jnn/v1/$endpoint")
                    .header("User-Agent",ua).post(body.toString().toRequestBody("application/json+protobuf".toMediaType()))
                headers.keys().forEach {name->builder.header(name,headers.getString(name))}
                return client.newCall(builder.build()).execute().use { response->
                    check(response.isSuccessful)
                    val source=requireNotNull(response.body).source();source.request(2L*1024*1024+1)
                    check(source.buffer.size<=2L*1024*1024)
                    JSONArray(source.readUtf8())
                }
            }
            phase="challenge"
            val key="O43z0dpjhgX20SCx4KAo"
            val challenge=request("Create",JSONArray().put(key))
            evaluate(view,"globalThis.KasaneAttest.challenge($challenge);void 0;")
            phase="snapshot"
            val state=waitFor(view,"snapshot_ready")
            phase="integrity"
            val integrity=request("GenerateIT",JSONArray().put(key).put(state.getString("snapshot")))
            evaluate(view,"globalThis.KasaneAttest.integrity($integrity,'JALbemLw3G4');void 0;")
            phase="mint"
            val ready=waitFor(view,"ready")
            check(ready.optString("token").length in 40..4096)
            val contentProof=ready.getString("token")
            phase="web_context"
            val cookies=mutableListOf<okhttp3.Cookie>()
            val webClient=client.newBuilder().cookieJar(object:okhttp3.CookieJar {
                override fun saveFromResponse(url:okhttp3.HttpUrl,values:List<okhttp3.Cookie>){if(url.host=="www.youtube.com"){cookies.removeAll {old->values.any {it.name==old.name}};cookies.addAll(values)}}
                override fun loadForRequest(url:okhttp3.HttpUrl)=if(url.host=="www.youtube.com")cookies.filter {it.matches(url)} else emptyList()
            }).addInterceptor { chain->chain.proceed(chain.request().newBuilder().header("User-Agent",ua).build()) }.build()
            val html=webClient.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/?app=desktop&persist_app=1&hl=en").build()).execute().use {r->
                sendStatus(0,Bundle().apply{putString("stream","Anonymous web context HTTP=${r.code}\n")})
                check(r.isSuccessful);val source=requireNotNull(r.body).source()
                source.request(2L*1024*1024+1);check(source.buffer.size<=2L*1024*1024);source.readUtf8()
            }
            phase="web_context_parse"
            val context=webContext(html)
            val profile=context.getString("clientName");check(profile in setOf("WEB","MWEB"))
            val version=context.getString("clientVersion");check(version.matches(Regex("[0-9.]{3,40}")))
            val visitor=context.optString("visitorData").ifBlank {
                Regex("\"VISITOR_DATA\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\]){1,4096}\")").find(html)?.groupValues?.get(1)
                    ?.let {JSONArray("[$it]").optString(0)}.orEmpty()
            }
            check(visitor.length in 1..4096);context.put("visitorData",visitor)
            evaluate(view,"globalThis.KasaneAttest.mint(${JSONObject.quote(visitor)});void 0;")
            val playerProof=waitFor(view,"ready").getString("token")
            // Use the timestamp advertised by this exact web context, never a guessed version.
            val sts=Regex("\"STS\"\\s*:\\s*([0-9]{5})").find(html)?.groupValues?.get(1)?.toIntOrNull()
            check(sts!=null)
            phase="web_player"
            val body=JSONObject().put("videoId","JALbemLw3G4").put("context",JSONObject().put("client",context))
                .put("playbackContext",JSONObject().put("contentPlaybackContext",JSONObject()
                    .put("signatureTimestamp",sts).put("html5Preference","HTML5_PREF_WANTS")))
                .put("serviceIntegrityDimensions",JSONObject().put("poToken",playerProof))
            val root=webClient.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("X-Youtube-Client-Name",if(profile=="WEB")"1" else "2").header("X-Youtube-Client-Version",version)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {r->
                check(r.isSuccessful);val source=requireNotNull(r.body).source();source.request(2L*1024*1024+1);check(source.buffer.size<=2L*1024*1024);JSONObject(source.readUtf8())
            }
            val status=root.optJSONObject("playabilityStatus")?.optString("status").orEmpty().takeIf {it.matches(Regex("[A-Z_]{1,40}"))} ?: "UNKNOWN"
            sendStatus(0,Bundle().apply{putString("stream","WEB player profile=$profile status=$status timestampPresent=true sessionBoundPlayerProof=true\n")})
            phase="sabr_config"
            val config=requireNotNull(cord.kasane.music.provider.sabr.SabrConfig.parse(root,"JALbemLw3G4",profile,version))
            phase="gvs_proof"
            val proof=cord.kasane.music.provider.sabr.WebPlaybackProof("JALbemLw3G4",java.util.Base64.getUrlDecoder().decode(contentProof),
                System.currentTimeMillis()+minOf(integrity.optLong(1),3600)*1000)
            var protection=-1
            cord.kasane.music.provider.sabr.SabrSession(config,webClient,diagnostic={line->
                Regex("event=protection status=([0-9]+)").find(line)?.let {protection=it.groupValues[1].toInt();sendStatus(0,Bundle().apply{putString("stream","WEB GVS protection status=$protection\n")})}
            },proof=proof).use {session->
                session.initialize()
                sendStatus(0,Bundle().apply{putString("stream","WEB GVS proof status=$protection\n")})
                check(protection==1)
            }
            finish(Activity.RESULT_OK,Bundle().apply{putString("stream","WEB ATTESTATION: real proof accepted by GVS, status=1; full playback not yet tested\n")})
        }catch(e:Throwable){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","WEB ATTESTATION FAILED: phase=$phase type=${e.javaClass.simpleName} reason=${(e as? cord.kasane.music.provider.sabr.SabrException)?.code ?: "DIAGNOSTIC"}\n")})}
        finally {main {web?.apply {stopLoading();destroy()};activity?.finish()}}
    }
}
