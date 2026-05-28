#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <mutex>

#include "whisper.h"

#define TAG "SublyWhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/**
 * Per-handle state. We keep the configured target language plus the last
 * language whisper auto-detected, because Kotlin queries the detected
 * language out-of-band via [nativeLastDetectedLang].
 */
struct WhisperHandle {
    whisper_context * ctx = nullptr;
    std::string target_lang;     // BCP-47 from Kotlin (e.g. "en"), or "" for auto-detect.
    std::string last_detected;   // Set after every successful nativeTranscribe call.
    std::mutex   detected_mu;    // Guards last_detected.
};

inline std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

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
        jstring target_language_jstr) {

    std::string model_path = jstring_to_std(env, model_path_jstr);
    std::string target     = jstring_to_std(env, target_language_jstr);

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

    auto * handle = new WhisperHandle();
    handle->ctx = ctx;
    handle->target_lang = target;
    LOGI("nativeInit: ctx=%p target='%s'", ctx, target.c_str());
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/,
        jlong handle_ptr,
        jfloatArray pcm_jarr,
        jint /*sample_rate*/) {

    auto * handle = reinterpret_cast<WhisperHandle *>(handle_ptr);
    if (!handle || !handle->ctx || !pcm_jarr) {
        return env->NewStringUTF("");
    }

    const jsize n_samples = env->GetArrayLength(pcm_jarr);
    if (n_samples <= 0) {
        return env->NewStringUTF("");
    }

    // Whisper expects mono float32 PCM at 16 kHz. The Kotlin segmenter
    // already mono-mixes; resampling is the caller's responsibility too.
    std::vector<float> pcmf32(n_samples);
    env->GetFloatArrayRegion(pcm_jarr, 0, n_samples, pcmf32.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.n_threads        = 4;
    params.suppress_blank   = true;
    // Empty target -> let whisper auto-detect. Non-empty -> force that lang.
    params.language = handle->target_lang.empty() ? nullptr : handle->target_lang.c_str();
    params.detect_language = handle->target_lang.empty();

    if (whisper_full(handle->ctx, params, pcmf32.data(), (int) pcmf32.size()) != 0) {
        LOGW("nativeTranscribe: whisper_full failed");
        return env->NewStringUTF("");
    }

    // Snapshot detected language under the per-handle mutex so a concurrent
    // nativeLastDetectedLang() can read it safely.
    {
        const int lang_id = whisper_full_lang_id(handle->ctx);
        const char * lang_str = (lang_id >= 0) ? whisper_lang_str(lang_id) : "";
        std::lock_guard<std::mutex> lk(handle->detected_mu);
        handle->last_detected = lang_str ? lang_str : "";
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(handle->ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char * seg = whisper_full_get_segment_text(handle->ctx, i);
        if (seg) result.append(seg);
    }

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
    if (handle->ctx) {
        whisper_free(handle->ctx);
        handle->ctx = nullptr;
    }
    delete handle;
}

} // extern "C"
