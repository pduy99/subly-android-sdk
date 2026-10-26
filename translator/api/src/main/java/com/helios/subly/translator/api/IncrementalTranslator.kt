package com.helios.subly.translator.api

import kotlinx.coroutines.flow.Flow

/**
 * A [SublyTranslator] that can translate one sentence piece by piece.
 *
 * Built for engines where a call is expensive enough that re-translating a
 * growing sentence on every partial is not an option — an on-device LLM spends
 * seconds per generation. Instead of re-sending the whole sentence each time,
 * the session sends only the newly committed source text, and the engine
 * conditions each piece on the pieces before it (for an LLM: the earlier turns
 * stay in the KV cache, so only the new tokens are prefilled).
 *
 * Which pieces to send is the caller's decision — see `WaitKBuffer` in the SDK,
 * which holds text back until a clause is complete so that a verb-final source
 * is not translated before its verb is heard.
 */
interface IncrementalTranslator {

    /**
     * Opens a segment for one sentence. MUST only be called after
     * [SublyTranslator.prepareModel] reached Ready. Close it when the sentence
     * is final or abandoned; a segment holds engine resources until then.
     */
    fun openSegment(): TranslationSegment
}

/** One sentence being translated incrementally. Not thread-safe: one caller. */
interface TranslationSegment : AutoCloseable {

    /** All source text appended so far, joined as sent. */
    val source: String

    /** Translations of every completed [append], joined in order. */
    val translation: String

    /**
     * Translates [sourceDelta] as the continuation of [source].
     *
     * Cold flow. Each emission is the translation of this delta so far,
     * growing as tokens are generated; the last emission is the complete
     * translation, after which it is added to [translation]. Errors propagate
     * as flow exceptions. Cancelling collection stops generation.
     *
     * MUST NOT log [sourceDelta] or its translation in release builds.
     */
    fun append(sourceDelta: String): Flow<String>
}
