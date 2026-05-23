# Subly SDK native AI core

Builds `libwhisper_jni.so` for `arm64-v8a` (the only ABI we ship — `minSdk=34`
devices are universally 64-bit ARM).

## Stub vs. real builds

By default the JNI library compiles as a **stub**: `nativeIsAvailable()`
returns `false` and the Kotlin `WhisperTranscriber` falls back to a
pass-through that drains audio frames without emitting packets. This keeps CI
green and lets the SDK build without vendoring upstream sources.

The CMake flag `SUBLY_HAS_WHISPER` is auto-detected from `sdk/build.gradle.kts`
based on whether `cpp/whisper.cpp/` exists.

## One-shot bootstrap (real engine)

From the SDK repo root:

```bash
./gradlew :sdk:prepareWhisper
```

That task is idempotent and does two things:

1. `git clone --depth 1 --branch v1.7.4 https://github.com/ggerganov/whisper.cpp`
   into `sdk/src/main/cpp/whisper.cpp/` (gitignored).
2. Downloads `ggml-tiny-q5_1.bin` (~31 MB) from Hugging Face into
   `sdk/src/main/assets/models/` (gitignored).

After it finishes, any subsequent build (`assembleDebug`, `installDebug`,
etc.) automatically flips `SUBLY_HAS_WHISPER=ON` and links against the real
engine. Re-running `prepareWhisper` is a no-op once both outputs exist.

To bump the upstream pin or model, edit `whisperTag` / `whisperModelUrl` near
the top of [`sdk/build.gradle.kts`](../../../build.gradle.kts) and re-run the
task.

## Translation target caveat

Whisper's built-in `translate=true` flag translates **to English only**. For
non-English target languages, the Kotlin layer currently emits the
transcribed source-language text and tags `sourceLanguageCode` accordingly —
an external NMT step (NLLB / Marian) is required to reach arbitrary targets
and will be added in a later phase.
