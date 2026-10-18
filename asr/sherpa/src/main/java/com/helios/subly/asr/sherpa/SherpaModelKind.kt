package com.helios.subly.asr.sherpa

/**
 * Indicates the role a specific on-device model plays in the pipeline.
 */
enum class SherpaModelKind {
    /** Streaming (online) recogniser. */
    ASR,
    PUNCTUATION,

    /** Offline recogniser, run on VAD-cut speech segments. */
    OFFLINE_ASR,

    /** Voice activity detector used to cut segments for [OFFLINE_ASR]. */
    VAD,
}