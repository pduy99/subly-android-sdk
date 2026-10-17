# Streaming translation: wait-k, incremental LLM translation, delegates

How the SDK turns a growing ASR hypothesis into a translation that appears
while the sentence is still being spoken, what happens when hardware or
input misbehaves, and what it costs. Measured numbers are in
[the measurements section](#measurements); everything else in this document
is either design or arithmetic from published model dimensions, and says
which.

---

## 1. Pipeline

```
capture ─▶ ASR (Vosk | whisper.cpp) ─▶ SublySession collector ─┬─▶ captions (source, live)
                                                                │
                                         onPartial / onFinal    ▼  (never blocks)
                                                      StreamingTranslationStage
                                                      ├─ WaitKBuffer: stable? clause?
                                                      ├─ work queue (merged when behind)
                                                      └─ worker coroutine ─▶ TranslationSegment
                                                                              (one LLM conversation
                                                                               per sentence, streamed)
                                                                 │
                                       translationState / captions (translated, live and final)
```

Before this change an LLM translator was finals-only, and its multi-second
generation ran on the coroutine that collects ASR results, so it
back-pressured the recogniser and, through it, audio capture
(`litertlm-and-vad-measurements.md` §2). Three things changed:

1. **Translation runs on its own coroutine.** The ASR collector only calls
   `onPartial` / `onFinal`, which run the wait-k decision and enqueue work.
   `IncrementalTranslatorSessionTest` checks that ASR finishes emitting a
   sentence while a one-second generation is still in flight.
2. **Sentences are translated a clause at a time.** `WaitKBuffer` commits
   settled clauses; each is one turn in a conversation that lives for the
   sentence, so earlier clauses are context already in the KV cache, not text
   prefilled again. The final translates only what is left.
3. **Tokens stream.** `sendMessageAsync` delivers the translation as it is
   generated, so time to first token, not time to whole translation, is what
   the reader waits for.

Engines that translate fast enough for partials (ML Kit) are untouched: they
still translate inline.

**Drafts are off by default.** With them on (`Subly.Builder
.setTranslationDrafts(true)`), settled clauses are translated while the
sentence is still being spoken. On the measured device that puts text on
screen far earlier in a sentence, but the extra generations compete with the
final for the GPU and delay it (see [Measurements](#measurements)). With them
off, each sentence is one streamed generation started at its ASR final,
still off the recogniser's coroutine. `WaitKPolicy` keeps drafts on, so code
driving the stage directly gets wait-k drafting.

## 2. Wait-k: when a hypothesis may be translated

Two questions, answered separately (`WaitKBuffer`):

**Stable?** A unit (word, or character in unspaced scripts) is stable once two
consecutive hypotheses agree on it: the local-agreement policy from
streaming Whisper. Translating an unstable word risks showing a translation
the recogniser takes back.

**Translatable?** Depends on the source word order:

| Source | Policy | Commits when |
|---|---|---|
| SVO (en, fr, es, vi, …) | k = 4 words | 4 stable words, or clause punctuation |
| zh, th, lo, km | k = 6 characters | 6 stable characters, or punctuation |
| ja (SOV) | wait for clause; noun-phrase drafts | punctuation, a verb-final marker (`ます`, `ました`, `です`, `masu`, `desu`, …), or a case particle (draft only) |
| ko (SOV) | wait for clause | punctuation, or `니다`, `어요`, `까요`, … |
| tr, hi, ur, fa, bn, ta, te, mr, my, am (SOV) | wait for clause | punctuation |

For an SOV source the verb comes last ("watashi wa ringo o **taberu**"). A
translation of the clause without it has to invent a verb, so the verb is
never committed before it is final. Noun phrases may be: an unambiguous case
particle (romaji `wa`/`o`/`ga`/`ni`/`de`/`e`/`mo`, kana `を`) ends one, and
with drafts on the reader sees "I … apples" before the verb arrives. Pieces
translated in source order would read "I apples eat", so an SOV sentence's
final is translated whole (`restructureOnFinal`) rather than stitched from
its pieces. Plain dictionary forms (`-ru`, `-u`) are
deliberately not markers: in kana they end countless non-verbs, and a false
boundary is the failure this exists to prevent.

**Revisions.** Text already committed is on screen and cannot be retracted.
If the final no longer starts with it (`FinalDelta.Diverged`), the whole
sentence is translated afresh and replaces the pieces.

## 3. Failure modes

### 3.1 Hardware delegate fallback

`LiteRtLmTranslator.prepareModel` tries `preferredDelegates` (default GPU,
then CPU) for the model `LiteRtLmModel.forDevice` picks: Qwen3-4B-Instruct-2507
(mixed int4) with ≥ 7 GB RAM, Qwen2.5-1.5B below. It and keeps the first that **initialises and completes a 4-token
test generation**. Init alone is not proof: a GPU path can initialise and
then fail on its first kernel.

| What goes wrong | What happens | Visible as |
|---|---|---|
| Delegate throws (no GPU support for the model, missing NPU libraries, OOM at init) | Recorded, next delegate tried | `AccelerationReport.attempts[i].failure`; `isFallback = true` |
| Delegate kills the process in native code | A marker file written before the attempt survives; the next launch skips that delegate for 24 h | attempt `failure = "skipped: the process died during init on this delegate N min ago"` |
| Every delegate fails, CPU included | `ModelPrepState.Error`, session `EngineState.Error` | error message lists each delegate's failure |
| NPU requested | Only useful with a checkpoint compiled for the device's NPU. The default Qwen2.5 checkpoint is not, so NPU is not in the default order | — |

**Whisper** (`WhisperTranscriber.prepareModel`) tries the GPU through ggml's
OpenCL backend, then the CPU:

| What goes wrong | What happens | Visible as |
|---|---|---|
| No vendor `libOpenCL.so`, or the vendor does not expose it to apps | The shim's `dlopen` fails, every OpenCL call reports "no platform", ggml registers no OpenCL device | GPU attempt `failure = "no OpenCL GPU device …"`, CPU active, default `small-q5_1` model |
| OpenCL present but the device is dropped as unsupported by ggml | Same as above | same |
| GPU init or its warm-up inference fails | CPU init on the same (q8_0) file | GPU attempt failed, CPU active |
| GPU path kills the process | Marker survives; next launch skips the GPU for 24 h | GPU attempt `failure = "skipped: …"` |
| An op the OpenCL backend does not implement | ggml's scheduler runs that op on the CPU; correctness unaffected | not reported per op |

Why q8_0 on the GPU: the OpenCL backend has Adreno-tuned q8_0 (and q4_0,
f16) matmul kernels and none for q5_1, so the CPU default model on the GPU
would leave its matmuls on the CPU.

**Vosk** has no accelerator path and always reports `CPU`.

### 3.2 Worst-case latency

| Scenario | Bound | Where it comes from |
|---|---|---|
| SVO, steady speech | k words + one generation | wait-k = 4 words ≈ 1.6 s at 150 wpm, then TTFT |
| SOV clause with no marker and no punctuation | `maxWaitUnits` units, then forced commit | ja: 24 chars; ko/tr/hi: 10 words ≈ 4 s at 150 wpm; counted as `FORCED_COMMIT` |
| Continuous speech with no pause | ASR's own segment cap, then the wait-k cap | Whisper closes a segment at 5 s (`MAX_SEGMENT_MS`); Vosk endpoints on silence |
| Translator slower than speech | Bounded queue, not a growing backlog | Pieces queued behind a running generation are merged into one append; at most one pending piece per sentence plus its final |
| Translator falls a sentence behind | Live partials for the old sentence are suppressed | The overlay keeps the source live; the old sentence's translation arrives as its final |
| A piece fails | The sentence is not lost | The final retranslates the whole sentence |
| Final rewrites committed text | One extra full generation | `Diverged` path |

### 3.3 Memory bounds

| Component | Bound | Basis |
|---|---|---|
| LLM weights | Qwen3-4B 2,659,057,664 bytes / Qwen2.5-1.5B 1,597,931,520 bytes, memory-mapped | file size; resident pages are file-backed and reclaimable, so they count in RSS but the kernel can drop them |
| LLM KV cache | Qwen3-4B ≤ 576 MiB / Qwen2.5-1.5B ≤ 112 MiB | 2 (K,V) × layers (36 / 28) × KV heads (8 / 2) × 128 head dim × 4 bytes (fp32) × 2,048 tokens |
| Conversations alive | 1 | one segment per sentence; closed before the next opens |
| Whisper small q5_1 (CPU) / q8_0 (GPU) | 190,085,487 / 264,464,607 bytes + compute buffers | model files; buffers measured below |
| Vosk small | 31–133 MB per language | `VoskModels` |
| Stage queue | O(1) per sentence | merged appends (3.2) |

Peak RSS, PSS and native heap during real streaming are in the
measurements.

## 4. Measuring

```
./gradlew :benchmark:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.helios.subly.benchmark.latency.StreamingLatencyBenchmarkTest
benchmark/tools/latency_report.py
```

`latency.json` carries, per clip and as p50/p90/p99 over the run:

| Metric | From | To |
|---|---|---|
| `ttft_ms` | clause committed / final arrived | first translated token |
| `piece_ms` | clause committed | its translation complete |
| `final_latency_ms` | ASR final | translated sentence published |
| `speech_end_to_translation_ms` | last voiced audio frame before the ASR final | translated sentence published |
| `speech_onset_to_first_translation_ms` | speech resumes | first translated text of that sentence |

plus peak RSS / PSS / native heap and every delegate attempt.
`PipelineBenchmarkTest` (`com.duypham.translation.benchmark`) is the task
specification's own test, unmodified, bound to the SDK by
`PipelineAdapter.kt`; its JUnit XML is the structured pass/fail record.

## Measurements

All on one Samsung Galaxy S24 Ultra (SM-S928B, Snapdragon 8 Gen 3 / SM8650,
Adreno 750, 12 GB, Android 16), 2026-10-17. Raw `latency.json` files are
regenerated by the harness; the tables here are the record.

### Delegates

| Engine | Delegate | Init | Notes |
|---|---|---|---|
| Qwen3-4B-Instruct-2507 int4 (LiteRT-LM) | GPU | 9–27 s (first init compiles kernels) | no fallback needed |
| Qwen2.5-1.5B int8 (LiteRT-LM) | GPU | 8.2 s | |
| whisper.cpp small q8_0 | GPU (OpenCL) | 2.4–27.8 s | after three fixes, below |
| Vosk small | CPU | 0.5–0.9 s | no accelerator path |

Getting whisper.cpp onto the Adreno GPU took three fixes, each found on the
device: a null backend context during weight loading (ggml-opencl
initialised it lazily, whisper.cpp asks earlier than llama.cpp does), an
assertion on the 51,865-row token embedding in the Adreno weight transpose,
and empty transcripts with flash attention on, so the GPU path runs without
it. The crash marker turned the first two crashes into CPU fallbacks on the
next launch, as designed. On this device the GPU path is not faster than
the CPU one (real-time factor ≈ 0.7 vs ≈ 0.65 on a 23 s clip); it leaves
the CPU free and passes the delegate requirement.

### Translator quality and speed (`TranslatorModelProbeTest`, GPU)

| | Qwen2.5-1.5B | Qwen3-4B-Instruct-2507 |
|---|---|---|
| time to first token | 443–469 ms | 1,063–1,107 ms |
| "Watashi wa ringo o taberu." | "I eat ramen." | "I eat apples." |
| 私はりんごを食べます。 | "I will eat an apple." | "I eat apples." |
| en→vi "Alloys are basically a mixture of two or more metals." | Vietnamese mixed with Chinese characters | correct |

Hence `forDevice` picks the 4B model where memory allows.

### Streaming latency (`StreamingLatencyBenchmarkTest`)

Vosk ASR, Qwen3-4B on the GPU, FLEURS en_01, en_02 (en→vi), ja_01, ja_02,
zh_01, zh_02 (→en), played at real time; 20–21 sentences per run. The three
runs were back to back; the device reported thermal status 3 (severe) by the
end, so absolute numbers are pessimistic and only this table's columns are
comparable with each other.

| ms | Baseline: finals inline (pre-change) | Streaming, drafts | **Streaming, no drafts (default)** |
|---|---|---|---|
| speech end → first translated token, p50 / p90 / p99 | 3,991 / 5,621 / 6,982 | 4,235 / 8,029 / 10,139 | **1,754 / 2,863 / 3,022** |
| speech end → full translation, p50 / p90 / p99 | 3,991 / 5,621 / 6,982 | 6,960 / 9,538 / 11,321 | 4,348 / 5,449 / 7,031 |
| ASR final → first token, p50 / p90 | 3,415 / 4,495 | — | 1,406 / 1,590 |
| speech onset → first translated text, p50 | 10,603 | 2,757 | 8,565 |
| peak RSS / native heap (MB) | 1,864 / 1,618 | 1,858 / 1,618 | 1,867 / 1,618 |

Peak PSS was 5.6–5.7 GB in every run; it includes the GPU's graphics
allocations, which `VmHWM` (RSS) does not.

What this says:

- **Off the recogniser's coroutine and streamed, a sentence's translation
  starts 2.3× sooner** after the speaker stops (p50 1.75 s vs 3.99 s). The
  whole translation takes about as long as before: the 4B model's decode
  rate on this GPU is the floor.
- **Drafts trade the end of a sentence for its middle.** Text appears ~2.8 s
  into a sentence instead of ~8.6 s, but every draft is a generation the
  final waits behind. An earlier variant that let the final preempt a draft
  in flight was worse still (speech end → full translation p50 5,728 ms):
  the cancelled draft's conversation cannot be continued, so the sentence
  had to be retranslated whole.
- Preliminary runs earlier the same day, on a cooler device, measured the
  drafts configuration at p50 3,752 ms speech end → full translation:
  run-to-run variation under thermal load is of the same order as the
  differences above.

### The task specification's test (`PipelineBenchmarkTest`)

Whisper (GPU) for audio, Qwen3-4B (GPU) for translation.

| Test | Result | Measured |
|---|---|---|
| 1 — no CPU fallback | **pass** | Whisper GPU, translator GPU |
| 2 — wait-k, no premature verb, SVO restructuring | **pass** | drafts "I", "I am" before the verb; final "I eat apples." |
| 3 — TTFT ≤ 300 ms, end to end ≤ 800 ms | **fail** | TTFT 4,246 ms = whisper final 2,963 ms (958 ms queued behind the partial window + 2,005 ms inference) + LLM first token 1,195 ms |

Test 3's limits are below what this hardware can do with these models:
whisper small needs ~0.9 s on this GPU to decode its first 1.5 s window, and
the 4B translator ~1.1 s to its first token, so the floor for "audio in →
first translated token" is ~2 s before any queueing. The 1.5B translator
would save ~0.6 s and translate wrongly (above).

## Chinese (Mandarin) on an 8 GB phone

Galaxy S10 (SM-G973F, Exynos 9820, Mali-G76, 7.6 GB, Android 12), 2026-10-17.
Test clip: `benchmark/src/androidTest/assets/realworld/zh_dialogue_01.mp4`, 48 s of
fast multi-speaker dialogue with names and slang; reference = its subtitles.

### Recognition (`ChineseAsrProbeTest`, character error rate)

| Recogniser | Dialogue clip | FLEURS zh 01 / 02 | Real-time factor |
|---|---|---|---|
| Vosk small-cn | 53.9% | 14.8% / 12.3% | 0.71 |
| whisper small q5_1 (CPU) | 42.4% | 48.9% / 52.6% | 1.1–8.3 |
| **sherpa streaming zipformer-zh (2025-06-30, int8)** | **14.5%** | 5.7% / 6.1% | 0.31 |
| **SenseVoice (2025-09-09, int8) + VAD** | **12.7%** | 9.1% / 6.1% | 0.13 |
| sherpa zipformer multi-zh-hans (2023-12-13) | 14.5% | 12.5% / 5.3% | 0.26 |
| Paraformer-zh (2025-10-07) | 17.0% | 11.4% / 6.1% | 0.09 |

The app now uses the zipformer for Streaming mode and SenseVoice for Batch mode
when the source is Chinese. Neither punctuates, and a final from fast dialogue
was several subtitle lines in one run, so `ChinesePunctuator` (CT-Transformer,
int8, SHA-256-verified) restores 。，？ in finals and partials, and
`CjkSentenceSplitter` cuts at clause punctuation (18 characters). **Measured in
the shipping app** (system-audio capture of the clip played in the phone's video
player): **14.5% character error in Streaming mode (15.8% on a second run) and
13.9% in Batch mode**, with subtitle-sized lines, against ~60% for the Vosk
pipeline it replaces.

### Translation, Chinese → Vietnamese (`TranslatorModelProbeTest`, the clip's lines)

| Translator | Time to first token | Outcome |
|---|---|---|
| ML Kit | 65–140 ms | about half the colloquial lines right ("Bạn cũng bán cái này, phải không?"); literal on others (等会儿 → "Chờ đợi bạn") |
| ML Kit via English | same | identical output: it already pivots |
| Qwen3-4B, GPU | ~10 s | best quality ("Anh là người bán này à?"), too slow for live captions here |
| Qwen3-4B, CPU | — | killed for memory at 4.6 GB |
| Qwen2.5-1.5B, GPU | 2.7–5.6 s | answers in Chinese (stripped by the leak guard to "。") |
| Qwen3-1.7B, GPU (LiteRT-LM 0.18, not shipped) | 2.2–4.5 s | English, Thai, leaked reasoning |

So on 8 GB phones the app translates with ML Kit (`LiteRtLmModel.canRunLargeModel`
is false), and on 12 GB-class phones with Qwen3-4B. On this class of phone
**recognition is good and translation is limited by ML Kit's quality on
colloquial speech**; no measured on-device LLM is both live and correct here.
