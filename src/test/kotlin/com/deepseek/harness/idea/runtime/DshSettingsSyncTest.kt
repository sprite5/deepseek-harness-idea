package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 纯逻辑测试：DshHomeManager 的 `llm-pi-ai:` 节回写算法。
 *
 * 测试范围（不依赖 PathManager / ApplicationManager）：
 * - [extractTopLevelSection]：抽节 / 找不到 / 空节 / 顶层 vs 嵌套 key
 * - [replaceTopLevelSection]：空文件追加 / 与其它顶层节共存 / 全量替换 / 幂等
 *
 * 不测 [syncProvidersToGlobal]（依赖 PathManager.getConfigDir() → 测试环境无 IDE 上下文）；
 * 其端到端行为由工具窗口集成测试覆盖。
 *
 * 函数为 `internal`：测试与主源集同 module，Kotlin `internal` 可见性允许直接调用，
 * 无需反射（反射会被 Kotlin 顶层 internal 函数的 name mangling 干扰）。
 */
class DshSettingsSyncTest {

    private val manager: DshHomeManager =
        DshHomeManager::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()

    // ───────── extractTopLevelSection ─────────

    @Test
    fun `extract llm-pi-ai section from typical settings yaml`() {
        val text = """
            ui-onboarding:
              welcomeNoticeVersion: "2026-08-13.1"

            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: ARK_PLAN_API_KEY
                  displayName: 火山方舟 PLAN
                  api: openai-completions
                  baseURL: https://ark.cn-beijing.volces.com/api/v3
                  models:
                    - id: doubao-pro-32k
                      contextWindow: 32000

            other-section:
              foo: bar
        """.trimIndent() + "\n"

        val section = manager.extractTopLevelSection(text, "llm-pi-ai:")
        assertNotNull(section)
        val body = section!!.body
        assertTrue(body.startsWith("llm-pi-ai:"))
        assertTrue(body.contains("ark-plan:"))
        assertTrue(body.contains("doubao-pro-32k"))
        assertFalse(body.contains("other-section"))
        assertFalse(body.contains("ui-onboarding"))
    }

    @Test
    fun `extract returns null when section missing`() {
        val text = "ui-onboarding:\n  welcomeNoticeVersion: \"v1\"\n"
        assertNull(manager.extractTopLevelSection(text, "llm-pi-ai:"))
    }

    @Test
    fun `extract handles section with no children`() {
        val text = "llm-pi-ai:\nui-onboarding:\n  welcomeNoticeVersion: \"v1\"\n"
        val section = manager.extractTopLevelSection(text, "llm-pi-ai:")
        assertNotNull(section)
        assertEquals("llm-pi-ai:", section!!.body)
        assertFalse(section.body.contains("ui-onboarding"))
    }

    @Test
    fun `extract ignores keys that are not at top level`() {
        val text = """
            outer:
              llm-pi-ai:
                fake: true
            llm-pi-ai:
              providers: {}
        """.trimIndent() + "\n"
        val section = manager.extractTopLevelSection(text, "llm-pi-ai:")
        assertNotNull(section)
        assertFalse(section!!.body.contains("fake: true"))
    }

    @Test
    fun `extract handles section at end of file`() {
        val text = """
            ui-onboarding:
              welcomeNoticeVersion: "v1"

            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: K
        """.trimIndent() + "\n"
        val section = manager.extractTopLevelSection(text, "llm-pi-ai:")
        assertNotNull(section)
        assertTrue(section!!.body.contains("ark-plan:"))
        assertFalse(section.body.contains("ui-onboarding"))
    }

    @Test
    fun `extract handles CRLF line endings`() {
        val text = "ui-onboarding:\r\n  welcomeNoticeVersion: \"v1\"\r\n\r\nllm-pi-ai:\r\n  providers: {}\r\n"
        val section = manager.extractTopLevelSection(text, "llm-pi-ai:")
        assertNotNull(section)
        assertFalse(section!!.body.contains('\r'))
    }

    // ───────── replaceTopLevelSection ─────────

    @Test
    fun `replace appends to empty file`() {
        val out = manager.replaceTopLevelSection("", "llm-pi-ai:", "llm-pi-ai:\n  providers: {}\n")
        assertEquals("llm-pi-ai:\n  providers: {}\n", out)
    }

    @Test
    fun `replace appends to file with other top-level sections`() {
        val text = "ui-onboarding:\n  welcomeNoticeVersion: \"v1\"\n"
        val out = manager.replaceTopLevelSection(text, "llm-pi-ai:", "llm-pi-ai:\n  providers: {}\n")
        assertTrue(out.contains("ui-onboarding:"))
        assertTrue(out.contains("welcomeNoticeVersion: \"v1\""))
        assertTrue(out.contains("llm-pi-ai:"))
        assertTrue(out.contains("providers: {}"))
    }

    @Test
    fun `replace preserves ui-onboarding and replaces llm-pi-ai section`() {
        val text = """
            ui-onboarding:
              welcomeNoticeVersion: "2026-08-13.1"

            llm-pi-ai:
              providers:
                old-provider:
                  apiKeyEnv: OLD_KEY

            other-section:
              foo: bar
        """.trimIndent() + "\n"
        val newBody = """
            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: ARK_PLAN_API_KEY
        """.trimIndent()
        val out = manager.replaceTopLevelSection(text, "llm-pi-ai:", newBody)
        assertTrue(out.contains("ui-onboarding:"), "ui-onboarding must survive")
        assertTrue(out.contains("welcomeNoticeVersion: \"2026-08-13.1\""), "version preserved verbatim")
        assertTrue(out.contains("ark-plan:"), "new provider present")
        assertFalse(out.contains("old-provider:"), "old provider removed (全量替换)")
        assertTrue(out.contains("other-section:"), "trailing section preserved")
    }

    @Test
    fun `replace with empty body wipes providers`() {
        // 节被「清空」语义：调用方传 normalizedNew="" 时，本算法把整段替换为空串。
        // 实际场景中 syncProvidersToGlobal 不会传空 body（只在 project 文件缺节时 noop），
        // 但该测试保证 replace 自身的鲁棒性。
        val text = """
            ui-onboarding:
              welcomeNoticeVersion: "v1"

            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: K
        """.trimIndent() + "\n"
        val out = manager.replaceTopLevelSection(text, "llm-pi-ai:", "")
        assertTrue(out.contains("ui-onboarding:"))
        assertFalse(out.contains("ark-plan:"))
        assertFalse(out.contains("apiKeyEnv: K"))
    }

    @Test
    fun `replace is idempotent on same content`() {
        val text = """
            ui-onboarding:
              welcomeNoticeVersion: "v1"
            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: K
        """.trimIndent() + "\n"
        val newBody = """
            llm-pi-ai:
              providers:
                ark-plan:
                  apiKeyEnv: K
        """.trimIndent()
        val once = manager.replaceTopLevelSection(text, "llm-pi-ai:", newBody)
        val twice = manager.replaceTopLevelSection(once, "llm-pi-ai:", newBody)
        assertEquals(once, twice)
    }

    @Test
    fun `replace on section that is the only content`() {
        val text = "llm-pi-ai:\n  providers: {}\n"
        val newBody = "llm-pi-ai:\n  providers:\n    ark-plan: {}\n"
        val out = manager.replaceTopLevelSection(text, "llm-pi-ai:", newBody)
        assertFalse(out.contains("providers: {}"))
        assertTrue(out.contains("ark-plan"))
    }
}
