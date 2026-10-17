package com.helios.subly.sdk.streaming

import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.TranslationSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingTranslationStageTest {

    /**
     * Word-by-word dictionary translator. Each append "generates" one token
     * per word, [tokenDelayMs] apart, and records what it was asked.
     */
    private class FakeTranslator(
        private val dictionary: Map<String, String>,
        private val tokenDelayMs: Long = 100,
        private val failOnAppend: Int? = null,
    ) : IncrementalTranslator {
        val appends = mutableListOf<String>()
        var segmentsOpened = 0

        override fun openSegment(): TranslationSegment {
            segmentsOpened++
            return object : TranslationSegment {
                private val pieces = mutableListOf<String>()
                private val outs = mutableListOf<String>()
                override val source get() = pieces.joinToString(" ")
                override val translation get() = outs.filter { it.isNotEmpty() }.joinToString(" ")

                override fun append(sourceDelta: String): Flow<String> = flow {
                    appends += sourceDelta
                    if (failOnAppend == appends.size) error("generation failed")
                    val words = sourceDelta.split(' ')
                        .map { it.lowercase().trim('.', ',') }
                        .mapNotNull { dictionary[it] }
                    var soFar = ""
                    for (w in words) {
                        delay(tokenDelayMs)
                        soFar = if (soFar.isEmpty()) w else "$soFar $w"
                        emit(soFar)
                    }
                    pieces += sourceDelta
                    outs += soFar
                    emit(soFar)
                }

                override fun close() = Unit
            }
        }
    }

    private val japanese = mapOf("watashi" to "I", "ringo" to "apples", "taberu" to "eat")
    private val english = mapOf(
        "the" to "le", "man" to "homme", "who" to "qui", "lives" to "habite",
        "next" to "à côté", "door" to "porte", "a" to "un",
    )

    private fun TestScope.stage(
        translator: IncrementalTranslator,
        language: String,
        states: MutableList<TranslationState>,
        metrics: MutableList<StageMetric> = mutableListOf(),
    ) = StreamingTranslationStage(
        translator = translator,
        policy = WaitKPolicy.forLanguage(language),
        onState = { states += it },
        onMetric = { metrics += it },
        nanoTime = { testScheduler.currentTime * 1_000_000 },
    )

    @Test
    fun `SOV sentence drafts its noun phrases and restructures on final`() = runTest {
        val translator = FakeTranslator(japanese)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "ja", states)
        val worker = launch { stage.run() }

        stage.onPartial("Watashi wa")
        stage.onPartial("Watashi wa ringo o")
        stage.onPartial("Watashi wa ringo o taberu.")
        advanceUntilIdle()
        // Noun phrases are drafted (the two commits arrived before the worker
        // ran, so they merged into one append); the verb is not.
        assertEquals(listOf("Watashi wa ringo o"), translator.appends)
        assertTrue(
            "translated the verb before it was final: $states",
            states.none { it is TranslationState.Partial && it.text.contains("eat") },
        )

        stage.onFinal("Watashi wa ringo o taberu.")
        stage.finish()
        worker.join()

        // The final is not "I" + "apples" + "eat" stitched in source order:
        // an SOV sentence is retranslated whole so the target can reorder it.
        assertEquals("Watashi wa ringo o taberu.", translator.appends.last())
        assertEquals(2, translator.segmentsOpened)
        assertTrue(states.last() is TranslationState.Finalized)
    }

    @Test
    fun `SVO pieces stream as partials and the final reuses them`() = runTest {
        val translator = FakeTranslator(english)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "en", states)
        val worker = launch { stage.run() }

        stage.onPartial("the man who lives")
        stage.onPartial("the man who lives next")
        advanceUntilIdle()
        val partial = states.last() as TranslationState.Partial
        assertEquals("le homme qui habite", partial.text)

        stage.onFinal("The man who lives next door.")
        stage.finish()
        worker.join()

        assertEquals(listOf("the man who lives", "next door."), translator.appends)
        assertEquals(1, translator.segmentsOpened)
        assertEquals("le homme qui habite à côté porte", (states.last() as TranslationState.Finalized).text)
    }

    @Test
    fun `input never waits for the translator`() = runTest {
        val translator = FakeTranslator(english, tokenDelayMs = 5_000)
        val stage = stage(translator, "en", mutableListOf())
        val worker = launch { stage.run() }

        val before = testScheduler.currentTime
        repeat(50) { i -> stage.onPartial((0..i).joinToString(" ") { "the" }) }
        stage.onFinal("the man.")
        assertEquals("onPartial/onFinal advanced virtual time", before, testScheduler.currentTime)

        stage.finish()
        worker.join()
    }

    @Test
    fun `work queued behind a slow generation is merged`() = runTest {
        val translator = FakeTranslator(english, tokenDelayMs = 1_000)
        val stage = stage(translator, "en", mutableListOf())
        val worker = launch { stage.run() }

        stage.onPartial("the man who lives")
        stage.onPartial("the man who lives next") // commits piece 1
        testScheduler.advanceTimeBy(500) // worker is mid-generation
        stage.onPartial("the man who lives next door a man who")
        stage.onPartial("the man who lives next door a man who lives") // commits piece 2
        stage.onFinal("the man who lives next door a man who lives.")
        stage.finish()
        worker.join()

        // Piece 2 and the final's remainder became one append.
        assertEquals(2, translator.appends.size)
        assertEquals("the man who lives", translator.appends[0])
    }

    @Test
    fun `a final that rewrites committed text is retranslated whole`() = runTest {
        val translator = FakeTranslator(english)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "en", states)
        val worker = launch { stage.run() }

        stage.onPartial("the man who lives")
        stage.onPartial("the man who lives next")
        advanceUntilIdle()
        stage.onFinal("A man who lives next door.")
        stage.finish()
        worker.join()

        assertEquals(listOf("the man who lives", "A man who lives next door."), translator.appends)
        assertEquals("un homme qui habite à côté porte", (states.last() as TranslationState.Finalized).text)
    }

    @Test
    fun `a failed piece is recovered by translating the final whole`() = runTest {
        val translator = FakeTranslator(english, failOnAppend = 1)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "en", states)
        val worker = launch { stage.run() }

        stage.onPartial("the man who lives")
        stage.onPartial("the man who lives next")
        advanceUntilIdle()
        stage.onFinal("the man who lives next door.")
        stage.finish()
        worker.join()

        assertEquals("the man who lives next door.", translator.appends.last())
        assertEquals("le homme qui habite à côté porte", (states.last() as TranslationState.Finalized).text)
    }

    @Test
    fun `the last sentence streams its final translation`() = runTest {
        val translator = FakeTranslator(english)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "en", states)
        val worker = launch { stage.run() }

        stage.onFinal("the man.")
        stage.finish()
        worker.join()

        assertEquals(
            listOf(
                TranslationState.Partial("the man.", "le", 0),
                TranslationState.Partial("the man.", "le homme", 0),
                TranslationState.Partial("the man.", "le homme", 0),
                TranslationState.Finalized("the man.", "le homme"),
            ),
            states,
        )
    }

    @Test
    fun `a sentence overtaken by newer speech does not stream`() = runTest {
        val translator = FakeTranslator(english, tokenDelayMs = 1_000)
        val states = mutableListOf<TranslationState>()
        val stage = stage(translator, "en", states)
        val worker = launch { stage.run() }

        stage.onFinal("the man.")
        stage.onPartial("a man who") // the next sentence has begun
        stage.finish()
        worker.join()

        assertTrue(
            "stale partials: $states",
            states.none { it is TranslationState.Partial && it.source == "the man." },
        )
        assertEquals("le homme", (states.last() as TranslationState.Finalized).text)
    }

    @Test
    fun `metrics report time to first token and final latency`() = runTest {
        val translator = FakeTranslator(english, tokenDelayMs = 100)
        val metrics = mutableListOf<StageMetric>()
        val stage = stage(translator, "en", mutableListOf(), metrics)
        val worker = launch { stage.run() }

        stage.onFinal("the man.")
        stage.finish()
        worker.join()

        assertEquals(100L, metrics.first { it.kind == StageMetric.Kind.TTFT }.ms)
        assertEquals(200L, metrics.first { it.kind == StageMetric.Kind.FINAL }.ms)
    }
}
