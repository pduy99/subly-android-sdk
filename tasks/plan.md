# Plan: SDK Accuracy Benchmark

Source spec: `SPEC.md` (confirmed 2026-08-19). Intent: `docs/intent/sdk-accuracy-benchmark.md`.

## Strategy

Build a **walking skeleton first**: one clip, one engine, one language pair, both stages,
end to end. Every later task widens an axis (engines, languages, clips, report richness)
of a pipeline that already works. Two facts from the codebase keep the skeleton thin:

- `Subly.Builder.setAudioCapture { }` already exists as the injection seam — the file
  audio source is benchmark-side code, not an SDK change.
- The only SDK change is a `start()` overload on `SublySession` that skips the
  `MediaProjection` token.

## Dependency graph

```
T1 scaffold ──┬── T2 start() overload ──┐
              ├── T3 FileAudioCapture ──┼── T5 runner v1 (skeleton run) ── CP-A
              └── T4 dataset seed ──────┘         │
                                                  ├── T6 judge + report v1 ── CP-B
                                                  ├── T7 engine matrix ──┐
                        T8 full dataset ──────────┘                      ├── T9 report v2 ── CP-C
                                                  T6 ────────────────────┘
                                                            T10 hardening + docs
```

T2, T3, T4 are independent of each other (parallelizable); everything downstream of T5 is serial
through the checkpoints.

---

## Phase 1 — Walking skeleton (1 clip × 1 engine × en→vi, both stages)

### T1. `:benchmark` module scaffold
Android library module `:benchmark` (+ registration in `settings.gradle.kts`), depending on
`:sdk`, `:asr:sherpa`, `:asr:whisper`, `:asr:vosk`, `:translator:mlkit`; androidx-test with
`additionalTestOutputs` wired; placeholder instrumented test.
- **Accept:** module follows existing conventions (version catalog, minSdk, kotlin options); no `:sdk` code touched.
- **Verify:** `./gradlew :benchmark:connectedDebugAndroidTest` passes on a connected arm64 device; placeholder output file appears under `benchmark/build/outputs/**/additional_test_output/`.

### T2. `SublySession.start()` overload (SDK's only change)
`@VisibleForTesting` overload documented "for capture sources that do not need a projection";
existing `start(MediaProjection)` delegates to shared internals. No other public-API change.
- **Accept:** existing SDK unit tests untouched and green; KDoc explains the seam.
- **Verify:** `./gradlew :sdk:test` green; new unit test drives a fake `AudioCapture` through `start()` and observes captions.

### T3. `FileAudioCapture` (mp4 → paced PCM)
Implements `AudioCapture`: `MediaExtractor`/`MediaCodec` decode → mono 16 kHz `AudioFrame`s,
real-time pacing, ~2 s trailing silence to flush segmenters, then flow completion. Bundled 5 s
test clip in androidTest assets.
- **Accept:** resampling/downmix handled for typical mp4 audio (44.1/48 kHz stereo AAC); `stop()` releases the codec; completion (not hang) at EOF.
- **Verify:** instrumented smoke test asserts sample rate, channel count, expected total frame count ±5%, and wall-clock duration ≈ clip duration (pacing).

### T4. Dataset seed + manifest tooling
`benchmark/dataset/` with `manifest.json` (schema per SPEC §3), one licensed English clip
(60–120 s), human-verified punctuated transcript + Vietnamese translation. Host-side manifest
parser + `validateDataset` unit test (files exist, targets match language rules, non-empty refs).
- **Accept:** clip license permits repo redistribution and is noted in manifest `notes`; references pass a human read-through.
- **Verify:** `./gradlew :benchmark:validateDataset` green; corrupting the manifest in a test fixture fails with an actionable message.

### T5. Benchmark runner v1 (the skeleton closes)
`BenchmarkRunnerTest` runs the seed clip through Sherpa en→vi via `Subly` +
`FileAudioCapture` + T2's `start()`, collects **final** captions in order, writes
`results.json` (SPEC schema: runId, device, git SHA, entries incl. `processingMs`,
`error`) through `additionalTestOutputs`.
- **Accept:** harness failures (decode/model/engine) fail the test with clear messages; quality never fails it; model download on first run handled (WorkManager/network allowed in test).
- **Verify:** run on device; pulled `results.json` validates against the schema and contains a non-empty transcript and translation for the seed clip.

**CHECKPOINT A (human):** read `results.json` next to the reference. Sanity questions: is the
transcript recognizably the audio? Is sentence assembly visible (multiple sentences, ordered)?
Decide go/adjust before building the judge on top of this output shape.

### T6. Judge + report v1 (`:benchmark:judge`)
Plain-JVM module: loads newest (or `--results`-given) `results.json` + references; `ClaudeJudge`
calls the Claude API (default `claude-sonnet-5`, temperature 0, one clip×engine pair per
request, structured JSON scores 0–100 for transcription accuracy/readability and translation
accuracy/readability + rationale; retry w/ backoff; `promptVersion` constant recorded).
`ReportRenderer` writes `reports/<runId>/report.md` + `scores.json`. `ANTHROPIC_API_KEY` from
env or `local.properties` — never committed.
- **Accept:** only generated/reference *text* leaves the machine; missing key fails fast with instructions; `reports/` git-ignored.
- **Verify:** golden-file unit tests for prompt assembly and report rendering (mock API); then `./gradlew :benchmark:judge` against Checkpoint A's real results produces a readable report.

**CHECKPOINT B (human):** review the first real report. Is the judge's scoring/rationale
trustworthy and actionable? Iterate the prompt here (cheap: re-judge without re-running the
device stage) until yes; then freeze `promptVersion` v1.

## Phase 2 — Breadth (all engines, all languages, real corpus)

### T7. Engine × language matrix
`EngineCatalog` encoding actual model availability (Vosk: en/zh/ja small models exist;
Whisper: multilingual ggml base; Sherpa: per its model catalog — confirm during this task).
Runner parameterized over clip × supported engine; unsupported pairs recorded as `"skipped"`
in results, never as failures.
- **Accept:** one device run covers every supported pair for languages present in the dataset; per-entry isolation (one engine crash doesn't kill other entries).
- **Verify:** device run with the seed clip yields one entry per supported engine; a deliberately unsupported pair shows as skipped.

### T8. Full dataset (3 × 3 corpus)
3 clips per language (en/zh/ja), 60–120 s, varied difficulty (clean / fast / noisy), each with
human-verified punctuated transcript and translations (`en→vi`; `zh`,`ja`→`vi`+`en`).
Workflow per SPEC §3: licensed source → mp4 transcode → tool-assisted draft → human verify →
manifest entry.
- **Accept:** every clip's license allows redistribution; `validateDataset` green; repo size increase reviewed (clips are short mp4 audio; consider Git LFS only if total > ~50 MB).
- **Verify:** `validateDataset` green; full device run completes with an entry (or skip) for every clip × engine × target.

### T9. Report v2 (side-by-side matrix)
Per-language × engine summary table (4 scores averaged over clips), per-clip drill-down with
judge rationale, worst-clip callouts, run metadata header (runId, device, git SHA,
promptVersion, judge model), "not run/skipped" section.
- **Accept:** two reports from different runs are comparable at a glance; `scores.json` diffable.
- **Verify:** rendering unit tests updated; generate the report for T8's full run and read it.

**CHECKPOINT C (human):** full-corpus report review. Do scores match your own reading of the
transcripts? Any judge bias (e.g., over-penalizing casing)? Adjust prompt once more if needed —
this is the last free change before scores become the longitudinal baseline. Freeze
`promptVersion`.

## Phase 3 — Hardening & finish

### T10. Failure paths, docs, workflow polish
Exercise and tidy: no network (model download fails → clear harness error), API rate-limit
retry, partial-run judging (judge only entries present). Write `benchmark/README.md`: how to
add a clip, run both stages, read the report; document the GMD/GitHub-Actions path as future
work (x86_64 native libs prerequisite, per SPEC §6 "ask first"). Remove `SPEC.md`/`tasks/`
staleness notes if any.
- **Accept:** a cold clone + device + API key can run the whole benchmark from README alone.
- **Verify:** follow the README verbatim on a fresh checkout; both stages succeed.

## Explicitly deferred (not tasks)
- Gradle Managed Devices on GitHub Actions + x86_64 ASR binaries.
- Baseline storage / automated pass-fail / CI gating.
- Speed metrics beyond the informational `processingMs`.

## Estimates & risk notes
- Riskiest task: **T3** (MediaCodec decode + pacing edge cases) — do it early; its smoke test
  is the canary. Second: **T6 prompt quality** — mitigated by Checkpoint B's cheap re-judge loop.
- Dataset work (T4, T8) is calendar-time heavy (human verification) and can proceed in
  parallel with any code task after T4's tooling exists.
