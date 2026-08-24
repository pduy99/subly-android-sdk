# Subly SDK

On-device, real-time speech captioning and translation for Android. Subly captures system audio (via `MediaProjection`), transcribes it locally with a pluggable ASR engine, translates the result on-device, and streams ready-to-render captions to your app — no audio ever leaves the device.

```
System audio ──▶ Capture ──▶ ASR engine ──▶ Sentence assembly ──▶ Translator ──▶ Caption stream
                              (Sherpa /                            (ML Kit)
                               Whisper /
                               Vosk)
```

---

## Requirements

- `minSdk` 34
- arm64-v8a device — the native ASR libraries ship 64-bit ARM binaries only
- A `MediaProjection` token obtained by your app (see [Permissions](#permissions))
- Kotlin coroutines — the entire public API is `Flow`-based

## Installation

The SDK is not published to a Maven repository yet. Consume it as a [Gradle composite build](https://docs.gradle.org/current/userguide/composite_builds.html):

**1. Clone the repo** (e.g. next to your app project):

```bash
git clone https://github.com/pduy99/subly-android-sdk.git
```

**2. Include the build** in your app's `settings.gradle.kts`:

```kotlin
includeBuild("../subly-android-sdk") {
    dependencySubstitution {
        substitute(module("com.helios.subly.sdk:core")).using(project(":sdk"))
        substitute(module("com.helios.subly.asr:sherpa-onnx")).using(project(":asr:sherpa"))
        substitute(module("com.helios.subly.asr:whisper")).using(project(":asr:whisper"))
        substitute(module("com.helios.subly.asr:vosk")).using(project(":asr:vosk"))
        substitute(module("com.helios.subly.translator:mlkit")).using(project(":translator:mlkit"))
    }
}
```

**3. Declare the dependencies** — Gradle substitutes them with the included build:

```kotlin
dependencies {
    implementation("com.helios.subly.sdk:core:0.0.1")

    // Pick at least one ASR engine:
    implementation("com.helios.subly.asr:sherpa-onnx:0.0.1") // streaming, partial results
    implementation("com.helios.subly.asr:whisper:0.0.1")     // chunked, higher accuracy
    implementation("com.helios.subly.asr:vosk:0.0.1")        // streaming, tiny footprint, 18 languages

    // Translator:
    implementation("com.helios.subly.translator:mlkit:0.0.1")
}
```

> **Using the sherpa engine?** `:asr:sherpa` compiles against the bundled `asr/sherpa/libs/sherpa-onnx-1.13.2.aar` as `compileOnly` (AGP doesn't allow local AARs as `implementation` inside library modules). Your **app** module must add the same AAR to its runtime classpath:
>
> ```kotlin
> implementation(files("../subly-android-sdk/asr/sherpa/libs/sherpa-onnx-1.13.2.aar"))
> ```

Building the whisper engine requires the Android NDK and CMake 3.22+ (whisper.cpp is compiled from vendored sources).

---

## Quick start

```kotlin
// 1. Build once — Subly is a cheap, reusable configuration object.
//    Engines are provided as factories; each session gets fresh instances.
val subly = Subly.Builder()
    .setAsrEngine { SherpaOnnxTranscriber(context) }
    .setTranslator { MlKitTranslator() }
    .build()

// 2. Create a session per language pair. Construction is free — no I/O yet.
val session = subly.createSession(
    LanguageConfig(source = "en", target = "vi")
)

// 3. Observe lifecycle for your loading / ready / error UI.
scope.launch {
    session.state.collect { state ->
        when (state) {
            is EngineState.Idle      -> showIdle()
            is EngineState.Preparing -> showProgress(state.progress) // 0f..1f
            is EngineState.Ready     -> showReady()
            is EngineState.Running   -> showCaptioningActive()
            is EngineState.Error     -> showError(state.error)
            is EngineState.Closed    -> { /* session is gone */ }
        }
    }
}

// 4. Observe captions for your overlay.
scope.launch {
    session.captions.collect { caption ->
        overlay.render(
            text = caption.translatedText,
            isStable = caption.isFinal, // false = partial, may be revised
        )
    }
}

// 5. Run it.
session.prepare()              // optional pre-warm; start() triggers it anyway
session.start(mediaProjection) // begins capturing & captioning
// ...
session.stop()                 // pause; models stay warm, start() resumes instantly
session.close()                // done; releases engines. Session can't be reused.
```

---

## Core concepts

### `Subly` — build once, keep forever

`Subly` holds engine **factories**, not engine instances. It performs no I/O, holds no native resources, and is safe to keep as a singleton (e.g. provided by Hilt). All heavy lifting belongs to sessions.

### `SublySession` — one language pair, one lifecycle

A session owns its engines exclusively. Closing one session never affects another. The lifecycle:

```
Idle ──prepare()──▶ Preparing ──▶ Ready ◀──stop()── Running
                        │           │                  ▲
                        ▼           └────start()───────┘
                      Error ──prepare()──▶ Preparing (retry)

(any state) ──close()──▶ Closed   [terminal]
```

Rules of thumb:

- **`prepare()` is optional but recommended.** Call it as soon as the user lands on your home screen so models download/extract while they're still choosing languages. `start()` waits for preparation automatically.
- **`Error` is recoverable.** Call `prepare()` (or `start()`) again to retry — useful after a failed model download on flaky connectivity.
- **`stop()` ≠ `close()`.** `stop()` halts capture but keeps models loaded; `start()` afterwards is instant. `close()` releases native resources and is terminal.
- **Changing languages = new session.** Close the old session and call `subly.createSession(newConfig)`. The `Subly` instance is reused.
- Calling `prepare()`/`start()` on a closed session throws `IllegalStateException` — fail fast, not silently.

### Two streams, two purposes

| | `state: StateFlow<EngineState>` | `captions: SharedFlow<Caption>` |
|---|---|---|
| Carries | Lifecycle only | Transcript + translation text |
| Conflated? | Yes (latest wins — fine for UI) | Finals are buffered, never silently dropped while collected; partials may be conflated under pressure |
| Late collectors | Always get current state | Replay the single most recent caption |
| Use for | Spinners, progress bars, status copy, error screens, analytics | Caption overlay, transcript history |

Don't drive your overlay from `state`, and don't infer engine health from `captions` — each flow does one job.

### Partial vs final captions

- `Caption(isFinal = false)` — a live hypothesis for the sentence in progress. **Replace** the currently displayed partial with each new one; never append.
- `Caption(isFinal = true)` — a completed unit, safe to append to history / persist. How finals are formed depends on the engine: streaming engines (Sherpa) emit each endpoint utterance one-to-one for the lowest latency, while chunked engines (Whisper) and the other pooled engines assemble finals into complete sentences first — finalized on punctuation or after 28 words, whichever comes first.

Note: all three engines emit partials + finals. Sherpa and Vosk partials arrive at a ~200 ms cadence (Vosk debounced); Whisper partials are re-transcriptions of the utterance-in-progress emitted every ~800 ms of speech. Build your overlay to handle both shapes.

Sherpa endpointing is tuned for captions: it finalizes after ~0.8 s of trailing silence (per spoken sentence/clause) with a ~10 s hard cap, so finals arrive every few seconds instead of letting one utterance grow for 10–20 s. Tune the `EndpointRule` values in `SherpaOnnxBackend` if you want longer or shorter segments.

Partial translation is **tail-only**: the session freezes the completed-sentence prefix of a partial (translating it once and caching it) and only re-translates the trailing in-progress clause. This keeps already-spoken text from reflowing under the reader as machine translation re-orders words with new context, and avoids re-translating the whole growing block each tick.

---

## Choosing an ASR engine

| | `SherpaOnnxTranscriber` | `WhisperTranscriber` | `VoskTranscriber` |
|---|---|---|---|
| Style | Streaming | Chunked (VAD-segmented windows + in-progress partials) | Streaming (Kaldi nnet3) |
| Partial results | ✅ ~200 ms cadence | ✅ ~800 ms cadence (growing-window re-transcription) | ✅ ~200 ms cadence (debounced) |
| Perceived latency | Lower | Low-moderate (first partial ≥ ~1.3 s into an utterance) | Lower (~0.15 s right context) |
| Accuracy | Good | Better, esp. noisy audio / accents | Fair (older Kaldi models; behind Zipformer) |
| Model delivery | Downloaded on first prepare (~90 MB int8 Zipformer, Hugging Face), or app-bundled assets if present | Downloaded on first prepare (~57 MB q5_1 model + ~0.9 MB Silero VAD, Hugging Face) | Downloaded + unzipped on first prepare (~30-50 MB small model, alphacephei.com) |
| Punctuation/casing | None (lowercase, unpunctuated) | Emitted by the model directly | None (lowercase, unpunctuated) |
| Non-speech handling | Streaming model is naturally robust to gaps | Three-layer guard rejects music/noise (see below) | Built-in Kaldi endpointing |
| Languages | en | en | 18 languages (en, en-in, zh, ru, fr, de, es, pt, tr, vi, it, nl, ca, ja, ko, hi, pl, uk) |
| Best for | Live conversation feel | Accuracy-first captioning | Tiny footprint / broad language coverage on low-end devices |

Add the engine you want to your app's dependencies (Vosk is a normal Maven
artifact — unlike sherpa it needs no app-side AAR wiring):

```kotlin
implementation("com.helios.subly.asr:vosk:0.0.1")
```

```kotlin
val subly = Subly.Builder()
    .setAsrEngine { VoskTranscriber(context) }   // picks the model from the session's source language
    .setTranslator { MlKitTranslator() }
    .build()
```

### Vosk model provisioning

`VoskTranscriber` resolves `LanguageConfig.source` to a `vosk-model-small-*`
build (see `VoskModels`), downloads the `.zip` from alphacephei.com on first
`prepare()`, and unpacks it into `filesDir/vosk/<name>/` (flattening the zip's
wrapper folder into the Kaldi `am/ conf/ graph/ ivector/` layout). Already-present
models are reused; an unsupported source language fails `prepare()` with
`IllegalArgumentException` listing the supported tags. Repoint
`VoskModels.DOWNLOAD_BASE_URL` at your own mirror for production, and bump a
model version in the same enum — it's the single source of truth. Output is
lowercase and unpunctuated, so finals lean on the SDK's word-count cap for
sentence assembly.

### Sherpa model provisioning

`prepareModel` makes the streaming Zipformer available in priority order:
on-disk (already downloaded) → unpack an app-bundled copy under
`assets/sherpa-onnx/<dirName>/` → download the int8 model files from Hugging
Face into app storage. int8 builds are the default (≈4× smaller and faster
than fp32 for a negligible WER cost); the backend resolves whichever
encoder/decoder/joiner `.onnx` files are present, preferring int8, so a bundled
fp32 copy works too.

Sherpa is a pure streaming engine: its output is **lowercase and unpunctuated**
by design — no punctuation/casing restoration runs. It also reports
`emitsCompleteUtterances = true`, so the session streams each endpoint final
one-to-one (translated and shown as-is) instead of pooling finals into
sentences — the lowest-latency path, and the reason the punctuation-based
sentence assembly is bypassed for this engine. Model source URLs live in
`SherpaOnnxModel`; repoint `downloadBaseUrl` at your own mirror for production.

### Non-speech rejection (Whisper)

Whisper is trained to always emit text, so background music, applause, or
steady noise make it hallucinate captions. The Whisper engine drops non-speech
windows through three layers, cheapest first:

1. **Silero VAD gate** — a small neural speech detector (`ggml-silero-v5.1.2.bin`,
   ~0.9 MB, downloaded on first prepare) runs *before* transcription. The
   upstream amplitude segmenter only knows loud-vs-quiet and can't tell speech
   from music; the VAD can. Windows with no speech frame above the threshold
   are dropped without paying for a Whisper decode (`vad_drop` benchmark line).
   The gate runs on **final windows only** — partials grow every ~800 ms and
   re-scanning the whole (up to 5 s) window each time added up to ~1 s of
   overhead on the hot path, while the VAD effectively always passes during
   real speech. Partials rely on layers 2–3 below, which run regardless.
2. **No-speech probability** — Whisper's own per-segment estimate; high values
   are dropped.
3. **Average token log-probability** — confidence of the decoded text. Music
   and noise hallucinations are usually low-confidence even when they slip past
   layers 1–2.

Layers 2–3 drop a window on *either* signal and emit a `no_speech_drop` line
with `reason=no_speech|low_conf`. Tune the thresholds (`VAD_SPEECH_PROB`,
`NO_SPEECH_THRESHOLD`, `AVG_LOGPROB_THRESHOLD`) in `whisper-jni.cpp`: raise
`VAD_SPEECH_PROB` if music still leaks; lower it (or make `AVG_LOGPROB_THRESHOLD`
more negative) if quiet/soft speech is being dropped. The VAD is optional — if
its model fails to download, prepare still succeeds and only layers 2–3 run.

All three engines implement `SublyAsr`, so switching is a one-line change in the builder. If you let users toggle engines at runtime, rebuild `Subly` with the other factory and create a new session.

### Translator notes (`MlKitTranslator`)

- First `prepare()` for a language pair downloads the ML Kit model (~30 MB per language). Pass download conditions to avoid metered networks:

```kotlin
MlKitTranslator(
    downloadConditions = DownloadConditions.Builder().requireWifi().build()
)
```

- ML Kit doesn't report download progress; the translator's `Preparing` phase is indeterminate (constant `0f`). The session's aggregated `EngineState.Preparing.progress` still moves thanks to the ASR side.

---

## Error handling

`EngineState.Error` carries a typed `SublyError` — branch on it instead of string-matching exceptions:

```kotlin
is EngineState.Error -> when (val e = state.error) {
    is SublyError.ModelPreparationFailed -> when (e.component) {
        SublyError.Component.ASR        -> showRetry("Speech model unavailable. Check your connection.")
        SublyError.Component.TRANSLATOR -> showRetry("Translation model download failed.")
        null                            -> showRetry("Setup failed.")
    }
    is SublyError.TranslationFailed -> showRetry("Translation stopped working.")
    is SublyError.PipelineFailed    -> showRetry("Captioning stopped unexpectedly.")
}
```

The original `Throwable` is always available via `e.cause` for logging/analytics. After any error, `prepare()`/`start()` retries.

---

## Permissions

The SDK does **not** request permissions; your app must:

1. **`MediaProjection`** — obtain a token via `MediaProjectionManager.createScreenCaptureIntent()` and pass the resulting projection to `session.start(...)`.
2. **`RECORD_AUDIO`** — required by `AudioPlaybackCapture`.
3. **Foreground service** — capture must run inside a foreground service with `android:foregroundServiceType="mediaProjection"`.

> **DRM-protected apps** (e.g. some streaming services) opt out of playback capture. The system delivers silence — the session keeps running but no captions appear. Detecting and messaging this is currently the app's responsibility.

---

## Implementing a custom engine

Implement `SublyAsr` or `SublyTranslator`. The contracts are documented on the interfaces and on `ModelPrepState`; the load-bearing rules:

1. **`prepareModel` must terminate with `Ready` or `Error`** — never complete silently, never throw. (The session defensively treats silent completion as an error, so violating this surfaces as `ModelPreparationFailed` rather than a false `Ready`.)
2. **Progress is normalized `0f..1f`.**
3. **`prepareModel` is re-collectable**: re-collection after `Error` retries; collection when already prepared emits `Ready` immediately.
4. **`transcribe`/`translate` before `Ready` fails loudly** with `IllegalStateException` — never silently drain input.
5. **Never log transcript content.** It's captured user speech. Log lengths and metadata only.
6. Engines are session-scoped: one instance per session, `release()` called exactly once on close, instance is dead afterwards.

---

## Threading

- All public session methods are safe to call from the main thread; heavy work runs on `Dispatchers.Default`/`IO` internally.
- `state` and `captions` can be collected from any dispatcher (use `flowWithLifecycle` / `repeatOnLifecycle` in UI).
- One caveat: `close()` synchronously releases native handles and may briefly block if an inference call is mid-flight. If you close sessions on the main thread frequently (e.g. rapid language switching), dispatch it: `withContext(Dispatchers.Default) { session.close() }`.

---

## Benchmarking (Whisper engine)

The whisper pipeline emits structured `key=value` benchmark lines under a single logcat tag, covering native inference, per-window pipeline latency, and segmentation:

```
adb logcat -s SublyBench
```

| Line | Source | Key fields |
|---|---|---|
| `model_init` | model load | `ms`, `model` |
| `warmup` | JNI, once per init | `ms` — one-off graph allocation absorbed during prepare |
| `whisper_full` | JNI, per inference | `audio_ms`, `infer_ms`, `rtf`, `audio_ctx`, `threads`, `encode_ms`, `decode_ms` |
| `asr_window` | transcriber, per window | `kind` (partial/final), `queue_ms`, `infer_ms`, `e2e_ms`, `rtf`, `skipped`, `text_len`, `raw_len` |
| `segment close` | amplitude segmenter | `segment_ms`, `close_wait_ms`, `partials`, `kept`, `reason` (silence/max) |
| `vad_pass` / `vad_drop` | JNI | Silero VAD gate result before decode (`max_prob`, `audio_ms`, `vad_ms`) |
| `no_speech_drop` | JNI | window discarded post-decode (`prob`, `avg_logprob`, `reason` (no_speech/low_conf), `audio_ms`) |
| `translate` | session, per MT call | `kind` (partial/final/flush), `ms`, `src_len`, `dst_len`, `ok` |
| `translate_skip` | session | partial re-translation throttled (`reason`) |
| `translate_warmup` | session, once per prepare | first ML Kit call absorbed during prepare (`ms`, `ok`) |
| `sentence_flush` | session | pooled remainder force-emitted after idle timeout (`len`) |
| `caption_dedupe` | session | consecutive identical final caption dropped (`len`) |

With VERBOSE enabled, `translate_text` lines carry the source/translated pair for MT quality evaluation alongside the ASR `transcript` lines.

`raw_len` > `text_len` means the repetition filter collapsed a hallucination loop — frequent large gaps indicate the model is looping (try a larger model or longer `MIN_PARTIAL_MS`).

How to read the speed numbers: `rtf` < 1.0 means faster than real time (lower is better; aim well under 0.5 for streaming headroom). `e2e_ms` on `asr_window` is the latency from end-of-captured-audio to result ready — the number the user feels. Rising `queue_ms` or nonzero `skipped` means inference isn't keeping up and stale partials are being dropped (by design). `close_wait_ms` on finals is the silence-hang floor — tune `SpeechSegmenter.SILENCE_HANG_MS` against it.

**Accuracy runs.** Transcript content is privacy-sensitive and is NOT logged by default. To benchmark accuracy (e.g. WER against a reference script), opt in on the device:

```
adb shell setprop log.tag.SublyBench VERBOSE   # enable transcript lines (until reboot)
adb shell setprop log.tag.SublyBench INFO      # disable again
```

While enabled, each window also logs `transcript kind=... text="..."` (including empty results — deletions count toward WER). Play a recording with a known reference transcript, collect the `kind=final` lines, and score them with any WER tool (e.g. `jiwer`). Compare `rtf`/`e2e_ms` and WER across models (`ggml-tiny-q8_0`, `ggml-base-q5_1`, ...) to pick the right speed/accuracy point per device tier. Never ship a build that enables transcript logging programmatically.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `IllegalArgumentException: An ASR engine is required` | `build()` without `setAsrEngine` | Provide both engine factories |
| `Error(ModelPreparationFailed(ASR))` on Whisper | First-run model download failed | Check connectivity; retry via `prepare()` |
| `Error(ModelPreparationFailed(TRANSLATOR))` | Unsupported language tag or download blocked by conditions | Validate tags against `TranslateLanguage`; relax `DownloadConditions` |
| State reaches `Running` but no captions | Source app is DRM-protected, or device volume routing excludes capture | Test with a non-DRM source (e.g. YouTube) |
| `IllegalStateException: ... not prepared` from an engine | `transcribe` collected before `Ready` — only possible when bypassing `SublySession` | Drive engines through a session, or await `Ready` yourself |
| Garbled/empty Whisper output + `SAMPLE RATE MISMATCH` in logcat | Capture not delivering 16 kHz audio | Ensure the capture path resamples to 16 kHz |
| Whisper captions hallucinate over music/noise | Non-speech leaking past the guards | Check `vad_drop`/`no_speech_drop` lines; raise `VAD_SPEECH_PROB` in `whisper-jni.cpp`. If no `vad_*` lines appear, the VAD model didn't download — captions fall back to layers 2–3 only |
| Real speech intermittently dropped | Guards too aggressive | Lower `VAD_SPEECH_PROB` or make `AVG_LOGPROB_THRESHOLD` more negative |
| Partial captions flicker | Overlay appends partials instead of replacing | Replace the displayed partial; append only `isFinal == true` |

---
