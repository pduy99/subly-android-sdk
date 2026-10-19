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
     * System instruction for incremental translation of one sentence.
     *
     * The sentence arrives as consecutive user messages, one per committed
     * clause. Each reply must translate only the newest piece, written to
     * follow the earlier replies, so the replies joined in order are the
     * sentence's translation. Earlier pieces stay in the conversation (and so
     * in the KV cache): they are context for the new piece, not re-translated.
     */
    fun incrementalInstruction(sourceTag: String, targetTag: String): String {
        val source = languageName(sourceTag)
        val target = languageName(targetTag)
        return "You are a live subtitle translation engine. The user sends one $source " +
            "sentence in consecutive pieces. Translate each piece into $target as the " +
            "continuation of your previous replies, so that your replies joined in order " +
            "read as one fluent $target sentence. A message may start with earlier subtitle " +
            "lines marked as context; use them to resolve who is speaking and what is meant, " +
            "but never translate or repeat them. Reply with the translation of the newest " +
            "piece only: never repeat earlier pieces, no explanation, no notes, no quotation " +
            "marks, and no romanisation."
    }

    /**
     * The first message of a sentence, with the previous lines of the
     * conversation as context.
     *
     * Subtitles are dialogue: a line like "你是这个卖家" drops its subject or
     * refers back, and translated alone it comes out as "Là người bán này"
     * with "you" lost. One or two earlier lines, already translated, are
     * enough to resolve that. They are clearly fenced so the model translates
     * only the new line.
     */
    fun withContext(piece: String, context: List<Pair<String, String>>): String {
        if (context.isEmpty()) return piece
        return buildString {
            append("Context (earlier lines, already translated; do not translate):\n")
            for ((source, translation) in context) append(source).append(" => ").append(translation).append('\n')
            append("\nTranslate:\n").append(piece)
        }
    }

    /**
     * The whole request for a Hunyuan-MT model ([LiteRtLmModel.PromptStyle.HY_MT]),
     * which has no system prompt: its documented default-translation prompt,
     * in Chinese when either side is Chinese, as its authors recommend.
     */
    fun hyMtMessage(text: String, sourceTag: String, targetTag: String): String {
        val chinese = sourceTag.substringBefore('-') == "zh" || targetTag.substringBefore('-') == "zh"
        return if (chinese) {
            "将以下文本翻译为${chineseLanguageName(targetTag)}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        } else {
            "Translate the following text into ${languageName(targetTag)}. Note that you should only " +
                "output the translated result without any additional explanation:\n\n$text"
        }
    }

    /** Chinese display name for a BCP-47 tag, e.g. `vi` -> `越南语`. */
    fun chineseLanguageName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
        return if (name.isBlank() || name.equals(tag, ignoreCase = true)) languageName(tag) else name
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
