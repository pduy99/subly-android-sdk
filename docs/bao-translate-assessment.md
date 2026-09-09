# What Subly can take from Bao-Translate

> **Status, 2026-09-09.** Items 1 and 4 are now implemented — `:asr:vad`
> (`SpeechGate`) and `:translator:litertlm` (`LiteRtLmTranslator`). What was
> measured on the way is recorded in
> [`litertlm-and-vad-measurements.md`](litertlm-and-vad-measurements.md).
> Items 2, 3 and 5 remain open.

Source: <https://github.com/d4551/Bao-Translate> — Apache-2.0, single author, 7 stars,
created 2026-06-03, last push 2026-07-26. Assessed 2026-09-09 against `dev` @ c5c4487.

Bao is an **app**, Subly is an **SDK**; Bao is **mic + turn-based** (VAD cuts a turn →
commit → translate → TTS), Subly is **system-audio + continuous**. That difference decides
most of what transfers and what doesn't.

Overlap that makes it worth reading at all: same three ASR engines (sherpa-onnx Zipformer,
Vosk small, chunked Whisper), same Silero VAD, same sherpa-onnx **1.13.2** AAR, same
alphacephei model URLs.

## Adopt, ranked

### 1. Silero VAD via the sherpa-onnx Kotlin API — `stt/VadProcessor.kt`

Our Silero gate lives inside `asr/whisper/src/main/cpp/whisper-jni.cpp`, so **Sherpa and Vosk
have no non-speech gate at all**. Bao gets the same VAD from `com.k2fsa.sherpa.onnx.Vad` +
`SileroVadModelConfig` — i.e. from the *AAR we already ship*. **Confirmed present** in
`asr/sherpa/libs/sherpa-onnx-1.13.2.aar`: `Vad`, `VadModelConfig`, `SileroVadModelConfig`,
`TenVadModelConfig` are all in its `classes.jar`, backed by the `libsherpa-onnx-jni.so` we
already package. A shared `SublyVad` in `audio:api` (or `core`) would gate all three engines
with **no new native build**.

It is not free of provisioning, though: sherpa's `Vad` wants a **`.onnx`** Silero model. The
`ggml-silero-v5.1.2.bin` we already download is the GGML build for whisper.cpp and is not
usable here — expect a second ~2 MB model and a second catalog entry.

Worth copying:
- `VadInitResult` = `Initialized | ModelUnavailable | Failed(msg)` + RMS/peak energy fallback.
  Maps cleanly onto our "`prepareModel` must terminate `Ready` or `Error`, never throw" rule —
  a missing VAD model degrades instead of failing prepare.
- `synchronized(inferenceLock)` around every native call *and* `cleanup()`, so the handle can't
  be freed mid-decode. Our engines already do this; theirs is a good cross-check.

Do **not** copy the shape: their `processAudioSegment` does `reset → acceptWaveform → flush →
drain` per buffer, which is batch/turn-based. Our streaming path needs the incremental form
with no per-call reset.

### 2. `validation/ValidationUtils.kt` — hallucination/noise text filter

Accumulated knowledge we don't have, and it is engine-agnostic (belongs at the session layer,
not in `asr/whisper`):
- Parenthesised and bracketed sound-caption rejection — `(soft music)`, `[BLANK_AUDIO]`,
  `[Applause]` — as anchored regexes over a `music|applause|laughter|…` word set.
- Cross-linguistic **filled-pause** rejection: `hmm+|uh+|äh+|euh+|хм+|мм+`, anchored so a
  whole-utterance filler string is dropped but a real word never is.
- `^[^\p{L}]+$` = "no letters of any script" → pure noise. Their comment records the
  regression: a plain `[\s\d\W]` version was killing whole Cyrillic/CJK utterances.

Skip their `trimmed.length < 2` gate, or script-gate it: the `\p{L}` regex got the non-Latin
pass but the length check didn't, and a single-character CJK utterance is a real word. We ship
zh/ja/ko through Vosk and maintain `CjkSpacing`, so that one would bite us specifically.
- `isSourceEcho` — the on-device-LLM failure where the model returns the source verbatim.
  Only relevant if we ship an LLM translator (§4), but it is the right guard for it.

Keep our policy, not theirs: `RepetitionFilter.collapse` **keeps the first occurrence** and
drops the repeats; their `isValidTranscription` **rejects the whole utterance**. For captions
ours is correct — borrow the regexes, not the verdict.

### 3. Resumable, retrying model downloads — `BaoTranslateHttpDownload.kt`

`OkHttpModelDownloader` is 68 lines: no resume, no retry, no integrity check, no free-space
check. We pull 30–90 MB models and our own README's error example is "a failed model download
on flaky connectivity". Theirs has: 5 attempts, `Range:` resume from the partial file,
expected-size validation, free-space reservation before writing, and a wrapper that
`disconnect()`s on every exit path including cancellation.

Two smaller details worth lifting: they **throttle progress emission** (at most every 100 ms
or 1 %) — we emit per 8 KB chunk, which is a lot of `Flow` traffic on a 90 MB download — and
they **fall back to a fresh download when the server answers 200 instead of 206**, so a mirror
without `Range` support can't corrupt a resumed file.

One trade-off to keep in mind: resuming means writing straight to the target file, so they lose
our `.tmp` + rename atomicity and lean on a final size check instead. Resume into `.tmp` and
rename on completion to keep both.

They use `HttpURLConnection`, we use OkHttp — this is a reimplementation, not a copy, which
also sidesteps the licence question. Add a `sha256` field to `VoskModels` / `SherpaOnnxModel`
while touching this; today a truncated file that survived the rename would be treated as a good
model, and `downloadModel` returns early on any `exists() && length > 0`.

### 4. LiteRT-LM as a second translator — `translate/TranslationPipeline.kt`

Biggest possible quality jump over ML Kit, and `SublyTranslator` is already the right seam
(`prepareModel` / `translate` / `release` / `supportedLanguages`). Bao runs Qwen2.5 1.5B (or
Gemma 4 E2B) through `com.google.ai.edge.litertlm:litertlm-android`, on **Google Maven**
(verified: latest `0.17.0`; Bao pins `0.13.1`, so expect API drift).

Two constraints to state up front, not discover later:
- **Latency.** `translateBlocking` is one full `synchronized` generation per call, and Bao
  never runs it on partials — only on committed turns. The honest shape for us is
  **LiteRT-LM on finals, ML Kit on partials**, which fits our existing tail-only partial
  translation. Needs a device measurement before it's more than an idea — and note this is
  *not* a drop-in module: `Subly.Builder().setTranslator { }` is a single factory and a session
  owns exactly one translator, so the hybrid means a composite `SublyTranslator` wrapping both,
  or an API change.
- **Footprint.** 1.5 GB moves Subly out of the ~90 MB-engine category. Product decision.
- **Model licensing.** Qwen2.5 and Gemma ship under different terms — Gemma's Terms of Use are
  not OSI-approved and restrict downstream use. For an SDK third parties redistribute, that
  constrains the model choice more than the 1.5 GB does.

Reusable regardless of the engine: the prompt shape ("produce only the translation, no
commentary"), `temperature 0.3 / topK 40 / topP 0.9`, `cleanTranslation` stripping quotes and
`"Translation: "` prefixes, and the echo/repetition guards on the output.

Also spotted in their version catalog: `com.google.mlkit:genai-prompt` (Gemini Nano) as
another on-device translator path — untried by them, but a cheaper footprint than a 1.5 GB LLM.

### 5. Low priority — `audio/AudioResampler.kt`

32-tap Hamming-windowed-sinc fractional resampler. We ask `AudioRecord` for 16 kHz directly,
so our resample path is usually dead code; but `SherpaOnnxTranscriber.linearResample` and
`VoskTranscriber.linearResample` are 2-point linear (≈ −15 dB images) and *do* run when a host
app feeds non-16k frames. Cheap drop-in hardening for that path only.

## Not useful

Kokoro / Supertonic TTS, OpenVoice voice cloning, LibreDrop (Quick Share protocol), the BLE
conversation manager, Bluetooth audio routing, Compose UI and view models. Subly is
captions-only and headless.

Their language keying is worse than ours — `"Spanish"` as a map key with a `CODE_MAP` beside
it, vs our BCP-47 tags. Don't take it.

Their sherpa Maven coordinate idea is dead too: `com.k2fsa.sherpa:onnx` 404s on
`repo1.maven.org`, and a Central search for `k2fsa` returns zero artifacts. Their own catalog
comment says they deliberately vendor the same AAR the same way we do. Our `compileOnly` +
app-side AAR wiring isn't our mistake — there is no published coordinate to switch to.

## Nothing for the problem we're actually working on

Our last five commits are sentence assembly and flush timing. Bao has **no equivalent layer**:
`StreamingCaptionController.feedCaptionPartial` writes the growing hypothesis straight into
`uiState.liveSourcePreview` and it is **never translated**; translation runs once per
VAD-committed segment in `runSegmentPipeline`. No sentence pooling, no partial translation, no
frozen-prefix re-translation, no idle flush. Subly is ahead here — turn-based capture sidesteps
the whole problem we have.

## Licence

Apache-2.0 → GPL-3.0 is one-way compatible: copying in is fine with notice retention and a
statement of changes. Wrinkle: the files we'd most want (`ValidationUtils.kt`, `VadProcessor.kt`,
`AudioResampler.kt`) carry **no per-file copyright header**, only the repo-level LICENSE —
`StreamingCaptioner.kt` and `VoskStreamingPipeline.kt` do carry the Google header (the repo is
an AI Edge Gallery derivative). Cleanest path: reimplement the downloader and the VAD wrapper
(we need different lifecycle contracts anyway), and attribute properly only what we take
verbatim — the resampler and the regex sets.

## Trust caveat

Well-commented, and several comments record real on-device debugging (the `MAX_TOKENS = 2048`
note describes an actual "failed to invoke the compiled model" session). But: one author, three
months old, 563 tests claimed in the README and unverified here, and at least one clearly
fragile line — `TranslationPipeline.extractText(message: Message) = message.toString()` to pull
text out of an SDK message object. Mine it for ideas; treat no constant as validated.
