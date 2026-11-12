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
    private val progressive: MediaSource.Factory,
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
            } catch (e: StreamUnavailableException) { UnavailableStream(e.message ?: "This track is unavailable.")
            } catch (_: Exception) { UnavailableStream("Could not resolve audio. Check your connection and retry.") }
            handler.post {
                if (current != generation) return@post
                when (result) {
                    is HlsStream -> {
                        val resolved = item.buildUpon().setUri(result.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                        child = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(network)
                            .setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy()).createMediaSource(resolved)
                    }
                    is ProgressiveStream -> child = progressive.createMediaSource(item)
                    is UnavailableStream -> failure = IOException(result.reason)
                }
                child?.let { prepareChildSource(Unit, it) }
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
        if (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in listOf(401, 403, 404, 410)) return C.TIME_UNSET
        if (info.errorCount > 2) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}
