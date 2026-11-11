package cord.kasane.music.ui

/** Navigation is independent of playback: Back never sends a player command. */
data class NavigationState(val selectedTab: Int = 0, val nowPlayingOpen: Boolean = false, val detailDepth: Int = 0)

fun navigationAfterBack(selectedTab: Int, nowPlayingOpen: Boolean, detailDepth: Int = 0): NavigationState =
    when {
        nowPlayingOpen -> NavigationState(selectedTab, false, detailDepth)
        detailDepth > 0 -> NavigationState(selectedTab, false, detailDepth - 1)
        else -> NavigationState()
    }

fun navigationAfterTabSelection(selectedTab: Int): NavigationState =
    NavigationState(selectedTab.coerceIn(0, 2))
