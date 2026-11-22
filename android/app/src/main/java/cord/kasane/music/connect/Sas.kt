package cord.kasane.music.connect

import java.security.MessageDigest

/** Pairing commitment and numeric-comparison code (docs/connect/protocol.md). */
object Sas {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    /** SHA256("kasane-commit-v1" || nc) */
    fun commit(nc: ByteArray): ByteArray =
        sha256("kasane-commit-v1".toByteArray(Charsets.UTF_8), nc)

    /**
     * u32_be(SHA256("kasane-pair-v1" || fpS || fpC || ns || nc)[0..4]) mod 1_000_000,
     * zero-padded to six digits.
     */
    fun code(serverFp: ByteArray, clientFp: ByteArray, ns: ByteArray, nc: ByteArray): String {
        val digest = sha256(
            "kasane-pair-v1".toByteArray(Charsets.UTF_8), serverFp, clientFp, ns, nc
        )
        val value = (digest[0].toLong() and 0xff shl 24) or
            (digest[1].toLong() and 0xff shl 16) or
            (digest[2].toLong() and 0xff shl 8) or
            (digest[3].toLong() and 0xff)
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray? {
    if (length % 2 != 0 || !all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
