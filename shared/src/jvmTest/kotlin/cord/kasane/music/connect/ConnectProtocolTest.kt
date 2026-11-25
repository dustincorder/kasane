package cord.kasane.music.connect

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConnectProtocolTest {

    private fun decode(line: String): Frame =
        (ConnectJson.decode(line) as ConnectJson.Decoded.Ok).frame

    @Test
    fun stateFrameCarriesRoleAndActiveId() {
        val state = Frame.State(
            rev = 5, queueRev = 2, playback = "playing",
            role = "listener", activeId = "device-xyz",
        )
        val decoded = decode(ConnectJson.encode(state)) as Frame.State
        assertEquals("listener", decoded.role)
        assertEquals("device-xyz", decoded.activeId)
    }

    @Test
    fun v1StateWithoutRoleDecodesWithDefaults() {
        // A frame from a peer that predates the role model must still decode.
        val line = """{"type":"state","rev":4,"queueRev":2,"playback":"playing","track":null,"positionMs":15000,"durationMs":201000,"volume":0.5,"shuffle":false,"repeat":"off","queueIndex":0,"queue":[],"error":null,"capabilities":["seek","volume","queue","handoff"]}"""
        val state = decode(line) as Frame.State
        assertEquals("solo", state.role)
        assertNull(state.activeId)
    }

    @Test
    fun takeoverCommandRoundTripsArgs() {
        val queue = listOf(TrackMeta(id = "a0nze6VvaXY", title = "Song", artist = "Artist"))
        val args = buildJsonObject {
            put("queue", ConnectJson.json.encodeToJsonElement(queue))
            put("index", 0)
            put("positionMs", 42_000)
            put("playing", true)
        }
        val cmd = Frame.Command(id = "t-1", seq = 9, action = "takeover", args = args)
        val decoded = decode(ConnectJson.encode(cmd)) as Frame.Command
        assertEquals("takeover", decoded.action)
        assertEquals(9, decoded.seq)
        val restored = ConnectJson.json.decodeFromJsonElement<List<TrackMeta>>(decoded.args["queue"]!!)
        assertEquals("a0nze6VvaXY", restored[0].id)
        assertEquals(42_000L, decoded.args["positionMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun yieldCommandCarriesNewActiveId() {
        val cmd = Frame.Command(id = "y-1", seq = 3, action = "yield",
            args = buildJsonObject { put("activeId", "device-xyz") })
        val decoded = decode(ConnectJson.encode(cmd)) as Frame.Command
        assertEquals("yield", decoded.action)
        assertEquals("device-xyz", decoded.args["activeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun commandArgsSurviveFlattenedEncoding() {
        // Commands keep the flattened wire shape (args next to id/seq/action) — protocol compat.
        val decoded = decode("""{"type":"command","id":"c-9","seq":12,"action":"seek","positionMs":61000}""") as Frame.Command
        assertEquals("seek", decoded.action)
        assertEquals(12, decoded.seq)
        assertEquals(61_000L, decoded.args["positionMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun unknownTypeStillDecodesAsUnknown() {
        val result = ConnectJson.decode("""{"type":"futureFrame","x":1}""")
        assertIs<ConnectJson.Decoded.UnknownType>(result)
    }

    @Test
    fun unknownFieldsInsideKnownFramesAreIgnored() {
        // Forward compatibility: a newer peer may add fields (e.g. role/activeId) freely.
        val line = """{"type":"state","rev":1,"queueRev":1,"playback":"idle","role":"performer","activeId":"d1","futureField":{"nested":true}}"""
        val state = decode(line) as Frame.State
        assertEquals("performer", state.role)
        assertEquals("d1", state.activeId)
    }
}
