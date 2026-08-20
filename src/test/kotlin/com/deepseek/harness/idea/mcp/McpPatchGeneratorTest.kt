package com.deepseek.harness.idea.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpPatchGeneratorTest {

    @Test
    fun `generates insert syntax with dynamic port`() {
        val patch = McpPatchGenerator.generate(3187)
        assertTrue(patch.contains("- insert:"), "must use insert syntax: $patch")
        assertTrue(patch.contains("id: mcp.ide"), "must declare mcp.ide id")
        assertTrue(patch.contains("name: '@deepseek-ai/dsh-mcp-client'"), "must reference the mcp-client plugin")
        assertTrue(patch.contains("serverName: ide"))
        assertTrue(patch.contains("transport: streamable-http"))
        assertTrue(patch.contains("url: http://127.0.0.1:3187/mcp"), "must contain dynamic mcp port")
        assertTrue(patch.contains("toolCallTimeoutMs: 60000"))
        assertTrue(patch.contains("reconnect:"))
        assertTrue(patch.contains("maxAttempts: 3"))
    }

    @Test
    fun `port appears exactly once in url`() {
        val patch = McpPatchGenerator.generate(9999)
        // url 中的端口恰好出现一次（生成逻辑不重复拼接）
        val urlMatches = Regex("""http://127\.0\.0\.1:9999/mcp""").findAll(patch).count()
        assertTrue(urlMatches == 1, "url with port should appear exactly once: $patch")
    }

    @Test
    fun `strict variant enables failOnStartupError`() {
        val patch = McpPatchGenerator.generateStrict(1234)
        assertTrue(patch.contains("failOnStartupError: true"))
    }

    @Test
    fun `generate and strict share base shape`() {
        val base = McpPatchGenerator.generate(5000)
        val strict = McpPatchGenerator.generateStrict(5000)
        assertTrue(base.contains("url: http://127.0.0.1:5000/mcp"))
        assertTrue(strict.contains("url: http://127.0.0.1:5000/mcp"))
        assertTrue(!base.contains("failOnStartupError"))
    }
}
