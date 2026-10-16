#!/usr/bin/env python3
"""Print the streaming-latency benchmark's latency.json as a readable report.

Usage:
    benchmark/tools/latency_report.py                 # newest latency.json under benchmark/build
    benchmark/tools/latency_report.py path/to/latency.json
    benchmark/tools/latency_report.py --json ...      # re-emit the summary as compact JSON

The device side (StreamingLatencyBenchmarkTest) writes latency.json; Gradle
pulls it into benchmark/build/outputs/connected_android_test_additional_output/.
"""
import argparse
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "build", "outputs", "connected_android_test_additional_output")


def newest_report():
    found = glob.glob(os.path.join(BUILD, "**", "latency.json"), recursive=True)
    if not found:
        sys.exit(f"no latency.json under {os.path.normpath(BUILD)}; run the benchmark first")
    return max(found, key=os.path.getmtime)


def fmt(d):
    if d is None:
        return f"{'-':>6} {'-':>7} {'-':>7} {'-':>7} {'-':>7}"
    return f"{d['count']:>6} {d['p50']:>7} {d['p90']:>7} {d['p99']:>7} {d['max']:>7}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    path = args.path or newest_report()
    with open(path) as f:
        run = json.load(f)

    if args.json:
        print(json.dumps({k: run[k] for k in ("runId", "device", "soc", "asrEngine", "summary",
                                               "targetsMet", "memory", "delegates")}))
        return

    print(f"run {run['runId']}  {run['device']} ({run['soc']}, API {run['sdkInt']})  "
          f"sdk {run['gitSha']}  asr={run['asrEngine']}")
    print(f"source: {os.path.normpath(path)}\n")

    print("delegates")
    for d in run["delegates"]:
        tried = ", ".join(
            f"{a['delegate']} {'ok' if a['succeeded'] else 'FAILED'} {a['initMs']}ms"
            + ("" if a["succeeded"] else f" ({a['failure']})")
            for a in d["attempts"])
        flag = " fallback" if d["fallback"] else ""
        print(f"  {d['component']:<48} {d['active']}{flag}  [{tried}]")

    print(f"\n{'metric (ms)':<40} {'n':>6} {'p50':>7} {'p90':>7} {'p99':>7} {'max':>7}")
    for name, d in run["summary"].items():
        print(f"  {name:<38} {fmt(d)}")

    t = run["targets"]
    print("\ntargets (task specification)")
    for name, met in run["targetsMet"].items():
        verdict = "n/a" if met is None else ("met" if met else "NOT met")
        print(f"  {name:<38} {verdict}")
    print(f"  (ttft <= {t['ttftMs']} ms, speech end -> translation <= {t['speechEndToTranslationMs']} ms, at p90)")

    m = run["memory"]
    print(f"\nmemory  peak RSS {m['peakRssMb']:.0f} MB  peak PSS {m['peakPssMb']:.0f} MB  "
          f"peak native heap {m['peakNativeHeapMb']:.0f} MB  ({m['samples']} samples)")

    print(f"\n{'clip':<16} {'pair':<6} {'s':>3} {'finals':>6} {'forced':>6} {'e2e p50':>8} {'e2e max':>8}  error")
    for c in run["clips"]:
        e2e = sorted(c["speechEndToTranslationMs"])
        p50 = e2e[(len(e2e) - 1) // 2] if e2e else "-"
        mx = e2e[-1] if e2e else "-"
        print(f"{c['clipId']:<16} {c['language'] + '>' + c['target']:<6} {c['durationSec']:>3} "
              f"{c['finals']:>6} {c['forcedCommits']:>6} {p50:>8} {mx:>8}  {c['error'] or ''}")


if __name__ == "__main__":
    main()
