#pragma once

#include <set>
#include <string>
#include <vector>

// UTF-8 safety helpers for text crossing the JNI boundary.
//
// whisper detokenizes to UTF-8 and can cut a multi-byte character in half when
// a decode hits the token cap. Java's NewStringUTF aborts the whole process on
// malformed input, so every string handed to JNI — and every substring taken
// of one — has to respect character boundaries. This matters most for CJK,
// where a character is 3 bytes and there are no spaces to align on.
//
// Header-only so the host-side tests in test/ exercise the same code that
// ships in the .so.

namespace subly::utf8 {

/** Length of the UTF-8 sequence led by [c], or 0 if [c] can't lead one. */
inline size_t seqLen(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 0; // continuation byte or invalid lead
}

/** Advances [i] past any continuation bytes to the next character start. */
inline size_t alignStart(const std::string & s, size_t i) {
    while (i < s.size() && ((unsigned char) s[i] & 0xC0) == 0x80) ++i;
    return i;
}

/**
 * Drops malformed bytes and any incomplete trailing sequence, so the result is
 * always valid UTF-8. Well-formed input is returned unchanged.
 */
inline std::string sanitize(const std::string & s) {
    std::string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const size_t n = seqLen((unsigned char) s[i]);
        if (n == 0) { ++i; continue; }   // stray byte — skip it
        if (i + n > s.size()) break;     // truncated tail — drop it
        bool ok = true;
        for (size_t k = 1; k < n; ++k) {
            if (((unsigned char) s[i + k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { ++i; continue; }
        out.append(s, i, n);
        i += n;
    }
    return out;
}

/** Splits [s] into UTF-8 characters, skipping malformed bytes. */
inline std::vector<std::string> chars(const std::string & s) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < s.size()) {
        const size_t n = seqLen((unsigned char) s[i]);
        if (n == 0 || i + n > s.size()) { ++i; continue; }
        out.emplace_back(s, i, n);
        i += n;
    }
    return out;
}

/**
 * Unique-character fraction, for judging repetition in scripts without word
 * spacing. Returns 1.0 for input too short to judge.
 */
inline double uniqueCharFraction(const std::string & s, size_t minChars) {
    const std::vector<std::string> cs = chars(s);
    if (cs.size() < minChars) return 1.0;
    const std::set<std::string> uniq(cs.begin(), cs.end());
    return (double) uniq.size() / (double) cs.size();
}

} // namespace subly::utf8
