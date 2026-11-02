# BitChord v1.9 macOS publication candidate

This document distinguishes the original privately validated v1.9.29 build from the public source candidate. The candidate is a clean source snapshot based on upstream v1.9; it does not include the private development history or the validated DMG. Its desktop BotGuard/PoToken integration is disabled because authorization for the undocumented endpoint and reused key could not be established. The candidate has not received physical DAC acceptance.

The candidate uses Java 21, Compose Desktop, FFmpeg, ONNX Runtime, and the native Automix analyser. The packaged app includes its own Java runtime.

## Build and install

Install an ARM64 JDK 21 and CMake. On Homebrew:

```sh
brew install openjdk@21 cmake
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :desktopApp:test :desktopApp:packageDmg
./gradlew :desktopApp:run
```

The app and DMG are under `desktopApp/build/compose/binaries/main/app/` and `desktopApp/build/compose/binaries/main/dmg/`. Mount `BitChord 1.9-1.9.29.dmg`, then drag **BitChord 1.9.app** to Applications. It has a different app name and bundle ID from BitChord v1.8. macOS preview preferences, downloads, cache, and logs use separate locations. Existing `/Applications/BitChord.app` stays untouched.

This local build has an ad hoc signature, not a Developer ID signature or notarization. Gatekeeper may block a copied app. Use Finder's **Open** context menu and confirm the app if you trust this build, or use the app-specific **Open Anyway** control in System Settings. Do not disable Gatekeeper globally.

## Audio status

The engine requests the recording's sample rate and 32-bit float output first. Java Sound then tries 32-bit, 24-bit, and 16-bit signed little-endian PCM, followed by 48 kHz and 44.1 kHz when the native rate cannot open. During a crossfade, both tracks use the outgoing rate; after its queued audio and tempo buffer finish, the output renegotiates for the incoming track.

On macOS, the opt-in **Automatically match DAC sample rate** setting maps the exact Java Sound mixer to a CoreAudio device UID using its native device ID. When the selected device advertises the source rate, BitChord drains and closes its output line, requests the CoreAudio nominal rate, verifies the read-back, then reopens Java Sound. Unsupported or failed changes leave Java Sound playback available. The scrollable Audio Pipeline reports bridge initialization, device identity and availability, nominal hardware rate, matching outcome, and any mismatch, including explicit unavailable states. BitChord watches rate-change notifications and restores the prior rate only while its own change is still verifiable. It does not use exclusive or hog mode. Matching rates alone do not establish bit-perfect playback.

Before physical testing, read-only CoreAudio inspection identified the selected ONIX Alpha XI1, read its 768 kHz nominal rate, and reported support for 44.1 and 96 kHz. That inspection did not request a rate change; the acceptance below was subsequently performed on hardware.

## Original private build: ONIX Alpha XI1 physical acceptance

The following hardware result applies only to the original private v1.9.29 build. It has not been repeated on this publication candidate.

**Test date:** 2 November 2026

**Result:** `BITCHORD_1.9.29_ONIX_XI1_PHYSICAL_ACCEPTANCE = PASS`

**Hardware:** Apple Silicon MacBook Air with an ONIX Alpha XI1 USB DAC.

The user reports that all six hardware acceptance checks passed. With a verified 24-bit/44.1 kHz FLAC, BitChord's Java Sound output, macOS Audio MIDI Setup, and the ONIX display all showed 44.1 kHz. Repeating the check with a 96 kHz source showed 96 kHz at those outputs. Playback remained stable during the checks. Automatic rate matching also restored the prior hardware rate when BitChord still owned its change and respected a subsequent rate change made externally.

This records the user's hardware acceptance report; the earlier read-only probe and automated tests alone did not establish physical DAC behavior.

## Original private baseline archive

The validated Apple Silicon DMG remains in the local release archive and is not part of this candidate:

`BitChord-1.9.29-macos-arm64-ONIX-XI1-validated.dmg`

**SHA-256:** `5678955eda727ebd85af8aabb30b3ae9c9ae320120f9b94f1df7fe0c0ed6eb42`

The private source commits and annotated validation tag are intentionally absent from the publication candidate's ancestry.

**Build environment:** macOS 27.0.1 (26A434), arm64, Homebrew OpenJDK 21.0.13.0.3, Gradle 8.11.1.

**Original private baseline automated results:** desktop 372 passed, shared 122 passed, shared UI 42 passed. The clean publication candidate also passed desktop 372, shared 122, and shared UI 42 tests on 2 November 2026. Android instrumentation tests were not executed because no Android SDK was available. The separate `bitperfect-validation` branch adds the PCM sample-accuracy test for this same candidate source.

**Archive verification:** copied DMG SHA-256 matched the expected digest and the source image; `hdiutil verify` reported a valid image. No rebuild was performed for this freeze.

The 14,818,810-sample PCM null test covers only bytes at Java Sound's `SourceDataLine.write()` boundary. End-to-end CoreAudio/USB/DAC bit-perfect delivery remains unverified. The original private build's physical DAC compatibility was established only for the ONIX Alpha XI1; this publication candidate has not been hardware-tested. Android instrumentation tests remain unrun.

The unresolved native macOS window zoom and tiling behavior remains documented and outside this audio change.

Search, settings, playback, and addon entry points remain in the desktop app. Actual addon server use and account sign-in require user-specific services and were not validated.

With desktop PoTokens disabled, InnerTubeX omits profiles that require a BotGuard token and retains its token-free profile selection and NewPipe fallback. Ordinary public tracks may continue to play; age-restricted and other token-dependent YouTube content may fail. Local files and third-party addon playback use separate paths. This expected loss is based on the resolver and client catalog; live YouTube playback was not tested here.
