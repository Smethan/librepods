# Architecture

LibrePods is a multi-platform client for Apple AirPods that speaks Apple's
proprietary **AACP** (over an L2CAP channel) and **ATT** protocols directly,
without any Apple software. The repository holds several independent
implementations of the same idea plus the reverse-engineering notes that back
them.

> Protocol reference lives beside this file: [`AAP Definitions.md`](./AAP%20Definitions.md),
> [`opcodes.md`](./opcodes.md), [`control_commands.md`](./control_commands.md),
> [`battery_report.md`](./battery_report.md), [`device-info.md`](./device-info.md).

## Repository layout

| Path | Responsibility |
| --- | --- |
| `android/` | The primary, actively developed client. Single-module Gradle project (`:app`). |
| `linux/` | Legacy Qt6/QML desktop client (C++). Superseded by a Rust rewrite that lives on the `linux/rust` branch, not on `main`. |
| `root-module-manual/` | Magisk/KernelSU module template that installs the Android APK as a privileged system app. |
| `docs/` | Reverse-engineered protocol documentation. |
| `extras/` | Standalone helper scripts (`proximity_keys.py`) and release metadata (`update_nonpatch.json`, `CHANGELOG.md`). |
| `.github/workflows/` | CI for Android, legacy Linux, and the Rust Linux rewrite. |

---

## Android app

**Package:** `me.kavishdevar.librepods` — `android/app/src/main/java/me/kavishdevar/librepods/`

### Entry points

| Component | File | Role |
| --- | --- | --- |
| `LibrePodsApplication` | `LibrePodsApplication.kt` | `android:name` in the manifest. Registers the Xposed service listener, creates the `BillingProvider`, observes `ProcessLifecycleOwner`. |
| `MainActivity` | `MainActivity.kt` | Launcher activity; hosts the whole Compose UI. Conditionally `System.loadLibrary("l2c_fcr_hook")` when Xposed is available, then starts + binds `AirPodsService` and hands the binder to `AirPodsViewModel.init(...)`. |
| `QuickSettingsDialogActivity` | `QuickSettingsDialogActivity.kt` | Compact dialog surface reachable from the QS tile. |
| `AirPodsService` | `services/AirPodsService.kt` | Foreground `Service` (`connectedDevice` type). The system's centre of gravity — owns the sockets, the protocol managers and all live device state. ~3.4k lines. |
| `AirPodsQSService` | `services/AirPodsQSService.kt` | Quick Settings tile for noise-control mode. |
| `BootReceiver` | `receivers/BootReceiver.kt` | Restarts the service after boot (`RECEIVE_BOOT_COMPLETED`). |
| `BatteryWidget`, `NoiseControlWidget` | `presentation/widgets/` | Home-screen app widgets (manifest `<receiver>`s). |
| `KotlinModule` | `utils/KotlinModule.kt` | Xposed **Java** entry point, declared in `src/main/resources/META-INF/xposed/java_init.list`. |
| `libl2c_fcr_hook.so` | `src/main/cpp/l2c_fcr_hook.cpp` | Xposed **native** entry point (`native_init.list`). Injected into the Bluetooth process; forces `l2c_fcr_chk_chan_modes` to return 1 and rewrites `BTA_DmSetLocalDiRecord`'s vendor to `0x004C` (Apple). |
| `libbluetooth_socket.so` | `src/main/cpp/bluetooth_socket.cpp` | Loaded by `AirPodsService`'s `companion object init`. Its `JNI_OnLoad` calls `VMRuntime.setHiddenApiExemptions` for `BluetoothSocket`/`BluetoothDevice` (class names XOR-obfuscated), which is what makes the reflective L2CAP construction legal. |

`AppListenerService` (`services/AppListenerService.kt`) exists in source but its
manifest `<service>` entry is commented out — it is currently dead code.

### Module map

| Path | Responsibility |
| --- | --- |
| `bluetooth/BluetoothConnectionManager.kt` | Global holder for the two live sockets (`aacpSocket`, `attSocket`) plus `createBluetoothSocket()`, which reflects over `BluetoothSocket`'s private constructors and tries five known signatures to open an L2CAP channel on a given PSM. |
| `bluetooth/AACPManager.kt` | The AACP protocol engine (~1.4k lines): opcode table, packet framing/parsing, `ControlCommandIdentifiers`, and the `PacketCallback` fan-out interface (battery, ear detection, head tracking, stem press, ownership, capabilities, custom EQ, …). |
| `bluetooth/ATTManagerv2` (`ATTManager.kt`) | Hand-rolled ATT client spoken over the second L2CAP socket. Reads/writes characteristics by hard-coded handle (`ATTHandles`, `ATTCCCDHandles`) for hearing-aid and accessibility features. |
| `bluetooth/BLEManager.kt` | BLE advertisement scanner. Decodes Apple's proximity-pairing manufacturer data into `AirPodsStatus` (battery, lid state, in-ear state) and reports changes through `AirPodsStatusListener`. |
| `services/` | Foreground service, QS tile service. Also `object ServiceManager` (`AirPodsService.kt:142`) — a global service locator that lets widgets, overlays and the QS tile reach the service without binding. |
| `data/` | Device models and protocol payload types (see below). |
| `data/updates/` | In-app release-notes model (`UpdateItem`, `Updates`). |
| `presentation/screens/` | ~23 Compose screens, one per settings surface, plus `onboarding/`. |
| `presentation/components/` | ~26 reusable Compose components, mostly `Styled*`-prefixed (`StyledScaffold`, `StyledSwitch`, …). |
| `presentation/viewmodel/` | `AirPodsViewModel`, `AppSettingsViewModel`, `PurchaseViewModel`. |
| `presentation/navigation/` | Navigation 3: `Screen.kt` (sealed `NavKey` hierarchy), `NavigationRoot.kt` (a `SnapshotStateList<Screen>` backstack), `AppNavGraph.kt` (`NavDisplay`). |
| `presentation/overlays/` | `IslandWindow`, `PopupWindow` — `SYSTEM_ALERT_WINDOW` overlays (the "connected"/"taken over" popups). |
| `presentation/theme/` | `Theme.kt`, `Color.kt`, `Type.kt`, and `LocalDesignSystem.kt` — `DesignSystem.Apple` (default) vs `.Material`. |
| `presentation/widgets/` | App widgets. |
| `billing/` | Flavor-swapped donation/purchase layer: `BillingProvider` interface with `PlayBillingProvider` and `FOSSBillingProvider`, picked by `BillingProviderFactory`. |
| `utils/` | Xposed glue (`KotlinModule`, `XposedState`, `XposedServiceHolder`, `RadareOffsetFinder`), `RootlessSupport`, `SystemAPIUtils`, `MediaController`, `HeadOrientation`, `GestureDetector`/`GestureFeedback`, `BluetoothCryptography`, `LogCollector`. |
| `src/main/cpp/` | Two NDK targets: `bluetooth_socket` (hidden-API exemption via `JNI_OnLoad`) and `l2c_fcr_hook` (the Xposed native hook, bundling a vendored XZ Embedded decoder in `cpp/xz/` used for symbol lookup). |
| `src/main/res-apple/` | Extra resource directory merged into `main` via `sourceSets` — Apple-derived assets (`font/sf_pro.otf`, AirPods Pro 2 renders). |

### Data models

- `data/AirPods.kt` — `AirPodsBase` describes one model (model numbers, display name, drawables, `capabilities: Set<Capability>`, `isHeadset`). Concrete subclasses cover the whole line: `AirPods`, `AirPods2/3/4`, `AirPods4ANC`, `AirPodsPro1`, `AirPodsPro2Lightning`, `AirPodsPro2USBC`, `AirPodsPro3`, `AirPodsMaxLightning`, `AirPodsMaxUSBC`. `AirPodsInstance` is the runtime pairing of a model with the actual serials/name. `Capability` gates which UI is shown.
- `data/Packets.kt` — does triple duty: the `AirPodsNotifications.*` broadcast
  action constants, the packet parsers/state holders nested under them
  (`EarDetection`, `ANC`, `BatteryNotification`, `ConversationalAwarenessNotification`),
  and the `Battery` (`@Parcelize`) / `BatteryComponent` / `BatteryStatus` models
  (`HEADSET=1, LEFT=4, RIGHT=2, CASE=8`). Its own header comment says it should be
  split up.
- `data/ControlCommandRepository.kt` — control-command state store.
- `data/CustomEq.kt`, `HearingAid.kt`, `Transparency.kt`, `StemAction.kt` — feature payload models.
- Protocol vocabularies live on `AACPManager.Companion`: `Opcodes` (20 entries —
  `BATTERY_INFO 0x04`, `EAR_DETECTION 0x06`, `CONTROL_COMMAND 0x09`,
  `HEADTRACKING 0x17`, `CUSTOM_EQ 0x63`, plus the handoff set),
  `ControlCommandIdentifiers` (~40 — `OWNS_CONNECTION 0x06`, `LISTENING_MODE 0x0D`,
  `HEARING_AID 0x2C`, `STEM_CONFIG 0x39`, …), and `StemPressType` /
  `StemPressBudType` / `ProximityKeyType` / `AudioSourceType`. `ATTHandles` and
  `ATTCCCDHandles` in `bluetooth/ATTManager.kt` carry the hard-coded ATT handles
  (`TRANSPARENCY 0x18`, `LOUD_SOUND_REDUCTION 0x1B`, `HEARING_AID 0x2A`).
- `AirPodsUiState` (`presentation/viewmodel/AirPodsViewModel.kt`) is the ~28-field
  immutable snapshot the UI renders; `AirPodsService.ServiceConfig` is the ~35-field
  mirror of `SharedPreferences("settings")`.
- `data/XposedRemotePref*.kt` — a `ContentProvider`-backed preference bridge so the Xposed hook (running inside `com.android.bluetooth`) can read the app's settings.

### Data flow

There are two ingest paths. The L2CAP session is authoritative; BLE is the
fallback used when no session exists.

**A — BLE advertisements (no connection needed).** `BLEManager` scans for Apple
manufacturer data (company ID 76, proximity-pairing type `0x07`), decrypts it
with the IRK/ENC keys previously fetched over AACP, and reports
`BLEManager.AirPodsStatus` through `AirPodsStatusListener`. Every handler in
`AirPodsService` short-circuits with
`if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return`.
A `bleOnlyMode` config flag exists.

**B — AACP over L2CAP (authoritative).**

```
AirPodsService.connectToSocket()
  UUID 74ec2172-0bad-4d01-8f77-997b2be0722a, PSM 4097 (0x1001)
    └─ createBluetoothSocket()      reflective ctor, 5 signature fallbacks
    └─ connectWithTimeout(6000ms)   own thread; aborts by closing the socket
    └─ BluetoothConnectionManager.aacpSocket = socket
    └─ handshake / feature flags / requestNotifications / requestProximityKeys
    └─ blocking read loop on Dispatchers.IO
         └─ AACPManager.receivePacket()
              header must be 04 00 04 00; dispatch on the opcode byte
              └─ PacketCallback fan-out
    └─ finally { tearDownConnection(socket) }
```

An optional side channel, `connectAttChannel()`, opens a **second** socket on
**PSM 31** with a null UUID — only when the Xposed `vendor_id_hook` preference is
on. `ATTManagerv2` runs a daemon `ATT-Reader` thread and correlates
request/response through a `ConcurrentHashMap<Byte, LinkedBlockingQueue<ByteArray>>`.

**Callback → state → UI.** `AirPodsService` implements `AACPManager.PacketCallback`,
mutates its state holders (`batteryNotification`, `earDetectionNotification`,
`ancNotification`, `conversationAwarenessNotification`,
`aacpManager.controlCommandStatusList`), refreshes the notification/widgets/system
Bluetooth metadata, and **broadcasts package-scoped Intents**
(`AirPodsNotifications.*`, defined in `data/Packets.kt`) as the internal event bus.

```
AirPodsService ──broadcast Intents──► AirPodsViewModel.observeBroadcasts()
               ──bound reference────► service.aacpManager / service.attManager
                                      observeAACP() / observeControl() / observeATT()
                                          │
                                          ▼
                            _uiState: MutableStateFlow<AirPodsUiState>
                                          │
                                          ▼
                    Compose screens (collectAsState) · widgets · QS tile · overlays
```

Outbound commands run the same path in reverse: a screen calls an
`AirPodsViewModel` method, which calls into the bound `AirPodsService` or straight
through to `service.aacpManager` / `service.attManager`.

**Persistence.** No database and no DataStore — `SharedPreferences` only:
`"settings"` (mirrored by the ~35-field `AirPodsService.ServiceConfig`) and
`"packet_logs"` (a rolling 1000-entry log exposed as
`packetLogsFlow: StateFlow<Set<String>>`). Xposed-scoped preferences go through
`data/XposedRemotePref*.kt` and `utils/XposedServiceHolder.kt`.

Notable constraint recorded in the code: AirPods accept **only one** L2CAP
channel on PSM `0x1001` and tear the link down on a second attempt
(`services/AirPodsService.kt:2801`, which enumerates the six competing triggers
that can race to connect). That is why socket ownership is centralised in a
single object, connects are serialised behind an `AtomicBoolean`, and teardown
does an identity check before closing.

### Privileged / root integration

Three separate, independent escalation paths — none of them mandatory:

1. **Xposed module** (`KotlinModule` + `libl2c_fcr_hook.so`, scoped in
   `META-INF/xposed/scope.list` to `com.android.bluetooth`,
   `com.google.android.bluetooth`, `com.android.settings`,
   `com.google.android.settings`). Works around a Fluoride L2CAP/FCR bug that
   otherwise prevents connecting, and enables Apple VendorID spoofing.
   `RadareOffsetFinder` locates the patch site at runtime.
2. **Root module** (`root-module-manual/`) — installs the APK to
   `/system/priv-app` and drops
   `system/etc/permissions/privapp-permissions-librepods.xml`, granting
   `BLUETOOTH_PRIVILEGED`, `MODIFY_PHONE_STATE`, `INTERACT_ACROSS_USERS` and
   `LOCAL_MAC_ADDRESS`. `LOCAL_MAC_ADDRESS` is what makes handoff/takeover work.
   `customize.sh` detects a conflicting `/data/app` copy and warns, because a
   signature mismatch silently disables the whole module.
3. **Rootless path** (`utils/RootlessSupport.kt`) — used on OS builds that
   already expose what is needed (Pixel on Android 16 QPR3, ColorOS/OxygenOS 16,
   realme UI 7.0).

---

## Linux app (`linux/`, legacy)

Qt6 C++/QML single-binary desktop client. `linux/README.md` states this version
is superseded by a Rust rewrite on the `linux/rust` branch.

| Path | Responsibility |
| --- | --- |
| `main.cpp`, `Main.qml` | Entry point and root QML window. |
| `BluetoothMonitor.{h,cpp}` | Connection lifecycle. |
| `airpods_packets.h`, `enums.h` | AACP packet/opcode definitions (the C++ mirror of `AACPManager`). |
| `ble/blemanager.*`, `ble/bleutils.*` | BLE advertisement scanning/decoding. |
| `media/mediacontroller.*`, `media/pulseaudiocontroller.*`, `media/playerstatuswatcher.*` | MPRIS/PulseAudio play-pause on ear detection. |
| `trayiconmanager.*` | System-tray UI. |
| `battery.hpp`, `eardetection.hpp`, `deviceinfo.hpp`, `BasicControlCommand.hpp` | Feature state holders. |
| `librepods-ctl.cpp` | Second executable — a small CLI/IPC control client (`Qt6::Core`, `Qt6::Network` only). |
| `thirdparty/QR-Code-generator/` | Vendored QR encoder, used by `KeysQRDialog.qml` to hand proximity keys to the phone. |
| `translations/` | Qt Linguist translations. Only `librepods_tr.ts` is listed in `TS_FILES`; the `it_IT` and `zh_TW` files are never compiled. |
| `hearing-aid-adjustments.py` | Standalone PyQt5 tool that speaks ATT over an L2CAP socket on PSM 31 — the desktop counterpart of `ATTManagerv2`. |

Two CMake targets: `librepods` (Qt6 Quick/Widgets/Bluetooth/DBus + OpenSSL + libpulse)
and `librepods-ctl`. Both have `install()` rules; the app also installs
`assets/me.kavishdevar.librepods.desktop`.

---

## Build, CI and release

### Android

| What | Command |
| --- | --- |
| Debug APK (what PR CI builds) | `cd android && ./gradlew assembleFossDebug` |
| Full release bundle | `cd android && ./gradlew packageReleaseArtifacts` |

`packageReleaseArtifacts` is a custom aggregate task defined in
`android/app/build.gradle.kts` (line ~280). It depends on `collectReleaseArtifacts`
(a `Copy` that renames APKs into `release/`) and on a `Zip` task that stamps the
app version/versionCode into `root-module-manual/module.prop` and packages the
Magisk module — so **the APK and the root module are versioned and released
together**.

Toolchain: Gradle 9.4.1 (wrapper), AGP 9.2.1, Kotlin 2.3.21, Java 21, `compileSdk`/`targetSdk` 37,
`minSdk` 33 (`foss`) / 36 (`play`), NDK `30.0.14904198`, CMake 3.22.1.

Flavors (`env` dimension): `foss` (`PLAY_BUILD=false`) and `play`
(`PLAY_BUILD=true`, `minSdk` 36, `-play` version suffix). Release builds are
minified + resource-shrunk with R8.

Signing is read from `android/local.properties`
(`RELEASE_STORE_FILE`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`);
when absent the build falls back to unsigned/debug signing, so a clean checkout
still builds.

CI (`.github/workflows/ci-android.yml`): PRs build `assembleFossDebug`; pushes to
any branch build `packageReleaseArtifacts`, publish a `nightly-<short-sha>`
prerelease with APKs, AAB and root-module zips, and post to Discord.

### Linux (legacy Qt)

```
cd linux && mkdir build && cd build && cmake .. && make -j $(nproc) && ./librepods
```

CI (`.github/workflows/ci-linux.yml`) is `workflow_dispatch`-only (the `push`
trigger is commented out) and builds with `cmake .. -G Ninja && ninja`.

### Linux (Rust rewrite)

`.github/workflows/ci-linux-rust.yml` builds `linux-rust/` with
`cargo build --release` + `just`, producing an AppImage. **That directory does not
exist on this branch** — the workflow only fires on the `linux/rust` branch and
`linux-v*` tags.

## External dependencies

- **Android:** version catalog at `android/gradle/libs.versions.toml`. Compose BOM
  2026.05.00 + Material3 `1.5.0-alpha21`, Navigation 3 (`navigation3-runtime`/`-ui`),
  `accompanist-permissions`, `haze` + `haze-materials` and Kyant0 `backdrop` (the
  liquid-glass UI effects), `aboutlibraries` (licenses screen),
  `com.android.billingclient:billing-ktx`, Play `review`/`review-ktx`,
  `io.github.libxposed` `api` (compileOnly) + `service`.
- **Linux:** Qt6 (Quick, Widgets, Bluetooth, DBus, LinguistTools), OpenSSL,
  libpulse via pkg-config, vendored QR-Code-generator.

## Permissions of note

Ordinary: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`),
`BLUETOOTH_ADVERTISE`, `BLUETOOTH_ADMIN`, `BLUETOOTH`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
`RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`,
`ANSWER_PHONE_CALLS`, `MODIFY_AUDIO_SETTINGS`, `com.android.vending.BILLING`.

Protected (declared with `tools:ignore="ProtectedPermissions"`, granted only when
the root module is installed): `BLUETOOTH_PRIVILEGED`, `MODIFY_PHONE_STATE`,
`LOCAL_MAC_ADDRESS`, `INTERACT_ACROSS_USERS`.

`INTERNET`, `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are present but
**commented out** — the app is offline by design.
