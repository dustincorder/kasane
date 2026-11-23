package cord.kasane.desktop.connect

import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.music.connect.ConnectConfig
import cord.kasane.music.connect.ConnectIdentities
import cord.kasane.music.connect.ConnectIdentity
import cord.kasane.music.connect.ConnectManager
import cord.kasane.music.connect.DeviceProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.SecureRandom
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

private const val SERVICE_TYPE = "_kasane._tcp.local."

/** Device id/name next to the key, as Android keeps them in preferences. */
private class FileProfileStore(private val file: File) : DeviceProfileStore {
    @Serializable private data class Profile(val id: String, val name: String)
    override fun load() = runCatching { Json.decodeFromString<Profile>(file.readText()) }.getOrNull()?.let { it.id to it.name }
    override fun save(deviceId: String, name: String) = file.writeText(Json.encodeToString(Profile.serializer(), Profile(deviceId, name)))
}

/**
 * A self-signed ECDSA P-256 identity ([SelfSigned]) kept in a PKCS12 store.
 * The store password is random and kept beside it with owner-only
 * permissions; Android keeps its key in the hardware-backed Keystore instead.
 */
internal fun loadIdentity(dir: File, defaultName: String): ConnectIdentity {
    dir.mkdirs()
    val store = File(dir, "identity.p12")
    val passFile = File(dir, "identity.pass")
    if (!store.exists() || !passFile.exists()) {
        val password = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        passFile.writeText(password)
        runCatching { Files.setPosixFilePermissions(passFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        val (keys, cert) = SelfSigned.create("Kasane Connect")
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("kasane_connect_identity", keys.private, password.toCharArray(), arrayOf(cert))
        store.outputStream().use { ks.store(it, password.toCharArray()) }
        runCatching { Files.setPosixFilePermissions(store.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
    val password = passFile.readText().trim().toCharArray()
    val base = ConnectIdentities.fromPkcs12(store.readBytes(), password, "", "")
    return ConnectIdentities.load(FileProfileStore(File(dir, "device.json")), defaultName) { base.privateKey to base.certificate }
}

/** The LAN address mDNS should announce: the first up, non-loopback IPv4 that is not a VPN/tunnel. */
private fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("tailscale") && !it.name.startsWith("tun") && !it.name.startsWith("docker") && !it.name.startsWith("br-") && !it.name.startsWith("veth") }
    .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

class DesktopConnect(player: DesktopPlayer, private val scope: CoroutineScope, dir: File, appVersion: String) {
    private val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Linux PC"
    val identity: ConnectIdentity = loadIdentity(dir, hostName)
    val manager = ConnectManager(ConnectConfig(
        identity = identity, adapter = DesktopPlayerAdapter(player, scope), trustFile = File(dir, "trusted.json"),
        appVersion = appVersion, platform = "linux", scope = scope))
    val remote = DesktopRemote(manager, player, scope)
    private var mdns: JmDNS? = null
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        manager.start()
        running = true
        val address = lanAddress()
        Thread({
            runCatching {
                val jmdns = JmDNS.create(address, hostName)
                mdns = jmdns
                jmdns.registerService(ServiceInfo.create(SERVICE_TYPE, identity.deviceName, manager.port, 0, 0, mapOf(
                    "id" to identity.deviceId, "platform" to "linux", "proto" to "1", "fp" to identity.fingerprintHex.take(16))))
                jmdns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = jmdns.requestServiceInfo(event.type, event.name, 3_000)
                    override fun serviceRemoved(event: ServiceEvent) {
                        val info = event.info
                        manager.onServiceLost(info?.getPropertyString("id"), info?.inet4Addresses?.firstOrNull()?.hostAddress, info?.port ?: 0)
                    }
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                        manager.onServiceDiscovered(info.getPropertyString("id"), info.name, info.getPropertyString("platform"), host, info.port)
                    }
                })
            }.onFailure { System.err.println("Kasane Connect discovery unavailable: $it") }
        }, "connect-mdns").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        val jmdns = mdns
        mdns = null
        Thread({ runCatching { jmdns?.unregisterAllServices(); jmdns?.close() } }, "connect-mdns-stop").apply { isDaemon = true }.start()
        manager.stop()
    }
}
