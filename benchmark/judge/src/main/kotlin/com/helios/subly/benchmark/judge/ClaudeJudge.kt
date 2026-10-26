package com.helios.subly.benchmark.judge

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams

/**
 * Calls the Claude API to score one entry, one clip×engine pair per request.
 *
 * Structured outputs (`outputConfig(JudgeScoresDto.class)`) guarantee the
 * response parses into the score schema. The SDK retries 429/5xx with backoff
 * on its own. Sampling parameters are deliberately absent — the model rejects
 * non-default values; run-to-run consistency comes from the pinned prompt.
 */
class ClaudeJudge(
    private val model: String,
    apiKey: String,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .maxRetries(4)
        .build()

    fun judge(input: JudgeInput): JudgeScores {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(JUDGE_SYSTEM_PROMPT)
            .outputConfig(JudgeScoresDto::class.java)
            .addUserMessage(buildJudgePrompt(input))
            .build()

        val response = client.messages().create(params)
        val dto = response.content()
            .asSequence()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .firstOrNull()
            ?: error("Judge returned no structured output for ${input.clipId}×${input.engine}")

        return JudgeScores(
            transcriptionAccuracy = dto.transcriptionAccuracy,
            transcriptionReadability = dto.transcriptionReadability,
            translationAccuracy = dto.translationAccuracy,
            translationReadability = dto.translationReadability,
            rationale = dto.rationale,
        )
    }

    /** Jackson-friendly shape (no-arg constructor + mutable fields) for the SDK. */
    class JudgeScoresDto {
        @JvmField var transcriptionAccuracy: Int = 0
        @JvmField var transcriptionReadability: Int = 0
        @JvmField var translationAccuracy: Int = 0
        @JvmField var translationReadability: Int = 0
        @JvmField var rationale: String = ""
    }
}
