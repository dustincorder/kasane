package cord.kasane.music

import cord.kasane.music.ui.NavigationState
import cord.kasane.music.ui.navigationAfterBack
import cord.kasane.music.ui.navigationAfterTabSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateTest {
    @Test fun backClosesPlayerBeforeLeavingSearch() {
        assertEquals(NavigationState(1, false), navigationAfterBack(1, true))
    }

    @Test fun secondBackReturnsFromSearchToHome() {
        val closed = navigationAfterBack(1, true)
        assertEquals(NavigationState(), navigationAfterBack(closed.selectedTab, closed.nowPlayingOpen))
    }

    @Test fun backFromLibraryReturnsHome() {
        assertEquals(NavigationState(), navigationAfterBack(2, false))
    }

    @Test fun backOnHomeKeepsRoot() {
        assertEquals(NavigationState(), navigationAfterBack(0, false))
    }

    @Test fun allThreeDestinationsCanBeSelected() {
        for (tab in 0..2) assertEquals(NavigationState(tab, false), navigationAfterTabSelection(tab))
    }

    @Test fun invalidTabCannotCreateFourthDestination() {
        assertEquals(NavigationState(2, false), navigationAfterTabSelection(3))
        assertEquals(NavigationState(0, false), navigationAfterTabSelection(-1))
    }

    @org.junit.Test fun catalogBackReturnsToPreviousDetailBeforeHome() {
        org.junit.Assert.assertEquals(NavigationState(1, false, 1), navigationAfterBack(1, false, 2))
    }
    @org.junit.Test fun closingPlayerPreservesCatalogStack() {
        org.junit.Assert.assertEquals(NavigationState(1, false, 2), navigationAfterBack(1, true, 2))
    }
}
