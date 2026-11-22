package cord.kasane.music.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-visible notes about the source a track is playing from (e.g. a fallback). */
class PlaybackNotices {
    data class Notice(val videoId: String, val message: String)

    private val mutable = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = mutable.asStateFlow()

    fun post(videoId: String, message: String) { mutable.value = Notice(videoId, message) }
    fun clear(videoId: String) { mutable.compareAndSet(mutable.value?.takeIf { it.videoId == videoId }, null) }

    companion object {
        val shared = PlaybackNotices()
    }
}
