# Facebook App Ads Remover

An LSPosed/Xposed module for `com.facebook.katana` that removes ads using structural DexKit discovery plus guarded, version-specific fast paths.

Current target: Facebook `576.0.0.42.73`, module `1.15` (versionCode 16). Discovery is structural rather than name-based, so newer builds generally keep working unchanged — the loader guard described below was verified against `578.0.0.40.75`. Older versions (571 and below) are no longer supported.

## Scope

- News Feed sponsored units
- Story ads and in-disc story ads
- Reels / upstream ad-backed story append paths
- Quicksilver game ad requests
- Audience Network and Neko playable ad activities used by games

## Features

The module is more than an ad blocker, and the settings screen below is its whole surface. `ui/Toggles.kt` is the source of truth for every switch, its default and its exact effect — what follows is the map, not a second copy of it.

**Ads** (on by default)

- **Block ads** — master switch: home-feed sponsored stories, video ads, banners and the ad-free-session spoof.
- **Block marketplace ads** — sponsored tiles, boosted listings and video ads in Marketplace.
- **Block game ads** — in-app game ad requests are rejected; rewarded requests resolve as success, so the reward is still granted.
- **Feed ad guard (CSR experiment)** — the second feed pipeline Facebook's CSR cohort uses, which the classic feed filter never sees.
- **Block reels shopping cards** — the "Shop now" product card overlaying promotional reels.

**Feed filters** (off by default) — hide Threads posts, Reels, suggestions, People You May Know, Stories in feed, and AI-generated content (stories carrying the gen-AI transparency flag); a free-text **keyword filter**; and a News Feed **auto-refresh block**.

**Stories** — view stories without marking them seen; keep the Stories tray out of the feed.

**Appearance** — force dark mode.

**Privacy** — allow screenshots and recording; block Facebook's own capture detection.

**Navigation** — activity list: dump every hidden activity Facebook starts (action, URI, extras) to the log.

**Links** — unwrap `facebook.com/l.php?u=…&fbclid=…` redirect links to the real destination before the browser opens them.

**Downloader** — capture media URLs with a floating quick-download bubble; hand media to the browser instead of the in-module downloader; quick-download from a copied link. A captured video also gets a **Repost** action, which posts it to a Facebook Page you administer (the page list comes from `graph.facebook.com`, the token from the live session).

**Video** — resume the last playback position when a video is reopened; background playback with no floating window.

**Account** — session export/import. **Module** — launcher-icon visibility. Both are described below.

## Settings App

The module ships its own Android app: one scrolling screen of switches. Open it from the launcher icon, or from the Xposed/Vector module list (Modules → Facebook App Ads Remover → settings).

**How a toggle reaches a hook.** The switches are not stored in the module app. They live in the framework's remote-preferences group `fbar_settings` (the LSPosed/Vector daemon database), which the module app writes through the libxposed *service* library and the hooks read inside the Facebook process through `XposedInterface.getRemotePreferences`. Plain `SharedPreferences` files in either app's storage are **not** part of that channel — the daemon never reads them. Until the service binds, the switches render with their defaults and stay disabled; the header line says so. Several hooks read their toggle once at install, so treat a change as **apply on next Facebook restart**.

Earlier builds kept toggles in a Facebook-process-local `fbar_prefs` file (written while verifying on device with root). The first time the settings app binds, it copies that file into the remote group if the group is still empty; after that the remote group wins.

### Session export / import

The **Account** section holds buttons rather than switches. *Export session* copies Facebook's two live session files (`authentication`, `logged_in`) plus the captured cookies to `Download/FacebookAppAdsRemover` as `FBAR-Session-*.json`. *Import latest* restores the newest export; *Import from file…* lets you browse to any of those JSON files, for instance one copied from another device. Force-stop Facebook afterwards.

The buttons broadcast into the Facebook process, where `core.SessionBackup` answers — that side holds the session files and the captured cookies.

### Hiding the launcher icon

The **Module** section's `Show launcher icon` switch takes the app out of the launcher. It is the odd one out on the screen: its value lives in `PackageManager`'s component state rather than the framework's remote preferences, so it needs no service and is enabled immediately. Hiding sits behind a confirmation dialog, because the icon is the normal way in.

The drawer entry is an `<activity-alias>` (`ui.LauncherAlias`), not the activity itself, so hiding the icon disables only that component. `ui.MainActivity` stays exported and startable by explicit intent, which is the way back:

```powershell
adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity
```

The manifest also carries an always-enabled second alias, `ui.InfoAlias`, with `ACTION_MAIN` + `CATEGORY_INFO`. `PackageManager.getLaunchIntentForPackage()` resolves `CATEGORY_INFO` before `CATEGORY_LAUNCHER`, and the Xposed module list opens a module's settings through exactly that call — without the second alias, hiding the icon would take the manager's settings button with it.

Two consequences worth knowing:

- Pixel Launcher's **search** does surface the `CATEGORY_INFO` entry (the app drawer does not — it queries `CATEGORY_LAUNCHER` explicitly). A search hit renders as package information, so tapping it opens *App Info* rather than this screen. App Info's own *Open* button does land on the settings screen.
- Hiding the icon cannot affect the module itself. The framework daemon loads the module from the APK path in its own database, so the hooks inside Facebook are untouched.

## Main Findings

- Obfuscated names such as `AiD`, `A84`, `A8t`, `ADF`, or `Aue` are too unstable to hardcode. They changed across builds and caused broken hooks.
- Stable strings and structural signatures are much more reliable than direct obfuscated names.
- Feed ads are inserted at multiple layers. Blocking only one layer is not enough.
- The main News Feed request is a mixed GraphQL payload containing organic and sponsored units. Blocking its host or request would also block the organic feed.
- The earliest safe client boundary found so far is the dedicated story-ad store layer identified by `AdsPaginatingNetworkAdBucketFetcher`, `FbStoryAdInDiscStoreImpl`, `IN_DISC_METADATA_KEY`, and `AD_BUCKETS_KEY`. The module blocks fetch, merge, deferred-update, and insertion methods there before ad units enter feed pools. The telemetry labels `ads_deletion`/`ads_insertion` are deliberately NOT used as class selectors anymore: unrelated story viewer classes log those labels, and hooking them blanks the story viewer (576's `X.BAl` was the story viewer's own `onDataChanged` handler).
- Game ads are not a single pipeline either. Quicksilver request hooks, postMessage hooks, and UI activity fallbacks all matter.
- Blocking `AudienceNetworkActivity` at `startActivity(...)` was too early and caused game hangs. Letting it launch and closing it immediately from activity lifecycle hooks worked better.
- `com.facebook.soloader.SoLoader` is not an ad class, but it *names* ad libraries. It holds the merged-native-library dispatch table, which lists every native library in the app — including ad-related ones such as `libmailboxinthreadadcontextbannerjni.so` — so a DexKit string-anchor scan for an ad-library name matches it. Sweeping it is fatal: its `loadLibrary` / `loadLibraryUnsafe` overloads return boolean, so a false-returning hook replaces them, no merged library ever gets its `JNI_OnLoad`, every `initHybrid` throws `UnsatisfiedLinkError`, and Facebook cannot start at all. Loader infrastructure must never be an anchor target — see the guard under Cache Invalidation.

## Hook Strategy

### Feed / Stories / Reels

- Resolve classes with DexKit using stable strings and method shapes.
- Resolve every matching story-ad provider instead of assuming one provider class; Facebook may split this pipeline between releases.
- Install feed, Reels, and game hooks independently so a changed Reels target cannot prevent feed-source filtering from loading.
- Remove ad-backed stories from the upstream list builder append path.
- Sanitize feed CSR filter inputs and outputs.
- Sanitize late-stage feed lists before they reach rendering.
- Block sponsored entries from the sponsored pool and story pool.
- Block story ad providers by intercepting merge/fetch/update style methods.
- Keep marker-based view removal as a last-resort safety net, not the primary News Feed path.

#### Facebook 576 Findings

- The feed component pair (576: wrapper `X.2q8`, component `X.2q4`) is discovered by Litho component name, not by obfuscated class name. Litho generated components pass a stable spec name to their base class constructor — `"NewsFeedFeedUnitComponent"` for the feed unit component and `"LoggingComponent"` for the generic wrapper Litho renders feed units through — and those strings survive Facebook's obfuscator. The class-load notifier reads the name reflectively (the generated base stores it in a final String field filled by a String constructor; the class is instantiated through its no-arg constructor to read it), and the full DexKit pass finds the same classes with an exact `usingStrings` match as a backstop.
- The cached initial News Feed (including a sponsored slot) assembles and renders within ~2s of a cold start — before any DexKit scan can finish. To win that race, the discovered guard pair is persisted in the host's `cacheDir` keyed by the Facebook version, and later launches load it right after `Application.attach`. The cached class names still fail `Class.forName` at attach time (the secondary dex is not configured yet), so every timed guard attempt re-tries registering them; the guard then installs ~200ms after attach, before the cached feed renders. This is what removes the "second feed item is a sponsored post" on force-close/reopen. A Facebook update changes the version key and falls back to the DexKit discovery, which then rewrites the cache.
- The wrapper renders via `A1F` only (no `A1H`), so the guard matches Litho layout entry points by shape — instance methods taking the Litho context (`X.3Qp`) with a non-primitive return — instead of requiring a method literally named `A1H`. Static builder factories with the same shape are excluded.
- The edge and wrapper-child fields are resolved structurally: the component's edge field is the one whose type is (or implements) the feed-item contract exposing `GraphQLFeedStoryCategory` (576: `X.3yV` via `B9B()`); the wrapper's child field is the one assignable to the component class.
- `StoryAdsInDisc` no longer exists anywhere in 576, and the story ad store moved to `X.BEC`. See the selector change above.
- The feed-item contract hooks (`X.3YX`/`X.3Xk` on 576) and the CSR/network/pool hooks all resolve structurally via DexKit (`X.21r` CSR filters, `X.21e` sponsored pool, `X.BEC` story ad store, late feed list hooks). The hardcoded 571 contract-class hints (`X.3YX`/`X.3Xk`), the Audience Network listener names (`X.mGv`/`X.mGo`), the Quicksilver handler name (`X.edO`), and the `X.2Jy` feed-object hint were removed entirely — the inspector and edge-field resolution work structurally (GraphQL edge class name, `GraphQLFeedUnitEdge`/`GraphQL`+`Feed` name matching, feed-story-category enum constants), and the AN reward no longer depends on them since the webview-delivery rewrite delivers the reward.
- The 571 hardcoded fast paths (`X.21p.Ani`, `X.1fM.A0B`, `X.21O.A03`, `X.2mm.A3F`, `X.1vr.addNewEdgeToCollection`, the `X.9xH`-style curated story-ad class list) were removed: they were all dead on 576, and the curated list even matched a network-connectivity helper (`X.9xH`) whose shape coincidentally fit the deferred-update rule. The seeded component guard seeds (`X.2q4`/`X.2q8`) were removed with the Litho-name discovery above.
- The global `addView` safety-net hook must never call `View.createAccessibilityNodeInfo()` on freshly added views. On 576, building the accessibility node mid-mount runs Facebook's custom-view accessibility code with side effects, and page-profile header text ("Sign up", "Followers", "posts") ends up blank after pull-to-refresh. `collectViewMarkerTexts` therefore reads only `contentDescription` and `text`.

### Native / Network Boundary

Facebook 571 stores most application bytecode in 18 Superpack secondary dex files. The small libraries visible directly in the APK, including `libfbunwindstack.so`, are not the feed-ad source. Networking may ultimately use native transports, but host-level blocking is too coarse because feed ads share the normal GraphQL request.

The preferred interception point is therefore after GraphQL data has been decoded but before dedicated ad providers merge it into the feed. Native or KernelSU hooks should only be considered if runtime logs show that the `ads_deletion` / `ads_insertion` provider hooks no longer resolve or fire.

### Game Ads

- Resolve Quicksilver ad request methods by their stable JSON error strings.
- Hook the Quicksilver `postMessage(String, String)` bridge as a second request-layer fallback.
- The runtime delegate the game webview actually uses (576: `X.q10`) is NOT the DexKit-discovered service delegate (`X.gJA`); it is caught at registration by hooking `WebView.addJavascriptInterface`. It is a thin delegate with no promise-resolve helper on its class, so request payloads can only be snapshotted there, not resolved.
- The promise result is delivered back into the webview as `evaluateJavascript("e = new Event('message');e.data = {...};window.dispatchEvent(e);")`. The module rewrites that JSON in place: for rewarded requests (`getrewardedvideoasync`/`getrewardedinterstitialasync`, and `showadasync` with a rewarded ad instance) error fields are dropped and `success/completed/didComplete/watched/rewarded` + `completionGesture:"post"` are forced, so the game grants the reward with no ad shown. The rewrite also covers `loadUrl` and `postWebMessage` deliveries. Note the envelope `type` stays `"rejectpromise"` on the rewritten `showadasync` responses — the game reads the outcome fields in `data`, and converting the envelope is unnecessary.
- Close `AudienceNetworkActivity`, `AudienceNetworkRemoteActivity`, and `NekoPlayableAdActivity` from lifecycle hooks as UI-level fallbacks.
- Only hard-block the playable activity launch path directly; Audience Network activity launches are allowed so their internal close/error flow can run before the activity is closed.

### Cache Invalidation

Two discovery results are persisted **inside the Facebook process**, and both are keyed against the build that produced them:

| Cache | File | Key | Rebuilt when |
|---|---|---|---|
| Method / discovery | `fbar_discovery_cache` in the host `cacheDir` | Facebook `versionCode` | the host version changes |
| Banner classes | `fbar_prefs_banner` in the host `shared_prefs` | Facebook `versionCode` **and** module `VERSION_CODE` | either stamp moves |

- **Why the banner cache needs the module stamp as well.** Its entries are obfuscated member names, so they mean something only for the exact host build — that is the host stamp. But the *scan's own semantics* change with the module, and that is precisely how a poisoned class set (the SoLoader entry) reached a shipped cache and stayed there: the list was written once and afterwards only ever read, so nothing could revise it. Stamping both makes the module version part of the cache's validity.
- **A stale cache is rebuilt only when a DexKit bridge is available.** On the discovery-cache-hit launch path there is none (`ModuleMain` passes a null bridge there), so a stale set is swept as it stands and the stamps are deliberately left stale: a name that no longer exists simply fails `Class.forName` and is skipped, and the loader guard makes a poisoned entry harmless. Wiping the set there would strand banner coverage until Facebook's data was cleared — deferring moves the rebuild to the next launch that does have a bridge.
- **The class set is filtered twice**: once at scan time, so a poisoned name is never written to the cache in the first place, and once before the sweep, so a cache written by an older module is harmless anyway. The sweep also never replaces `loadLibrary` / `loadLibraryUnsafe`, whichever class it lands on.

## Notes About Logs

- Runtime logs go through `core/L.kt`, which writes to logcat and to the framework's module log.
- Read them with `adb logcat -s FacebookAppAdsRemover FBAR.Discovery`; every hook has its own `FBAR.*` tag.
- Logging is currently **not** gated behind `BuildConfig.DEBUG` — release builds are as loud as debug ones. Gating it is an open item.

## About `feedCsr=0`

The startup line:

```text
DexKit groups: ... feedCsr=0 ...
```

is normal in the current implementation.

That number is only the result of the initial batch string-group search. Feed CSR hooks are also resolved by later structural and fallback matchers, so `feedCsr=0` does not mean feed CSR filtering is disabled.

The line that actually matters is:

```text
Resolved feed CSR filters=...
```

If that later line contains resolved classes, the CSR filtering path is active even when the earlier batch count is zero.

## Build

- Android app module: `app`
- Host package: `com.facebook.katana`
- Application ID: `tn.loukious.facebookappadsremover`

Build the debug APK with:

```powershell
./gradlew :app:assembleDebug
```

`versionCode` / `versionName` live in `app/build.gradle.kts`; the output lands in `app/build/outputs/apk/debug/FacebookAppAdsRemover-v<versionName>-debug.apk`.

## Current Direction

- Prefer stable strings, type signatures, and runtime structure over obfuscated identifiers.
- Gate the runtime logging behind `BuildConfig.DEBUG` (open item — nothing gates it today).
- Treat feed, story, and game ads as separate pipelines with separate fallbacks.
