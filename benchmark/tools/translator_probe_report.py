#!/usr/bin/env python3
"""Score TranslatorModelProbeTest output: chrF against the references, and speed.

Usage:
    benchmark/tools/translator_probe_report.py                 # newest translator_probe.json
    benchmark/tools/translator_probe_report.py path/to/translator_probe.json
    benchmark/tools/translator_probe_report.py --show zh-vi     # also print outputs for a direction

chrF is sacrebleu's default (character 1-6 grams, beta 2, whitespace ignored),
computed per sentence and averaged. Only samples that carry a reference
(probeSamples=flores) are scored.
"""
import argparse
import glob
import json
import os
import statistics
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "build", "outputs", "connected_android_test_additional_output")


def ngrams(text, n):
    return Counter(text[i:i + n] for i in range(len(text) - n + 1))


def chrf(hyp, ref, max_n=6, beta=2.0):
    hyp, ref = "".join(hyp.split()), "".join(ref.split())
    precisions, recalls = [], []
    for n in range(1, max_n + 1):
        h, r = ngrams(hyp, n), ngrams(ref, n)
        if not h or not r:
            continue
        overlap = sum((h & r).values())
        precisions.append(overlap / sum(h.values()))
        recalls.append(overlap / sum(r.values()))
    if not precisions:
        return 0.0
    p, r = statistics.mean(precisions), statistics.mean(recalls)
    if p + r == 0:
        return 0.0
    return 100 * (1 + beta ** 2) * p * r / (beta ** 2 * p + r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?")
    ap.add_argument("--show")
    args = ap.parse_args()
    path = args.path or max(glob.glob(os.path.join(BUILD, "**", "translator_probe.json"), recursive=True),
                            key=os.path.getmtime, default=None)
    if not path:
        sys.exit("no translator_probe.json; run the probe first")
    rows = json.load(open(path, encoding="utf-8"))

    by = defaultdict(list)
    for r in rows:
        by[(r["model"], f"{r['source']}-{r['target']}")].append(r)
    models = list(dict.fromkeys(r["model"] for r in rows))
    directions = list(dict.fromkeys(f"{r['source']}-{r['target']}" for r in rows))

    print(f"source: {os.path.normpath(path)}\n")
    print("chrF (higher is better)")
    print(f"{'model':<30}" + "".join(f"{d:>8}" for d in directions) + f"{'mean':>8}")
    for m in models:
        cells, all_scores = [], []
        for d in directions:
            scored = [chrf(r["output"], r["reference"]) for r in by[(m, d)] if r.get("reference") and not r.get("error")]
            all_scores += scored
            cells.append(f"{statistics.mean(scored):8.1f}" if scored else f"{'-':>8}")
        print(f"{m:<30}" + "".join(cells) + (f"{statistics.mean(all_scores):8.1f}" if all_scores else ""))

    print("\nspeed per sentence (ms): first token p50 / p90, whole translation p50 / p90; errors")
    for m in models:
        ok = [r for d in directions for r in by[(m, d)] if not r.get("error") and r["totalMs"] >= 0]
        errors = sum(1 for d in directions for r in by[(m, d)] if r.get("error"))
        if not ok:
            print(f"{m:<30} no successful samples, {errors} errors")
            continue
        ttft = sorted(r["ttftMs"] for r in ok)
        total = sorted(r["totalMs"] for r in ok)
        q = lambda s, p: s[int((len(s) - 1) * p)]
        delegate = ok[0].get("delegate", "")
        print(f"{m:<30} {delegate:<4} first {q(ttft, .5):>5} / {q(ttft, .9):>5}   whole {q(total, .5):>5} / {q(total, .9):>5}"
              f"   errors {errors}")

    if args.show:
        for m in models:
            print(f"\n== {m} {args.show}")
            for r in by[(m, args.show)]:
                print(f"  {r['input']}\n   -> {r['output']}\n   ref {r['reference']}")


if __name__ == "__main__":
    main()
