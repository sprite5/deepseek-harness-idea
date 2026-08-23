package com.deepseek.harness.idea.mcp

/**
 * 生成 `ide.yml` patch 内容（Step 3，见 docs/DESIGN.md §3.6/§4.5）。
 *
 * 语法基于实测的 cordis loader patch：顶层 YAML 数组，`insert` 条目把 mcp-client
 * 实例插入 root entry 列表（`--patch` 覆盖层只能修改已有条目或 insert，不能新增顶层条目）。
 *
 * 生成示例（YAML）：
 * ```yaml
 * - insert:
 *     - id: mcp.ide
 *       name: '@deepseek-ai/dsh-mcp-client'
 *       config:
 *         serverName: ide
 *         transport: streamable-http
 *         url: http://127.0.0.1:<mcpPort>/mcp
 *         toolCallTimeoutMs: 60000
 *         reconnect:
 *           enabled: true
 *           maxAttempts: 3
 * ```
 */
object McpPatchGenerator {

    const val SERVER_NAME = "ide"
    private const val PLUGIN = "@deepseek-ai/dsh-mcp-client"
    private const val TOOL_TIMEOUT_MS = 60000
    private const val RECONNECT_MAX_ATTEMPTS = 3

    /**
     * 根据 MCP server 地址生成 patch 文本（mcpPort 动态填入）。
     * [globalConfigDir] 非空时，追加 `$settings`/`$credentials`（cordis patch 的"修改已有单元"语法，
     * 按 id 覆盖其 config）把 dsh 的设置/凭据 path 指向全局唯一配置文件 —— 实现"配置共享 + 数据隔离"（方案 C）。
     */
    fun generate(mcpPort: Int, host: String = "127.0.0.1", globalConfigDir: String = ""): String {
        val url = "http://$host:$mcpPort/mcp"
        return buildString {
            appendLine("- insert:")
            appendLine("    - id: mcp.$SERVER_NAME")
            appendLine("      name: '$PLUGIN'")
            appendLine("      config:")
            appendLine("        serverName: $SERVER_NAME")
            appendLine("        transport: streamable-http")
            appendLine("        url: $url")
            appendLine("        toolCallTimeoutMs: $TOOL_TIMEOUT_MS")
            appendLine("        reconnect:")
            appendLine("          enabled: true")
            appendLine("          maxAttempts: $RECONNECT_MAX_ATTEMPTS")
            if (globalConfigDir.isNotBlank()) {
                val g = globalConfigDir.replace('\\', '/')
                appendLine("- \$settings:")
                appendLine("    config:")
                appendLine("        path: '$g/settings.yaml'")
                appendLine("- \$credentials:")
                appendLine("    config:")
                appendLine("        path: '$g/.credentials.yaml'")
            }
        }
    }

    /** 生成带 failOnStartupError 的严格形态（测试/诊断用）：连接或工具同步失败即拒绝启动。 */
    fun generateStrict(mcpPort: Int, host: String = "127.0.0.1", globalConfigDir: String = ""): String {
        val base = generate(mcpPort, host, globalConfigDir)
        return base.replace(
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n",
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n        failOnStartupError: true\n"
        )
    }
}
