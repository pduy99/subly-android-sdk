// Measures the Silero VAD exactly as whisper-jni.cpp gates with it: max
// per-frame speech probability over the leading slice, versus over the whole
// window. Input files are raw mono s16le at 16 kHz, one per segment.
#include "whisper.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static std::vector<float> read_raw(const char * path) {
    FILE * f = fopen(path, "rb");
    if (!f) return {};
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<int16_t> s(n / 2);
    if (fread(s.data(), 2, s.size(), f) != s.size()) { fclose(f); return {}; }
    fclose(f);
    std::vector<float> out(s.size());
    for (size_t i = 0; i < s.size(); ++i) out[i] = s[i] / 32768.0f;
    return out;
}

static float max_prob(whisper_vad_context * vad, const float * pcm, int n) {
    if (n <= 0) return -1.0f;
    if (!whisper_vad_detect_speech(vad, pcm, n)) return -1.0f;
    const int np = whisper_vad_n_probs(vad);
    const float * p = whisper_vad_probs(vad);
    float m = 0.0f;
    for (int i = 0; i < np && p; ++i) if (p[i] > m) m = p[i];
    return m;
}

int main(int argc, char ** argv) {
    if (argc < 3) { fprintf(stderr, "usage: vad_probe <model> <raw...>\n"); return 2; }
    auto cp = whisper_vad_default_context_params();
    cp.n_threads = 4;
    cp.use_gpu = false;
    whisper_vad_context * vad = whisper_vad_init_from_file_with_params(argv[1], cp);
    if (!vad) { fprintf(stderr, "vad init failed\n"); return 1; }

    const int SR = 16000;
    const int FRONT = 1500 * SR / 1000;   // VAD_SCAN_MAX_MS in whisper-jni.cpp

    printf("file\tms\tfront_max\tfull_max\trest_max\n");
    for (int i = 2; i < argc; ++i) {
        auto pcm = read_raw(argv[i]);
        if (pcm.empty()) { fprintf(stderr, "skip %s\n", argv[i]); continue; }
        const int n = (int) pcm.size();
        const float front = max_prob(vad, pcm.data(), n < FRONT ? n : FRONT);
        const float full  = max_prob(vad, pcm.data(), n);
        // The remainder scanned on its own: detect_speech resets LSTM state,
        // so a second call starts cold and need not match the full scan.
        const float rest  = n > FRONT ? max_prob(vad, pcm.data() + FRONT, n - FRONT) : -1.0f;
        printf("%s\t%d\t%.3f\t%.3f\t%.3f\n", argv[i], n * 1000 / SR, front, full, rest);
        fflush(stdout);
    }
    whisper_vad_free(vad);
    return 0;
}
