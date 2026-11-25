package cord.kasane.desktop.login

import org.json.JSONObject

/**
 * `--login`: the helper process that shows the Google sign-in window (system WebKitGTK, ephemeral
 * profile — nothing on disk). Prints exactly one JSON result line to stdout and exits:
 * a captured session or a failure/cancellation.
 */
fun runLoginHelper(): Int {
    // A missing display aborts inside gtk_init before any window exists; the parent reads EOF.
    LoginWindow().await()
    return 0
}

/** The parsed result line of the `--login` helper. */
data class CapturedLogin(val cookie: String, val visitorData: String?, val dataSyncId: String?, val authUser: Int)

fun JSONObject.toCapturedLogin(): CapturedLogin? {
    if (!optBoolean("ok")) return null
    val cookie = optString("cookie").takeIf(String::isNotBlank) ?: return null
    val config = optJSONObject("config")
    return CapturedLogin(
        cookie = cookie,
        visitorData = config?.optString("visitorData")?.takeIf(String::isNotBlank),
        dataSyncId = config?.optString("dataSyncId")?.takeIf(String::isNotBlank),
        authUser = config?.optString("authUser", "0")?.toIntOrNull()?.takeIf { it >= 0 } ?: 0,
    )
}
