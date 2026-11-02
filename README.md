# Unofficial BitChord macOS Fork

> An independent Apple Silicon macOS fork of [BitChord](https://github.com/kushagrasinghx/BitChord). This fork is **not affiliated with, maintained by, or officially endorsed by the upstream BitChord developers**.

Upstream desktop development has been discontinued. This repository continues the desktop port independently. The source baseline is **1.9.29** and targets **Apple Silicon (arm64) macOS**.

**Modification notice:** This fork's 2026 macOS publication candidate is a clean source snapshot based on upstream v1.9. It is separate from the original privately validated build and does not include that private commit history or DMG. Upstream copyright, license, and contributor attribution remain with the original project.

## Publication candidate status

The `macos` branch passes `:desktopApp:test` (372 tests), `:shared:jvmTest` (122), and `:sharedUi:jvmTest` (42) on 2 November 2026. These are automated source tests; they do not verify live YouTube playback or DAC output. The candidate's 24-bit PCM and CoreAudio rate-matching code is retained from the private baseline, but this candidate has not been physically tested with a DAC.

The original private v1.9.29 build passed ONIX Alpha XI1 physical acceptance on 2 November 2026. That result applies only to the original private build, not this modified candidate.

## Candidate behavior

- macOS desktop playback, local FLAC playback, addon framework, 24-bit PCM output path, and opt-in CoreAudio hardware-rate matching remain in the source candidate.
- The desktop BotGuard/PoToken integration is disabled; the candidate does not reuse the upstream Google API key or call that desktop endpoint. Shared Android PoToken code is unchanged.
- Ordinary public YouTube tracks are expected to use token-free InnerTubeX profiles or the NewPipe fallback. This was not verified with live playback. Age-restricted and other token-dependent YouTube content may fail.
- Local files and third-party addon playback use separate paths from the desktop PoToken integration.

The separate [`bitperfect-validation` branch](https://github.com/abbypangeran/BitChord/tree/bitperfect-validation) contains the PCM null-test and its results at Java Sound's `SourceDataLine.write()` boundary. That result does not establish end-to-end bit-perfect delivery through Java Sound, CoreAudio, USB, the driver, or a DAC. This fork makes no hardware bit-perfect playback claim.

## Limitations

- Physical DAC acceptance applies only to the original private build with an ONIX Alpha XI1; the publication candidate has not been hardware-tested. Other DACs and Intel Macs are unverified.
- End-to-end CoreAudio/USB/DAC bit-perfect delivery remains unverified.
- Age-restricted and other PoToken-dependent YouTube playback may be unavailable.
- Native macOS window zoom and tiling behavior still needs polish.
- Android instrumentation tests were not run because an Android SDK was unavailable.
- Individual addon services and account flows require separate validation.
- Candidate builds are ad hoc signed and not notarized; macOS may show a first-launch security prompt.

## Build on Apple Silicon macOS

Install JDK 21 and CMake, then build the desktop tests and candidate DMG:

```sh
brew install openjdk@21 cmake
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :desktopApp:test :desktopApp:packageDmg
```

The candidate DMG is written under `desktopApp/build/compose/binaries/main/dmg/`. See the [macOS build and audio notes](https://github.com/abbypangeran/BitChord/blob/macos/docs/macos-preview.md) and [PCM sample-accuracy validation](https://github.com/abbypangeran/BitChord/blob/bitperfect-validation/docs/bitperfect-validation.md).

## Attribution and license

This fork is based on the original [BitChord project](https://github.com/kushagrasinghx/BitChord). The upstream project, its authors, and its contributors retain attribution for their work. This fork is licensed under the **GNU General Public License v3.0 (GPLv3)**; see [LICENSE](LICENSE). The fork is independently maintained and does not imply upstream endorsement.
