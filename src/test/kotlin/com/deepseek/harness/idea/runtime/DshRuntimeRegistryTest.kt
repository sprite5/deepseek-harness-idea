package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 并发上限逻辑测试（Step 5 FR-02.6）。
 * 注：DshRuntimeRegistry 是 applicationService，需要 Application 实例；
 * 此处直接实例化以测试名额登记/释放/超限语义。
 */
class DshRuntimeRegistryTest {

    @Test
    fun `acquire up to limit then reject`() {
        val registry = DshRuntimeRegistry()
        val handle = Any()
        // 注册前 3 个项目
        assertTrue(registry.tryAcquire("proj-1", handle))
        assertTrue(registry.tryAcquire("proj-2", handle))
        assertTrue(registry.tryAcquire("proj-3", handle))
        assertEquals(3, registry.runningCount())
        // 第 4 个被拒绝
        assertFalse(registry.tryAcquire("proj-4", handle))
        assertEquals(3, registry.runningCount())
        assertFalse(registry.isRunning("proj-4"))
    }

    @Test
    fun `release frees a slot`() {
        val registry = DshRuntimeRegistry()
        val handle = Any()
        registry.tryAcquire("a", handle)
        registry.tryAcquire("b", handle)
        registry.release("a")
        assertEquals(1, registry.runningCount())
        assertFalse(registry.isRunning("a"))
        // 释放后可再登记新项目
        assertTrue(registry.tryAcquire("c", handle))
        assertEquals(2, registry.runningCount())
    }

    @Test
    fun `same project is idempotent`() {
        val registry = DshRuntimeRegistry()
        val handle = Any()
        assertTrue(registry.tryAcquire("dup", handle))
        assertTrue(registry.tryAcquire("dup", handle)) // 幂等：已有实例
        assertEquals(1, registry.runningCount())
        registry.release("dup")
        assertEquals(0, registry.runningCount())
    }
}
