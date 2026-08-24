# Benchmark tooling (uncommitted — kept here so the corpus is reproducible)

These are the scripts behind the 2026-08-23 corpus normalisation and the two
SDK gate fixes. Nothing here is wired into Gradle; it is kept so nobody has to
reverse-engineer a 52 dB gain curve later. Commit, move, or delete as you see
fit.

## `normalize_corpus.py`

Per-utterance loudness normalisation, the change that produced the current
`dataset/audio/*.mp4`.

```bash
python3 normalize_corpus.py original-audio/ out/
```

Each FLEURS clip concatenates independently recorded utterances, each at
whatever level its own session had — a 35 dB spread inside `fleurs_en_01` and
52 dB inside `fleurs_ja_03`. A whole-file gain cannot fix that, so the script
splits on *level steps* as well as pauses (word gaps inside one sentence run
0.02 s; the level steps across a 0.24 s pause), measures each utterance with
ffmpeg's EBU R128 meter, and applies a constant gain per utterance toward
-16 LUFS, ramped across the gaps and always capped at -1 dBFS true peak.
Sample count is preserved exactly.

The pre-normalisation clips are not duplicated here — git already has them.
To re-derive or revert:

```bash
git show d8f189c:benchmark/src/androidTest/assets/dataset/audio/fleurs_en_01.mp4 > orig.mp4
```

Result: clips went from -14.7…-59.1 LUFS to -16.9…-22.8 LUFS. `fleurs_en_02`
needed +40 dB and its SNR was unharmed (31.5 → 32.4 dB) — it was recorded
quiet, not noisily.

## `segmenter_sim.py`

Host reimplementation of `SpeechSegmenter`'s gate over the same 50 ms frames
`FileAudioCapture` emits. Reproduces device behaviour (`fleurs_en_01`: 4
segments / 9.1 s; `fleurs_en_02`: 0 segments), which is what made it usable
as the validation harness for the adaptive gate — a device run takes an hour,
this takes seconds.

It carries both gates, so the fix can be measured against the shipped one on
identical audio:

| gate | original corpus | normalised corpus | + white noise σ=200 |
|---|---|---|---|
| fixed `SPEECH_THRESHOLD = 500` | 61.1% | 90.1% | 101.1% (latched open) |
| adaptive noise floor | 85.8% | 97.5% | 88.2% |

The 101.1% is the fixed gate segmenting the *whole stream* including silence:
in any room with audible noise it stops gating at all.

## `vad_probe.cpp` + `vad_probe.CMakeLists.txt`

Runs the same Silero VAD the SDK ships, on the host, over segments dumped by
`segmenter_sim.py`. Builds against the in-tree whisper.cpp:

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF \
      -DGGML_LLAMAFILE=OFF -DGGML_USE_CPU=ON     # needs vad_probe.CMakeLists.txt as CMakeLists.txt
cmake --build build -j4
curl -sL -o ggml-silero-v5.1.2.bin \
  "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin?download=true"
./build/vad_probe ggml-silero-v5.1.2.bin segs/*.raw
```

`vad-probe-before.tsv` / `vad-probe-after.tsv` are its output for the corpus
before and after the fixes. The "before" run reproduces the device's measured
45% Japanese drop rate exactly, which is what makes the host measurement
trustworthy.

Columns are the max Silero speech probability over the leading 1500 ms
(`front_max`, what the gate used to look at), over the whole window
(`full_max`), and over everything after the leading slice (`rest_max`).
Windows with `front_max` ≈ 0.1 and `full_max` = 1.000 are real sentences the
old gate threw away without a decode.
