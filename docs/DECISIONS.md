# Decisions

Architectural decisions reconstructed from the code, build files, commit history
and README. Each entry states the evidence it was inferred from. These were not
written at the time — treat them as a faithful reading of the tree, and correct
them where the original intent differed.

---

## 1. Reimplement Apple's AACP and ATT protocols from scratch

**Decision.** Speak Apple's proprietary AirPods protocol directly over raw L2CAP
sockets rather than relying on any standard Bluetooth profile or Apple software.

**Context.** Nearly every interesting AirPods feature — noise-control modes, ear
detection, per-bud battery, head tracking, hearing-aid configuration, handoff —
is carried over Apple's own protocol, not AVRCP/HFP/A2DP. The project reverse
engineered it independently; `README.md` notes the Wireshark dissector by
[@pabloaul](https://github.com/pabloaul) came later and was not used for most of
the implementation, though it unblocked future work (two-way HQ audio, spatial
audio). The findings are checked in as `docs/AAP Definitions.md`,
`docs/opcodes.md`, `docs/control_commands.md`, `docs/battery_report.md` and
`docs/device-info.md`.

**Consequences.**
- Two parallel protocol engines must be kept in sync: `bluetooth/AACPManager.kt`
  (Kotlin) and `linux/airpods_packets.h` + `linux/enums.h` (C++). The Rust
  rewrite adds a third (`aacp.rs`, `att.rs`, translated from the Kotlin per the
  README).
- Every firmware change is a potential breakage, with no vendor contract to rely on.
- The protocol docs in `docs/` are load-bearing, not decoration.

---

## 2. Centralise the L2CAP sockets in one global object

**Decision.** `BluetoothConnectionManager` is a Kotlin `object` holding exactly
one `aacpSocket` (UUID `74ec2172-0bad-4d01-8f77-997b2be0722a`, PSM `4097`) and one
`attSocket` (null UUID, PSM `31`), and `AirPodsService` is the only thing that
opens them.

**Context.** AirPods accept only one L2CAP channel on PSM `0x1001` and tear the
link down if a second connection is attempted — this is stated in a comment at
`services/AirPodsService.kt:2801`. Commits `57d692c` ("refactor AACP socket
handling"), `af42614` ("fix rework ATT connection"), `619d08f` ("support AirPods
Max and stop dropping the AACP connection") and `5abbbe9` ("harden L2CAP connect
against hangs, ATT leaks and disconnect races") show this was learned the hard way.

**Consequences.**
- Connection state is process-global and not testable in isolation.
- Anything wanting device access must go through the bound `AirPodsService` (or
  `object ServiceManager` for components that cannot bind).
- Connects are serialised behind an `AtomicBoolean`, bounded by a 6-second
  `connectWithTimeout` that aborts by closing the socket from another thread, and
  teardown does an identity check so a late failure cannot close a newer socket.
- Socket lifecycle bugs surface as "the AirPods just disconnected", far from
  their cause — hence the repeated hardening commits.

---

## 3. Open the L2CAP socket by reflecting over private `BluetoothSocket` constructors

**Decision.** `createBluetoothSocket()` in
`bluetooth/BluetoothConnectionManager.kt` walks a list of five known private
constructor signatures, trying each until one works, and logs the full
constructor table when it fails.

**Context.** The public Android SDK offers no way to open an L2CAP channel on an
arbitrary PSM with the required parameters. The signature varies by Android
version — the first entry in the list is annotated `// A16QPR3`.

This is only legal because of a second piece: `src/main/cpp/bluetooth_socket.cpp`
calls `VMRuntime.setHiddenApiExemptions` for `BluetoothSocket` and
`BluetoothDevice` from `JNI_OnLoad`, with the class names XOR-obfuscated. The
library is loaded from `AirPodsService`'s `companion object init`, before any
reflection happens.

**Consequences.**
- The app ships a native library for no reason other than unlocking a hidden API.
- Every new Android release is a potential break, and the fallback chain must grow
  (the first entry is already annotated `// A16QPR3`).
- The verbose failure logging — dumping the full constructor table on failure —
  exists precisely because field diagnosis is the only way to find the next
  signature.
- This is the app's most fragile surface, and it is deliberate.

---

## 4. Ship an Xposed module to work around a platform Bluetooth bug

**Decision.** Bundle an Xposed module (Java entry `utils/KotlinModule.kt`, native
entry `src/main/cpp/l2c_fcr_hook.cpp` → `libl2c_fcr_hook.so`) scoped to
`com.android.bluetooth`, `com.google.android.bluetooth` and the Settings apps.

**Context.** `android/README.md` attributes this to a bug in the Fluoride
Bluetooth stack combined with Apple's non-compliance with the Bluetooth
standards, tracked at
[issuetracker 371713238](https://issuetracker.google.com/issues/371713238), fixed
upstream and expected to land for everyone in Android 17. Separately, the hook
enables Apple VendorID spoofing, which unlocks a further set of features.

The hook does two distinct things: it forces `l2c_fcr_chk_chan_modes` to return 1
(the Fluoride workaround), and it rewrites `BTA_DmSetLocalDiRecord`'s vendor to
`0x004C` — Apple — behind a separate runtime toggle.

**Consequences.**
- Root plus an Xposed framework is required on most devices today — a large
  install barrier that the README concedes "is not guaranteed to work on all
  devices".
- The native hook must locate its patch site at runtime, which is why
  `utils/RadareOffsetFinder.kt` and the bundled `xz` decoder exist in the CMake
  target.
- Once Android 17 is widespread this whole path becomes vestigial. It is already
  unnecessary on Pixel/Android 16 QPR3 and ColorOS/OxygenOS 16 — the
  `utils/RootlessSupport.kt` path.


---

## 4a. VendorID spoofing as an explicit, opt-in feature

**Decision.** Present the Bluetooth adapter as an Apple device, gated behind a
user-facing preference rather than always on.

**Context.** `l2c_fcr_hook.cpp`'s `fake_BTA_DmSetLocalDiRecord` rewrites the
vendor to `0x004C`, controlled by an atomic flag that `KotlinModule` toggles from
the remote `vendor_id_hook` preference. Both `connectAttChannel()` and
`takeOver()` refuse to run when that preference is off
(`services/AirPodsService.kt:2975, 2626-2633`). The Linux equivalent is documented
in `README.md` as `DeviceID = bluetooth:004C:0000:0000` in
`/etc/bluetooth/main.conf`.

**Consequences.**
- Hearing-aid support, transparency customisation and multipoint handoff are
  permanently root/Xposed-gated — the README marks them accordingly.
- The ATT side channel is not a fallback for the non-rooted path; it simply does
  not exist there.

---

## 4b. BLE advertisements as a degraded fallback for battery, lid and ear state

**Decision.** Scan Apple's proximity-pairing advertisements so battery and
wear state are available without an L2CAP session — but treat the session as
authoritative whenever it exists.

**Context.** `bluetooth/BLEManager.kt` filters on manufacturer ID 76 and
proximity-pairing type `0x07`, then decrypts using IRK/ENC keys fetched over AACP
(`ProximityKeyType`). Every BLE handler in `AirPodsService` short-circuits with
`if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return`, and a
`bleOnlyMode` config flag exists for devices where the session cannot be
established at all. `extras/proximity_keys.py` and the Linux `KeysQRDialog.qml`
exist to move those keys between devices.

**Consequences.**
- Two ingest paths must agree on the same state holders, and the guard is
  repeated at every call site rather than enforced structurally.
- The advertisement payload is earbud-shaped (left/right/case), so AirPods Max
  battery has to be collapsed to a single component on the way in
  (`data/Packets.kt:215-226`).
- Keys must be obtained over AACP first, so the fallback is only useful *after* a
  successful connection has happened at least once.

---

## 5. Make the root module optional and additive

**Decision.** Keep root strictly out of the critical path. The Magisk module in
`root-module-manual/` only installs the APK to `/system/priv-app` and grants four
extra permissions.

**Context.** `root-module-manual/system/etc/permissions/privapp-permissions-librepods.xml`
grants `BLUETOOTH_PRIVILEGED`, `MODIFY_PHONE_STATE`, `INTERACT_ACROSS_USERS` and
`LOCAL_MAC_ADDRESS`. `module.prop` describes these as enabling system-settings
battery display, the AirPods icon, and speaker fallback — and `android/README.md`
states the module "is optional and only provides extra features, but it is not
required for the app to work". The app is also on the Play Store, which forbids
requiring root.

**Consequences.**
- Feature availability is a matrix of (OS version × root × Xposed × module),
  which the READMEs have to spell out and the app has to detect at runtime.
- A signature mismatch between a `/data/app` copy and the module silently
  disables everything, because the `/data/app` copy wins. Commits `ac2d876`
  ("fix handoff and takeover silently doing nothing") and `2871166` ("fix root
  module versioning and add privileged-install diagnostics") added the
  `customize.sh` detection and warning for exactly this.

---

## 6. Version and release the APK and root module as one unit

**Decision.** `app/build.gradle.kts` stamps `version` and `versionCode` into
`root-module-manual/module.prop` while zipping the module, from the same
`appVersionName`/`appVersionCode` that drive the APK.

**Context.** The in-repo `module.prop` carries a comment saying its values are
"only a placeholder for manual installs", and the build script comment records
the failure it fixes: the hand-written version had "drifted several releases
behind the app it ships", so Magisk compared a stale `versionCode` against
`updateJson` and never offered module updates.

**Consequences.**
- `packageReleaseArtifacts` is the only correct way to cut a release; it
  aggregates the FOSS release/debug APKs, the Play AAB and both module zips into
  `release/`.
- The checked-in `module.prop` version is intentionally not authoritative — do
  not "fix" it by hand.

---

## 7. Two product flavors instead of two apps

**Decision.** A single `env` flavor dimension with `foss` and `play`, gated at
runtime by `BuildConfig.PLAY_BUILD` and a `BillingProvider` interface.

**Context.** `app/build.gradle.kts:85-95`. The Play flavor sets `minSdk = 36` and
a `-play` version suffix; the FOSS flavor targets `minSdk = 33`. Billing is
swapped by `billing/BillingProviderFactory.kt` between `PlayBillingProvider` and
`FOSSBillingProvider`. Commits `f86d7b9` ("fix PLAY_BUILD flag") and `3c3c0ed`
("add message for Play users who unlocked FOSS upgrade") show the two must
interoperate on a user's purchase state.

**Consequences.**
- No `src/foss/` or `src/play/` source sets — all divergence is runtime branching
  on one boolean, which keeps the build simple but scatters the conditionals.
- The FOSS build must never link Play Billing, so `FOSSBillingProvider` has to
  stay a real, working no-Play implementation.

---

## 8. Compose + Navigation 3, with two selectable design systems

**Decision.** Build the UI entirely in Jetpack Compose on Navigation 3, and ship
both an Apple-styled design system and a Material 3 Expressive one, switchable at
runtime.

**Context.** 60 files contain `@Composable`; there are no Fragments. Navigation is
`androidx.navigation3` (`presentation/navigation/`). `presentation/theme/Theme.kt`
takes an `m3eEnabled` flag and swaps typography between `AppleTypography` and
`MaterialTypography`, exposing the choice as `LocalDesignSystem`. Commit
`790e396` added the M3E theme; the Apple look was there first, and `cd40975`
("check premium for enabling Apple UI") ties it to the purchase state.

**Consequences.**
- Screens must render correctly under both systems — `AirPodsSettingsScreen.kt`
  has explicit `m3eEnabled` branches, and previews exist for both.
- `haze`/`backdrop` are pulled in for the Apple liquid-glass effects, and SF Pro
  is bundled in `res-apple/font/`, which the README commits to replacing.
- The M3E path needs a project-wide opt-in:
  `androidx.compose.material3.ExperimentalMaterial3ExpressiveApi` is added in
  `app/build.gradle.kts`, and Material3 is pinned to an alpha — the project rides
  Compose pre-releases.
- XML layouts survive only where Compose cannot go: `RemoteViews` widgets,
  notifications, and `SYSTEM_ALERT_WINDOW` overlays — which is why `viewBinding`
  stays enabled.

---

## 9. Service-owned state, ViewModels as thin adapters, no DI

**Decision.** `AirPodsService` holds all device state; `AirPodsViewModel` is
handed the bound service instance via `init(service, controlRepo, sharedPreferences)`
and adapts it to a `StateFlow<AirPodsUiState>`. No dependency-injection framework.

**Context.** The device connection must outlive the UI (foreground service,
widgets, QS tile, boot receiver), so the service is the natural owner. Hilt was
evaluated and backed out: it is in `gradle/libs.versions.toml` but commented out
at `app/build.gradle.kts:10,152-153`, alongside a commented `//@AndroidEntryPoint`
at `MainActivity.kt:66`.

**Consequences.**
- Screens reach through the ViewModel into `service.aacpManager` and
  `service.attManager` directly, so the protocol layer is effectively public to
  the UI. Commit `0f50eab` ("move ATT code to viewmodel from screens") pulled some
  of this back a layer, but not all.
- Nothing is constructor-injectable, which is part of why there are no tests.
- `SharedPreferences` doubles as both persistence and an event bus —
  `AirPodsService` implements `OnSharedPreferenceChangeListener` and reacts to
  writes.


---

## 9a. Package-scoped broadcast Intents as the internal event bus

**Decision.** `AirPodsService` announces state changes by
`sendBroadcast(...).setPackage(packageName)` using the `AirPodsNotifications.*`
action constants, and the ViewModel, widgets and overlays subscribe.

**Context.** The consumers are heterogeneous — a bound ViewModel, two
`AppWidgetProvider`s, a `TileService`, and two `WindowManager` overlays — and only
one of them can hold a service binding. Broadcasts reach all of them uniformly.
`object ServiceManager` (`services/AirPodsService.kt:142`) covers the cases that
need to call *into* the service without binding.

**Consequences.**
- Loose coupling, at the cost of untyped Intent-extra payloads with no
  compile-time contract.
- Duplicate action strings are undetectable: `AIRPODS_CONNECTED` and
  `AIRPODS_L2CAP_CONNECTED` are literally the same string today
  (`data/Packets.kt:85-86`), so nothing can distinguish the two events.

---

## 9b. Model AirPods Max as a first-class "headset" shape

**Decision.** Add `isHeadset` to `AirPodsBase` and guard the earbud-shaped
assumptions behind it, rather than special-casing Max at the call sites or
excluding it.

**Context.** Commit `619d08f` ("support AirPods Max and stop dropping the AACP
connection"). `AirPods.kt:34-38` documents the flag; `BatteryComponent.HEADSET = 1`
and `BatteryNotification` was made count-driven with the KDoc "Anything that
assumes exactly three components silently drops AirPods Max battery data"
(`data/Packets.kt:165-173`). Three behavioural branches key off it: suppress lid
popups (`AirPodsService.kt:309-312`), never disconnect audio on "all worn
charging" (`:900-909`), and collapse BLE pod batteries to one (`Packets.kt:215-226`).

**Consequences.**
- Both device shapes coexist; earbud logic is guarded, not removed.
- Anything new that iterates left/right/case, reads lid state, or assumes
  in-case detection must consult `isHeadset` first.
- `BatteryNotification.MAX_COMPONENTS = 3` against a four-entry
  `BatteryComponent.ALL` is the same class of assumption, still unfixed.

---

## 9c. Decide device support by runtime fingerprint, not by `minSdk`

**Decision.** Set `minSdk` to 33 and answer "does this device need root?" at
runtime in `utils/RootlessSupport.kt`.

**Context.** `isSupported()` returns true unconditionally for SDK ≥ 37, and
otherwise whitelists Pixel on 36 (by `Build.ID` prefix `CP1A`) and
OnePlus/Oppo/realme on ≥ 36 — the same ROMs `android/README.md` names. A
`bypass_device_check.v2` preference is the escape hatch, and onboarding has a
`NotSupportedPage`. Commit `bffb5c8` ("consider all A17 devices supported")
widened the rule.

**Consequences.**
- The support matrix is data in one file rather than a build-level constraint, so
  a newly-shipping ROM is a one-line change and not a release.
- It is a whitelist, so correct devices are wrongly rejected until someone reports
  it — hence the bypass, and the onboarding commits (`1783bb7`, `a7537f2`,
  `95ecc0e`) that keep reworking how it is presented.
- `minSdk 33` leaves genuinely dead branches behind, e.g. a `SDK_INT >= Q` (29)
  check at `AirPodsService.kt:428-435`.

---

## 10. No automated tests; CI verifies "it builds"

**Decision.** Ship without a test suite. CI builds artifacts and publishes
nightlies.

**Context.** There is no `src/test/` or `src/androidTest/` anywhere, no test task
in `.github/workflows/ci-android.yml`, and no lint or format check. The workflow
builds `assembleFossDebug` on PRs and `packageReleaseArtifacts` on every push,
then cuts a `nightly-<sha>` prerelease and posts to Discord.

**Consequences.**
- Correctness is established by field use on real hardware, which is why the
  nightly channel and `utils/LogCollector.kt` / the troubleshooting screen exist.
- The behaviour that matters most — L2CAP handshakes against real firmware —
  genuinely cannot be unit tested, but the parsing layers (`AACPManager`,
  `BLEManager` advertisement decoding, `data/`) could be, and currently are not.
- Regressions like `4c7a3cb`, `1381022` and `aca4373` are the expected cost.

---

## 11. Rewrite the Linux client in Rust rather than evolve the Qt one

**Decision.** Leave `linux/` (Qt6 C++/QML) in place on `main` while the
replacement is developed as `linux-rust/` on the `linux/rust` branch.

**Context.** `linux/README.md` announces the rewrite and points at
[PR #241](https://github.com/kavishdevar/librepods/pull/241), directing users to
nightly AppImages from `ci-linux-rust.yml`. The README notes `aacp.rs` and
`att.rs` were translated from the Kotlin with AI.

**Consequences.**
- `main` currently carries a Qt client whose CI is `workflow_dispatch`-only —
  effectively unbuilt and unmaintained.
- Two Linux implementations exist across branches; protocol fixes may need
  porting to both until the cutover.
- Distribution shifts from "build it yourself with CMake" to AppImage/Flatpak
  tarballs cut from `linux-v*` tags.

---

## 12. Offline by design

**Decision.** No network access. `INTERNET`, `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` are declared but commented out in `AndroidManifest.xml`,
and `BLUETOOTH_SCAN` carries `android:usesPermissionFlags="neverForLocation"`.

**Context.** The whole premise is liberating AirPods from a cloud ecosystem;
keeping the permission list minimal is both a privacy stance and what makes the
Play listing defensible.

**Consequences.**
- Update checking is external — `module.prop` points Magisk at
  `extras/update_nonpatch.json` on GitHub; the app itself does not phone home.
- Release notes ship in-app as static data (`data/updates/`), not fetched.
- Any future feature needing the network reopens this decision explicitly.
