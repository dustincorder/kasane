package cord.kasane.music

import cord.kasane.music.provider.ArtworkSizing
import kotlin.test.*

class ArtworkSizingTest {
    private val small = "https://yt3.googleusercontent.com/abc123=w120-h120-l90-rj"

    @Test fun upgradesSmallSearchThumbnailsToTheDrawnSize() {
        assertEquals("https://yt3.googleusercontent.com/abc123=w544-h544-l90-rj", ArtworkSizing.sized(small, 500))
        assertEquals("https://yt3.googleusercontent.com/abc123=w1200-h1200-l90-rj", ArtworkSizing.sized(small, 1080))
    }

    @Test fun keepsListThumbnailsSmallInsteadOfDownloadingFullSizeCovers() {
        assertEquals("https://yt3.googleusercontent.com/abc123=w120-h120-l90-rj", ArtworkSizing.sized(small, 100))
        assertEquals("https://yt3.googleusercontent.com/abc123=w240-h240-l90-rj", ArtworkSizing.sized(small, 150))
    }

    @Test fun neverDowngradesAndNeverExceedsTheCap() {
        val big = "https://lh3.googleusercontent.com/x=w2400-h2400-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/x=w1200-h1200-l90-rj", ArtworkSizing.sized(big, 64))
        assertTrue(ArtworkSizing.sized(small, 99999).contains("w1200-h1200"))
    }

    @Test fun handlesSingleSizeSuffix() {
        assertEquals("https://lh3.googleusercontent.com/x=s544-c", ArtworkSizing.sized("https://lh3.googleusercontent.com/x=s60-c", 400))
    }

    @Test fun leavesOtherHostsAndUnsizedUrlsAlone() {
        val ytimg = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals(ytimg, ArtworkSizing.sized(ytimg, 1000))
        val plain = "https://lh3.googleusercontent.com/abc"
        assertEquals(plain, ArtworkSizing.sized(plain, 1000))
        assertEquals("https://evil.example/x=w120-h120", ArtworkSizing.sized("https://evil.example/x=w120-h120", 1000))
        assertFalse(ArtworkSizing.isResizable("file:///data/a.img"))
    }
}
