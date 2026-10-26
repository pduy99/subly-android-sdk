#!/usr/bin/env python3
"""Host reimplementation of SpeechSegmenter's gate.

Mirrors FileAudioCapture (mono 16 kHz, 50 ms frames, 2 s trailing silence)
and SpeechSegmenter.chunk() exactly, so the segmentation it reports is what
the device would produce. Pure stdlib on purpose: no numpy on this host.
"""
import array
import subprocess
import sys

SR = 16000
FRAME = 800            # 50 ms
TRAILING_SILENCE_MS = 2000

SILENCE_HANG_MS = 300
MAX_SEGMENT_MS = 5000
MIN_SEGMENT_MS = 300
PRE_ROLL_MS = 100
FRAMES_TO_OPEN = 2
MAX_OVERLAP_MS = 1000


def decode(path, gain=1.0):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "s16le",
         "-acodec", "pcm_s16le", "-ac", "1", "-ar", str(SR), "-"],
        capture_output=True, check=True).stdout
    pcm = array.array("h")
    pcm.frombytes(raw)
    if gain != 1.0:
        pcm = array.array("h", (max(-32768, min(32767, int(s * gain))) for s in pcm))
    return pcm


def frames(pcm):
    """(timestampMs, maxAbsSample, nsamples) per frame, incl. trailing silence."""
    emitted = 0
    for off in range(0, len(pcm), FRAME):
        chunk = pcm[off:off + FRAME]
        peak = max((abs(s) for s in chunk), default=0)
        yield emitted * 1000 // SR, peak, len(chunk)
        emitted += len(chunk)
    for _ in range(TRAILING_SILENCE_MS // 50):
        yield emitted * 1000 // SR, 0, FRAME
        emitted += FRAME


class Gate:
    """Fixed absolute threshold — today's shipped behaviour."""

    def __init__(self, threshold=500):
        self.threshold = threshold

    def __call__(self, peak, in_speech):
        return peak > self.threshold


class AdaptiveGate:
    """Noise-floor-relative threshold (the proposed fix).

    Tracks the floor from non-speech frames only and opens on a fixed dB
    margin above it, clamped into [FLOOR_MIN, FLOOR_MAX] so a silent room
    can't drive the gate to zero and a loud one can't lock it shut.
    """

    def __init__(self, margin=4.0, floor_min=40, floor_max=2000,
                 attack=0.30, decay=0.02):
        self.margin, self.floor_min, self.floor_max = margin, floor_min, floor_max
        self.attack, self.decay = attack, decay
        self.floor = None

    def __call__(self, peak, in_speech):
        if self.floor is None:
            self.floor = max(self.floor_min, min(self.floor_max, peak))
        thr = max(self.floor_min, self.floor * self.margin)
        loud = peak > thr
        if not loud and not in_speech:          # adapt on non-speech only
            a = self.attack if peak > self.floor else self.decay
            self.floor += (peak - self.floor) * a
            self.floor = max(self.floor_min, min(self.floor_max, self.floor))
        return loud


def segment(pcm, gate):
    segs, ring, seg_ms = [], 0, 0
    silent_ms = consecutive = 0
    in_speech = False
    start_ms = -1
    ring_cap_ms = PRE_ROLL_MS

    for ts, peak, n in frames(pcm):
        frame_ms = n * 1000 // SR
        if gate(peak, in_speech):
            consecutive += 1
            if not in_speech and consecutive >= FRAMES_TO_OPEN:
                in_speech = True
                start_ms = ts - ring
                seg_ms = ring          # pre-roll drained into the segment
                ring = 0
            silent_ms = 0
        else:
            consecutive = 0
            if in_speech:
                silent_ms += frame_ms

        if in_speech:
            seg_ms += frame_ms
            silence_reached = silent_ms >= SILENCE_HANG_MS
            max_reached = seg_ms >= MAX_SEGMENT_MS
            if silence_reached or max_reached:
                if seg_ms >= MIN_SEGMENT_MS:
                    segs.append((start_ms, seg_ms,
                                 "max" if max_reached else "silence"))
                if max_reached and MAX_OVERLAP_MS > 0:
                    start_ms += seg_ms - MAX_OVERLAP_MS
                    seg_ms = MAX_OVERLAP_MS
                    silent_ms = 0
                    ring = 0
                else:
                    seg_ms = 0
                    ring = 0
                    silent_ms = 0
                    in_speech = False
                    start_ms = -1
        else:
            ring = min(ring_cap_ms, ring + frame_ms)

    # End-of-stream flush (the fix; today's code drops this segment).
    if in_speech and seg_ms >= MIN_SEGMENT_MS:
        segs.append((start_ms, seg_ms, "eos"))
    return segs


def union_ms(segs):
    """Total distinct audio covered, merging the overlap tails."""
    spans = sorted((s[0], s[0] + s[1]) for s in segs)
    total, cur_end = 0, None
    cur_start = None
    for a, b in spans:
        if cur_start is None:
            cur_start, cur_end = a, b
        elif a <= cur_end:
            cur_end = max(cur_end, b)
        else:
            total += cur_end - cur_start
            cur_start, cur_end = a, b
    if cur_start is not None:
        total += cur_end - cur_start
    return total


def report(path, gate_factory, gain=1.0, label=""):
    pcm = decode(path, gain)
    dur_ms = len(pcm) * 1000 // SR
    segs = segment(pcm, gate_factory())
    covered = union_ms(segs)   # overlap tails carried across max-closes
                               # would otherwise be counted twice
    print(f"{label:<34} segments={len(segs):>3}  covered={covered/1000:>6.1f}s"
          f" / {dur_ms/1000:>5.1f}s  ({covered/max(dur_ms,1)*100:>5.1f}%)")
    return segs, dur_ms


if __name__ == "__main__":
    print(__doc__.splitlines()[0])


class QuantileGate:
    """Sliding-window noise-floor gate (the proposed fix).

    Floor = a low quantile of recent frame peaks, which speech cannot drag
    upward (it sits in the upper tail) and which needs no attack/decay
    tuning. History is pre-filled with digital silence so the gate starts at
    its most sensitive and converges as it hears the room.
    """

    def __init__(self, window_ms=5000, quantile=0.20, margin=4.0,
                 min_thr=24, max_thr=4000, refresh_frames=4, warmup_ms=1000):
        self.n = max(1, window_ms // 50)
        self.warmup = max(1, warmup_ms // 50)
        self.q, self.margin = quantile, margin
        self.min_thr, self.max_thr = min_thr, max_thr
        self.refresh = refresh_frames
        self.hist = [0] * self.n
        self.i = 0
        self.filled = 0
        self.since = 0
        self.thr = min_thr

    def __call__(self, peak, in_speech):
        self.hist[self.i] = peak
        self.i = (self.i + 1) % self.n
        self.filled = min(self.n, self.filled + 1)
        self.since += 1
        if self.filled >= self.warmup and self.since >= self.refresh:
            self.since = 0
            floor = sorted(self.hist[:self.filled])[int((self.filled - 1) * self.q)]
            self.thr = max(self.min_thr,
                           min(self.max_thr, int(floor * self.margin)))
        return peak > self.thr
