package com.deepseek.harness.idea.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentSelectionQueueTest {

    @Test
    fun `latest returns most recent push`() {
        val q = SentSelectionQueue()
        q.push("A.kt", "kotlin", "aaa")
        q.push("B.kt", "kotlin", "bbb")
        val latest = q.latest()
        assertNotNull(latest)
        assertEquals("B.kt", latest!!.filePath)
        assertEquals("bbb", latest.selection)
        assertEquals(2, q.size())
    }

    @Test
    fun `empty queue returns null`() {
        val q = SentSelectionQueue()
        assertNull(q.latest())
        assertEquals(0, q.size())
    }

    @Test
    fun `ring capacity keeps newest ten`() {
        val q = SentSelectionQueue(maxEntries = 3)
        for (i in 1..10) q.push("F$i.kt", null, "sel$i")
        assertEquals(3, q.size())
        assertEquals("F10.kt", q.latest()!!.filePath)
    }

    @Test
    fun `oversize selection is truncated with marker`() {
        val q = SentSelectionQueue(maxBytes = 1024)
        val big = "x".repeat(5000)
        val id = q.push("Big.kt", null, big)
        val item = q.latest()!!
        assertTrue(item.selection.length < big.length, "should truncate")
        assertTrue(item.selection.contains("已截断"), "should note truncation")
        assertEquals(id, item.id)
    }

    @Test
    fun `ids are unique and sequential`() {
        val q = SentSelectionQueue()
        val id1 = q.push("A.kt", null, "1")
        val id2 = q.push("A.kt", null, "2")
        assertEquals("s1", id1)
        assertEquals("s2", id2)
    }
}
