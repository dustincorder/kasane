package cord.kasane.music.connect

import java.net.InetSocketAddress
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAdapter : ConnectPlayerAdapter {
    val executed = java.util.concurrent.CopyOnWriteArrayList<String>()
    var state = PlayerSnapshot(
        playback = "paused",
        track = TrackMeta("t1", "Song One", "Artist"),
        queue = listOf(TrackMeta("t1", "Song One", "Artist"), TrackMeta("t2", "Song Two", "Artist")),
        queueIndex = 0,
        durationMs = 200_000,
        volume = 0.7f,
    )
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed
    override suspend fun snapshot() = state
    override suspend fun execute(action: String, args: JsonObject) {
        executed += action
        state = when (action) {
            "play" -> state.copy(playback = "playing")
            "pause" -> state.copy(playback = "paused")
            "playQueueIndex" -> state.copy(playback = "playing")
            else -> state
        }
        _changed.value = counter.incrementAndGet()
    }
    override suspend fun handoff(request: Frame.Handoff) {
        state = state.copy(
            queue = request.queue,
            queueIndex = request.index.toLong(),
            playback = if (request.playing) "playing" else "paused",
        )
        _changed.value = counter.incrementAndGet()
    }
    override fun capabilities() = listOf("seek", "queue", "handoff")
}

/** Minimal wire-level controller used to exercise the server directly. */
private class RawClient(
    private val identity: ConnectIdentity,
    private val scope: CoroutineScope,
) {
    private val tls = ConnectTls(identity)
    private lateinit var socket: SSLSocket
    lateinit var link: Link
    val seen = java.util.concurrent.CopyOnWriteArrayList<Frame>()
    val identityFp: ByteArray get() = identity.fingerprint

    fun connect(port: Int) {
        socket = tls.clientSocket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
        socket.startHandshake()
        link = Link(socket, scope,
            onFrame = { seen.add(it) },
            onUnknownType = {},
            onClose = { },
            onError = { it.printStackTrace() })
        link.start()
        link.send(hello())
    }

    private fun hello() = Frame.Hello(1, 1, DeviceInfo(
        id = identity.deviceId, name = identity.deviceName,
        platform = "android", app = "kasane", appVersion = "test"))

    suspend fun <T> until(pick: (Frame) -> T?): T {
        try {
            return withTimeout(15_000) {
                while (true) {
                    seen.firstNotNullOfOrNull(pick)?.let { return@withTimeout it }
                    kotlinx.coroutines.delay(20)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("timed out waiting for frame; received: $seen")
        }
    }

    fun close() = link.close()
}

class ConnectLoopbackTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var server: ConnectManager
    private lateinit var adapter: FakeAdapter
    private lateinit var serverDir: java.io.File
    private lateinit var clientDir: java.io.File

    private fun fixture(name: String): ConnectIdentity =
        ConnectIdentities.fromPkcs12(
            requireNotNull(javaClass.getResource("/connect/$name")).readBytes(),
            "kasane".toCharArray(),
            UUID.randomUUID().toString(),
            name,
        )

    private fun manager(identity: ConnectIdentity, adapter: ConnectPlayerAdapter, dir: java.io.File) =
        ConnectManager(
            ConnectConfig(
                identity = identity,
                adapter = adapter,
                trustFile = java.io.File(dir, "trusted.json"),
                appVersion = "test",
                platform = "android",
                scope = scope,
                listenPort = 0,
            )
        )

    @Before
    fun setUp() {
        adapter = FakeAdapter()
        serverDir = java.nio.file.Files.createTempDirectory("connect-server").toFile()
        clientDir = java.nio.file.Files.createTempDirectory("connect-client").toFile()
        server = manager(fixture("test-identity.p12"), adapter, serverDir)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        job.cancel()
        serverDir.deleteRecursively()
        clientDir.deleteRecursively()
    }

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            delay(25)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    /** Drives the whole pairing exchange on the wire, confirming on the server prompt. */
    private suspend fun pairAsServer(raw: RawClient) {
        val nc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        raw.link.send(Frame.PairRequest(Sas.commit(nc).hex()))
        val ns = raw.until { (it as? Frame.PairNonce)?.ns }!!
        raw.link.send(Frame.PairReveal(nc.hex()))
        val prompt = withTimeout(15_000) {
            while (server.prompts.value.isEmpty()) delay(25)
            server.prompts.value.first()
        }
        // The server prompt carries the SAS both sides computed.
        val expectCode = Sas.code(
            serverIdentityFp(), raw.identityFp, ns.unhex()!!, nc)
        assertEquals(expectCode, prompt.code)
        server.confirmPairing(prompt.promptId, true)
        raw.until { (it as? Frame.PairConfirm)?.accepted }
        raw.link.send(Frame.PairConfirm(true))
        val result = raw.until { (it as? Frame.PairResult)?.accepted }
        assertTrue(result == true)
        raw.until { it is Frame.AuthOk }
    }

    private fun serverIdentityFp() = server.fingerprintHex.unhex()!!

    @Test
    fun pairingSuccessThenAuthorizedCommand() = runBlocking {
        val raw = RawClient(fixture("test-identity-2.p12"), scope)
        raw.connect(server.port)
        // The server greets us immediately.
        raw.until { (it as? Frame.Hello)?.device?.id }
        val verdict = raw.until { f -> if (f is Frame.AuthOk || f is Frame.AuthRequired) f else null }
        assertTrue(verdict is Frame.AuthRequired)

        pairAsServer(raw)

        raw.link.send(Frame.Command("a", 1, "play"))
        val ack = raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "a" } }
        assertTrue(ack!!.ok)
        assertTrue(adapter.executed.contains("play"))
        raw.close()
    }

    @Test
    fun unauthorizedCommandIsRejectedAndNotExecuted() = runBlocking {
        val raw = RawClient(fixture("test-identity-2.p12"), scope)
        raw.connect(server.port)
        raw.until { (it as? Frame.Hello)?.device?.id }
        raw.until { f -> if (f is Frame.AuthOk || f is Frame.AuthRequired) f else null }

        raw.link.send(Frame.Command("u", 1, "play"))
        val ack = raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "u" } }
        assertFalse(ack!!.ok)
        assertEquals("unauthorized", ack.error?.code)
        assertFalse(adapter.executed.contains("play"))
        raw.close()
    }

    @Test
    fun replayedSeqAndDuplicateIdAreNotExecuted() = runBlocking {
        val raw = RawClient(fixture("test-identity-2.p12"), scope)
        raw.connect(server.port)
        raw.until { (it as? Frame.Hello)?.device?.id }
        raw.until { f -> if (f is Frame.AuthOk || f is Frame.AuthRequired) f else null }
        pairAsServer(raw)

        raw.link.send(Frame.Command("x", 1, "play"))
        assertTrue(raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "x" } }!!.ok)

        raw.link.send(Frame.Command("y", 1, "pause"))
        val replayed = raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "y" } }
        assertEquals("replayed", replayed!!.error?.code)

        // Same command id under a new seq is acked with the original result,
        // not executed a second time.
        raw.link.send(Frame.Command("x", 2, "pause"))
        val dup = raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "x" && a.ok } }
        assertTrue(dup!!.ok)
        assertEquals(listOf("play"), adapter.executed.toList())
        raw.close()
    }

    @Test
    fun staleQueueRevIsRejected() = runBlocking {
        val raw = RawClient(fixture("test-identity-2.p12"), scope)
        raw.connect(server.port)
        raw.until { (it as? Frame.Hello)?.device?.id }
        raw.until { f -> if (f is Frame.AuthOk || f is Frame.AuthRequired) f else null }
        pairAsServer(raw)

        val args = buildJsonObject {
            put("index", 0)
            put("queueRev", 99_999)
        }
        raw.link.send(Frame.Command("s", 1, "playQueueIndex", args))
        val ack = raw.until { (it as? Frame.Ack)?.takeIf { a -> a.id == "s" } }
        assertEquals("stale_queue", ack!!.error?.code)
        raw.close()
    }

    @Test
    fun managerPairingAndCommand() = runBlocking {
        val clientAdapter = FakeAdapter()
        val client = manager(fixture("test-identity-2.p12"), clientAdapter, clientDir)
        client.start()
        try {
            val promptId = client.pair("127.0.0.1:${server.port}")
            assertNotNull(promptId)
            awaitTrue { server.prompts.value.isNotEmpty() }
            // The initiator only displays the code, the same one the receiver shows; one confirmation, on the receiver.
            val shown = client.prompts.value.single()
            assertTrue(shown.awaitingPeer && !shown.incoming)
            assertEquals(server.prompts.value.single().code, shown.code)
            assertEquals(6, shown.code.length)
            server.confirmPairing(server.prompts.value.first().promptId, true)
            awaitTrue {
                client.devices.value.any { it.connection == "paired" }
            }
            awaitTrue { client.prompts.value.isEmpty() && server.prompts.value.isEmpty() }
            assertTrue(client.devices.value.single { it.trusted }.state != DeviceState.OFFLINE)
            val deviceId = client.devices.value.first { it.connection == "paired" }.id!!
            client.sendCommand(deviceId, "pause")
            awaitTrue { adapter.executed.contains("pause") }
            // The controller must keep receiving state after its initial frame.
            awaitTrue { client.remoteStates.value[deviceId]?.playback == "paused" }
            client.sendCommand(deviceId, "play")
            awaitTrue { client.remoteStates.value[deviceId]?.playback == "playing" }
            // Incremental frames omit an unchanged queue; the controller keeps the last one.
            assertEquals(listOf("t1", "t2"), client.remoteStates.value[deviceId]?.queue?.map { it.id })
        } finally {
            client.stop()
        }
    }

    @Test
    fun receiverRejectingLeavesNoTrustOnEitherSide() = runBlocking {
        val client = manager(fixture("test-identity-2.p12"), FakeAdapter(), clientDir)
        client.start()
        try {
            client.pair("127.0.0.1:${server.port}")
            awaitTrue { server.prompts.value.isNotEmpty() }
            server.confirmPairing(server.prompts.value.first().promptId, false)
            awaitTrue { client.prompts.value.isEmpty() }
            awaitTrue { client.devices.value.none { it.trusted } && server.devices.value.none { it.trusted } }
            awaitTrue { client.devices.value.any { it.lastError?.contains("declined") == true } }
        } finally { client.stop() }
    }

    @Test
    fun discoveryNeverListsThisDeviceOrItsOwnMachine() {
        val me = server
        val ownId = fixtureIdOf(me)
        me.onServiceDiscovered(ownId, "Me", "android", "10.9.9.9", 47650)       // own stable id
        me.onServiceDiscovered("other-app", "Same machine", "linux", "127.0.0.1", 47651) // another app here
        me.onServiceDiscovered(null, "Half resolved", null, "10.9.9.8", 47650)  // TXT not arrived yet
        assertTrue("got ${me.devices.value}", me.devices.value.isEmpty())
    }

    @Test
    fun oneDeviceIsOneEntryWhateverTheRouteOrRecordOrder() {
        val me = server
        // Same phone announced twice (two interfaces), with a platform that must not flip to a desktop.
        me.onServiceDiscovered("phone-1", "Pixel 6 Pro", "android", "10.9.9.20", 47650)
        me.onServiceDiscovered("phone-1", "Pixel 6 Pro", "android", "10.9.9.21", 47650)
        assertEquals(1, me.devices.value.size)
        assertEquals("android", me.devices.value.single().platform)
        assertEquals(DeviceState.AVAILABLE, me.devices.value.single().state)
        // Gone from the network: an unpaired device disappears, no stale record.
        me.onServiceLost(null, "10.9.9.21", 47650)
        assertTrue("got ${me.devices.value}", me.devices.value.isEmpty())
    }

    private fun fixtureIdOf(m: ConnectManager) = m.localDeviceId
}
