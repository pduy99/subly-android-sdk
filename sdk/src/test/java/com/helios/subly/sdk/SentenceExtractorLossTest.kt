package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import java.util.Locale
import org.junit.Test

class SentenceExtractorLossTest {

    /** Words in, words out: pooling may delay text but must never drop it. */
    @Test
    fun `unpunctuated finals survive extraction`() {
        val extractor = SentenceExtractor(Locale.ENGLISH)
        val finals = listOf(
            "wine and beer might be had in any quantity the only limitation being that not more than one bottle of wine or one quart of beer could be",
            "issued at one time no account was taken of the amount of liquors admitted in one day and debtors might practically have as much as they liked if",
            "they could only pay for it no attempt was made to check drunkenness beyond the penalty of shutting out friends from any ward in which a prisoner exceeded",
            "quarrelling among the debtors was not unfrequent blows were struck and fights often ensued for this and other acts of misconduct there was the discipline",
            "of the refractory ward or strong room on the debtor side bad cases were removed to a cell on the felon side and here they were locked in solitary",
            "confinement for three days at a time order throughout the debtor side was preserved and discipline maintained by a system open to grave abuses",
        )
        val out = mutableListOf<String>()
        for (f in finals) {
            extractor.append(f)
            out += extractor.extractCompleted()
        }
        extractor.drain().takeIf { it.isNotEmpty() }?.let { out += it }

        assertEquals(
            finals.joinToString(" ").split(" ").size,
            out.joinToString(" ").split(" ").filter { it.isNotEmpty() }.size,
        )
    }
}
