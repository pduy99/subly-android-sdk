// Provided with the streaming-pipeline task specification. Assertions are
// kept exactly as supplied; the types it uses are bound to the SDK in
// PipelineAdapter.kt in this package.
package com.duypham.translation.benchmark

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * Test Harness for Opus to validate the Real-Time Translation Pipeline.
 *
 * INSTRUCTIONS FOR OPUS:
 * You must implement the TranslationPipeline to make these tests pass.
 * Do not modify the test assertions. Ensure the hardware delegate is active,
 * Wait-k buffering prevents premature verb translation, and latency thresholds are met.
 */
@RunWith(AndroidJUnit4::class)
class PipelineBenchmarkTest {

    private lateinit var pipeline: TranslationPipeline

    @Before
    fun setup() {
        // Opus must implement TranslationPipelineFactory and the underlying pipeline
        pipeline = TranslationPipelineFactory.createPipeline()
    }

    @Test
    fun `test 1 - Model Delegate Status ensures NPU or GPU is used`() {
        val config = pipeline.getActiveConfig()

        // The pipeline MUST NOT fallback to CPU for Gemma or Whisper
        assertNotEquals("Whisper model fell back to CPU!", DelegateType.CPU, config.whisperDelegate)
        assertNotEquals("Gemma model fell back to CPU!", DelegateType.CPU, config.gemmaDelegate)

        assertTrue("Expected hardware acceleration (NPU/GPU)",
            config.whisperDelegate == DelegateType.NPU || config.whisperDelegate == DelegateType.GPU)
    }

    @Test
    fun `test 2 - Streaming Token Stability and Wait-k buffering`() = runBlocking {
        // Simulate SOV (Subject-Object-Verb) input audio chunks
        val audioChunks = listOf(
            AudioChunk(data = byteArrayOf(), isSilence = false, transcriptMock = "Watashi wa "),
            AudioChunk(data = byteArrayOf(), isSilence = false, transcriptMock = "ringo o "),
            AudioChunk(data = byteArrayOf(), isSilence = false, transcriptMock = "taberu.")
        )

        val emittedStates = mutableListOf<TranslationState>()

        val job = launch(Dispatchers.IO) {
            pipeline.stateFlow.toList(emittedStates)
        }

        // Push chunks with simulated human speaking delay
        audioChunks.forEach { chunk ->
            pipeline.pushAudio(chunk)
            delay(300) // 300ms between chunks
        }

        pipeline.stopStream()
        job.cancel()

        // Verification: Wait-k logic should prevent Gemma from outputting premature translations
        // until enough context (the verb) is provided.
        val prematureTranslations = emittedStates.filter {
            it is TranslationState.Partial && it.text.contains("eat", ignoreCase = true)
        }

        // The model should wait for chunk 3 before restructuring the sentence to include the verb
        assertTrue("Buffer leak: Model hallucinated translated verb before it was spoken!",
            prematureTranslations.isEmpty() || emittedStates.indexOf(prematureTranslations.first()) >= 2)

        val finalOutput = emittedStates.last()
        assertTrue("Expected final state", finalOutput is TranslationState.Finalized)
        assertTrue("Final output must contain valid SVO restructuring",
            (finalOutput as TranslationState.Finalized).text.matches(Regex(".*I.*eat.*apples.*", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun `test 3 - End-to-End Latency Limits`() = runBlocking {
        val syntheticAudio = AudioGenerator.generateContinuousSpeech(durationSec = 2)
        val latch = CountDownLatch(1)
        var timeToFirstToken = -1L

        val job = launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            pipeline.stateFlow.collect { state ->
                if (state is TranslationState.Partial && timeToFirstToken == -1L) {
                    timeToFirstToken = System.currentTimeMillis() - startTime
                }
                if (state is TranslationState.Finalized) {
                    latch.countDown()
                }
            }
        }

        val totalLatency = measureTimeMillis {
            pipeline.pushAudio(syntheticAudio)
            pipeline.stopStream()

            // Wait up to 3 seconds for processing to finish
            val completed = latch.await(3, TimeUnit.SECONDS)
            assertTrue("Pipeline timed out, latency too high", completed)
        }
        job.cancel()

        // Benchmark Thresholds
        assertTrue("TTFT (Time To First Token) exceeded 300ms: $timeToFirstToken ms", timeToFirstToken in 1..300)
        assertTrue("End-to-End Latency exceeded 800ms: $totalLatency ms", totalLatency <= 800)

        println("Pipeline Benchmarks Passed: TTFT=${timeToFirstToken}ms, Total Latency=${totalLatency}ms")
    }
}
