package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkSpacingTest {

    @Test
    fun `collapses the inter-word spaces vosk emits in japanese`() {
        val out = CjkSpacing("ja").collapse("俺 聞い た 情報 に よれ ば 当該 文書 は")
        assertEquals("俺聞いた情報によれば当該文書は", out)
    }

    @Test
    fun `collapses them in chinese`() {
        val out = CjkSpacing("zh").collapse("他 没有 设定 削减 数额 只是 表示")
        assertEquals("他没有设定削减数额只是表示", out)
    }

    @Test
    fun `leaves english alone`() {
        val text = "alloys are basically a mixture of two or more metals"
        assertEquals(text, CjkSpacing("en").collapse(text))
    }

    @Test
    fun `keeps the spaces that belong around latin and digits`() {
        // The ja/zh references contain zero spaces between two CJK characters
        // and 22 next to Latin or digits, so only the former is an artefact.
        // Deleting the latter would corrupt exactly the tokens a reader leans
        // on, and the boundary is genuinely ambiguous in Japanese typography.
        assertEquals("USB メモリ", CjkSpacing("ja").collapse("USB メモリ"))
        assertEquals("2019 年", CjkSpacing("ja").collapse("2019 年"))
        assertEquals("COVID 19 の影響", CjkSpacing("ja").collapse("COVID 19 の 影響"))
    }

    @Test
    fun `collapses runs of several spaces`() {
        assertEquals("国境紛争", CjkSpacing("ja").collapse("国境   紛争"))
    }

    @Test
    fun `handles empty and space-only input`() {
        assertEquals("", CjkSpacing("ja").collapse(""))
        assertEquals(" ", CjkSpacing("ja").collapse(" "))
    }

    @Test
    fun `is a no-op for languages written with spaces`() {
        assertEquals("xin chào các bạn", CjkSpacing("vi").collapse("xin chào các bạn"))
    }
}
