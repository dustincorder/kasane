package cord.kasane.desktop.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real mpv process over JSON IPC against a generated WAV — the same load/seek/pause/end
 * path the player drives, no mocks. Skipped where the `mpv` binary is absent.
 */
class MpvEngineTest {
    private fun mpvAvailable() = runCatching {
        ProcessBuilder("mpv", "--version").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
    }.getOrDefault(false)

    /** A mono 8 kHz 8-bit PCM WAV of [seconds] of silence — every player decodes it. */
    private fun wav(seconds: Int): File {
        val rate = 8_000
        val data = ByteArray(seconds * rate) { 128.toByte() }
        val file = createTempDirectory("mpv-test").resolve("tone.wav").toFile()
        file.outputStream().use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            ascii("RIFF"); int(36 + data.size); ascii("WAVE")
            ascii("fmt "); int(16); short(1); short(1); int(rate); int(rate); short(1); short(8)
            ascii("data"); int(data.size); out.write(data)
        }
        return file
    }

    @Test fun `loads plays seeks and pauses a local file`() {
        if (!mpvAvailable()) return
        runBlocking {
            MpvEngine(audioOutput = "null").use { engine ->
                engine.load(wav(120).absolutePath, null, 0.0, play = true)
                val started = withTimeout(10_000) { engine.status.first { it.durationSec > 0 && it.positionSec > 0.2 } }
                assertTrue(started.durationSec > 100)
                engine.seek(60.0)
                withTimeout(10_000) { engine.status.first { it.positionSec >= 55.0 } }
                engine.setPaused(true)
                withTimeout(5_000) { engine.status.first { it.paused } }
            }
        }
    }

    @Test fun `reports end of file as an event`() {
        if (!mpvAvailable()) return
        runBlocking {
            MpvEngine(audioOutput = "null").use { engine ->
                engine.load(wav(1).absolutePath, null, 0.0, play = true)
                withTimeout(15_000) { engine.events.first { it is AudioEngine.Event.Ended } }
            }
        }
    }
}
