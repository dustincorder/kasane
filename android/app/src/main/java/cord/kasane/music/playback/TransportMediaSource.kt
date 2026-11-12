package cord.kasane.music.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import cord.kasane.music.provider.*
import kotlinx.coroutines.*
import java.io.IOException

/** Resolves on IO before selecting a protocol; the queue identity never contains a signed URL. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TransportMediaSource(
    private val item: MediaItem,
    private val resolve: suspend (String) -> PlaybackStream,
    private val network: DataSource.Factory,
) : CompositeMediaSource<Unit>() {
    private var child: MediaSource? = null
    private var job: Job? = null
    private var failure: IOException? = null
    private var generation = 0
    override fun getMediaItem(): MediaItem = item
    override fun prepareSourceInternal(listener: TransferListener?) {
        super.prepareSourceInternal(listener)
        val handler = Handler(requireNotNull(Looper.myLooper()))
        val current = ++generation
        job = CoroutineScope(Dispatchers.IO).launch {
            val result = try { resolve(item.mediaId) } catch (e: CancellationException) { throw e
            } catch (e: Exception) { playbackFailure(e) }
            handler.post {
                if (current != generation) return@post
                when (result) {
                    is HlsStream -> {
                        val resolved = item.buildUpon().setUri(result.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                        child = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(network)
                            .setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy()).createMediaSource(resolved)
                    }
                    is ProgressiveStream -> child = ProgressiveMediaSource.Factory(network)
                        .setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy()).createMediaSource(
                            item.buildUpon().setUri(result.audio.url).setMimeType(result.audio.mimeType.substringBefore(';').trim()).build())
                    is UnavailableStream -> failure = TransportUnavailableException(result.reason)
                }
                val profile = if (result is HlsStream) result.clientProfile else if (result is ProgressiveStream) result.clientProfile else "none"
                val transport = if (result is HlsStream) "HLS" else if (result is ProgressiveStream) "PROGRESSIVE" else "UNAVAILABLE"
                android.util.Log.i("KasanePlayback", "videoId=${item.mediaId} client=$profile transport=$transport reason=${if (result is UnavailableStream) result.code else "selected"} mime=${if (result is HlsStream) MimeTypes.APPLICATION_M3U8 else if (result is ProgressiveStream) result.audio.mimeType else "unknown"}")
                child?.let { source ->
                    var loadIndex = 0
                    val failures = mutableMapOf<Long, Int>()
                    source.addEventListener(handler, object : MediaSourceEventListener {
                        override fun onLoadCompleted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, info: LoadEventInfo, data: MediaLoadData) {
                            loadIndex++
                            val recoveredAttempts = failures.remove(info.loadTaskId) ?: 0
                            android.util.Log.i("KasanePlayback", "videoId=${item.mediaId} client=$profile transport=$transport load=$loadIndex http=2xx recoveryAttempts=$recoveredAttempts dataType=${data.dataType} segmentStartMs=${data.mediaStartTimeMs} offset=${info.dataSpec.position + info.bytesLoaded} bytes=${info.bytesLoaded} durationMs=${info.loadDurationMs}")
                        }
                        override fun onLoadError(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                            val attempt = (failures[info.loadTaskId] ?: 0) + 1
                            failures[info.loadTaskId] = attempt
                            val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                            android.util.Log.w("KasanePlayback", "videoId=${item.mediaId} client=$profile transport=$transport load=$loadIndex task=${info.loadTaskId} attempt=$attempt recoveryAttempts=${attempt - 1} segmentStartMs=${data.mediaStartTimeMs} offset=${info.dataSpec.position + info.bytesLoaded} bytes=${info.bytesLoaded} http=${http?.responseCode ?: -1} error=${error.javaClass.simpleName} canceled=$wasCanceled")
                        }
                    })
                    prepareChildSource(Unit, source)
                }
            }
        }
    }
    override fun onChildSourceInfoRefreshed(id: Unit, source: MediaSource, timeline: Timeline) {
        refreshSourceInfo(object : ForwardingTimeline(timeline) {
            override fun getWindow(index: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
                super.getWindow(index, window, defaultPositionProjectionUs)
                window.mediaItem = item // Never expose/persist a signed manifest URI as the queue item.
                return window
            }
        })
    }
    override fun maybeThrowSourceInfoRefreshError() { failure?.let { throw it }; super.maybeThrowSourceInfoRefreshError() }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        requireNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) { child?.releasePeriod(mediaPeriod) }
    override fun releaseSourceInternal() {
        generation++; job?.cancel(); job = null
        super.releaseSourceInternal(); child = null; failure = null
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BoundedStreamLoadPolicy : DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = info.exception
        if (!PlaybackRetryPolicy.canRetry((error as? HttpDataSource.InvalidResponseCodeException)?.responseCode, info.errorCount)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}
