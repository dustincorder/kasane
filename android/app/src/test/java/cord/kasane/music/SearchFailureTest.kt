package cord.kasane.music

import cord.kasane.music.provider.YouTubeMusicProvider
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SearchFailureTest {
    @Test fun transportFailureRemainsDifferentFromNoResults() = runTest {
        val provider = YouTubeMusicProvider(OkHttpClient.Builder().addInterceptor {
            throw IOException("offline")
        }.build())
        try {
            provider.search("a song", 25)
            fail("A transport failure must reach the UI error state")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("connection"))
        }
    }

    @Test fun successfulEmptySearchIsAnEmptyResult() = runTest {
        val provider = YouTubeMusicProvider(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build())
        assertTrue(provider.search("a song", 25).isEmpty())
    }
}
