#!/usr/bin/env bash
# BitChord PCM bit-accuracy check: fixtures -> production path capture -> independent null test.
# Needs flac, metaflac, ffmpeg, python3 + numpy. Opens no audio device; changes no app settings.
# Usage: tools/bitperfect/run.sh [artefact-dir]   (default: build/bitperfect, git-ignored)
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(python3 -c 'import os,sys;print(os.path.abspath(sys.argv[1]))' "${1:-build/bitperfect}")"
mkdir -p "$OUT/state"
export JAVA_HOME="${JAVA_HOME_21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"  # as docs/macos-preview.md
python3 tools/bitperfect/generate.py "$OUT"
# XDG_STATE_HOME keeps the sink's diagnostic log lines out of the user's real desktop.log.
BITCHORD_BITPERFECT_DIR="$OUT" XDG_STATE_HOME="$OUT/state" ./gradlew --no-daemon -q \
  :desktopApp:test --rerun --tests 'com.music.bitchord.desktop.DesktopBitPerfectValidationTest'
python3 tools/bitperfect/compare.py "$OUT"
