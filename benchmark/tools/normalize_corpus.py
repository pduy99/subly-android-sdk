#!/usr/bin/env python3
"""Per-utterance loudness normalisation of the FLEURS benchmark corpus.

Each clip concatenates independently recorded FLEURS utterances, each
carrying whatever level its own recording session had — inside fleurs_en_01
that is a 36 dB spread. A whole-file gain cannot fix that, so this measures
and corrects each constituent utterance separately.

Sample count is preserved exactly (the manifest pins durationSec), and gain
moves on a ramp across the gaps so the noise floor doesn't step audibly at a
boundary.
"""
import array
import json
import math
import os
import re
import subprocess
import sys
import tempfile

SR = 16000
WIN = 320                 # 20 ms analysis frame
TARGET_LUFS = -16.0
PEAK_CEILING = 29204      # -1 dBFS in int16
GAP_MERGE_MS = 500        # shorter gaps stay inside one utterance...
LEVEL_STEP_DB = 6.0       # ...unless the level steps, which marks a new source
MIN_UTTERANCE_S = 0.8     # shorter groups are fragments, not recordings
PAD_MS = 150


def decode(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-ac", "1",
         "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    a = array.array("h")
    a.frombytes(raw)
    return a


def frame_rms(pcm):
    out = []
    for off in range(0, len(pcm) - WIN + 1, WIN):
        acc = 0
        for s in pcm[off:off + WIN]:
            acc += s * s
        out.append(math.sqrt(acc / WIN))
    return out


def utterances(pcm):
    """Spans of [start, end) samples, one per constituent recording.

    Splitting on gap length alone is not enough: inside fleurs_en_01 the
    level steps 35 dB across a 0.24 s pause while word gaps inside a single
    sentence are 0.02 s. So a run only joins the utterance in progress if it
    is both close in time AND close in level.
    """
    rms = frame_rms(pcm)
    if not rms:
        return []
    floor = sorted(rms)[max(0, int(len(rms) * 0.05))]
    thr = max(floor * 4.0, 3.0)

    runs, start = [], None
    for i, r in enumerate(rms + [0.0]):
        if r > thr and start is None:
            start = i
        elif r <= thr and start is not None:
            seg = rms[start:i]
            energy = sum(x * x for x in seg) / max(1, len(seg))
            runs.append([start, i, math.sqrt(energy)])
            start = None
    if not runs:
        return []

    merge_frames = GAP_MERGE_MS * SR // 1000 // WIN
    groups = [[runs[0][0], runs[0][1], runs[0][2], runs[0][1] - runs[0][0]]]
    for a, b, r in runs[1:]:
        g = groups[-1]
        near = a - g[1] <= merge_frames
        same_level = abs(20 * math.log10(max(r, 1e-6) / max(g[2], 1e-6))) <= LEVEL_STEP_DB
        if near and same_level:
            n = g[3] + (b - a)
            g[2] = math.sqrt((g[2] ** 2 * g[3] + r ** 2 * (b - a)) / n)
            g[1], g[3] = b, n
        else:
            groups.append([a, b, r, b - a])

    # Fragments too short to meter on their own join the neighbour they
    # actually resemble, rather than whichever happens to be adjacent.
    changed = True
    while changed and len(groups) > 1:
        changed = False
        for i, g in enumerate(groups):
            if g[3] * WIN / SR >= MIN_UTTERANCE_S:
                continue
            cands = [j for j in (i - 1, i + 1) if 0 <= j < len(groups)]
            j = min(cands, key=lambda k: abs(math.log(max(groups[k][2], 1e-6) /
                                                      max(g[2], 1e-6))))
            h = groups[j]
            n = h[3] + g[3]
            h[2] = math.sqrt((h[2] ** 2 * h[3] + g[2] ** 2 * g[3]) / n)
            h[0], h[1], h[3] = min(h[0], g[0]), max(h[1], g[1]), n
            groups.pop(i)
            changed = True
            break

    pad = PAD_MS * SR // 1000 // WIN
    spans = []
    for a, b, _, _ in groups:
        spans.append([max(0, a - pad) * WIN, min(len(rms), b + pad) * WIN])
    # Hand every sample to exactly one utterance so the ramp logic has no
    # unowned regions between neighbours.
    for i in range(len(spans) - 1):
        if spans[i][1] > spans[i + 1][0]:
            mid = (spans[i][1] + spans[i + 1][0]) // 2
            spans[i][1] = spans[i + 1][0] = mid
    return [tuple(s) for s in spans]


def measure_lufs(pcm, lo, hi):
    """Integrated loudness of pcm[lo:hi] via ffmpeg's EBU R128 meter."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        tmp = f.name
    try:
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR),
             "-ac", "1", "-i", "-", tmp],
            input=pcm[lo:hi].tobytes(), check=True, capture_output=True)
        out = subprocess.run(
            ["ffmpeg", "-v", "info", "-i", tmp, "-af",
             "loudnorm=print_format=json", "-f", "null", "-"],
            capture_output=True, text=True).stderr
        m = re.search(r'\{[^{}]*"input_i"[^{}]*\}', out, re.S)
        if m:
            v = float(json.loads(m.group(0))["input_i"])
            if v > -70:
                return v
    finally:
        os.unlink(tmp)
    return None


def gain_for(pcm, lo, hi):
    lufs = measure_lufs(pcm, lo, hi)
    peak = max((abs(s) for s in pcm[lo:hi]), default=1) or 1
    if lufs is None:                      # too quiet/short to meter
        g = PEAK_CEILING / peak
    else:
        g = 10 ** ((TARGET_LUFS - lufs) / 20)
    # Never clip: a peak ceiling always wins over the loudness target.
    return min(g, PEAK_CEILING / peak), lufs, peak


def build_envelope(n, spans, gains):
    """Piecewise gain over the whole timeline, ramped across the gaps."""
    env = [1.0] * n
    if not spans:
        return env
    for (lo, hi), g in zip(spans, gains):
        for i in range(lo, hi):
            env[i] = g
    for i in range(spans[0][0]):
        env[i] = gains[0]
    for i in range(spans[-1][1], n):
        env[i] = gains[-1]
    for k in range(len(spans) - 1):
        a, b = spans[k][1], spans[k + 1][0]
        if b <= a:
            continue
        g0, g1 = gains[k], gains[k + 1]
        for i in range(a, b):
            t = (i - a) / (b - a)
            env[i] = g0 + (g1 - g0) * t
    return env


def process(src, dst):
    pcm = decode(src)
    spans = utterances(pcm)
    gains, rows = [], []
    for lo, hi in spans:
        g, lufs, peak = gain_for(pcm, lo, hi)
        gains.append(g)
        rows.append((lo / SR, hi / SR, lufs, peak, 20 * math.log10(g)))

    env = build_envelope(len(pcm), spans, gains)
    out = array.array("h", (max(-32768, min(32767, int(s * e)))
                            for s, e in zip(pcm, env)))

    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR),
         "-ac", "1", "-i", "-", "-c:a", "aac", "-b:a", "96k", dst],
        input=out.tobytes(), check=True, capture_output=True)

    name = os.path.basename(src)[:-4]
    print(f"{name}: {len(spans)} utterances, {len(pcm)/SR:.2f}s")
    for a, b, lufs, peak, gdb in rows:
        li = f"{lufs:7.1f}" if lufs is not None else "   n/a "
        print(f"    {a:6.2f}-{b:6.2f}s  in={li} LUFS  peak={peak:>5}  gain={gdb:+6.1f} dB")
    return len(pcm) / SR


if __name__ == "__main__":
    src_dir, dst_dir = sys.argv[1], sys.argv[2]
    os.makedirs(dst_dir, exist_ok=True)
    for f in sorted(os.listdir(src_dir)):
        if f.endswith(".mp4"):
            process(os.path.join(src_dir, f), os.path.join(dst_dir, f))
