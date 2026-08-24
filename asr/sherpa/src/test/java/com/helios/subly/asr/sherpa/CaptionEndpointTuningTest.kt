package com.helios.subly.asr.sherpa

import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEndpointTuningTest {

    @Test
    fun `no rule can end a caption without a pause`() {
        // The regression this guards: a rule with zero trailing silence ends
        // the caption on elapsed time alone, which in continuous speech falls
        // mid-word ("refractory" -> "refractor" + "y ward"). Every rule must
        // wait for a gap between words.
        for (rule in CaptionEndpointTuning.ALL) {
            assertTrue(
                "rule $rule can fire at zero trailing silence and will cut mid-word",
                rule.minTrailingSilenceSec > 0f,
            )
        }
    }

    @Test
    fun `the run-on cap still bounds caption length`() {
        // It must remain the loosest rule on silence, or it never bounds
        // anything the normal pause rule has not already ended.
        assertTrue(
            CaptionEndpointTuning.RUN_ON_CAP.minTrailingSilenceSec <
                CaptionEndpointTuning.SPEECH_PAUSE.minTrailingSilenceSec,
        )
        assertTrue(CaptionEndpointTuning.RUN_ON_CAP.minUtteranceLengthSec > 0f)
    }

    @Test
    fun `the run-on cap requires speech before it fires`() {
        // Without this it competes with the silence guard during a long
        // pause and ends an utterance that never contained any words.
        assertTrue(CaptionEndpointTuning.RUN_ON_CAP.mustContainNonSilence)
    }
}
