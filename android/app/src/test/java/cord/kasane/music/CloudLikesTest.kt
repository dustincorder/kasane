package cord.kasane.music

import cord.kasane.music.provider.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudLikesTest {
    @Test fun statusTargetsExactVideoAndUnknownIsNotNeutral() {
        val root = JSONObject("""{"likeButtonRenderer":{"target":{"videoId":"other"},"likeStatus":"LIKE"}}""")
        assertEquals(CloudLikeState.UNKNOWN, CloudLikeParser.parse(root, "wanted"))
        assertEquals(CloudLikeState.LIKED, CloudLikeParser.parse(root, "other"))
        assertEquals(CloudLikeState.UNKNOWN, CloudLikeParser.parse(JSONObject(), "wanted"))
    }
    @Test fun overlayReportsDislikeAndNeutral() {
        for ((status, expected) in listOf("DISLIKE" to CloudLikeState.DISLIKED, "INDIFFERENT" to CloudLikeState.NEUTRAL)) {
            val root = JSONObject("""{"playerOverlays":{"likeButtonRenderer":{"likeStatus":"$status"}}}""")
            assertEquals(expected, CloudLikeParser.parse(root, "wanted"))
        }
    }
    @Test fun refreshIsSingleFlightAndCachedButForceReadsAgain() = runTest {
        var reads = 0
        val store = CloudLikes({ reads++; CloudLikeState.LIKED }, { _, _ -> }, now = { 1000 }, dispatcher = StandardTestDispatcher(testScheduler))
        store.refresh("id"); store.refresh("id"); advanceUntilIdle()
        assertEquals(1, reads); assertEquals(CloudLikeState.LIKED, store.states.value["id"])
        store.refresh("id"); advanceUntilIdle(); assertEquals(1, reads)
        store.refresh("id", force = true); advanceUntilIdle(); assertEquals(2, reads)
        store.close()
    }
    @Test fun optimisticMutationRollsBackOnFailureAndRepeatedPressDoesNotSendTwice() = runTest {
        var writes = 0
        val gate = CompletableDeferred<Unit>()
        val store = CloudLikes({ CloudLikeState.NEUTRAL }, { _, _ -> writes++; gate.await(); error("network") }, dispatcher = StandardTestDispatcher(testScheduler))
        store.refresh("id"); advanceUntilIdle()
        store.toggle("id"); store.toggle("id")
        assertEquals(CloudLikeState.LIKED, store.states.value["id"])
        runCurrent(); assertEquals(1, writes)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(CloudLikeState.NEUTRAL, store.states.value["id"])
        assertTrue(store.pending.value.isEmpty()); store.close()
    }
    @Test fun libraryHintCannotOverwriteServerNeutralAndSessionsAreIsolated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = CloudLikes({ CloudLikeState.NEUTRAL }, { _, _ -> }, dispatcher = dispatcher)
        first.seedLiked(listOf("liked")); assertEquals(CloudLikeState.LIKED, first.states.value["liked"])
        first.refresh("liked", true); advanceUntilIdle()
        first.seedLiked(listOf("liked")); assertEquals(CloudLikeState.NEUTRAL, first.states.value["liked"])
        first.close()
        val second = CloudLikes({ CloudLikeState.UNKNOWN }, { _, _ -> }, dispatcher = dispatcher)
        assertTrue(second.states.value.isEmpty()); second.close()
    }
    @Test fun dislikeAndRemovalAreExplicitAndSeparateFromLike() = runTest {
        val operations = mutableListOf<Boolean>()
        val store = CloudLikes({ CloudLikeState.NEUTRAL }, { _, _ -> error("Like must not be called") },
            writeDislike = { _, enabled -> operations.add(enabled) }, dispatcher = StandardTestDispatcher(testScheduler))
        store.toggleDislike("id"); advanceUntilIdle()
        assertEquals(CloudLikeState.DISLIKED, store.states.value["id"])
        store.toggleDislike("id"); advanceUntilIdle()
        assertEquals(CloudLikeState.NEUTRAL, store.states.value["id"])
        assertEquals(listOf(true, false), operations); store.close()
    }
    @Test fun failedOrUnknownRefreshKeepsCachedLikedSongsHint() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = CloudLikes({ gate.await(); error("offline") }, { _, _ -> }, dispatcher = StandardTestDispatcher(testScheduler))
        store.refresh("id"); runCurrent(); store.seedLiked(listOf("id"))
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(CloudLikeState.LIKED, store.states.value["id"]); store.close()
    }

}
