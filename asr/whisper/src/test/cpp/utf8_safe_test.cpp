// Host-side tests for the UTF-8 safety helpers.
//
// Build and run:
//   c++ -std=c++17 -I ../../main/cpp utf8_safe_test.cpp -o /tmp/utf8_test && /tmp/utf8_test
//
// No device or NDK needed — the helpers are header-only, so this exercises
// exactly the code that ships in libwhisper-jni.so.

#include "utf8_safe.h"

#include <cassert>
#include <cstdio>
#include <string>

namespace u = subly::utf8;

static int failures = 0;

static void check(bool ok, const char * what) {
    if (!ok) { std::printf("  FAIL: %s\n", what); ++failures; }
    else     { std::printf("  ok:   %s\n", what); }
}

int main() {
    // The exact payload from tombstone_16: a whisper repetition loop cut off
    // mid-character. It ends 0xe9 0x99 — the first two bytes of 陆 (E9 99 86).
    // Passing this to NewStringUTF aborted the process.
    const std::string crashing =
        "\xe5\x8f\xb0\xe9\xa3\x8e\xe5\x9c\xa8\xe5\x8d\x97\xe9\x83\xa8"
        "\xe6\xb2\xbf\xe6\xb5\xb7\xe7\x99\xbb\xe9\x99\x86\xe4\xb8\x80"
        "\xe5\x9c\xba\xe5\xbc\xba"          // 台风在南部沿海登陆一场强
        "\xe5\x8f\xb0\xe9\xa3\x8e\xe5\x9c\xa8\xe5\x8d\x97\xe9\x83\xa8"
        "\xe6\xb2\xbf\xe6\xb5\xb7\xe7\x99\xbb"
        "\xe9\x99";                          // truncated 陆 — the crash trigger

    std::printf("sanitize:\n");
    const std::string safe = u::sanitize(crashing);
    check(safe.size() == crashing.size() - 2, "drops the 2-byte truncated tail");
    check(u::sanitize(safe) == safe, "is idempotent");
    check(u::sanitize("hello") == "hello", "leaves ASCII untouched");
    check(u::sanitize("台风在南部") == "台风在南部", "leaves valid CJK untouched");
    check(u::sanitize("") == "", "handles empty input");
    check(u::sanitize(std::string("a\x80\x80" "b")) == "ab", "drops stray continuation bytes");
    check(u::sanitize("\xe9\x99") == "", "drops a lone truncated sequence");

    // Every byte of the sanitized output must form complete sequences —
    // this is the property NewStringUTF actually requires.
    std::printf("well-formedness:\n");
    bool wellFormed = true;
    for (size_t i = 0; i < safe.size();) {
        const size_t n = u::seqLen((unsigned char) safe[i]);
        if (n == 0 || i + n > safe.size()) { wellFormed = false; break; }
        i += n;
    }
    check(wellFormed, "sanitized output is complete valid UTF-8");

    std::printf("alignStart:\n");
    const std::string cjk = "台风在南部";                 // 5 chars x 3 bytes
    check(u::alignStart(cjk, 0) == 0, "leaves a character start alone");
    check(u::alignStart(cjk, 1) == 3, "advances off a mid-character offset");
    check(u::alignStart(cjk, 2) == 3, "advances off the 2nd continuation byte");
    check(u::alignStart(cjk, 3) == 3, "leaves the next character start alone");
    check(u::alignStart(cjk, cjk.size()) == cjk.size(), "handles end of string");
    // The property that matters: slicing at an aligned offset is always safe.
    bool allSlicesValid = true;
    for (size_t i = 0; i <= cjk.size(); ++i) {
        const std::string tail = cjk.substr(u::alignStart(cjk, i));
        if (u::sanitize(tail) != tail) { allSlicesValid = false; break; }
    }
    check(allSlicesValid, "every aligned slice is valid UTF-8");

    std::printf("uniqueCharFraction (CJK repetition):\n");
    // A repetition loop: 12 distinct characters repeated, so the fraction is low.
    std::string looped;
    for (int i = 0; i < 8; ++i) looped += "台风在南部沿海登陆一场强";
    check(u::uniqueCharFraction(looped, 12) < 0.5, "flags a repetition loop");
    // Real prose: mostly distinct characters.
    const std::string prose =
        "晚上好，欢迎收看七点新闻。今天上午，一场强台风在南部沿海登陆，"
        "给多个城市带来了暴雨和大风。当地政府已经提前转移了沿海地区的居民。";
    check(u::uniqueCharFraction(prose, 12) >= 0.5, "does not flag normal prose");
    check(u::uniqueCharFraction("你好", 12) == 1.0, "short input is not judged");

    std::printf("\n%s\n", failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
    return failures == 0 ? 0 : 1;
}
