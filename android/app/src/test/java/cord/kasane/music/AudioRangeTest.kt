package cord.kasane.music
import cord.kasane.music.playback.AudioRange
import cord.kasane.music.playback.CdnRecoveryBudget
import org.junit.Assert.*
import org.junit.Test
class AudioRangeTest {
    @Test fun unknownLengthStillUsesBoundedRange() { assertEquals(524288L, AudioRange.length(-1)) }
    @Test fun knownLengthUsesNoMoreThanOneChunk() { assertEquals(524288L, AudioRange.length(9999999)) }
    @Test fun lastChunkNeverExtendsPastResource() { assertEquals(123L, AudioRange.length(123)) }
    @Test fun noDataMeansNoMoreRequests() { assertEquals(0L, AudioRange.length(0)) }
    @Test fun readsResourceLengthFromContentRange() { assertEquals(99999L, AudioRange.totalLength("bytes 0-1023/99999")) }
    @Test fun unknownServerTotalStaysUnknown() { assertNull(AudioRange.totalLength("bytes 0-1023/*")) }
    @Test fun seekUsesAbsoluteOffsetExactlyOnceInQuery() {
        assertEquals("https://audio.example.test/audio?signature=test&range=524288-1048575",
            AudioRange.url("https://audio.example.test/audio?signature=test", 524288, 524288))
    }
    @Test fun replacesExistingRangeRatherThanDuplicatingIt() {
        assertEquals("https://audio.example.test/audio?range=10-29",
            AudioRange.url("https://audio.example.test/audio?range=0-9", 10, 20))
    }
    @Test fun nextChunkOffsetIncludesBytesReadAndOriginalSeekExactlyOnce() {
        assertEquals(1_048_676L, AudioRange.absoluteOffset(100, 2L * AudioRange.CHUNK_BYTES))
    }
    @Test fun recoveryBudgetAllowsTwoRefreshesThenFailsUntilProgress() {
        val budget = CdnRecoveryBudget(2)
        budget.beforeRefresh()
        budget.beforeRefresh()
        try { budget.beforeRefresh(); fail("must bound repeated CDN refreshes") }
        catch (_: java.io.IOException) { }
        budget.onBytesRead(32)
        budget.beforeRefresh()
    }
    @Test fun zeroByteReadDoesNotResetRecoveryBudget() {
        val budget = CdnRecoveryBudget(1)
        budget.beforeRefresh()
        budget.onBytesRead(0)
        try { budget.beforeRefresh(); fail("zero bytes are not progress") }
        catch (_: java.io.IOException) { }
    }
}
