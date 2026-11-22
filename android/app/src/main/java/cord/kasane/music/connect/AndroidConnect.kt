package cord.kasane.music.connect

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

// v1 keys only allowed SHA-256 digests; Conscrypt signs the TLS handshake with
// NONEwithECDSA over a precomputed hash, so they could never complete TLS.
private const val KEY_ALIAS = "kasane_connect_identity_v2"
private const val PREFS = "kasane_connect_profile"

private class PrefsProfileStore(context: Context) : DeviceProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun load(): Pair<String, String>? {
        val id = prefs.getString("device_id", null) ?: return null
        val name = prefs.getString("device_name", null) ?: return null
        return id to name
    }
    override fun save(deviceId: String, name: String) {
        prefs.edit().putString("device_id", deviceId).putString("device_name", name).apply()
    }
}

object AndroidConnect {
    /** EC P-256 key in AndroidKeyStore; the keystore self-signs the cert. */
    private fun keyMaterial(): Pair<java.security.PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getEntry(KEY_ALIAS, null)
        if (existing is KeyStore.PrivateKeyEntry) {
            return existing.privateKey to (existing.certificate as X509Certificate)
        }
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Kasane Connect"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(now))
            .setCertificateNotAfter(Date(now + 20L * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
        val entry = store.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun createIdentity(context: Context): ConnectIdentity =
        ConnectIdentities.load(PrefsProfileStore(context), deviceName(context)) { keyMaterial() }

    /** Builds the manager, server and mDNS registration/discovery for this device. */
    fun createService(
        context: Context,
        playback: cord.kasane.music.playback.PlaybackManager,
        appVersion: String,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ConnectService {
        val identity = createIdentity(context)
        val adapter = AndroidPlayerAdapter(context.applicationContext, playback)
        val config = ConnectConfig(
            identity = identity,
            adapter = adapter,
            trustFile = java.io.File(context.noBackupFilesDir, "connect/trusted.json"),
            appVersion = appVersion,
            platform = "android",
            scope = scope,
        )
        val manager = ConnectManager(config)
        return ConnectService(context, manager, identity)
    }
}

/** Owns the ConnectManager, mDNS advertisement and discovery on Android. */
class ConnectService(
    context: Context,
    val manager: ConnectManager,
    identity: ConnectIdentity,
) {
    private val discovery = NsdDiscovery(context) { id, name, platform, host, port ->
        manager.onServiceDiscovered(id, name, platform, host, port)
    }.also { d ->
        d.lost = { id, host, port -> manager.onServiceLost(id, host, port) }
    }

    private val name = identity.deviceName
    private val txt = mapOf(
        "id" to identity.deviceId,
        "platform" to "android",
        "proto" to "1",
        "fp" to identity.fingerprintHex.take(16),
    )

    fun start() {
        manager.start()
        discovery.register(name, manager.port, txt)
        discovery.discover()
    }

    fun stop() {
        discovery.stopDiscovery()
        discovery.stopRegister()
        manager.stop()
    }
}
