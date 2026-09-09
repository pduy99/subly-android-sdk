package com.helios.subly.translator.litertlm

import java.util.Locale

/**
 * Prompt construction and output tidying for the LLM translator.
 *
 * Kept separate from [LiteRtLmTranslator] because this is the part worth
 * unit-testing: the translator itself is a thin lifecycle wrapper around a
 * native engine that no JVM test can load.
 */
internal object TranslationPrompt {

    /**
     * System instruction for one language pair.
     *
     * Deliberately short. Every token here is prefilled on *every* caption —
     * a fresh conversation is created per translation so history cannot
     * accumulate — so prompt length is a per-caption latency cost, not a
     * one-off. It buys two things and no more: the direction of translation,
     * and a ban on the commentary instruction-tuned models like to add.
     */
    fun systemInstruction(sourceTag: String, targetTag: String): String {
        val source = languageName(sourceTag)
        val target = languageName(targetTag)
        return "You are a translation engine. Translate the user's $source text into $target. " +
            "Reply with the $target translation only: no explanation, no notes, no quotation " +
            "marks, and no romanisation."
    }

    /**
     * English display name for a BCP-47 tag, e.g. `vi` -> `Vietnamese`.
     *
     * Uses the platform's own tables rather than a hand-written map, so
     * adding a language to the model catalog needs no change here. Falls back
     * to the tag itself when the platform has no name — a model given "yue"
     * still does better than one given nothing.
     */
    fun languageName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank() || name.equals(tag, ignoreCase = true)) tag else name
    }

    /**
     * Strips the packaging instruction-tuned models add around a translation.
     *
     * Handles the forms actually observed from small instruct models: a
     * `Translation:` label, wrapping quotes, a markdown code fence, and a
     * trailing explanatory paragraph. Only the first paragraph survives —
     * a caption is one sentence, so anything after a blank line is the model
     * talking about the translation rather than translating.
     */
    fun clean(raw: String): String {
        var text = raw.trim()

        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBefore("```").trim()
        }

        // Only the first paragraph: see above.
        text = text.substringBefore("\n\n").trim()

        for (label in LABELS) {
            if (text.startsWith(label, ignoreCase = true)) {
                text = text.substring(label.length).trim()
            }
        }

        // Strip one layer of matching wrapping quotes. Only when *both* ends
        // match, so a translation that legitimately opens with a quotation
        // keeps it.
        for ((open, close) in QUOTE_PAIRS) {
            if (text.length >= 2 && text.first() == open && text.last() == close) {
                text = text.substring(1, text.length - 1).trim()
                break
            }
        }

        return text
    }

    private val LABELS = listOf("Translation:", "Translated text:", "Answer:", "Output:")

    private val QUOTE_PAIRS = listOf(
        '"' to '"',
        '\'' to '\'',
        '“' to '”',
        '«' to '»',
        '「' to '」',
    )
}
