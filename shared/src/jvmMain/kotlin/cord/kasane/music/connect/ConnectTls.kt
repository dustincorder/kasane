package cord.kasane.music.connect

import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS 1.3 mutual auth with self-signed certificates. Certificate and hostname
 * checks are intentionally disabled; the peer leaf fingerprint is captured
 * here and enforced at the application layer (trust store / SAS pairing).
 */
class ConnectTls(private val identity: ConnectIdentity) {

    private inner class IdentityKeyManager : X509ExtendedKeyManager() {
        private val alias = "kasane"
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) =
            arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) =
            alias
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) =
            arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) =
            alias
        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) =
            alias
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) =
            alias
        override fun getCertificateChain(alias: String?) =
            arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    }

    private inner class RecordingTrustManager : X509ExtendedTrustManager() {
        @Volatile var lastPeer: Array<X509Certificate>? = null
            private set

        private fun capture(chain: Array<out X509Certificate>?) {
            if (!chain.isNullOrEmpty()) lastPeer = chain.copyOf() as Array<X509Certificate>
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            capture(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            capture(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            capture(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            capture(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            capture(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            capture(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustManager = RecordingTrustManager()

    val context: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(arrayOf(IdentityKeyManager()), arrayOf(trustManager), null)
    }

    fun serverSocket(port: Int): SSLServerSocket {
        // Bind IPv4 explicitly like the Rust peer: a dual-stack "::" socket can share
        // the port with another process's 0.0.0.0 listener, and IPv4 clients then
        // reach that process instead of us.
        val socket = context.serverSocketFactory.createServerSocket(
            port, 50, java.net.InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.needClientAuth = true
        return socket
    }

    fun clientSocket(): SSLSocket {
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        return socket
    }

    /** Lowercase hex SHA-256 fingerprint of the leaf presented to [socket]. */
    fun peerFingerprint(socket: SSLSocket): String? {
        val chain = try {
            socket.session.peerCertificates
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            null
        } ?: trustManager.lastPeer
        val leaf = chain?.filterIsInstance<X509Certificate>()?.firstOrNull() ?: return null
        return Sas.sha256(leaf.encoded).hex()
    }
}
