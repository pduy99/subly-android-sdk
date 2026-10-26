package com.helios.subly.asr.sherpa

import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaAutoTranscriberTest {

    private class FakeAsr : SublyAsr {
        val prepared = mutableListOf<String>()
        var releases = 0
        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = emptyFlow()
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> {
            prepared += config.source
            return flowOf(ModelPrepState.Ready)
        }
        override fun release() {
            releases++
        }
        override fun supportedLanguages() = emptyList<String>()
    }

    @Test
    fun `every supported language has a recogniser that can load`() {
        for (language in listOf("en", "zh", "ja", "es", "vi")) {
            val model = SherpaOnnxModel.bestFor(language)
            assertNotNull(language, model)
            model!!
            assertTrue(language in model.languages)
            when (model.kind) {
                SherpaModelKind.ASR -> assertEquals(model, SherpaOnnxModel.forLanguage(language))
                SherpaModelKind.OFFLINE_ASR -> assertNotNull(model.offlineFamily)
                else -> error("$language maps to a ${model.kind}")
            }
            assertTrue(model.downloadFiles.isNotEmpty() && model.downloadBaseUrl.startsWith("https://"))
        }
        assertEquals(SherpaOnnxModel.STREAMING_ZIPFORMER_ZH, SherpaOnnxModel.bestFor("zh-CN"))
    }

    @Test
    fun `routes each language to its mode and frees the other`() = runBlocking {
        val streaming = FakeAsr()
        val offline = FakeAsr()
        val asr = SherpaAutoTranscriber(streaming, offline)

        assertEquals(ModelPrepState.Ready, asr.prepareModel(LanguageConfig("en", "vi")).last())
        assertEquals(ModelPrepState.Ready, asr.prepareModel(LanguageConfig("es", "vi")).last())
        assertEquals(0, streaming.releases)
        asr.prepareModel(LanguageConfig("vi", "en")).last()
        assertEquals(1, streaming.releases)
        asr.prepareModel(LanguageConfig("ja", "en")).last()
        assertEquals(0, offline.releases)
        asr.prepareModel(LanguageConfig("zh", "en")).last()
        assertEquals(1, offline.releases)

        assertEquals(listOf("en", "es", "zh"), streaming.prepared)
        assertEquals(listOf("vi", "ja"), offline.prepared)
    }

    @Test
    fun `an unsupported language is an error, not a crash`() = runBlocking {
        val asr = SherpaAutoTranscriber(FakeAsr(), FakeAsr())
        assertTrue(asr.prepareModel(LanguageConfig("fr", "en")).last() is ModelPrepState.Error)
    }
}
