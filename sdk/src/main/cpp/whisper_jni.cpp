// JNI bridge for Subly's Whisper.cpp integration.
//
// Two build modes:
//   * SUBLY_HAS_WHISPER undefined -> stub mode. nativeIsAvailable() returns
//     false, all other entry points are no-ops. Kotlin's WhisperTranscriber
//     detects this and degrades to a pass-through. This is the default so
//     the SDK can build on CI without vendoring whisper.cpp.
//   * SUBLY_HAS_WHISPER=1 -> real mode. Links against whisper.cpp's
//     `whisper` target (added in CMakeLists.txt) and forwards calls.
//
// Native function signatures intentionally mirror the Kotlin object in
// data/ai/WhisperNative.kt so the two stay in lockstep.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#ifdef SUBLY_HAS_WHISPER
// whisper.cpp's CMake target exports its include/ directory; just <whisper.h>.
#include <whisper.h>
#endif

#define LOG_TAG "SublyWhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

#ifdef SUBLY_HAS_WHISPER
struct WhisperHandle {
    whisper_context* ctx = nullptr;
    std::string targetLang;
    // Whisper's built-in translation only produces English. We keep it OFF
    // here so the transcript stays in the source language and the ML Kit NMT
    // stage handles the full source->target pair (avoiding lossy double
    // translation when the user picks a non-English target).
    bool translate = false;
    // BCP-47 tag of the language detected by whisper on the LAST
    // nativeTranscribe call; empty until the first call returns.
    std::string lastDetectedLang;
};
#endif

[[maybe_unused]] std::string jstringToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* cstr = env->GetStringUTFChars(s, nullptr);
    std::string out(cstr ? cstr : "");
    if (cstr) env->ReleaseStringUTFChars(s, cstr);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_helios_subly_sdk_data_ai_WhisperNative_nativeIsAvailable(JNIEnv*, jobject) {
#ifdef SUBLY_HAS_WHISPER
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_helios_subly_sdk_data_ai_WhisperNative_nativeInit(
    JNIEnv* env, jobject, jstring jModelPath, jstring jTargetLang) {
#ifdef SUBLY_HAS_WHISPER
    const std::string modelPath  = jstringToStd(env, jModelPath);
    const std::string targetLang = jstringToStd(env, jTargetLang);
    if (modelPath.empty()) {
        LOGE("nativeInit: empty model path");
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only on Android per PRD AI core
    whisper_context* ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("nativeInit: whisper_init_from_file_with_params failed for %s", modelPath.c_str());
        return 0;
    }
    auto* h = new WhisperHandle{ctx, targetLang, /*translate=*/false, /*lastDetectedLang=*/""};
    LOGI("nativeInit: ok, target=%s, translate=off", targetLang.c_str());
    return reinterpret_cast<jlong>(h);
#else
    (void)env; (void)jModelPath; (void)jTargetLang;
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_sdk_data_ai_WhisperNative_nativeTranscribe(
    JNIEnv* env, jobject, jlong handle, jfloatArray jPcm, jint sampleRate) {
#ifdef SUBLY_HAS_WHISPER
    if (handle == 0 || jPcm == nullptr) return env->NewStringUTF("");
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    const jsize n = env->GetArrayLength(jPcm);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language        = "auto";       // PRD: source locked to auto-detect
    params.translate       = h->translate; // OFF by default — see WhisperHandle comment.
    params.print_progress  = false;
    params.print_realtime  = false;
    params.print_timestamps= false;
    params.no_context      = true;
    params.single_segment  = false;
    params.suppress_blank  = true;
    params.n_threads       = 4;

    if (sampleRate != WHISPER_SAMPLE_RATE) {
        LOGW("nativeTranscribe: unexpected sample rate %d (expected %d)", sampleRate, WHISPER_SAMPLE_RATE);
    }

    if (whisper_full(h->ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("nativeTranscribe: whisper_full failed");
        h->lastDetectedLang.clear();
        return env->NewStringUTF("");
    }
    // Capture the language detected by whisper on this call. We stash on the
    // handle so the Kotlin layer can query it via nativeLastDetectedLang
    // without changing the transcribe return signature.
    const int langId = whisper_full_lang_id(h->ctx);
    if (langId >= 0) {
        const char* code = whisper_lang_str(langId);
        h->lastDetectedLang = code != nullptr ? code : "";
    } else {
        h->lastDetectedLang.clear();
    }
    std::string out;
    const int n_seg = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < n_seg; ++i) {
        const char* t = whisper_full_get_segment_text(h->ctx, i);
        if (t != nullptr) out.append(t);
    }
    return env->NewStringUTF(out.c_str());
#else
    (void)env; (void)handle; (void)jPcm; (void)sampleRate;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jstring JNICALL
Java_com_helios_subly_sdk_data_ai_WhisperNative_nativeLastDetectedLang(
    JNIEnv* env, jobject, jlong handle) {
#ifdef SUBLY_HAS_WHISPER
    if (handle == 0) return env->NewStringUTF("");
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    return env->NewStringUTF(h->lastDetectedLang.c_str());
#else
    (void)handle;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT void JNICALL
Java_com_helios_subly_sdk_data_ai_WhisperNative_nativeRelease(JNIEnv*, jobject, jlong handle) {
#ifdef SUBLY_HAS_WHISPER
    if (handle == 0) return;
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    if (h->ctx != nullptr) whisper_free(h->ctx);
    delete h;
#else
    (void)handle;
#endif
}

} // extern "C"
