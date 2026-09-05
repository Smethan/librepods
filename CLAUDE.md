<!-- claude-setup:start -->
## Project docs (auto-loaded context)
@docs/ARCHITECTURE.md
@docs/STANDARDS.md
@docs/DECISIONS.md

## Commands
All Android commands run from `android/`; all Linux commands from `linux/`.

- Install:   no dependency-install step — Gradle resolves on first build (`cd android && ./gradlew --version` warms the wrapper). For the Linux client, install Qt6 (Quick/Widgets/Bluetooth/DBus/LinguistTools), OpenSSL, libpulse and CMake — see `linux/README.md` for per-distro package names.
- Run/dev:   `cd android && ./gradlew installFossDebug` (build only: `./gradlew assembleFossDebug` — this is what PR CI runs). Full release set: `./gradlew packageReleaseArtifacts`. Linux: `cd linux && mkdir -p build && cd build && cmake .. && make -j $(nproc) && ./librepods`.
- Test:      none — this repo has no tests (no `src/test/`, no `src/androidTest/`, no test task in CI).
- Lint:      not configured — no ktlint, detekt, clang-format or `lint.xml`, and CI runs no lint task. AGP's built-in `./gradlew lintFossDebug` is available but unused and unenforced.
- Typecheck: no separate step — the Kotlin/C++ compile is the typecheck. `cd android && ./gradlew compileFossDebugKotlin`.

## Notes
- Java 21 and NDK `30.0.14904198` are required to build the Android app.
- Release signing reads `android/local.properties`; without it the build falls back to debug signing, so a clean checkout still builds.
- The APK and the Magisk module in `root-module-manual/` are versioned together from `appVersionName`/`appVersionCode` in `android/app/build.gradle.kts`. Do not hand-edit the version in `root-module-manual/module.prop`.
<!-- claude-setup:end -->
