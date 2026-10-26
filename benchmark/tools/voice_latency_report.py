#!/usr/bin/env python3
"""Per-sentence caption latency against the voice, from SublyBench logcat.

The session logs one `sentence_latency` line per final caption (see
VoiceLatencyTracker). This works on any run, including the real app:

    adb logcat -c
    ... play something through the overlay ...
    adb logcat -d -s SublyBench | benchmark/tools/voice_latency_report.py

Columns (ms):
  text   speech starts -> first source words on screen
  trans  speech starts -> first translated words on screen
  asr    speech stops  -> recogniser commits the sentence
  xlate  commit        -> final translation published
  e2e    speech stops  -> final translation published
"""
import re
import sys

COLUMNS = [
    ("text", "onset_to_text_ms"),
    ("trans", "onset_to_translation_ms"),
    ("asr", "end_to_asr_final_ms"),
    ("xlate", "asr_final_to_caption_ms"),
    ("e2e", "end_to_caption_ms"),
]


def pct(values, p):
    s = sorted(values)
    return s[int((len(s) - 1) * p)]


def main():
    src = open(sys.argv[1], encoding="utf-8") if len(sys.argv) > 1 else sys.stdin
    rows = []
    for line in src:
        if "sentence_latency" not in line:
            continue
        fields = dict(re.findall(r"(\w+)=(\S+)", line.split("sentence_latency", 1)[1]))
        rows.append(fields)
    if not rows:
        sys.exit("no sentence_latency lines (is the SDK build current?)")

    print(f"{'n':>3} {'onset_s':>8} {'end_s':>7} " + " ".join(f"{c:>6}" for c, _ in COLUMNS) + "  merged len")
    for r in rows:
        print(f"{r['n']:>3} {r['voice_onset_s']:>8} {r['voice_end_s']:>7} "
              + " ".join(f"{r.get(k, 'na'):>6}" for _, k in COLUMNS)
              + f"  {r.get('merged', ''):>6} {r.get('src_len', '')}")
    print()
    for c, k in COLUMNS:
        vals = [int(r[k]) for r in rows if r.get(k, "na").lstrip("-").isdigit()]
        if vals:
            print(f"{c:>6}: p50 {pct(vals, .5):>6}  p90 {pct(vals, .9):>6}  max {max(vals):>6}  (n={len(vals)})")


if __name__ == "__main__":
    main()
