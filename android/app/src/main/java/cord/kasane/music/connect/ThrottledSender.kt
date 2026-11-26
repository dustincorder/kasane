package cord.kasane.music.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces a fast value stream (e.g. a dragged volume slider) into at most one
 * delivery per [intervalMs]: intermediate values are dropped, the newest always
 * wins, and the final submitted value is always delivered — the wire never floods.
 */
class ThrottledSender<T>(
    scope: CoroutineScope,
    intervalMs: Long,
    private val deliver: suspend (T) -> Unit,
) {
    private val inbox = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in inbox) {
                runCatching { deliver(value) }
                delay(intervalMs)
            }
        }
    }

    fun submit(value: T) {
        inbox.trySend(value)
    }
}
