package cord.kasane.music.provider.sabr

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** SABR protobuf field numbers and UMP framing documented by LuanRT/googlevideo (MIT).
 * See third_party/googlevideo-LICENSE.txt. No generated protobuf runtime is needed. */
internal object SabrWire {
    const val MAX_PART = 2 * 1024 * 1024
    class Fields(val numbers: Map<Int, List<Long>>, val bytes: Map<Int, List<ByteArray>>) {
        fun number(field: Int, default: Long = 0) = numbers[field]?.firstOrNull() ?: default
        fun data(field: Int) = bytes[field]?.firstOrNull()
        fun text(field: Int) = data(field)?.toString(Charsets.UTF_8).orEmpty()
        fun message(field: Int) = decode(data(field) ?: byteArrayOf())
        fun repeatedNumbers(field: Int): List<Long> {
            val result = numbers[field].orEmpty().toMutableList()
            bytes[field].orEmpty().forEach { packed ->
                val input = packed.inputStream()
                while (input.available() > 0) { requireSafe(result.size < 2048); result.add(varint(input)) }
            }
            return result
        }
    }
    fun decode(data: ByteArray): Fields {
        requireSafe(data.size <= MAX_PART)
        val input = data.inputStream()
        val numbers = mutableMapOf<Int, MutableList<Long>>()
        val bytes = mutableMapOf<Int, MutableList<ByteArray>>()
        var count = 0
        while (input.available() > 0) {
            requireSafe(++count <= 2048)
            val tag = varint(input)
            val field = (tag ushr 3).toInt()
            requireSafe(field in 1..536870911)
            when ((tag and 7).toInt()) {
                0 -> numbers.getOrPut(field) { mutableListOf() }.add(varint(input))
                1 -> exact(input, 8)
                2 -> {
                    val size = varint(input)
                    requireSafe(size in 0..MAX_PART.toLong())
                    bytes.getOrPut(field) { mutableListOf() }.add(exact(input, size.toInt()))
                }
                5 -> exact(input, 4)
                else -> throw IOException("Unsupported SABR protobuf wire type.")
            }
        }
        return Fields(numbers, bytes)
    }
    fun varint(input: InputStream): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            val b = input.read()
            if (b < 0) throw EOFException("Truncated SABR protobuf.")
            requireSafe(shift != 63 || b <= 1)
            result = result or ((b and 127).toLong() shl shift)
            if (b < 128) return result
        }
        throw IOException("Invalid SABR varint.")
    }
    fun number(field: Int, value: Long) = encodeVarint(field.toLong() shl 3) + encodeVarint(value)
    fun bytes(field: Int, value: ByteArray) = encodeVarint((field.toLong() shl 3) or 2) + encodeVarint(value.size.toLong()) + value
    fun text(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun encodeVarint(value: Long): ByteArray {
        val output = ByteArrayOutputStream(); var remaining = value
        while (remaining and -128L != 0L) { output.write((remaining.toInt() and 127) or 128); remaining = remaining ushr 7 }
        output.write(remaining.toInt()); return output.toByteArray()
    }
    fun exact(input: InputStream, size: Int): ByteArray {
        requireSafe(size in 0..MAX_PART)
        val data = ByteArray(size); var position = 0
        while (position < size) {
            val read = input.read(data, position, size - position)
            if (read < 0) throw EOFException("Truncated SABR UMP part.")
            position += read
        }
        return data
    }
    /** UMP uses a prefix-length integer, not protobuf LEB128. */
    private fun umpNumber(input: InputStream, eofAllowed: Boolean = false): Int? {
        val first = input.read()
        if (first < 0 && eofAllowed) return null
        if (first < 0) throw EOFException("Truncated UMP header.")
        val size = when { first < 128 -> 1; first < 192 -> 2; first < 224 -> 3; first < 240 -> 4; else -> 5 }
        if (size == 1) return first
        val tail = exact(input, size - 1)
        var value = if (size == 5) 0L else (first and ((1 shl (8 - size)) - 1)).toLong()
        tail.forEachIndexed { i, byte -> value = value or ((byte.toInt() and 255).toLong() shl (if (size == 5) i * 8 else 8 - size + i * 8)) }
        requireSafe(value <= Int.MAX_VALUE)
        return value.toInt()
    }
    fun readParts(input: InputStream, onPart: (Int, ByteArray) -> Unit) {
        var consumed = 0L; var count = 0
        while (true) {
            val type = umpNumber(input, true) ?: return
            val length = umpNumber(input) ?: throw EOFException("Missing UMP size.")
            consumed += length
            requireSafe(length <= MAX_PART && consumed <= 8L * 1024 * 1024 && ++count <= 2048)
            onPart(type, exact(input, length))
        }
    }
    fun requireSafe(condition: Boolean) { if (!condition) throw IOException("Invalid or excessive SABR protocol data.") }
}
