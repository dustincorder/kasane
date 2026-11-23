package cord.kasane.music.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One cancellable, bounded HTTP adapter shared by both JVM consumers. */
class OkHttpCatalogTransport(client: OkHttpClient) : CatalogHttpTransport {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun execute(request: CatalogHttpRequest): CatalogHttpResponse = suspendCancellableCoroutine { continuation ->
        val url = request.baseUrl.toHttpUrl().newBuilder().addPathSegments("youtubei/v1/${request.endpoint}")
        request.query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val builder = Request.Builder().url(url.build()).post(request.body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(CatalogFailure(CatalogFailureReason.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = if (it.isSuccessful) {
                            val source = it.body?.source() ?: throw CatalogFailure(CatalogFailureReason.EMPTY_RESPONSE)
                            source.request(InnerTubeClient.MAX_RESPONSE_BYTES.toLong() + 1)
                            if (source.buffer.size > InnerTubeClient.MAX_RESPONSE_BYTES) throw CatalogFailure(CatalogFailureReason.RESPONSE_TOO_LARGE)
                            source.readUtf8()
                        } else ""
                        if (continuation.isActive) continuation.resume(CatalogHttpResponse(it.code, body))
                    }
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is CatalogFailure -> failure
                        is IOException -> CatalogFailure(CatalogFailureReason.NETWORK)
                        else -> CatalogFailure(CatalogFailureReason.INVALID_RESPONSE)
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }
}

object JvmCatalogCrypto {
    fun sha1Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
