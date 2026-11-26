package cord.kasane.music.update

import cord.kasane.music.ota.ReleaseAsset
import cord.kasane.music.ota.ReleaseInfo
import cord.kasane.music.ota.SemVersion
import cord.kasane.music.ota.UpdateChannel
import cord.kasane.music.ota.UpdateChecker
import cord.kasane.music.ota.UpdateDownloader
import cord.kasane.music.ota.UpdateHttpTransport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-level contract for the Android OTA controller: channel→asset routing,
 * rate-limited startup checks, fail-closed checksum and signature gates, and the
 * grant-gate in front of the package-installer intent. Transport and all platform
 * seams are faked; the downloader runs against MockWebServer.
 */
class AndroidUpdatesTest {

    private val apkBytes = "fake-apk-payload".toByteArray()
    private val apkSha = sha256(apkBytes)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(version: String, prerelease: Boolean, assetName: String?,
        assetUrl: String = "https://files.invalid/x.apk", digest: String? = apkSha,
        body: String = "changes") = """[{
            "tag_name": "v$version",
            "name": "Kasane $version",
            "body": "$body",
            "html_url": "https://example.invalid/r/$version",
            "prerelease": $prerelease,
            "draft": false,
            "assets": ${if (assetName == null) "[]" else """[{"name":"$assetName","browser_download_url":"$assetUrl","size":${apkBytes.size}${if (digest != null) ""","digest":"sha256:$digest"""" else ""}}]"""}
        }]""".trimIndent()

    private class Harness(
        scope: CoroutineScope,
        channel: UpdateChannel,
        bodies: Map<String, String>,
        downloadDir: File,
        signatureOk: Boolean = true,
        installGranted: Boolean = true,
        lastCheckMs: Long = 0,
    ) {
        var httpCalls = 0
        var lastCheckWrite: Long = -1
        var installedFile: File? = null
        var permissionOpened = false
        val updates: AndroidUpdates

        init {
            val transport = UpdateHttpTransport { url, _ -> httpCalls++; bodies[url] }
            val checker = UpdateChecker(
                http = transport, channel = channel,
                current = SemVersion.parse("0.2.0-alpha")!!,
                nowMs = { 10_000L },
                readLastCheckMs = { lastCheckMs },
                writeLastCheckMs = { lastCheckWrite = it })
            updates = AndroidUpdates(
                scope = scope, checker = checker, channel = channel,
                downloader = UpdateDownloader(OkHttpClient()),
                updateDir = downloadDir,
                signatureMatches = { signatureOk },
                canRequestInstall = { installGranted },
                startInstall = { installedFile = it },
                openInstallPermission = { permissionOpened = true })
        }
    }

    private fun apiBody(body: String) =
        mapOf("https://api.github.com/repos/dustincorder/kasane/releases?per_page=30" to body)

    private fun state(u: AndroidUpdates) = u.state.value

    /** Terminal state after a check or download, or the current one when nothing is running. */
    private suspend fun awaitSettled(u: AndroidUpdates): AndroidUpdates.State =
        withTimeout(10_000) { u.state.first { it !is AndroidUpdates.State.Checking && it !is AndroidUpdates.State.Downloading } }

    private fun release(asset: ReleaseAsset?) = ReleaseInfo("v0.3.0", SemVersion.parse("0.3.0")!!,
        "Kasane 0.3.0", "", "", false, listOfNotNull(asset))

    @Test fun stableChannelSelectsStableApk() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE,
            apiBody(body = releaseJson("0.3.0", prerelease = false,
                assetName = "kasane-android-0.3.0.apk")), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkNow()
        val s = awaitSettled(h.updates) as? AndroidUpdates.State.Available
        assertEquals("kasane-android-0.3.0.apk", s?.asset?.name)
        assertTrue(h.lastCheckWrite > 0)
    }

    @Test fun stableChannelNeverOffersPrereleases() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE,
            apiBody(body = releaseJson("0.3.0-nightly", prerelease = true,
                assetName = "kasane-android-0.3.0-nightly.apk")), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkNow()
        assertEquals(AndroidUpdates.State.UpToDate, awaitSettled(h.updates))
    }

    @Test fun nightlyChannelSelectsNightlyAsset() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.NIGHTLY,
            apiBody(body = releaseJson("0.3.0-nightly", prerelease = true,
                assetName = "kasane-android-0.3.0-nightly.apk")), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkNow()
        val s = awaitSettled(h.updates) as? AndroidUpdates.State.Available
        assertEquals("kasane-android-0.3.0-nightly.apk", s?.asset?.name)
    }

    @Test fun nightlyAssetWithoutMarkerIsNotPicked() = runBlocking<Unit> {
        // A pre-release whose apk is not tagged "nightly" must not install on nightly.
        val h = Harness(this, UpdateChannel.NIGHTLY,
            apiBody(body = releaseJson("0.3.0-beta", prerelease = true,
                assetName = "kasane-android-0.3.0.apk")), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkNow()
        val s = awaitSettled(h.updates) as? AndroidUpdates.State.Available
        assertNull(s?.asset)
    }

    @Test fun startupCheckIsSilentAndRateLimited() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE,
            apiBody(body = releaseJson("0.3.0", prerelease = false, assetName = "kasane-android-0.3.0.apk")),
            downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile(), lastCheckMs = 10_000L) // just checked → inside the 24h window
        h.updates.checkOnStart()
        delay(200)
        assertEquals(AndroidUpdates.State.Idle, state(h.updates))
        assertEquals(0, h.httpCalls)
    }

    @Test fun startupCheckStaysSilentOnServerFailure() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkOnStart()
        delay(200)
        assertEquals(AndroidUpdates.State.Idle, state(h.updates)) // no error banner on launch
    }

    @Test fun manualCheckReportsServerFailure() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        h.updates.checkNow()
        assertTrue(awaitSettled(h.updates) is AndroidUpdates.State.Failed)
    }

    @Test fun downloadFailsClosedWithoutChecksum() = runBlocking<Unit> {
        val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = kotlin.io.path.createTempDirectory("ota-test").toFile())
        val asset = ReleaseAsset("kasane-android-0.3.0.apk", "https://files.invalid/a.apk", 10, null)
        h.updates.download(release(asset), asset)
        val s = awaitSettled(h.updates) as? AndroidUpdates.State.Failed
        assertTrue(s?.reason?.contains("checksum") == true)
    }

    @Test fun signatureMismatchDiscardsPackage() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("ota-test").toFile()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apkBytes)))
            val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = dir, signatureOk = false)
            val asset = ReleaseAsset("kasane-android-0.3.0.apk", server.url("/apk").toString(), apkBytes.size.toLong(), apkSha)
            h.updates.download(release(asset), asset)
            val s = awaitSettled(h.updates) as? AndroidUpdates.State.Failed
            assertTrue(s?.reason?.contains("signed") == true)
            assertEquals(0, dir.listFiles()?.size ?: 0) // discarded, not left on disk
        }
    }

    @Test fun verifiedPackageReachesReadyThenInstallIntent() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("ota-test").toFile()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apkBytes)))
            val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = dir)
            val asset = ReleaseAsset("kasane-android-0.3.0.apk", server.url("/apk").toString(), apkBytes.size.toLong(), apkSha)
            h.updates.download(release(asset), asset)
            val s = awaitSettled(h.updates) as? AndroidUpdates.State.ReadyToInstall
            assertTrue(s?.file?.isFile == true)
            h.updates.installNow()
            assertEquals(s?.file, h.installedFile)
            assertFalse(h.permissionOpened)
        }
    }

    @Test fun installWithoutGrantOpensUnknownSourcesSettings() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("ota-test").toFile()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apkBytes)))
            val h = Harness(this, UpdateChannel.STABLE, emptyMap(), downloadDir = dir, installGranted = false)
            val asset = ReleaseAsset("kasane-android-0.3.0.apk", server.url("/apk").toString(), apkBytes.size.toLong(), apkSha)
            h.updates.download(release(asset), asset)
            assertTrue(awaitSettled(h.updates) is AndroidUpdates.State.ReadyToInstall)
            h.updates.installNow()
            assertTrue(h.permissionOpened)
            assertNull(h.installedFile) // no sideload attempt without the grant
        }
    }
}
