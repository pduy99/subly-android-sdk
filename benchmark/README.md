# Accuracy benchmark

Measures whether an SDK change made caption quality better or worse.

Two stages. The **device stage** plays benchmark clips through the real SDK
pipeline (file audio → ASR → sentence assembly → ML Kit translation) on a
connected phone and writes `results.json`. The **judge stage** runs on your
Mac: it pairs each generated transcript/translation with the human-verified
reference and asks Claude to score accuracy *and readability* (punctuation,
casing, sentence segmentation), then writes a report.

Quality scores never fail a build. The device test fails only on harness
errors — a clip that wouldn't decode, an engine that crashed, a model that
wouldn't download.

## Running it

```bash
# 1. Device stage — needs a connected arm64 phone. Writes results.json.
./gradlew :benchmark:connectedDebugAndroidTest

# 2. Judge stage — needs an API key. Writes reports/<runId>/.
./gradlew :benchmark:judge:run

#    Judge an older run, or use a different model:
./gradlew :benchmark:judge:run --args="--results <path> --model claude-opus-5"

# Dataset consistency check (host-side, no device needed)
./gradlew :benchmark:validateDataset
```

The judge reads the API key from `ANTHROPIC_API_KEY` or `CLAUDE_KEY` — in the
environment, or in `local.properties` (which is git-ignored). Only text is
ever sent: audio never leaves the device.

**The committed baseline** lives in `benchmark/baseline/` — the report and scores every
new run is compared against. `reports/` is per-run scratch and git-ignored.

**Reading the report.** `reports/<runId>/report.md` opens with a language ×
engine summary, then the worst entries, then per-clip scores and the judge's
rationale. `scores.json` beside it holds the same numbers unformatted, for
diffing two runs. Both are stamped with the run ID, device, SDK commit, judge
model, and prompt version — a score is only comparable to another score from
the same `promptVersion`.

**If judging fails.** The judge records why an entry could not be scored and
carries on, then gives up after three consecutive failures — a dead key or an
empty credit balance fails identically for every entry. The device results are
never touched, so fix the cause and rejudge the same file rather than repeating
the device run:

```bash
# --results needs an ABSOLUTE path: the judge JVM runs from a different working dir
./gradlew :benchmark:judge:run --args="--results $PWD/reports/pending/<name>.results.json"
```

**Runtime.** The full matrix is long — every clip is played at real time,
once per engine; a clip's extra translation targets re-translate that same
pass rather than playing it again. Budget the better part of an hour for a
full run, and note the first run on a device additionally downloads each
engine's models. For a quick check during iteration, cut `manifest.json` down
to the clips you care about; the runner and judge both work off whatever the
manifest lists.

Because the run is long, `results.json` is rewritten after every entry, so an
interrupted run still leaves judgeable data. If the build dies but the phone
is still reachable, pull the partial file yourself and judge it:

```bash
adb pull /sdcard/googletest/test_outputfiles/results.json
./gradlew :benchmark:judge:run --args="--results $PWD/results.json"
```

Keep the phone awake and on a cable that won't wobble — a USB drop mid-run
ends the build with `device '<serial>' not found`, and Gradle can't retrieve
the file at all if the device vanishes.

## Adding a clip

1. Put the audio at `src/androidTest/assets/dataset/audio/<id>.mp4`.
2. Write the verified transcript to
   `references/<id>.transcript.txt` — **punctuated and cased**. The judge
   grades the SDK's readability against it, so an unpunctuated reference
   silently destroys that half of the measurement.
3. Write one translation per target to `references/<id>.translation.<lang>.txt`
   (`en` clips → `vi`; `zh`/`ja` clips → `vi` and `en`).
4. Add the manifest entry, then run `./gradlew :benchmark:validateDataset`.

A clip from a new source needs both a human verification pass and a license
that permits redistribution in this repo — see the table below for what the
current tiers rely on.

## Where the audio comes from

Every clip is a real human recording; there is no synthesised speech in the
corpus. All of it comes from one source — the **Google FLEURS test split**
(CC-BY-4.0), which permits redistribution in this repo.

| Tier | FLEURS locale | Clips | References |
|---|---|---|---|
| `fleurs_en_*` | `en_us` | 5, 20-31 s | transcript + `vi` |
| `fleurs_ja_*` | `ja_jp` | 5, 29-45 s | transcript + `vi`, `en` |
| `fleurs_zh_*` | `cmn_hans_cn` | 5, 24-35 s | transcript + `vi`, `en` |

**The three tiers are parallel.** Every clip is built from the same FLoRes-101
sentences (ids 1661-1679) read in each language, so an `en` score and a `zh`
score are measured over the same content and compare directly. It also means
the reference translations are FLoRes' own human translations rather than
machine output.

Every clip is nonetheless clean speech with no background. The app meets
speech over music, effects, and room tone, from people who hesitate and talk
over each other, and none of that is measured here — so treat these scores as
an upper bound and a regression detector, not a prediction of field accuracy.

One source quirk worth knowing when reading a report. FLEURS utterances are
independently read single sentences, so a clip is unrelated sentences played
back to back: the topic changes roughly every 10 seconds, and no clip carries
cross-sentence context. Every acoustic pause is therefore a true sentence end
as well, which flatters punctuation restoration compared with continuous
speech.

To close that gap, record what your users actually watch: 60 seconds of real
content carries the mix, the accents, and the overlapping dialogue at once.
Keep such files out of the public repo — recordings of copyrighted video can't
be redistributed here — and point the manifest at a local or Git LFS path.

**A human has to verify every transcript.** FLEURS ships curated transcripts
and FLoRes ships human translations, which is why both are trusted here. For
anything you add, someone has to listen and confirm, because the judge grades
the SDK against that text: a wrong reference silently moves every score for
the clip.

## Layout

```
benchmark/
├── src/main/java/...          dataset + results models (shared with the judge)
├── src/test/java/...          host-side tests, incl. validateDataset
├── src/androidTest/
│   ├── java/...               FileAudioCapture, EngineCatalog, the runner
│   └── assets/dataset/        the corpus: manifest.json, audio/, references/
└── judge/                     host-side JVM module: Claude judge + report
```

`EngineCatalog` declares which engines have models for which benchmark
languages, and how long to wait for each engine to go quiet after playback.
Pairs an engine has no model for are recorded as skipped, never failed —
Sherpa's model catalog is English-only, so its zh/ja rows are expected gaps.

## Known constraints

- **arm64 device only.** The ASR engines ship 64-bit ARM binaries, so Gradle
  Managed Devices (x86_64 emulators) can't run this yet — that needs x86_64
  native libraries added to the engine modules first.
- **Samsung devices may block the test install** with
  `INSTALL_FAILED_VERIFICATION_FAILURE`. Fix:
  `adb shell settings put global verifier_verify_adb_installs 0`.
- **ASR runs once per clip, not once per target.** A `zh` clip with `vi` and
  `en` references transcribes once; the second target re-translates the
  captions the first pass produced, which is what the session does with them
  anyway. Both targets therefore share one transcript, so a gap between their
  scores is the translation rather than the recognition. One consequence when
  reading `results.json`: `processingMs` on a secondary entry covers
  translation only, and is not comparable with a primary entry's.
- **The judge is non-deterministic.** Sampling parameters are unavailable on
  the judge models, so run-to-run stability rests on the pinned prompt.
  Treat small score movements as noise; look for direction and size.
