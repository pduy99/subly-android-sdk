# Intent: SDK Accuracy Benchmark

Confirmed via interview on 2026-08-19.

- **Outcome:** An accuracy benchmark for the Subly SDK: an instrumented test feeds mp4 audio (EN/ZH/JA) through the full pipeline minus capture — via a new file-based audio entry point — across every ASR engine with a model for that language, then a host-side stage sends generated vs. verified transcription/translation pairs to the Claude API, which judges accuracy *and* readability (punctuation, segmentation) and writes a human-readable side-by-side report.
- **User:** Duy, as the SDK's developer — run it after each meaningful SDK change to see whether the change helped or hurt.
- **Why now:** The pipeline is under active change (sentence assembly, engine config) with no objective way to tell if output quality moved.
- **Success:** After an SDK change, one device run + one judge run produces a report trusted enough to keep or revert the change.
- **Constraint:** Runs on a physical arm64 device today; designed so it can later run on Gradle Managed Devices in GitHub Actions (which will eventually need x86_64 native libs — accepted as future work).
- **In scope:** Building the benchmark dataset — mp4 clips per language plus verified punctuated transcriptions and VI/EN translations — under a harness-defined folder/manifest convention.
- **Out of scope:** Speed/latency metrics (informational at most), deterministic WER/CER scoring (LLM is the sole judge), automated pass/fail baselines or CI gating, and host-JVM execution of the SDK.

## Key design decisions from the interview

- Full pipeline minus MediaProjection capture; a file-based audio entry point (test seam) will be built.
- Engine coverage: side-by-side matrix of all engines (Sherpa / Whisper / Vosk) that have models for each test language.
- Two-stage architecture: (1) on-device instrumented test runs the SDK over the mp4s and dumps generated transcriptions/translations as JSON pulled off the device; (2) host-side Gradle task/script sends generated-vs-verified pairs to the Claude API for judging and report generation. Keeps API keys off-device and allows re-judging without re-running audio processing.
- References are punctuated deliberately so the LLM can judge readability (punctuation, casing, sentence segmentation), not just word accuracy.
- Translation targets: Vietnamese and English.
