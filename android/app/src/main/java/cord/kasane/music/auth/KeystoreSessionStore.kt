package cord.kasane.music.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext lives in noBackupFilesDir; plaintext session fields never enter Room/preferences. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "youtube-session.enc"))
    private val alias = "kasane.youtube.session.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized override fun read(): AccountSession? {
        if (!file.baseFile.exists()) return null
        try {
            val bytes = file.readFully()
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            return AccountSession(
                cookie = json.getString("cookie"),
                authUser = json.optInt("authUser", 0),
                channelId = json.optNullable("channelId"),
                delegatedSessionId = json.optNullable("delegatedSessionId"),
                visitorData = json.optNullable("visitorData"),
                dataSyncId = json.optNullable("dataSyncId"),
            )
        } catch (_: Exception) {
            clear()
            throw java.io.IOException("Saved YouTube Music session could not be restored. Sign in again.")
        }
    }

    @Synchronized override fun write(session: AccountSession) {
        val json = JSONObject().put("cookie", session.cookie).put("authUser", session.authUser)
            .put("channelId", session.channelId).put("delegatedSessionId", session.delegatedSessionId)
            .put("visitorData", session.visitorData).put("dataSyncId", session.dataSyncId)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val output = file.startWrite()
        try { output.write(cipher.iv + cipher.doFinal(json)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    @Synchronized override fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun JSONObject.optNullable(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
}
