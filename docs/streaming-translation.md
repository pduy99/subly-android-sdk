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
| ja (SOV) | wait for clause | punctuation, or a verb-final marker (`ます`, `ました`, `です`, `masu`, `desu`, …) |
| ko (SOV) | wait for clause | punctuation, or `니다`, `어요`, `까요`, … |
| tr, hi, ur, fa, bn, ta, te, mr, my, am (SOV) | wait for clause | punctuation |

For an SOV source the verb comes last ("watashi wa ringo o **taberu**"). A
translation of the clause without it has to invent a verb, so nothing is
committed until the clause closes. Plain dictionary forms (`-ru`, `-u`) are
deliberately not markers: in kana they end countless non-verbs, and a false
boundary is the failure this exists to prevent.

**Revisions.** Text already committed is on screen and cannot be retracted.
If the final no longer starts with it (`FinalDelta.Diverged`), the whole
sentence is translated afresh and replaces the pieces.

## 3. Failure modes

### 3.1 Hardware delegate fallback

`LiteRtLmTranslator.prepareModel` tries `preferredDelegates` (default GPU,
then CPU) and keeps the first that **initialises and completes a 4-token
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
| LLM weights | 1,597,931,520 bytes, memory-mapped | file size; resident pages are file-backed and reclaimable, so they count in RSS but the kernel can drop them |
| LLM KV cache | ≤ 112 MiB | 2 (K,V) × 28 layers × 2 KV heads × 128 head dim × 4 bytes (fp32 upper bound) × 2,048 tokens (`maxNumTokens`) |
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
