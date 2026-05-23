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
        val detectedLangs: ArrayDeque<String> = ArrayDeque(),
    ) : WhisperBackend {
        var initCount = 0
        var releaseCount = 0
        var lastDetectedLangReturn: String = ""
        val transcribeArgs = mutableListOf<Triple<Long, FloatArray, Int>>()

        override fun isAvailable() = available
        override fun init(modelPath: String, targetLanguageCode: String): Long {
            initCount++
            return initResult
        }

        override fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int): String {
            transcribeArgs += Triple(handle, pcm, sampleRateHz)
            lastDetectedLangReturn = detectedLangs.removeFirstOrNull() ?: ""
            return transcripts.removeFirstOrNull() ?: ""
        }

        override fun lastDetectedLang(handle: Long): String = lastDetectedLangReturn

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

    /** Permissive segmenter: any non-silent frame opens a segment, 50 ms hang closes it. */
    private fun testSegmenter() = SpeechSegmenter(
        threshold = 500,
        silenceHangMs = 50,
        maxSegmentMs = 5_000,
        minSegmentMs = 25,
        preRollMs = 0,
        framesToOpenSpeech = 1,
    )

    /** 50 ms @ 16 kHz mono frame. */
    private fun frame(maxAbs: Int, ts: Long) = AudioFrame(
        pcm = ShortArray(800) { maxAbs.toShort() },
        sampleRateHz = 16_000,
        channelCount = 1,
        timestampMs = ts,
        maxAbsSample = maxAbs,
    )

    private fun silentFrame(ts: Long) = AudioFrame(
        pcm = ShortArray(800),
        sampleRateHz = 16_000,
        channelCount = 1,
        timestampMs = ts,
        maxAbsSample = 0,
    )

    /** Speech (4 x 50 ms loud) + silence (3 x 50 ms) -> one segment. */
    private fun oneSegmentFrames(startTs: Long = 0L): List<AudioFrame> = buildList {
        for (i in 0 until 4) add(frame(5_000, startTs + i * 50L))
        for (i in 4 until 7) add(silentFrame(startTs + i * 50L))
    }

    @Test
    fun `degrades to drain when backend unavailable`() = runTest {
        val backend = FakeBackend(available = false)
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/dev/null")),
        )
        val packets = transcriber.transcribeAudio(oneSegmentFrames().asFlow(), LanguageConfig("en")).toList()

        assertTrue(packets.isEmpty())
        assertEquals(0, backend.initCount)
    }

    @Test
    fun `degrades to drain when model not on disk`() = runTest {
        val backend = FakeBackend(available = true)
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader(path = null)),
        )
        val packets = transcriber.transcribeAudio(oneSegmentFrames().asFlow(), LanguageConfig("en")).toList()

        assertTrue(packets.isEmpty())
        assertEquals(0, backend.initCount)
    }

    @Test
    fun `emits TranslationPacket per non-empty transcription with detected language`() = runTest {
        val backend = FakeBackend(
            transcripts = ArrayDeque(listOf("hello world", "second segment")),
            detectedLangs = ArrayDeque(listOf("en", "es")),
        )
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        val frames = oneSegmentFrames(0L) + oneSegmentFrames(350L)

        val packets = transcriber.transcribeAudio(frames.asFlow(), LanguageConfig("vi")).toList()

        assertEquals(2, packets.size)
        assertEquals("hello world", packets[0].text)
        assertEquals("en", packets[0].sourceLanguageCode)
        assertEquals("second segment", packets[1].text)
        assertEquals("es", packets[1].sourceLanguageCode)
        assertTrue(packets.all { it.source == TranslationPacket.Source.AUDIO })
        assertTrue(packets.all { it.targetLanguageCode == "vi" })
        assertEquals(1, backend.initCount)
    }

    @Test
    fun `empty detected language falls back to auto`() = runTest {
        val backend = FakeBackend(
            transcripts = ArrayDeque(listOf("ambiguous")),
            detectedLangs = ArrayDeque(listOf("")),
        )
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        val packets = transcriber.transcribeAudio(oneSegmentFrames().asFlow(), LanguageConfig("en")).toList()
        assertEquals(1, packets.size)
        assertEquals("auto", packets[0].sourceLanguageCode)
    }

    @Test
    fun `non-English target preserves requested code on packet`() = runTest {
        val backend = FakeBackend(
            transcripts = ArrayDeque(listOf("bonjour")),
            detectedLangs = ArrayDeque(listOf("fr")),
        )
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        val packets = transcriber.transcribeAudio(oneSegmentFrames().asFlow(), LanguageConfig("fr")).toList()

        assertEquals(1, packets.size)
        assertEquals("fr", packets[0].targetLanguageCode)
        assertNotEquals("en", packets[0].targetLanguageCode)
    }

    @Test
    fun `release frees native handle once`() = runTest {
        val backend = FakeBackend(
            transcripts = ArrayDeque(listOf("x")),
            detectedLangs = ArrayDeque(listOf("en")),
        )
        val transcriber = WhisperTranscriber(
            context = ctx,
            segmenter = testSegmenter(),
            backend = backend,
            modelLoaderFactory = FakeLoaderFactory(FakeLoader("/data/local/model.bin")),
        )
        transcriber.transcribeAudio(oneSegmentFrames().asFlow(), LanguageConfig("en")).toList()

        transcriber.release()
        transcriber.release()
        assertEquals(1, backend.releaseCount)
    }
}
