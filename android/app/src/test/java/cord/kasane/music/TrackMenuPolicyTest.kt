package cord.kasane.music

import cord.kasane.music.ui.components.TrackMenuPolicy
import cord.kasane.music.provider.CloudLikeState
import org.junit.Assert.*
import org.junit.Test

class TrackMenuPolicyTest {
    @Test fun guestAndLocalTracksNeverOfferAccountMutations() {
        assertNull(TrackMenuPolicy.ratings(false, false, CloudLikeState.LIKED, true).like)
        val local = TrackMenuPolicy.ratings(true, true, CloudLikeState.LIKED, true)
        assertNull(local.like); assertNull(local.dislike)
    }
    @Test fun existingAccountRatingChangesActionToRemoval() {
        assertEquals("Remove YouTube Music like", TrackMenuPolicy.ratings(true, false, CloudLikeState.LIKED, true).like)
        assertEquals("Remove YouTube Music dislike", TrackMenuPolicy.ratings(true, false, CloudLikeState.DISLIKED, true).dislike)
        assertNull(TrackMenuPolicy.ratings(true, false, CloudLikeState.NEUTRAL, false).dislike)
    }
}
