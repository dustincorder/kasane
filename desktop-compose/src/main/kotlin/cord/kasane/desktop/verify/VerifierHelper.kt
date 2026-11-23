package cord.kasane.desktop.verify

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `--verifier`: the helper process that hosts system WebKitGTK. One JSON request per stdin line
 * (`{"id","method","args"}`), one JSON reply per stdout line. The first line announces readiness and the
 * engine's User-Agent. It exits when stdin closes, so the parent's lifetime bounds every browser process.
 */
fun runVerifierHelper(): Int {
    val bundle = WebKitGtkRuntime::class.java.getResourceAsStream("/kasane-verifier.js")?.bufferedReader()?.use { it.readText() }
        ?: run { println(JSONObject().put("ready", false).put("error", "verifier bundle missing")); return 1 }
    val out = System.out
    WebKitGtkRuntime(bundle).use { runtime ->
        runBlocking {
            // Load the engine before announcing readiness, so the first real call does not pay for it.
            val ready = runCatching { runtime.call("protocol", JSONArray()) }
            synchronized(out) {
                out.println(JSONObject().put("ready", ready.isSuccess).put("userAgent", runtime.userAgent)
                    .put("error", ready.exceptionOrNull()?.message ?: JSONObject.NULL))
                out.flush()
            }
            if (ready.isFailure) return@runBlocking
            while (true) {
                val line = readLine() ?: break
                val request = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val reply = JSONObject().put("id", request.optLong("id"))
                runCatching { runtime.call(request.getString("method"), request.optJSONArray("args") ?: JSONArray()) }
                    .onSuccess { reply.put("ok", true).put("value", it ?: JSONObject.NULL) }
                    .onFailure { reply.put("ok", false).put("error", it.message ?: it.toString()) }
                synchronized(out) { out.println(reply); out.flush() }
            }
        }
    }
    return 0
}
