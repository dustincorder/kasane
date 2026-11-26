package cord.kasane.music.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import cord.kasane.music.ota.OkHttpUpdateTransport
import cord.kasane.music.ota.ReleaseAsset
import cord.kasane.music.ota.ReleaseInfo
import cord.kasane.music.ota.SemVersion
import cord.kasane.music.ota.UpdateChannel
import cord.kasane.music.ota.UpdateChecker
import cord.kasane.music.ota.UpdateDownloader
import cord.kasane.music.ota.UpdateOutcome
import cord.kasane.music.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android OTA glue over the shared [UpdateChecker]/[UpdateDownloader]: startup check
 * (rate-limited by the checker), manual check from Settings, download with a mandatory
 * SHA-256 proof, and install through the system package-installer intent — never a
 * silent sideload. A candidate APK is only offered when it is signed with the same
 * key as the running build, so unsigned/debug artifacts can never replace Stable.
 *
 * All platform seams are injected — the controller is a plain-JVM state machine.
 */
class AndroidUpdates(
    private val scope: CoroutineScope,
    private val checker: UpdateChecker,
    private val channel: UpdateChannel,
    private val downloader: UpdateDownloader,
    private val updateDir: File,
    private val signatureMatches: (File) -> Boolean,
    private val canRequestInstall: () -> Boolean,
    private val startInstall: (File) -> Unit,
    private val openInstallPermission: () -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        /** A newer release exists; [asset] may be null when nothing matches this platform. */
        data class Available(val release: ReleaseInfo, val asset: ReleaseAsset?) : State
        data class Downloading(val release: ReleaseInfo, val doneBytes: Long, val totalBytes: Long) : State
        /** APK downloaded, checksum and signature verified — safe to hand to the installer. */
        data class ReadyToInstall(val release: ReleaseInfo, val file: File) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    /**
     * Asset names carry the product and channel (`kasane-android-<ver>[-nightly].apk`):
     * stable never picks a nightly artifact and vice versa.
     */
    private fun assetMatchers(): List<String> = when (channel) {
        UpdateChannel.NIGHTLY -> listOf("android", ".apk", "nightly")
        UpdateChannel.STABLE -> listOf("android", ".apk", "-nightly")
    }

    /** Startup check: silent unless an update is actually downloadable. */
    fun checkOnStart() {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.ReadyToInstall) return
        scope.launch {
            when (val outcome = checker.checkIfDue(assetMatchers())) {
                is UpdateOutcome.Available -> _state.value = State.Available(outcome.release, outcome.asset)
                else -> Unit // Skipped/UpToDate/Failed stay silent on startup
            }
        }
    }

    /** Manual "check for updates" — always hits the API and reports every outcome. */
    fun checkNow() {
        if (_state.value is State.Checking) return
        _state.value = State.Checking
        scope.launch {
            _state.value = when (val outcome = checker.checkNow(assetMatchers())) {
                is UpdateOutcome.Available -> State.Available(outcome.release, outcome.asset)
                is UpdateOutcome.UpToDate -> State.UpToDate
                is UpdateOutcome.Skipped -> State.Idle
                is UpdateOutcome.Failed -> State.Failed(outcome.reason)
            }
        }
    }

    /**
     * Download → verify SHA-256 (mandatory) → verify the signature matches this
     * build → only then offer install. Anything unverifiable is discarded.
     */
    fun download(release: ReleaseInfo, asset: ReleaseAsset?) {
        if (asset == null) {
            _state.value = State.Failed("This release has no Android package attached.")
            return
        }
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release, 0, asset.sizeBytes)
        scope.launch {
            val sha256 = checker.resolveChecksum(asset, release)
            if (sha256 == null) {
                _state.value = State.Failed("No checksum published for this build — not installing an unverifiable package.")
                return@launch
            }
            _state.value = when (val result = downloader.download(asset, updateDir, sha256) { done, total ->
                _state.value = State.Downloading(release, done, total)
            }) {
                is UpdateDownloader.Result.Done ->
                    if (signatureMatches(result.file)) State.ReadyToInstall(release, result.file)
                    else { result.file.delete(); State.Failed("The downloaded package is signed by a different key — it was discarded.") }
                is UpdateDownloader.Result.Failed -> State.Failed(result.reason)
            }
        }
    }

    /** User tapped Install: grant-gate first, then hand the verified APK to the installer. */
    fun installNow() {
        val s = _state.value as? State.ReadyToInstall ?: return
        if (canRequestInstall()) startInstall(s.file) else openInstallPermission()
    }

    /** Close the update card/dialog; a ReadyToInstall file survives for the next attempt. */
    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    companion object {

        /** True only when [apk] carries exactly the same single signer as this package. */
        fun packageSignatureMatches(context: Context, apk: File): Boolean {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            fun signers(info: PackageInfo?): List<ByteArray> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    info?.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
                else @Suppress("DEPRECATION") info?.signatures?.map { it.toByteArray() } ?: emptyList()
            val mine = runCatching { signers(pm.getPackageInfo(context.packageName, flags)) }.getOrNull() ?: return false
            val theirs = runCatching { signers(pm.getPackageArchiveInfo(apk.absolutePath, flags)) }.getOrNull() ?: return false
            return mine.size == 1 && theirs.size == 1 && mine[0].contentEquals(theirs[0])
        }

        fun create(
            context: Context,
            client: okhttp3.OkHttpClient,
            scope: CoroutineScope,
            readLastCheckMs: () -> Long,
            writeLastCheckMs: (Long) -> Unit,
        ): AndroidUpdates {
            val channel = if (BuildConfig.UPDATE_CHANNEL == "nightly") UpdateChannel.NIGHTLY else UpdateChannel.STABLE
            val checker = UpdateChecker(
                http = OkHttpUpdateTransport(client),
                channel = channel,
                current = SemVersion.parse(BuildConfig.VERSION_NAME) ?: SemVersion(intArrayOf(0), emptyList()),
                nowMs = { System.currentTimeMillis() },
                readLastCheckMs = readLastCheckMs,
                writeLastCheckMs = writeLastCheckMs,
            )
            val app = context.applicationContext
            return AndroidUpdates(
                scope = scope,
                checker = checker,
                channel = channel,
                downloader = UpdateDownloader(client),
                updateDir = File(app.cacheDir, "updates"),
                signatureMatches = { packageSignatureMatches(app, it) },
                canRequestInstall = { app.packageManager.canRequestPackageInstalls() },
                startInstall = { file ->
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    app.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    })
                },
                openInstallPermission = {
                    app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
    }
}
