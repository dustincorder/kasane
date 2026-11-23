# System WebView for the Compose desktop (Linux, Windows): verification PoC

Goal: run Kasane's existing YouTube playback verification (`verifier/dist/kasane-verifier.js`, BotGuard
attestation + PoToken minting + player-script decipher, driven by `VerifiedPlayerResolver`) on the desktop
without bundling Chromium/CEF. Proof of concept: `poc/webview-verify` (Gradle `:poc-webview`), isolated from
both apps. It compiles the production `VerifiedPlayerResolver.kt` unchanged.

## What the verifier needs from a browser

An offline page (no network from the page; native code fetches every challenge/token/script), `evaluate(js)`,
and one JS→native reply callback. Nothing visible, nothing persisted. Any engine with those three primitives
works; the question is which engine is cheap, isolated and cleanly disposable.

## Test

`build/install/poc-webview/bin/poc-webview <engine> [videoIds]` on this machine (Arch, KDE Plasma Wayland,
WebKitGTK 2.54.2, JDK 21). For each track: full verified resolve, then range reads at 0, 1 MiB, the middle and
the end of the file (guest ANDROID_VR URLs now return 403 after the first range). With `-Dkasane.play=1` the
verified stream is served through the desktop's unchanged `StreamProxy` and decoded end to end.

## Results (real verification, same two tracks)

| Engine | Verified | Ranges past 1st chunk | Engine+bundle ready | 1st resolve | Next resolve | Memory while alive | Added size | Cleanup / storage |
|---|---|---|---|---|---|---|---|---|
| **System WebKitGTK via JNA** (`WebKitGtkRuntime`) | yes | 200 everywhere | 0.85–1.1 s | 4.5–8.5 s | 0.3 s | JVM +230 MB (GTK/WebKit UI libs), WebProcess ~350–390 MB, NetworkProcess ~50 MB | JNA 1.8 MB (WebKitGTK is a system library) | `close()` ends both WebKit processes; ephemeral data manager: nothing written to disk |
| JavaFX WebView 21 (bundled WebKit) | yes | 200 everywhere | 2.9–3.2 s | 6.7–13.4 s | 0.35 s | JVM ~365 MB (in-process) | +51 MB per platform (javafx-web 42 MB) | in-process; user data dir pointed at a temp dir |
| Enaium/webview-kmp 1.0.1 (webview/webview over JNI) | yes | 200 everywhere | — | 6.5 s | 0.4 s | like WebKitGTK | ~70 KB Linux, ~250 KB Windows | WebKit processes kept running after `close()`; wrote persistent `~/.local/share/java` (HSTS, media keys) and `~/.cache/java/WebKitCache`; built with Kotlin 2.4 metadata (Kasane is 2.0.21); Gradle metadata does not resolve for a plain JVM consumer; no hidden-window or storage API |
| NucleusFramework/ComposeNativeWebview 1.0.3 | not run | | | | | | | desktop requires replacing the window backend with Nucleus Tao (`nucleusApplication`); its docs state it does not run on the AWT backend. Excluded by requirement |

End-to-end decode (WebKitGTK): Teardrop (5:30) verified, served by `StreamProxy`, decoded by GStreamer
(faad) to WAV: **330.4 s**, the full track. A guest URL stops after its first range (~30 s).
(mpv/ffmpeg on this machine are temporarily broken by today's libjxl 0.13 upgrade, unrelated to Kasane;
GStreamer's own AAC decoder was used for this check.)

## Notes per requirement

- **No windowing migration.** WebKitGTK/JavaFX run without any window of ours (offscreen GtkOffscreenWindow;
  headless JavaFX `WebEngine`). Compose/AWT is untouched.
- **KDE Wayland.** All runs were on the Plasma Wayland session. WebKitGTK must start with compositing and the
  DMA-BUF/GL renderer disabled (`WEBKIT_DISABLE_COMPOSITING_MODE`, `WEBKIT_DISABLE_DMABUF_RENDERER`,
  `GDK_DISABLE=gl`, hardware acceleration NEVER); otherwise GDK aborts trying to create a GL context.
- **Isolation.** WebKitGTK: `webkit_website_data_manager_new_ephemeral()` (no cookies/storage/cache on disk),
  local storage/databases/media/WebGL off, file access off, page CSP `default-src 'none'` so page JS cannot
  reach the network; our bundle is injected with `evaluate_javascript` (not subject to the CSP).
- **No permanent browser processes.** WebKit's processes live only while the runtime exists; destroying it
  ends them (checked). Production should create it on first verification and close it after an idle period.
- **Windows.** Not tested here (no Windows machine). WebView2 provides the same primitives
  (`ExecuteScriptAsync`, `WebMessageReceived`, `NavigateToString`, in-private profile / temp user-data folder,
  processes end with the controller). Bindings: the 166 KB `WebView2Loader.dll` + COM via JNA, or the
  webview/webview C library's WebView2 backend; both unverified.

## Recommendation

1. **Linux: system WebKitGTK through JNA** (the PoC's `WebKitGtkRuntime`, ~200 lines, +1.8 MB). It is the
   smallest option that passed real verification and the only one meeting isolation and cleanup out of the box.
2. **Run it in a short-lived helper process** of the same desktop app (`kasane --verifier`, JSON over
   stdin/stdout), started on the first verified resolve and exiting after a few idle minutes. This keeps GTK
   off the JVM that hosts Compose/AWT (AWT may initialise GTK itself), confines a WebKit crash, and makes
   "no permanently running browser processes" a property of process lifetime rather than of cleanup code.
   Memory (~450 MB while verifying) is then returned when idle.
3. **Windows: WebView2 behind the same `VerificationRuntime` interface** in that helper; to be built and tested
   on Windows. JavaFX WebView is the working fallback on both systems if a system engine is missing, at +51 MB.
4. **Do not adopt** webview-kmp (no isolation/cleanup control, Kotlin 2.4 metadata, very new) or
   ComposeNativeWebview (window backend migration).
5. To wire it into playback: move `VerifiedPlayerResolver` (pure JVM) to `shared/jvmCommonMain`, give the
   desktop player a verified path before the iOS-HLS and guest paths. No change to Android.

## Google sign-in

Must use the external browser (no embedded login): system browser via `xdg-open`/`Desktop.browse`, OAuth 2.0
authorization-code flow with PKCE and a loopback redirect (`http://127.0.0.1:<port>/`), tokens kept in the
Secret Service (Linux) / Credential Manager (Windows). Open question, not tested here: which InnerTube clients
accept OAuth bearer tokens for the YouTube Music account library; Android's session is cookie-based and cannot
be obtained from an external browser. This needs its own experiment before it replaces Guest on desktop.

## Implemented (follow-up)

- `VerifiedPlayerResolver` moved to `shared/jvmCommonMain` and returns `VerifiedResult`; Android maps it to its
  `PlaybackStream` in `MusicProvider` (behaviour unchanged, `VerifiedPlayerResolverTest` passes).
- Desktop: `verify/WebKitGtkRuntime` (from the PoC) runs in the `--verifier` helper process
  (`verify/VerifierHelper`), driven by `verify/VerifierProcess`: started on the first verified track, stopped
  after 3 idle minutes and on exit; crashes are replaced on the next call. The packaged launcher needs its
  jpackage marker variables removed from the child environment, or it acts as a plain `java` launcher.
- `DesktopPlayer` order: verified (system WebKitGTK) → iOS HLS → guest (with cut-off detection). Expired
  verified URLs are re-verified by `StreamProxy`, not replaced with guest URLs.
- Checked headless with the packaged app (`--play`): Teardrop and Around the World play past the 30 s guest
  cut-off from `source=verified`; no WebKit or helper process remains after exit. Not yet checked: idle
  shutdown timing, a session longer than the signed URL's lifetime, Windows (no WebView2 runtime yet; the
  desktop falls back to iOS HLS/guest there).
