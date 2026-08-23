package com.deepseek.harness.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExplainLogComposerTest {

    private val prefix = "PREFIX-指令"

    @Test
    fun `buildMessage joins prefix and log with blank line`() {
        val msg = ExplainLogComposer.buildMessage(prefix, "line1\nline2")
        assertEquals("PREFIX-指令\n\nline1\nline2", msg)
    }

    @Test
    fun `buildMessage truncates oversized log and appends note`() {
        val big = "x".repeat(ExplainLogComposer.MAX_BYTES + 1000)
        val msg = ExplainLogComposer.buildMessage(prefix, big)
        assertTrue(msg.startsWith("PREFIX-指令\n\n"))
        assertTrue(msg.endsWith("…(已截断，超出 64KB)"))
        // 截断后总字节数不超过上限（指令 + 空行 + 截断正文 + 注明）
        assertTrue(msg.toByteArray(Charsets.UTF_8).size <= ExplainLogComposer.MAX_BYTES + prefix.toByteArray(Charsets.UTF_8).size + 128)
    }

    @Test
    fun `buildMessage keeps under-limit log intact`() {
        val small = "log\n".repeat(100)
        val msg = ExplainLogComposer.buildMessage(prefix, small)
        assertEquals("PREFIX-指令\n\n$small", msg)
    }

    @Test
    fun `buildMessage returns empty for blank log`() {
        assertEquals("", ExplainLogComposer.buildMessage(prefix, ""))
        assertEquals("", ExplainLogComposer.buildMessage(prefix, "   \n\t"))
    }
}
