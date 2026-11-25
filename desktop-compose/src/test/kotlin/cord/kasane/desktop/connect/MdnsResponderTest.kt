package cord.kasane.desktop.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.ByteArrayOutputStream

/** Query parsing: Android's NSD sets the unicast-response flag in the question — the responder must still match it. */
class MdnsResponderTest {
    private fun query(name: String, qtype: Int, qclass: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        u16(0); u16(0); u16(1); u16(0); u16(0); u16(0) // id, flags (QR=0), qdcount=1
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0)
        u16(qtype); u16(qclass)
        return out.toByteArray()
    }

    @Test fun plainPtrQuestionParses() {
        val q = query("_kasane._tcp.local", qtype = 12)
        assertEquals(listOf("_kasane._tcp.local" to 12), MdnsResponder.questions(q, q.size))
    }

    @Test fun quBitIsMaskedOffTheType() {
        // Android NSD sends QU queries — PTR arrives as 0x800C, not 0x000C (RFC 6762 §5.4).
        val q = query("_kasane._tcp.local", qtype = 0x800C)
        assertEquals(listOf("_kasane._tcp.local" to 12), MdnsResponder.questions(q, q.size))
        val any = query("_kasane._tcp.local", qtype = 0x80FF)
        assertEquals(listOf("_kasane._tcp.local" to 255), MdnsResponder.questions(any, any.size))
    }

    @Test fun responsesAreIgnored() {
        val q = query("_kasane._tcp.local", qtype = 12)
        q[2] = 0x84.toByte() // QR=1 lives in the high flags byte — a response, not a question
        assertEquals(emptyList(), MdnsResponder.questions(q, q.size))
    }
}
