package com.helios.subly.sdk

import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.Caption
import java.util.Locale
import kotlin.math.sqrt

/**
 * Times each caption against the voice it came from, as the viewer
 * experiences it: from when a sentence starts being spoken to when its first
 * words and first translation appear, and from when it stops being spoken to
 * when its final translation appears.
 *
 * Fed by the session: every audio frame ([onFrame]), every sentence handed to
 * translation ([onAsrFinal]) and every caption published ([onCaption]). One
 * `sentence_latency` line per final caption goes to [log]; [summary] gives
 * percentiles over the run. All times are wall clock (frames are timed when
 * they reach the session), relative to the first frame.
 *
 * Voice is detected by frame RMS, which is enough to find where speech starts
 * and stops in captured media; it is not a speech detector.
 *
 * Thread-safe: frames arrive on the recogniser's thread, captions on the
 * session's.
 */
internal class VoiceLatencyTracker(
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD,
) {
    private var startNanos = -1L
    private var lastVoicedNanos = -1L

    /** The sentence being spoken: everything since the last ASR final. */
    private var open = Open()

    /** Sentences handed to translation whose final caption is still to come. */
    private val pending = ArrayDeque<Sentence>()

    private var index = 0
    private val samples = mutableMapOf<String, MutableList<Long>>()

    private class Open {
        var onsetNanos = -1L
        var firstTextNanos = -1L
        var firstTranslationNanos = -1L
    }

    private class Sentence(
        val text: String,
        val onsetNanos: Long,
        val endNanos: Long,
        val asrFinalNanos: Long,
        val firstTextNanos: Long,
        val firstTranslationNanos: Long,
    )

    @Synchronized
    fun onFrame(frame: AudioFrame) {
        val now = nanoTime()
        if (startNanos < 0) startNanos = now
        if (rms(frame.pcm) < rmsThreshold) return
        lastVoicedNanos = now
        if (open.onsetNanos < 0) open.onsetNanos = now
    }

    /** A sentence was committed by the recogniser and handed to translation. */
    @Synchronized
    fun onAsrFinal(text: String) {
        val now = nanoTime()
        pending += Sentence(
            text = normalise(text),
            onsetNanos = open.onsetNanos,
            endNanos = lastVoicedNanos,
            asrFinalNanos = now,
            firstTextNanos = open.firstTextNanos,
            firstTranslationNanos = open.firstTranslationNanos,
        )
        open = Open()
    }

    @Synchronized
    fun onCaption(caption: Caption) {
        val now = nanoTime()
        if (!caption.isFinal) {
            if (caption.originalText.isBlank()) return
            // A sentence already committed can still be streaming its
            // translation while the next one is spoken; that caption belongs
            // to the committed sentence, not the open one.
            val source = normalise(caption.originalText)
            if (source.isNotEmpty() && pending.any { it.text.contains(source) || source.contains(it.text) }) return
            if (open.firstTextNanos < 0) open.firstTextNanos = now
            // A finals-only path fills a partial's translation with its
            // source text; that is not a translation.
            val translated = caption.translatedText.isNotBlank() && caption.translatedText != caption.originalText
            if (translated && open.firstTranslationNanos < 0) open.firstTranslationNanos = now
            return
        }

        // A translator may merge several committed sentences into one
        // caption: they all end here, timed from the first and the last.
        if (pending.isEmpty()) return
        val captionText = normalise(caption.originalText)
        val matched = arrayListOf(pending.removeFirst())
        while (pending.isNotEmpty() && pending.first().text.length >= MIN_MERGE_MATCH &&
            captionText.contains(pending.first().text)
        ) {
            matched += pending.removeFirst()
        }
        val first = matched.first()
        val last = matched.last()
        index++

        fun since(from: Long, to: Long) = if (from < 0 || to < 0) null else (to - from) / 1_000_000
        val values = linkedMapOf(
            // Speech start -> words on screen. What makes captions feel live.
            "onset_to_text_ms" to since(first.onsetNanos, first.firstTextNanos.takeIf { it >= 0 } ?: now),
            "onset_to_translation_ms" to since(first.onsetNanos, first.firstTranslationNanos.takeIf { it >= 0 } ?: now),
            // Speech end -> sentence committed, then -> translated.
            "end_to_asr_final_ms" to since(last.endNanos, last.asrFinalNanos),
            "asr_final_to_caption_ms" to since(last.asrFinalNanos, now),
            "end_to_caption_ms" to since(last.endNanos, now),
        )
        values.forEach { (name, ms) -> if (ms != null && ms >= 0) samples.getOrPut(name) { ArrayList() } += ms }
        log(
            "sentence_latency n=$index voice_onset_s=${seconds(first.onsetNanos)} " +
                "voice_end_s=${seconds(last.endNanos)} caption_s=${seconds(now)} " +
                values.entries.joinToString(" ") { (k, v) -> "$k=${v ?: "na"}" } +
                " merged=${matched.size} src_len=${caption.originalText.length}"
        )
    }

    /** One line of p50/p90/max per measure, for the end of a run. */
    @Synchronized
    fun summary(): String? {
        if (samples.isEmpty()) return null
        return "latency_summary sentences=$index " + samples.entries.joinToString(" ") { (name, values) ->
            val sorted = values.sorted()
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            "$name=p50:${pct(0.5)},p90:${pct(0.9)},max:${sorted.last()}"
        }
    }

    private fun seconds(nanos: Long): String =
        if (nanos < 0 || startNanos < 0) "na" else String.format(Locale.US, "%.2f", (nanos - startNanos) / 1e9)

    private fun normalise(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size)
    }

    companion object {
        /** About -36 dBFS: above a media mix's noise floor, below quiet syllables. */
        const val DEFAULT_RMS_THRESHOLD = 500.0

        /** Shorter sentences ("yes") are too often inside unrelated ones to count as merged. */
        const val MIN_MERGE_MATCH = 4
    }
}
