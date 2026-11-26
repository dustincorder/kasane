package cord.kasane.desktop.auth

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionStore
import org.json.JSONObject
import java.io.IOException

/**
 * The YouTube Music session in the OS Secret Service (GNOME Keyring / KWallet via libsecret,
 * called through JNA — nothing bundled). [probe] decides at startup whether the service is
 * reachable; when it is not, the caller keeps the session in memory only and the UI says so.
 * Cookie and tokens are never written to a plain file or logged.
 */
class SecretServiceStore(
    schemaName: String = SCHEMA_NAME,
    private val item: String = ITEM,
) : SessionStore {
    enum class Availability { READY, EMPTY, UNAVAILABLE }

    private val lib = NativeLibrary.getInstance("secret-1")
    private val glib = NativeLibrary.getInstance("glib-2.0")
    private fun f(name: String): Function = lib.getFunction(name)

    private val schema: Pointer = f("secret_schema_new").invokePointer(
        arrayOf(schemaName, 0, "service", 0, null)) // SECRET_SCHEMA_NONE, one STRING attribute
        ?: throw IOException("libsecret rejected the session schema")

    @Synchronized
    fun probe(): Availability {
        val error = Memory(8).apply { clear() }
        val raw = f("secret_password_lookup_sync").invokePointer(
            arrayOf(schema, null, error, "service", item, null))
        val gerr = error.getPointer(0)
        if (raw == null) {
            if (gerr != null) return Availability.UNAVAILABLE.also { gerrorFree(gerr) }
            return Availability.EMPTY
        }
        free(raw)
        return Availability.READY
    }

    @Synchronized
    override fun read(): AccountSession? {
        val error = Memory(8).apply { clear() }
        val raw = f("secret_password_lookup_sync").invokePointer(
            arrayOf(schema, null, error, "service", item, null))
        val gerr = error.getPointer(0)
        if (raw == null) {
            if (gerr != null) {
                val message = gerror(gerr)
                gerrorFree(gerr)
                throw IOException("The saved YouTube Music session could not be read ($message). Sign in again.")
            }
            return null
        }
        val json = try { JSONObject(raw.getString(0, "UTF-8")) } finally { free(raw) }
        return try {
            AccountSession(
                cookie = json.getString("cookie"),
                authUser = json.optInt("authUser", 0),
                channelId = json.optNullable("channelId"),
                delegatedSessionId = json.optNullable("delegatedSessionId"),
                visitorData = json.optNullable("visitorData"),
                dataSyncId = json.optNullable("dataSyncId"),
            )
        } catch (e: Exception) {
            clear()
            throw IOException("The saved YouTube Music session could not be restored. Sign in again.")
        }
    }

    @Synchronized
    override fun write(session: AccountSession) {
        val json = JSONObject().put("cookie", session.cookie).put("authUser", session.authUser)
            .put("channelId", session.channelId).put("delegatedSessionId", session.delegatedSessionId)
            .put("visitorData", session.visitorData).put("dataSyncId", session.dataSyncId).toString()
        val error = Memory(8).apply { clear() }
        val ok = f("secret_password_store_sync").invokeInt(
            arrayOf(schema, "default", LABEL, json, null, error, "service", item, null))
        val gerr = error.getPointer(0)
        if (ok != 1) {
            val message = if (gerr != null) gerror(gerr).also { gerrorFree(gerr) } else "unknown error"
            throw IOException("The Secret Service refused to store the YouTube Music session ($message).")
        }
    }

    @Synchronized
    override fun clear() {
        val error = Memory(8).apply { clear() }
        f("secret_password_clear_sync").invokeInt(arrayOf(schema, null, error, "service", item, null))
        error.getPointer(0)?.let(::gerrorFree)
    }

    /** GError: gint domain, gint code, gchar* message — message sits at offset 8 on 64-bit. */
    private fun gerror(error: Pointer): String = error.getPointer(8)?.getString(0) ?: "unknown error"
    private fun gerrorFree(error: Pointer) = glib.getFunction("g_error_free").invokeVoid(arrayOf(error))
    private fun free(value: Pointer) = f("secret_password_free").invokeVoid(arrayOf(value))

    private fun JSONObject.optNullable(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        const val SCHEMA_NAME = "cord.kasane.compose.session"
        const val ITEM = "youtube-session"
        const val LABEL = "Kasane YouTube Music session"
    }
}
