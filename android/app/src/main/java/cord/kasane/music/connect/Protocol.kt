package cord.kasane.music.connect

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val app: String = "",
    val appVersion: String = "",
)

@Serializable
data class ArtistMeta(val name: String, val id: String? = null)

@Serializable
data class AlbumMeta(val title: String, val id: String? = null)

/** Metadata-only track projection; stream URLs never cross the wire. */
@Serializable
data class TrackMeta(
    val id: String,
    val title: String,
    val artist: String = "",
    val artists: List<ArtistMeta> = emptyList(),
    val album: AlbumMeta? = null,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
    val source: String = "youtube",
)

@Serializable
data class FrameError(val code: String, val message: String? = null)

/** One line-delimited protocol frame (docs/connect/protocol.md). */
@Serializable
sealed class Frame {
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val minProtocol: Int, val device: DeviceInfo) : Frame()

    @Serializable
    @SerialName("authOk")
    object AuthOk : Frame()

    @Serializable
    @SerialName("authRequired")
    object AuthRequired : Frame()

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String? = null) : Frame()

    @Serializable
    @SerialName("ping")
    object Ping : Frame()

    @Serializable
    @SerialName("pong")
    object Pong : Frame()

    @Serializable
    @SerialName("pairRequest")
    data class PairRequest(val commit: String) : Frame()

    @Serializable
    @SerialName("pairNonce")
    data class PairNonce(val ns: String) : Frame()

    @Serializable
    @SerialName("pairReveal")
    data class PairReveal(val nc: String) : Frame()

    @Serializable
    @SerialName("pairConfirm")
    data class PairConfirm(val accepted: Boolean) : Frame()

    @Serializable
    @SerialName("pairResult")
    data class PairResult(val accepted: Boolean, val reason: String? = null) : Frame()

    @Serializable
    @SerialName("unpair")
    object Unpair : Frame()

    @Serializable
    @SerialName("subscribe")
    object Subscribe : Frame()

    @Serializable
    @SerialName("sync")
    object Sync : Frame()

    /** Playback snapshot broadcast by the player. */
    @Serializable
    @SerialName("state")
    data class State(
        val rev: Long,
        val queueRev: Long,
        val playback: String,
        val track: TrackMeta? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val volume: Float? = null,
        val shuffle: Boolean = false,
        val repeat: String = "off",
        val queueIndex: Long = -1,
        val queue: List<TrackMeta>? = null,
        val error: String? = null,
        val capabilities: List<String> = emptyList(),
    ) : Frame()

    @Serializable(with = CommandSerializer::class)
    @SerialName("command")
    data class Command(
        val id: String,
        val seq: Long,
        val action: String,
        val args: JsonObject = JsonObject(emptyMap()),
    ) : Frame()

    @Serializable
    @SerialName("ack")
    data class Ack(val id: String, val ok: Boolean, val error: FrameError? = null) : Frame()

    @Serializable
    @SerialName("handoff")
    data class Handoff(
        val transferId: String,
        val queue: List<TrackMeta> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: String = "off",
    ) : Frame()

    @Serializable
    @SerialName("handoffResult")
    data class HandoffResult(
        val transferId: String,
        val ok: Boolean,
        val error: FrameError? = null,
    ) : Frame()
}

/** Commands carry `args` flattened next to id/seq/action. */
object CommandSerializer : KSerializer<Frame.Command> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("command")

    override fun serialize(encoder: Encoder, value: Frame.Command) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        val fields = LinkedHashMap<String, JsonElement>(value.args.size + 3)
        fields["id"] = JsonPrimitive(value.id)
        fields["seq"] = JsonPrimitive(value.seq)
        fields["action"] = JsonPrimitive(value.action)
        fields.putAll(value.args)
        json.encodeJsonElement(JsonObject(fields))
    }

    override fun deserialize(decoder: Decoder): Frame.Command {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        val obj = json.decodeJsonElement().jsonObject
        fun required(key: String) = obj[key] ?: throw SerializationException("missing $key")
        val args = LinkedHashMap(obj)
        args.remove("type")
        args.remove("id")
        args.remove("seq")
        args.remove("action")
        return Frame.Command(
            id = required("id").jsonPrimitive.content,
            seq = required("seq").jsonPrimitive.content.toLong(),
            action = required("action").jsonPrimitive.content,
            args = JsonObject(args),
        )
    }
}

object ConnectJson {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        // Peers must see every field (e.g. TrackMeta.source) even when it
        // equals our local default; Rust requires some of them.
        encodeDefaults = true
    }

    /** Known `type` tags, checked before sealed dispatch so unknown frames get `unknown_type`. */
    private val known = setOf(
        "hello", "authOk", "authRequired", "error", "ping", "pong",
        "pairRequest", "pairNonce", "pairReveal", "pairConfirm", "pairResult",
        "unpair", "subscribe", "sync", "state", "command", "ack",
        "handoff", "handoffResult",
    )

    sealed interface Decoded {
        data class Ok(val frame: Frame) : Decoded
        data class UnknownType(val type: String) : Decoded
        data class Malformed(val detail: String) : Decoded
    }

    fun decode(line: String): Decoded {
        val element = try {
            json.parseToJsonElement(line)
        } catch (e: SerializationException) {
            return Decoded.Malformed(e.message ?: "bad json")
        } catch (e: IllegalArgumentException) {
            return Decoded.Malformed(e.message ?: "bad json")
        }
        val obj = element as? JsonObject ?: return Decoded.Malformed("frame is not an object")
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: return Decoded.Malformed("missing type")
        if (type !in known) return Decoded.UnknownType(type)
        return try {
            Decoded.Ok(json.decodeFromJsonElement<Frame>(element))
        } catch (e: SerializationException) {
            Decoded.Malformed("$type: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Decoded.Malformed("$type: ${e.message}")
        }
    }

    fun encode(frame: Frame): String = json.encodeToJsonElement(frame).toString()

    fun error(code: String) = Frame.Error(code)
}
