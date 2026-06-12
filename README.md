# Subly SDK

On-device, real-time speech captioning and translation for Android. Subly captures system audio (via `MediaProjection`), transcribes it locally with a pluggable ASR engine, translates the result on-device, and streams ready-to-render captions to your app — no audio ever leaves the device.

```
System audio ──▶ Capture ──▶ ASR engine ──▶ Sentence assembly ──▶ Translator ──▶ Caption stream
                              (Sherpa /                            (ML Kit)
                               Whisper)
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
- `Caption(isFinal = true)` — a completed sentence. Safe to append to history / persist. Sentences are finalized on punctuation or after 20 words, whichever comes first.

Note: both engines emit partials + finals. Sherpa partials arrive at ~200 ms cadence; Whisper partials are re-transcriptions of the utterance-in-progress emitted every ~800 ms of speech. Build your overlay to handle both shapes.

---

## Choosing an ASR engine

| | `SherpaOnnxTranscriber` | `WhisperTranscriber` |
|---|---|---|
| Style | Streaming | Chunked (VAD-segmented windows + in-progress partials) |
| Partial results | ✅ ~200 ms cadence | ✅ ~800 ms cadence (growing-window re-transcription) |
| Perceived latency | Lower | Low-moderate (first partial ≥ ~1.3 s into an utterance) |
| Accuracy | Good | Better, esp. noisy audio / accents |
| Model delivery | Provided by your app: bundle under `assets/sherpa-onnx/<model>/` or download into app storage | Downloaded on first prepare (~57 MB q5_1, Hugging Face) |
| Best for | Live conversation feel | Accuracy-first captioning |

Both implement `SublyAsr`, so switching is a one-line change in the builder. If you let users toggle engines at runtime, rebuild `Subly` with the other factory and create a new session.

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
| `segment close` | VAD segmenter | `segment_ms`, `close_wait_ms`, `partials`, `kept`, `reason` (silence/max) |
| `no_speech_drop` | JNI | window discarded as non-speech (`prob`, `audio_ms`) |
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
| Partial captions flicker | Overlay appends partials instead of replacing | Replace the displayed partial; append only `isFinal == true` |

---

## API at a glance

```kotlin
class Subly {
    fun createSession(config: LanguageConfig): SublySession
    class Builder {
        fun setAsrEngine(factory: () -> SublyAsr): Builder      // required
        fun setTranslator(factory: () -> SublyTranslator): Builder // required
        fun setAudioCapture(factory: () -> AudioCapture): Builder  // optional (tests)
        fun build(): Subly
    }
}

class SublySession : AutoCloseable {
    val state: StateFlow<EngineState>
    val captions: SharedFlow<Caption>
    fun prepare()                              // idempotent; retries after Error
    fun start(mediaProjection: MediaProjection)
    fun stop()                                 // keeps models warm
    override fun close()                       // terminal
}
```
