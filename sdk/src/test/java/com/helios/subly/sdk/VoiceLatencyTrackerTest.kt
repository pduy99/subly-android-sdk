package com.helios.subly.sdk

import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.Caption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLatencyTrackerTest {

    private var nowMs = 0L
    private val lines = ArrayList<String>()
    private val tracker = VoiceLatencyTracker(log = { lines += it }, nanoTime = { nowMs * 1_000_000 })

    private fun frame(voiced: Boolean) =
        AudioFrame(ShortArray(160) { if (voiced) 3_000 else 10 }, 16_000, 1, nowMs, if (voiced) 3_000 else 10)

    private fun at(ms: Long, block: () -> Unit) {
        nowMs = ms
        block()
    }

    private fun partial(src: String, dst: String) = Caption(src, dst, isFinal = false, mode = Caption.CaptureMode.AUDIO)
    private fun final(src: String, dst: String) = Caption(src, dst, isFinal = true, mode = Caption.CaptureMode.AUDIO)

    private fun field(line: String, name: String) = Regex("$name=(\\S+)").find(line)!!.groupValues[1]

    @Test
    fun `times a sentence from its voice`() {
        at(0) { tracker.onFrame(frame(voiced = false)) }
        at(1_000) { tracker.onFrame(frame(voiced = true)) } // speech starts
        at(1_600) { tracker.onCaption(partial("Hello", "Hello")) } // source only
        at(2_500) { tracker.onCaption(partial("Hello there", "Xin chào")) }
        at(3_000) { tracker.onFrame(frame(voiced = true)) } // last voiced
        at(3_200) { tracker.onFrame(frame(voiced = false)) }
        at(3_800) { tracker.onAsrFinal("Hello there.") }
        at(5_000) { tracker.onCaption(final("Hello there.", "Xin chào bạn.")) }

        val line = lines.single()
        assertEquals("1.00", field(line, "voice_onset_s"))
        assertEquals("3.00", field(line, "voice_end_s"))
        assertEquals("600", field(line, "onset_to_text_ms"))
        assertEquals("1500", field(line, "onset_to_translation_ms"))
        assertEquals("800", field(line, "end_to_asr_final_ms"))
        assertEquals("1200", field(line, "asr_final_to_caption_ms"))
        assertEquals("2000", field(line, "end_to_caption_ms"))
    }

    @Test
    fun `the next sentence starts timing at its own voice, even before the previous caption`() {
        at(0) { tracker.onFrame(frame(voiced = true)) }
        at(1_000) { tracker.onAsrFinal("One sentence.") }
        at(1_500) { tracker.onFrame(frame(voiced = true)) } // second sentence starts
        at(2_000) { tracker.onCaption(final("One sentence.", "Một câu.")) }
        at(2_300) { tracker.onCaption(partial("Two", "Hai")) }
        at(3_000) { tracker.onAsrFinal("Two sentences.") }
        at(4_000) { tracker.onCaption(final("Two sentences.", "Hai câu.")) }

        assertEquals(2, lines.size)
        assertEquals("800", field(lines[1], "onset_to_text_ms"))
        assertEquals("1000", field(lines[1], "asr_final_to_caption_ms"))
    }

    @Test
    fun `a committed sentence still translating does not count as the open one's translation`() {
        at(0) { tracker.onFrame(frame(voiced = true)) }
        at(1_000) { tracker.onAsrFinal("One sentence.") }
        at(1_200) { tracker.onFrame(frame(voiced = true)) } // next sentence starts
        at(1_500) { tracker.onCaption(partial("One sentence.", "Một")) } // previous one streaming
        at(1_800) { tracker.onCaption(final("One sentence.", "Một câu.")) }
        at(2_000) { tracker.onCaption(partial("Two", "Two")) }
        at(2_700) { tracker.onCaption(partial("Two words", "Hai từ")) }
        at(3_000) { tracker.onAsrFinal("Two words.") }
        at(3_500) { tracker.onCaption(final("Two words.", "Hai từ.")) }

        assertEquals("1500", field(lines[1], "onset_to_translation_ms"))
    }

    @Test
    fun `merged sentences end on one caption`() {
        at(0) { tracker.onFrame(frame(voiced = true)) }
        at(1_000) { tracker.onAsrFinal("First part.") }
        at(2_000) { tracker.onFrame(frame(voiced = true)) }
        at(2_500) { tracker.onAsrFinal("Second part.") }
        at(4_000) { tracker.onCaption(final("First part. Second part.", "Phần một. Phần hai.")) }

        val line = lines.single()
        assertEquals("2", field(line, "merged"))
        assertEquals("2000", field(line, "end_to_caption_ms"))
        assertTrue(tracker.summary()!!.startsWith("latency_summary sentences=1"))
    }
}
