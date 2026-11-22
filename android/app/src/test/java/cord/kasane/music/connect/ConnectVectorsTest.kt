package cord.kasane.music.connect

import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared vectors in docs/connect/test-vectors.json are normative. */
class ConnectVectorsTest {

    private fun vectors() =
        kotlinx.serialization.json.Json.parseToJsonElement(
            File("../../docs/connect/test-vectors.json").readText()
        ).jsonObject

    private fun String.hexBytes() = unhex() ?: error("bad hex $this")

    @Test
    fun commitAndSasMatchTheSharedVector() {
        val sas = vectors()["sas"]!!.jsonObject
        val nc = sas["nc"]!!.jsonPrimitive.content.hexBytes()
        assertEquals(sas["commit"]!!.jsonPrimitive.content, Sas.commit(nc).hex())
        val code = Sas.code(
            sas["fpServer"]!!.jsonPrimitive.content.hexBytes(),
            sas["fpClient"]!!.jsonPrimitive.content.hexBytes(),
            sas["ns"]!!.jsonPrimitive.content.hexBytes(),
            nc,
        )
        assertEquals(sas["code"]!!.jsonPrimitive.content, code)
    }

    @Test
    fun allVectorFramesDecodeAndIgnoreUnknownFields() {
        for (line in vectors()["frames"]!!.jsonArray) {
            val text = line.jsonPrimitive.content
            when (val decoded = ConnectJson.decode(text)) {
                is ConnectJson.Decoded.Ok -> {}
                else -> error("frame failed to decode: $text -> $decoded")
            }
        }
    }

    @Test
    fun decodedCommandCarriesFlattenedArgs() {
        val line = """{"type":"command","id":"c-1","seq":1,"action":"seek","positionMs":61000}"""
        val decoded = ConnectJson.decode(line) as ConnectJson.Decoded.Ok
        val command = decoded.frame as Frame.Command
        assertEquals("seek", command.action)
        assertEquals(61000L, command.args["positionMs"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun commandRoundTripsItsFlattenedArgs() {
        val frame = Frame.Command("id-9", 4, "moveQueue",
            kotlinx.serialization.json.JsonObject(mapOf(
                "from" to kotlinx.serialization.json.JsonPrimitive(3),
                "to" to kotlinx.serialization.json.JsonPrimitive(1),
                "queueRev" to kotlinx.serialization.json.JsonPrimitive(7),
            )))
        val decoded = ConnectJson.decode(ConnectJson.encode(frame)) as ConnectJson.Decoded.Ok
        val command = decoded.frame as Frame.Command
        assertEquals(frame.id, command.id)
        assertEquals(7L, command.args["queueRev"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun unknownTypeIsReportedNotMalformed() {
        val decoded = ConnectJson.decode("""{"type":"futureThing","x":1}""")
        assertTrue(decoded is ConnectJson.Decoded.UnknownType)
        assertEquals("futureThing", (decoded as ConnectJson.Decoded.UnknownType).type)
    }
}
