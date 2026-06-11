package com.helios.subly.asr.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cases taken from real on-device benchmark logs (SublyBench, base-q5_1):
 * whisper repetition-loop hallucinations that must be collapsed, and
 * legitimate text that must pass through untouched.
 */
class RepetitionFilterTest {

    @Test
    fun `clean sentence is unchanged`() {
        val text = "I am learning English every day at home with full focus and strong motivation."
        assertEquals(text, RepetitionFilter.collapse(text))
    }

    @Test
    fun `phrase loop with trailing partial repeat collapses to first occurrence`() {
        val text = "If you do not want to, if you do not want to, if you do not want to, " +
                "if you do not want to, if you do not want to, if you"
        assertEquals("If you do not want to,", RepetitionFilter.collapse(text))
    }

    @Test
    fun `single word loop collapses`() {
        val text = "Today! Today! Today! Today! Today! Today! Today! Today! Today!"
        assertEquals("Today!", RepetitionFilter.collapse(text))
    }

    @Test
    fun `sentence loop collapses keeping punctuation of first occurrence`() {
        val text = "And without memorizing grammar rules. And without memorizing grammar rules. " +
                "And without memorizing grammar rules. And without memorizing grammar rules. And without"
        assertEquals("And without memorizing grammar rules.", RepetitionFilter.collapse(text))
    }

    @Test
    fun `degenerate character run is dropped entirely`() {
        val text = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"
        assertEquals("", RepetitionFilter.collapse(text))
    }

    @Test
    fun `loop not anchored at start collapses only the looped part`() {
        val text = "The first and most important step is to understand the most important step " +
                "is to understand the most important step is to understand the basics"
        assertEquals(
            "The first and most important step is to understand the basics",
            RepetitionFilter.collapse(text)
        )
    }

    @Test
    fun `two repetitions are preserved - people legitimately repeat sentences`() {
        val text = "A sentence that sounds natural, but may look difficult at first. " +
                "A sentence that sounds natural, but may look difficult at first."
        assertEquals(text, RepetitionFilter.collapse(text))
    }

    @Test
    fun `double words are preserved`() {
        val text = "it is very very good and so so nice"
        assertEquals(text, RepetitionFilter.collapse(text))
    }

    @Test
    fun `triple single word collapses`() {
        assertEquals("no never give up", RepetitionFilter.collapse("no no no never give up"))
    }

    @Test
    fun `truncated loop-tail word fragment is dropped`() {
        // Real case (raw_len=221): token cap cut the loop mid-word.
        val text = "and without memorizing grammar rules. and without memorizing grammar rules. " +
                "and without memorizing grammar rules. and without memorizing grammar rules. " +
                "and without memor"
        assertEquals("and without memorizing grammar rules.", RepetitionFilter.collapse(text))
    }

    @Test
    fun `fragment immediately after loop blocks is dropped`() {
        assertEquals("stop the car.", RepetitionFilter.collapse("stop the car. stop the car. stop the car. sto"))
    }

    @Test
    fun `non-matching word after loop survives`() {
        assertEquals(
            "go home now great",
            RepetitionFilter.collapse("go home now go home now go home now great")
        )
    }

    // ---- CJK (unspaced) cases from real Japanese benchmark logs ----------

    @Test
    fun `japanese phrase loop collapses at character level`() {
        val text = "よろしくお願いします".repeat(5) + "よろしく".repeat(3)
        assertEquals("よろしくお願いします", RepetitionFilter.collapse(text))
    }

    @Test
    fun `japanese loop with truncated tail collapses`() {
        assertEquals(
            "おいくつですか?",
            RepetitionFilter.collapse("おいくつですか?".repeat(4) + "おいく")
        )
        assertEquals(
            "同じです!",
            RepetitionFilter.collapse("同じです!".repeat(6) + "同")
        )
    }

    @Test
    fun `japanese multi-phrase cycle collapses`() {
        assertEquals(
            "本当ですか?カイムラさんも?",
            RepetitionFilter.collapse("本当ですか?カイムラさんも?".repeat(3))
        )
    }

    @Test
    fun `japanese loop with non-repeating prefix keeps the prefix`() {
        assertEquals(
            "私は私のお客",
            RepetitionFilter.collapse("私は私の" + "お客の".repeat(8) + "お")
        )
    }

    @Test
    fun `legitimate japanese text is unchanged`() {
        val text = "はい今日から働くことになりました佐藤優人"
        assertEquals(text, RepetitionFilter.collapse(text))
        assertEquals("おはようございます。", RepetitionFilter.collapse("おはようございます。"))
        // Doubling (2 reps) preserved, same rule as the word-level pass.
        assertEquals("どうぞどうぞよろしくね", RepetitionFilter.collapse("どうぞどうぞよろしくね"))
    }

    @Test
    fun `long non-repetitive latin word is unchanged`() {
        assertEquals(
            "internationalization",
            RepetitionFilter.collapse("internationalization")
        )
    }

    @Test
    fun `blank input returns empty`() {
        assertEquals("", RepetitionFilter.collapse("   "))
    }

    @Test
    fun `comparison ignores case and punctuation but output keeps original`() {
        val text = "Stop. stop, STOP! stop"
        assertEquals("Stop.", RepetitionFilter.collapse(text))
    }
}
