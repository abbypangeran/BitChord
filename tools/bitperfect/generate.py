#!/usr/bin/env python3
"""Deterministic, copyright-free PCM test signals for the BitChord bit-accuracy check.

Writes, per signal: reference PCM (int32 little-endian, one value per sample, interleaved),
a FLAC encoded by the reference `flac` encoder, and a manifest. Every FLAC is decoded again by
two independent decoders (`flac -d` and the FFmpeg CLI) and must match the reference exactly
before BitChord is tested at all.

Test instrumentation only; not part of the application.
"""
import hashlib
import json
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/bitperfect").resolve() / "fixtures"
SECONDS = 2
TAIL = 37  # deliberately not a multiple of any FLAC block size


def signals(rate, bits, seed):
    """Name -> (frames, 2) int array; left and right always differ, so a swap is visible."""
    top, bottom = (1 << (bits - 1)) - 1, -(1 << (bits - 1))
    frames = rate * SECONDS + TAIL
    rng = np.random.default_rng(seed)
    out = {}

    s = np.zeros((frames, 2), np.int64)
    for i, (l, r) in enumerate([(1, 0), (0, -1), (top, 0), (0, bottom), (-1, 1), (bottom, top)]):
        s[1000 + i * 4096] = (l, r)
    s[frames - 1] = (1, -1)
    out["silence_impulse"] = s

    s = np.zeros((frames, 2), np.int64)
    s[0::2, 0], s[1::2, 0] = top, bottom
    s[0::2, 1], s[1::2, 1] = bottom, top
    s[frames // 2:, 0] = np.where(np.arange(frames - frames // 2) % 3 == 0, top, top - 1)
    s[frames // 2:, 1] = np.where(np.arange(frames - frames // 2) % 3 == 0, bottom, bottom + 1)
    out["full_scale"] = s

    s = np.zeros((frames, 2), np.int64)
    s[:, 0] = (np.arange(frames) % 9) - 4               # -4..+4 LSB staircase
    s[:, 1] = np.where((np.arange(frames) // 7) % 2 == 0, 1, -1)  # +/-1 LSB square
    out["lsb"] = s

    out["random"] = rng.integers(bottom, top + 1, size=(frames, 2), dtype=np.int64)

    t = np.arange(frames) / rate
    amp = top * 10 ** (-1 / 20)
    left = (np.sin(2 * np.pi * 100 * t) + np.sin(2 * np.pi * 1000 * t) + np.sin(2 * np.pi * 10000 * t)) / 3
    right = (np.sin(2 * np.pi * 440 * t) + np.sin(2 * np.pi * 3000 * t) + np.sin(2 * np.pi * 15000 * t)) / 3
    out["sines"] = np.round(np.stack([left, right], 1) * amp).astype(np.int64)
    return out


def write_wav(path, pcm, rate, bits):
    width = bits // 8
    data = pcm.astype("<i4").view(np.uint8).reshape(-1, 4)[:, :width].tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(data)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True).stdout


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    flac_version = run("flac", "--version").decode().strip()
    ffmpeg_version = run("ffmpeg", "-version").decode().splitlines()[0]
    manifest = {"encoder": flac_version, "verifier": ffmpeg_version, "fixtures": []}
    plan = [(44_100, 24), (48_000, 24), (96_000, 24), (44_100, 16)]
    for seed, (rate, bits) in enumerate(plan):
        for name, pcm in signals(rate, bits, seed + 1).items():
            stem = f"{name}_{bits}b_{rate}"
            ref = OUT / f"{stem}.ref.s32le"
            wav = OUT / f"{stem}.wav"
            flac = OUT / f"{stem}.flac"
            pcm.astype("<i4").tofile(ref)
            write_wav(wav, pcm, rate, bits)
            run("flac", "--silent", "--force", "--verify", "-8", "-o", str(flac), str(wav))
            wav.unlink()

            # Independent decode 1: the reference libFLAC decoder.
            raw = run("flac", "--silent", "--decode", "--force-raw-format", "--endian=little",
                      "--sign=signed", "--stdout", str(flac))
            width = bits // 8
            b = np.frombuffer(raw, np.uint8).reshape(-1, width)
            padded = np.zeros((len(b), 4), np.uint8)
            padded[:, 4 - width:] = b
            flac_dec = padded.view("<i4").ravel() >> (32 - bits)
            # Independent decode 2: FFmpeg CLI (Homebrew build, not BitChord's bundled JavaCPP one).
            ff = np.frombuffer(run("ffmpeg", "-v", "error", "-i", str(flac), "-f", "s32le", "-"), "<i4") >> (32 - bits)
            reference = pcm.ravel()
            ok_flac = flac_dec.shape == reference.shape and np.array_equal(flac_dec, reference)
            ok_ff = ff.shape == reference.shape and np.array_equal(ff, reference)
            if not (ok_flac and ok_ff):
                sys.exit(f"reference decode mismatch for {stem}: flac={ok_flac} ffmpeg={ok_ff}")
            md5 = run("metaflac", "--show-md5sum", str(flac)).decode().strip()
            manifest["fixtures"].append({
                "name": stem, "signal": name, "sampleRate": rate, "bitsPerSample": bits, "channels": 2,
                "frames": int(pcm.shape[0]), "samples": int(pcm.size),
                "flac": flac.name, "flacSha256": sha256(flac), "flacStreamMd5": md5,
                "reference": ref.name, "referenceSha256": sha256(ref),
                "independentDecode": {"flac": ok_flac, "ffmpeg": ok_ff},
            })
            print(f"ok  {stem}  frames={pcm.shape[0]}")
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
