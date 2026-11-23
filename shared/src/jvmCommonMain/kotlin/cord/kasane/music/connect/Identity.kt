package cord.kasane.music.connect

import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID

/**
 * A Kasane Connect identity: a stable device id/name plus a self-signed
 * ECDSA P-256 key pair used for mutual TLS. The key pair is pluggable so JVM
 * tests can load a PKCS12 fixture instead of AndroidKeyStore material.
 */
class ConnectIdentity(
    val deviceId: String,
    val deviceName: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    /** SHA-256 of the leaf certificate DER, used for trust pinning. */
    val fingerprint: ByteArray
        get() = Sas.sha256(certificate.encoded)

    val fingerprintHex: String
        get() = fingerprint.hex()
}

/** Persists the device id/name independently of the key material. */
interface DeviceProfileStore {
    fun load(): Pair<String, String>?
    fun save(deviceId: String, name: String)
}

/** In-memory store for JVM tests. */
class MemoryProfileStore(private var profile: Pair<String, String>? = null) : DeviceProfileStore {
    override fun load() = profile
    override fun save(deviceId: String, name: String) {
        profile = deviceId to name
    }
}

object ConnectIdentities {
    /**
     * Load or create an identity backed by [keyMaterial]: on Android this is a
     * KeyStore.PrivateKeyEntry for the `kasane_connect_identity` alias; in
     * tests any PrivateKey + X509Certificate pair works.
     */
    fun load(
        profile: DeviceProfileStore,
        defaultName: String,
        keyMaterial: () -> Pair<PrivateKey, X509Certificate>,
    ): ConnectIdentity {
        val (key, cert) = keyMaterial()
        val saved = profile.load()
        val deviceId = saved?.first ?: UUID.randomUUID().toString()
        val name = saved?.second ?: defaultName
        if (saved == null) profile.save(deviceId, name)
        return ConnectIdentity(deviceId, name, key, cert)
    }

    /** Load a `PKCS12` fixture, as used by the JVM loopback tests. */
    fun fromPkcs12(
        bytes: ByteArray,
        password: CharArray,
        deviceId: String,
        deviceName: String,
    ): ConnectIdentity {
        val store = KeyStore.getInstance("PKCS12")
        store.load(bytes.inputStream(), password)
        val alias = store.aliases().nextElement()
        val key = store.getKey(alias, password) as PrivateKey
        val cert = store.getCertificate(alias) as X509Certificate
        return ConnectIdentity(deviceId, deviceName, key, cert)
    }
}
