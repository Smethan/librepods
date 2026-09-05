# Standards

What this codebase actually does today — not aspirations. Where practice is
inconsistent, that is called out explicitly under **Inconsistencies**.

## Languages and toolchain

| | Android | Linux (legacy) |
| --- | --- | --- |
| Language | Kotlin 2.3.21, plus C++23 for the NDK targets | C++ (Qt6) + QML |
| Java/JVM | Java 21 source & target | — |
| SDK/deps | AGP 9.2.1, compileSdk/targetSdk 37, minSdk 33 (`foss`) / 36 (`play`), NDK `30.0.14904198`, CMake 3.22.1 | CMake ≥ 3.16, Qt6 |
| Kotlin style | `kotlin.code.style=official` (`android/gradle.properties`) | — |

## Formatting

`.editorconfig` at the repo root is the single source of truth and applies to
every language:

- UTF-8, LF, final newline, trailing whitespace trimmed.
- Default indent: **2 spaces**.
- `*.{py,java,r,R,kt,xml,kts,h,hpp,cpp,qml}`: **4 spaces** — i.e. essentially all
  real source in this repo is 4-space indented.
- `*.md`: 4-space indent, trailing whitespace preserved (it means `<br>`), no
  line-length limit.

There is **no** formatter or linter enforcing this. No ktlint, no detekt, no
`clang-format`, no `lint.xml`, and CI never runs a lint task.

## Licensing headers

Every source file carries the GPL-3.0-or-later header block:

```
LibrePods - AirPods liberated from Apple's ecosystem
Copyright (C) 2025 LibrePods contributors
...
```

77 of 108 Kotlin files have it today. New files should carry it.

## Android conventions

### Package layout

`me.kavishdevar.librepods` with a layer-first split:
`bluetooth/`, `data/`, `services/`, `receivers/`, `billing/`, `utils/`, and
`presentation/{components,navigation,overlays,screens,theme,viewmodel,widgets}/`.
Top-level entry points (`MainActivity`, `LibrePodsApplication`,
`QuickSettingsDialogActivity`) sit directly in the root package.

### Naming

- Screens: `<Feature>Screen.kt` containing a `<Feature>Screen` `@Composable`
  (`AirPodsSettingsScreen.kt`, `HearingAidScreen.kt`, …). One screen per file.
- ViewModels: `<Feature>ViewModel.kt`.
- Managers: `<Domain>Manager.kt` (`AACPManager`, `ATTManagerv2`, `BLEManager`,
  `BillingManager`, `MediaController`).
- Enum constants: `SCREAMING_SNAKE_CASE` with an explicit wire value and a
  `companion object { fun fromByte(...) }` — see `AACPManager.ControlCommandIdentifiers`,
  `ProximityKeyType`, `StemPressType`, `AudioSourceType`.

### UI

- **Jetpack Compose only** for app UI (60 files contain `@Composable`). No
  Fragments, no Activity XML layouts.
- **Navigation 3** (`androidx.navigation3`), wired in
  `presentation/navigation/{NavigationRoot,AppNavGraph,Screen}.kt`.
- XML layouts in `res/layout/` exist for the six surfaces Compose cannot serve —
  `RemoteViews` widgets, notifications, and the overlay windows — and are reached
  through `viewBinding`, which is enabled for exactly that reason.
- Material3 (`1.5.0-alpha21`) plus `haze` and Kyant0 `backdrop` for the
  liquid-glass effects.

### State

- `StateFlow` only. The pattern is
  `private val _uiState = MutableStateFlow(<Feature>UiState())` exposed as
  `val uiState: StateFlow<...>`, with an immutable `data class <Feature>UiState`.
  Same shape on `AirPodsService._packetLogsFlow/packetLogsFlow` and
  `BillingProvider.isPremium/price`.
- **`LiveData` is used nowhere.** One deliberate exception to the `StateFlow`
  rule: `AirPodsViewModel.isReady` is a Compose `mutableStateOf`.
- Persistence is `SharedPreferences`, almost always
  `getSharedPreferences("settings", MODE_PRIVATE)`. **No database, no DataStore.**
  `AirPodsService` implements `SharedPreferences.OnSharedPreferenceChangeListener`
  and reacts to preference writes as its settings-change channel.
- Cross-component events go over **package-scoped broadcast Intents** — the
  `AirPodsNotifications.*` constants in `data/Packets.kt`, always sent with
  `.setPackage(packageName)` and received in `AirPodsViewModel.observeBroadcasts()`.
  Payloads are untyped Intent extras.

### Threading

Explicit, ad-hoc `CoroutineScope(...)` creation at call sites rather than injected
or structured scopes: `Dispatchers.IO` (15 sites), `Dispatchers.Main` (3),
`Dispatchers.Default` (1). `viewModelScope` is used in ViewModels. `GlobalScope`
is not used; `runBlocking` appears twice.

Where a blocking JVM call must be abortable, the codebase drops to a raw daemon
`Thread` and cancels by closing the socket — `ATT-Reader` in `ATTManager.kt`,
`AACP-Connect` in `AirPodsService.connectWithTimeout()`, whose KDoc explains why
`withTimeout` cannot cancel a blocking `connect()`. **Follow this pattern for any
new blocking Bluetooth call.** Supporting primitives in use: an `AtomicBoolean`
reentrancy guard (`socketConnectInProgress`), `synchronized(writeLock)` around
socket writes in `AACPManager`, and `ConcurrentHashMap`/`LinkedBlockingQueue` for
ATT request/response correlation.

### Dependency injection

**None.** Hilt is present in `libs.versions.toml` and in `app/build.gradle.kts`
but every line is commented out (plugin at line 10, deps at 152–153), as is the
lone `//@AndroidEntryPoint` in `MainActivity.kt:66`.

The established substitute is the global singleton: `object ServiceManager`
(`services/AirPodsService.kt:142`), `object BluetoothConnectionManager`,
`object BillingManager` (with a `lateinit var provider`), `object MediaController`,
`object XposedServiceHolder`, `object HeadTracking`, and
`object XposedRemotePrefProvider` (a hand-rolled factory seam). Wiring happens in
`MainActivity.Main()` via a `ServiceConnection` that calls
`airPodsViewModel.init(service, controlRepo, sharedPreferences)`.

### Logging

`android.util.Log` throughout — 324 `Log.d`, 87 `Log.e`, 51 `Log.w`, 3 `Log.i`.
**Three** tag conventions coexist (see Inconsistencies):

1. `private const val TAG = "..."` at file top — `AirPodsService.kt:140`,
   `ATTManager.kt:27`, `KotlinModule.kt:14`.
2. `private const val TAG` inside a `companion object` — `BLEManager.kt:493`,
   `GestureDetector.kt:46`, `RadareOffsetFinder.kt:42`.
3. Inline string literals — `Log.d("AirPodsQSService", …)` (30+ sites),
   `Log.d("createSocket<psm>", …)`, `Log.d("AirPodsParser", …)`.

`AACPManager` uses an instance-scoped tag,
`"AACPManager[${System.identityHashCode(this)}]"`, so concurrent instances can be
told apart. Native code uses `__android_log_print` macros with
`#define LOG_TAG "LibrePodsHook"`. `LogCollector` embeds its own markers
(`<LogCollector:Start>`, `<LogCollector:Complete:Success|Failed>`) in log
messages so a capture can be sliced out of logcat.

### Error handling

Broad `try/catch (e: Exception)` with `Log.w`/`Log.e` or `e.printStackTrace()`.
The protocol layer deliberately swallows parse failures rather than dropping the
session — `AACPManager.receivePacket()` catches everything so an unknown or
malformed packet cannot kill the connection. `closeQuietly` is the idiom for
sockets.

### Documentation comments

Recent code carries genuinely good KDoc that explains *why* — see
`connectWithTimeout`, `resolveLocalMacAddress`, `BatteryNotification`, and the
comment block enumerating the six competing connect triggers. Older code has
none. When touching the Bluetooth layer, match the recent standard: these
comments are the only thing standing in for tests.

### Build-config gating

Flavor differences go through `BuildConfig.PLAY_BUILD` and the
`BillingProvider` interface (`PlayBillingProvider` vs `FOSSBillingProvider`,
selected by `BillingProviderFactory`) rather than per-flavor source sets. There
are no `src/foss/` or `src/play/` directories.

Device support is decided at runtime, not by `minSdk`: `utils/RootlessSupport.kt`
returns true unconditionally for SDK ≥ 37 and otherwise whitelists Pixel on 36
(by `Build.ID` prefix `CP1A`) and OnePlus/Oppo/realme on ≥ 36, with a
`bypass_device_check.v2` escape hatch.

## Testing

There are **no tests** — no `src/test/`, no `src/androidTest/`, no test
dependencies (JUnit/Espresso/Robolectric) in `libs.versions.toml`, no C++ test
targets, and no test task in CI. The only automated gate is "does
`assembleFossDebug` compile" on pull requests. Verification is review plus field
use on real hardware, which is what `utils/LogCollector.kt`, the troubleshooting
screen, demo mode (`AirPodsViewModel.activateDemoMode`) and the nightly channel
are for.

## Inconsistencies and rough edges

Real, currently-true problems observed in the tree. Roughly ordered by how likely
they are to bite someone.

### Bugs

| Issue | Evidence |
| --- | --- |
| **Two broadcast constants are the same string.** `AirPodsNotifications.AIRPODS_CONNECTED` and `AIRPODS_L2CAP_CONNECTED` both equal `"me.kavishdevar.librepods.AIRPODS_CONNECTED"`, so the two "different" events are indistinguishable to any receiver. | `data/Packets.kt:85-86` |
| **Italian translations never load.** The directory is `res/value-it/` (missing the `s`), which is not a valid resource qualifier. Every other locale is correct: `values-de`, `values-es`, `values-fr`, `values-pt`, `values-tr`, `values-uk`, `values-vi`, `values-zh-rCN`, `values-zh-rTW`. | `android/app/src/main/res/value-it/strings.xml` |
| **`@Serializable` without the plugin.** `Screen.kt` annotates its route types, but the kotlinx-serialization Gradle plugin is not applied and no `kotlinx-serialization-json` dependency is declared — no serializers are generated. | `presentation/navigation/Screen.kt`; `app/build.gradle.kts:6-12` |
| **Defaults disagree between the two sources of truth.** `ServiceConfig` declares `takeoverWhenDisconnected = true`, while the preference seeding writes `putBoolean("takeover_when_disconnected", false)`. | `services/AirPodsService.kt:183-190` vs `:475-488` |
| **Stale published versions.** `root-module-manual/module.prop` and `extras/update_nonpatch.json` both advertise `v0.2.6`/`46` while the app is `1.0.0-rc2`/`63`. The Gradle zip task rewrites `module.prop` at build time, but `updateJson` — which Magisk actually reads to offer updates — is not regenerated. | `root-module-manual/module.prop`, `extras/update_nonpatch.json`, `app/build.gradle.kts:3-4` |
| **Every model shows AirPods Pro 2 artwork.** All eleven `AirPods*` classes point at the same `airpods_pro_2*` drawables; the per-model drawables are commented out on every line. | `data/AirPods.kt:58-292` |
| **Latent off-by-one.** `BatteryNotification.MAX_COMPONENTS = 3` while `BatteryComponent.ALL` has four entries. Legal today because no device reports four, but it is exactly the assumption that broke AirPods Max. | `data/Packets.kt:43,296` |
| **Linux README names a file that does not exist** — it says `python3 hearing_aid.py`; the file is `hearing-aid-adjustments.py`. | `linux/README.md:189` |

### Convention drift

| Issue | Evidence |
| --- | --- |
| **Three log-tag conventions**, plus tags that don't match their class: `QuickSettingsDialogActivity` logs under `"QSActivity"`, `SystemAPIUtils` under `"SystemApisUtils"`, and `AirPodsService` uses its `TAG` constant *and* inline `"AirPodsService"`, `"AirPodsParser"`, `"BatteryNotification"`, `"ANC"`. | see **Logging** above |
| **A quarter of Kotlin files lack the GPL header** (77/108 have it). Missing from, among others, `LibrePodsApplication.kt`, `BillingManager.kt`, `XposedRemotePrefProvider.kt`, `XposedRemotePrefImpl.kt`, `Screen.kt`, `NavigationRoot.kt`, `AppNavGraph.kt`, `SystemAPIUtils.kt`, `LocalDesignSystem.kt`, `bluetooth_socket.cpp`. | repo-wide |
| **`Packets.kt` carries its own unheeded TODO** — `// TODO: Remove everything but Battery-related stuff` — yet still owns the ANC/ear-detection/CA parsers and all broadcast constants. | `data/Packets.kt:25` |
| **Foreground-service policy is suppressed, not satisfied** — `tools:ignore="ForegroundServicesPolicy"`. | `AndroidManifest.xml:10-11` |

### Dead weight

| Issue | Evidence |
| --- | --- |
| **Large commented-out subsystems left in place**: all of `CrossDevice`, `AppListenerService` (source exists, manifest entry commented out), `MainActivity`'s deep-link intent-filter, Hilt, and the `CameraControl` screen route. | `AirPodsService.kt:592-596,682-696,2707-2716`; `AndroidManifest.xml:91-97,125-135`; `Screen.kt:46-47` |
| **Hilt is half-adopted.** Declared in the version catalog, commented out everywhere it would take effect. Either finish it or drop the catalog entries. | `libs.versions.toml`, `app/build.gradle.kts:10,152-153` |
| **`navigation-compose` 2.9.8 is a declared dependency with zero usages** — no `NavHost`, no `rememberNavController`. The app is entirely on Navigation 3. | `app/build.gradle.kts:139` |
| **Dead CMake variable.** `XPOSED_SRC_DIR` points at `src/xposed/cpp`, which does not exist, and nothing consumes it. | `app/src/main/cpp/CMakeLists.txt:26` |
| **Dead code under `minSdk 33`.** The head-gesture branch still tests `SDK_INT >= Q` (API 29). | `services/AirPodsService.kt:428-435` |
| **Stale duplicate on the Linux side.** `CMakeLists.txt` compiles `media/playerstatuswatcher.cpp`; an unreferenced copy sits at `linux/playerstatuswatcher.cpp`. | `linux/CMakeLists.txt:44` |
| **Uncompiled Linux translations.** `TS_FILES` lists only `librepods_tr.ts`; `it_IT` and `zh_TW` exist but are never built. | `linux/CMakeLists.txt:15-17` |

### Structural

| Issue | Evidence |
| --- | --- |
| **`AirPodsService` is a 3,462-line god object** holding sockets, protocol callbacks, notification, media, gesture, telephony and persistence logic. `AACPManager` is 1,415 lines. Both are the natural place to add features and the hardest place to test. | `services/AirPodsService.kt`, `bluetooth/AACPManager.kt` |
| **`AirPodsViewModel` is not a boundary.** UI code reaches through it into `service.aacpManager.*` and `service.attManager.*` directly, so the protocol layer is effectively public API for screens. Commit `0f50eab` pulled some ATT code back a layer; the rest remains. | `presentation/viewmodel/AirPodsViewModel.kt:253,579,658,666` |
| **Reflection over hidden platform API is load-bearing.** `createBluetoothSocket()` tries five private `BluetoothSocket` constructor signatures in sequence, backed by a JNI hidden-API exemption in `bluetooth_socket.cpp`. Every Android release can invalidate them. This is deliberate — but it is the app's most fragile surface. | `bluetooth/BluetoothConnectionManager.kt`, `cpp/bluetooth_socket.cpp` |
| **Linux CI is effectively off.** `ci-linux.yml` is `workflow_dispatch`-only (its `push` trigger is commented out), and `ci-linux-rust.yml` builds `linux-rust/`, which only exists on the `linux/rust` branch. `linux/` on `main` is not built by CI at all. | `.github/workflows/ci-linux.yml`, `ci-linux-rust.yml` |

## Contribution notes

- `README.md` explicitly discloses which parts were AI-generated (head gestures,
  the r2/Xposed offset setup, troubleshooter/log collector on Android; `aacp.rs`,
  `att.rs` and parts of `media_controller.rs` on the Rust side). Keep that
  section honest when adding generated code.
- `res-apple/` holds Apple-derived assets — `font/sf_pro.otf` and the AirPods
  Pro 2 renders. SF Pro is Apple property and `README.md` commits to replacing it
  with an open alternative. (`res/font/hack.otf` is the unrelated, freely
  licensed monospace face.)
- The LibrePods name and logo are excluded from the GPL grant (see the Trademark
  Notice in `README.md`).
