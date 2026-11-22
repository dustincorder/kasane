package cord.kasane.music.connect

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val DEFAULT_PORT = 47650
private const val PROTOCOL = 1
private const val PAIRING_TIMEOUT_MS = 120_000L
private const val COMMAND_TIMEOUT_MS = 10_000L
private const val HANDOFF_TIMEOUT_MS = 30_000L
private const val DEDUP_CAP = 256
private const val MAX_PAIR_FAILURES = 3
private const val PAIR_FAILURE_WINDOW_MS = 600_000L
private const val PAIR_COOLDOWN_MS = 300_000L
private const val AUTH_IDLE_MS = 20_000L
private const val MAX_UNAUTH_PER_IP = 2
private const val MAX_UNAUTH_TOTAL = 4
private const val RESOLVE_FRESH_MS = 120_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

data class ConnectDeviceView(
    val id: String?,
    val name: String,
    val platform: String?,
    val addr: String?,
    val online: Boolean,
    val trusted: Boolean,
    /** disconnected | connecting | connected | paired */
    val connection: String,
    val lastError: String? = null,
)

data class ConnectPairingPrompt(
    val promptId: String,
    val incoming: Boolean,
    val peerName: String,
    val code: String,
    val deviceId: String?,
)

data class ConnectConfig(
    val identity: ConnectIdentity,
    val adapter: ConnectPlayerAdapter,
    val trustFile: java.io.File,
    val appVersion: String,
    val platform: String = "android",
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

/** Callback for discovered services; ConnectManager owns what happens next. */
fun interface DiscoveredListener {
    fun onService(id: String?, name: String, platform: String?, host: String, port: Int)
}

/**
 * Kasane Connect facade: server (player), client (controller), trust,
 * discovery, pairing prompts, remote state and handoff. Mirrors the Rust
 * ConnectService.
 */
class ConnectManager(private val config: ConnectConfig) {

    // ---- device book ----------------------------------------------------
    private data class DeviceEntry(
        var id: String?,
        var name: String,
        var platform: String?,
        var addr: String?,
        var lastResolve: Long = 0,
        var conn: String = "disconnected",
        var lastError: String? = null,
    )

    inner class Session(
        val link: Link,
        val clientMode: Boolean,
        val socket: SSLSocket,
    ) {
        var peer: DeviceInfo? = null
        var peerFp: String = ""
        @Volatile var authed = false
        @Volatile var subscribed = false
        var lastQueueRevSent = -1L
        var lastSeq = 0L
        var closed = false
        val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, FrameError?>>>()
        val remoteState = MutableStateFlow<Frame.State?>(null)
        var lastRev = 0L
        val pairEvents = kotlinx.coroutines.flow.MutableSharedFlow<Frame>(extraBufferCapacity = 16)
        val pairLock = Mutex()
        var dead = CompletableDeferred<Unit>()
    }

    private val scope = config.scope
    private val identity = config.identity
    private val tls = ConnectTls(identity)
    private val trust = TrustStore(config.trustFile)
    private val adapter = config.adapter

    private val entries = LinkedHashMap<String, DeviceEntry>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val wanted = mutableSetOf<String>()
    private val dedup = ConcurrentHashMap<String, ArrayDeque<Triple<String, Boolean, FrameError?>>>()
    private val pairFailures = ArrayDeque<Long>()

    val localDeviceName: String get() = identity.deviceName

    private val _devices = MutableStateFlow<List<ConnectDeviceView>>(emptyList())
    val devices: StateFlow<List<ConnectDeviceView>> = _devices.asStateFlow()

    private val _prompts = MutableStateFlow<List<ConnectPairingPrompt>>(emptyList())
    val prompts: StateFlow<List<ConnectPairingPrompt>> = _prompts.asStateFlow()

    private val _remoteStates = MutableStateFlow<Map<String, Frame.State>>(emptyMap())
    val remoteStates: StateFlow<Map<String, Frame.State>> = _remoteStates.asStateFlow()

    private val unauthByIp = ConcurrentHashMap<String, Int>()
    @Volatile private var unauthTotal = 0

    var port: Int = DEFAULT_PORT
        private set
    private var serverSocket: javax.net.ssl.SSLServerSocket? = null
    private var running = false

    // server-side pairing: at most one pending exchange
    private val pairSlot = Mutex()
    private class ServerPairing(
        val session: Session,
        var ns: ByteArray = ByteArray(0),
        var commit: String = "",
        var promptId: String = "",
        var peerAccepted: Boolean? = null,
        var localAccepted: CompletableDeferred<Boolean>? = null,
        val deadline: Long = System.currentTimeMillis() + PAIRING_TIMEOUT_MS,
    )
    private var serverPairing: ServerPairing? = null

    val deviceInfo = DeviceInfo(
        id = identity.deviceId,
        name = identity.deviceName,
        platform = config.platform,
        app = "kasane",
        appVersion = config.appVersion,
    )

    val fingerprintHex: String get() = identity.fingerprintHex

    // ---- lifecycle --------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        val listener = try {
            tls.serverSocket(DEFAULT_PORT)
        } catch (e: Exception) {
            try { tls.serverSocket(0) } catch (e2: Exception) { return }
        }
        serverSocket = listener
        port = listener.localPort
        scope.launch(Dispatchers.IO) { acceptLoop(listener) }
        scope.launch { publisher() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        sessions.values.forEach { it.link.close() }
        sessions.clear()
    }

    // ---- inbound sessions -------------------------------------------------

    private suspend fun acceptLoop(listener: javax.net.ssl.SSLServerSocket) {
        while (running) {
            val socket = try {
                listener.accept() as SSLSocket
            } catch (e: Exception) {
                if (running) delay(100) else break
                continue
            }
            val ip = socket.inetAddress?.hostAddress ?: "?"
            // Admission control: cap unauthenticated peers so they cannot
            // starve paired devices.
            val perIp = unauthByIp.getOrDefault(ip, 0)
            if (perIp >= MAX_UNAUTH_PER_IP || unauthTotal >= MAX_UNAUTH_TOTAL) {
                runCatching { socket.close() }
                continue
            }
            unauthByIp[ip] = perIp + 1
            unauthTotal++
            scope.launch { handleInbound(socket, ip) }
        }
    }

    private suspend fun handleInbound(socket: SSLSocket, ip: String) {
        lateinit var session: Session
        val link = Link(
            socket, scope,
            onFrame = { frame -> onFrame(session, frame, server = true) },
            onUnknownType = { t -> session.link.send(Frame.Error("unknown_type", t)) },
            onClose = { onSessionClosed(session) },
            onError = { logWarn("inbound session error", it) },
        )
        session = Session(link, clientMode = false, socket)
        try {
            socket.startHandshake()
        } catch (e: Exception) {
            releaseUnauth(ip)
            runCatching { socket.close() }
            return
        }
        session.peerFp = tls.peerFingerprint(socket) ?: ""
        // Both sides announce themselves immediately (protocol §Transport).
        // Queue our hello before the reader starts: the client's hello may
        // already be buffered, and authOk must never overtake our hello.
        link.send(Frame.Hello(PROTOCOL, PROTOCOL, deviceInfo))
        link.start()
        // Unauthenticated idle cutoff.
        scope.launch {
            delay(AUTH_IDLE_MS)
            if (!session.authed) link.close()
        }
    }

    private fun releaseUnauth(ip: String) {
        unauthByIp.computeIfPresent(ip) { _, n -> if (n <= 1) null else n - 1 }
        unauthTotal--
    }

    private fun onSessionClosed(session: Session) {
        if (!session.closed) {
            session.closed = true
            session.dead.complete(Unit)
        }
        session.pending.values.forEach { it.complete(false to FrameError("failed", "connection closed")) }
        session.pending.clear()
        session.peer?.let { peer ->
            val wasAuthed = session.authed
            if (sessions.remove(peer.id, session)) refreshDevices()
            if (!wasAuthed) releaseUnauth(session.socket.inetAddress?.hostAddress ?: "?")
        } ?: releaseUnauth(session.socket.inetAddress?.hostAddress ?: "?")
    }

    // ---- frame dispatch ----------------------------------------------------

    private fun onFrame(session: Session, frame: Frame, server: Boolean) {
        when (frame) {
            is Frame.Hello -> onHello(session, frame)
            is Frame.Ping -> session.link.send(Frame.Pong)
            is Frame.Pong -> {}
            is Frame.AuthOk -> {
                session.authed = true
                session.peer?.let { markConnected(it, "paired") }
                scope.launch { session.pairEvents.emit(frame) }
            }
            is Frame.AuthRequired -> scope.launch { session.pairEvents.emit(frame) }
            is Frame.Error -> scope.launch { session.pairEvents.emit(frame) }
            is Frame.Unpair -> {
                session.peer?.let {
                    trust.remove(it.id)
                    entries.remove(it.id)
                    refreshDevices()
                }
            }
            is Frame.PairRequest -> if (server) onPairRequest(session, frame)
            is Frame.PairNonce -> scope.launch { session.pairEvents.emit(frame) }
            is Frame.PairReveal -> if (server) onPairReveal(session, frame)
            is Frame.PairConfirm -> if (server) onPairConfirm(session, frame)
            is Frame.PairResult -> scope.launch { session.pairEvents.emit(frame) }
            is Frame.Subscribe -> {
                if (!requireAuth(session)) return
                session.subscribed = true
                pushState(session, forceQueue = true)
            }
            is Frame.Sync -> {
                if (!requireAuth(session)) return
                pushState(session, forceQueue = true)
            }
            is Frame.State -> {
                if (!session.clientMode) return
                if (frame.rev <= session.lastRev) return
                session.lastRev = frame.rev
                session.remoteState.value = frame
                session.peer?.let { peer ->
                    _remoteStates.value = _remoteStates.value + (peer.id to frame)
                }
            }
            is Frame.Command -> {
                if (!server) return
                if (!requireAuth(session, commandId = frame.id)) return
                scope.launch { handleCommand(session, frame) }
            }
            is Frame.Ack -> {
                session.pending.remove(frame.id)?.complete(frame.ok to frame.error)
            }
            is Frame.Handoff -> {
                if (!server) return
                if (!requireAuth(session)) {
                    session.link.send(Frame.HandoffResult(frame.transferId, false, FrameError("unauthorized")))
                    return
                }
                scope.launch { handleHandoff(session, frame) }
            }
            is Frame.HandoffResult -> {
                session.pending.remove("handoff:${frame.transferId}")
                    ?.complete(frame.ok to frame.error)
            }
        }
    }

    private fun onHello(session: Session, hello: Frame.Hello) {
        if (hello.protocol < 1 || hello.minProtocol > PROTOCOL) {
            session.link.send(Frame.Error("incompatible_version"))
            session.link.close()
            return
        }
        val previous = session.peer?.id
        session.peer = hello.device
        if (session.clientMode) {
            val expected = wantedSessionFor(session)
            if (expected != null && expected != hello.device.id) {
                session.link.close()
                return
            }
            sessions[hello.device.id] = session
        }
        if (!session.clientMode) {
            if (trust.isTrusted(hello.device.id, session.peerFp)) {
                session.authed = true
                releaseUnauth(ipOf(session))
                session.link.send(Frame.AuthOk)
            } else {
                session.link.send(Frame.AuthRequired)
            }
        }
        upsertEntry(hello.device.id) {
            name = hello.device.name
            platform = hello.device.platform
            if (!session.clientMode) conn = if (session.authed) "paired" else "connected"
            addr = remoteAddr(session)
        }
        // Persist the endpoint for trusted peers so manual reconnect works.
        if (session.authed) {
            trust.get(hello.device.id)?.let { d ->
                val ep = remoteAddr(session)
                if (ep != null && ep !in d.endpoints) {
                    trust.put(d.copy(endpoints = (d.endpoints + ep).takeLast(4)))
                }
            }
        }
        refreshDevices()
    }

    private fun ipOf(session: Session): String =
        session.socket.inetAddress?.hostAddress ?: "?"

    private fun remoteAddr(session: Session): String? =
        session.socket.inetAddress?.hostAddress?.let { "$it:${session.socket.port}" }

    private fun requireAuth(session: Session, commandId: String? = null): Boolean {
        if (session.authed) return true
        if (commandId != null) {
            session.link.send(Frame.Ack(commandId, false, FrameError("unauthorized")))
        } else {
            session.link.send(Frame.Error("unauthorized"))
        }
        return false
    }

    // ---- pairing (server side) --------------------------------------------

    private fun onPairRequest(session: Session, request: Frame.PairRequest) {
        if (session.authed) return
        val now = System.currentTimeMillis()
        synchronized(pairFailures) {
            while (pairFailures.isNotEmpty() && now - pairFailures.first() > PAIR_FAILURE_WINDOW_MS) {
                pairFailures.removeFirst()
            }
            if (pairFailures.size >= MAX_PAIR_FAILURES) {
                session.link.send(Frame.PairResult(false, "rate_limited"))
                notePairFailure()
                return
            }
        }
        if (serverPairing != null && serverPairing?.session !== session) {
            session.link.send(Frame.PairResult(false, "busy"))
            return
        }
        if (!pairSlot.tryLock()) {
            session.link.send(Frame.PairResult(false, "busy"))
            return
        }
        val ns = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        serverPairing = ServerPairing(session, ns = ns, commit = request.commit)
        session.link.send(Frame.PairNonce(ns.hex()))
    }

    private fun onPairReveal(session: Session, reveal: Frame.PairReveal) {
        val pairing = serverPairing ?: return
        if (pairing.session !== session) return
        val nc = reveal.nc.unhex()
        if (nc == null || nc.size != 32 || Sas.commit(nc).hex() != pairing.commit) {
            failServerPairing("commit_mismatch")
            return
        }
        val code = Sas.code(identity.fingerprint, session.peerFp.unhex() ?: return, pairing.ns, nc)
        val promptId = UUID.randomUUID().toString()
        pairing.promptId = promptId
        val reply = CompletableDeferred<Boolean>()
        pairing.localAccepted = reply
        addPrompt(ConnectPairingPrompt(promptId, incoming = true,
            peerName = session.peer?.name ?: "device", code = code, deviceId = session.peer?.id))
        scope.launch {
            val left = pairing.deadline - System.currentTimeMillis()
            val local = withTimeoutOrNull(left.coerceAtLeast(1)) { reply.await() } ?: false
            removePrompt(promptId)
            if (!local) {
                failServerPairing("rejected")
                return@launch
            }
            pairing.localAccepted = CompletableDeferred<Boolean>().also { it.complete(true) }
            session.link.send(Frame.PairConfirm(true))
            // Wait for the peer's confirmation within the remaining window.
            val wait = pairing.deadline - System.currentTimeMillis()
            val peer = withTimeoutOrNull(wait.coerceAtLeast(1)) {
                while (pairing.peerAccepted == null) delay(50)
                pairing.peerAccepted!!
            } ?: false
            if (peer) {
                session.peer?.let { dev ->
                    trust.put(TrustedDevice(dev.id, dev.name, dev.platform,
                        session.peerFp, System.currentTimeMillis() / 1000))
                }
                session.authed = true
                releaseUnauth(session.socket.inetAddress?.hostAddress ?: "?")
                session.link.send(Frame.PairResult(true))
                session.link.send(Frame.AuthOk)
                upsertEntry(session.peer?.id) { conn = "paired" }
                refreshDevices()
            } else {
                session.link.send(Frame.PairResult(false, "rejected"))
                notePairFailure()
            }
            serverPairing = null
            pairSlot.unlock()
        }
    }

    private fun onPairConfirm(session: Session, confirm: Frame.PairConfirm) {
        val pairing = serverPairing ?: return
        if (pairing.session !== session) return
        pairing.peerAccepted = confirm.accepted
    }

    private fun failServerPairing(reason: String) {
        val pairing = serverPairing ?: return
        pairing.session.link.send(Frame.PairResult(false, reason))
        serverPairing = null
        pairSlot.unlock()
        notePairFailure()
    }

    private fun notePairFailure() {
        synchronized(pairFailures) { pairFailures.addLast(System.currentTimeMillis()) }
    }

    // ---- commands & state (server side) -------------------------------------

    private suspend fun handleCommand(session: Session, command: Frame.Command) {
        val peerId = session.peer?.id ?: "?"
        if (command.seq <= session.lastSeq) {
            session.link.send(Frame.Ack(command.id, false, FrameError("replayed")))
            return
        }
        val queue = dedup.getOrPut(peerId) { ArrayDeque() }
        val seen = queue.firstOrNull { it.first == command.id }
        if (seen != null) {
            session.link.send(Frame.Ack(command.id, seen.second, seen.third))
            return
        }
        session.lastSeq = command.seq
        val needsQueueRev = command.action in setOf("playQueueIndex", "removeQueueIndex", "moveQueue")
        if (needsQueueRev) {
            val rev = command.args["queueRev"]?.jsonPrimitive?.longOrNull
            if (rev != null && rev != currentQueueRev()) {
                reply(session, peerId, command.id, false, FrameError("stale_queue"))
                return
            }
        }
        try {
            adapter.execute(command.action, command.args)
            reply(session, peerId, command.id, true, null)
        } catch (e: CmdException) {
            reply(session, peerId, command.id, false, FrameError(e.code, e.message))
        } catch (e: Exception) {
            reply(session, peerId, command.id, false, FrameError("failed", e.message))
        }
    }

    private fun reply(session: Session, peerId: String, id: String, ok: Boolean, error: FrameError?) {
        val queue = dedup.getOrPut(peerId) { ArrayDeque() }
        queue.addLast(Triple(id, ok, error))
        while (queue.size > DEDUP_CAP) queue.removeFirst()
        session.link.send(Frame.Ack(id, ok, error))
    }

    private suspend fun handleHandoff(session: Session, request: Frame.Handoff) {
        val result = try {
            adapter.handoff(request)
            null
        } catch (e: CmdException) {
            FrameError(e.code, e.message)
        } catch (e: Exception) {
            FrameError("failed", e.message)
        }
        session.link.send(Frame.HandoffResult(request.transferId, result == null, result))
    }

    // ---- state publishing ----------------------------------------------------

    private var lastQueueSignature: Int = 0
    private var queueRevCounter = 0L
    private var revCounter = 0L
    @Volatile private var latestSnapshot = PlayerSnapshot()

    private fun currentQueueRev(): Long = queueRevCounter

    private suspend fun publisher() {
        var changed = adapter.changed.value
        while (running) {
            withTimeoutOrNull(500) { adapter.changed.first { it != changed } }
            changed = adapter.changed.value
            publishOnce()
        }
    }

    private suspend fun publishOnce() {
        val snap = adapter.snapshot()
        latestSnapshot = snap
        val sig = snap.queue.hashCode() * 31 + snap.queueIndex.hashCode()
        if (sig != lastQueueSignature) {
            lastQueueSignature = sig
            queueRevCounter++
        }
        revCounter++
        sessions.values.forEach { session ->
            if (session.authed && session.subscribed) pushState(session, forceQueue = false)
        }
    }

    private fun pushState(session: Session, forceQueue: Boolean) {
        val snap = latestSnapshot
        val queueRev = queueRevCounter
        val includeQueue = forceQueue || session.lastQueueRevSent != queueRev
        session.lastQueueRevSent = queueRev
        session.link.send(
            Frame.State(
                rev = revCounter,
                queueRev = queueRev,
                playback = snap.playback,
                track = snap.track,
                positionMs = snap.positionMs,
                durationMs = snap.durationMs,
                volume = snap.volume,
                shuffle = snap.shuffle,
                repeat = snap.repeat,
                queueIndex = snap.queueIndex,
                queue = if (includeQueue) snap.queue else null,
                error = snap.error,
                capabilities = adapter.capabilities(),
            )
        )
    }

    // ---- outbound sessions ---------------------------------------------------

    private var expectedBySession = ConcurrentHashMap<Session, String>()
    private fun wantedSessionFor(session: Session) = expectedBySession[session]

    /** Connect (or reuse) an authenticated session to `target` (device id or host[:port]). */
    suspend fun connect(target: String): Session {
        val entry = entries.values.firstOrNull {
            it.id == target || it.addr?.substringBefore(':') == target ||
                it.addr == target
        }
        val wantId = if (entry != null) entry.id else null
        if (wantId == null) validateManualTarget(target)
        wantId?.let { id -> sessions[id]?.let { if (!it.closed) return it } }
        val addr = entry?.addr ?: target.withDefaultPort()
        return dial(addr, wantId)
    }

    private fun String.withDefaultPort(): String =
        if (contains(':')) this else "$this:$DEFAULT_PORT"

    private suspend fun dial(addr: String, wantId: String?): Session {
        markEntry(wantId ?: addr) { conn = "connecting" }
        refreshDevices()
        val socket = tls.clientSocket()
        val host = addr.substringBefore(':')
        val portN = addr.substringAfter(':').toIntOrNull() ?: DEFAULT_PORT
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(host, portN), CONNECT_TIMEOUT_MS.toInt())
                    socket.startHandshake()
                }
            } ?: throw java.io.IOException("connect timed out")
        } catch (e: Exception) {
            markEntry(wantId ?: addr) { conn = "disconnected"; lastError = e.message }
            refreshDevices()
            runCatching { socket.close() }
            throw e
        }
        lateinit var session: Session
        val link = Link(
            socket, scope,
            onFrame = { frame -> onFrame(session, frame, server = false) },
            onUnknownType = {},
            onClose = { onSessionClosed(session) },
            onError = { logWarn("outbound session error", it) },
        )
        session = Session(link, clientMode = true, socket)
        session.peerFp = tls.peerFingerprint(socket) ?: ""
        wantId?.let { expectedBySession[session] = it }
        link.send(Frame.Hello(PROTOCOL, PROTOCOL, deviceInfo))
        link.start()
        // Wait for authOk/authRequired.
        val authed = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            var outcome: Boolean? = null
            val job = launch {
                session.pairEvents.collect { f ->
                    if (outcome == null) outcome = when (f) {
                        is Frame.AuthOk -> true
                        is Frame.AuthRequired -> false
                        is Frame.Error -> false
                        else -> null
                    }
                }
            }
            while (outcome == null && !session.closed) delay(25)
            job.cancel()
            outcome
        } ?: false
        if (authed) {
            session.authed = true
            subscribeRemote(session)
        }
        refreshDevices()
        return session
    }

    private fun subscribeRemote(session: Session) {
        session.peer?.id?.let { wanted.add(it) }
        session.link.send(Frame.Subscribe)
    }

    /**
     * Begin pairing with `target` (device id or host[:port]). Returns the
     * prompt id for [confirmPairing], or null when already paired.
     */
    suspend fun pair(target: String): String? {
        val conn = connect(target)
        if (conn.authed) return null
        conn.pairLock.withLock {
            if (conn.authed) return null
            val nc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val commit = Sas.commit(nc).hex()
            if (!conn.link.send(Frame.PairRequest(commit))) throw ConnectException("connection closed")
            val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
            val first = waitPairEvent(conn, deadline) { f ->
                when (f) {
                    is Frame.PairNonce -> f
                    is Frame.PairResult -> f
                    else -> null
                }
            } ?: throw ConnectException("pairing timed out")
            val ns = when (first) {
                is Frame.PairNonce -> first.ns
                is Frame.PairResult ->
                    throw ConnectException("pairing refused: ${first.reason ?: ""}")
                else -> throw ConnectException("pairing failed")
            }
            val nsBytes = ns.unhex() ?: throw ConnectException("bad nonce")
            conn.link.send(Frame.PairReveal(nc.hex()))
            val peerFp = conn.peerFp.unhex() ?: throw ConnectException("no peer fingerprint")
            val code = Sas.code(peerFp, identity.fingerprint, nsBytes, nc)
            val promptId = UUID.randomUUID().toString()
            val reply = CompletableDeferred<Boolean>()
            outgoingPrompts[promptId] = reply
            addPrompt(ConnectPairingPrompt(promptId, incoming = false,
                peerName = conn.peer?.name ?: "device", code = code, deviceId = conn.peer?.id))
            scope.launch {
                val accepted = withTimeoutOrNull(deadline - System.currentTimeMillis() + 1000) {
                    reply.await()
                } ?: false
                outgoingPrompts.remove(promptId)
                removePrompt(promptId)
                conn.link.send(Frame.PairConfirm(accepted))
                val left = deadline - System.currentTimeMillis()
                val result = waitPairEvent(conn, deadline) { it as? Frame.PairResult }
                if (result?.accepted == true) {
                    conn.peer?.let { dev ->
                        trust.put(TrustedDevice(dev.id, dev.name, dev.platform,
                            conn.peerFp, System.currentTimeMillis() / 1000))
                    }
                    conn.authed = true
                    upsertEntry(conn.peer?.id) { this.conn = "paired" }
                    subscribeRemote(conn)
                    refreshDevices()
                }
            }
            return promptId
        }
    }

    private val outgoingPrompts = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private suspend fun <T : Any> waitPairEvent(
        session: Session,
        deadlineMs: Long,
        pick: (Frame) -> T?,
    ): T? {
        val left = deadlineMs - System.currentTimeMillis()
        if (left <= 0) return null
        return withTimeoutOrNull(left) {
            var found: T? = null
            session.pairEvents.first { f -> pick(f).also { found = it } != null }
            found
        }
    }

    fun confirmPairing(promptId: String, accepted: Boolean) {
        val promptsNow = _prompts.value
        val incoming = promptsNow.firstOrNull { it.promptId == promptId }?.incoming == true
        outgoingPrompts[promptId]?.complete(accepted)
        removePrompt(promptId)
        if (incoming) {
            serverPairing?.let { pairing ->
                if (pairing.promptId == promptId) pairing.localAccepted?.complete(accepted)
            }
        }
    }

    /** Send a command to a paired device; resolves to the ack. */
    suspend fun sendCommand(deviceId: String, action: String, args: JsonObject = JsonObject(emptyMap())) {
        val conn = connect(deviceId)
        if (!conn.authed) cmdFail("unauthorized", "device is not paired")
        val id = UUID.randomUUID().toString()
        val seq = nextSeq(conn)
        val reply = CompletableDeferred<Pair<Boolean, FrameError?>>()
        conn.pending[id] = reply
        val cleanArgs = LinkedHashMap(args).apply {
            remove("id"); remove("seq"); remove("action")
        }
        if (!conn.link.send(Frame.Command(id, seq, action, JsonObject(cleanArgs)))) {
            conn.pending.remove(id)
            cmdFail("failed", "connection closed")
        }
        val (ok, error) = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { reply.await() }
            ?: cmdFail("failed", "no ack received")
        if (!ok) throw CmdException(error?.code ?: "failed", error?.message ?: "command failed")
    }

    private fun nextSeq(session: Session): Long {
        session.lastSeq++
        return session.lastSeq
    }

    /** Push a metadata-only queue + position to the target. */
    suspend fun handoff(deviceId: String, request: Frame.Handoff) {
        val conn = connect(deviceId)
        if (!conn.authed) cmdFail("unauthorized", "device is not paired")
        val transferId = request.transferId.ifEmpty { UUID.randomUUID().toString() }
        val sent = request.copy(transferId = transferId)
        val reply = CompletableDeferred<Pair<Boolean, FrameError?>>()
        conn.pending["handoff:$transferId"] = reply
        if (!conn.link.send(sent)) {
            conn.pending.remove("handoff:$transferId")
            cmdFail("failed", "connection closed")
        }
        val (ok, error) = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { reply.await() }
            ?: cmdFail("failed", "handoff timed out")
        if (!ok) throw CmdException(error?.code ?: "failed", error?.message ?: "handoff failed")
    }

    suspend fun unpair(deviceId: String) {
        sessions.remove(deviceId)?.let { session ->
            session.link.send(Frame.Unpair)
            session.link.close()
        }
        trust.remove(deviceId)
        wanted.remove(deviceId)
        entries.remove(deviceId)
        _remoteStates.value = _remoteStates.value - deviceId
        refreshDevices()
    }

    /** Called by discovery when a `_kasane._tcp` service resolves. */
    fun onServiceDiscovered(id: String?, name: String, platform: String?, host: String, port: Int) {
        if (id == identity.deviceId) return
        val key = id ?: "$host:$port"
        upsertEntry(id ?: key) {
            if (id != null) this.id = id
            this.name = name
            this.platform = platform ?: this.platform
            addr = "$host:$port"
            lastResolve = System.currentTimeMillis()
        }
        // A fresh resolve of a trusted/wanted device triggers an immediate reconnect.
        if (id != null && wanted.contains(id) && sessions[id]?.closed != false) {
            scope.launch {
                runCatching { connect(id) }
            }
        }
        refreshDevices()
    }

    fun onServiceLost(id: String?, host: String?, port: Int) {
        val key = id ?: "${host ?: ""}:$port"
        entries[key]?.lastResolve = 0
        refreshDevices()
    }

    private fun upsertEntry(key: String?, block: DeviceEntry.() -> Unit) {
        if (key == null) return
        val entry = entries.getOrPut(key) { DeviceEntry(id = key, name = key, platform = null, addr = null) }
        entry.block()
    }

    private fun markEntry(key: String, block: DeviceEntry.() -> Unit) {
        val entry = entries.getOrPut(key) {
            DeviceEntry(
                id = if (key.contains(':')) null else key,
                name = key,
                platform = null,
                addr = key.takeIf { it.contains(':') },
            )
        }
        entry.block()
    }

    private fun markConnected(device: DeviceInfo, conn: String) {
        upsertEntry(device.id) {
            name = device.name
            platform = device.platform
            this.conn = conn
            lastError = null
        }
    }

    private fun addPrompt(prompt: ConnectPairingPrompt) {
        _prompts.value = _prompts.value + prompt
    }

    private fun removePrompt(promptId: String) {
        _prompts.value = _prompts.value.filterNot { it.promptId == promptId }
    }

    private fun refreshDevices() {
        val trusted = trust.all().associateBy { it.deviceId }
        val out = LinkedHashMap<String, ConnectDeviceView>()
        for ((id, d) in trusted) {
            out[id] = ConnectDeviceView(
                id = id, name = d.name, platform = d.platform,
                addr = d.endpoints.lastOrNull(),
                online = false, trusted = true, connection = "disconnected",
            )
        }
        val now = System.currentTimeMillis()
        for ((key, e) in entries) {
            val id = e.id ?: key
            val online = e.lastResolve > 0 && now - e.lastResolve < RESOLVE_FRESH_MS ||
                sessions[id]?.closed == false
            val view = ConnectDeviceView(
                id = e.id, name = e.name, platform = e.platform, addr = e.addr,
                online = online,
                trusted = trusted.containsKey(id),
                connection = e.conn,
                lastError = e.lastError,
            )
            out[id] = out[id]?.copy(
                name = e.name.ifEmpty { out[id]!!.name },
                platform = e.platform ?: out[id]!!.platform,
                addr = e.addr ?: out[id]!!.addr,
                online = online, connection = e.conn,
                lastError = e.lastError,
            ) ?: view
        }
        for ((id, session) in sessions) {
            val peer = session.peer ?: continue
            val conn = if (session.authed) "paired" else "connected"
            out[id] = (out[id] ?: ConnectDeviceView(id, peer.name, peer.platform,
                remoteAddr(session), online = true, trusted = trust.isTrusted(id, session.peerFp), connection = conn))
                .copy(online = true, connection = conn, name = peer.name, platform = peer.platform)
        }
        _devices.value = out.values.toList()
    }

    fun remoteState(deviceId: String): Frame.State? = sessions[deviceId]?.remoteState?.value

    /** Manual entry is limited to private/loopback/link-local IPv4 for v1. */
    fun validateManualTarget(target: String) {
        val host = target.substringBefore(':')
        val ip = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ip.size != 4 || ip.any { it !in 0..255 }) {
            throw ConnectException("enter a private IPv4 address, not \"$target\"")
        }
        val private = ip[0] == 10 ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168) ||
            ip[0] == 127 ||
            (ip[0] == 169 && ip[1] == 254)
        if (!private) throw ConnectException("only LAN addresses can be added manually")
    }
}

class ConnectException(message: String) : Exception(message)

private fun logWarn(tag: String, t: Throwable) {
    runCatching { android.util.Log.w("KasaneConnect", tag, t) }
        .onFailure { println("KasaneConnect $tag: $t") }
}
