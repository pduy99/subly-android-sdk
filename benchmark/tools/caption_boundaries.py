#!/usr/bin/env python3
"""Deterministic caption-segmentation metric — no judge, no noise.

The judge's per-tier averages swing by up to 16 points on the 5-clip English
tier with identical code, which is wider than any segmentation change worth
making. This measures the same property directly from results.json.

  captions   how many caption units the SDK emitted
  sentences  how many sentences the human reference contains
  ratio      captions / sentences; 1.0 is perfect, >1 is over-splitting
  mid-clause captions that end without a sentence terminator, or whose
             successor starts lowercase — a boundary that fell inside a
             sentence rather than between two

Usage:  caption_boundaries.py <results.json> [<results.json> ...]
"""
import json
import os
import re
import sys

TERMINATORS = ".!?…。！？．"
REF_DIR = "benchmark/src/androidTest/assets/dataset/references"


def sentence_count(text):
    parts = [p for p in re.split(r"[.!?…。！？．]+", text) if p.strip()]
    return max(1, len(parts))


def analyse(path):
    entries = json.load(open(path))
    entries = entries["entries"] if isinstance(entries, dict) else entries
    rows = {}
    for e in entries:
        caps = e.get("captions") or []
        if not caps:
            continue
        ref_path = os.path.join(REF_DIR, f"{e['clipId']}.transcript.txt")
        if not os.path.exists(ref_path):
            continue
        ref = open(ref_path).read().strip()

        texts = [c["original"].strip() for c in caps if c.get("original", "").strip()]
        mid = 0
        for i, t in enumerate(texts):
            ends_clean = t and t[-1] in TERMINATORS
            nxt = texts[i + 1] if i + 1 < len(texts) else None
            starts_lower = bool(nxt) and nxt[0].islower()
            if not ends_clean or starts_lower:
                mid += 1
        key = (e["sourceLang"], e["engine"])
        rows.setdefault(key, []).append((len(texts), sentence_count(ref), mid))
    return rows


def main(paths):
    print(f"{'run':<28}{'lang x engine':<15}{'caps':>6}{'sents':>7}{'ratio':>7}{'mid-clause':>12}")
    for p in paths:
        run = os.path.basename(os.path.dirname(p))[:26]
        for key, vals in sorted(analyse(p).items()):
            caps = sum(v[0] for v in vals)
            sents = sum(v[1] for v in vals)
            mid = sum(v[2] for v in vals)
            print(f"{run:<28}{key[0] + ' ' + key[1]:<15}{caps:>6}{sents:>7}"
                  f"{caps / max(sents, 1):>7.2f}{mid:>8} ({mid / max(caps, 1) * 100:.0f}%)")


if __name__ == "__main__":
    main(sys.argv[1:])
