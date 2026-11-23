package cord.kasane.music.connect

/**
 * What a device is doing, as the user sees it. Discovery, trust, the live session and playback are
 * different facts and are never conflated: an available device is not playing, a paired one may be offline.
 */
enum class DeviceState {
    /** Seen on the network, not paired. */
    AVAILABLE,
    /** A pairing with this device is in progress. */
    PAIRING,
    /** Paired and reachable, no live session yet. */
    PAIRED,
    /** Paired with a live, authenticated session; nothing known to be playing. */
    CONNECTED,
    PLAYING,
    /** Connected and a track is loaded but not playing. */
    PAUSED,
    /** Paired, but not seen on the network. */
    OFFLINE,
}

data class ConnectDeviceView(
    /** The stable device id. Null only for a manually entered address that has not answered yet. */
    val id: String?,
    val name: String,
    val platform: String?,
    val addr: String?,
    val online: Boolean,
    val trusted: Boolean,
    /** disconnected | connecting | connected | paired */
    val connection: String,
    val lastError: String? = null,
    val state: DeviceState = DeviceState.AVAILABLE,
)

data class ConnectPairingPrompt(
    val promptId: String,
    val incoming: Boolean,
    val peerName: String,
    val code: String,
    val deviceId: String?,
    /** Outgoing pairing: this device only displays the code; the other device's user confirms. */
    val awaitingPeer: Boolean = false,
)
