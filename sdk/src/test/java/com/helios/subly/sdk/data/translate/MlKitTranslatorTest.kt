package com.helios.subly.sdk.data.translate

import com.helios.subly.sdk.domain.repository.DownloadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlKitTranslatorTest {

    private class FakeClient(val source: String, val target: String) : NmtClient {
        var ensured = false
        var ensureResult = true
        var closed = false
        val translated = mutableListOf<String>()

        override suspend fun ensureModel(): Boolean {
            ensured = true; return ensureResult
        }

        override suspend fun translate(text: String): String {
            translated += text
            return "[$source->$target]$text"
        }

        override fun close() { closed = true }
    }

    private class FakeFactory : NmtClientFactory {
        val created = mutableListOf<FakeClient>()
        var failNextEnsure = false

        override fun create(sourceBcp47: String, targetBcp47: String): NmtClient {
            val c = FakeClient(sourceBcp47, targetBcp47)
            if (failNextEnsure) { c.ensureResult = false; failNextEnsure = false }
            created += c
            return c
        }
    }

    private val noopIdentifier = object : LanguageIdentifier {
        override suspend fun identify(text: String) = null
        override fun close() {}
    }

    @Test
    fun `caches per-pair clients across calls`() = runTest {
        val factory = FakeFactory()
        val translator = MlKitTranslator(factory, noopIdentifier, maxCacheSize = 4)

        translator.translate("a", "en", "es").toList()
        translator.translate("b", "en", "es").toList()
        translator.translate("c", "en", "es").toList()

        assertEquals(1, factory.created.size)
        assertEquals(listOf("a", "b", "c"), factory.created[0].translated)
    }

    @Test
    fun `evicts least-recently-used pair when cache full`() = runTest {
        val factory = FakeFactory()
        val translator = MlKitTranslator(factory, noopIdentifier, maxCacheSize = 2)

        translator.translate("a", "en", "es").toList() // [en->es]
        translator.translate("b", "en", "vi").toList() // [en->es, en->vi]
        translator.translate("c", "fr", "es").toList() // evicts en->es

        assertEquals(3, factory.created.size)
        // First-created (en->es) should be closed.
        assertTrue("en->es client should be closed", factory.created[0].closed)
        assertFalse("en->vi should still be live", factory.created[1].closed)
        assertFalse("fr->es should still be live", factory.created[2].closed)
    }

    @Test
    fun `pass-through when source equals target without consulting factory`() = runTest {
        val factory = FakeFactory()
        val translator = MlKitTranslator(factory, noopIdentifier)

        val result = translator.translate("hello", "en", "EN").toList()

        assertEquals(listOf("hello"), result)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `ensureModel surfaces failure as DownloadResult Failed`() = runTest {
        val factory = FakeFactory().also { it.failNextEnsure = true }
        val translator = MlKitTranslator(factory, noopIdentifier)

        val r = translator.ensureModel("en", "vi")

        assertTrue(r is DownloadResult.Failed)
    }

    @Test
    fun `ensureModel returns Success on identity pair without creating client`() = runTest {
        val factory = FakeFactory()
        val translator = MlKitTranslator(factory, noopIdentifier)

        assertEquals(DownloadResult.Success, translator.ensureModel("en", "en"))
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `release closes all cached clients and identifier`() = runTest {
        val factory = FakeFactory()
        var idClosed = false
        val identifier = object : LanguageIdentifier {
            override suspend fun identify(text: String) = null
            override fun close() { idClosed = true }
        }
        val translator = MlKitTranslator(factory, identifier)

        translator.translate("a", "en", "es").toList()
        translator.translate("b", "en", "vi").toList()
        translator.release()

        assertTrue(factory.created.all { it.closed })
        assertTrue(idClosed)
    }

    @Test
    fun `translate passes through on backend error`() = runTest {
        val factory = NmtClientFactory { _, _ ->
            object : NmtClient {
                override suspend fun ensureModel() = true
                override suspend fun translate(text: String): String = error("boom")
                override fun close() {}
            }
        }
        val translator = MlKitTranslator(factory, noopIdentifier)

        val result = translator.translate("hi", "en", "es").toList()

        assertEquals(listOf("hi"), result)
    }

    @Test
    fun `identifySource delegates to identifier`() = runTest {
        val identifier = object : LanguageIdentifier {
            override suspend fun identify(text: String) = if (text == "ola") "pt" else null
            override fun close() {}
        }
        val translator = MlKitTranslator(FakeFactory(), identifier)

        assertEquals("pt", translator.identifySource("ola"))
        assertNull(translator.identifySource("???"))
    }
}
