package com.deepseek.harness.idea.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnapshotDiffTest {

    private fun md5(s: String) = s

    @Test
    fun `detects modified new and deleted`() {
        val baseline = mapOf(
            "a.txt" to md5("a1"),
            "b.txt" to md5("b1"),
            "gone.txt" to md5("g1"),
        )
        val current = mapOf(
            "a.txt" to md5("a2"),      // modified
            "b.txt" to md5("b1"),      // unchanged
            "new.txt" to md5("n1"),    // new
        )
        val changes = SnapshotDiff.diff(baseline, current)
        val byPath = changes.associateBy { it.relativePath }

        assertEquals(SnapshotDiff.ChangeType.MODIFIED, byPath["a.txt"]?.type)
        assertEquals(SnapshotDiff.ChangeType.DELETED, byPath["gone.txt"]?.type)
        assertEquals(SnapshotDiff.ChangeType.NEW, byPath["new.txt"]?.type)
        assertEquals(null, byPath["b.txt"])
        assertEquals(3, changes.size)
    }

    @Test
    fun `no changes when identical`() {
        val m = mapOf("x.txt" to "v1", "y.txt" to "v2")
        assertEquals(0, SnapshotDiff.diff(m, m).size)
    }

    @Test
    fun `baseline md5 carried on modified and deleted`() {
        val baseline = mapOf("m.txt" to "old", "d.txt" to "was")
        val current = mapOf("m.txt" to "new")
        val changes = SnapshotDiff.diff(baseline, current).associateBy { it.relativePath }
        assertEquals("old", changes["m.txt"]?.baselineMd5)
        assertEquals("new", changes["m.txt"]?.currentMd5)
        assertEquals("was", changes["d.txt"]?.baselineMd5)
        assertEquals(null, changes["d.txt"]?.currentMd5)
    }

    @Test
    fun `new change carries current md5`() {
        val changes = SnapshotDiff.diff(emptyMap(), mapOf("n.txt" to "v")).associateBy { it.relativePath }
        assertEquals(null, changes["n.txt"]?.baselineMd5)
        assertEquals("v", changes["n.txt"]?.currentMd5)
    }

    @Test
    fun `sorted by type then path`() {
        val baseline = mapOf("z.txt" to "a", "a.txt" to "b")
        val current = mapOf("z.txt" to "a", "a.txt" to "c", "m.txt" to "n")
        val changes = SnapshotDiff.diff(baseline, current)
        // 排序：MODIFIED(0) < NEW(1) < DELETED(2)，同类型按路径；z.txt 无变化不在结果
        assertEquals(listOf("a.txt", "m.txt"), changes.map { it.relativePath })
    }
}
