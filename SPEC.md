# SPEC: SDK Accuracy Benchmark

Derived from the confirmed intent in `docs/intent/sdk-accuracy-benchmark.md` (interviewed 2026-08-19).

## 1. Objective

A repeatable accuracy benchmark for the Subly SDK, run by the SDK developer after each
meaningful change to answer: **did output quality get better or worse?**

Two-stage pipeline:

1. **Device stage** (instrumented test, physical arm64 device): decodes benchmark mp4 files,
   feeds their PCM through the full SDK pipeline minus capture (file source → ASR →
   sentence assembly → translation) for every ASR engine that has a model for the clip's
   language, and dumps the generated transcriptions/translations as JSON test output.
2. **Judge stage** (host JVM, Claude API): pairs generated output with verified references
   and asks Claude to score **accuracy** and **readability** (punctuation, casing, sentence
   segmentation), then renders a human-readable side-by-side report.

Success: one device run + one judge run yields a report trusted enough to keep or revert a change.

Non-goals (out of scope): speed/latency metrics, deterministic WER/CER scoring, automated
pass/fail baselines or CI gating, host-JVM execution of the SDK itself.

## 2. Commands

```bash
# Stage 1 — run the SDK over the dataset on a connected device.
# Results land in benchmark/build/outputs/.../additional_test_output/ (see §3).
./gradlew :benchmark:connectedDebugAndroidTest

# Stage 1, one pair only — the fast inner loop; a full matrix takes over an hour.
./gradlew :benchmark:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.benchmarkClips=aishell_zh_01 \
  -Pandroid.testInstrumentationRunnerArguments.benchmarkEngines=whisper

# Stage 2 — judge the newest device-stage results and write the report.
./gradlew :benchmark:judge:run
#   --args="--results <path>"  judge a specific results.json (default: newest)
#   --args="--model <id>"      Claude model (default: claude-sonnet-5)
# Key comes from ANTHROPIC_API_KEY / CLAUDE_KEY in the environment or local.properties.

# Utilities
./gradlew :benchmark:validateDataset   # manifest ↔ files consistency check (host-side unit test)
```

## 3. Project structure

New Gradle module `:benchmark` in this repo (Android library, benchmark code lives in
`androidTest`; never shipped, never published).

```
benchmark/
├── build.gradle.kts                  # depends on :sdk, :asr:*, :translator:mlkit
├── baseline/                         # committed reference run: report.md + scores.json
├── src/androidTest/
│   ├── assets/dataset/               # the corpus (git-committed; lives under assets so it
│   │   ├── manifest.json             #   ships in the test APK — AGP 9 rejects extra asset roots)
│   │   ├── audio/                    # mp4 clips: <id>.mp4 (e.g. lj_read_01.mp4)
│   │   └── references/               # verified text: <id>.transcript.txt,
│   │                                 #   <id>.translation.<target>.txt (punctuated)
│   └── java/com/helios/subly/benchmark/
│       ├── BenchmarkRunnerTest.kt    # instrumented entry point; walks clip × engine × target
│       ├── FileAudioCapture.kt       # AudioCapture impl: MediaExtractor/MediaCodec mp4 → PCM
│       └── EngineCatalog.kt          # engine × language availability + per-engine quiescence
├── src/main/java/...                 # dataset + results models, shared with the judge
├── src/test/java/...                 # host-side unit tests (manifest parsing, report rendering)
└── judge/                            # host-side judge (plain Kotlin/JVM module :benchmark:judge)
    └── src/main/java/com/helios/subly/benchmark/judge/
        ├── Main.kt                   # CLI: load results + references → judge → report
        ├── ClaudeJudge.kt            # Anthropic API client, prompt assembly, retry
        └── ReportRenderer.kt         # report.md (side-by-side) + scores.json
reports/                              # judge output, git-ignored: reports/<runId>/report.md
```

### Dataset manifest schema (`dataset/manifest.json`)

```json
{
  "clips": [
    {
      "id": "lj_read_01",
      "language": "en",
      "audio": "audio/lj_read_01.mp4",
      "durationSec": 90,
      "transcript": "references/lj_read_01.transcript.txt",
      "translations": { "vi": "references/lj_read_01.translation.vi.txt" },
      "notes": "single speaker, clean audio, medium pace"
    }
  ]
}
```

- Languages: `en`, `zh`, `ja`. Translation targets: `en` → `vi`; `zh`/`ja` → `vi` and `en`.
- References are **punctuated and cased** — required so the judge can score readability.
- Corpus: **Google FLEURS** test split, 5 parallel clips per language, 20–45 s, every clip
  a real human recording. An earlier synthetic (macOS `say`) corpus was removed: it
  flattered the scores and — worse — manufactured a defect that looked like an SDK bug
  (see Finding 2 in `tasks/todo.md`). Synthetic speech must not be reintroduced.

| Tier | Source | License | Content |
|---|---|---|---|
| `fleurs_en_*` | FLEURS `en_us` test | CC-BY-4.0 | FLoRes-101 sentences, read |
| `fleurs_zh_*` | FLEURS `cmn_hans_cn` test | CC-BY-4.0 | the same sentences, in Mandarin |
| `fleurs_ja_*` | FLEURS `ja_jp` test | CC-BY-4.0 | the same sentences, in Japanese |

- **Clips are parallel across languages.** `fleurs_en_03`, `fleurs_zh_03` and
  `fleurs_ja_03` are the same FLoRes sentence ids spoken in three languages, so a score
  gap between languages is a property of the engine, not of the content. The previous
  corpus could not support that claim: it was three unrelated sources (an 1880s audiobook,
  studio Mandarin, crowd-recorded Japanese), so "Japanese scores worse" was unattributable.
- **References are human translations, not machine output.** FLEURS is built on
  FLoRes-101, a professionally translated parallel corpus, so the Vietnamese and English
  references are professional work. Under the previous corpus every Vietnamese reference
  was written by the same assistant that writes the judge prompt, which was a circularity
  worth removing.
- Dataset preparation workflow: select sentence ids present in **all four** of en/zh/ja/vi
  → concatenate consecutive utterances per language into a clip → transcode to mp4 (AAC,
  16 kHz mono, the FLEURS native rate, so the source is never resampled) → take transcript
  and translations verbatim from the FLEURS TSVs → add manifest entry. Licensing of each
  clip must permit redistribution in this repo; CC-BY-4.0 requires attribution, which the
  manifest `notes` carry per clip.
- **The corpus quirk that matters:** FLEURS utterances are independently read single
  sentences, so a clip concatenates unrelated sentences and the topic changes roughly
  every 10 s. Two consequences. An ASR endpoint always coincides with a sentence end, so
  punctuation restoration is flattered here relative to continuous speech — the failure
  mode that motivated shipping it off by default (a breath pause mid-clause becoming
  "reference to its. capacity") cannot appear in this corpus at all. And no clip resembles
  the continuous narration of the videos this SDK captions.
- **Still unmeasured:** every clip is clean speech. Background music, noise, and
  overlapping speakers are absent, so scores are an upper bound and a regression
  detector, not a prediction of field accuracy.

### Device-stage results schema (`results.json`)

```json
{
  "runId": "2026-08-19T10-32-11_pixel8_a1b2c3",
  "device": { "model": "Pixel 8", "sdk": 35 },
  "sdkGitSha": "…",
  "entries": [
    {
      "clipId": "lj_read_01", "engine": "sherpa", "sourceLang": "en", "targetLang": "vi",
      "transcript": "…all captions joined…", "translation": "…all captions joined…",
      "captions": [
        { "original": "…one caption…", "translated": "…one caption…" }
      ],
      "captionCount": 14, "processingMs": 61234, "error": null, "skipped": null
    }
  ]
}
```

`captions` preserves the boundaries the joined text destroys. Each entry appeared on
screen as one unit, so the judge needs them to score segmentation instead of inferring it
from punctuation — a caption ending mid-clause reads badly even when the joined text
looks fine. `processingMs` is recorded as informational context only — it is never
judged. `skipped`
is set when an engine has no model for the clip's language: an expected matrix gap, never
a failure. `results.json` is rewritten after **every** entry, so a run cut short by a USB
drop or a crash still leaves judgeable data on the device.

### Judge report

`reports/<runId>/report.md` — a language × engine summary of averaged scores, worst-entry
callouts, then per-clip transcription accuracy, transcription readability, translation
accuracy, translation readability (each 0–100) and the judge's rationale. `scores.json`
alongside it holds the raw structured scores for manual diffing between runs. `reports/`
is scratch output and git-ignored.

### Reference baseline

`benchmark/baseline/` holds the committed reference run — the report and scores a future
run is compared against.

**There is currently no valid baseline.** The previous one (`2026-08-20T04-14-50_SM-G973F`,
SDK `e0b8b80`) was deliberately voided, for two independent reasons, either of which alone
would have been enough:

1. It was recorded through a **truncated playback window** — the harness timed each clip
   from `start()` while the capture was still decoding the mp4, so every clip lost its
   tail (measured: 268 characters on one 65 s clip). Fixed in `2d09918`.
2. The corpus it measured **no longer exists**. LJ Speech, AISHELL-1 and Common Voice were
   replaced wholesale by FLEURS, and scores never compare across corpora.

**Judge noise floor: about ±4 points on accuracy, but ±7 or worse on readability.**
Between the v1 and v2 baselines the accuracy instructions did not change, yet per-cell
accuracy still moved -4..+3. Readability is looser: judging one *byte-identical*
transcript twice returned 40 and then 33. Accuracy is anchored to a reference string;
readability is a holistic impression, and it wanders.

The practical consequence is a floor on what this benchmark can resolve. With three
English clips and ±7 per cell, a change worth less than roughly 10 readability points
is not measurable here — a single run will produce a number, but re-running would
produce a different one. Before concluding that a text-level change helped or hurt,
judge the same results twice and check the effect survives. To resolve smaller effects
you need more clips, not more confidence in one run.

**Scores are only comparable within a `promptVersion` and within a corpus.** Both changed
once already: the synthetic-corpus numbers that preceded this baseline are void, because
the audio they measured no longer exists. Replace the baseline deliberately, and say in
the commit message why.

## 4. Architecture & key design decisions

- **File audio entry point**: `FileAudioCapture` implements the existing `AudioCapture`
  interface and is injected via the existing `Subly.Builder.setAudioCapture { }` seam —
  no new SDK surface for the audio path. It decodes mp4 → mono 16 kHz PCM with
  `MediaExtractor`/`MediaCodec` and emits `AudioFrame`s **paced at real time** (streaming
  engines and Whisper's silence-based `SpeechSegmenter` assume it), followed by ~2 s of
  silence frames to flush segmenters, then completes.
- **`start()` without projection**: `SublySession.start(MediaProjection)` requires a token
  the file source never uses. Add an internal/`@VisibleForTesting` overload
  `start()` (documented "for capture sources that do not need a projection") rather than
  making the public parameter nullable. This is the only SDK change this project makes.
- **Transcript assembly**: the device stage collects `session.captions`, keeps **final**
  captions only (ignoring partials), and joins them in order into the generated
  transcript/translation per clip × engine.
- **Engine × language matrix**: `EngineCatalog` declares which engines support which
  benchmark languages (from each engine's model catalog). Unsupported pairs are skipped
  and listed in the report as "not run", not scored as failures.
- **Judge determinism**: temperature 0, fixed prompt version (`promptVersion` recorded in
  `scores.json`), one clip×engine pair per request, structured-output JSON scores.
  Judge model default `claude-sonnet-5`, overridable. Only *text* is sent to the API —
  audio never leaves the device, consistent with the SDK's privacy story.
- **Results transport**: device stage writes through androidx-test
  `additionalTestOutputs`, which Gradle pulls off the device automatically and which
  works unchanged on Gradle Managed Devices — no hand-rolled `adb pull`.

## 5. Code style & testing strategy

- Match existing SDK conventions: Kotlin, coroutines/Flow, explicit lifecycles, KDoc on
  public surfaces, version catalog for dependencies, same formatting as neighbouring modules.
- The benchmark is measurement tooling, not a pass/fail test: `BenchmarkRunnerTest`
  fails only on *harness* errors (decode failure, engine crash, model download failure);
  quality scores never fail the run.
- Host-side unit tests (JVM, run in CI freely): manifest parsing/validation, results
  serialization, judge prompt assembly (golden-file), report rendering.
- One instrumented smoke test: `FileAudioCapture` decodes a bundled 5 s clip and emits
  the expected frame count/format — catches decoder regressions without a full run.

## 6. Boundaries

**Always**
- Keep `ANTHROPIC_API_KEY` in env/local properties — never in the repo, never on the device.
- Record `runId`, device, SDK git SHA, and `promptVersion` in every artifact so runs
  are comparable after the fact.
- Human-verify every reference before it enters `dataset/`.

**Ask first**
- Any SDK public-API change beyond the `start()` overload in §4.
- Adding x86_64 native ASR libs (the prerequisite for the GMD/GitHub-Actions leg).
- Changing the judge prompt or model default (invalidates comparability with prior runs).
- Committing any clip whose license is unclear.
- Replacing `benchmark/baseline/`, which resets what every future run is measured against.

**Never**
- Send audio (or anything but generated/reference text) to the Claude API.
- Let benchmark code or the dataset into the published SDK artifacts.
- Gate CI on judge scores (explicitly out of scope for this project).
- Add synthetic speech to the corpus. It inflates scores and can fabricate a defect that
  reads as an SDK bug — this already happened once.
- Read a score as a regression without checking that the corpus and `promptVersion` match
  the baseline's. A corpus change moves every number.

## 7. Findings this benchmark produced

Recorded because they are the return on the whole exercise, and because one of them is a
cautionary tale about the method itself. Detail in `tasks/todo.md`.

1. **Whisper aborted the process on CJK audio** (fixed, `fe6bc85`). Real: unvalidated
   native text reached `NewStringUTF`, and a repetition guard that counted
   whitespace-delimited words was inert for scripts without spaces. Found on the first
   full matrix run; would have killed any consumer app captioning Chinese or Japanese.
2. **"Whisper emits almost no CJK captions"** (retracted). An artefact of the synthetic
   corpus: evenly-paced TTS never cued a sentence terminator, so `SentenceExtractor` never
   completed a sentence. Real speech produces 2–12 captions per pair. The benchmark's own
   test data manufactured this one.
