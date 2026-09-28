package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 纯逻辑测试：DshHomeManager 的 provider 条目（cordis.patch.yml）同步算法
 * （方案 A'，dsh 0.1.7 适配：llm-pi-ai 配置 = `- id: llm-pi-ai` / `- id: agent-default-model` 条目）。
 *
 * 测试范围（不依赖 PathManager / ApplicationManager）：
 * - [DshHomeManager.splitTopLevelItems]：顶层条目切块 / 嵌套 `- id:` 不误切 / 空文件 / CRLF
 * - [DshHomeManager.upsertPatchEntry]：替换 / 追加 / 幂等 / 删除 / 头注释保留 / 引号 id
 *
 * [DshHomeManager.syncProvidersToGlobal] / [DshHomeManager.mergeGlobalProvidersInto] 依赖
 * PathManager.getConfigDir() → 测试环境无 IDE 上下文，端到端行为由工具窗口集成测试覆盖。
 *
 * 函数为 `internal`：测试与主源集同 module，Kotlin `internal` 可见性允许直接调用。
 */
class DshSettingsSyncTest {

    private val manager: DshHomeManager =
        DshHomeManager::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()

    // ───────── splitTopLevelItems ─────────

    @Test
    fun `split extracts top-level entries from real-world patch`() {
        val text = """
            # 本层由插件通过 --patch 覆盖，不在此修改
            - id: dsh-client-ui-settings-general
              name: "@deepseek-ai/dsh-client-ui-settings-general"
              config:
                welcomeNoticeVersion: 2026-08-13.1
            - id: llm-pi-ai
              name: "@deepseek-ai/dsh-llm-pi-ai"
              config:
                providers:
                  xiaomi-token-plan-cn:
                    apiKeyEnv: XIAOMI_TOKEN_PLAN_CN_API_KEY
                    models:
                      - id: mimo-v2.6-flash
                        name: mimo-v2.6-flash
            - id: agent-default-model
              name: "@deepseek-ai/dsh-agent-default-model"
              config:
                provider: xiaomi-token-plan-cn
        """.trimIndent() + "\n"

        val items = manager.splitTopLevelItems(text)
        assertEquals(3, items.size)
        assertTrue(items[1].startsWith("- id: llm-pi-ai"))
        assertTrue(items[1].contains("mimo-v2.6-flash"), "nested model entries stay inside the item")
        assertTrue(items[1].contains("apiKeyEnv"))
        assertTrue(items[2].startsWith("- id: agent-default-model"))
        // 头注释不属于任何条目
        assertFalse(items[0].contains("--patch"))
    }

    @Test
    fun `split does not treat indented dashes as new entries`() {
        val text = "- id: llm-pi-ai\n  config:\n    models:\n      - id: a\n      - id: b\n- id: x\n"
        val items = manager.splitTopLevelItems(text)
        assertEquals(2, items.size)
        assertEquals("- id: x", items[1])
    }

    @Test
    fun `split handles empty list placeholder and blank lines`() {
        assertEquals(0, manager.splitTopLevelItems("# comment\n[]\n").size)
        assertEquals(0, manager.splitTopLevelItems("[]\n").size)
        assertEquals(2, manager.splitTopLevelItems("- id: a\n  k: v\n\n- id: b\n").size)
    }

    @Test
    fun `split handles CRLF line endings`() {
        val text = "- id: llm-pi-ai\r\n  name: x\r\n- id: agent-default-model\r\n  k: v\r\n"
        val items = manager.splitTopLevelItems(text)
        assertEquals(2, items.size)
        items.forEach { assertFalse(it.contains('\r')) }
    }

    // ───────── upsertPatchEntry ─────────

    @Test
    fun `upsert replaces existing entry and keeps header comment`() {
        val text = """
            # 本层由插件通过 --patch 覆盖，不在此修改
            - id: llm-pi-ai
              config:
                providers:
                  old:
                    apiKeyEnv: OLD_KEY
        """.trimIndent() + "\n"
        val out = manager.upsertPatchEntry(
            text, "llm-pi-ai",
            "- id: llm-pi-ai\n  config:\n    providers:\n      new:\n        apiKeyEnv: NEW_KEY",
        )
        assertTrue(out.startsWith("# 本层由插件通过 --patch 覆盖，不在此修改\n"), "header comment preserved")
        assertTrue(out.contains("new:"))
        assertFalse(out.contains("OLD_KEY"), "replaced entry is gone (全量镜像)")
    }

    @Test
    fun `upsert appends missing entry to empty list`() {
        val out = manager.upsertPatchEntry("[]\n", "llm-pi-ai", "- id: llm-pi-ai\n  config:\n    providers: {}")
        assertFalse(out.contains("[]"), "placeholder replaced by real list")
        assertTrue(out.startsWith("- id: llm-pi-ai"))
    }

    @Test
    fun `upsert is idempotent on same content`() {
        val entry = "- id: agent-default-model\n  config:\n    provider: xiaomi-token-plan-cn"
        val text = "- id: llm-pi-ai\n  config: {}\n"
        val once = manager.upsertPatchEntry(text, "agent-default-model", entry)
        val twice = manager.upsertPatchEntry(once, "agent-default-model", entry)
        assertEquals(once, twice)
    }

    @Test
    fun `upsert with null entry deletes it`() {
        val text = "- id: llm-pi-ai\n  config: {}\n- id: agent-default-model\n  k: v\n"
        val out = manager.upsertPatchEntry(text, "llm-pi-ai", null)
        assertFalse(out.contains("llm-pi-ai"))
        assertTrue(out.contains("agent-default-model"), "other entries survive")
        assertEquals(out, manager.upsertPatchEntry(out, "llm-pi-ai", null), "deleting absent entry is noop")
    }

    @Test
    fun `upsert matches quoted id`() {
        val text = "- id: 'llm-pi-ai'\n  config:\n    providers:\n      old:\n        apiKeyEnv: OLD\n"
        val out = manager.upsertPatchEntry(
            text, "llm-pi-ai",
            "- id: 'llm-pi-ai'\n  config:\n    providers:\n      new:\n        apiKeyEnv: NEW",
        )
        assertFalse(out.contains("OLD"), "quoted-id entry replaced")
        assertTrue(out.contains("NEW"))
    }
}
