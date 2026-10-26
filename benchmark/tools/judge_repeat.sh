#!/usr/bin/env bash
# Judges one results.json N times and averages the scores.
#
# The judge's per-entry spread on identical input is ~7 points, with tier
# averages on the 5-clip English tier swinging 16. That is wider than most
# changes worth making, so a single pass cannot tell a real improvement from
# a resample. Averaging N passes narrows it by sqrt(N).
#
# Usage: judge_repeat.sh <results.json> [passes]
set -u
RESULTS="$1"
PASSES="${2:-3}"
# Unique per invocation: re-running with the same target used to overwrite
# the previous attempt's scores, and a failed retry then destroyed good data.
OUT="$(dirname "$RESULTS")/repeat-$(basename "$RESULTS" .results.json)-$(date +%H%M%S)"
mkdir -p "$OUT"
cd /home/helios/Code/subly-project/subly-android-sdk || exit 1
export ANDROID_HOME=/opt/android-sdk QEMU_LD_PREFIX=/opt/x86_64-sysroot

for i in $(seq 1 "$PASSES"); do
    echo "=== pass $i/$PASSES $(date -Is) ==="
    ./gradlew :benchmark:judge:run --console=plain \
        --args="--results $RESULTS" > "$OUT/pass$i.log" 2>&1
    rc=$?
    latest=$(ls -dt reports/*/ | head -1)
    cp "$latest/scores.json" "$OUT/pass$i.scores.json" 2>/dev/null
    echo "pass $i exit=$rc -> $(basename "$latest")"
done
echo "STATE repeat-done"
