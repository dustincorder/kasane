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

private val VIRTUAL_PREFIXES = listOf("zt", "tailscale", "tun", "tap", "wg", "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "lo")

/** The interface carrying the IPv4 default route (Linux /proc/net/route), i.e. the real LAN, not a VPN overlay. */
internal fun defaultRouteInterface(routes: String? = runCatching { java.io.File("/proc/net/route").readText() }.getOrNull()): String? =
    routes?.lines()?.drop(1)?.map { it.trim().split(Regex("\\s+")) }
        ?.filter { it.size > 7 && it[1] == "00000000" && it[7] == "00000000" }
        ?.minByOrNull { it.getOrNull(6)?.toIntOrNull() ?: Int.MAX_VALUE }?.get(0)

/**
 * The address to announce and browse on. Overlay networks (ZeroTier, Tailscale, WireGuard, containers) also
 * carry site-local IPv4 addresses; picking one of those made this computer invisible to the phone.
 */
internal fun lanAddress(): InetAddress? {
    val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    fun v4(nif: NetworkInterface) = nif.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    defaultRouteInterface()?.let { name -> all.firstOrNull { it.name == name }?.let(::v4)?.let { return it } }
    return all.filter { nif -> !nif.isVirtual && VIRTUAL_PREFIXES.none { nif.name.startsWith(it) } }.firstNotNullOfOrNull(::v4)
}

class DesktopConnect(player: DesktopPlayer, private val scope: CoroutineScope, dir: File, appVersion: String) {
    private val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Linux PC"
    val identity: ConnectIdentity = loadIdentity(dir, hostName)
    val manager = ConnectManager(ConnectConfig(
        identity = identity, adapter = DesktopPlayerAdapter(player, scope), trustFile = File(dir, "trusted.json"),
        appVersion = appVersion, platform = "linux", scope = scope))
    val remote = DesktopRemote(manager, player, scope)
    private var mdns: JmDNS? = null
    @Volatile private var responder: MdnsResponder? = null
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
                // Advertised by our own responder; JmDNS only browses (Android could not parse its announcements).
                (address as? java.net.Inet4Address)?.let { lan ->
                    responder = MdnsResponder(lan, instance = "${identity.deviceName} ${identity.deviceId.take(8)}",
                        hostLabel = "kasane-${identity.deviceId.take(8)}", port = manager.port, txt = mapOf(
                            "id" to identity.deviceId, "name" to identity.deviceName, "platform" to "linux",
                            "proto" to "1", "fp" to identity.fingerprintHex.take(16)))
                }
                // JmDNS can report a removal without the TXT data; idByService remembers which device each name was.
                jmdns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = jmdns.requestServiceInfo(event.type, event.name, 3_000)
                    override fun serviceRemoved(event: ServiceEvent) {
                        val (id, addr) = idByService.remove(event.name) ?: return
                        manager.onServiceLost(id, addr?.substringBefore(':'), addr?.substringAfter(':')?.toIntOrNull() ?: 0)
                    }
                    override fun serviceResolved(event: ServiceEvent) = report(event.info)
                })
                // JmDNS raises no new event when a known service only changes address (a phone rejoining Wi-Fi with
                // a new IP), so look the services up again periodically; the manager updates the stored address.
                while (running && mdns === jmdns) {
                    Thread.sleep(15_000)
                    runCatching { jmdns.list(SERVICE_TYPE, 3_000).forEach(::report) }
                }
            }.onFailure { System.err.println("Kasane Connect discovery unavailable: $it") }
        }, "connect-mdns").apply { isDaemon = true }.start()
    }

    private val idByService = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()

    private fun report(info: ServiceInfo) {
        val id = info.getPropertyString("id") ?: return
        // One phone can answer on several interfaces; use its LAN address, not a link-local or tunnel one.
        val host = (info.inet4Addresses.firstOrNull { it.isSiteLocalAddress } ?: info.inet4Addresses.firstOrNull())?.hostAddress ?: return
        idByService[info.name] = id to "$host:${info.port}"
        val name = info.getPropertyString("name")?.takeIf { it.isNotBlank() } ?: info.name
        manager.onServiceDiscovered(id, name, info.getPropertyString("platform"), host, info.port)
    }

    fun stop() {
        if (!running) return
        running = false
        val jmdns = mdns
        mdns = null
        responder?.close()
        responder = null
        Thread({ runCatching { jmdns?.unregisterAllServices(); jmdns?.close() } }, "connect-mdns-stop").apply { isDaemon = true }.start()
        manager.stop()
    }
}
