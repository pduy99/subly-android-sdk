# Subly Android SDK

On-device, real-time speech captioning and translation for Android.
Audio is captured, transcribed, and translated entirely on the phone; nothing leaves the device.
The main consumer is the sibling repo `../Subly-App-Consumer`, which includes this repo as a Gradle composite build (`includeBuild("../Subly-Android-SDK")`).
SDK changes are therefore visible to the app without publishing, and most features span both repos.
Both repos use the same branch names for one feature and open PRs into `dev`.

## Pipeline

```
Capture (MediaProjection) -> Speech gate (Silero VAD) -> ASR -> Sentence assembly -> Translator -> Caption flow
```

- `sdk/` - `Subly.Builder` (factories for ASR, translator and capture, plus flags such as `setPunctuationRestoration` and `setTranslationDrafts`) and `SublySession`, which owns the pipeline and its `EngineState` (Idle, Preparing, Ready, Running, Error, Closed).
  - `SublySession.setLanguages` switches language pair in place: it re-prepares the same engines without releasing them.
  - Sentence assembly is in `SentenceExtractor`, `PunctuationRestorer`, `CjkSentenceSplitter` and `CjkSpacing`.
  - `AsrResult.Final.endsSentence = false` tells assembly that a final was cut mid-sentence, so it pools it instead of terminating it.
  - `streaming/` (`StreamingTranslationStage`, `WaitKBuffer`) translates committed clauses incrementally for LLM translators that implement `IncrementalTranslator`.
  - `translation/HandoverTranslator` serves a fast translator (ML Kit) while a slow primary (the LLM) loads, then hands over.
- `core/model` - shared types (`LanguageConfig`, `AsrResult`, `ModelPrepState`, `EngineState`, `AccelerationReport`).
- `core/downloader` - `ModelDownloader` / `OkHttpModelDownloader`; every engine takes one, so apps can configure model fetching.
- `audio/` - capture APIs and implementations.
- `asr/api` - the `SublyAsr` interface. `prepareModel` must always end in `Ready` or `Error`, never complete silently. `emitsCompleteUtterances` says whether each final is one utterance.
- `asr/vad` - `SpeechGate` (Silero) mutes non-speech before streaming recognisers.
- `asr/sherpa` - sherpa-onnx engines (the recommended path, see below).
- `asr/whisper` (whisper.cpp with an OpenCL GPU path) and `asr/vosk` - general multilingual engines. The consumer app no longer uses them; keep them working for other consumers.
- `translator/mlkit` - per-pair NMT, about 30 MB per pair, about 100 ms per line.
- `translator/litertlm` - on-device LLM translation with LiteRT-LM (`LiteRtLmModel`, `LiteRtLmTranslator`).
- `benchmark/` - device benchmarks and probes (androidTest only, never shipped), plus `benchmark/judge` (host JVM, Claude API judge for accuracy reports, see `SPEC.md`).

## Speech recognition: one model per language

The supported source languages are en, zh, ja, es and vi, each with a model chosen by on-device measurement.
Full numbers and reasoning are in `docs/five-language-asr.md`.

| Language | Model (`SherpaOnnxModel`) | Mode |
|---|---|---|
| en | `STREAMING_KROKO_EN` | streaming |
| zh | `STREAMING_ZIPFORMER_ZH` + `PUNCT_ZH_EN` (`ChinesePunctuator`) | streaming |
| ja | `REAZONSPEECH_JA` | offline on VAD segments |
| es | `STREAMING_KROKO_ES` | streaming |
| vi | `ZIPFORMER_VI` (30M) | offline on VAD segments |

- `SherpaAutoTranscriber` is the entry point. It routes a source language to `SherpaOnnxModel.bestFor(lang)` and keeps one model loaded: switching between a streaming and an offline language releases the other engine.
- `SherpaOnnxTranscriber` runs streaming transducers with the endpoint rules in `CaptionEndpointTuning` (0.8 s pause ends a caption; a 10 s run-on cap still waits for a 0.15 s gap).
- `OfflineVadTranscriber` runs offline models on Silero VAD segments and re-decodes the open segment about once a second for live partials.
  - Segmentation is per model (`vadMinSilenceSec`, `maxSegmentSec`), because each segment becomes one caption.
  - Vietnamese needs the 0.8 s pause, or every breath becomes a caption.
  - ReazonSpeech needs 0.3 s / 8 s, because it collapses on long segments (a 9.4 s segment decoded as one word).
  - The VAD cap is soft: segments can run past `maxSegmentSec`.
- `SenseVoiceTranscriber` is now a thin wrapper that pins `OfflineVadTranscriber` to SenseVoice.
- `CasingNormalizer` turns all-caps output into sentence case and leaves cased text alone.
- Kroko models emit the full stop of one utterance at the start of the next; `SherpaOnnxTranscriber` strips leading closing punctuation (keeping ¿ ¡).
- Model downloads are pinned to Hugging Face revisions; files fetched from third-party mirrors also carry a `sha256` in the catalog.
- The sherpa-onnx runtime (`asr/sherpa/libs/sherpa-onnx-1.13.2.aar`) is `compileOnly` here. An AAR cannot bundle another AAR, so the app must ship it. 1.13.2 already supports Moonshine and Qwen3-ASR configs.

## Translation

- `LiteRtLmModel.canRunLargeModel(context)` is true at 10.5 GB total RAM or more. Then the app uses Gemma 4 E2B (`GEMMA4_E2B_IT`, Apache-2.0), with ML Kit until it is loaded (`HandoverTranslator`); otherwise ML Kit alone.
- Gemma 4 E2B replaced Qwen3-4B after measurement: equal or better chrF on FLEURS in 6 of 8 directions, 0.38 s instead of 2.2 s to the first token, and 1.6 s instead of 5.3 s per sentence on the S24 GPU. See `docs/realtime-translation.md`.
- Hy-MT2-1.8B (Tencent's translation model) is in the catalog with its own prompt style (`PromptStyle.HY_MT`: no system prompt, one message per conversation). It is as accurate as Gemma but slower on the S24.
- `HandoverTranslator` prepares both engines at once and is ready with whichever is ready first. ML Kit model downloads have been seen to hang with no error for over 20 minutes, and `MlKitTranslator` now gives up after 5.
- Translator context (previous lines in the prompt) made small models copy lines, so it is off by default.
- Translation drafts (clause by clause) are off by default; they made end-of-sentence latency worse with Qwen3-4B and again with Gemma.
- LiteRT-LM 0.18.0, built with Kotlin 2.4. AGP 9's built-in Kotlin compiles with the Kotlin it bundles (2.2) unless the Kotlin Gradle plugin is on the classpath, so the root `build.gradle.kts` puts `kotlin-gradle-plugin` (the catalog's `kotlin`) there. The app needs Hilt 2.60+ to read Kotlin 2.4 metadata.
- Gemma 4 decodes with its multi-token-prediction drafter (`LiteRtLmModel.speculativeDecoding`, set through LiteRT-LM's process-global `ExperimentalFlags` before engine init). On the S24 it is 19% faster per sentence at the same quality (`docs/realtime-translation.md`); `TranslatorModelProbeTest` takes `probeSpeculative=true|false` for the A/B.

## Real-time pipeline

- `EarlyCommitter` commits a sentence from the partial hypothesis once it is complete, followed by speech, and unchanged for three partials. Fast dialogue rarely pauses, so waiting for the recogniser's final delayed lines by up to 10 s.
- `StreamingTranslationStage` with `livePreview` (default on) translates the sentence being spoken whenever the translator is idle, and cancels the preview the moment committed work arrives.
- `VoiceLatencyTracker` logs `sentence_latency` per caption to `SublyBench`: speech start -> first text / first translation, speech end -> commit -> caption. Read it with `benchmark/tools/voice_latency_report.py`.
- `SherpaOnnxModel.punctuates` (the Kroko models): a final without a terminator was cut by a pause, so it is joined with the next one, unless the next starts with a capital (`SublyAsr.capitalisesOnlySentenceStarts`).

## Build and test

```
./gradlew :asr:sherpa:testDebugUnitTest :sdk:testDebugUnitTest
./gradlew :benchmark:compileDebugAndroidTestKotlin
./gradlew :benchmark:validateDataset
```

Toolchain: AGP 9.2, Kotlin 2.4, minSdk 31, arm64-v8a only.

## On-device benchmarks

All device work runs on physical phones, not emulators: emulator CPU and GPU timings do not represent phones.
Test phones are a Galaxy S10 (`RF8M319JKTN`, Exynos 9820, 8 GB, the low-end baseline) and a Galaxy S24 Ultra (`R5CX13SLC7Z`, SM8650, 12 GB).
When more than one device is connected, pass `ANDROID_SERIAL`.

- Always pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Otherwise Gradle uninstalls the test APK (`com.helios.subly.benchmark.test`) and deletes gigabytes of downloaded models.
- Transcripts are logged only with `adb shell setprop log.tag.SublyBench VERBOSE`. Never log transcript content at other levels: it is end-user speech.
- Pick tests with `-Pandroid.testInstrumentationRunnerArguments.class=...` and pass test arguments the same way.
- `latency/AsrProbeTest` compares recognisers: CER for zh/ja, WER for en/es/vi, plus RTF, init time and download size. Narrow a run with `probeLanguages` (for example `ja,vi`) and `probeAsr` (candidate names); `probeClips` (up to 5), `probeVadSilence` and `probeVadMax` tune offline segmentation. Output goes to logcat tag `AsrProbe` and to `asr_probe.json`.
- `latency/StreamingLatencyBenchmarkTest` runs the full pipeline. Arguments: `latencyAsr` (`vosk|whisper|sherpa|sensevoice|auto`), `latencyClips`, `latencyTarget`, `latencyTranslator` (`mlkit` or a `LiteRtLmModel` name). `benchmark/tools/latency_report.py` prints the newest `latency.json`.
- `latency/TranslatorModelProbeTest` compares translators (`probeModels`, including `MLKIT` and `MLKIT_PIVOT_EN`). `probeSamples=flores` uses FLEURS sentences with references in every direction (`probeDirections`, `probeLimit`); score with `benchmark/tools/translator_probe_report.py` (chrF and speed). In the test app, ML Kit downloads can hang; leave `MLKIT` out unless its models are already there.
- The dataset is under `benchmark/src/androidTest/assets/dataset/` (FLEURS clips for en, zh, ja, es, vi with references, listed in `manifest.json`). `realworld/zh_dialogue_01` is fast real-world Mandarin dialogue.
- After about an hour of continuous GPU runs the S24 throttles (thermal status 3), so compare only runs done back to back.

## Conventions

- Decisions about models and defaults are made by measurement on device, and the numbers go into the catalog KDoc and `docs/`. Do not change a default without new measurements.
- Comments explain why, often with the measurement that justified the code. Keep that style.
- Never use the em dash in prose, comments or commit messages; use a plain dash.
- Do not add an AI co-author line to commits.
- Shell is zsh: quote globs such as `--include=*.kt`, and use arrays when a variable must split into words.
