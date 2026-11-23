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
private const val RECONNECT_INTERVAL_MS = 5_000L
private const val MAX_UNAUTH_PER_IP = 2
private const val MAX_UNAUTH_TOTAL = 4
private const val CONNECT_TIMEOUT_MS = 10_000L

data class ConnectConfig(
    val identity: ConnectIdentity,
    val adapter: ConnectPlayerAdapter,
    val trustFile: java.io.File,
    val appVersion: String,
    val platform: String = "android",
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Preferred listening port; an ephemeral port is used when it is taken. */
    val listenPort: Int = DEFAULT_PORT,
    /** True for an address that belongs to this machine (another app instance here is not a remote device). */
    val isLocalAddress: (String) -> Boolean = ::isThisMachine,
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

    private val entries = ConcurrentHashMap<String, DeviceEntry>()
    private val sessions = ConcurrentHashMap<String, Session>()
    /** Sessions other devices opened to us; subscribed ones receive our state updates. */
    private val inbound: MutableSet<Session> = ConcurrentHashMap.newKeySet()
    private val wanted = mutableSetOf<String>()
    private val dedup = ConcurrentHashMap<String, ArrayDeque<Triple<String, Boolean, FrameError?>>>()
    private val pairFailures = ArrayDeque<Long>()

    val localDeviceName: String get() = identity.deviceName
    val localDeviceId: String get() = identity.deviceId

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
            tls.serverSocket(config.listenPort)
        } catch (e: Exception) {
            try { tls.serverSocket(0) } catch (e2: Exception) { return }
        }
        serverSocket = listener
        port = listener.localPort
        scope.launch(Dispatchers.IO) { acceptLoop(listener) }
        scope.launch { publisher() }
        scope.launch { reconnectLoop() }
    }

    /** Keep sessions to online paired devices alive; one attempt per device at a time. */
    private suspend fun reconnectLoop() {
        val inflight = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        while (running) {
            delay(RECONNECT_INTERVAL_MS)
            for (id in wanted.toList()) {
                if (sessions[id]?.closed == false || !inflight.add(id)) continue
                val entry = entries[id]
                // NSD reports a lost service explicitly (lastResolve = 0); it does not re-resolve.
                val online = entry != null && entry.lastResolve > 0
                if (!online) { inflight.remove(id); continue }
                scope.launch {
                    try { runCatching { connect(id) } } finally { inflight.remove(id) }
                }
            }
            refreshDevices()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        sessions.values.forEach { it.link.close() }
        sessions.clear()
        inbound.forEach { it.link.close() }
        inbound.clear()
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
        inbound.add(session)
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
            while (true) {
                delay(AUTH_IDLE_MS)
                if (session.authed || session.closed) break
                // A pairing has its own 120 s deadline while the users compare codes.
                if (serverPairing?.session === session) continue
                link.close()
                break
            }
        }
    }

    private fun releaseUnauth(ip: String) {
        unauthByIp.computeIfPresent(ip) { _, n -> if (n <= 1) null else n - 1 }
        unauthTotal--
    }

    private fun onSessionClosed(session: Session) {
        inbound.remove(session)
        if (!session.closed) {
            session.closed = true
            session.dead.complete(Unit)
        }
        // An in-flight pairing on this connection can never finish: end it so
        // the prompt disappears and the next pairing is not refused as busy.
        serverPairing?.takeIf { it.session === session }?.let { pairing ->
            if (pairing.peerAccepted == null) pairing.peerAccepted = false
            val prompt = pairing.localAccepted
            if (prompt == null) {
                // Closed before the prompt existed: nothing else will clean up.
                serverPairing = null
                pairSlot.unlock()
            } else {
                prompt.complete(false)
            }
        }
        session.pending.values.forEach { it.complete(false to FrameError("failed", "connection closed")) }
        session.pending.clear()
        session.peer?.let { peer ->
            val wasAuthed = session.authed
            if (sessions.remove(peer.id, session)) {
                // A device that went away is no longer "playing" anywhere in the UI.
                _remoteStates.value = _remoteStates.value - peer.id
                refreshDevices()
            }
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
                // The queue is only sent when it changed (protocol §State); keep the last one.
                val previous = session.remoteState.value
                val merged = if (frame.queue == null && previous?.queue != null && previous.queueRev == frame.queueRev)
                    frame.copy(queue = previous.queue) else frame
                if (merged.queue == null) session.link.send(Frame.Sync)
                session.remoteState.value = merged
                session.peer?.let { peer ->
                    _remoteStates.value = _remoteStates.value + (peer.id to merged)
                }
                if (previous?.playback != merged.playback) refreshDevices()
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
        if (hello.device.id == identity.deviceId) {
            // This device reached itself (own address, or mDNS echo). Never a pairing candidate.
            session.link.close()
            return
        }
        session.peer = hello.device
        if (session.clientMode) remoteAddr(session)?.let { mergeAddressEntries(hello.device.id, it) }
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
            // Never trust a peer that is gone before it can learn the result.
            if (peer && !session.closed) {
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
        // A peer rejection ends the pairing now and withdraws our prompt.
        if (!confirm.accepted) pairing.localAccepted?.complete(false)
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
        inbound.forEach { session ->
            if (session.authed && session.subscribed && !session.closed) pushState(session, forceQueue = false)
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

    /**
     * Dial a peer. If the peer still trusts this device from a pairing this device has since forgotten, ask it
     * to forget us too and dial again: the user gets a normal pairing with a code, not a dead end.
     */
    private suspend fun dial(addr: String, wantId: String?, resetStale: Boolean = true): Session {
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
        // authOk only means the peer trusts us; we must also have pinned its
        // certificate, or an impostor could accept our commands.
        val peer = session.peer
        if (authed && (peer == null || !trust.isTrusted(peer.id, session.peerFp))) {
            session.authed = false
            if (resetStale) {
                // The peer authenticated our certificate, so this request can only come from us.
                link.send(Frame.Unpair)
                delay(300)
                link.close()
                return dial(addr, wantId, resetStale = false)
            }
            link.close()
            val name = peer?.name ?: "The other device"
            throw ConnectException("$name still trusts this device from an earlier pairing. Remove this device on $name and pair again.")
        }
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
            // This device only displays the code. The user compares it on the other device and confirms there,
            // once; this side's confirmation is implied by having started the pairing.
            val cancelled = CompletableDeferred<Boolean>()
            outgoingPrompts[promptId] = cancelled
            addPrompt(ConnectPairingPrompt(promptId, incoming = false,
                peerName = conn.peer?.name ?: "device", code = code, deviceId = conn.peer?.id, awaitingPeer = true))
            refreshDevices()
            conn.link.send(Frame.PairConfirm(true))
            scope.launch {
                val watcher = launch { if (!cancelled.await()) conn.link.send(Frame.PairConfirm(false)) }
                val result = waitPairEvent(conn, deadline) { it as? Frame.PairResult }
                watcher.cancel()
                outgoingPrompts.remove(promptId)
                removePrompt(promptId)
                if (result?.accepted == true) {
                    conn.peer?.let { dev ->
                        trust.put(TrustedDevice(dev.id, dev.name, dev.platform,
                            conn.peerFp, System.currentTimeMillis() / 1000))
                    }
                    conn.authed = true
                    upsertEntry(conn.peer?.id) { this.conn = "paired"; lastError = null }
                    subscribeRemote(conn)
                } else {
                    val why = when (result?.reason) {
                        null -> "pairing timed out"
                        "rejected" -> "${conn.peer?.name ?: "The other device"} declined the pairing"
                        else -> "pairing failed (${result.reason})"
                    }
                    conn.peer?.let { dev -> upsertEntry(dev.id) { lastError = why } }
                    conn.link.close()
                }
                refreshDevices()
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

    /** Close the session to a paired device; it reconnects when it is announced again. */
    fun disconnect(deviceId: String) {
        wanted.remove(deviceId)
        sessions.remove(deviceId)?.link?.close()
        _remoteStates.value = _remoteStates.value - deviceId
        refreshDevices()
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
        // A record without the device id is half-resolved (the TXT data has not arrived): wait for the complete
        // one rather than inventing an address-keyed device that later duplicates the real entry.
        if (id.isNullOrBlank()) return
        // Never this device (stable id), and never another app instance on this machine (its address is ours).
        if (id == identity.deviceId || config.isLocalAddress(host)) return
        val addr = "$host:$port"
        mergeAddressEntries(id, addr)
        upsertEntry(id) {
            this.id = id
            this.name = name
            this.platform = platform ?: this.platform
            this.addr = addr
            lastResolve = System.currentTimeMillis()
        }
        // Paired devices connect on their own as soon as they appear, so this
        // device always knows what they are playing.
        if (trust.get(id) != null) wanted.add(id)
        if (wanted.contains(id) && sessions[id]?.closed != false) {
            scope.launch {
                runCatching { connect(id) }
            }
        }
        refreshDevices()
    }

    /** A service disappeared. Match by id when known, else by address, so no record stays "online" for ever. */
    fun onServiceLost(id: String?, host: String?, port: Int) {
        val addr = host?.let { "$it:$port" }
        val key = id?.takeIf { entries.containsKey(it) } ?: entries.entries.firstOrNull { it.value.addr == addr }?.key
        key?.let { entries[it]?.lastResolve = 0 }
        refreshDevices()
    }

    /**
     * An entry created from an address (manual pairing, or a connection before the id was known) and the
     * entry for the same endpoint with a real id are one device: fold the first into the second.
     */
    private fun mergeAddressEntries(id: String, addr: String) {
        entries.entries.filter { (k, e) -> k != id && e.id == null && e.addr == addr }.forEach { (k, e) ->
            entries.remove(k)
            upsertEntry(id) { conn = e.conn; lastError = e.lastError }
        }
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
        val remote = _remoteStates.value
        val pairingWith = _prompts.value.mapNotNull { it.deviceId }.toSet()
        fun stateOf(id: String?, online: Boolean, isTrusted: Boolean): DeviceState {
            val live = id?.let { sessions[it] }?.let { it.authed && !it.closed } == true
            return when {
                id != null && id in pairingWith -> DeviceState.PAIRING
                isTrusted && live -> when (remote[id]?.playback) {
                    "playing", "buffering" -> DeviceState.PLAYING
                    "paused", "idle", "ended", "error" -> DeviceState.PAUSED
                    else -> DeviceState.CONNECTED
                }
                isTrusted && online -> DeviceState.PAIRED
                isTrusted -> DeviceState.OFFLINE
                online -> DeviceState.AVAILABLE
                else -> DeviceState.OFFLINE
            }
        }
        val out = LinkedHashMap<String, ConnectDeviceView>()
        for ((id, d) in trusted) {
            out[id] = ConnectDeviceView(
                id = id, name = d.name, platform = d.platform,
                addr = d.endpoints.lastOrNull(),
                online = false, trusted = true, connection = "disconnected",
                state = DeviceState.OFFLINE,
            )
        }
        val knownAddrs = entries.values.filter { it.id != null }.mapNotNull { it.addr }.toSet() +
            sessions.values.mapNotNull { remoteAddr(it) }
        for ((key, e) in entries) {
            val id = e.id ?: key
            // An address-only record for an endpoint that already has an identified device is the same device.
            if (e.id == null && e.addr in knownAddrs) continue
            val live = sessions[id]?.closed == false
            val online = e.lastResolve > 0 || live
            val isTrusted = trusted.containsKey(id)
            // A never-paired device that is gone (not announced, not connecting) is stale: do not list it.
            if (!isTrusted && !online && e.conn == "disconnected" && e.lastError == null) continue
            val view = ConnectDeviceView(
                id = e.id, name = e.name, platform = e.platform, addr = e.addr,
                online = online, trusted = isTrusted, connection = e.conn, lastError = e.lastError,
            )
            out[id] = out[id]?.copy(
                name = e.name.ifEmpty { out[id]!!.name },
                platform = e.platform ?: out[id]!!.platform,
                addr = e.addr ?: out[id]!!.addr,
                online = online, connection = e.conn, lastError = e.lastError,
            ) ?: view
        }
        for ((id, session) in sessions) {
            val peer = session.peer ?: continue
            val conn = if (session.authed) "paired" else "connected"
            out[id] = (out[id] ?: ConnectDeviceView(id, peer.name, peer.platform,
                remoteAddr(session), online = true, trusted = trust.isTrusted(id, session.peerFp), connection = conn))
                .copy(online = true, connection = conn, name = peer.name, platform = peer.platform)
        }
        _devices.value = out.entries.map { (id, v) -> v.copy(state = stateOf(v.id ?: id.takeIf { v.id != null }, v.online, v.trusted)) }
            .sortedWith(compareBy({ it.state.ordinal }, { it.name.lowercase() }))
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

/** Loopback, or an address bound to one of this machine's interfaces. */
fun isThisMachine(host: String): Boolean {
    val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
    if (address.isLoopbackAddress) return true
    return runCatching { java.net.NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)
}

/** Platform log sink; Android routes it to logcat, the desktop to stderr. */
object ConnectLog {
    @Volatile var warn: (String, Throwable) -> Unit = { tag, t -> System.err.println("KasaneConnect $tag: $t") }
}

private fun logWarn(tag: String, t: Throwable) = ConnectLog.warn(tag, t)
