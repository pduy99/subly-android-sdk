package com.helios.subly.sdk.translation

import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.api.TranslationSegment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HandoverTranslatorTest {

    private class Fast(private val serves: Boolean = true) : SublyTranslator {
        var released = false
        override suspend fun translate(text: String) = "fast:$text"
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
            emit(ModelPrepState.Preparing(0.5f))
            emit(if (serves) ModelPrepState.Ready else ModelPrepState.Error(IllegalArgumentException("pair")))
        }
        override fun release() { released = true }
        override fun supportedLanguages() = listOf("en", "zh", "vi", "xx")
    }

    private class Primary(private val loadMs: Long) : SublyTranslator, IncrementalTranslator {
        val translated = CopyOnWriteArrayList<String>()
        var released = false
        override val translatesPartials = false
        override suspend fun translate(text: String): String {
            translated += text
            return "llm:$text"
        }
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
            emit(ModelPrepState.Preparing(0.1f))
            delay(loadMs)
            emit(ModelPrepState.Ready)
        }
        override fun openSegment(): TranslationSegment = object : TranslationSegment {
            private val outs = mutableListOf<String>()
            override val source = ""
            override val translation get() = outs.joinToString(" ")
            override fun append(sourceDelta: String): Flow<String> = flow {
                val out = "llm:$sourceDelta"
                outs += out
                emit(out)
            }
            override fun close() = Unit
        }
        override fun release() { released = true }
        override fun supportedLanguages() = listOf("en", "zh", "vi")
    }

    private val pair = LanguageConfig("zh", "vi")

    private fun HandoverTranslator<Primary>.awaitPrimary() = runBlocking {
        withTimeout(5_000) { primaryState.first { it is ModelPrepState.Ready } }
        withTimeout(5_000) { while (!isUsingPrimary) delay(10) }
    }

    @Test
    fun `ready as soon as the fast engine is, without waiting for the primary`() = runBlocking {
        val handover = HandoverTranslator(Fast(), Primary(loadMs = 2_000))
        val start = System.nanoTime()
        val states = handover.prepareModel(pair).toList()
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", (System.nanoTime() - start) / 1_000_000 < 1_000)
        assertEquals(ModelPrepState.Ready, states.last())
        assertFalse(handover.isUsingPrimary)
        assertEquals("fast:你好", handover.translate("你好"))
        handover.release()
    }

    @Test
    fun `hands over to the primary once it is loaded and warmed up`() {
        val primary = Primary(loadMs = 200)
        val handover = HandoverTranslator(Fast(), primary)
        runBlocking { handover.prepareModel(pair).toList() }
        handover.awaitPrimary()

        assertEquals(listOf("Hello."), primary.translated) // the warm-up
        val segment = handover.openSegment()
        val out = runBlocking { segment.append("你好").toList().last() }
        assertEquals("llm:你好", out)
        handover.release()
    }

    @Test
    fun `a sentence started on the fast engine finishes on it`() = runBlocking {
        val primary = Primary(loadMs = 200)
        val handover = HandoverTranslator(Fast(), primary)
        handover.prepareModel(pair).toList()
        val opened = handover.openSegment() // before the hand-over
        handover.awaitPrimary()
        assertEquals("fast:你好", opened.append("你好").toList().last())
        handover.release()
    }

    @Test
    fun `when the fast engine cannot serve the pair, preparation waits for the primary`() = runBlocking {
        val handover = HandoverTranslator(Fast(serves = false), Primary(loadMs = 200))
        val states = handover.prepareModel(pair).toList()
        assertEquals(ModelPrepState.Ready, states.last())
        assertTrue(states.none { it is ModelPrepState.Error })
        assertTrue(handover.isUsingPrimary)
        assertEquals("llm:你好", handover.translate("你好"))
        handover.release()
    }

    @Test
    fun `release releases both engines`() {
        val fast = Fast()
        val primary = Primary(loadMs = 10_000)
        val handover = HandoverTranslator(fast, primary)
        runBlocking { handover.prepareModel(pair).toList() }
        handover.release()
        assertTrue(fast.released)
        assertTrue(primary.released)
    }
}
