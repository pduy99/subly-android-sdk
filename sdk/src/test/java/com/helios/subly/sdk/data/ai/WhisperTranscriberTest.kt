package com.helios.subly.sdk.data.ai

import android.content.Context
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperTranscriberTest {

    private val ctx: Context = mock()

    private class FakeBackend(
        var available: Boolean = true,
        var initResult: Long = 42L,
        val transcripts: ArrayDeque<String> = ArrayDeque(),
    ) : WhisperBackend {
        var initCount = 0
        var releaseCount = 0
        val transcribeArgs = mutableListOf<Triple<Long, FloatArray, Int>>()

        override fun isAvailable() = available
        override fun init(modelPath: String, targetLanguageCode: String): Long {
            initCount++
            return initResult
        }

        override fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int): String {
            transcribeArgs += Triple(handle, pcm, sampleRateHz)
            return transcripts.removeFirstOrNull() ?: ""
        }

        override fun release(handle: Long) {
            releaseCount++
        }
    }

    private class FakeLoader(val path: String?) : WhisperModelLoader(mock<Context>()) {
        override fun resolve(model: WhisperModel): String? = path
    }

    private class FakeLoaderFactory(private val loader: WhisperModelLoader) : WhisperModelLoaderFactory {
        override fun create(context: Context): WhisperModelLoader = loader
    }

    @Test
    fun `degrades to drain when backend unavailable`() = runTest {
        val backend = FakeBackend(available = false)
        val transcriber = WhisperTranscriber(
            context = ctx,
            chunker = AudioChunker(windowMs = 100, hopMs = 100),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/dev/null")),
        )
        val frames = listOf(silentFrame(timestampMs = 0))
        val packets = transcriber.transcribeAudio(frames.asFlow(), LanguageConfig("en")).toList()

        assertTrue(packets.isEmpty())
        assertEquals(0, backend.initCount)
    }

    @Test
    fun `degrades to drain when model not on disk`() = runTest {
        val backend = FakeBackend(available = true)
        val transcriber = WhisperTranscriber(
            context = ctx,
            chunker = AudioChunker(windowMs = 100, hopMs = 100),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader(path = null)),
        )
        val packets = transcriber.transcribeAudio(listOf(silentFrame(0)).asFlow(), LanguageConfig("en")).toList()

        assertTrue(packets.isEmpty())
        assertEquals(0, backend.initCount) // never tried to init without a model path
    }

    @Test
    fun `emits TranslationPacket per non-empty transcription`() = runTest {
        val backend = FakeBackend(transcripts = ArrayDeque(listOf("hello world", "", "  ", "second segment")))
        val transcriber = WhisperTranscriber(
            context = ctx,
            chunker = AudioChunker(windowMs = 100, hopMs = 100),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        // Produce 4 windows worth of audio (4 x 100 ms frames).
        val frames = (0 until 4).map { i ->
            AudioFrame(
                pcm = ShortArray(1_600) { 1_000 },
                sampleRateHz = 16_000,
                channelCount = 1,
                timestampMs = i * 100L,
                maxAbsSample = 1_000,
            )
        }

        val packets = transcriber.transcribeAudio(frames.asFlow(), LanguageConfig("en")).toList()

        assertEquals(2, packets.size)
        assertEquals("hello world", packets[0].text)
        assertEquals("second segment", packets[1].text)
        assertTrue(packets.all { it.source == TranslationPacket.Source.AUDIO })
        assertTrue(packets.all { it.targetLanguageCode == "en" })
        assertEquals(1, backend.initCount) // handle reused across windows
    }

    @Test
    fun `non-English target preserves requested code on packet`() = runTest {
        val backend = FakeBackend(transcripts = ArrayDeque(listOf("bonjour")))
        val transcriber = WhisperTranscriber(
            context = ctx,
            chunker = AudioChunker(windowMs = 100, hopMs = 100),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        val frames = listOf(
            AudioFrame(ShortArray(1_600) { 500 }, 16_000, 1, 0L, 500),
        )
        val packets = transcriber.transcribeAudio(frames.asFlow(), LanguageConfig("fr")).toList()

        assertEquals(1, packets.size)
        assertEquals("fr", packets[0].targetLanguageCode)
        assertNotEquals("en", packets[0].targetLanguageCode)
    }

    @Test
    fun `release frees native handle once`() = runTest {
        val backend = FakeBackend(transcripts = ArrayDeque(listOf("x")))
        val transcriber = WhisperTranscriber(
            context = ctx,
            chunker = AudioChunker(windowMs = 100, hopMs = 100),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        transcriber.transcribeAudio(
            listOf(AudioFrame(ShortArray(1_600) { 1 }, 16_000, 1, 0, 1)).asFlow(),
            LanguageConfig("en"),
        ).toList()

        transcriber.release()
        transcriber.release()
        assertEquals(1, backend.releaseCount)
    }

    private fun silentFrame(timestampMs: Long) = AudioFrame(
        pcm = ShortArray(800),
        sampleRateHz = 16_000,
        channelCount = 1,
        timestampMs = timestampMs,
        maxAbsSample = 0,
    )
}
