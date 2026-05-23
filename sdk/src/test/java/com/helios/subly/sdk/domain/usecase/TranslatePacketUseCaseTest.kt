package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.DownloadResult
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatePacketUseCaseTest {

    /** Fake that mirrors text with a tagging convention so assertions are trivial. */
    private class FakeTranslator(
        private val identified: String? = null,
        private val translate: (String, String, String) -> String = { t, s, tg -> "[$s->$tg]$t" },
    ) : TranslatorRepository {
        override fun translate(text: String, sourceBcp47: String, targetBcp47: String): Flow<String> =
            flowOf(this.translate.invoke(text, sourceBcp47, targetBcp47))

        override suspend fun ensureModel(sourceBcp47: String, targetBcp47: String) =
            DownloadResult.Success

        override suspend fun identifySource(text: String): String? = identified

        override fun release() {}
    }

    private fun packet(
        text: String,
        source: String? = "auto",
        target: String = "es",
    ) = TranslationPacket(
        text = text,
        sourceLanguageCode = source,
        targetLanguageCode = target,
        timestampMs = 0L,
        source = TranslationPacket.Source.AUDIO,
        isFinal = true,
    )

    @Test
    fun `pass-through when detected source equals requested target`() = runTest {
        val usecase = TranslatePacketUseCase(FakeTranslator(identified = "es"))

        val out = usecase(
            listOf(packet("hola", source = "auto")).asFlow(),
            LanguageConfig("es"),
        ).toList()

        assertEquals(1, out.size)
        assertEquals("hola", out[0].text)
        assertEquals("es", out[0].sourceLanguageCode)
        assertEquals("es", out[0].targetLanguageCode)
    }

    @Test
    fun `translates when source differs from target`() = runTest {
        val usecase = TranslatePacketUseCase(FakeTranslator(identified = "en"))

        val out = usecase(
            listOf(packet("hello", source = "auto")).asFlow(),
            LanguageConfig("vi"),
        ).toList()

        assertEquals(1, out.size)
        assertEquals("[en->vi]hello", out[0].text)
        assertEquals("en", out[0].sourceLanguageCode)
        assertEquals("vi", out[0].targetLanguageCode)
    }

    @Test
    fun `degrades when source language cannot be identified`() = runTest {
        val usecase = TranslatePacketUseCase(FakeTranslator(identified = null))

        val out = usecase(
            listOf(packet("???", source = "auto")).asFlow(),
            LanguageConfig("es"),
        ).toList()

        assertEquals(1, out.size)
        // Original text preserved; target rewritten to the unknown source tag.
        assertEquals("???", out[0].text)
        assertEquals("auto", out[0].targetLanguageCode)
    }

    @Test
    fun `degrades when translator passes through (failure)`() = runTest {
        // translate() returns original text -> translator effectively failed.
        val usecase = TranslatePacketUseCase(
            FakeTranslator(identified = "en", translate = { t, _, _ -> t }),
        )

        val out = usecase(
            listOf(packet("hello", source = "auto")).asFlow(),
            LanguageConfig("es"),
        ).toList()

        assertEquals(1, out.size)
        assertEquals("hello", out[0].text)
        // Plan: degraded packet's target should equal source language code.
        assertEquals("en", out[0].sourceLanguageCode)
        assertEquals("en", out[0].targetLanguageCode)
    }

    @Test
    fun `honours explicit source language without identifying`() = runTest {
        // identified=null would otherwise force degrade; but explicit non-auto
        // source skips the identifier entirely.
        val usecase = TranslatePacketUseCase(FakeTranslator(identified = null))

        val out = usecase(
            listOf(packet("bonjour", source = "fr")).asFlow(),
            LanguageConfig("en"),
        ).toList()

        assertEquals("[fr->en]bonjour", out[0].text)
        assertEquals("fr", out[0].sourceLanguageCode)
        assertEquals("en", out[0].targetLanguageCode)
    }

    @Test
    fun `skips blank packets`() = runTest {
        val usecase = TranslatePacketUseCase(FakeTranslator(identified = "en"))

        val out = usecase(
            listOf(packet(""), packet("   "), packet("hi")).asFlow(),
            LanguageConfig("es"),
        ).toList()

        assertEquals(1, out.size)
        assertTrue(out[0].text.contains("hi"))
    }
}
