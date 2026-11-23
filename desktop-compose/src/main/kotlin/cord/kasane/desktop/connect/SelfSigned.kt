package cord.kasane.desktop.connect

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A self-signed X.509 v3 certificate for a fresh ECDSA P-256 key, built from
 * public JDK APIs only (the JDK has no certificate builder, and the bundled
 * runtime has no keytool). Peers pin its SHA-256 fingerprint; nothing else in
 * the certificate is trusted, so it carries only the fields X.509 requires.
 */
internal object SelfSigned {
    private val ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun create(commonName: String, days: Long = 3650): Pair<KeyPair, X509Certificate> {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val name = seq(set(seq(COMMON_NAME + tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val algorithm = seq(ECDSA_SHA256)
        val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name,
            seq(time(now.minusDays(1)), time(now.plusDays(days))),
            name,
            keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() }
        val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        cert.verify(keys.public)
        return keys to cert
    }

    private fun time(t: ZonedDateTime): ByteArray =
        if (t.year in 1950..2049) tlv(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(t).toByteArray(Charsets.US_ASCII))
        else tlv(0x18, DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(t).toByteArray(Charsets.US_ASCII))

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
