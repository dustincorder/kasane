# Real artwork preview fixtures

These original JPEGs were downloaded on 2026-11-11 from public YouTube Music
thumbnail URLs returned by guest InnerTube Search and Home responses.

- assets/artwork-previews/search.jpg: Let Down — Radiohead • OK Computer OKNOTOK 1997 2017 • 5:00
  Source: https://yt3.googleusercontent.com/gdMTA2cTkNFr6COYNygeVpgIpxR2yNg5MjaX02OQqmsU6NlymcR2mqWR0UZe2HaBYVSTHU8lIQamafQC=w120-h120-l90-rj
- assets/artwork-previews/home.jpg: Beat It — Michael Jackson • 2.2B plays
  Source: https://yt3.googleusercontent.com/URvHCfI2iyGAlAwqqBFeaFhU9DeKk_iuX40OIIIj8Zp0wIT3BVsJ2JRMwLLbUB9EZS7t7oDlMrI2S3OvGA=w120-h120-l90-rj

No image was generated or substituted into production data. These files and
ArtworkPreviews.kt belong to the debug source set only. Preview functions seed
Coil's memory cache with the original downloaded image bytes and disable
inspection mode within their own composition, because Coil 2 suppresses image
requests in inspection mode. They call the production components.

Network availability is independently checked by LiveArtworkProbeTest.
For an explicit guest check, run from android/:

    KASANE_ARTWORK_LIVE=1 ./gradlew testDebugUnitTest --tests cord.kasane.music.LiveArtworkProbeTest

This does not exercise Google authentication or playback, and sends no account data.
