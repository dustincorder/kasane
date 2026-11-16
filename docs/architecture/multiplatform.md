# Kotlin Multiplatform migration

## Current implemented boundary

`shared/src/commonMain/kotlin/cord/kasane/music` owns the existing serializable
Track/catalog models and QueuePolicy. Android depends on `project(":shared")`;
there is no second copy of these classes in Android sources. Public packages,
constructor defaults, source IDs and serialization names remain unchanged.

The initial KMP target is JVM: Android and Linux both execute JVM bytecode.
The Android app stays under `android/app`; a desktop app will consume the same
core. This target configuration is sufficient for portable domain logic, not a
claim of completed Compose Multiplatform UI or native platform adapters. Shared
UI needs platform-aware target configuration in a later verified stage.

The existing Kotlin/serialization/Compose-compiler versions remain 2.0.21, Gradle
8.9 and AGP 8.7.3. No simultaneous compiler/Room/UI upgrade is part of this step.
The [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
notes JVM-target `withJava()` limitations for this Gradle range; this module does
not use that DSL. No Android Gradle plugin is applied to the shared JVM module.

## Android storage compatibility

Domain Track has no Room annotations. Android `data/TrackRecord.kt` owns the
`tracks` table mapping; TrackDao retains its public domain methods and converts
only at insert boundaries. Query projections still return the shared Track.
Room stays at schema version 3 with identity hash
`9e8ed8bf833155ca561f290dd5a603e2`. Debug and release generated CREATE TABLE SQL
and identity match the preceding schema. No migration or destructive fallback
was added, and no account-store or signing configuration was changed.

An isolated API 35 `.dev` test verified single/batch inserts, every Track field,
and the favorite join in an in-memory database. The pre-existing emulator cache
DB opened after the app update; real guest Home/Search and fresh repository Room
reads passed with no repeat HTTP requests. This is not a Pixel update test.

## Validation of the domain extraction

From `android/`:

- `./gradlew :shared:jvmTest :app:assembleDebug :app:testDebugUnitTest`: passed;
  10 shared tests, 187 Android tests (178 passed, nine opt-in live tests skipped).
- `./gradlew :shared:jvmTest :app:assembleRelease :app:assembleDebugAndroidTest -PkasaneCacheProbe=true`: passed.
- Explicit instrumentation on `emulator-5580` only:
  `cord.kasane.music.dev.test/cord.kasane.music.CatalogCacheInstrumentation`: passed.
- Guest metadata timings in that run: fresh Home 2037 ms/one HTTP request, search
  615 ms/one request, memory median 17 us and 875 us/zero HTTP requests, fresh
  repository Room reads 6 ms and 5 ms/zero requests. No UI/image timing claim.

## Next boundaries

Extract account/library contracts and the existing provider into shared code;
keep Keystore/WebView and platform playback at their respective boundaries. Port
JSON/request logic without maintaining two evolving provider implementations.
Then add Compose Desktop with a real libmpv adapter (already available on the host),
shared catalog/queue and verified audio. Keep Rust/Tauri until parity passes.
See `../ROADMAP.md` and `../ACCEPTANCE.md` for the complete product scope.

## Shared account/library contracts (subsequent extraction)

commonMain now also owns AccountSession/Identity/State and SessionStore,
AccountCollection/AccountLibraryProvider, CatalogRemote, CloudLikeState and
TrackLinks. Android continues using these exact public packages; the Keystore,
WebView, AccountRepository, HTTP/parser, Room cache and rating controller
implementations remain their existing Android implementations for now. No account
cookies/IDs, session file format, login behavior or remote writes were changed.
The common session model has no serialization annotation and retains redacted
`toString()` and input validation; sensitive values do not become domain exports.

`./gradlew :shared:jvmTest assembleDebug testDebugUnitTest assembleRelease` passed:
12 shared tests, 187 Android tests (178 passed, nine live skipped). This is common
contract/compiler validation plus Android regression coverage, not a new live login
or verified Linux account session. The native Room test above belongs to the prior
Track extraction and does not claim real account coverage.

**Next concrete implementation:** move request construction/error policy to a common
InnerTube client with a bounded, cancellable HTTP port and platform SHA/clock ports;
port parser traversal to existing kotlinx.serialization JSON. Android OnlineCatalog
must delegate to this implementation, preserving its validated response, auth-host,
cancellation and error behavior. Keep one provider, not a copied Linux provider;
then wire Compose Desktop and libmpv to that shared catalog/queue.


## Verified provider checkpoint (2026-11-16)

Android OnlineCatalog now delegates typed reads and explicit library writes to
SharedOnlineCatalog in commonMain. InnerTubeClient owns request/context/error
policy; common JSON parsers own catalog, artwork, radio, rating and playlist
response conversion. The JVM OkHttp adapter bounds responses, cancels calls,
rejects redirects and avoids persistent cookie jars. Platform clock/SHA ports
keep Android APIs out of common code. Remaining org.json entry points are
compatibility facades for login and stream consumers, not a second provider.

Blank rating acknowledgements now require a read-only status confirmation; an
unknown result rolls back the optimistic controller state without repeating the
mutation. A partial HTTP body failure is classified NETWORK rather than malformed
JSON. Both corrections have regression coverage with synthetic credentials only.

Verification: shared JVM tests 37 passed; Android tests 188 total, 179 passed,
nine opt-in live tests skipped. Debug, release and isolated test APK builds passed.
The isolated emulator guest cache probe passed (Home 1794 ms/one request, search
929 ms/one request; memory medians 19/1090 us and Room 6/5 ms, zero HTTP requests).
These are metadata timings, not UI/image timings or a controlled speed comparison.
The isolated MediaSession radio probe passed: queue 51, deduplicated, manual entry
preserved and populated before the first track ended. No Pixel installation,
account credential access or personal-account mutation was performed.

This checkpoint does not implement or prove full SABR playback. Earlier partial
SABR results remain as documented in sabr-playback.md; full real-track EOF is not
confirmed. Cache and session/controller migration, Kotlin desktop and the remaining
product roadmap are unfinished. Work paused here at the user's explicit request.
