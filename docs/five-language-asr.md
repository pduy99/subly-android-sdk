# One recogniser per language: English, Chinese, Japanese, Spanish, Vietnamese

The consumer app now captions five languages, as both source and target.
Each source language gets the most accurate offline recogniser we measured for it.
The app no longer has a Streaming/Batch setting: `SherpaAutoTranscriber` loads the chosen model for the source language, either streaming or offline on VAD segments.

## Choice

| Language | Recogniser | Mode | Download | Probe error (S10) | In-app error (S10) |
|---|---|---|---|---|---|
| en | Kroko Zipformer en 2025-08-06 | streaming | 71 MB | 7.9% WER | 5.1% WER |
| zh | Zipformer zh int8 2025-06-30 + CT-Transformer punctuation | streaming | 167 + 76 MB | 6.2% CER FLEURS, 14.5% dialogue | 13.9% CER dialogue |
| ja | ReazonSpeech k2 v2 (int8) | offline + Silero VAD | 160 MB | 21.3% CER | 10.4% CER |
| es | Kroko Zipformer es 2025-08-06 | streaming | 156 MB | 7.0% WER | 2.2% WER |
| vi | Zipformer vi 30M int8 2026-02-09 | offline + Silero VAD | 34 MB | 12.7% WER | 9.7% WER |

The offline modes also download Silero VAD (0.6 MB).
A first run downloads only the source language's model.

Decision rule:
1. Lowest error with RTF ≤ 0.5 on the Galaxy S10 (Exynos 9820, 8 GB).
2. Prefer a streaming model within about 2 points of the best offline one, because streaming gives live partials.
3. Among near-equals, prefer the smaller download.

"In-app" means the release app on the S10, with the clip played in a video player and captured through MediaProjection.
Error is scored on the source text of the final captions.
It is one clip per language (`fleurs_*_01`, `zh_dialogue_01`), while the probe averages two FLEURS clips.
Read the two columns as a sanity check, not as a comparison.

## Probe results

From `AsrProbeTest` (benchmark module) on the Galaxy S10 with 4 threads:
- **Clips:** two FLEURS clips per language, plus the real-world Chinese dialogue.
- **Error:** CER for zh/ja, WER for en/es/vi, with punctuation and case ignored.
- **RTF:** the worst clip.

| Lang | Candidate | Error | RTF | Download |
|---|---|---|---|---|
| en | Parakeet unified 0.6B streaming | 4.8% | **12.7** | 663 MB |
| en | Parakeet TDT 0.6B v2 (offline) | 5.4% | 0.12 | 661 MB |
| en | Parakeet TDT 0.6B v3 (offline) | 6.8% | 0.12 | 670 MB |
| en | **Kroko en streaming** | 7.9% | 0.06 | 71 MB |
| en | Qwen3-ASR 0.6B | 11.8% | 0.72 | 987 MB |
| en | zipformer-en-2023-06-26 streaming (previous) | 13.7% | 0.25 | 73 MB |
| en | Vosk small (previous) | 20.7% | 0.17 | - |
| en | Moonshine base en | 25.1% | 0.12 | 141 MB |
| en | SenseVoice | 48.0% | 0.05 | 237 MB |
| zh | Qwen3-ASR 0.6B | 0.0% / 13.3% dialogue | 0.83 | 987 MB |
| zh | **Zipformer zh streaming** | 6.2% / 14.5% dialogue | 0.26 | 167 MB |
| zh | Moonshine base zh | 6.8% / 92.1% dialogue | 0.17 | 141 MB |
| zh | SenseVoice | 7.5% / 12.7% dialogue | 0.07 | 237 MB |
| zh | Vosk small (previous) | 12.2% / 61.2% dialogue | 0.65 | - |
| ja | Qwen3-ASR 0.6B | 17.2% | 0.62 | 987 MB |
| ja | **ReazonSpeech k2 v2** | 21.3% | 0.09 | 160 MB |
| ja | Moonshine base ja | 22.6% | 0.18 | 141 MB |
| ja | Vosk small (previous) | 29.5% | 0.57 | - |
| ja | SenseVoice | 76.2% | 0.05 | 237 MB |
| es | Moonshine base es | 4.7% | 0.50 | 65 MB |
| es | **Kroko es streaming** | 7.0% | 0.13 | 156 MB |
| es | Parakeet TDT 0.6B v3 | 7.6% | 0.12 | 670 MB |
| es | Qwen3-ASR 0.6B | 8.1% | 0.75 | 987 MB |
| es | Vosk small (previous) | 13.9% | 0.10 | - |
| vi | zipformer-vi-2025-04-20 | 12.2% | 0.07 | 77 MB |
| vi | **zipformer-vi-30M** | 12.7% | 0.06 | 34 MB |
| vi | Qwen3-ASR 0.6B | 18.4% | 0.85 | 987 MB |
| vi | Moonshine base vi | 23.2% | 0.19 | 141 MB |
| vi | Vosk small (previous) | 47.1% | 0.75 | - |

Notes:
- **Qwen3-ASR** is the most accurate model on several languages, and its 0.0% on Chinese FLEURS suggests it saw FLEURS in training.
  On the S10 it runs at 0.6–0.85x real time, too slow for live partials, and it is a 987 MB download.
  A 12 GB phone could run it.
  Revisit it once the app can choose a recogniser by device class, as it already does for translators.
- **Parakeet streaming** decodes at 12x real time on the S10, which makes it unusable there.
- **SenseVoice** in English and Japanese drops consonants and whole words on these clips.
  Its Chinese stays good.

## Segmentation of the offline models

An offline model's VAD segment becomes one caption, so where segments end matters as much as accuracy.

- **Vietnamese.** The zipformer emits no punctuation.
  With SenseVoice's 0.3 s pause, every breath ended a caption ("Mà nói rằng." / "Về cơ bản."), and each piece was translated on its own: 12.6% WER with fragmentary translations.
  With the streaming models' 0.8 s pause (`CaptionEndpointTuning.SPEECH_PAUSE`) and a 12 s soft cap, captions became whole sentences: 9.7% WER, with translations that read as sentences.
- **Japanese.** ReazonSpeech with the 0.8 s pause produced a 9.4 s segment that decoded as one word ("これ"), losing two sentences.
  It is reliable on short segments, so it keeps a 0.3 s pause and an 8 s cap: 10.4% CER.
- **Joining capped segments did not work.** Marking a segment cut at the cap as `endsSentence = false` made things worse.
  Sherpa's VAD cap is soft (segments ran to 9.4 s against an 8 s cap), and the joined text came out split in odd places.

These per-model settings live in `SherpaOnnxModel.vadMinSilenceSec` and `maxSegmentSec`.

## Other fixes found in the app run

- **Stray "." captions from Kroko.** The Kroko models put the full stop that ends one utterance at the start of the next (". Alloys are…"), so sentence assembly captioned a lone ".".
  `SherpaOnnxTranscriber` now strips leading closing punctuation and keeps opening marks such as ¿ and ¡.
- **Benchmark logging.** `OfflineVadTranscriber` logs `offline_segment ms=… decode_ms=…` to `SublyBench`, and the segment text only under VERBOSE.

## App size

The release APK went from 110.8 MB to 95.4 MB after the app stopped shipping Vosk and whisper.cpp.
Both modules stay in the SDK for other consumers.

## Reproduce

```
./gradlew :benchmark:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.helios.subly.benchmark.latency.AsrProbeTest \
  -Pandroid.testInstrumentationRunnerArguments.probeLanguages=ja,vi \
  -Pandroid.testInstrumentationRunnerArguments.probeAsr=reazonspeech-k2-v2,zipformer-vi-30M
```

Results go to logcat (`adb logcat -s AsrProbe`) and to `asr_probe.json`.
