# Near real-time translation: measurements and changes

Goal: translated captions that keep up with speech on a phone, fully offline.
All numbers are from the release consumer app on a Galaxy S24 Ultra (SM8650, 12 GB).
Clips were played in a video player and captured through MediaProjection, the way a user runs it.

## How it is measured

The session now times every caption against the voice it came from (`VoiceLatencyTracker`).
It logs one `sentence_latency` line per final caption to `SublyBench`, plus a `latency_summary` at the end of a run.
`benchmark/tools/voice_latency_report.py` turns a logcat dump into a table.

| Column | From | To |
|---|---|---|
| text | speech starts | first source words on screen |
| trans | speech starts | first translated words on screen |
| asr | speech stops | sentence committed |
| xlate | sentence committed | final translation published |
| e2e | speech stops | final translation published |

Voice is detected by frame RMS.
On clean read speech (FLEURS) that finds sentence starts and ends well.
On the real-world dialogue, background music keeps it "voiced", so there the start of a sentence reads as the moment the previous one was committed.

```
adb shell setprop log.tag.SublyBench VERBOSE
adb logcat -c
# play something through the overlay
adb logcat -d -s SublyBench | benchmark/tools/voice_latency_report.py
```

## Where the time went (before)

On `zh_dialogue_01` (fast Mandarin dialogue, to Vietnamese) with Qwen3-4B on the GPU:

| ms | p50 | p90 |
|---|---|---|
| speech starts -> first translation | 11,531 | 16,563 |
| commit -> translation | 5,979 | 7,327 |
| speech stops -> translation | 6,086 | 7,533 |

Three causes, in order of size:
1. **The translator was slow.** Qwen3-4B takes 2.2 s to its first token and 5.3 s per sentence on this GPU. Fast dialogue produced lines faster than that, so they queued up and were merged into 50-character generations.
2. **Sentences were committed late.** Nobody in the dialogue pauses for 0.8 s, so the recogniser only finalised at its 10 s run-on cap. A line spoken at second 10 was committed at second 19.
3. **Nothing was translated while a sentence was spoken.** A long read sentence (8-9 s) showed no translation until it ended.

## Changes

### 1. Translator: Gemma 4 E2B instead of Qwen3-4B

Gemma 4 is Apache-2.0, unlike Gemma 3, so it can ship in the SDK.
It runs on the LiteRT-LM 0.16.1 runtime we already use.
`TranslatorModelProbeTest` (`probeSamples=flores`) translates 15 FLEURS sentences in 8 directions.
The FLEURS sentences share ids across languages, so every direction has a human reference (`dataset/parallel/flores_parallel.json`).
`benchmark/tools/translator_probe_report.py` scores them with chrF.

| Model | zh-vi | en-vi | ja-vi | es-vi | vi-en | zh-en | ja-en | en-zh | chrF mean | first token p50 | sentence p50 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Qwen3-4B (before) | 47.3 | 51.0 | 41.0 | 43.2 | 59.1 | 54.9 | 57.9 | 33.7 | 48.5 | 2,210 ms | 5,271 ms |
| **Gemma 4 E2B** | 47.1 | 56.1 | 45.7 | 46.4 | 59.7 | 55.3 | 60.2 | 29.3 | **50.0** | **380 ms** | **1,641 ms** |
| Hy-MT2-1.8B (int8) | 48.2 | 57.2 | 45.3 | 47.6 | 58.1 | 53.1 | 54.5 | 33.5 | 49.7 | 1,089 ms | 3,802 ms |

Gemma 4 E2B is as good as Qwen3-4B (better in 6 of 8 directions, worse into Chinese) and 3.2 times faster per sentence.
Hy-MT2, Tencent's dedicated translation model, is as accurate but slower on this GPU, because its int8 weights are 1.8 GB against Gemma's mixed 2/4/8-bit.
It stays in the catalog (`LiteRtLmModel.HY_MT2_1_8B`, prompt style `HY_MT`) for re-measurement on faster phones.

Known slip: Gemma translated 千九百六十七年 (1967, in kanji numerals) as "1916".

### 2. Early commit: sentences from partials

`EarlyCommitter` commits a sentence as soon as the partial hypothesis (punctuated by the recogniser or the Chinese punctuator) contains it, with speech after it.
The sentence must stay unchanged for three partials, about 0.4 s.
It then needs at least 3 letters of following speech, or 4 characters in Chinese and Japanese.
The recogniser's final later skips what was committed.
If the recogniser revised that text in between, close matches are dropped and the rest is kept, so a sentence is never lost.

Tightening from 2 to 3 stable partials, and from 2 to 4 following characters for Chinese, brought the dialogue's error rate back from 16.4% to 14.5%.
With the looser rule, early commits had caught partial-only artefacts ("硬硬通货").

### 3. Live preview: translating the sentence being spoken

When the translator has no committed work, `StreamingTranslationStage` translates the sentence still being spoken as a whole and shows that as the live translation (`Subly.Builder.setLivePreview`, on by default).
A preview never delays committed work: it is cancelled the moment a committed sentence needs the translator.
It is redone when the sentence has grown by about a word (4 letters).

Translation drafts (`setTranslationDrafts`) were measured again with Gemma.
They still make things worse: speech stops -> caption p50 rose to 2.9 s from 2.4 s, because draft generations queue in front of finals and stop finals from being merged.
They stay off.

### 4. Other fixes found on the way

- **Kroko leaves out full stops.** Kroko punctuates its own output, so a final without a terminator was cut by a pause, not ended ("…one of the guys, or" / "gals.").
  Such finals are now `endsSentence = false` (`SherpaOnnxModel.punctuates`).
  When the next final starts with a capital, the pooled text is closed as a sentence (`SublyAsr.capitalisesOnlySentenceStarts`); whisper is excluded, since it capitalises every window.
- **ML Kit model downloads can hang forever.** On the S24, downloads for new pairs (ja, es) showed no progress and no error for over 20 minutes; one finished after about 25.
  `HandoverTranslator` now prepares both engines at once and is ready with whichever is ready first. Spanish -> Vietnamese was ready in 8 s on Gemma while ML Kit's download was stuck.
  `MlKitTranslator` gives up after 5 minutes with an error the UI can retry.
- **ReazonSpeech segment cap 8 s -> 6 s**: 17.3% -> 14.7% character error over five FLEURS clips (`AsrProbeTest` with `probeVadMax`).

## Result

Release app, Galaxy S24 Ultra, Gemma 4 E2B, early commit and live preview on:

| Clip | Pair | speech starts -> first translation, p50 / p90 | speech stops -> caption, p50 / p90 | recognition error |
|---|---|---|---|---|
| zh_dialogue_01 | zh -> vi | 2,170 / 4,104 ms (was 11,531 / 16,563) | 1,548 / 2,722 ms (was 6,086 / 7,533) | 14.5% CER |
| fleurs_en_02 | en -> vi | 3,021 ms, 3 sentences (9,985 before live preview) | 2,565 / 2,624 ms | |
| fleurs_es_01 | es -> vi | 1,326 / 1,446 ms | 2,770 / 3,626 ms | 2.2% WER |
| fleurs_ja_02 | ja -> vi | 3,517 / 5,868 ms | 1,995 / 2,682 ms | 11.3% CER |
| fleurs_vi_01 | vi -> en | 3,821 / 4,021 ms | 2,122 / 2,155 ms | 9.7% WER |

Japanese and Vietnamese use offline recognisers, so their source text appears about 2.5 s into a sentence, against about 0.5 s for the streaming ones.
That is the next gap to close on those two languages.

## Not yet measured

- Gemma 4 E2B on 8 GB phones. Google's card gives about 0.7 GB of GPU memory, so it may replace ML Kit on the Galaxy S10, whose ML Kit downloads share the hang above. The S10 was not connected for this work.

## LiteRT-LM 0.18 and multi-token prediction

The SDK moved to LiteRT-LM 0.18.0 for Gemma 4's multi-token prediction (speculative decoding with the checkpoint's drafter).
`TranslatorModelProbeTest`, FLEURS, 15 sentences x 8 directions, Galaxy S24 Ultra.
The runs alternated on/off/on/off, because the phone heated from thermal status 1 to 2 over the runs.

| Gemma 4 E2B | first token p50 | sentence p50 | decode (output chars/s) | chrF |
|---|---|---|---|---|
| MTP off | 519 ms | 1,987 ms | 65.6 | 49.6 |
| MTP on | 566 ms | 1,615 ms | 82.6 | 49.4 |

MTP is 19% faster per sentence and decodes 26% faster, at the same quality (the chrF difference is inside run-to-run noise).
That is well short of Google's "up to 2.2x", which was measured on a newer phone.
It is on by default (`LiteRtLmModel.speculativeDecoding`).

0.16.1 and 0.18.0 without MTP measured the same, back to back on the same phone: 2,148 against 2,181 ms per sentence.
The GPU is not bit-exact between runs either: two runs with identical settings gave identical output for only 39 of 120 sentences.

To repeat the A/B:

```
./gradlew :benchmark:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.helios.subly.benchmark.latency.TranslatorModelProbeTest \
  -Pandroid.testInstrumentationRunnerArguments.probeSamples=flores \
  -Pandroid.testInstrumentationRunnerArguments.probeLimit=15 \
  -Pandroid.testInstrumentationRunnerArguments.probeModels=GEMMA4_E2B_IT \
  -Pandroid.testInstrumentationRunnerArguments.probeSpeculative=false   # then =true
benchmark/tools/translator_probe_report.py
```
