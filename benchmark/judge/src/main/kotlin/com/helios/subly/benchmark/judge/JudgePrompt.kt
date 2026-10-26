package com.helios.subly.benchmark.judge

/**
 * Version of the judging prompt. Recorded in every scores.json so runs remain
 * comparable — bump it whenever [buildJudgePrompt] (or the system prompt)
 * changes meaning, and expect scores to shift across the bump.
 */
const val PROMPT_VERSION = "v2"

val JUDGE_SYSTEM_PROMPT = """
You are an expert evaluator of live-caption quality. You score the output of an
on-device speech-captioning SDK against human-verified references. You are
rigorous, consistent, and you apply the same standards to every submission so
scores are comparable across evaluation runs.
""".trimIndent()

/**
 * One clip × engine × target per prompt, deterministic for a given input.
 *
 * Accuracy measures content fidelity (words heard / meaning conveyed).
 * Readability measures how pleasant the text is to read as captions —
 * punctuation, casing, and sentence segmentation. References are punctuated
 * and cased on purpose; generated text often is not, and that difference is
 * exactly what the readability scores must capture.
 */
private fun captionBlock(input: JudgeInput): String {
    if (input.captions.isEmpty()) {
        return """
<caption_boundaries>
Not recorded for this run — judge segmentation from punctuation alone.
</caption_boundaries>
""".trim()
    }
    val src = input.captions.mapIndexed { i, c -> "${i + 1}. ${c.original.trim()}" }
    val dst = input.captions.mapIndexed { i, c -> "${i + 1}. ${c.translated.trim()}" }
    return """
<generated_captions_source>
${src.joinToString("\n")}
</generated_captions_source>

<generated_captions_translated>
${dst.joinToString("\n")}
</generated_captions_translated>
""".trim()
}

fun buildJudgePrompt(input: JudgeInput): String = """
Evaluate one clip processed by a speech captioning SDK.

Source language: ${input.sourceLang}
Translation target language: ${input.targetLang}
Clip: ${input.clipId}
Engine: ${input.engine}

<reference_transcript>
${input.referenceTranscript.trim()}
</reference_transcript>

<generated_transcript>
${input.generatedTranscript.trim()}
</generated_transcript>

<reference_translation>
${input.referenceTranslation.trim()}
</reference_translation>

<generated_translation>
${input.generatedTranslation.trim()}
</generated_translation>

The generated text above is the captions joined together. Below is the same
output with the caption boundaries the SDK actually emitted. Each numbered
line appeared on screen as one unit, read on its own — so a line that ends
mid-clause, or splits a phrase across two captions, is hard to read even when
the joined text looks fine.

${captionBlock(input)}

Score the generated output on four dimensions, each 0-100:

- transcriptionAccuracy: how faithfully the generated transcript captures the
  words of the reference transcript. Penalize missing, wrong, or invented
  words; ignore punctuation and casing here.
- transcriptionReadability: how readable the generated transcript is as
  captions. Weigh two things roughly equally: punctuation and casing against
  the reference, and whether the caption boundaries above fall at sensible
  places. Unpunctuated run-on text scores low even when the words are right,
  and so does text broken mid-clause across captions.
- translationAccuracy: how faithfully the generated translation conveys the
  meaning of the reference translation. Different but equivalent wording is
  fine; penalize lost, wrong, or invented meaning.
- translationReadability: how natural and readable the generated translation
  is in the target language — grammar, flow, punctuation, segmentation.

Calibration: 90+ near-perfect; 70-89 good with noticeable flaws; 40-69
significant problems but usable; below 40 badly degraded.

Also write a `rationale`: 2-4 sentences naming the most important concrete
problems (quote short examples) that drove the scores.
""".trimIndent()
