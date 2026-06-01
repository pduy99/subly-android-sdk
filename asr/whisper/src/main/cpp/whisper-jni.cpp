#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <vector>
#include <cstring>
#include <mutex>
#include <algorithm>

#include "whisper.h"

#define TAG "SublyWhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

    struct WhisperHandle {
        whisper_context * ctx = nullptr;
        std::string target_lang;
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

    inline int optimalThreadCount() {
        const int hw = (int) std::thread::hardware_concurrency();
        if (hw <= 0) return 2;           // unknown → safe default
        if (hw <= 4) return hw;          // small device, use all
        return std::min(hw / 2, 6);      // big device: half cores, capped at 6
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
    LOGI("nativeInit: ctx=%p target='%s' threads=%d",
            ctx, target.c_str(), optimalThreadCount());
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_asr_whisper_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/,
        jlong handle_ptr,
        jfloatArray pcm_jarr,
        jint /*sample_rate*/) {         // whisper.cpp always assumes 16 kHz internally

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

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.translate        = false;
    params.no_context       = true;
    params.print_timestamps = true;
    params.n_threads = optimalThreadCount();
    params.single_segment = true;
    params.language      = handle->target_lang.empty() ? nullptr : handle->target_lang.c_str();
    params.detect_language = handle->target_lang.empty();

    whisper_reset_timings(handle->ctx);

    if (whisper_full(handle->ctx, params, pcmf32.data(), (int) pcmf32.size()) != 0) {
        LOGW("nativeTranscribe: whisper_full failed");
        return env->NewStringUTF("");
    }

    whisper_print_timings(handle->ctx);

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

    LOGI("nativeTranscribe: %d samples → %d segments → '%s'",
            n_samples, n_segments, result.c_str());

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