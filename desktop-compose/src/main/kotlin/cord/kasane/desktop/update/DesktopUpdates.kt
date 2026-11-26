package cord.kasane.desktop.update

import cord.kasane.desktop.BuildInfo
import cord.kasane.desktop.DesktopSettings
import cord.kasane.music.ota.OkHttpUpdateTransport
import cord.kasane.music.ota.ReleaseAsset
import cord.kasane.music.ota.ReleaseInfo
import cord.kasane.music.ota.SemVersion
import cord.kasane.music.ota.UpdateChecker
import cord.kasane.music.ota.UpdateDownloader
import cord.kasane.music.ota.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Update checks and installs for the desktop client over the shared OTA module.
 * Auto-check fires once a day from the UI path only; the install step always runs the
 * tarball's own `install.sh` after an explicit user action — never a silent self-replace.
 */
class DesktopUpdates(
    private val scope: CoroutineScope,
    http: OkHttpClient,
    private val settings: DesktopSettings,
    private val updatesDir: File,
    private val downloader: UpdateDownloader = UpdateDownloader(http),
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val release: ReleaseInfo, val asset: ReleaseAsset?) : State
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : State
        /** The tarball's install.sh ran; the new version takes over on the next launch. */
        data class Installed(val release: ReleaseInfo, val detail: String) : State
        data class Failed(val message: String) : State
    }

    private val checker = UpdateChecker(
        http = OkHttpUpdateTransport(http),
        channel = BuildInfo.updateChannel,
        current = SemVersion.parse(BuildInfo.version) ?: SemVersion(intArrayOf(0), emptyList()),
        nowMs = { System.currentTimeMillis() },
        readLastCheckMs = { settings.prefs.value.lastUpdateCheckMs },
        writeLastCheckMs = { ms -> settings.update { it.copy(lastUpdateCheckMs = ms) } },
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called once from the UI path (not smoke/verifier runs): honors the daily rate limit. */
    fun checkOnStart() {
        scope.launch { fold(checker.checkIfDue(BuildInfo.assetMatchers)) }
    }

    /** Settings "Check for updates" — always queries GitHub. */
    fun checkNow() {
        scope.launch {
            _state.value = State.Checking
            fold(checker.checkNow(BuildInfo.assetMatchers))
        }
    }

    private fun fold(outcome: UpdateOutcome) {
        _state.value = when (outcome) {
            is UpdateOutcome.Available -> State.Available(outcome.release, outcome.asset)
            is UpdateOutcome.UpToDate -> State.UpToDate(outcome.latest?.tag ?: BuildInfo.version)
            is UpdateOutcome.Skipped -> _state.value // the daily window holds: keep whatever was shown
            is UpdateOutcome.Failed -> State.Failed(outcome.reason)
        }
    }

    /**
     * Download → SHA-256 verify → unpack → run the tarball's `install.sh` (the existing
     * install path: rsync into `~/.local/opt/<install>`, desktop entry, icon caches).
     * Missing checksum aborts the download — unverified archives are never installed.
     */
    fun downloadAndInstall(release: ReleaseInfo, asset: ReleaseAsset) {
        scope.launch {
            val sha = checker.resolveChecksum(asset, release)
            if (sha == null) {
                _state.value = State.Failed(
                    "No checksum published for ${asset.name} — refusing to install an unverified download. " +
                        "Open the release page to update manually.")
                return@launch
            }
            val result = downloader.download(asset, updatesDir, expectedSha256 = sha) { done, total ->
                _state.value = State.Downloading(done, total)
            }
            when (result) {
                is UpdateDownloader.Result.Failed -> _state.value = State.Failed(result.reason)
                is UpdateDownloader.Result.Done -> installFrom(result.file, release)
            }
        }
    }

    private suspend fun installFrom(archive: File, release: ReleaseInfo) {
        val unpacked = File(updatesDir, "unpacked").apply { deleteRecursively(); mkdirs() }
        val untar = runProcess(listOf("tar", "-xzf", archive.absolutePath, "-C", unpacked.absolutePath))
        val installer = unpacked.listFiles()
            ?.firstOrNull { File(it, "install.sh").isFile }
            ?.let { File(it, "install.sh") }
            ?: unpacked.listFiles()?.firstOrNull { it.name == "install.sh" }
        if (untar.first != 0 || installer == null) {
            _state.value = State.Failed(
                "The archive has no installer — unpacked to ${unpacked.absolutePath}, install it manually.")
            return
        }
        val install = runProcess(listOf("sh", installer.absolutePath), cwd = installer.parentFile)
        _state.value = if (install.first == 0) {
            State.Installed(release, install.second.trim().ifBlank { "Restart ${BuildInfo.appName} to run the new version." })
        } else {
            State.Failed("The installer failed: ${install.second.trim().ifBlank { "exit ${install.first}" }}")
        }
    }

    private suspend fun runProcess(command: List<String>, cwd: File? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder(command).apply { cwd?.let(::directory); redirectErrorStream(true) }.start()
                p.inputStream.bufferedReader().readText().let { p.waitFor() to it }
            }.getOrElse { -1 to it.message.orEmpty() }
        }
}
