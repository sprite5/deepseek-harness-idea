package com.deepseek.harness.idea.bridge

import java.nio.charset.StandardCharsets

/**
 * 读取插件资源中的 MCP server 脚本（mcp-ide-server.mjs）。
 * 资源位于 src/main/resources/，随插件 jar 打包；测试环境从 classpath 读取。
 */
object IdeBridgeResources {

    const val MCP_SERVER_RESOURCE = "/mcp-ide-server.mjs"

    /** 返回脚本内容；资源缺失时返回 null（调用方降级）。 */
    fun mcpServerScript(): String? =
        try {
            IdeBridgeResources::class.java.getResourceAsStream(MCP_SERVER_RESOURCE)?.use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
}
