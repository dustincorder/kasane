package cord.kasane.poc

import cord.kasane.music.playback.AudioRange
import cord.kasane.music.provider.verification.VerifiedResult
import cord.kasane.music.provider.verification.VerificationRuntime
import cord.kasane.music.provider.verification.VerifiedPlayerResolver
import cord.kasane.desktop.verify.WebKitGtkRuntime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** RSS in MB of this JVM and of every descendant process (WebKit's web/network processes). */
fun memory(): String {
    fun rss(pid: Long) = runCatching { File("/proc/$pid/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrDefault(0)
    val me = ProcessHandle.current()
    val kids = me.descendants().toList()
    return "jvm=${rss(me.pid())}MB children=${kids.joinToString { "${it.info().command().orElse("?").substringAfterLast('/')}:${rss(it.pid())}MB" }}"
}

fun children(): List<String> = ProcessHandle.current().descendants().map { it.info().command().orElse("?").substringAfterLast('/') }.toList()

fun main(args: Array<String>) {
    val engine = args.getOrElse(0) { "webkitgtk" }
    val ids = args.drop(1).ifEmpty { listOf("Tb0MC0jFv6M", "Jb6gcoR266U") }
    val bundle = File(System.getProperty("kasane.verifier") ?: "../../verifier/dist/kasane-verifier.js").readText()
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    println("engine=$engine  before: ${memory()}")
    val t0 = System.nanoTime()
    val runtime: VerificationRuntime = when (engine) {
        "webkitgtk" -> WebKitGtkRuntime(bundle)
        "javafx" -> JavaFxRuntime(bundle)
        else -> error("unknown engine $engine")
    }
    val protocol = runBlocking { runtime.call("protocol", org.json.JSONArray()) }
    println("engine + 490 KB bundle ready (protocol=$protocol) after ${(System.nanoTime() - t0) / 1_000_000}ms")
    val resolver = VerifiedPlayerResolver(http, runtime)
    runBlocking {
        for ((n, id) in ids.withIndex()) {
            val start = System.nanoTime()
            val stream = resolver.resolve(id)
            val ms = (System.nanoTime() - start) / 1_000_000
            when (stream) {
                is VerifiedResult.Verified -> {
                    val a = stream.audio
                    // Guest URLs now answer only the first range; a verified URL must serve the rest of the file.
                    val probes = listOf(0L, 1L shl 20, a.contentLength / 2, a.contentLength - 300_000).filter { it in 0 until a.contentLength }
                    val codes = probes.map { pos ->
                        http.newCall(Request.Builder().url(AudioRange.url(a.url, pos, 262_144)).apply { a.headers.forEach { (k, v) -> header(k, v) } }.build())
                            .execute().use { r -> "${pos / 1024}KiB:${r.code}/${r.body?.bytes()?.size}" }
                    }
                    println("[$n] $id verified in ${ms}ms itag=${a.itag} size=${a.contentLength / 1024}KiB ranges=${codes}")
                    if (n == 0) println("     startup incl. engine launch: ${(System.nanoTime() - t0) / 1_000_000}ms; ${memory()}")
                }
                is VerifiedResult.Unavailable -> println("[$n] $id FAILED in ${ms}ms: ${stream.code} ${stream.reason}")
            }
        }
    }
    println("after resolves: ${memory()}")
    if (System.getProperty("kasane.play") != null) runBlocking {
        // Real playback of a verified stream through the desktop's own engine (mpv, no sound), past the guest cut-off.
        val stream = resolver.resolve(ids[0]) as VerifiedResult.Verified
        val proxy = cord.kasane.desktop.audio.StreamProxy(http, cord.kasane.music.provider.GuestPlayerResolver(http))
        java.io.File("/tmp/kasane-poc-url").writeText(proxy.register(ids[0], stream.audio))
        println("serving verified stream on loopback for 120 s")
        kotlinx.coroutines.delay(120_000)
        println("cutOff=${proxy.consumeCutOff(ids[0])}")
        proxy.close()
    }
    (runtime as AutoCloseable).close()
    Thread.sleep(1500)
    println("after close: children still running = ${children()}")
}
