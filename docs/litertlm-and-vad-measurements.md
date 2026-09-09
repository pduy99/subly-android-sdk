# Measurements: the shared speech gate and the LiteRT-LM translator

Device: Samsung SM-G973F (Galaxy S10, Exynos 9820, 8 GB RAM, Android 12 / API
31, arm64-v8a) — the same device every other Subly benchmark on this repo was
run on. Dates: 2026-09-09.

Everything below is a number that came off the device, not an estimate. Where
a design decision rests on one of them, the code says so at the point it
matters.

---

## 1. The sherpa-onnx `Vad` bindings

`SpeechGate`'s whole design hinges on which of the two APIs the sherpa runtime
exposes is usable, and the AAR's signatures alone cannot answer that. A
throwaway instrumented probe against `silero_vad.onnx` (643,854 bytes, from the
sherpa-onnx `asr-models` release) and a 25 s FLEURS clip:

| Question | Answer |
|---|---|
| Does `compute()` carry Silero's LSTM state between calls? | **Yes.** The same window fed five times returned 0.016, 0.005, 0.004, 0.006, 0.029 — drifting, so state is carried. |
| Does `compute()` require exactly 512 samples? | **Yes**, and strictly: a 256-sample call fails with `Got: 256 Expected: 512`. |
| What happens after a bad call? | It **poisons the state**. Every subsequent call returned `NULL input supplied for input h` until `reset()`. |
| Speech vs silence separation | Digital silence: max **0.043**, mean 0.018. Speech: max **0.9998**, mean 0.639, 498/782 windows above 0.5. |
| Onset sharpness | Three consecutive windows at the start of speech: **0.04 → 0.15 → 0.93**. |
| Cost | 782 windows in **260 ms** for 25 s of audio — about **1% of real time**. |
| `acceptWaveform` + `isSpeechDetected()` | 537/782 windows flagged speech, 12 transitions — smoother, but it applies sherpa's own `minSpeechDuration` hysteresis. |

**What this decided.** The gate uses `compute()` and implements its own
hysteresis, rather than `acceptWaveform`/`isSpeechDetected()`. The latter
delays speech onset by `minSpeechDuration` (0.25 s by default); `compute()`
hands back the raw per-window probability, so the gate can open on the very
first window that crosses the threshold and clip nothing. The strict window
size and the state-poisoning behaviour are why `SherpaVadBackend` validates
length before every call and resets on any throw.

The 0.043-vs-0.9998 separation is why the default threshold stays at Silero's
own 0.5 rather than being lowered to catch the 0.15 transition window: there
is no accuracy to buy at the cost of a threshold sitting closer to the noise
floor, and the cost of missing that window is one 32 ms frame.

---

## 2. LiteRT-LM on this device

`LiteRtLmLatencyTest`, Qwen2.5-1.5B-Instruct int8 (`ekv4096`, 1,597,931,520
bytes), CPU backend, 4 threads, greedy decoding, `maxOutputToken = 256`.

| | First run | Model already on disk |
|---|---|---|
| `prepareModel` to `Ready` | **213,752 ms** | **681 ms** |

The 1.6 GB download is the whole of the first number (≈7.5 MB/s over the
device's WiFi). **Engine init on a cached model is 681 ms** — the checkpoint is
memory-mapped rather than read, so it is effectively free.

That single number is what made the accuracy benchmark runnable at all. Each
benchmark clip builds a fresh `SublySession`, and therefore a fresh engine; had
init cost tens of seconds, a 30-entry matrix would have spent 15 minutes doing
nothing but reloading the same file, and the honest answer would have been to
add a process-wide engine cache before measuring anything. At 681 ms it is
noise, and no such complication is justified.

Per-caption generation, on caption-length English → Vietnamese:

| Source | Words | ms |
|---|---|---|
| "The quick brown fox jumps over the lazy dog." | 9 | 4,886 |
| "Rainfall in the region has decreased by roughly a third over the past decade." | 14 | 4,315 |
| "He said the new policy would take effect at the start of next year, once parliament approves it." | 18 | 5,083 |
| **mean** | | **4,761** |

(A first-run pass measured 5,509 / 4,657 / 5,703, mean 5,289 — same shape.)

**What this decided.** ~4.8 s per caption is workable for finals and
unworkable for partials, which arrive on a ~200 ms cadence and are
re-translated as the clause grows. `SublySession` translates on the same
coroutine that collects ASR results, so a 4.8 s call there does not merely lag
the caption: it applies backpressure through the ASR flow all the way back to
audio capture. That is the entire reason for
`SublyTranslator.translatesPartials`, which `LiteRtLmTranslator` sets to
`false`.

Note also how flat the numbers are against input length — 9 words and 18 words
differ by 16%. Cost is dominated by decode, not prefill, which is why the
system instruction is kept short but not agonised over, and why
`maxOutputToken` is the latency guard that matters.

---

## 3. Accuracy benchmark

Four runs of `BenchmarkRunnerTest` over the 15-clip FLEURS set, `sherpa` and
`vosk` only (Whisper is untouched by either change and its 90 s quiescence
would have doubled every run). 30 scored entries each; raw `results.json`
files are under `reports/_raw/` — which is gitignored, so the tables here are
the durable record.

| Run | Gate | Translator | Purpose |
|---|---|---|---|
| A `16-16-41` | off | ML Kit | Baseline on `dev` @ c5c4487 |
| B `17-31-47` | on, v1 | LiteRT-LM | Both changes together |
| C `18-05-14` | on, v1 | ML Kit | Control: isolates the gate |
| D `18-34-13` | on, v2 | ML Kit | Control, after the gate was fixed |

Two runs of the control were needed because B and A differ in two variables at
once, and the second one leaks into the ASR side: the session's idle-flush
timers are wall-clock, and an LLM that blocks the collect coroutine for ~7 s
per final back-pressures capture and shifts them. A caption-count difference
between A and B is therefore not attributable to the gate. C holds the
translator fixed so it is.

### The gate regressed ASR, and the control caught it

A caption start follows an ASR endpoint, which follows a pause — precisely
where a gate that cannot see an onset coming has closed. Comparing the *first
eight characters* of each caption against the baseline:

| | Caption starts changed | Mean transcript similarity |
|---|---|---|
| C — gate v1 | **25 / 64** | 0.945 |
| D — gate v2 | **5 / 64** | 0.980 |

Gate v1 was eating the first word of a caption, systematically:

```
記事の温度が上がりすぎ…   ->   の温度が上がりすぎ…
一本間で沸騰する地域…     ->   間で吹っ飛んの地域…
国会从二零零五财年…      ->   不会从二零零财年…
Congress began running…  ->   Iris began running…
```

That is the failure a per-window gate cannot avoid: a verdict cannot be
applied to audio that has already been emitted, so the window carrying a
speech onset is muted before the model knows speech has started.

**The fix** was two changes to `SpeechGate`, both now the defaults:

- a **~128 ms lookahead** (4 windows). Audio is held back and a window is
  muted only when neither it *nor the audio shortly after it* is speech.
- **`closeAfterMs` 800 -> 2500**, longer than the trailing silence any
  `CaptionEndpointTuning` rule asks for, so an ordinary pause between two
  sentences no longer closes the gate at all.

After the fix (run D vs A): 9 of the 10 English rows are byte-identical to the
baseline transcript, caption counts match on 28 of 30 rows, and the 5 remaining
start differences are ordinary run-to-run ASR variation with no prefix loss —
two of them are the gate helping (`你也可以自行…` -> `你也可以咨询…`, the
correct word for "consult"; a filler `えっと` dropped).

The one remaining count change is `fleurs_ja_01` (5 -> 6), where a long run-on
caption splits at a genuine pause. Captions 0-3 are unchanged.

### LiteRT-LM vs ML Kit

Run B completed with **zero harness failures** across ~90 LLM translations —
no echo-guard trips, no repetition-guard trips, no timeouts.

Quality is genuinely better on meaning, and the wins are the kind a per-pair
NMT model structurally cannot get:

| Source | ML Kit | LiteRT-LM |
|---|---|---|
| "a figure for the **cuts**" | "các **vết cắt**" (incisions) | "các **cắt giảm**" (reductions) ✅ |
| "many **elements** on the periodic table" | "nhiều **yếu tố**" (factors) | "nhiều **nguyên tố**" (chemical elements) ✅ |
| "the **nineteen sixty seven** mid east war" | "mười chín mươi bảy chiến tranh giữa Đông" (garbled) | "chiến tranh Mideast **năm 1967**" ✅ |

But it has a failure mode ML Kit does not have at all — **code-switching**:

| | Captions with CJK text in a `vi`/`en` target |
|---|---|
| ML Kit | **0 / 97** (0.0%) |
| LiteRT-LM | **5 / 97** (5.2%) |

```
Tôi luôn là một混合物 của hai hoặc nhiều huy chương…
Côn trùng không thể quay lại điểm xuất phát, chỉ có蜻蛉 và ánh sáng…
The văn bản theo tuần sẽ đề cập đến tranh chấp biên giới…
```

The existing guards do not catch this: `isSourceEcho` looks for a *whole*
verbatim echo and `isDegenerate` for repetition loops, while this is a
correct-shaped sentence with a few tokens left in the wrong language. Two
candidate fixes, neither implemented because each needs its own measured run:

1. A **target-script guard** — reject output containing script the target
   language does not use. Cheap and mechanical, but it turns a mostly-correct
   caption into a failed one, which for an overlay may be the worse trade.
2. A **prompt nudge** — the system instruction already bans romanisation;
   extending it to "write entirely in {target}" may be enough. Untested, and
   an unverified prompt change is not worth shipping on a hunch.

### Cost

| | ML Kit | LiteRT-LM |
|---|---|---|
| Per clip x engine (wall clock) | ~50 s | ~65-120 s |
| Full 30-entry matrix | 18 min | 32 min |

The extra time is the ~4.8-7.3 s per final caption from §2. Partials cost
nothing extra — they never reach the LLM.
