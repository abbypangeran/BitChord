#!/usr/bin/env python3
"""Independent sample-level null test of BitChord's captured SourceDataLine bytes.

Decodes every capture with NumPy (no BitChord code), aligns it with the reference PCM, and writes
results.json. Exits 1 when a capture that should be lossless differs in any sample.

Test instrumentation only; not part of the application.
"""
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/bitperfect").resolve()
MAX_OFFSET = 4096


def decode(raw, fmt):
    width = fmt["bytesPerSample"]
    b = np.frombuffer(raw, np.uint8)
    if fmt["isFloat"]:
        return b.view("<f4").astype(np.float64), "float"
    b = b.reshape(-1, width)
    padded = np.zeros((len(b), 4), np.uint8)
    padded[:, 4 - width:] = b
    return (padded.view("<i4").ravel() >> (32 - 8 * width)).astype(np.int64), "int"


def normalise(values, kind, out_bits, src_bits):
    """Output values in source-depth LSBs (float64), plus whether the mapping was lossless."""
    if kind == "float":
        scaled = values * float(1 << (src_bits - 1))
        return scaled, bool(np.all(scaled == np.round(scaled)))
    shift = out_bits - src_bits
    if shift >= 0:
        padding_clean = bool(np.all((values & ((1 << shift) - 1)) == 0))
        return (values >> shift).astype(np.float64) if padding_clean else values / float(1 << shift), padding_clean
    return (values << -shift).astype(np.float64), False  # 24-bit source into 16 bits: lossy by definition


def best_offset(out, ref, channels, tol):
    """Leading offset in frames, measured, not assumed; 0 unless another one matches better."""
    probe = min(len(ref), 8192 * channels)
    def misses(k):
        a = out[k * channels: k * channels + probe] if k >= 0 else out[:probe]
        r = ref[:probe] if k >= 0 else ref[-k * channels: -k * channels + probe]
        n = min(len(a), len(r))
        return np.count_nonzero(np.abs(a[:n] - r[:n]) > tol) if n else probe
    if misses(0) == 0:
        return 0
    return min(range(-MAX_OFFSET, MAX_OFFSET + 1), key=misses)


def main():
    manifest = {f["name"]: f for f in json.loads((ROOT / "fixtures/manifest.json").read_text())["fixtures"]}
    capture = json.loads((ROOT / "captures/capture.json").read_text())
    results, unexpected = [], 0
    for c in capture["captures"]:
        fx = manifest[c["fixture"]]
        fmt = c["sinkFormat"]
        ch, src_bits, out_bits = fx["channels"], fx["bitsPerSample"], 8 * fmt["bytesPerSample"]
        ref = np.fromfile(ROOT / "fixtures" / fx["reference"], "<i4").astype(np.float64)
        values, kind = decode((ROOT / "captures" / c["capture"]).read_bytes(), fmt)
        out, lossless_mapping = normalise(values, kind, out_bits, src_bits)
        # Requantised output differs by up to one output step; attenuated output is compared as-is.
        tol = float(1 << (src_bits - out_bits)) if out_bits < src_bits else 0.0
        offset = best_offset(out, ref, ch, tol) if c["gain"] == 1 else 0
        a = out[offset * ch:] if offset >= 0 else out
        r = ref if offset >= 0 else ref[-offset * ch:]
        n = min(len(a), len(r))
        diff = a[:n] - r[:n]
        bad = np.flatnonzero(diff != 0)
        missing = abs(len(a) - len(r))
        mismatches = int(len(bad) + missing)
        first = None
        if len(bad):
            i = int(bad[0])
            lo, hi = max(0, i - 3), min(n, i + 4)
            first = {"sampleIndex": i, "frame": i // ch, "channel": i % ch,
                     "reference": r[lo:hi].tolist(), "output": a[lo:hi].tolist()}
        quantiser = None
        if out_bits < src_bits and kind == "int" and offset == 0 and len(values) == len(r):
            k = float(1 << (src_bits - out_bits))
            rs = r / k
            quantiser = {name: bool(np.array_equal(values, q.astype(np.int64))) for name, q in {
                "truncateTowardZero": np.trunc(rs), "floor": np.floor(rs),
                "roundHalfAwayFromZero": np.sign(rs) * np.floor(np.abs(rs) + 0.5)}.items()}
        exact = mismatches == 0 and offset == 0 and fmt["sampleRate"] == fx["sampleRate"] and fmt["channels"] == ch
        expected_lossless = c["expectedLossless"]
        verdict = "EXACT" if exact else ("NOT_EXACT" if expected_lossless else "LOSSY_EXPECTED")
        if expected_lossless and not exact:
            unexpected += 1
        results.append({
            "fixture": c["fixture"], "source": f"{src_bits}-bit/{fx['sampleRate']} Hz/{ch} ch",
            "output": c["output"], "outputFormat": fmt, "gain": c["gain"],
            "framesReference": fx["frames"], "framesOutput": len(a) // ch, "leadingOffsetFrames": offset,
            "samplesCompared": int(n), "mismatches": mismatches,
            "exactMatchPercent": round(100.0 * (n - len(bad)) / max(1, len(r)), 6),
            "maxAbsErrorLsb": float(np.max(np.abs(diff))) if n else None,
            "rmsErrorLsb": float(np.sqrt(np.mean(diff ** 2))) if n else None,
            "losslessRepresentation": lossless_mapping, "requantiser": quantiser, "firstMismatch": first,
            "kotlinMismatches": c["kotlinMismatches"], "verdict": verdict,
        })
        print(f"{verdict:15s} {c['fixture']:28s} {c['output']:18s} n={n} mism={mismatches} "
              f"max={results[-1]['maxAbsErrorLsb']} rms={results[-1]['rmsErrorLsb']:.4g}")
    lossless = [x for x in results if x["verdict"] != "LOSSY_EXPECTED"]
    overall = "EXACT" if unexpected == 0 and lossless else ("NOT_EXACT" if unexpected else "INCONCLUSIVE")
    summary = {"overall": overall, "scope": "Java Sound SourceDataLine.write boundary only; not CoreAudio/USB/DAC",
               "boundary": capture["boundary"], "hostJavaSoundNegotiation": capture["hostJavaSoundNegotiation"],
               "unexpectedFailures": unexpected, "results": results}
    (ROOT / "results.json").write_text(json.dumps(summary, indent=2))
    print(f"OVERALL {overall}  unexpected={unexpected}  -> {ROOT / 'results.json'}")
    sys.exit(1 if unexpected else 0)


if __name__ == "__main__":
    main()
