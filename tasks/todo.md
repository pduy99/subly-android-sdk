# Todo: SDK Accuracy Benchmark

Detail and acceptance criteria: `tasks/plan.md`. Spec: `SPEC.md`.

## Phase 1 — Walking skeleton
- [x] T1: Scaffold `:benchmark` module; placeholder instrumented test + additionalTestOutputs wired
      (device-verified on SM-G973F: TestStorage output round trip confirmed. The stale
      sdk unit tests found during T1 were deleted with user approval.)
- [x] T2: `SublySession.start()` no-projection overload (`@VisibleForTesting`) + unit test
      (per user decision: additive `ProjectionlessAudioCapture` sub-interface in audio:api
      rather than widening `AudioCapture.frames` to a nullable projection)
- [x] T3: `FileAudioCapture` (mp4 → paced 16 kHz mono PCM + trailing silence) + instrumented smoke test
      (smoke test green on SM-G973F; note: device needed
      `settings put global verifier_verify_adb_installs 0` to accept adb test installs)
- [x] T4: Dataset seed (1 EN clip + verified refs) + `manifest.json` + `validateDataset` task
      (seed clip is synthetic macOS TTS — transcript verified by construction, no licensing;
      VI reference translation to be human-confirmed at Checkpoint A)
- [x] T5: Runner v1 — seed clip × **Vosk** en→vi → `results.json` via additionalTestOutputs
      (deviations: Vosk instead of Sherpa — Sherpa's native AAR is compileOnly and joins in T7;
      dataset moved to benchmark/src/androidTest/assets/dataset/ because AGP 9.2-rc01's
      sourceSets DSL breaks on extra asset roots. Debugged on-device: playback window now
      anchors at start() not prepare(), and quiescence is 20 s because Vosk finalizes ~20 s
      apart — 8 s raced the engine's last final and truncated the tail.)
- [x] CHECKPOINT A: human review of raw results vs reference (user unblocked with API key;
      VI reference translation still open for their explicit sign-off)
- [x] T6: `:benchmark:judge` — Claude judge + report v1 + unit tests
      (deviations: official Anthropic Java SDK instead of raw OkHttp; no temperature-0 —
      claude-sonnet-5 rejects non-default sampling params, determinism rests on the pinned
      prompt; structured outputs guarantee the score schema. Invoke:
      ./gradlew :benchmark:judge:run)
- [ ] CHECKPOINT B: review first real report; iterate prompt; freeze promptVersion v1

## Phase 2 — Breadth
- [x] T7: `EngineCatalog` + parameterized runner over clip × supported engine; skips recorded, not failed
      (Sherpa is en-only — its Zipformer catalog has no zh/ja model; its native AAR is
      compileOnly so the test APK bundles it like an app would. Per-engine quiescence:
      Whisper needs 60 s, streaming engines 20 s.)
- [x] T8: Full dataset — 3 clips × {en, zh, ja}, verified refs (en→vi; zh/ja→vi+en)
      (synthetic macOS TTS: Daniel/Samantha en, Tingting zh, Kyoko ja — verified by
      construction, no licensing. 9 clips, 24 reference files, 2.2 MB.)
- [x] T9: Report v2 — language × engine summary averages, worst-entry callouts, run metadata
- [x] CHECKPOINT C: full-corpus baseline captured after the Whisper CJK fix.
      45 entries (28 judged, 12 engine-skips, 5 whisper-CJK caption failures), zero
      crashes. Report: reports/2026-08-19T18-32-26_SM-G973F/report.md
      promptVersion v1 frozen — scores are only comparable within a promptVersion.

## Finding 1 (FIXED in fe6bc85): Whisper hard-crashed the process on CJK audio

Surfaced by the first full-matrix run, at `zh_synthetic_01 × whisper`. Not a
benchmark bug — a shippable-severity SDK bug. Any consumer app transcribing
CJK with Whisper can be killed outright (SIGABRT, not a catchable exception).

Chain, from the tombstone plus `asr/whisper/src/main/cpp/whisper-jni.cpp`:

1. `looksRepetitive` (line 56) splits on whitespace to count repeated "words".
   CJK text has no spaces, so the whole string is one word, `words.size() < 6`
   returns false, and the repetition guard is **inert for zh/ja**.
2. The carry truncation (line 504) computes `result.size() - MAX_CARRY_CHARS`
   as a **byte** offset and only realigns at a space — which CJK never has —
   so `handle->carry` routinely splits a 3-byte character. That corrupt carry
   is fed back as the next window's prompt.
3. An unfiltered repetition loop runs to the token cap, and whisper emits text
   ending mid-UTF-8-sequence (tombstone input ends `0xe9 0x99`, the first two
   bytes of 陆 `E9 99 86`).
4. `NewStringUTF(result.c_str())` (line 520) aborts on invalid Modified UTF-8
   and takes the whole process down.

Line 520 is the load-bearing safety gap: native text reaches JNI unvalidated,
so *any* malformed whisper output is fatal rather than degraded.

Needs a decision before the matrix can complete — fix the SDK, or exclude
whisper×{zh,ja} from EngineCatalog to get a full baseline first.

## Phase 3 — Hardening
- [x] T10: `benchmark/README.md` + incremental results.json writes so an interrupted
      run keeps its data (found the hard way — the first full-matrix run lost everything
      to a USB drop 7 minutes in). Offline/rate-limit paths still unexercised.

## Deferred (do not pick up without asking)
- GMD on GitHub Actions + x86_64 native ASR libs
- Baselines / pass-fail gating / CI

## Finding 2 (WAS AN ARTEFACT of the synthetic corpus)

On the TTS corpus, 5 of 9 whisper x zh/ja pairs produced no captions at all
and the rest managed 1-2 for a ~55 s clip. On the real-voice corpus the same
engine produces 2-12 captions per pair with zero errors across all 45 entries.

The defect was in the test data, not the SDK: SentenceExtractor waits for a
sentence terminator, and evenly-paced synthetic speech gave whisper no
acoustic cue to emit one. Real recordings with natural pauses do. Closed —
but it is a standing warning that a synthetic corpus can manufacture
SDK-looking bugs.

## Follow-ups worth considering

- The runner fails the build on "no final captions", which is a quality outcome
  rather than a harness fault. Consider recording it as a zero-score result so a
  quality regression doesn't look like a broken benchmark.
- 7 of 9 clips are 50-59 s, under the 60 s floor in SPEC.md; all are clean
  single-speaker TTS, so nothing here measures noise, accents, or disfluencies.
- Reference translations are unverified LLM output (see the dataset note); they
  are the grading standard, so errors there move every score.

## Finding 3 (MEASURED, feature shipped OFF): punctuation restoration is
## only right where a pause is a sentence end

Restoring a terminator at each ASR endpoint helps or harms depending entirely
on whether the recogniser's pause was a sentence boundary.

| clip | engine | readability off -> on |
|---|---|---|
| aishell_zh_01 | vosk | 25 -> 82 |
| lj_read_01 | vosk | 25 -> 30 |
| lj_read_01 | sherpa | 45 -> 38 |

Chinese gained enormously — captions went from 60-character blobs to one
sentence each. Two caveats on that number: AISHELL clips are separately
recorded utterances, so an endpoint genuinely is a sentence end, and the
reference punctuation was itself restored at those same boundaries, so
reference and output share a boundary source. The improvement is real but the
magnitude is inflated.

English regressed. LJ Speech is continuous prose, so the speaker pauses for
breath mid-clause and the heuristic writes a period there: "reference to its.
capacity", "chapel as a. day and night room". Worse than no punctuation.

Shipped behind Subly.Builder.setPunctuationRestoration, default OFF.

Separate finding from the same data: **sherpa emits 100% uppercase** (774
uppercase chars, 0 lowercase in one clip). For that engine, all-caps is the
dominant readability defect and punctuation does not touch it. Lower-casing
with sentence-initial capitals is likely a larger win than anything above, and
is a pure text transform with no boundary guessing.

## Finding 4 (SHIPPED, unconfirmed by the judge): Sherpa's all-caps output is
## fixed, but the benchmark cannot prove it helped

sherpa-onnx ships an upper-case token vocabulary: across 17 benchmark
transcripts, 12,689 upper-case characters and zero lower-case. `CasingNormalizer`
now lower-cases it (commit `451a2fc`).

The benchmark caught a real regression in my first version. Applying sentence-case
per caption capitalised the first letter of *every* caption, and since Sherpa
endpoints fall mid-clause that manufactured false sentence starts — the judge
flagged "erratic mid-sentence capitalization ('Capacity', 'Day', 'Came')" and
scored it below the all-caps original. Fixed by capitalising only after a real
terminator, which for this engine means the first caption only.

Readability against the all-caps baseline, English clips:

| clip | baseline | with casing |
|---|---|---|
| lj_read_01 | 40 | 32 |
| lj_read_02 | 28 | 33 |
| lj_read_03 | 35 | 25 |

That is a mean of about -4, with per-clip swings of -10..+5 — and re-judging one
identical transcript moved it 40 -> 33 on its own. **The effect is smaller than
the measurement error**, so these numbers neither confirm nor refute the change.
See the noise-floor note in SPEC.md section 3.

The change stays in on typographic grounds, not benchmark grounds: no captioning
product ships all-caps, and the judge's readability score is visibly dominated by
something else (below). If it needs to be settled empirically, it needs more
English clips.

## Next lever: caption boundaries, not text formatting

Every judge rationale in every variant leads with the same defect — captions cut
mid-word. "refractor" | "y ward", "con" | "finement", "grave ab" | "uses",
"auth" | "ority", "open" | "ed". These do double damage: they wreck readability,
and the translator then renders the fragments as nonsense ("khúc xạ y phường",
"trong conen Bản tóm tắt", untranslated "Grave AB"), so translation accuracy is
dragged down by an upstream segmentation bug.

This is worth more than any casing or punctuation work: it is the one defect the
judge names in all three languages, and unlike punctuation it has an unambiguous
fix — never end a caption inside a word.

## Finding 5 (FIXED): the mid-word caption splits were three separate bugs,
## and the worst one was in the benchmark itself

The judge named mid-word splits in every rationale, in all three languages
("refractor" | "y ward", "con" | "finement", "auth" | "ority"). Tracing them
found three independent causes, only two of which were in the SDK.

**1. A hard endpoint that ignored silence (asr/sherpa).** `rule3` was
`minTrailingSilence = 0`, `minUtteranceLength = 10s` — an elapsed-time cut
that ended the utterance wherever the audio happened to be, mid-word
included. It now waits for a 0.15 s gap, so the cut lands between words. A
speaker who never pauses gets a longer caption instead, which is the right
trade: a long caption is readable, a severed word is not. Guarded by a unit
test asserting no endpoint rule may fire at zero trailing silence.

**2. No end-of-stream drain (sdk).** Pooled text escaped the sentence
extractor only via a 3 s idle timer, which every partial cancelled. When
audio ended the channel closed and the remainder went with it. Now drained
explicitly at end of stream. Affects every engine that emits no terminators.

**3. The benchmark cut every clip short (benchmark) — the big one.**
`playedOut` was `elapsed >= durationSec + 2s`, timed from `start()`. But
`FileAudioCapture` decodes the entire mp4 *inside* `frames()`, before pacing
begins, so the clock started ~25 s before the audio did. Measured on
lj_read_02: the harness exited at `elapsedMs=67033` against `clipMs=67000`
while the recogniser was still emitting. The runner now waits for the
capture to signal its last frame.

Effect on lj_read_02: 607 -> 925 characters, 4 -> 7 captions, and the
transcript now reaches the true end of the clip. No mid-word artifact
survives in any English clip, and no judge rationale mentions one.

**The scores did not move outside the noise** (readability -8, +7, -5;
mean about -2 against a floor of +-7). What changed is the *rationales*: the
fragment-driven translation nonsense is gone. "refractory ward" was reaching
users as "khúc xạ y phường" ("refraction Y ward") because the two halves were
translated separately; it now translates as a phrase. Per-cell scores are too
coarse to show that, which is a limitation of the metric, not evidence the
bug was harmless.

**The committed baseline was measured with truncated audio.** Every clip in
it is missing its tail, so tail content is not comparable against any future
run. Re-baselining is a deliberate act (SPEC section 3) and has not been done.
