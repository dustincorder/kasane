package cord.kasane.desktop.audio

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The playback backend port. Kasane owns the queue and hands the engine one resolved URL at
 * a time; decoding, buffering and output belong to the implementation ([MpvEngine] in the app,
 * a fake in tests).
 */
interface AudioEngine : AutoCloseable {
    data class Status(
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val paused: Boolean = true,
        val buffering: Boolean = false,
        val idle: Boolean = true,
        val volume: Double = 100.0,
    )

    sealed interface Event {
        data object Ended : Event
        data class Failed(val reason: String) : Event
    }

    val status: StateFlow<Status>
    val events: SharedFlow<Event>

    /** Play [url] from [startSec]; [userAgent] must match the client the URL was issued to. */
    fun load(url: String, userAgent: String?, startSec: Double, play: Boolean)
    fun setPaused(paused: Boolean)
    fun seek(sec: Double)
    fun setVolume(percent: Double)
    fun stop()
    override fun close()
}
