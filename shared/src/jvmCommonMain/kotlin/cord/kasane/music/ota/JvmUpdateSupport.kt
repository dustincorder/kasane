package cord.kasane.music.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** OkHttp-backed transport for [UpdateChecker]. */
class OkHttpUpdateTransport(private val client: OkHttpClient) : UpdateHttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.get().build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }
}

object Sha256 {
    fun hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Streams a release asset to disk and verifies it: size (when the API reported one) and
 * SHA-256 (when a checksum was resolved). A failed verification deletes the file —
 * an unverified or corrupt archive is never offered for install.
 */
class UpdateDownloader(private val client: OkHttpClient) {

    sealed interface Result {
        data class Done(val file: File, val sha256: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun download(
        asset: ReleaseAsset,
        destDir: File,
        expectedSha256: String?,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val part = File(destDir, asset.name + ".part")
        destDir.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val streamed = runCatching {
            client.newCall(Request.Builder().url(asset.downloadUrl).get().build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    r.body?.byteStream()?.use { input ->
                        part.outputStream().buffered().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                onProgress?.invoke(done, asset.sizeBytes)
                            }
                        }
                    } ?: return@use false
                    true
                }
        }.getOrElse { part.delete(); return@withContext Result.Failed("Download failed — check your connection.") }
        if (!streamed) { part.delete(); return@withContext Result.Failed("Download failed — check your connection.") }
        if (asset.sizeBytes >= 0 && done != asset.sizeBytes) {
            part.delete()
            return@withContext Result.Failed("Download was truncated (${done} of ${asset.sizeBytes} bytes).")
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            return@withContext Result.Failed("Checksum mismatch — the download was discarded.")
        }
        val final = File(destDir, asset.name)
        if (!part.renameTo(final)) part.copyTo(final, overwrite = true).also { part.delete() }
        Result.Done(final, actual)
    }
}
