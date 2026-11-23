package cord.kasane.desktop.connect

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Minimal mDNS/DNS-SD responder (RFC 6762/6763) for one `_kasane._tcp` service on one IPv4 interface.
 * It answers PTR/SRV/TXT/A questions, announces on start and sends goodbyes on stop. Plain, uncompressed
 * records only, which every resolver parses (Android's rejected the packets JmDNS sent).
 */
class MdnsResponder(
    private val address: Inet4Address,
    instance: String,
    hostLabel: String,
    private val port: Int,
    private val txt: Map<String, String>,
) : AutoCloseable {
    private val service = "_kasane._tcp.local"
    private val instanceName = "$instance.$service"
    private val hostName = "$hostLabel.local"
    private val group = InetAddress.getByName("224.0.0.251")
    private val iface = NetworkInterface.getByInetAddress(address)
    /** An explicit IPv4 channel: a dual-stack socket silently failed to send to the IPv4 group. */
    private val channel: DatagramChannel = DatagramChannel.open(StandardProtocolFamily.INET).apply {
        setOption(StandardSocketOptions.SO_REUSEADDR, true)
        bind(InetSocketAddress(5353))
        setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
        setOption(StandardSocketOptions.IP_MULTICAST_TTL, 255)
        join(group, iface)
        configureBlocking(true)
    }
    @Volatile private var running = true

    init {
        if (DEBUG) System.err.println("mdns: responder for $instanceName on $address via ${iface?.name}")
        thread(name = "mdns-responder", isDaemon = true) { loop() }
        thread(name = "mdns-announce", isDaemon = true) {
            // RFC 6762 §8.3: at least two announcements, one second apart.
            repeat(3) { i -> if (running) send(response(ttlScale = 1)); Thread.sleep(1000L shl i) }
        }
    }

    private fun loop() {
        val buf = java.nio.ByteBuffer.allocate(9000)
        while (running) {
            buf.clear()
            try { channel.receive(buf) } catch (_: Exception) { if (!running) break else continue }
            buf.flip()
            val data = ByteArray(buf.remaining()).also { buf.get(it) }
            val questions = runCatching { questions(data, data.size) }.getOrNull() ?: continue
            val wanted = questions.any { (name, type) ->
                val n = name.lowercase()
                (n == service && (type == PTR || type == ANY)) ||
                    (n == instanceName.lowercase() && (type == SRV || type == TXT || type == ANY)) ||
                    (n == hostName.lowercase() && (type == A || type == ANY))
            }
            if (DEBUG) System.err.println("mdns: query ${questions} wanted=$wanted")
            if (wanted) send(response(ttlScale = 1)).onFailure { if (DEBUG) System.err.println("mdns: send failed $it") }
        }
    }

    private fun send(bytes: ByteArray) = runCatching { channel.send(java.nio.ByteBuffer.wrap(bytes), InetSocketAddress(group, 5353)) }

    /** Response with PTR, SRV, TXT and A in the answer section; ttlScale 0 is a goodbye. */
    private fun response(ttlScale: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(0); out.u16(0x8400); out.u16(0); out.u16(4); out.u16(0); out.u16(0)
        record(out, service, PTR, 4500 * ttlScale, cacheFlush = false) { name(it, instanceName) }
        record(out, instanceName, SRV, 120 * ttlScale) { it.u16(0); it.u16(0); it.u16(port); name(it, hostName) }
        record(out, instanceName, TXT, 4500 * ttlScale) { o ->
            txt.forEach { (k, v) -> val e = "$k=$v".toByteArray(Charsets.UTF_8); o.write(e.size); o.write(e) }
        }
        record(out, hostName, A, 120 * ttlScale) { it.write(address.address) }
        return out.toByteArray()
    }

    private fun record(out: ByteArrayOutputStream, owner: String, type: Int, ttl: Int, cacheFlush: Boolean = true, data: (ByteArrayOutputStream) -> Unit) {
        name(out, owner)
        out.u16(type)
        out.u16(if (cacheFlush) 0x8001 else 0x0001)
        out.u16(ttl ushr 16); out.u16(ttl and 0xFFFF)
        val rdata = ByteArrayOutputStream().also(data).toByteArray()
        out.u16(rdata.size); out.write(rdata)
    }

    private fun name(out: ByteArrayOutputStream, name: String) {
        for (label in splitLabels(name)) { val b = label.toByteArray(Charsets.UTF_8); out.write(b.size); out.write(b) }
        out.write(0)
    }

    /** "My Phone._kasane._tcp.local": the instance label may contain dots-free spaces; split on the service suffix. */
    private fun splitLabels(name: String): List<String> =
        if (name.endsWith(".$service") && name != service) listOf(name.removeSuffix(".$service")) + service.split('.') else name.split('.')

    private fun ByteArrayOutputStream.u16(v: Int) { write(v ushr 8 and 0xFF); write(v and 0xFF) }

    override fun close() {
        if (!running) return
        send(response(ttlScale = 0))
        running = false
        runCatching { channel.close() }
    }

    companion object {
        private val DEBUG = System.getenv("KASANE_MDNS_DEBUG") == "1"
        const val A = 1; const val PTR = 12; const val TXT = 16; const val SRV = 33; const val ANY = 255

        /** Questions of a query (flags QR=0), names decompressed. Responses are ignored. */
        fun questions(data: ByteArray, length: Int): List<Pair<String, Int>> {
            if (length < 12 || data[2].toInt() and 0x80 != 0) return emptyList()
            val count = (data[4].toInt() and 0xFF shl 8) or (data[5].toInt() and 0xFF)
            var pos = 12
            return List(count) {
                val (name, next) = readName(data, length, pos)
                if (next + 4 > length) throw IllegalArgumentException("truncated")
                val type = (data[next].toInt() and 0xFF shl 8) or (data[next + 1].toInt() and 0xFF)
                pos = next + 4
                name to type
            }
        }

        private fun readName(data: ByteArray, length: Int, start: Int): Pair<String, Int> {
            val labels = mutableListOf<String>()
            var pos = start; var end = -1; var jumps = 0
            while (true) {
                if (pos >= length) throw IllegalArgumentException("truncated")
                val len = data[pos].toInt() and 0xFF
                when {
                    len == 0 -> { pos++; break }
                    len and 0xC0 == 0xC0 -> {
                        if (++jumps > 16 || pos + 1 >= length) throw IllegalArgumentException("bad pointer")
                        if (end < 0) end = pos + 2
                        pos = (len and 0x3F shl 8) or (data[pos + 1].toInt() and 0xFF)
                    }
                    else -> { labels += String(data, pos + 1, len, Charsets.UTF_8); pos += 1 + len }
                }
            }
            return labels.joinToString(".") to (if (end >= 0) end else pos)
        }
    }
}
