package com.helios.subly.asr.vosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the language -> model resolver (no Android / no JNI). */
class VoskModelsTest {

    @Test
    fun `primary subtag resolves`() {
        assertEquals(VoskModels.EN, VoskModels.forLanguage("en"))
        assertEquals(VoskModels.FR, VoskModels.forLanguage("fr"))
        assertEquals(VoskModels.VI, VoskModels.forLanguage("vi"))
    }

    @Test
    fun `region tag falls back to primary subtag`() {
        assertEquals(VoskModels.EN, VoskModels.forLanguage("en-US"))
        assertEquals(VoskModels.FR, VoskModels.forLanguage("fr-CA"))
    }

    @Test
    fun `exact region tag wins over primary subtag`() {
        // en-in has its own model; plain/other en regions map to en-us.
        assertEquals(VoskModels.EN_IN, VoskModels.forLanguage("en-IN"))
        assertEquals(VoskModels.EN, VoskModels.forLanguage("en-GB"))
    }

    @Test
    fun `vosk-specific aliases resolve`() {
        assertEquals(VoskModels.ZH, VoskModels.forLanguage("zh"))
        assertEquals(VoskModels.ZH, VoskModels.forLanguage("zh-CN"))
        assertEquals(VoskModels.VI, VoskModels.forLanguage("vn"))
    }

    @Test
    fun `case is normalized`() {
        assertEquals(VoskModels.DE, VoskModels.forLanguage("DE"))
    }

    @Test
    fun `unsupported or blank language is null`() {
        assertNull(VoskModels.forLanguage("xx"))
        assertNull(VoskModels.forLanguage(""))
        assertNull(VoskModels.forLanguage("   "))
    }

    @Test
    fun `zip url is built from base and dir name`() {
        assertEquals(
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            VoskModels.EN.zipUrl,
        )
    }

    @Test
    fun `supported languages are exposed and unique`() {
        val tags = VoskModels.supportedLanguageTags()
        assertTrue("en" in tags)
        assertEquals(tags.size, tags.distinct().size)
    }
}
