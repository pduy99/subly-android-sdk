package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkSentenceSplitterTest {

    private val zh = CjkSentenceSplitter("zh")

    @Test
    fun `short sentences are untouched`() {
        assertEquals(listOf("你是这个卖家？"), zh.split("你是这个卖家？"))
    }

    @Test
    fun `long sentences split at clause punctuation`() {
        val s = "那我跟你坐一起吃吗，到时候也可以跟你妈坐一起，我妈应该会来。"
        assertEquals(
            listOf("那我跟你坐一起吃吗，到时候也可以跟你妈坐一起，", "我妈应该会来。"),
            zh.split(s),
        )
    }

    @Test
    fun `an unpunctuated run-on is halved`() {
        val s = "你是的还是是真品也等会儿你就说你是我女儿出生都都会什么叫我声音起来啦拉卡拉今天就可以的家里是叶脉小时吧"
        val parts = zh.split(s)
        assertEquals(2, parts.size)
        assertEquals(s, parts.joinToString(""))
    }

    @Test
    fun `an unpunctuated sentence under the hard cap is kept whole`() {
        val s = "带上我跟你们一起吃吗到时候也会给你妈做一些我们应该会"
        assertEquals(listOf(s), zh.split(s))
    }

    @Test
    fun `other languages are never split`() {
        val s = "This is a long English sentence, with commas, that goes on well past twenty-eight characters."
        assertEquals(listOf(s), CjkSentenceSplitter("en").split(s))
    }
}
