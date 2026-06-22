#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <vector>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <algorithm>
#include <chrono>
#include <cmath>

#include "whisper.h"

#define TAG "SublyWhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Benchmark lines share one tag across JNI and Kotlin so a single logcat
// filter captures the whole pipeline:  adb logcat -s SublyBench
#define BENCH_TAG "SublyBench"
#define LOGBENCH(...) __android_log_print(ANDROID_LOG_INFO, BENCH_TAG, __VA_ARGS__)

namespace {

    struct WhisperHandle {
        whisper_context     * ctx = nullptr;
        whisper_vad_context * vad = nullptr; // null => VAD gate disabled
        std::string target_lang;     // empty => auto-detect, then pinned below
        std::string pinned_lang;     // language pinned after first detection
        std::string last_detected;
        std::mutex   detected_mu;
    };

    inline std::string jstring_to_std(JNIEnv * env, jstring s) {
        if (!s) return {};
        const char * c = env->GetStringUTFChars(s, nullptr);
        std::string out(c ? c : "");
        if (c) env->ReleaseStringUTFChars(s, c);
        return out;
    }

    // Counts "performance" cores by reading per-core max frequencies. On
    // big.LITTLE SoCs, sizing the ggml thread pool past the number of big
    // cores is counter-productive: every graph sync waits for the slowest
    // (little) core. Cores within 80% of the fastest core count as "big".
    int countPerfCores() {
        const int hw = (int) std::thread::hardware_concurrency();
        if (hw <= 0) return 0;

        std::vector<long> freqs;
        freqs.reserve(hw);
        for (int i = 0; i < hw; ++i) {
            char path[96];
            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
            FILE * f = fopen(path, "r");
            if (!f) continue;
            long khz = 0;
            if (fscanf(f, "%ld", &khz) == 1 && khz > 0) freqs.push_back(khz);
            fclose(f);
        }
        if (freqs.empty()) return 0;

        const long top = *std::max_element(freqs.begin(), freqs.end());
        int perf = 0;
        for (long f : freqs) {
            if (f * 10 >= top * 8) perf++;  // >= 80% of top freq
        }
        return perf;
    }

    int optimalThreadCount() {
        // Computed once; sysfs reads + heuristics are not free per-chunk.
        static const int cached = [] {
            const int hw = (int) std::thread::hardware_concurrency();
            const int perf = countPerfCores();
            int n;
            if (perf > 0) {
                n = std::min(perf, 6);
            } else if (hw <= 0) {
                n = 2;                       // unknown → safe default
            } else if (hw <= 4) {
                n = hw;                      // small device, use all
            } else {
                n = std::min(hw / 2, 6);     // big device: half cores, capped
            }
            return std::max(1, n);
        }();
        return cached;
    }

    // Whisper encodes 16 kHz audio at 50 frames/sec (100 mel frames/sec,
    // conv stride 2). By default whisper_full pads every chunk to 30 s and
    // runs the full 1500-frame encoder — for a 2 s utterance that is ~15x
    // wasted encode work. Shrinking audio_ctx to fit the actual audio (plus
    // headroom) is the single biggest latency win for short streaming
    // chunks (the `-ac` flag of whisper.cpp's stream example).
    inline int audioCtxFor(int n_samples) {
        const int frames = n_samples / 320;      // 16000 / 50
        return std::clamp(frames + 64, 192, 1500);
    }

    // ---- Non-speech rejection (music / noise hallucination guards) ----------
    //
    // Three layers, cheapest first:
    //   1. Silero VAD gate (below): a neural speech detector run *before*
    //      Whisper. The amplitude VAD upstream only knows "loud vs quiet" and
    //      cannot tell speech from music — this can. Windows with no speech
    //      frame above VAD_SPEECH_PROB are dropped without paying for a
    //      Whisper decode at all.
    //   2. no-speech probability: whisper's own per-segment estimate.
    //   3. average token log-prob: confidence of the decoded text. Music/noise
    //      hallucinations are typically low-confidence even when (1)/(2) let
    //      them through.
    // A window is dropped if the VAD finds no speech, OR whisper reports high
    // no-speech probability, OR the decoded text is very low confidence.

    // Max Silero per-frame speech probability below which a window is treated
    // as non-speech and dropped before transcription. 0.5 is Silero's default
    // speech threshold; raise toward 0.6-0.7 if music still leaks, lower if
    // quiet/soft speech is being dropped.
    constexpr float VAD_SPEECH_PROB = 0.50f;

    // Whisper's own per-segment no-speech probability above which we drop.
    constexpr float NO_SPEECH_THRESHOLD = 0.60f;

    // Average decoded-token log-probability below which a window is treated as
    // a hallucination and dropped. Confident speech is typically > -0.7; music
    // lyric/noise hallucinations commonly land in -1.2..-2.0. -1.0 is the
    // OpenAI Whisper default; make it more negative if real speech is dropped.
    constexpr float AVG_LOGPROB_THRESHOLD = -1.0f;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeIsAvailable(JNIEnv *, jobject) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeInit(
        JNIEnv * env, jobject /*thiz*/,
        jstring model_path_jstr,
        jstring target_language_jstr,
        jstring vad_model_path_jstr) {

    std::string model_path = jstring_to_std(env, model_path_jstr);
    std::string target     = jstring_to_std(env, target_language_jstr);
    std::string vad_path   = jstring_to_std(env, vad_model_path_jstr);

    if (model_path.empty()) {
        LOGE("nativeInit: empty model path");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context * ctx = whisper_init_from_file_with_params(model_path.c_str(), cparams);
    if (!ctx) {
        LOGE("nativeInit: whisper_init_from_file_with_params failed for %s", model_path.c_str());
        return 0;
    }

    // Warm-up: the first whisper_full of a session pays one-off ggml graph /
    // backend allocation costs (benchmarks showed ~3.8 s vs ~0.5 s steady
    // state). Run one inference over silence now, while the app is still
    // showing prepare progress, so the first real caption isn't late.
    {
        const auto t0 = std::chrono::steady_clock::now();
        // whisper_full rejects audio shorter than 1 s; use 1.1 s of silence.
        std::vector<float> silence((size_t) WHISPER_SAMPLE_RATE * 11 / 10, 0.0f);

        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.print_progress   = false;
        wp.print_special    = false;
        wp.print_realtime   = false;
        wp.print_timestamps = false;
        wp.no_context       = true;
        wp.single_segment   = true;
        wp.no_timestamps    = true;
        wp.n_threads        = optimalThreadCount();
        wp.max_tokens       = 16;
        wp.temperature_inc  = 0.0f;
        wp.suppress_blank   = true;
        wp.suppress_nst     = true;
        wp.audio_ctx        = audioCtxFor((int) silence.size());
        wp.language         = "en";   // fixed lang: don't pollute auto-detection
        wp.detect_language  = false;

        if (whisper_full(ctx, wp, silence.data(), (int) silence.size()) != 0) {
            LOGW("nativeInit: warm-up inference failed (non-fatal)");
        }
        const double warmup_ms =
                std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - t0).count();
        LOGBENCH("warmup ms=%.0f", warmup_ms);
    }

    // Optional Silero VAD gate. Non-fatal if it fails to load: we just fall
    // back to the no-speech / log-prob guards inside nativeTranscribe.
    whisper_vad_context * vad = nullptr;
    if (!vad_path.empty()) {
        whisper_vad_context_params vctx_params = whisper_vad_default_context_params();
        vctx_params.n_threads = optimalThreadCount();
        vctx_params.use_gpu   = false;
        vad = whisper_vad_init_from_file_with_params(vad_path.c_str(), vctx_params);
        if (!vad) {
            LOGW("nativeInit: VAD init failed for %s (gate disabled)", vad_path.c_str());
        }
    }

    auto * handle = new WhisperHandle();
    handle->ctx = ctx;
    handle->vad = vad;
    handle->target_lang = target;
    LOGI("nativeInit: ctx=%p vad=%p target='%s' threads=%d",
            ctx, vad, target.c_str(), optimalThreadCount());
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/,
        jlong handle_ptr,
        jfloatArray pcm_jarr,
        jint /*sample_rate*/,           // whisper.cpp always assumes 16 kHz internally
        jboolean gate_with_vad) {       // run the Silero VAD gate (finals only; see Kotlin)

    auto * handle = reinterpret_cast<WhisperHandle *>(handle_ptr);
    if (!handle || !handle->ctx || !pcm_jarr) {
        return env->NewStringUTF("");
    }

    const jsize n_samples = env->GetArrayLength(pcm_jarr);
    if (n_samples <= 0) {
        return env->NewStringUTF("");
    }

    std::vector<float> pcmf32(n_samples);
    env->GetFloatArrayRegion(pcm_jarr, 0, n_samples, pcmf32.data());

    const double audio_ms_in = (double) n_samples * 1000.0 / WHISPER_SAMPLE_RATE;

    // Layer 1 — Silero VAD gate. Run the neural speech detector first; if no
    // frame in this window looks like speech, drop it without paying for a
    // Whisper decode. This is what rejects background music / noise that the
    // upstream amplitude VAD (loud-vs-quiet only) can't distinguish.
    //
    // Only gated when gate_with_vad is set. Partial windows skip it: they grow
    // every ~800 ms and re-scanning the whole (up to 5 s) window each time adds
    // up to ~1 s of pure overhead on the hot path. Partials are disposable, so
    // any non-speech that slips through is caught post-decode by the no-speech
    // / log-prob guards below and never reaches a committed final anyway.
    if (handle->vad && gate_with_vad) {
        const auto t_vad = std::chrono::steady_clock::now();
        // detect_speech resets LSTM state; correct here since each window is
        // an independent (possibly growing) snapshot passed in full.
        const bool ok = whisper_vad_detect_speech(
                handle->vad, pcmf32.data(), (int) pcmf32.size());
        float max_prob = 0.0f;
        if (ok) {
            const int   n_probs = whisper_vad_n_probs(handle->vad);
            const float * probs = whisper_vad_probs(handle->vad);
            for (int i = 0; i < n_probs && probs; ++i) {
                if (probs[i] > max_prob) max_prob = probs[i];
            }
        }
        const double vad_ms = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - t_vad).count();
        if (!ok || max_prob < VAD_SPEECH_PROB) {
            LOGBENCH("vad_drop max_prob=%.2f audio_ms=%.0f vad_ms=%.1f",
                     max_prob, audio_ms_in, vad_ms);
            return env->NewStringUTF("");
        }
        LOGBENCH("vad_pass max_prob=%.2f audio_ms=%.0f vad_ms=%.1f",
                 max_prob, audio_ms_in, vad_ms);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.translate        = false;
    params.no_context       = true;
    params.n_threads        = optimalThreadCount();
    params.single_segment   = true;

    // Latency: don't decode timestamp tokens (unused downstream), bound the
    // decode length, and disable temperature fallback — a low-confidence
    // chunk can otherwise trigger up to 5 full re-decodes.
    //
    // max_tokens is proportional to audio duration (~16 tokens/sec of audio,
    // vs. real speech at ~2-6 tokens/sec). A fixed cap of 64 let the decoder
    // spin through ~10x more tokens than an 800 ms window could contain —
    // benchmarks showed repetition-loop hallucinations burning ~75% of total
    // inference CPU. With a duration-proportional budget the loop is cut off
    // almost immediately.
    const int max_tokens = std::clamp((int) (n_samples / 1000), 16, 64);
    params.no_timestamps    = true;
    params.max_tokens       = max_tokens;
    params.temperature_inc  = 0.0f;
    params.suppress_blank   = true;
    params.suppress_nst     = true;
    params.audio_ctx        = audioCtxFor((int) pcmf32.size());

    // Language: explicit target wins; otherwise auto-detect once, then pin
    // the detected language so we don't pay detection on every chunk.
    const std::string & lang = !handle->target_lang.empty() ? handle->target_lang
                                                            : handle->pinned_lang;
    params.language        = lang.empty() ? nullptr : lang.c_str();
    params.detect_language = false;  // never detect-only; decode in one pass

    whisper_reset_timings(handle->ctx);

    const auto t_start = std::chrono::steady_clock::now();

    if (whisper_full(handle->ctx, params, pcmf32.data(), (int) pcmf32.size()) != 0) {
        LOGW("nativeTranscribe: whisper_full failed");
        return env->NewStringUTF("");
    }

    const auto t_end = std::chrono::steady_clock::now();
    const double infer_ms =
            std::chrono::duration<double, std::milli>(t_end - t_start).count();
    const double audio_ms = (double) n_samples * 1000.0 / WHISPER_SAMPLE_RATE;

    {
        // Per-call speed line. rtf = inference time / audio duration;
        // rtf < 1.0 means faster than real time (lower is better).
        // encode/decode/... are whisper-internal averages per run.
        whisper_timings * t = whisper_get_timings(handle->ctx);
        if (t) {
            LOGBENCH("whisper_full audio_ms=%.0f infer_ms=%.1f rtf=%.3f "
                     "audio_ctx=%d threads=%d encode_ms=%.1f decode_ms=%.1f "
                     "sample_ms=%.1f batchd_ms=%.1f prompt_ms=%.1f",
                     audio_ms, infer_ms, infer_ms / audio_ms,
                     params.audio_ctx, params.n_threads,
                     t->encode_ms, t->decode_ms,
                     t->sample_ms, t->batchd_ms, t->prompt_ms);
            delete t;
        } else {
            LOGBENCH("whisper_full audio_ms=%.0f infer_ms=%.1f rtf=%.3f "
                     "audio_ctx=%d threads=%d",
                     audio_ms, infer_ms, infer_ms / audio_ms,
                     params.audio_ctx, params.n_threads);
        }
    }

    {
        const int lang_id = whisper_full_lang_id(handle->ctx);
        const char * lang_str = (lang_id >= 0) ? whisper_lang_str(lang_id) : "";
        std::lock_guard<std::mutex> lk(handle->detected_mu);
        handle->last_detected = lang_str ? lang_str : "";
        // Pin only on a reasonably long chunk (>= 2 s) — language detection
        // on very short partial windows is unreliable.
        if (handle->target_lang.empty() && handle->pinned_lang.empty()
                && !handle->last_detected.empty()
                && n_samples >= 2 * WHISPER_SAMPLE_RATE) {
            handle->pinned_lang = handle->last_detected;
            LOGI("nativeTranscribe: pinned auto-detected language '%s'",
                 handle->pinned_lang.c_str());
        }
    }

    // Layers 2 & 3 — post-decode hallucination guards. Even when the VAD lets
    // a window through (e.g. singing, speech-like noise), whisper's own
    // no-speech probability and the confidence of the decoded text catch most
    // of the remaining garbage. Drop on EITHER signal.
    {
        const int n_seg = whisper_full_n_segments(handle->ctx);
        if (n_seg > 0) {
            const float no_speech =
                    whisper_full_get_segment_no_speech_prob(handle->ctx, 0);

            // Mean token log-prob across all decoded segments. whisper exposes
            // per-token *linear* probability; average its log.
            double sum_logprob = 0.0;
            int    n_tokens    = 0;
            for (int s = 0; s < n_seg; ++s) {
                const int nt = whisper_full_n_tokens(handle->ctx, s);
                for (int t = 0; t < nt; ++t) {
                    const float p = whisper_full_get_token_p(handle->ctx, s, t);
                    sum_logprob += std::log((double) std::max(p, 1e-6f));
                    n_tokens++;
                }
            }
            const float avg_logprob =
                    n_tokens > 0 ? (float) (sum_logprob / n_tokens) : 0.0f;

            const bool drop_no_speech = no_speech > NO_SPEECH_THRESHOLD;
            const bool drop_low_conf  =
                    n_tokens > 0 && avg_logprob < AVG_LOGPROB_THRESHOLD;
            if (drop_no_speech || drop_low_conf) {
                LOGBENCH("no_speech_drop prob=%.2f avg_logprob=%.2f "
                         "reason=%s audio_ms=%.0f",
                         no_speech, avg_logprob,
                         drop_no_speech ? "no_speech" : "low_conf", audio_ms);
                return env->NewStringUTF("");
            }
        }
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(handle->ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char * seg = whisper_full_get_segment_text(handle->ctx, i);
        if (seg) result.append(seg);
    }

    // Never log transcript content — it is end-user speech.
    LOGI("nativeTranscribe: %d samples → %d segments → %zu chars (audio_ctx=%d)",
            n_samples, n_segments, result.size(), params.audio_ctx);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeLastDetectedLang(
        JNIEnv * env, jobject /*thiz*/, jlong handle_ptr) {
    auto * handle = reinterpret_cast<WhisperHandle *>(handle_ptr);
    if (!handle) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lk(handle->detected_mu);
    return env->NewStringUTF(handle->last_detected.c_str());
}

JNIEXPORT void JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle_ptr) {
    auto * handle = reinterpret_cast<WhisperHandle *>(handle_ptr);
    if (!handle) return;
    if (handle->vad) {
        whisper_vad_free(handle->vad);
        handle->vad = nullptr;
    }
    if (handle->ctx) {
        whisper_free(handle->ctx);
        handle->ctx = nullptr;
    }
    delete handle;
}

} // extern "C"