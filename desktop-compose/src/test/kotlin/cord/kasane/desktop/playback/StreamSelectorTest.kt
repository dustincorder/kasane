package cord.kasane.desktop.playback

import cord.kasane.desktop.audio.StreamProxy
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.IosHlsResolver
import cord.kasane.music.provider.verification.VerificationException
import cord.kasane.music.provider.verification.VerificationRuntime
import cord.kasane.music.provider.verification.VerifiedPlayerResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * `resolveForSave` must never touch the anonymous guest path: Android's save chain stores
 * only session-progressive or verified streams because YouTube stops anonymous progressive
 * downloads part-way. The trap HTTP client fails the test if it is ever used.
 */
class StreamSelectorTest {
    private fun trapHttp() = OkHttpClient.Builder().addInterceptor {
        throw AssertionError("anonymous resolver must not be used for offline saves")
    }.build()

    private inner class Rig(verified: VerifiedPlayerResolver?) : AutoCloseable {
        private val proxy = StreamProxy(OkHttpClient(), GuestPlayerResolver(OkHttpClient()))
        val selector = StreamSelector(proxy, GuestPlayerResolver(trapHttp()), IosHlsResolver(trapHttp()), verified)
        override fun close() = proxy.close()
    }

    @Test fun `resolveForSave returns null without a verified resolver`() = Rig(null).use { rig ->
        runBlocking { assertNull(rig.selector.resolveForSave("abcdefghij1")) }
    }

    @Test fun `resolveForSave returns null when verification is unavailable`() {
        val broken = VerifiedPlayerResolver(trapHttp(), object : VerificationRuntime {
            override suspend fun call(method: String, args: JSONArray): Any =
                throw VerificationException("no runtime")
            override val userAgent: String get() = ""
        })
        Rig(broken).use { rig -> runBlocking { assertNull(rig.selector.resolveForSave("abcdefghij1")) } }
    }
}
